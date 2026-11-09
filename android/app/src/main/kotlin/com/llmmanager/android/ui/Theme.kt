package com.llmmanager.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun LlmManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            secondary = Palette.accent2,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.raised,
            onSurfaceVariant = Palette.muted,
            surfaceContainerHigh = Palette.raised,
            outline = Palette.line,
            outlineVariant = Palette.lineSoft,
            error = Palette.bad,
        ),
        content = content,
    )
}

/** The dashboard's palette, shared with the web page. Series colours are validated for this surface. */
object Palette {
    val background = Color(0xFF0B0D12)
    val surface = Color(0xFF12151D)
    val raised = Color(0xFF191D28)
    val line = Color(0xFF232938)
    val lineSoft = Color(0xFF1C2130)
    val text = Color(0xFFE8EAF0)
    val muted = Color(0xFF8A93A6)
    val faint = Color(0xFF5C6478)
    val accent = Color(0xFF8B7CF6)
    val accent2 = Color(0xFF5EC4FF)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val bad = Color(0xFFF87171)

    // Chart series: Codex = categorical slot 1, agent runs = slot 2, commits = accent.
    val codex = Color(0xFF3987E5)
    val agent = Color(0xFFD95926)
    val commits = Color(0xFF9085E9)
    val gemini = Color(0xFF199E70)
    val codexText = Color(0xFF8FBAF2)
    val agentText = Color(0xFFF0A07E)
    val geminiText = Color(0xFF6FD3AD)
}
