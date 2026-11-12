package com.llmmanager.android.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.AndroidApp
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.LaunchResponse
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.ServerClient
import com.llmmanager.android.data.SettingsStore
import com.llmmanager.android.update.AppUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val settings: SettingsStore,
    private val client: ServerClient,
    private val credentials: Credentials,
    private val updater: AppUpdater,
    defaultServerUrl: String,
) : ViewModel() {

    sealed interface Connection {
        data object Loading : Connection
        data object NotConfigured : Connection
        data object Online : Connection
        data class NoRoute(val detail: String) : Connection

        /** No usable token, and the server did not pair this phone: [reason] says why. */
        data class NeedsToken(val reason: String?) : Connection
        data class Failed(val message: String) : Connection
    }

    data class UiState(
        val serverUrl: String? = null,
        val hasToken: Boolean = false,
        /** The name the server paired this phone as; null when the token was typed in. */
        val pairedAs: String? = null,
        val connection: Connection = Connection.Loading,
        val data: ProjectsResponse? = null,
        val refreshing: Boolean = false,
        /** Project id with a launch in flight. */
        val launching: String? = null,
        /** An update is downloading. */
        val updating: Boolean = false,
    )

    sealed interface Event {
        data class Message(val text: String) : Event
        data class Started(val projectName: String, val launch: LaunchResponse) : Event
        data object NeedToken : Event

        /** Android needs the user's permission (once) before this app may install its update. */
        data class AllowInstalls(val settings: Intent) : Event
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    // One-shot effects: a Channel buffers them until the UI collects, unlike a SharedFlow.
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.serverUrl, settings.hasToken, settings.pairedAs) { url, token, paired -> Triple(url, token, paired) }
                .collect { (url, token, paired) ->
                    if (url == null && ServerAddress.parse(defaultServerUrl) is ServerAddress.Parsed.Valid) {
                        settings.setServerUrl(defaultServerUrl)
                        return@collect
                    }
                    val changed = url != mutableState.value.serverUrl
                    mutableState.update { it.copy(serverUrl = url, hasToken = token, pairedAs = paired) }
                    if (changed) refresh()
                }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        val server = currentServer() ?: run {
            mutableState.update { it.copy(connection = Connection.NotConfigured, data = null) }
            return
        }
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true) }
            // Pairs first when this phone has no token yet, and again when the server rejects the saved one.
            val connection = when (val result = credentials.call(server) { client.projects(server, it) }) {
                is ApiResult.Ok -> {
                    mutableState.update { it.copy(data = result.value) }
                    Connection.Online
                }
                is ApiResult.NoRoute -> Connection.NoRoute(result.detail)
                ApiResult.Unauthorized -> Connection.NeedsToken(credentials.refusal)
                is ApiResult.Failed -> Connection.Failed(result.message)
            }
            mutableState.update { it.copy(connection = connection, refreshing = false, data = it.data.takeUnless { connection is Connection.NeedsToken }) }
        }
    }

    fun startCodex(project: Project) {
        val server = currentServer() ?: return
        if (mutableState.value.launching != null) return
        viewModelScope.launch {
            mutableState.update { it.copy(launching = project.id) }
            val event = when (val result = credentials.call(server) { client.startCodex(server, project.id, it) }) {
                is ApiResult.Ok -> Event.Started(project.name, result.value)
                ApiResult.Unauthorized -> Event.NeedToken
                is ApiResult.NoRoute -> Event.Message("Can't reach the server — is Tailscale connected?")
                is ApiResult.Failed -> Event.Message(result.message)
            }
            mutableState.update { it.copy(launching = null) }
            eventChannel.send(event)
            if (event is Event.Started) refresh()
        }
    }

    /** Push, pull request or merge for an agent [branch] of [project]; the server validates everything. */
    fun publish(project: Project, branch: String, action: String) {
        val server = currentServer() ?: return
        viewModelScope.launch {
            val event = when (val result = credentials.call(server) { client.branchAction(server, it, project.id, action, branch) }) {
                is ApiResult.Ok -> Event.Message(result.value)
                ApiResult.Unauthorized -> Event.NeedToken
                is ApiResult.NoRoute -> Event.Message("Can't reach the server — is Tailscale connected?")
                is ApiResult.Failed -> Event.Message(result.message)
            }
            eventChannel.send(event)
            refresh()
        }
    }

    /** Downloads [app] from the server and hands it to Android's installer, which asks the user to confirm. */
    fun update(app: AndroidApp) {
        val server = currentServer() ?: return
        if (mutableState.value.updating) return
        viewModelScope.launch {
            mutableState.update { it.copy(updating = true) }
            val event = when (val outcome = updater.install(server, app)) {
                AppUpdater.Outcome.NeedsPermission -> Event.AllowInstalls(updater.permissionIntent())
                AppUpdater.Outcome.Started -> Event.Message("Confirm the update to version ${app.versionName ?: app.versionCode}")
                is AppUpdater.Outcome.Failed -> Event.Message(outcome.message)
            }
            mutableState.update { it.copy(updating = false) }
            eventChannel.send(event)
        }
    }

    /** Saves the address and, when given, a typed-in token. Returns an error to show, or null. */
    fun saveSettings(url: String, token: String?): String? {
        val parsed = ServerAddress.parse(url)
        if (parsed is ServerAddress.Parsed.Invalid) return parsed.reason
        val address = (parsed as ServerAddress.Parsed.Valid).address
        viewModelScope.launch {
            settings.setServerUrl(address.baseUrl)
            token?.trim()?.takeIf { it.isNotEmpty() }?.let { settings.saveToken(it) }
            eventChannel.send(Event.Message("Settings saved"))
            refresh()
        }
        return null
    }

    fun forgetToken() {
        viewModelScope.launch {
            settings.clearToken()
            eventChannel.send(Event.Message("Token removed from this phone"))
        }
    }

    private fun currentServer(): ServerAddress? =
        mutableState.value.serverUrl?.let { (ServerAddress.parse(it) as? ServerAddress.Parsed.Valid)?.address }
}
