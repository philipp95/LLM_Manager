package com.llmmanager.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project

/**
 * The sessions to choose from, drawn as the repository's branches: the default branch is the
 * rail on the left, "New session" starts a branch off it, every session is a branch with a dot
 * per message, and merged ones bend back into the rail and fold away below. Newest on top.
 */
@Composable
fun SessionTree(project: Project, sessions: List<ChatView>, selected: String?, onSelect: (String?) -> Unit) {
    val open = sessions.filter { !it.merged && !it.closed }
    val merged = sessions.filter { it.merged }.sortedByDescending { it.mergedAt ?: it.updatedAt }
    val closed = sessions.filter { it.closed }
    var expanded by rememberSaveable(project.id) { mutableStateOf(false) }
    val shown = if (expanded || open.size <= COLLAPSED_MAX) open else open.take(COLLAPSED_MAX - 1)
    val tree = !project.isServer
    Column {
        if (tree) TreeHead(project, open.size, merged.size)
        TreeRow(Lane(LaneKind.New, on = selected == null).takeIf { tree }) { NewSession(project, selected == null) { onSelect(null) } }
        shown.forEach { session -> SessionRow(project, session, session.id == selected, tree) { onSelect(session.id) } }
        if (open.size > COLLAPSED_MAX) {
            TreeRow(Lane(LaneKind.Rail).takeIf { tree }) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show fewer" else "Show all ${open.size} open sessions", color = Palette.onHarbor)
                }
            }
        }
        SessionGroup(project, GroupText("Merged", "in ${project.defaultBranch ?: "the default branch"}"), merged, selected, onSelect)
        SessionGroup(project, GroupText("Closed", "branch removed, not merged"), closed, selected, onSelect)
    }
}

private class GroupText(val label: String, val note: String)

/** The top of the rail: the default branch and how many branches hang off it. */
@Composable
private fun TreeHead(project: Project, open: Int, merged: Int) {
    TreeRow(Lane(LaneKind.Head)) {
        Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.branch, contentDescription = null, tint = Palette.onHarbor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(project.defaultBranch ?: "default branch", color = Palette.onHarbor, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text(
                plural(open, "open branch", "open branches") + if (merged > 0) " · $merged merged" else "",
                color = Palette.onHarbor2, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One row of the tree: its piece of the drawing, as tall as the card beside it. */
@Composable
private fun TreeRow(lane: Lane?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (lane != null) {
            Canvas(Modifier.width(LANE_WIDTH).fillMaxHeight().clearAndSetSemantics { }) { drawLane(lane) }
        }
        Box(Modifier.weight(1f).padding(vertical = 5.dp)) { content() }
    }
}

@Composable
private fun NewSession(project: Project, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .background(Palette.rustStrong, shape)
            .border(2.dp, if (selected) Palette.onRust else Palette.rustStrong, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Glyphs.plus, contentDescription = null, tint = Palette.onRust, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("New session", color = Palette.onRust, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(if (project.isServer) "Works on the server itself" else "Starts a branch of its own", color = Palette.onRust, fontSize = 12.sp)
        }
    }
}

/** One session: what was asked, where it works, and how far its pull request is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(project: Project, session: ChatView, selected: Boolean, tree: Boolean, onClick: () -> Unit) {
    val kind = laneKind(session)
    val done = kind != LaneKind.Open
    val shape = RoundedCornerShape(14.dp)
    TreeRow(Lane(kind, session.turns.map(::turnDot), selected).takeIf { tree }) {
        Column(
            Modifier.fillMaxWidth()
                .semantics { this.selected = selected }
                .background(if (done) Palette.paper2 else Palette.paper, shape)
                .border(2.dp, if (selected) Palette.rust else if (done) Palette.paper2 else Palette.paper, shape)
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentMark(session.agent, size = 16.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    session.title, color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                SessionStatus(session)
            }
            Text(
                workplace(project, session), color = Palette.ink2, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SessionPullRequest(project, session)
                if (session.sharesBranch) Text("shared branch", color = Palette.warnInk, fontSize = 12.sp)
                Text(plural(session.turns.size, "message", "messages"), color = Palette.ink2, fontSize = 12.sp)
                Text(
                    if (session.merged) "merged " + relativeTime(session.mergedAt ?: session.updatedAt) else relativeTime(session.updatedAt),
                    color = Palette.ink2, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SessionStatus(session: ChatView) {
    when {
        session.merged -> Chip("merged", ChipTone.Ok)
        session.closed && !session.busy -> Chip("closed")
        else -> session.state?.let { RunStatusChip(it) }
    }
}

/** The open pull request's number and what its checks say. */
@Composable
private fun SessionPullRequest(project: Project, session: ChatView) {
    val pr = session.pullRequest?.takeIf { !session.merged } ?: return
    val open = pr.state == "open"
    val name = pullRequestName(project, short = true) + " " + prRef(project.forge, pr.number)
    Chip(if (open) name else "$name ${pr.state}", if (open) ChipTone.Live else ChipTone.Neutral)
    if (open) checks(session.ci)?.let { Chip(it.text, it.tone) }
}

/** A folded group of the tree: sessions whose branches are no longer open. */
@Composable
private fun SessionGroup(project: Project, text: GroupText, sessions: List<ChatView>, selected: String?, onSelect: (String?) -> Unit) {
    if (sessions.isEmpty()) return
    var opened by rememberSaveable(project.id, text.label) { mutableStateOf(false) }
    val open = opened || sessions.any { it.id == selected }
    val tree = !project.isServer
    val shape = RoundedCornerShape(12.dp)
    TreeRow(Lane(LaneKind.Knot).takeIf { tree }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Palette.harbor2, shape).border(1.dp, Palette.harborLine, shape)
                .clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Unfold") {
                    if (open && sessions.any { it.id == selected }) onSelect(null)
                    opened = !open
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${text.label} · ${sessions.size}", color = Palette.onHarbor, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            Spacer(Modifier.width(8.dp))
            Text(text.note, color = Palette.onHarbor2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", color = Palette.onHarbor, fontSize = 12.5.sp)
        }
    }
    if (open) sessions.forEach { session -> SessionRow(project, session, session.id == selected, tree) { onSelect(session.id) } }
}

private fun laneKind(session: ChatView) = when {
    session.merged -> LaneKind.Merged
    session.closed -> LaneKind.Closed
    else -> LaneKind.Open
}

private fun turnDot(turn: com.llmmanager.android.data.ChatTurn): LaneDot = when {
    turn.runs.any { it.active } -> LaneDot.Working
    turn.runs.lastOrNull()?.status == "failed" -> LaneDot.Failed
    else -> LaneDot.Done
}

private const val COLLAPSED_MAX = 8
