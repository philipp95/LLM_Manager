package com.llmmanager.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.R

/** A brand mark from the Simple Icons path data in res/values/brand_marks.xml. */
@Composable
private fun brandMark(@StringRes path: Int): ImageVector {
    val data = stringResource(path)
    return remember(data) {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
            .build()
    }
}

/** The forge's mark in a soft square: GitHub and GitLab logos, "ADO" for Azure DevOps, a folder otherwise. */
@Composable
fun ForgeBadge(forge: String?, size: Dp = 30.dp) {
    val label = forgeName(forge) ?: "Local repository"
    Box(
        Modifier.size(size).background(Palette.paper2, RoundedCornerShape(size * 0.3f)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val mark = size * 0.6f
        when (forge) {
            "github" -> Icon(brandMark(R.string.mark_github), null, tint = Palette.ink, modifier = Modifier.size(mark))
            "gitlab" -> Icon(brandMark(R.string.mark_gitlab), null, tint = Palette.ink, modifier = Modifier.size(mark))
            "azure-devops" -> Text("ADO", color = Palette.ink, fontSize = (size.value * 0.32f).sp, fontWeight = FontWeight.ExtraBold)
            else -> Icon(Glyphs.folder, null, tint = Palette.ink, modifier = Modifier.size(mark))
        }
    }
}

/** The agent's mark in plain ink: Claude and Gemini logos, a terminal prompt for Codex. */
@Composable
fun AgentMark(agent: String, size: Dp = 18.dp, tint: Color = Palette.ink) {
    val vector = when (agent) {
        "claude" -> brandMark(R.string.mark_claude)
        "gemini" -> brandMark(R.string.mark_gemini)
        else -> Glyphs.terminal
    }
    Icon(vector, contentDescription = agentLabel(agent), tint = tint, modifier = Modifier.size(size))
}

/** An agent's name with its mark, for lists. */
@Composable
fun AgentChip(agent: String, prefix: String = "") {
    Chip(prefix + agentLabel(agent), leading = { AgentMark(agent, size = 13.dp, tint = Palette.ink2) })
}

/** A person's initials in a muted circle; the full name is read out and shown nowhere else. */
@Composable
fun PersonAvatar(name: String, size: Dp = 20.dp) {
    val colour = Palette.people[Math.floorMod(name.hashCode(), Palette.people.size)]
    Box(
        Modifier.size(size).background(colour, CircleShape).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** CI/CD result as a coloured disc with a glyph, so it never relies on colour alone. */
@Composable
fun CiMark(ci: String?, size: Dp = 18.dp) {
    val (glyph, colour, label) = when (ci) {
        "success" -> Triple(Glyphs.check, Palette.ok, "CI/CD passed")
        "failure" -> Triple(Glyphs.cross, Palette.bad, "CI/CD failed")
        "pending" -> Triple(Glyphs.clock, Palette.warn, "CI/CD running")
        else -> return
    }
    Box(
        Modifier.size(size).background(colour, CircleShape).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}
