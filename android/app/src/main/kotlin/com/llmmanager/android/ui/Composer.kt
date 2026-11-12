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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.JobRequest
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ScheduleResponse

/**
 * The message box under the chat: pick an available agent, write, optionally ask a second agent
 * to review first, send. Text survives refreshes; it is cleared once the server accepts the job.
 * An agent that is not signed in on the server gets the sign-in pop-up first, then the message goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(project: Project, schedule: ScheduleResponse?, sending: Boolean, actions: ChatActions) {
    // Until the schedule is loaded every agent is offered; the server refuses ones it lacks.
    val available = AGENT_ORDER.filter { schedule == null || schedule.agents[it] == true }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable(project.id) { mutableStateOf("") }
    var review by rememberSaveable(project.id) { mutableStateOf(false) }
    val agent = listOfNotNull(chosen, schedule?.defaultAgent).firstOrNull { it in available } ?: available.firstOrNull() ?: "claude"
    val reviewer = reviewerFor(agent, available)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        available.forEach { option -> AgentOption(option, selected = option == agent) { chosen = option } }
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        placeholder = { Text("Ask ${agentLabel(agent)} to change something in ${project.name.substringAfterLast('/')}…") },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Palette.field, unfocusedContainerColor = Palette.field),
    )
    reviewer?.let { ReviewToggle(review, agent, it) { checked -> review = checked } }
    SendButton(sending, enabled = text.isNotBlank() && available.isNotEmpty()) {
        val request = JobRequest(
            projectId = project.id, kind = if (review && reviewer != null) "pipeline" else "run", agent = agent, prompt = text.trim(),
            reviewer = reviewer.takeIf { review }, publish = project.forge != null,
        )
        val send = {
            actions.onSend(request) {
                text = ""
                review = false
            }
        }
        val missing = listOfNotNull(request.agent, request.reviewer).filter { schedule?.needsSignIn(it) == true }
        signInThen(missing, actions.onSignIn, send)
    }
    val hint = if (schedule?.needsSignIn(agent) == true) {
        "${agentLabel(agent)} is not signed in on the server yet. Sending asks you to sign in first."
    } else {
        composerHint(project, available)
    }
    Text(hint, color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
}

/** Signs in each of [agents] in turn, then runs [then]. */
private fun signInThen(agents: List<String>, signIn: (String, () -> Unit) -> Unit, then: () -> Unit) {
    if (agents.isEmpty()) then() else signIn(agents.first()) { signInThen(agents.drop(1), signIn, then) }
}

/** The agent's usual reviewer when it is available, else any other available agent. */
private fun reviewerFor(agent: String, available: List<String>): String? {
    val others = available.filter { it != agent }
    return defaultReviewer(agent).takeIf { it in others } ?: others.firstOrNull()
}

private fun composerHint(project: Project, available: List<String>): String = when {
    available.isEmpty() -> "No coding agent is installed on the server."
    project.forge != null -> "Works on a new branch. When it's done, the branch is pushed and a pull request is opened on ${forgeName(project.forge)}."
    else -> "This repository has no GitHub, GitLab or Azure DevOps remote, so the work stays on a local branch."
}

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
