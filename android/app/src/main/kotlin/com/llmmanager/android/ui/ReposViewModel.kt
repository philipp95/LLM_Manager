package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.CloneStatus
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.RepoClient
import com.llmmanager.android.data.RepoPreference
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.SettingsStore
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

/** Arranging the home screen (pins and stars) and adding repositories by their address. */
class ReposViewModel(
    private val settings: SettingsStore,
    private val client: RepoClient,
    private val credentials: Credentials,
) : ViewModel() {

    data class State(
        /** Pins and stars changed on this phone, shown at once while the server saves them. */
        val changed: Map<String, RepoPreference> = emptyMap(),
        val clones: List<CloneStatus> = emptyList(),
        val adding: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()

    private var server: ServerAddress? = null
    private var watching: Job? = null

    init {
        viewModelScope.launch {
            settings.serverUrl.collect { url -> server = url?.let { (ServerAddress.parse(it) as? ServerAddress.Parsed.Valid)?.address } }
        }
    }

    fun prefer(project: Project, preference: RepoPreference) {
        val target = server ?: return
        mutableState.update { it.copy(changed = it.changed + (project.id to preference)) }
        viewModelScope.launch {
            val result = credentials.call(target) { client.prefer(target, it, project.id, preference) }
            if (result !is ApiResult.Ok) {
                mutableState.update { it.copy(changed = it.changed - project.id) }
                messages.send(describe(result))
            }
        }
    }

    /** Asks the server to clone [url]; [onStarted] clears the form, [onCloned] runs when a clone has finished. */
    fun add(url: String, onStarted: () -> Unit, onCloned: () -> Unit) {
        val target = server ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(adding = true) }
            val result = credentials.call(target) { client.clone(target, it, url.trim()) }
            mutableState.update { it.copy(adding = false) }
            if (result is ApiResult.Ok) {
                onStarted()
                messages.send("Cloning ${result.value.name.ifEmpty { "the repository" }} on the server")
                watch(onCloned)
            } else {
                messages.send(describe(result))
            }
        }
    }

    /** Follows the server's clones until none is running; [onCloned] runs each time one has finished. */
    fun watch(onCloned: () -> Unit) {
        val target = server ?: return
        if (watching?.isActive == true) return
        watching = viewModelScope.launch {
            var running = -1
            while (running != 0) {
                val result = credentials.call(target) { client.clones(target, it) }
                if (result !is ApiResult.Ok) break
                mutableState.update { it.copy(clones = result.value) }
                val now = result.value.count { it.state == "cloning" }
                if (running > now) onCloned()
                running = now
                if (running > 0) delay(CLONE_POLL_MS)
            }
        }
    }

    private companion object {
        const val CLONE_POLL_MS = 3_000L
    }
}

/** Pinned first, then the most stars, otherwise the server's order; with this phone's unsaved changes applied. */
fun List<Project>.arranged(changed: Map<String, RepoPreference>): List<Project> =
    map { project -> changed[project.id]?.let { project.copy(pinned = it.pinned, stars = it.stars) } ?: project }
        .sortedWith(compareByDescending<Project> { it.pinned }.thenByDescending { it.stars })
