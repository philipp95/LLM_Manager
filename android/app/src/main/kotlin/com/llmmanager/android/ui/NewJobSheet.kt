package com.llmmanager.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.JobRequest
import com.llmmanager.android.data.ResumeTarget
import com.llmmanager.android.data.Schedule
import com.llmmanager.android.data.ScheduleResponse
import com.llmmanager.android.data.SessionRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Everything the form holds; one immutable value so each part can be its own composable. */
private data class Form(
    val kind: String = "run",
    val agent: String = "claude",
    val project: Pair<String, String>? = null,
    val session: ResumeTarget? = null,
    val prompt: String = "",
    val whenMode: String = "now",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(9, 0),
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    val resumeOnLimit: Boolean = true,
) {
    val ready: Boolean get() = if (kind == "resume") session != null else project != null

    fun toRequest(zone: ZoneId): JobRequest {
        val schedule = when (whenMode) {
            "once" -> Schedule(at = date.atTime(time).atZone(zone).toInstant().toString())
            "daily" -> Schedule(time = time.toString(), days = days, zone = zone.id)
            else -> null
        }
        val target = session
        return if (kind == "resume" && target != null) {
            val ref = SessionRef(target.session.agent, target.session.sessionId, target.session.worktree)
            JobRequest(target.projectId, kind, target.session.agent, prompt, schedule, ref, resumeOnLimit)
        } else {
            JobRequest(project?.first.orEmpty(), kind, agent, prompt, schedule, null, resumeOnLimit)
        }
    }
}

private fun sessionLabel(target: ResumeTarget) = "${target.projectName} · ${target.branch ?: "detached"} · ${agentLabel(target.session.agent)}"

/** The form for a new job. [projects] are (id, name) pairs from the overview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewJobSheet(
    projects: List<Pair<String, String>>,
    schedule: ScheduleResponse,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (JobRequest) -> Unit,
) {
    val soon = remember { java.time.LocalDateTime.now().plusHours(1).withMinute(0) }
    var form by remember {
        mutableStateOf(Form(project = projects.firstOrNull(), session = schedule.resumable.firstOrNull(), date = soon.toLocalDate(), time = soon.toLocalTime()))
    }
    var picking by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.raised) {
        Column(
            Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New job", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Segmented(listOf("run" to "Run a task", "pipeline" to "Build + review", "resume" to "Resume"), form.kind) { form = form.copy(kind = it) }
            if (form.kind == "resume") {
                ResumeFields(form, schedule.resumable) { form = it }
            } else {
                TaskFields(form, projects, schedule.agents) { form = it }
            }
            OutlinedTextField(
                value = form.prompt, onValueChange = { form = form.copy(prompt = it) }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (form.kind == "resume") "Message (optional)" else "Task") },
                placeholder = { Text(if (form.kind == "resume") "Continue where you left off." else "What should the agent do?") },
            )
            WhenFields(form, onChange = { form = it }, onPick = { picking = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = form.resumeOnLimit, onCheckedChange = { form = form.copy(resumeOnLimit = it) })
                Text("If a usage limit stops it, continue automatically when the limit resets", color = Palette.text, fontSize = 13.5.sp)
            }
            Muted("Runs work in a new branch and worktree, may edit files and run build/test commands, and never push.")
            Button(
                onClick = { onSubmit(form.toRequest(ZoneId.systemDefault())) },
                enabled = !submitting && form.ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
            ) { Text(if (form.whenMode == "now") "Start now" else "Schedule") }
        }
    }
    when (picking) {
        "date" -> DateDialog(form.date, onDismiss = { picking = null }) { form = form.copy(date = it); picking = null }
        "time" -> TimeDialog(form.time, onDismiss = { picking = null }) { form = form.copy(time = it); picking = null }
    }
}

@Composable
private fun ResumeFields(form: Form, resumable: List<ResumeTarget>, onChange: (Form) -> Unit) {
    val options = resumable.map { it to "${sessionLabel(it)} · ${relativeTime(it.session.updatedAt)}" }
    Picker("Session to continue", form.session?.let(::sessionLabel) ?: "No resumable sessions", options) { onChange(form.copy(session = it)) }
    Muted("Only sessions in agent worktrees can be resumed, never your own checkout.")
}

@Composable
private fun TaskFields(form: Form, projects: List<Pair<String, String>>, agents: Map<String, Boolean>, onChange: (Form) -> Unit) {
    Picker("Project", form.project?.second ?: "No projects", projects.map { it to it.second }) { onChange(form.copy(project = it)) }
    Segmented(listOf("claude" to "Claude", "codex" to "Codex"), form.agent) { onChange(form.copy(agent = it)) }
    val builder = agentLabel(form.agent)
    val reviewer = agentLabel(if (form.agent == "claude") "codex" else "claude")
    when {
        form.kind == "pipeline" -> Muted("$builder builds and commits, $reviewer reviews the commit, then $builder addresses the review.")
        agents[form.agent] == false -> Text("$builder is not installed on the server.", color = Palette.bad, fontSize = 12.5.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenFields(form: Form, onChange: (Form) -> Unit, onPick: (String) -> Unit) {
    Text("When", color = Palette.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    Segmented(listOf("now" to "Now", "once" to "Once", "daily" to "Repeat"), form.whenMode) { onChange(form.copy(whenMode = it)) }
    if (form.whenMode == "once") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPick("date") }) { Text(form.date.toString()) }
            OutlinedButton(onClick = { onPick("time") }) { Text(form.time.toString()) }
        }
    }
    if (form.whenMode == "daily") {
        OutlinedButton(onClick = { onPick("time") }) { Text("at ${form.time}") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAYS.forEachIndexed { index, name ->
                val day = index + 1
                val selected = day in form.days
                FilterChip(
                    selected = selected, label = { Text(name) },
                    onClick = { onChange(form.copy(days = if (selected) form.days - day else (form.days + day).sorted())) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Palette.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(current, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // The picker reports midnight UTC of the chosen day.
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.raised,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
