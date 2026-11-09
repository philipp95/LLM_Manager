package com.llmmanager.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmmanager.android.data.ApiResult
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
class ScheduleViewModel(private val settings: SettingsStore, private val client: ServerClient) : ViewModel() {

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

    fun refreshUsage() = withToken { server, token ->
        val result = client.usage(server, token)
        mutableState.update {
            when (result) {
                is ApiResult.Ok -> it.copy(usage = result.value, usageError = null)
                ApiResult.Unauthorized -> it.copy(needsToken = true, usageError = "Enter the access token to see usage")
                else -> it.copy(usageError = describe(result))
            }
        }
    }

    fun refreshSchedule() = withToken { server, token ->
        val result = client.schedule(server, token)
        mutableState.update {
            when (result) {
                is ApiResult.Ok -> it.copy(schedule = result.value, scheduleError = null, needsToken = false)
                ApiResult.Unauthorized -> it.copy(needsToken = true)
                else -> it.copy(scheduleError = describe(result))
            }
        }
    }

    fun create(request: JobRequest, onCreated: () -> Unit) = withToken { server, token ->
        mutableState.update { it.copy(submitting = true) }
        val result = client.createJob(server, token, request)
        mutableState.update { it.copy(submitting = false) }
        if (result is ApiResult.Ok) {
            onCreated()
            messages.send(if (request.schedule == null) "Started" else "Scheduled")
            refreshSchedule()
        } else {
            messages.send(describe(result))
        }
    }

    fun jobAction(jobId: String, action: String) = withToken { server, token ->
        val result = client.jobAction(server, token, jobId, action)
        messages.send(if (result is ApiResult.Ok) "Done" else describe(result))
        refreshSchedule()
    }

    fun cancel(runId: String) = withToken { server, token ->
        val result = client.cancelRun(server, token, runId)
        messages.send(if (result is ApiResult.Ok) "Cancelled" else describe(result))
        refreshSchedule()
    }

    fun openLog(runId: String) = withToken { server, token ->
        when (val result = client.runLog(server, token, runId)) {
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

    private fun withToken(block: suspend (ServerAddress, String) -> Unit) = launchWithServer { server ->
        val token = settings.token()
        if (token == null) mutableState.update { it.copy(needsToken = true) } else block(server, token)
    }

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Ok -> "OK"
        is ApiResult.NoRoute -> "Can't reach the server — is Tailscale connected?"
        ApiResult.Unauthorized -> "The access token was refused"
        is ApiResult.Failed -> result.message
    }
}
