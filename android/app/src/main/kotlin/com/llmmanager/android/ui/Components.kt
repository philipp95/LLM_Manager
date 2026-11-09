package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Raised card with the dashboard's gradient surface. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.raised, Palette.surface)), shape)
            .border(1.dp, Palette.line, shape)
            .then(if (padding) Modifier.padding(14.dp) else Modifier),
        content = content,
    )
}

enum class ChipTone(val text: Color, val border: Color, val fill: Color) {
    Neutral(Palette.muted, Palette.line, Color.White.copy(alpha = 0.03f)),
    Codex(Palette.codexText, Palette.codex.copy(alpha = 0.45f), Palette.codex.copy(alpha = 0.12f)),
    Agent(Palette.agentText, Palette.agent.copy(alpha = 0.45f), Palette.agent.copy(alpha = 0.12f)),
    Ok(Palette.ok, Palette.ok.copy(alpha = 0.3f), Color.Transparent),
    Warn(Palette.warn, Palette.warn.copy(alpha = 0.35f), Palette.warn.copy(alpha = 0.07f)),
    Bad(Palette.bad, Palette.bad.copy(alpha = 0.35f), Color.Transparent),
}

/** Status and identity chips always carry a word, never colour alone. */
@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.Neutral, dot: Boolean = false, mono: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.background(tone.fill, shape).border(1.dp, tone.border, shape).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).background(tone.text, RoundedCornerShape(50)))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text, color = tone.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        )
    }
}

@Composable
fun StatTile(value: Int, label: String, swatch: Color?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.background(Palette.surface, shape).border(1.dp, Palette.lineSoft, shape).padding(10.dp)) {
        Text(value.toString(), color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            swatch?.let {
                Box(Modifier.size(8.dp).background(it, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
            }
            Text(label.uppercase(), color = Palette.muted, fontSize = 10.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}
