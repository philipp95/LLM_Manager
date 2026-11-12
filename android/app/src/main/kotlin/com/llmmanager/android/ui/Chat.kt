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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Run
import com.llmmanager.android.data.ScheduleResponse

/**
 * One message and its answer: the runs that share a pipeline (build, review, fix) or a branch
 * (resumes), oldest first. Only the newest [limit] conversations are kept.
 */
fun conversationTurns(runs: List<Run>, projectId: String, limit: Int = 8): List<List<Run>> =
    runs.filter { it.projectId == projectId }
        .sortedBy { it.createdAt }
        .groupBy { it.pipelineId ?: it.branch ?: it.id }
        .values.toList()
        .takeLast(limit)

/**
 * Talk to an agent about this repository: each message starts a run on its own branch, and
 * when the project has a forge the branch is pushed and a pull request opened once it is done.
 */
@Composable
fun ChatPanel(project: Project, schedule: ScheduleResponse?, sending: Boolean, actions: ChatActions) {
    val turns = conversationTurns(schedule?.runs.orEmpty(), project.id)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Chat", Modifier.weight(1f))
            Text("Each message runs on its own branch", color = Palette.ink2, fontSize = 12.5.sp)
        }
        Spacer(Modifier.size(10.dp))
        if (turns.isEmpty()) {
            val after = if (project.forge != null) " and opens a pull request when it's done." else "."
            Text("No messages yet. Ask an agent below: it works on a new branch$after", color = Palette.ink2, fontSize = 13.5.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            turns.forEach { turn ->
                Message(turn.first())
                Answer(TurnView(project, turn), project.forge, actions, needsSignIn = schedule?.needsSignIn(turn.last().agent) == true)
            }
        }
        HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
        Composer(project, schedule, sending, actions)
    }
}

/** What one conversation turn shows, worked out once from its runs. */
private class TurnView(project: Project, val runs: List<Run>) {
    val first = runs.first()
    val last = runs.last()
    val active = runs.firstOrNull { it.active }
    val summary = runs.lastOrNull { it.summary != null }?.summary
    val note = runs.lastOrNull { it.publishNote != null }?.publishNote
    val reviewer = runs.firstOrNull { it.stage == "review" }?.agent
    val failure = last.error?.takeIf { last.status == "failed" }
    val pullRequest = first.branch?.let { branch -> project.worktrees.firstOrNull { it.branch == branch } }
        ?.publish?.pullRequest?.takeIf { it.url.startsWith("https://") }
    val progress = when {
        summary != null || active == null -> null
        active.status == "queued" -> "Waiting for a free slot…"
        else -> "Working on it…"
    }
    val attach = active?.takeIf { it.status == "running" }?.tmuxSession?.let { "tmux attach -t $it" }
    val footer = listOfNotNull(first.branch, "updated " + relativeTime(last.finishedAt ?: last.startedAt ?: last.createdAt)).joinToString(" · ")
}

/** The person's message, right-aligned on the harbor colour. */
@Composable
private fun Message(run: Run) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        Column(Modifier.background(Palette.harbor2, RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(run.prompt.take(MAX_PROMPT), color = Palette.onHarbor, fontSize = 14.sp)
            Text(
                localDateTime(run.createdAt), color = Palette.onHarbor2, fontSize = 11.5.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
    }
}

/** The agent's answer: progress through the stages, its last summary, and the pull request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answer(turn: TurnView, forge: String?, actions: ChatActions, needsSignIn: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(end = 28.dp).background(Palette.paper2, RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentMark(turn.first.agent)
            Spacer(Modifier.width(6.dp))
            Text(agentLabel(turn.first.agent), color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            turn.reviewer?.let {
                Text(" · reviewed by ${agentLabel(it)}", color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            RunStatusChip(turn.active ?: turn.last)
        }
        if (turn.runs.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                turn.runs.forEach { StageChip(it) }
            }
        }
        turn.summary?.let { Text(it.take(MAX_SUMMARY), color = Palette.ink, fontSize = 13.5.sp) }
        turn.progress?.let { Text(it, color = Palette.ink2, fontSize = 12.5.sp) }
        turn.failure?.let { Text(it.take(MAX_SUMMARY), color = Palette.badInk, fontSize = 13.sp) }
        Outcome(turn, forge)
        turn.attach?.let { AttachCommand(it, actions.onCopy) }
        Text(turn.footer, color = Palette.ink2, fontSize = 11.5.sp)
        if (turn.failure != null && needsSignIn) {
            // Most likely why it failed: the agent's CLI is not signed in on the server.
            Button(onClick = { actions.onSignIn(turn.last.agent) {} }) { Text("Sign in to ${agentLabel(turn.last.agent)}", fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onLog(turn.last) }) { Text("Log", fontSize = 13.sp) }
            turn.active?.let { active ->
                TextButton(onClick = { actions.onCancel(active) }) { Text("Cancel", color = Palette.badInk, fontSize = 13.sp) }
            }
        }
    }
}

/** The pull request the turn led to, or what publishing said instead. */
@Composable
private fun Outcome(turn: TurnView, forge: String?) {
    val uriHandler = LocalUriHandler.current
    val pr = turn.pullRequest
    if (pr != null) {
        TextButton(onClick = { uriHandler.openUri(pr.url) }, modifier = Modifier.heightIn(min = 32.dp)) {
            Icon(Glyphs.pullRequest, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pull request ${prRef(forge, pr.number)} · ${pr.state}", fontSize = 13.sp)
        }
    } else {
        turn.note?.let { Text(it, color = Palette.ink2, fontSize = 12.5.sp) }
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

private val STAGE_NAMES = mapOf("work" to "Build", "review" to "Review", "fix" to "Fix", "resume" to "Resume")
private const val MAX_PROMPT = 800
private const val MAX_SUMMARY = 700
