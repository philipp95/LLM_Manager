package com.llmmanager.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Harbor. Panels are sand paper on a deep-water window, so components (buttons, fields,
 * dialogs, sheets) take a light scheme while the background stays dark.
 */
@Composable
fun LlmManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.rustStrong,
            onPrimary = Palette.onRust,
            primaryContainer = Palette.paper2,
            onPrimaryContainer = Palette.ink,
            secondary = Palette.teal,
            onSecondary = Color.White,
            secondaryContainer = Palette.paper3,
            onSecondaryContainer = Palette.ink,
            background = Palette.harbor,
            onBackground = Palette.onHarbor,
            surface = Palette.paper,
            onSurface = Palette.ink,
            surfaceVariant = Palette.paper2,
            onSurfaceVariant = Palette.ink2,
            surfaceContainerLowest = Palette.field,
            surfaceContainerLow = Palette.paper,
            surfaceContainer = Palette.paper,
            surfaceContainerHigh = Palette.paper,
            surfaceContainerHighest = Palette.paper2,
            outline = Palette.ink3,
            outlineVariant = Palette.paperLine,
            error = Palette.badInk,
        ),
        content = content,
    )
}

/**
 * The dashboard's palette, shared with the web page. Text on paper uses [ink] and [ink2]
 * (12.2:1 and 5.7:1); status colours have darker "Ink" steps for text. Series colours are
 * validated as one categorical set on [paper].
 */
object Palette {
    val harbor = Color(0xFF13282D)
    val harbor2 = Color(0xFF1B353B)
    val harborLine = Color(0xFF2C4B52)
    val onHarbor = Color(0xFFEDE3D1)
    val onHarbor2 = Color(0xFFA9BDBD)

    val paper = Color(0xFFF1E7D6)
    val paper2 = Color(0xFFE7DAC3)
    val paper3 = Color(0xFFD9C9AD)
    val paperLine = Color(0xFFD8C9AE)
    val field = Color(0xFFFBF6EC)
    val ink = Color(0xFF2B2621)
    val ink2 = Color(0xFF625850)
    val ink3 = Color(0xFF857A6E)

    val rust = Color(0xFFD2733A)
    val rustStrong = Color(0xFFA9501F)
    val onRust = Color(0xFFFFF8EE)
    val teal = Color(0xFF3E7F86)
    val tealInk = Color(0xFF2D5F64)
    val attachText = Color(0xFF9FD3D8)

    val ok = Color(0xFF4F8A5B)
    val okInk = Color(0xFF34623E)
    val warn = Color(0xFFC9962E)
    val warnInk = Color(0xFF7A5810)
    val bad = Color(0xFFB8483B)
    val badInk = Color(0xFF9C3A2F)

    // Chart series: agents keep these hues everywhere; commits use the harbor teal.
    val claude = Color(0xFFC25E28)
    val codex = Color(0xFF2B5DA8)
    val gemini = Color(0xFFA23B72)
    val commits = teal

    /** Initials avatars for people: muted, and white text on each passes 4.5:1. */
    val people = listOf(teal, Color(0xFF4A5A7A), Color(0xFF6B6A3A), Color(0xFF8A4B2E))

    fun agent(agent: String?): Color = when (agent) {
        "codex" -> codex
        "gemini" -> gemini
        else -> claude
    }
}
