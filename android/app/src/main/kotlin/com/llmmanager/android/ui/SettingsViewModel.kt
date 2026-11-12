package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.AgentPreferenceRequest
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.ForgeTokenRequest
import com.llmmanager.android.data.ProfileRequest
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.SettingsClient
import com.llmmanager.android.data.SettingsResponse
import com.llmmanager.android.data.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Settings tab: profile, agent preferences and sign-outs, code-host tokens. */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val client: SettingsClient,
    private val credentials: Credentials,
) : ViewModel() {

    data class State(
        val data: SettingsResponse? = null,
        val error: String? = null,
        val loading: Boolean = false,
        val saving: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()

    private var server: ServerAddress? = null

    init {
        viewModelScope.launch {
            settings.serverUrl.collect { url -> server = url?.let { (ServerAddress.parse(it) as? ServerAddress.Parsed.Valid)?.address } }
        }
    }

    fun load() {
        val target = server ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val result = credentials.call(target) { client.load(target, it) }
            mutableState.update {
                if (result is ApiResult.Ok) it.copy(data = result.value, error = null, loading = false) else it.copy(error = describe(result), loading = false)
            }
        }
    }

    fun saveProfile(name: String, email: String, defaultAgent: String?) =
        change { server, token -> client.saveProfile(server, token, ProfileRequest(name.trim(), email.trim(), defaultAgent)) }

    fun saveAgent(agent: String, enabled: Boolean, model: String) =
        change { server, token -> client.saveAgent(server, token, agent, AgentPreferenceRequest(enabled, model.trim())) }

    fun signOut(agent: String) = change { server, token -> client.signOut(server, token, agent) }

    /** Checks the token with the code host and saves it on the server; [onSaved] closes the form. */
    fun connectForge(forge: String, request: ForgeTokenRequest, onSaved: () -> Unit) =
        change(onSaved) { server, token -> client.connectForge(server, token, forge, request) }

    fun disconnectForge(forge: String, host: String?) = change { server, token -> client.disconnectForge(server, token, forge, host) }

    private fun change(onOk: () -> Unit = {}, call: suspend (ServerAddress, String) -> ApiResult<SettingsResponse>) {
        val target = server ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true) }
            val result = credentials.call(target) { call(target, it) }
            mutableState.update { it.copy(saving = false) }
            if (result is ApiResult.Ok) {
                mutableState.update { it.copy(data = result.value, error = null) }
                onOk()
                messages.send(result.value.message ?: "Saved")
            } else {
                messages.send(describe(result))
            }
        }
    }
}

/** A failed call in words for the person. */
internal fun describe(result: ApiResult<*>): String = when (result) {
    is ApiResult.Ok -> "OK"
    is ApiResult.NoRoute -> "Can't reach the server — is Tailscale connected?"
    ApiResult.Unauthorized -> "The access token was refused"
    is ApiResult.Failed -> result.message
}
