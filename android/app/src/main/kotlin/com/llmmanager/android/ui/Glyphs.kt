package com.llmmanager.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line glyphs drawn like the web page's icons: 24-unit paths, round 2-unit strokes. */
object Glyphs {
    val terminal by lazy { stroked("M4 17l6-6-6-6M12 19h8") }
    val folder by lazy { stroked("M3 6h6l2 2h10v11H3z") }
    val link by lazy { stroked("M14 3h7v7M10 14 21 3M19 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5") }
    val branch by lazy { stroked("M6 3v12M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9a9 9 0 0 1-9 9") }
    val merge by lazy { stroked("M18 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21V9a9 9 0 0 0 9 9") }
    val pullRequest by lazy { stroked("M18 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM13 6h3a2 2 0 0 1 2 2v7M6 9v12") }
    val check by lazy { stroked("M5 12l5 5L20 7", width = 3f) }
    val cross by lazy { stroked("M6 6l12 12M18 6 6 18", width = 3f) }
    val pin by lazy {
        stroked(
            "M12 17v5M9 10.8a2 2 0 0 1-1.1 1.8l-1.8.9A2 2 0 0 0 5 15.2V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.8a2 2 0 0 0-1.1-1.8l-1.8-.9" +
                "A2 2 0 0 1 15 10.8V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
        )
    }
    val chart by lazy { stroked("M5 20V11M12 20V4M19 20v-6") }
    val server by lazy { stroked("M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01") }
    val plus by lazy { stroked("M12 5v14M5 12h14", width = 2.6f) }
    val clock by lazy { stroked("M12 7v5l3 2M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18z", width = 2.6f) }

    private fun stroked(path: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                PathParser().parsePathString(path).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
