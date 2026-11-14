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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import com.llmmanager.android.data.Project

/** What the merge bar says and offers for a session, worked out once. */
private class MergeView(
    val look: MergeLook,
    val title: String,
    val detail: String,
    val checks: Checks? = null,
    /** The pull request's page, when the title links to it. */
    val link: String? = null,
    val button: MergeButton? = null,
)

private class MergeLook(val edge: Color, val glyph: ImageVector)

private class MergeButton(val action: String, val label: String, val during: String, val glyph: ImageVector, val careful: Boolean = false)

private val QUIET_BRANCH get() = MergeLook(Palette.paper3, Glyphs.branch)
private val QUIET_GONE get() = MergeLook(Palette.paper3, Glyphs.cross)

private fun mergeView(project: Project, session: ChatView): MergeView {
    val pr = session.pullRequest
    val base = project.defaultBranch ?: "the default branch"
    val name = pullRequestName(project)
    val ref = pr?.let { "$name ${prRef(project.forge, it.number)}" }.orEmpty()
    val link = pr?.url?.takeIf { it.startsWith("https://") }
    return when {
        session.merged -> mergedView(session, "Merged into $base", ref, link)
        pr?.state == "closed" -> MergeView(QUIET_GONE, "$ref was closed", "It was closed without merging.", link = link)
        session.closed -> MergeView(QUIET_GONE, "Branch removed", "This session's worktree is gone, so it can't be merged from here.")
        pr != null -> openView(session, ref, base, link)
        else -> unpublishedView(project, session, base)
    }
}

private fun mergedView(session: ChatView, title: String, ref: String, link: String?) = MergeView(
    MergeLook(Palette.ok, Glyphs.merge), title,
    listOf(ref, relativeTime(session.mergedAt ?: session.updatedAt)).filter { it.isNotBlank() }.joinToString(" · ") + ". " +
        if (session.open) "The branch is still on the server." else "The branch was removed. Start a new session for the next change.",
    link = link, button = MergeButton("merge", "Clean up", "Cleaning up", Glyphs.check).takeIf { session.open },
)

/** An open pull request: merge it, or merge it anyway when its checks are not green. */
private fun openView(session: ChatView, ref: String, base: String, link: String?): MergeView {
    val checks = checks(session.ci)
    val careful = checks != null && checks.tone != ChipTone.Ok
    val edge = when (checks?.tone) {
        ChipTone.Bad -> Palette.bad
        ChipTone.Warn -> Palette.warn
        else -> Palette.teal
    }
    return MergeView(
        MergeLook(edge, Glyphs.pullRequest), ref, plural(session.commits, "commit", "commits") + " into $base",
        checks ?: Checks("no checks reported", ChipTone.Neutral), link,
        MergeButton("merge", if (careful) "Merge anyway" else "Merge", "Merging", Glyphs.merge, careful),
    )
}

/** A branch without a pull request yet. */
private fun unpublishedView(project: Project, session: ChatView, base: String): MergeView {
    val name = pullRequestName(project).lowercase()
    val changes = plural(session.commits, "commit", "commits")
    return when {
        session.commits == 0 -> MergeView(QUIET_BRANCH, "No changes yet", "When the agent commits work, its $name shows here.")
        project.forge == null -> MergeView(
            QUIET_BRANCH, "$changes, kept on the server",
            "This repository has no GitHub, GitLab or Azure DevOps remote, so there is nothing to merge into.",
        )
        else -> MergeView(
            MergeLook(Palette.teal, Glyphs.branch), "$changes not in a $name yet", "Pushes the branch and opens it against $base.",
            button = MergeButton("pull-request", "Open $name", "Opening", Glyphs.pullRequest),
        )
    }
}

/**
 * The bar that stays on top of an open session: how far its pull request is, what the checks
 * say, and one large button for the next step. Merging asks nothing: the forge still applies
 * its own rules, and afterwards the server removes the branch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergeBar(project: Project, session: ChatView, working: Boolean, error: String?, onAction: (String) -> Unit) {
    val view = mergeView(project, session)
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().shadow(8.dp, shape).clip(shape).background(Palette.paper).height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(view.look.edge))
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val opens = view.link?.let { Modifier.clickable(role = Role.Button, onClickLabel = "Open in the browser") { uriHandler.openUri(it) } }
            Row(opens ?: Modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(view.look.glyph, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    view.title, color = Palette.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    textDecoration = if (view.link != null) TextDecoration.Underline else null,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                view.checks?.let { Chip(it.text, it.tone) }
                Text(view.detail, color = Palette.ink2, fontSize = 12.5.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
            view.button?.let { MergeAction(it, working, session.busy) { onAction(it.action) } }
            if (session.busy && view.button != null) Text("Wait until the agent is done.", color = Palette.ink2, fontSize = 12.sp)
            error?.let { Text(it, color = Palette.badInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun MergeAction(button: MergeButton, working: Boolean, busy: Boolean, onClick: () -> Unit) {
    val colours = if (button.careful) {
        ButtonDefaults.buttonColors(containerColor = Palette.field, contentColor = Palette.badInk)
    } else {
        ButtonDefaults.buttonColors(containerColor = Palette.rustStrong, contentColor = Palette.onRust)
    }
    Button(
        onClick = onClick, enabled = !working && !busy, colors = colours, shape = RoundedCornerShape(13.dp),
        border = if (button.careful) androidx.compose.foundation.BorderStroke(2.dp, Palette.bad) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.ink2, strokeWidth = 2.dp)
        } else {
            Icon(button.glyph, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (working) button.during else button.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** "Pull request", or GitLab's "Merge request". */
fun pullRequestName(project: Project, short: Boolean = false): String = when {
    project.forge == "gitlab" -> if (short) "MR" else "Merge request"
    else -> if (short) "PR" else "Pull request"
}

class Checks(val text: String, val tone: ChipTone)

/** What a pull request's checks say, in words; null when there are none. */
fun checks(ci: String?): Checks? = when (ci) {
    "success" -> Checks("checks passed", ChipTone.Ok)
    "failure" -> Checks("checks failed", ChipTone.Bad)
    "pending" -> Checks("checks running", ChipTone.Warn)
    else -> null
}
