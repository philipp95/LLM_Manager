package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Project

/** Repository tiles, [columns] to a row; tiles in a row share one height so their parts line up. */
fun LazyListScope.repoGrid(projects: List<Project>, columns: Int, onOpen: (Project) -> Unit) {
    items(projects.chunked(columns), key = { row -> "row-" + row.first().id }) { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { project -> RepoTile(project, Modifier.weight(1f).fillMaxHeight(), onOpen = { onOpen(project) }) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * One repository: forge, name, README sentence, latest merged pull request (number, author,
 * agent, CI/CD), the past week's merged and open counts, and a link to the forge.
 */
@Composable
fun RepoTile(project: Project, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val owner = project.name.substringBeforeLast('/', "")
    val repo = project.name.substringAfterLast('/')
    // The server builds the forge link from validated parts; still only open https links.
    val webUrl = (project.webUrl ?: project.gitHubUrl)?.takeIf { it.startsWith("https://") }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.shadow(4.dp, shape).background(Palette.paper, shape).clickable(onClickLabel = "Open $repo", onClick = onOpen).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeBadge(project.forge)
            Spacer(Modifier.weight(1f))
            if (project.worktrees.any { it.codex?.running == true }) Chip("Codex live", ChipTone.Live, dot = true)
        }
        Text(
            repo, color = Palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(owner.ifEmpty { "local" }, color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Always three lines tall, as an explicit height: intrinsic measurement (which lines the tiles of a row up) ignores minLines.
        val descriptionHeight = with(LocalDensity.current) { (DESCRIPTION_LINE_HEIGHT * DESCRIPTION_LINES).toDp() }
        Text(
            project.description ?: "No README description.", color = Palette.ink2, fontSize = 12.5.sp, lineHeight = DESCRIPTION_LINE_HEIGHT,
            maxLines = DESCRIPTION_LINES, overflow = TextOverflow.Ellipsis,
            fontStyle = if (project.description == null) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp).heightIn(min = descriptionHeight),
        )
        TileCell("Latest merged") { LatestMerged(project) }
        TileCell("Past week") { PastWeek(project) }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = Palette.paperLine)
        if (webUrl != null) {
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Open on ${forgeName(project.forge) ?: "the forge"}") { uriHandler.openUri(webUrl) }
                    .padding(top = 9.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    forgeName(project.forge) ?: "Repository", color = Palette.rustStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Glyphs.link, contentDescription = null, tint = Palette.rustStrong, modifier = Modifier.size(14.dp))
            }
        } else {
            Text(
                "No forge remote", color = Palette.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 9.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun TileCell(label: String, content: @Composable () -> Unit) {
    HorizontalDivider(color = Palette.paperLine)
    Text(
        label.uppercase(), color = Palette.ink2, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp,
        modifier = Modifier.padding(top = 7.dp, bottom = 3.dp),
    )
    Column(Modifier.heightIn(min = 38.dp).padding(bottom = 8.dp)) { content() }
}

@Composable
private fun LatestMerged(project: Project) {
    val insights = project.insights
    val pr = insights?.latestMerged
    when {
        project.forge == null -> CellNote("Local repository")
        insights == null -> CellNote("Reading ${forgeName(project.forge)}…")
        pr == null -> CellNote(if (insights.error != null && insights.fetchedAt == null) "Can't read pull requests" else "None yet")
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(prRef(project.forge, pr.number), color = Palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                pr.author?.let { PersonAvatar(it) }
                pr.agent?.let { AgentMark(it) }
                CiMark(pr.ci)
            }
            Text(pr.title, color = Palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PastWeek(project: Project) {
    val insights = project.insights?.takeIf { it.fetchedAt != null }
    if (project.forge == null || insights == null) {
        CellNote("—")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        WeekCount(insights.mergedLastWeek, "merged")
        WeekCount(insights.openCount, "open")
    }
}

@Composable
private fun WeekCount(value: Int, label: String) {
    Column {
        Text(value.toString(), color = Palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
        Text(label, color = Palette.ink2, fontSize = 11.5.sp)
    }
}

@Composable
private fun CellNote(text: String) {
    Text(text, color = Palette.ink2, fontSize = 12.5.sp)
}

private val DESCRIPTION_LINE_HEIGHT = 17.sp
private const val DESCRIPTION_LINES = 3
