package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.SettingsClient
import com.llmmanager.android.data.SettingsStore
import com.llmmanager.android.data.SignInStep
import com.llmmanager.android.data.SignInView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sign-in pop-up. The agent's own CLI signs in on the server; this follows it, every two
 * seconds while it runs, also while the person is in the browser signing in, so the pop-up is
 * up to date when they come back.
 */
class SignInViewModel(
    private val settings: SettingsStore,
    private val client: SettingsClient,
    private val credentials: Credentials,
) : ViewModel() {

    data class State(
        /** The agent being signed in; null when the pop-up is closed. */
        val agent: String? = null,
        val view: SignInView? = null,
        /** A problem with what was typed (a code or a key), shown under the field. */
        val note: String? = null,
        val busy: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Agents that just got signed in, so the screens showing them refresh. */
    private val signedInChannel = Channel<String>(Channel.BUFFERED)
    val signedIn: Flow<String> = signedInChannel.receiveAsFlow()

    private var server: ServerAddress? = null
    private var following: Job? = null
    private var afterSignIn: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            settings.serverUrl.collect { url -> server = url?.let { (ServerAddress.parse(it) as? ServerAddress.Parsed.Valid)?.address } }
        }
    }

    /** Opens the pop-up for [agent]; [then] runs once it is signed in, e.g. sending the message that needed it. */
    fun open(agent: String, then: (() -> Unit)? = null) {
        afterSignIn = then
        mutableState.value = State(agent = agent, view = SignInView(agent, "starting"))
        follow(SignInStep.Start())
    }

    fun restart() = follow(SignInStep.Start(restart = true))

    fun submit(code: String) {
        if (code.isBlank()) return
        follow(SignInStep.Code(code.trim()))
    }

    /** Signs in with an API key instead, and ends the sign-in that was running. */
    fun useApiKey(key: String) {
        val agent = mutableState.value.agent ?: return
        val target = server ?: return
        if (key.isBlank()) return
        following?.cancel()
        following = viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            val result = credentials.call(target) { client.useApiKey(target, it, agent, key.trim()) }
            mutableState.update { it.copy(busy = false) }
            if (result !is ApiResult.Ok) {
                mutableState.update { it.copy(note = describe(result)) }
                return@launch
            }
            if (mutableState.value.view?.live == true) credentials.call(target) { client.signIn(target, it, agent, SignInStep.Cancel) }
            finished(agent, SignInView(agent, "done", message = "Signed in with an API key"))
        }
    }

    /** Stops the sign-in on the server and closes the pop-up. */
    fun cancel() {
        val agent = mutableState.value.agent
        val target = server
        close()
        if (agent != null && target != null) viewModelScope.launch { credentials.call(target) { client.signIn(target, it, agent, SignInStep.Cancel) } }
    }

    /** Closes the pop-up; a sign-in still running on the server can be picked up again. */
    fun close() {
        following?.cancel()
        afterSignIn = null
        mutableState.value = State()
    }

    private fun follow(first: SignInStep) {
        val agent = mutableState.value.agent ?: return
        val target = server ?: return
        following?.cancel()
        following = viewModelScope.launch {
            var step: SignInStep? = first
            while (step != null) {
                val current = step
                if (current is SignInStep.Code) mutableState.update { it.copy(busy = true) }
                val view = when (val result = credentials.call(target) { client.signIn(target, it, agent, current) }) {
                    is ApiResult.Ok -> result.value
                    else -> SignInView(agent, "failed", message = describe(result))
                }
                mutableState.update { it.copy(view = view, busy = false, note = view.message.takeIf { view.state == "waiting" }) }
                if (view.state == "done") {
                    finished(agent, view)
                    break
                }
                step = if (view.live) SignInStep.Poll.also { delay(if (view.state == "starting") STARTING_POLL_MS else POLL_MS) } else null
            }
        }
    }

    private suspend fun finished(agent: String, view: SignInView) {
        mutableState.update { it.copy(view = view, note = null) }
        signedInChannel.send(agent)
        val then = afterSignIn
        afterSignIn = null
        then?.invoke()
        delay(SUCCESS_SHOWN_MS)
        if (mutableState.value.agent == agent && mutableState.value.view?.state == "done") mutableState.value = State()
    }

    private companion object {
        const val STARTING_POLL_MS = 700L
        const val POLL_MS = 2_000L
        const val SUCCESS_SHOWN_MS = 1_500L
    }
}
