package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.CodexSession
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Worktree

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectCard(
    project: Project,
    codexAvailable: Boolean,
    launching: Boolean,
    onStartCodex: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val owner = project.name.substringBeforeLast('/', "")
    val repo = project.name.substringAfterLast('/')
    // Only ever open github.com links the server built from a validated owner/repo.
    val gitHubUrl = project.gitHubUrl?.takeIf { it.startsWith("https://github.com/") }
    val agents = project.worktrees.count { it.agentRun }
    val live = project.worktrees.count { it.codex?.running == true }

    Panel(padding = false) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(project.name, repo)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (owner.isNotEmpty()) Text(owner, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(repo, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (gitHubUrl != null) {
                IconButton(onClick = { uriHandler.openUri(gitHubUrl) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Open on GitHub", tint = Palette.text)
                }
            }
        }
        FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(project.defaultBranch ?: "unknown", mono = true)
            Chip(project.kind)
            Chip(relativeTime(project.worktrees.mapNotNull { it.lastCommitAt }.maxOrNull()))
            if (live > 0) Chip("$live live", ChipTone.Codex, dot = true)
            if (agents > 0) Chip(plural(agents, "run", "runs"), ChipTone.Agent, dot = true)
        }
        if (project.commitsPerDay.isNotEmpty()) {
            val last = project.commitsPerDay.takeLast(14)
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("14d", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Sparkline(last, Modifier.weight(1f).height(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(plural(last.sum(), "commit", "commits"), color = Palette.muted, fontSize = 12.sp)
            }
        }
        project.worktrees.forEach { worktree ->
            HorizontalDivider(color = Palette.lineSoft)
            WorktreeRow(worktree, onCopy)
        }
        PathsSection(project)
        HorizontalDivider(color = Palette.lineSoft)
        Column(Modifier.padding(14.dp)) {
            Button(
                onClick = onStartCodex,
                enabled = codexAvailable && !launching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, disabledContainerColor = Palette.raised),
            ) {
                if (launching) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Start Codex", fontWeight = FontWeight.SemiBold)
            }
            if (!codexAvailable) {
                Text(
                    "Codex CLI not found on the server", color = Palette.faint, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String, repo: String) {
    val hue = name.fold(0) { h, c -> (h * 31 + c.code) % 360 }.toFloat()
    Box(
        Modifier.size(42.dp).background(
            Brush.linearGradient(listOf(Color.hsl(hue, 0.7f, 0.55f), Color.hsl((hue + 40) % 360, 0.7f, 0.42f))),
            RoundedCornerShape(12.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(repo.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorktreeRow(worktree: Worktree, onCopy: (String) -> Unit) {
    val state = stateColor(worktree)
    // Intrinsic height lets the status bar span the whole row, however tall its content is.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(14.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(state, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    worktree.branch ?: "detached @ ${worktree.head?.take(8) ?: "?"}",
                    color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ChangesChip(worktree)
            }
            Text(
                listOfNotNull(relativeTime(worktree.lastCommitAt), worktree.head?.take(7)).joinToString("  ·  "),
                color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            val tags = tags(worktree)
            if (tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { (text, tone) -> Chip(text, tone, dot = tone != ChipTone.Neutral) }
                }
            }
            worktree.codex?.let { AttachBox(it, onCopy) }
        }
    }
}

private fun stateColor(worktree: Worktree): Color = when {
    worktree.codex?.running == true -> Palette.codex
    worktree.missing || worktree.changedFiles == null -> Palette.bad
    worktree.changedFiles == 0 -> Palette.ok
    else -> Palette.warn
}

private fun tags(worktree: Worktree): List<Pair<String, ChipTone>> = listOfNotNull(
    worktree.codex?.let { if (it.running) "Codex live" to ChipTone.Codex else "Codex" to ChipTone.Neutral },
    if (worktree.agentRun) "Agent run" to ChipTone.Agent else null,
    if (worktree.scheduledRun) "Scheduled run" to ChipTone.Neutral else null,
    if (worktree.main) "main worktree" to ChipTone.Neutral else null,
)

@Composable
private fun ChangesChip(worktree: Worktree) {
    val changed = worktree.changedFiles
    when {
        worktree.missing -> Chip("missing", ChipTone.Bad)
        changed == null -> Chip("status unknown", ChipTone.Bad)
        changed == 0 -> Chip("clean", ChipTone.Ok)
        else -> Chip(plural(changed, "change", "changes"), ChipTone.Warn)
    }
}

@Composable
private fun AttachBox(session: CodexSession, onCopy: (String) -> Unit) {
    if (!session.running) {
        Text("Codex session ended · worktree kept", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        return
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().background(Color(0xFF07090D), RoundedCornerShape(10.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(session.attachCommand, color = Palette.codexText, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCopy(session.attachCommand) }) { Text("Copy", color = Palette.text, fontSize = 12.sp) }
    }
}

@Composable
private fun PathsSection(project: Project) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(color = Palette.lineSoft)
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(if (open) "Hide paths & origin" else "Paths & origin", color = Palette.muted, fontSize = 12.5.sp)
    }
    if (open) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            project.originUrl?.let { PathLine("origin", it) }
            PathLine("repo", project.path)
            project.worktrees.forEach { PathLine(it.branch ?: "detached", it.path) }
        }
    }
}

@Composable
private fun PathLine(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Palette.faint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
    }
}
