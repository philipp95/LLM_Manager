package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.LaunchResponse
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.ServerClient
import com.llmmanager.android.data.SettingsStore
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
    defaultServerUrl: String,
) : ViewModel() {

    sealed interface Connection {
        data object Loading : Connection
        data object NotConfigured : Connection
        data object Online : Connection
        data class NoRoute(val detail: String) : Connection

        /** No token saved yet, or the server rejected it ([refused]). */
        data class NeedsToken(val refused: Boolean) : Connection
        data class Failed(val message: String) : Connection
    }

    data class UiState(
        val serverUrl: String? = null,
        val hasToken: Boolean = false,
        val connection: Connection = Connection.Loading,
        val data: ProjectsResponse? = null,
        val refreshing: Boolean = false,
        /** Project id with a launch in flight. */
        val launching: String? = null,
    )

    sealed interface Event {
        data class Message(val text: String) : Event
        data class Started(val projectName: String, val launch: LaunchResponse) : Event
        data object NeedToken : Event
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    // One-shot effects: a Channel buffers them until the UI collects, unlike a SharedFlow.
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.serverUrl, settings.hasToken) { url, token -> url to token }.collect { (url, token) ->
                if (url == null && ServerAddress.parse(defaultServerUrl) is ServerAddress.Parsed.Valid) {
                    settings.setServerUrl(defaultServerUrl)
                    return@collect
                }
                val changed = url != mutableState.value.serverUrl
                mutableState.update { it.copy(serverUrl = url, hasToken = token) }
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
            val token = settings.token()
            if (token == null) {
                mutableState.update { it.copy(connection = Connection.NeedsToken(refused = false), data = null) }
                return@launch
            }
            mutableState.update { it.copy(refreshing = true) }
            val connection = when (val result = client.projects(server, token)) {
                is ApiResult.Ok -> {
                    mutableState.update { it.copy(data = result.value) }
                    Connection.Online
                }
                is ApiResult.NoRoute -> Connection.NoRoute(result.detail)
                ApiResult.Unauthorized -> Connection.NeedsToken(refused = true)
                is ApiResult.Failed -> Connection.Failed(result.message)
            }
            mutableState.update { it.copy(connection = connection, refreshing = false) }
        }
    }

    fun startCodex(project: Project) {
        val server = currentServer() ?: return
        if (mutableState.value.launching != null) return
        viewModelScope.launch {
            val token = settings.token()
            if (token == null) {
                eventChannel.send(Event.NeedToken)
                return@launch
            }
            mutableState.update { it.copy(launching = project.id) }
            val event = when (val result = client.startCodex(server, project.id, token)) {
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
            val token = settings.token()
            if (token == null) {
                eventChannel.send(Event.NeedToken)
                return@launch
            }
            val event = when (val result = client.branchAction(server, token, project.id, action, branch)) {
                is ApiResult.Ok -> Event.Message(result.value)
                ApiResult.Unauthorized -> Event.NeedToken
                is ApiResult.NoRoute -> Event.Message("Can't reach the server — is Tailscale connected?")
                is ApiResult.Failed -> Event.Message(result.message)
            }
            eventChannel.send(event)
            refresh()
        }
    }

    /** Saves the address and, when given, a new token. Returns an error to show, or null. */
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
            eventChannel.send(Event.Message("Access token removed from this phone"))
        }
    }

    private fun currentServer(): ServerAddress? =
        mutableState.value.serverUrl?.let { (ServerAddress.parse(it) as? ServerAddress.Parsed.Valid)?.address }
}
