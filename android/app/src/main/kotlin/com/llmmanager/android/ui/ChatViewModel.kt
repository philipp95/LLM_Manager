package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.ChatClient
import com.llmmanager.android.data.ChatRequest
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sessions of the repository on screen, and sending messages to them. */
class ChatViewModel(
    private val settings: SettingsStore,
    private val client: ChatClient,
    private val credentials: Credentials,
) : ViewModel() {

    data class State(
        val projectId: String? = null,
        /** Null until loaded. */
        val chats: List<ChatView>? = null,
        val error: String? = null,
        val sending: Boolean = false,
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

    fun load(projectId: String) {
        val target = server ?: return
        // Another repository's sessions are never shown for this one, not even while loading.
        if (mutableState.value.projectId != projectId) mutableState.value = State(projectId = projectId)
        viewModelScope.launch {
            val result = credentials.call(target) { client.chats(target, it, projectId) }
            mutableState.update {
                when {
                    it.projectId != projectId -> it
                    result is ApiResult.Ok -> it.copy(chats = result.value, error = null)
                    else -> it.copy(error = describe(result))
                }
            }
        }
    }

    /** Sends [message]; [onSent] gets the session it went to, so the screen can show it. */
    fun send(message: ChatRequest, onSent: (ChatView) -> Unit) {
        val target = server ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(sending = true) }
            val result = credentials.call(target) { client.send(target, it, message) }
            mutableState.update { it.copy(sending = false) }
            if (result is ApiResult.Ok) {
                onSent(result.value)
                messages.send("${agentLabel(result.value.agent)} is on it")
                load(message.projectId)
            } else {
                messages.send(describe(result))
            }
        }
    }
}
