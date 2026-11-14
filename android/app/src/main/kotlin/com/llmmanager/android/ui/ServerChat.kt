package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ScheduleResponse

/** Stands in for a repository where the chat expects one: the Server chat has no branch and no forge. */
private val SERVER_PROJECT = Project(id = SERVER_ID, name = "the server")

/**
 * The Server chat: the one place where an agent may change the server itself. Repository
 * sessions can change only their own repository; this one is not confined, and says so.
 */
@Composable
fun ServerChatScreen(chats: ChatViewModel.State, schedule: ScheduleResponse?, actions: ChatActions) {
    val chat = rememberChatScreen(SERVER_PROJECT, chats, schedule)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "warning") {
            Text(
                "This chat is not sandboxed. Its agent works as the server's user and can change anything that user can: " +
                    "LLM Manager itself, other repositories, the machine's settings. Use a repository's chat for work on that repository.",
                color = Palette.warnInk, fontSize = 13.5.sp,
                modifier = Modifier.fillMaxWidth().background(Palette.paper, RoundedCornerShape(14.dp)).padding(12.dp),
            )
        }
        chatItems(chat, actions)
    }
}

/** Whether [project] stands for the Server chat. */
val Project.isServer: Boolean get() = id == SERVER_ID

/** Where a session works: its branch, or the server itself. */
fun workplace(project: Project, session: ChatView): String = session.branch ?: if (project.isServer) "on the server" else "new branch"
