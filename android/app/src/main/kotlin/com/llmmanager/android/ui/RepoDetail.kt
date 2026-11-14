package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.PullRequestSummary
import com.llmmanager.android.data.ScheduleResponse

/** One repository: what it is, a chat with the agents, its pull request history and its branches. */
@Composable
fun RepoDetail(
    project: Project,
    schedule: ScheduleResponse?,
    chats: ChatViewModel.State,
    codexAvailable: Boolean,
    launching: Boolean,
    actions: RepoActions,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "head") { RepoHeader(project) }
        item(key = "chat") { ChatPanel(project, chats.chats.takeIf { chats.projectId == project.id }, schedule, chats.sending, actions.chat) }
        item(key = "history") { HistoryPanel(project) }
        item(key = "branches") {
            BranchesPanel(project, codexAvailable, launching, actions.onStartCodex, actions.chat.onCopy, actions.onPublish)
        }
        item(key = "paths") { PathsPanel(project) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoHeader(project: Project) {
    val uriHandler = LocalUriHandler.current
    val owner = project.name.substringBeforeLast('/', "")
    val repo = project.name.substringAfterLast('/')
    val webUrl = (project.webUrl ?: project.gitHubUrl)?.takeIf { it.startsWith("https://") }
    val insights = project.insights?.takeIf { it.fetchedAt != null }
    val branches = project.worktrees.count { !it.main }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeBadge(project.forge, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (owner.isNotEmpty()) Text(owner, color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(repo, color = Palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
            }
        }
        project.description?.let { Text(it, color = Palette.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(insights?.mergedLastWeek?.toString() ?: "–", "merged this week", Modifier.weight(1f))
            Stat(insights?.openCount?.toString() ?: "–", "open pull requests", Modifier.weight(1f))
            Stat(branches.toString(), if (branches == 1) "agent branch" else "agent branches", Modifier.weight(1f))
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(project.defaultBranch ?: "unknown", mono = true)
            Chip("last commit " + relativeTime(project.worktrees.mapNotNull { it.lastCommitAt }.maxOrNull()))
        }
        if (webUrl != null) {
            TextButton(onClick = { uriHandler.openUri(webUrl) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Open on ${forgeName(project.forge) ?: "the forge"}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Icon(Glyphs.link, contentDescription = null, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.background(Palette.paper2, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(value, color = Palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.ink2, fontSize = 11.5.sp, lineHeight = 14.sp)
    }
}

/** The repository's pull requests, newest first: the history of the branches that became changes. */
@Composable
private fun HistoryPanel(project: Project) {
    val insights = project.insights
    val forge = forgeName(project.forge)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Branch history", Modifier.weight(1f))
            insights?.fetchedAt?.let { Text("from $forge · ${relativeTime(it)}", color = Palette.ink2, fontSize = 12.sp) }
        }
        Spacer(Modifier.size(6.dp))
        when {
            forge == null -> Muted("Pull request history needs a GitHub, GitLab or Azure DevOps remote.")
            insights == null -> Muted("Reading pull requests from $forge…")
            else -> {
                insights.error?.let {
                    Text(
                        "Couldn't read pull requests: $it", color = Palette.warnInk, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(Palette.warn.copy(alpha = 0.16f), RoundedCornerShape(12.dp)).padding(10.dp),
                    )
                }
                if (insights.recent.isEmpty() && insights.fetchedAt != null) Muted("No pull requests yet.")
                insights.recent.forEachIndexed { index, pr ->
                    if (index > 0) HorizontalDivider(color = Palette.paperLine)
                    PullRequestRow(project.forge, pr)
                }
            }
        }
    }
}

@Composable
private fun PullRequestRow(forge: String?, pr: PullRequestSummary) {
    val uriHandler = LocalUriHandler.current
    val url = pr.url.takeIf { it.startsWith("https://") }
    val (glyph, colour, detail) = when (pr.state) {
        "merged" -> Triple(Glyphs.merge, Palette.teal, "merged ${relativeTime(pr.mergedAt)}")
        "open" -> Triple(Glyphs.pullRequest, Palette.ok, "opened ${relativeTime(pr.createdAt)}")
        else -> Triple(Glyphs.cross, Palette.ink3, "closed")
    }
    Row(
        Modifier.fillMaxWidth().then(if (url != null) Modifier.clickable(onClickLabel = "Open pull request") { uriHandler.openUri(url) } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).background(colour, CircleShape).semantics { contentDescription = pr.state }, contentAlignment = Alignment.Center) {
            Icon(glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prRef(forge, pr.number), color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(pr.title, color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(listOfNotNull(pr.branch, detail).joinToString(" · "), color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            pr.author?.let { PersonAvatar(it) }
            pr.agent?.let { AgentMark(it) }
            CiMark(pr.ci)
        }
    }
}
