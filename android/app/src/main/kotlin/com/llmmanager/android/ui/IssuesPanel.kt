package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.IssueSummary
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.RepoIssues

/** How many open issues are listed before "Show all". */
private const val FIRST_ISSUES = 6

/** Azure DevOps has work items, kept per project; the others have issues. */
private fun issueNoun(forge: String?) = if (forge == "azure-devops") "work items" else "issues"

/** The repository's open issues on its forge, and the ones closed lately; a tap opens one there. */
@Composable
fun IssuesPanel(project: Project, issues: RepoIssues?) {
    val forge = forgeName(project.forge) ?: "the forge"
    val noun = issueNoun(project.forge)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle(if (issues?.fetchedAt != null) "Open $noun · ${issues.openCount}" else "Open $noun", Modifier.weight(1f))
            issues?.fetchedAt?.let { Text("from $forge · ${relativeTime(it)}", color = Palette.ink2, fontSize = 12.sp) }
        }
        Spacer(Modifier.size(6.dp))
        if (issues == null) {
            Muted("Reading $noun from $forge…")
        } else {
            issues.error?.let {
                Text(
                    "Couldn't read $noun: $it", color = Palette.warnInk, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Palette.warn.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).padding(10.dp),
                )
            }
            if (issues.open.isEmpty() && issues.fetchedAt != null) Muted("No open $noun.")
            IssueLists(issues, noun, forge)
        }
    }
}

@Composable
private fun IssueLists(issues: RepoIssues, noun: String, forge: String) {
    val uriHandler = LocalUriHandler.current
    var all by rememberSaveable { mutableStateOf(false) }
    var closed by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) issues.open else issues.open.take(FIRST_ISSUES)
    IssueRows(shown)
    if (shown.size < issues.open.size) {
        TextButton(onClick = { all = true }) { Text("Show all ${issues.open.size}", fontWeight = FontWeight.Bold) }
    }
    val page = issues.url?.takeIf { it.startsWith("https://") }
    val more = issues.openCount - issues.open.size
    if (page != null) {
        TextButton(onClick = { uriHandler.openUri(page) }) {
            Text(if (more > 0 && all) "$more more on $forge" else "All $noun on $forge", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Icon(Glyphs.link, contentDescription = null, modifier = Modifier.size(15.dp))
        }
    }
    if (issues.closed.isNotEmpty()) {
        TextButton(onClick = { closed = !closed }) {
            Text((if (closed) "Hide" else "Show") + " recently closed · ${issues.closed.size}", color = Palette.ink2, fontWeight = FontWeight.Bold)
        }
        if (closed) IssueRows(issues.closed)
    }
}

@Composable
private fun IssueRows(issues: List<IssueSummary>) {
    issues.forEachIndexed { index, issue ->
        if (index > 0) HorizontalDivider(color = Palette.paperLine)
        IssueRow(issue)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueRow(issue: IssueSummary) {
    val uriHandler = LocalUriHandler.current
    val url = issue.url.takeIf { it.startsWith("https://") }
    val isClosed = issue.state == "closed"
    val detail = listOfNotNull(
        issue.kind, issue.status, issue.author?.let { "by $it" }, "updated ${relativeTime(issue.updatedAt ?: issue.createdAt)}",
        issue.comments?.takeIf { it > 0 }?.let { plural(it, "comment", "comments") },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().then(if (url != null) Modifier.clickable(onClickLabel = "Open on the forge") { uriHandler.openUri(url) } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(if (isClosed) Palette.ink3 else Palette.ok, CircleShape).semantics { contentDescription = issue.state },
            contentAlignment = Alignment.Center,
        ) {
            if (isClosed) Icon(Glyphs.check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            else Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "#${issue.number}  ${issue.title}", color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
            )
            Text(detail, color = Palette.ink2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            if (issue.labels.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { issue.labels.forEach { Chip(it) } }
            }
        }
        if (issue.assignees.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { issue.assignees.forEach { PersonAvatar(it) } }
        }
    }
}
