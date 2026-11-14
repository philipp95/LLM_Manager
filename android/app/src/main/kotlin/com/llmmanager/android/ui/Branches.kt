package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Worktree

/** The project's worktrees with push / pull request controls, and Codex in tmux as the hands-on option. */
@Composable
fun BranchesPanel(
    project: Project,
    codexAvailable: Boolean,
    launching: Boolean,
    onStartCodex: () -> Unit,
    onCopy: (String) -> Unit,
    onPublish: (branch: String, action: String) -> Unit,
    sessions: List<ChatView> = emptyList(),
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Branches", Modifier.weight(1f))
            Text(plural(project.worktrees.size, "worktree", "worktrees"), color = Palette.ink2, fontSize = 12.5.sp)
        }
        project.worktrees.forEachIndexed { index, worktree ->
            if (index > 0) HorizontalDivider(color = Palette.paperLine)
            Column(Modifier.fillMaxWidth().background(Palette.field, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp)) {
                WorktreeRow(worktree, onCopy, project.forge, project.defaultBranch, onPublish)
                sessions.filter { it.branch != null && it.branch == worktree.branch }.forEach { session ->
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentMark(session.agent, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(session.title, color = Palette.ink2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.paperLine, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (codexAvailable) CODEX_HINT else "Codex CLI not found on the server.",
                color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onStartCodex, enabled = codexAvailable && !launching) {
                if (launching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Glyphs.terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Start Codex", fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorktreeRow(
    worktree: Worktree,
    onCopy: (String) -> Unit,
    forge: String?,
    defaultBranch: String?,
    onPublish: (branch: String, action: String) -> Unit,
) {
    // Intrinsic height lets the status bar span the whole row, however tall its content is.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 12.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(stateColor(worktree), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    worktree.branch ?: "detached @ ${worktree.head?.take(8) ?: "?"}",
                    color = Palette.ink, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ChangesChip(worktree)
            }
            Text(
                listOfNotNull(relativeTime(worktree.lastCommitAt), worktree.head?.take(7)).joinToString("  ·  "),
                color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            val tags = tags(worktree)
            if (tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { (text, tone) -> Chip(text, tone, dot = tone == ChipTone.Live) }
                }
            }
            worktree.codex?.let { session ->
                if (session.running) {
                    AttachCommand(session.attachCommand, onCopy)
                } else {
                    Text("Codex session ended · worktree kept", color = Palette.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (!worktree.main && worktree.branch != null) {
                Text("Target: ${defaultBranch ?: "Default branch"}  ←  ${worktree.branch}", color = Palette.tealInk,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                BranchFlow(worktree.publish, Modifier.padding(top = 10.dp))
            }
            val branch = worktree.branch
            if (worktree.publish != null && branch != null) {
                PublishRow(worktree.publish, forge, branch, defaultBranch) { action -> onPublish(branch, action) }
            }
        }
    }
}

private fun stateColor(worktree: Worktree): Color = when {
    worktree.codex?.running == true -> Palette.teal
    worktree.missing || worktree.changedFiles == null -> Palette.bad
    worktree.changedFiles == 0 -> Palette.ok
    else -> Palette.warn
}

private fun tags(worktree: Worktree): List<Pair<String, ChipTone>> = listOfNotNull(
    worktree.codex?.let { if (it.running) "Codex live" to ChipTone.Live else "Codex" to ChipTone.Neutral },
    when {
        worktree.scheduledRun -> "agent run" to ChipTone.Neutral
        worktree.agentRun -> "agent branch" to ChipTone.Neutral
        else -> null
    },
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

/**
 * Every run works in a tmux session of its own on the server, so it survives a restart and can
 * be watched live over SSH. Most of the time nobody needs that, so the command stays folded away.
 */
@Composable
fun AttachDisclosure(command: String, onCopy: (String) -> Unit) {
    var open by rememberSaveable(command) { mutableStateOf(false) }
    Text(
        if (open) "Hide the terminal command" else "Watch live from a terminal", color = Palette.rustStrong, fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { open = !open }.padding(vertical = 8.dp),
    )
    if (open) {
        Text("Signed in to the server over SSH, this opens the agent's own terminal.", color = Palette.ink2, fontSize = 12.5.sp)
        AttachCommand(command, onCopy)
    }
}

/** A tmux attach command on a dark strip, with Copy. */
@Composable
fun AttachCommand(command: String, onCopy: (String) -> Unit) {
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().background(Palette.harbor, RoundedCornerShape(10.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            command, color = Palette.attachText, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(command) }) { Text("Copy", color = Palette.onHarbor, fontSize = 12.sp) }
    }
}

/** Where the repository and its worktrees live on the server; folded away by default. */
@Composable
fun PathsPanel(project: Project) {
    var open by remember { mutableStateOf(false) }
    Panel(padding = false) {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Glyphs.folder, contentDescription = null, tint = Palette.ink2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (open) "Hide paths & origin" else "Paths & origin", color = Palette.ink2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                project.originUrl?.let { PathLine("origin", it) }
                PathLine("repo", project.path)
                project.worktrees.forEach { PathLine(it.branch ?: "detached", it.path) }
            }
        }
    }
}

@Composable
private fun PathLine(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = Palette.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Palette.ink2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
    }
}

private const val CODEX_HINT = "Or work with Codex yourself: a new worktree with Codex in tmux, to attach over SSH."
