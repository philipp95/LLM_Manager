package com.llmmanager.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Smallest clean axis maximum at or above [value]. */
fun niceMax(value: Int): Int {
    if (value <= 4) return maxOf(value, 1)
    var magnitude = 1
    while (magnitude * 10 <= value) magnitude *= 10
    return listOf(1, 2, 5, 10).map { it * magnitude }.first { it >= value }
}

/**
 * Daily columns; [series] stack bottom-up with a 2dp surface gap, the top segment
 * rounded 4dp at its data end and square at the baseline. Tapping or dragging
 * selects a day via [onSelect].
 */
@Composable
fun ColumnChart(
    labels: List<String>,
    series: List<ChartSeries>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    formatTick: (Int) -> String = { it.toString() },
) {
    val measurer = rememberTextMeasurer()
    val days = labels.size
    val totals = List(days) { day -> series.sumOf { it.values.getOrElse(day) { 0 } } }
    val max = niceMax(totals.maxOrNull() ?: 0)
    Box(modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp)
                .pointerInput(days) { detectTapGestures { onSelect(dayAt(it.x, size.width.toFloat(), PLOT_LEFT.toPx(), days)) } }
                .pointerInput(days) {
                    detectHorizontalDragGestures { change, _ -> onSelect(dayAt(change.position.x, size.width.toFloat(), PLOT_LEFT.toPx(), days)) }
                },
        ) {
            val left = PLOT_LEFT.toPx()
            val top = 8.dp.toPx()
            val bottom = 20.dp.toPx()
            val plotW = size.width - left
            val plotH = size.height - top - bottom
            val band = plotW / days
            val barW = (band - 2.dp.toPx()).coerceIn(2.dp.toPx(), 24.dp.toPx())
            fun y(v: Float) = top + plotH - v / max * plotH
            val ticks = if (max >= 4 && max % 2 == 0) listOf(0, max / 2, max) else listOf(0, max)
            ticks.forEach { tick ->
                drawLine(Palette.paperLine, Offset(left, y(tick.toFloat())), Offset(size.width, y(tick.toFloat())), 1.dp.toPx())
                label(measurer, formatTick(tick), Offset(left - 6.dp.toPx(), y(tick.toFloat())), alignEnd = true)
            }
            selected?.let {
                drawRoundRect(Palette.ink.copy(alpha = 0.07f), Offset(left + it * band, top), Size(band, plotH), CornerRadius(4.dp.toPx()))
            }
            for (day in 0 until days) {
                val x = left + day * band + (band - barW) / 2
                var base = top + plotH
                val visible = series.filter { it.values.getOrElse(day) { 0 } > 0 }
                visible.forEachIndexed { index, s ->
                    val gap = if (index > 0) 2.dp.toPx() else 0f
                    val h = (s.values[day].toFloat() / max * plotH - gap).coerceAtLeast(1f)
                    base -= h + gap
                    column(Offset(x, base), Size(barW, h), s.color, roundTop = index == visible.lastIndex)
                }
            }
            listOf(0, (days - 1) / 2, days - 1).distinct().forEach { day ->
                val anchor = Offset(left + day * band + band / 2, size.height - 8.dp.toPx())
                label(measurer, shortDay(labels[day]), anchor, alignEnd = day == days - 1, alignStart = day == 0)
            }
        }
    }
}

private val PLOT_LEFT = 32.dp

/** The day band under [x], or null left of the plot. */
internal fun dayAt(x: Float, width: Float, left: Float, days: Int): Int? {
    if (days <= 0 || x < left || width <= left) return null
    return ((x - left) / ((width - left) / days)).toInt().coerceIn(0, days - 1)
}

private fun DrawScope.column(topLeft: Offset, size: Size, color: Color, roundTop: Boolean) {
    val r = if (roundTop) minOf(4.dp.toPx(), size.width / 2, size.height) else 0f
    val (x, y) = topLeft
    val path = Path().apply {
        addRoundRect(
            RoundRect(x, y, x + size.width, y + size.height, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)),
        )
    }
    drawPath(path, color)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, anchor: Offset, alignEnd: Boolean = false, alignStart: Boolean = false) {
    val layout = measurer.measure(text, TextStyle(color = Palette.ink2, fontSize = 10.sp))
    val x = when {
        alignEnd -> anchor.x - layout.size.width
        alignStart -> anchor.x
        else -> anchor.x - layout.size.width / 2f
    }
    drawText(layout, topLeft = Offset(x, anchor.y - layout.size.height / 2f))
}

data class ChartSeries(val values: List<Int>, val color: Color)
