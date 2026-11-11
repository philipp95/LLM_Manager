package com.llmmanager.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.llmmanager.android.data.JobRequest
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Run

/** What the chat needs from the screen around it. */
class ChatActions(
    val onSend: (request: JobRequest, onSent: () -> Unit) -> Unit,
    val onLog: (Run) -> Unit,
    val onCancel: (Run) -> Unit,
    val onCopy: (String) -> Unit,
)

/** Everything the repository screen can do, routed to the view models by the caller. */
class RepoActions(
    val chat: ChatActions,
    val onStartCodex: () -> Unit,
    val onPublish: (branch: String, action: String) -> Unit,
)

/** Which dialog is open: one holder, so the screen and its parts can open them. */
class DialogState {
    var settings by mutableStateOf(false)
    var confirming by mutableStateOf<Project?>(null)
    var cancelling by mutableStateOf<Run?>(null)
    var started by mutableStateOf<DashboardViewModel.Event.Started?>(null)
}
