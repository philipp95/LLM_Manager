package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatTurn
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Run
import com.llmmanager.android.data.ScheduleResponse

/**
 * Talk to the agents about this repository, in sessions. A new session works on a branch of its
 * own; its next messages continue the same conversation there, and when the project has a forge
 * the branch's pull request is opened and then kept up to date.
 */
@Composable
fun ChatPanel(project: Project, chats: List<ChatView>?, schedule: ScheduleResponse?, sending: Boolean, actions: ChatActions) {
    // Opening a repository starts with a new session; picking one from the list continues it.
    var selectedId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    val sessions = chats.orEmpty()
    val selected = sessions.firstOrNull { it.id == selectedId }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Sessions", Modifier.weight(1f))
            Text("Each session works on its own branch", color = Palette.ink2, fontSize = 12.5.sp)
        }
        Spacer(Modifier.size(10.dp))
        SessionList(project, sessions, selected?.id) { selectedId = it }
        HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(vertical = 12.dp))
        if (selected == null) {
            val after = if (project.forge != null) "; when it's done, the branch is pushed and a pull request is opened." else "."
            Text("Your message starts a new session on a new branch$after", color = Palette.ink2, fontSize = 13.5.sp)
        } else {
            Conversation(project, selected, schedule, actions)
        }
        HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
        Composer(project, ComposerTarget(schedule, sessions, selected), sending, actions) { sent -> selectedId = sent.id }
    }
}

/** A session's branch, its pull request, and its messages with their answers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(project: Project, session: ChatView, schedule: ScheduleResponse?, actions: ChatActions) {
    val uriHandler = LocalUriHandler.current
    val pr = sessionPullRequest(project, session)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(session.branch ?: "new branch", mono = true)
        if (session.sharesBranch) Chip("shared with another session", ChipTone.Warn)
    }
    if (pr != null) {
        TextButton(onClick = { uriHandler.openUri(pr.url) }, modifier = Modifier.heightIn(min = 32.dp)) {
            Icon(Glyphs.pullRequest, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("${if (project.forge == "gitlab") "Merge request" else "Pull request"} ${prRef(project.forge, pr.number)} · ${pr.state}", fontSize = 13.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        session.turns.takeLast(MAX_TURNS).forEach { turn ->
            Message(turn)
            Answer(TurnView(turn), actions, needsSignIn = schedule?.needsSignIn(turn.runs.lastOrNull()?.agent ?: session.agent) == true)
        }
    }
}

/** What one message's answer shows, worked out once from its runs. */
private class TurnView(turn: ChatTurn) {
    val runs: List<Run> = turn.runs
    val first = runs.firstOrNull()
    val last = runs.lastOrNull()
    val active = runs.firstOrNull { it.active }
    val summary = runs.lastOrNull { it.summary != null }?.summary
    val note = runs.lastOrNull { it.publishNote != null }?.publishNote
    val reviewer = runs.firstOrNull { it.stage == "review" }?.agent
    val failure = last?.error?.takeIf { last.status == "failed" }
    val progress = when {
        summary != null || active == null -> null
        active.status == "queued" -> "Waiting for a free slot…"
        else -> "Working on it…"
    }
    val attach = active?.takeIf { it.status == "running" }?.tmuxSession?.let { "tmux attach -t $it" }
    val footer = "updated " + relativeTime(last?.let { it.finishedAt ?: it.startedAt ?: it.createdAt })
}

/** The person's message, right-aligned on the harbor colour. */
@Composable
private fun Message(turn: ChatTurn) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        Column(Modifier.background(Palette.harbor2, RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(turn.message.take(MAX_PROMPT), color = Palette.onHarbor, fontSize = 14.sp)
            Text(
                localDateTime(turn.createdAt), color = Palette.onHarbor2, fontSize = 11.5.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
    }
}

/** The agent's answer: progress through the stages, its last summary, and what publishing did. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answer(turn: TurnView, actions: ChatActions, needsSignIn: Boolean) {
    val first = turn.first ?: return
    Column(
        Modifier.fillMaxWidth().padding(end = 28.dp).background(Palette.paper2, RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentMark(first.agent)
            Spacer(Modifier.width(6.dp))
            Text(agentLabel(first.agent), color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            turn.reviewer?.let {
                Text(" · reviewed by ${agentLabel(it)}", color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            (turn.active ?: turn.last)?.let { RunStatusChip(it) }
        }
        if (turn.runs.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                turn.runs.forEach { StageChip(it) }
            }
        }
        turn.summary?.let { Text(it.take(MAX_SUMMARY), color = Palette.ink, fontSize = 13.5.sp) }
        turn.progress?.let { Text(it, color = Palette.ink2, fontSize = 12.5.sp) }
        turn.failure?.let { Text(it.take(MAX_SUMMARY), color = Palette.badInk, fontSize = 13.sp) }
        turn.note?.let { Text(it, color = Palette.ink2, fontSize = 12.5.sp) }
        turn.attach?.let { AttachCommand(it, actions.onCopy) }
        Text(turn.footer, color = Palette.ink2, fontSize = 11.5.sp)
        AnswerActions(turn, first.agent, actions, needsSignIn)
    }
}

/** Log and cancel; and signing in, when that is most likely why the message failed. */
@Composable
private fun AnswerActions(turn: TurnView, agent: String, actions: ChatActions, needsSignIn: Boolean) {
    if (turn.failure != null && needsSignIn) {
        Button(onClick = { actions.onSignIn(agent) {} }) { Text("Sign in to ${agentLabel(agent)}", fontSize = 13.sp) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.last?.let { last -> OutlinedButton(onClick = { actions.onLog(last) }) { Text("Log", fontSize = 13.sp) } }
        turn.active?.let { active ->
            TextButton(onClick = { actions.onCancel(active) }) { Text("Cancel", color = Palette.badInk, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StageChip(run: Run) {
    val tone = when (run.status) {
        "succeeded" -> ChipTone.Ok
        "running" -> ChipTone.Live
        "failed", "limited" -> ChipTone.Bad
        else -> ChipTone.Neutral
    }
    Chip(STAGE_NAMES[run.stage] ?: run.stage, tone, dot = run.status == "running")
}

private val STAGE_NAMES = mapOf("work" to "Build", "review" to "Review", "fix" to "Fix", "resume" to "Continue")
private const val MAX_PROMPT = 800
private const val MAX_SUMMARY = 700
private const val MAX_TURNS = 12
