package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
import com.llmmanager.android.data.Credentials
import com.llmmanager.android.data.JobRequest
import com.llmmanager.android.data.RunLog
import com.llmmanager.android.data.ScheduleResponse
import com.llmmanager.android.data.ServerAddress
import com.llmmanager.android.data.ServerClient
import com.llmmanager.android.data.SettingsStore
import com.llmmanager.android.data.UsageResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Schedule and usage tabs. Shares the server address and token with the overview. */
class ScheduleViewModel(
    private val settings: SettingsStore,
    private val client: ServerClient,
    private val credentials: Credentials,
) : ViewModel() {

    data class State(
        val usage: UsageResponse? = null,
        val usageError: String? = null,
        val schedule: ScheduleResponse? = null,
        val scheduleError: String? = null,
        val needsToken: Boolean = false,
        val loading: Boolean = false,
        val log: RunLog? = null,
        val submitting: Boolean = false,
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

    fun refreshUsage() = launchWithServer { server ->
        val result = credentials.call(server) { client.usage(server, it) }
        mutableState.update {
            when (result) {
                is ApiResult.Ok -> it.copy(usage = result.value, usageError = null)
                ApiResult.Unauthorized -> it.copy(needsToken = true, usageError = "Enter the access token to see usage")
                else -> it.copy(usageError = describe(result))
            }
        }
    }

    fun refreshSchedule() = launchWithServer { server ->
        val result = credentials.call(server) { client.schedule(server, it) }
        mutableState.update {
            when (result) {
                is ApiResult.Ok -> it.copy(schedule = result.value, scheduleError = null, needsToken = false)
                ApiResult.Unauthorized -> it.copy(needsToken = true)
                else -> it.copy(scheduleError = describe(result))
            }
        }
    }

    /** Creates a job; [message] replaces the default confirmation ("Started" or "Scheduled"). */
    fun create(request: JobRequest, message: String? = null, onCreated: () -> Unit) = launchWithServer { server ->
        mutableState.update { it.copy(submitting = true) }
        val result = credentials.call(server) { client.createJob(server, it, request) }
        mutableState.update { it.copy(submitting = false) }
        if (result is ApiResult.Ok) {
            onCreated()
            messages.send(message ?: if (request.schedule == null) "Started" else "Scheduled")
            refreshSchedule()
        } else {
            messages.send(describe(result))
        }
    }

    fun jobAction(jobId: String, action: String) = launchWithServer { server ->
        val result = credentials.call(server) { client.jobAction(server, it, jobId, action) }
        messages.send(if (result is ApiResult.Ok) "Done" else describe(result))
        refreshSchedule()
    }

    fun cancel(runId: String) = launchWithServer { server ->
        val result = credentials.call(server) { client.cancelRun(server, it, runId) }
        messages.send(if (result is ApiResult.Ok) "Cancelled" else describe(result))
        refreshSchedule()
    }

    fun openLog(runId: String) = launchWithServer { server ->
        when (val result = credentials.call(server) { client.runLog(server, it, runId) }) {
            is ApiResult.Ok -> mutableState.update { it.copy(log = result.value) }
            else -> messages.send(describe(result))
        }
    }

    fun closeLog() = mutableState.update { it.copy(log = null) }

    private fun launchWithServer(block: suspend (ServerAddress) -> Unit) {
        val target = server ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            block(target)
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Ok -> "OK"
        is ApiResult.NoRoute -> "Can't reach the server — is Tailscale connected?"
        ApiResult.Unauthorized -> "The access token was refused"
        is ApiResult.Failed -> result.message
    }
}
