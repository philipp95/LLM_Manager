package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.PullRequestRef

/** Choose a session to continue, or "New session" for a new one on a branch of its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionList(project: Project, sessions: List<ChatView>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by rememberSaveable(project.id) { mutableStateOf(false) }
    val shown = if (expanded || sessions.size <= COLLAPSED_MAX) sessions else sessions.take(COLLAPSED_MAX - 1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SessionRow(selected == null, onClick = { onSelect(null) }) {
            Text("+  New session", color = Palette.rustStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Starts on a branch of its own", color = Palette.ink2, fontSize = 12.sp)
        }
        shown.forEach { session ->
            SessionRow(session.id == selected, onClick = { onSelect(session.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentMark(session.agent, size = 16.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        session.title, color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    session.state?.let {
                        Spacer(Modifier.width(6.dp))
                        RunStatusChip(it)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(session.branch ?: "new branch", color = Palette.ink2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (session.sharesBranch) Text("shared branch", color = Palette.warnInk, fontSize = 12.sp)
                    sessionPullRequest(project, session)?.let { pr ->
                        val kind = if (project.forge == "gitlab") "MR" else "PR"
                        Text("$kind ${prRef(project.forge, pr.number)} ${pr.state}", color = Palette.ink2, fontSize = 12.sp)
                    }
                    Text(relativeTime(session.updatedAt), color = Palette.ink2, fontSize = 12.sp)
                }
            }
        }
        if (sessions.size > COLLAPSED_MAX) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${sessions.size} sessions") }
        }
    }
}

@Composable
private fun SessionRow(selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .semantics { this.selected = selected }
            .background(Palette.field, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.ink else Palette.paperLine, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

/** The pull request of a session's branch, when the server opened one. */
fun sessionPullRequest(project: Project, session: ChatView): PullRequestRef? =
    session.branch?.let { branch -> project.worktrees.firstOrNull { it.branch == branch } }?.publish?.pullRequest?.takeIf { it.url.startsWith("https://") }

private const val COLLAPSED_MAX = 6
