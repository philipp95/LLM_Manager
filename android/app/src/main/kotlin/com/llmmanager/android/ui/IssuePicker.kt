package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.IssueRef
import com.llmmanager.android.data.IssueSummary
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.RepoIssues
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** As many issues as the server reads for one message. */
private const val MAX_ISSUES = 5

/** How many matches the picker lists; searching narrows them down. */
private const val MAX_OPTIONS = 8

private const val CHIP_TITLE = 28

/** The issues attached to the message being written. */
class IssuePick(initial: List<IssueRef> = emptyList()) {
    var chosen by mutableStateOf(initial)
        private set
    val numbers: List<Long> get() = chosen.map { it.number }
    val full: Boolean get() = chosen.size >= MAX_ISSUES

    fun has(number: Long): Boolean = chosen.any { it.number == number }

    /** Attaches the issue, or detaches it when it is attached already; a full list takes no more. */
    fun toggle(ref: IssueRef) {
        chosen = when {
            has(ref.number) -> chosen.filter { it.number != ref.number }
            full -> chosen
            else -> chosen + ref
        }
    }

    fun clear() {
        chosen = emptyList()
    }

    companion object {
        private val refs = ListSerializer(IssueRef.serializer())
        val Saver: Saver<IssuePick, String> = Saver(
            save = { Json.encodeToString(refs, it.chosen) },
            restore = { saved -> IssuePick(runCatching { Json.decodeFromString(refs, saved) }.getOrDefault(emptyList())) },
        )
    }
}

@Composable
fun rememberIssuePick(projectId: String): IssuePick = rememberSaveable(projectId, saver = IssuePick.Saver) { IssuePick() }

private fun noun(forge: String?) = if (forge == "azure-devops") "work item" else "issue"

/**
 * Attaches issues to a message: the server reads their title, description and comments for the
 * agent, and the session's pull request closes them. Not offered without a forge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssuePicker(project: Project, issues: RepoIssues?, picked: IssuePick) {
    if (project.forge == null || project.isServer) return
    val noun = noun(project.forge)
    var open by rememberSaveable(project.id) { mutableStateOf(false) }
    FlowRow(
        Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AttachButton(if (picked.chosen.isEmpty()) "Attach $noun" else plural(picked.chosen.size, noun, "${noun}s"), open) { open = !open }
        picked.chosen.forEach { ref -> IssueChip(ref, onRemove = { picked.toggle(ref) }) }
    }
    if (open) IssueOptions(noun, issues, picked)
}

@Composable
private fun AttachButton(label: String, open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 36.dp).background(if (open) Palette.paper3 else Palette.field, shape).border(1.dp, Palette.paperLine, shape)
            .clickable(onClickLabel = if (open) "Close the list" else "Choose from the list", role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Glyphs.cross else Glyphs.plus, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The searchable list of open issues; a number that is not listed can be attached as it is. */
@Composable
private fun IssueOptions(noun: String, issues: RepoIssues?, picked: IssuePick) {
    var query by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Palette.paper2, shape).border(1.dp, Palette.paperLine, shape).padding(10.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Title, label or number") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Palette.field, unfocusedContainerColor = Palette.field),
        )
        val matches = matching(issues?.open.orEmpty(), query)
        matches.take(MAX_OPTIONS).forEach { issue ->
            val ref = IssueRef(issue.number, issue.title, issue.url, issue.state)
            val ticked = picked.has(issue.number)
            IssueOption(numbered(issue.number, issue.title), ticked, enabled = ticked || !picked.full) { picked.toggle(ref) }
        }
        val typed = query.trim().removePrefix("#").toLongOrNull()?.takeIf { number -> number > 0 && matches.none { it.number == number } }
        if (typed != null) {
            IssueOption(numbered(typed, "Attach it, though it is not in this list"), picked.has(typed), enabled = !picked.full || picked.has(typed)) {
                picked.toggle(IssueRef(typed))
            }
        }
        Text(optionsNote(noun, issues, matches.size, picked.full), color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Open issues whose number, title, labels or author hold every word of [query]. */
private fun matching(open: List<IssueSummary>, query: String): List<IssueSummary> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }.map { it.removePrefix("#") }
    return open.filter { issue ->
        val text = (listOf(issue.number.toString(), issue.title, issue.author.orEmpty()) + issue.labels).joinToString(" ").lowercase()
        words.all { it in text }
    }
}

private fun optionsNote(noun: String, issues: RepoIssues?, matches: Int, full: Boolean): String = when {
    full -> "Up to $MAX_ISSUES ${noun}s go with one message."
    issues == null -> "Reading the open ${noun}s… A number can be typed meanwhile."
    issues.error != null -> "Couldn't read the ${noun}s: ${issues.error}. A number can still be typed."
    matches == 0 -> "No open $noun matches. Type its number to attach it anyway."
    matches > MAX_OPTIONS -> "${matches - MAX_OPTIONS} more; search to narrow them down."
    else -> "The agent reads the title, description and comments; the pull request closes the $noun when merged."
}

@Composable
private fun IssueOption(label: AnnotatedString, ticked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = if (ticked) "Detach" else "Attach", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).background(if (ticked) Palette.rust else Palette.field, CircleShape)
                .border(1.dp, if (ticked) Palette.rust else Palette.paperLine, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(Glyphs.check, contentDescription = "attached", tint = Palette.onRust, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(10.dp))
        val colour = if (enabled) Palette.ink else Palette.ink3
        Text(
            label, color = colour, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
        )
    }
}

/** The issues attached to a message, under its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueChips(issues: List<IssueRef>, onDark: Boolean = false) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        issues.forEach { IssueChip(it, onDark) }
    }
}

/**
 * An attached issue: its number and title. A tap opens it on the forge, over https only; with
 * [onRemove] the tap detaches it instead. A closed one says so with a tick.
 */
@Composable
fun IssueChip(ref: IssueRef, onDark: Boolean = false, onRemove: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    val url = ref.url.takeIf { it.startsWith("https://") }
    val closed = ref.state == "closed"
    val ink = if (onDark) Palette.onHarbor else Palette.ink
    val line = if (onDark) Color.White.copy(alpha = 0.22f) else Palette.paperLine
    val fill = if (onDark) Color.White.copy(alpha = 0.10f) else Palette.field
    val shape = RoundedCornerShape(50)
    val tap = when {
        onRemove != null -> Modifier.clickable(onClickLabel = "Detach #${ref.number}", onClick = onRemove)
        url != null -> Modifier.clickable(onClickLabel = "Open on the forge") { uriHandler.openUri(url) }
        else -> Modifier
    }
    Row(
        Modifier.heightIn(min = if (onRemove != null) 36.dp else 26.dp).background(fill, shape).border(1.dp, line, shape).then(tap)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (closed) {
            Icon(Glyphs.check, contentDescription = "closed", tint = ink, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text("#${ref.number}", color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        if (ref.title.isNotBlank()) {
            Text(
                " " + ref.title.take(CHIP_TITLE) + if (ref.title.length > CHIP_TITLE) "…" else "",
                color = ink.copy(alpha = 0.85f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Glyphs.cross, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        }
    }
}
