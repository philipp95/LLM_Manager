package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatRequest
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ScheduleResponse

/**
 * The message box under the chat. With a session selected it continues that session with that
 * session's agent; otherwise it starts a new session on a new branch, or, when asked, on the
 * branch of another session. An agent that is not signed in on the server gets the sign-in
 * pop-up first, then the message goes. Text survives refreshes; it is cleared once sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(project: Project, target: ComposerTarget, sending: Boolean, actions: ChatActions, onSent: (ChatView) -> Unit) {
    val schedule = target.schedule
    val session = target.session
    // Until the schedule is loaded every agent is offered; the server refuses ones it lacks.
    val available = AGENT_ORDER.filter { schedule == null || schedule.agents[it] == true }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable(project.id) { mutableStateOf("") }
    var review by rememberSaveable(project.id) { mutableStateOf(false) }
    var shareWith by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    val agent = session?.agent ?: listOfNotNull(chosen, schedule?.defaultAgent).firstOrNull { it in available } ?: available.firstOrNull() ?: "claude"
    val reviewer = reviewerFor(agent, available)
    val shareable = target.sessions.filter { it.branch != null && it.open }
    val shared = shareable.firstOrNull { it.id == shareWith }.takeIf { session == null }

    if (session == null) NewSessionOptions(available, agent, shareable, shared?.id, onAgent = { chosen = it }, onShare = { shareWith = it })
    val placeholder = if (session != null) "Message ${agentLabel(agent)} in this session…"
    else "Ask ${agentLabel(agent)} to change something in ${project.name.substringAfterLast('/')}…"
    OutlinedTextField(
        value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        placeholder = { Text(placeholder) },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Palette.field, unfocusedContainerColor = Palette.field),
    )
    reviewer?.let { ReviewToggle(review, agent, it) { checked -> review = checked } }
    val blocked = blocked(session, agent)
    SendButton(sending, enabled = text.isNotBlank() && available.isNotEmpty() && blocked == null) {
        val request = ChatRequest(
            projectId = project.id, prompt = text.trim(), agent = agent, reviewer = reviewer.takeIf { review },
            chatId = session?.id, sameBranchAs = shared?.id, publish = project.forge != null,
        )
        val send = {
            actions.onSend(request) { sent ->
                text = ""
                review = false
                shareWith = null
                onSent(sent)
            }
        }
        val missing = listOfNotNull(request.agent, request.reviewer).filter { schedule?.needsSignIn(it) == true }
        signInThen(missing, actions.onSignIn, send)
    }
    val signIn = "${agentLabel(agent)} is not signed in on the server yet. Sending asks you to sign in first."
    val hint = blocked ?: signIn.takeIf { schedule?.needsSignIn(agent) == true } ?: composerHint(project, available, session, shared)
    Text(hint, color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Why the selected session cannot take a message now, or null. */
private fun blocked(session: ChatView?, agent: String): String? = when {
    session?.busy == true -> "${agentLabel(agent)} is still working on the last message. Send the next one when it's done."
    session?.open == false -> "This session's worktree was removed, so it can't continue. Start a new session."
    else -> null
}

/** For a new session: which agent, and which branch (a new one unless another session's is chosen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewSessionOptions(
    available: List<String>,
    agent: String,
    shareable: List<ChatView>,
    shared: String?,
    onAgent: (String) -> Unit,
    onShare: (String?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        available.forEach { option -> AgentOption(option, selected = option == agent) { onAgent(option) } }
    }
    if (shareable.isNotEmpty()) BranchChoice(shareable, shared, onShare)
}

/** Signs in each of [agents] in turn, then runs [then]. */
private fun signInThen(agents: List<String>, signIn: (String, () -> Unit) -> Unit, then: () -> Unit) {
    if (agents.isEmpty()) then() else signIn(agents.first()) { signInThen(agents.drop(1), signIn, then) }
}

/** A new session's branch: a new one, which is the default, or the branch of another session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchChoice(sessions: List<ChatView>, chosen: String?, onChoose: (String?) -> Unit) {
    Text("Branch", color = Palette.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = chosen == null, onClick = { onChoose(null) }, label = { Text("New branch") })
        sessions.forEach { other ->
            FilterChip(
                selected = chosen == other.id, onClick = { onChoose(other.id) },
                label = { Text("Same as “${other.title.take(CHOICE_TITLE)}”", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** The agent's usual reviewer when it is available, else any other available agent. */
private fun reviewerFor(agent: String, available: List<String>): String? {
    val others = available.filter { it != agent }
    return defaultReviewer(agent).takeIf { it in others } ?: others.firstOrNull()
}

private fun composerHint(project: Project, available: List<String>, session: ChatView?, shared: ChatView?): String = when {
    available.isEmpty() -> "No coding agent is installed on the server."
    session != null ->
        "Continues this session on ${session.branch ?: "its branch"}" + (if (project.forge != null) "; its pull request is updated when it's done." else ".")
    shared != null -> "Works on ${shared.branch} together with “${shared.title.take(CHOICE_TITLE)}”: their runs take turns, and both change that branch."
    project.forge != null ->
        "Starts a new session on a new branch. When it's done, the branch is pushed and a pull request is opened on ${forgeName(project.forge)}."
    else -> "Starts a new session on a new branch. This repository has no GitHub, GitLab or Azure DevOps remote, so the work stays local."
}

private const val CHOICE_TITLE = 32

@Composable
private fun ReviewToggle(checked: Boolean, agent: String, reviewer: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            "Cross-review first: ${agentLabel(reviewer)} reviews the change, ${agentLabel(agent)} addresses it",
            color = Palette.ink2, fontSize = 13.sp,
        )
    }
}

@Composable
private fun SendButton(sending: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !sending,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp),
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.onRust, strokeWidth = 2.dp)
        } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Send", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AgentOption(agent: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val content = if (selected) Palette.paper else Palette.ink
    Row(
        Modifier.heightIn(min = 40.dp)
            .background(if (selected) Palette.ink else Palette.field, shape)
            .border(1.dp, if (selected) Palette.ink else Palette.paperLine, shape)
            .clickable(onClickLabel = "Ask ${agentLabel(agent)}", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentMark(agent, size = 17.dp, tint = content)
        Spacer(Modifier.width(7.dp))
        Text(pickName(agent), color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
