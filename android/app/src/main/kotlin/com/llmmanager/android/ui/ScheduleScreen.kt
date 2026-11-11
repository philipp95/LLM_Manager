package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Job
import com.llmmanager.android.data.JobView
import com.llmmanager.android.data.Run
import com.llmmanager.android.data.RunLog

private val STAGES = mapOf("work" to "Build", "review" to "Review", "fix" to "Fix", "resume" to "Resume")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun ScheduleScreen(
    state: ScheduleViewModel.State,
    projects: List<Pair<String, String>>,
    viewModel: ScheduleViewModel,
    onNeedToken: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val data = state.schedule
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        if (state.needsToken) {
            item {
                Panel {
                    Text("Schedules and run logs need the server's access token.", color = Palette.ink)
                    Button(onClick = onNeedToken, modifier = Modifier.padding(top = 10.dp)) { Text("Enter token") }
                }
            }
            return@LazyColumn
        }
        state.scheduleError?.let { item { Panel { Text(it, color = Palette.badInk) } } }
        data?.sandbox?.let { sandbox ->
            item {
                val on = sandbox.agents.values.any { it }
                Panel {
                    Chip(if (on) "Agent sandbox on" else "Agent sandbox off", if (on) ChipTone.Ok else ChipTone.Warn)
                    Muted(sandbox.note)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Jobs")
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Button(onClick = { creating = true }, enabled = data != null) { Text("New job") }
            }
        }
        if (data != null && data.jobs.isEmpty()) item { BackgroundNote("No jobs yet. Schedule a run, a build-and-review, or a resume.") }
        items(data?.jobs.orEmpty(), key = { "job-" + it.job.id }) { view ->
            JobCard(view, onAction = { action -> viewModel.jobAction(view.job.id, action) }, onConfirm = { text, run -> confirm = text to run })
        }
        item { SectionTitle("Recent runs") }
        if (data != null && data.runs.isEmpty()) item { BackgroundNote("No runs yet.") }
        items(data?.runs.orEmpty(), key = { "run-" + it.id }) { run ->
            RunCard(
                run, onLog = { viewModel.openLog(run.id) }, onCopy = onCopy,
                onCancel = { confirm = "Cancel this run? Its worktree and branch are kept." to { viewModel.cancel(run.id) } },
            )
        }
    }
    if (creating && data != null) {
        NewJobSheet(
            projects, data, state.submitting,
            onDismiss = { creating = false },
            onSubmit = { request -> viewModel.create(request) { creating = false } },
        )
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("No") } },
        )
    }
}

fun describeSchedule(job: Job): String {
    val schedule = job.schedule
    if (schedule.at != null) return if (job.done) "Ran ${localDateTime(job.lastFiredAt ?: schedule.at)}" else "Once · ${localDateTime(schedule.at)}"
    val days = when {
        schedule.days.isEmpty() || schedule.days.size == 7 -> "every day"
        schedule.days == listOf(1, 2, 3, 4, 5) -> "Mon–Fri"
        else -> schedule.days.joinToString(" ") { DAY_NAMES[(it - 1).coerceIn(0, 6)] }
    }
    return "Daily ${schedule.time} · $days"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(view: JobView, onAction: (String) -> Unit, onConfirm: (String, () -> Unit) -> Unit) {
    val job = view.job
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.name, color = Palette.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (job.publish) Chip("opens a PR")
            if (!job.enabled) Chip("paused")
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (job.kind == "pipeline") {
                AgentChip(job.agent, prefix = "builds: ")
                AgentChip(job.reviewer ?: defaultReviewer(job.agent), prefix = "reviews: ")
            } else {
                AgentChip(job.agent)
            }
            Chip(job.projectName)
        }
        val next = view.nextRunAt?.takeIf { job.enabled }?.let { " · next ${untilTime(it)}" }.orEmpty()
        Text(describeSchedule(job) + next, color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
        Text(job.prompt, color = Palette.ink2, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction("run") }) { Text("Run now") }
            OutlinedButton(onClick = { onAction(if (job.enabled) "pause" else "enable") }) { Text(if (job.enabled) "Pause" else "Enable") }
            TextButton(onClick = { onConfirm("Delete \"${job.name}\"? Past runs and their branches stay.") { onAction("delete") } }) {
                Text("Delete", color = Palette.badInk)
            }
        }
    }
}

/** Where a run stands, in words. */
@Composable
fun RunStatusChip(run: Run) {
    when (run.status) {
        "queued" -> Chip(if (run.notBefore != null && untilTime(run.notBefore) != "now") "waits ${untilTime(run.notBefore)}" else "queued")
        "running" -> Chip("working", ChipTone.Live, dot = true)
        "succeeded" -> Chip("done", ChipTone.Ok)
        "limited" -> Chip(if (run.resumeScheduled) "limit · resumes" else "usage limit", ChipTone.Warn)
        "cancelled" -> Chip("cancelled")
        else -> Chip("failed", ChipTone.Bad)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunCard(run: Run, onLog: () -> Unit, onCancel: () -> Unit, onCopy: (String) -> Unit) {
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentChip(run.agent)
            Chip("${STAGES[run.stage] ?: run.stage} · ${run.projectName}")
            RunStatusChip(run)
        }
        val started = run.startedAt?.let { "started ${relativeTime(it)}" } ?: "created ${relativeTime(run.createdAt)}"
        Text(listOfNotNull(run.branch, started).joinToString(" · "), color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
        run.summary?.let { Snippet(it, Palette.ink2) }
        run.error?.let { Snippet(it, Palette.badInk) }
        run.publishNote?.let { Snippet(it, Palette.ink2) }
        if (run.status == "running" && run.tmuxSession != null) AttachCommand("tmux attach -t ${run.tmuxSession}", onCopy)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onLog) { Text("Log") }
            if (run.active) TextButton(onClick = onCancel) { Text("Cancel", color = Palette.badInk) }
        }
    }
}

@Composable
private fun Snippet(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
}

/** A run's prompt and activity, as the server recorded it. */
@Composable
fun LogDialog(log: RunLog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${STAGES[log.run.stage] ?: log.run.stage} · ${agentLabel(log.run.agent)}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(log.run.prompt, color = Palette.ink2, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = Palette.paperLine)
                if (log.activity.isEmpty()) Text("No activity recorded yet.", color = Palette.ink2)
                log.activity.forEach { line ->
                    val color = when (line.kind) {
                        "error" -> Palette.badInk
                        "done" -> Palette.okInk
                        "tool" -> Palette.ink2
                        else -> Palette.ink
                    }
                    Text(line.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
