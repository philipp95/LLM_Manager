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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A sand-paper panel on the harbor background. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(4.dp, shape)
            .background(Palette.paper, shape)
            .then(if (padding) Modifier.padding(14.dp) else Modifier),
        content = content,
    )
}

/** Chip colours on paper; status text uses the darker ink steps. */
enum class ChipTone(val text: Color, val border: Color, val fill: Color) {
    Neutral(Palette.ink2, Palette.paperLine, Palette.paper2),
    Live(Palette.tealInk, Palette.teal.copy(alpha = 0.4f), Palette.teal.copy(alpha = 0.16f)),
    Ok(Palette.okInk, Palette.ok.copy(alpha = 0.35f), Palette.ok.copy(alpha = 0.14f)),
    Warn(Palette.warnInk, Palette.warn.copy(alpha = 0.45f), Palette.warn.copy(alpha = 0.18f)),
    Bad(Palette.badInk, Palette.bad.copy(alpha = 0.35f), Palette.bad.copy(alpha = 0.12f)),
}

/** Status and identity chips always carry a word, never colour alone. */
@Composable
fun Chip(
    text: String,
    tone: ChipTone = ChipTone.Neutral,
    dot: Boolean = false,
    mono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.background(tone.fill, shape).border(1.dp, tone.border, shape).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).background(tone.text, RoundedCornerShape(50)))
            Spacer(Modifier.width(5.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text, color = tone.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** A heading drawn on the harbor background, between panels. */
@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Palette.onHarbor2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
}

/** A heading inside a panel. */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, modifier = modifier)
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = Palette.ink2, fontSize = 12.sp)
    }
}

/** Secondary text inside a panel. */
@Composable
fun Muted(text: String) {
    Text(text, color = Palette.ink2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
}

/** Secondary text on the harbor background. */
@Composable
fun BackgroundNote(text: String) {
    Text(text, color = Palette.onHarbor2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
}
