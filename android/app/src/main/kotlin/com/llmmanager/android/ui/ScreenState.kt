package com.llmmanager.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.llmmanager.android.data.AndroidApp
import com.llmmanager.android.data.ChatRequest
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.ScheduleResponse
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Run

/** What the chat needs from the screen around it. */
class ChatActions(
    /** Sends a chat message; [onSent] gets the session it went to. */
    val onSend: (request: ChatRequest, onSent: (ChatView) -> Unit) -> Unit,
    val onLog: (Run) -> Unit,
    val onCancel: (Run) -> Unit,
    val onCopy: (String) -> Unit,
    /** Opens the sign-in pop-up for an agent; [then] runs once it is signed in. */
    val onSignIn: (agent: String, then: () -> Unit) -> Unit = { _, _ -> },
)

/** Everything the repository screen can do, routed to the view models by the caller. */
class RepoActions(
    val chat: ChatActions,
    val onStartCodex: () -> Unit,
    val onPublish: (branch: String, action: String) -> Unit,
)

/** What the message box sends to: the selected session, or a new one; and what the server says about agents. */
class ComposerTarget(val schedule: ScheduleResponse?, val sessions: List<ChatView>, val session: ChatView?)

/** The screen's view models, handed around together. */
class ViewModels(
    val dashboard: DashboardViewModel,
    val schedule: ScheduleViewModel,
    val settings: SettingsViewModel,
    val signIn: SignInViewModel,
    val chats: ChatViewModel,
)

/** What the sign-in pop-up can do, routed to [SignInViewModel] by the caller. */
class SignInActions(
    val onSubmit: (code: String) -> Unit,
    val onApiKey: (key: String) -> Unit,
    val onRestart: () -> Unit,
    val onCancel: () -> Unit,
    val onClose: () -> Unit,
    val onCopy: (String) -> Unit,
)

/** What the settings dialog shows about updates: the server's app, and how to get it. */
class UpdateState(val offered: AndroidApp?, val onUpdate: (AndroidApp) -> Unit)

/** Which dialog is open: one holder, so the screen and its parts can open them. */
class DialogState {
    var settings by mutableStateOf(false)
    var confirming by mutableStateOf<Project?>(null)
    var cancelling by mutableStateOf<Run?>(null)
    var started by mutableStateOf<DashboardViewModel.Event.Started?>(null)
}
