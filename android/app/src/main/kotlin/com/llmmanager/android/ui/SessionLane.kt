package com.llmmanager.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** What a row's piece of the session tree shows. */
enum class LaneKind { Head, Rail, Knot, New, Open, Merged, Closed }

enum class LaneDot { Done, Failed, Working }

/** A row's piece of the tree: the rail of the default branch and, for a session, its branch with a dot per message. */
class Lane(val kind: LaneKind, val dots: List<LaneDot> = emptyList(), val on: Boolean = false)

val LANE_WIDTH = 46.dp
private val RAIL = Color(0xFF5F8088)
private val OPEN = Color(0xFFE9A06B)
private val MERGED = Color(0xFF8CC59A)
private val FAILED = Color(0xFFE07A6C)
private const val MAX_DOTS = 6

/**
 * Draws [lane]. The rail runs at x 11, a branch at x 33. A branch forks off the rail at the
 * bottom of its row and ends at the top: back in the rail when merged, an open ring while it
 * can take messages, a bar when it was closed. Messages are dots, the oldest lowest.
 */
fun DrawScope.drawLane(lane: Lane) {
    val rail = 11.dp.toPx()
    val x = 33.dp.toPx()
    val bend = 22.dp.toPx()
    val line = (if (lane.on) 3.dp else 2.dp).toPx()
    val h = size.height
    drawLine(RAIL, Offset(rail, if (lane.kind == LaneKind.Head) h / 2 else 0f), Offset(rail, h), 2.dp.toPx())
    when (lane.kind) {
        LaneKind.Head -> drawCircle(RAIL, 6.dp.toPx(), Offset(rail, h / 2))
        LaneKind.Knot -> {
            drawCircle(Palette.harbor, 6.dp.toPx(), Offset(rail, h / 2))
            drawCircle(RAIL, 5.dp.toPx(), Offset(rail, h / 2), style = Stroke(2.dp.toPx()))
        }
        LaneKind.Rail -> Unit
        else -> {
            val colour = when (lane.kind) {
                LaneKind.Merged -> MERGED
                LaneKind.Closed -> Palette.onHarbor2
                else -> OPEN
            }
            val dashed = lane.kind == LaneKind.Closed || lane.kind == LaneKind.New
            val dashes = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())) else null
            val stroke = Stroke(line, cap = StrokeCap.Round, pathEffect = dashes)
            val path = Path().apply {
                moveTo(rail, h)
                cubicTo(rail, h - bend * 0.6f, x, h - bend * 0.4f, x, h - bend)
                lineTo(x, bend)
                if (lane.kind == LaneKind.Merged) cubicTo(x, bend * 0.4f, rail, bend * 0.6f, rail, 3.dp.toPx()) else lineTo(x, bend * 0.6f)
            }
            drawPath(path, colour, style = stroke)
            drawDots(lane, colour, x, bend)
            drawEnd(lane.kind, colour, Offset(x, bend * 0.45f), rail)
        }
    }
}

private fun DrawScope.drawDots(lane: Lane, colour: Color, x: Float, bend: Float) {
    val dots = lane.dots.takeLast(MAX_DOTS)
    val span = size.height - 2 * bend
    dots.forEachIndexed { index, dot ->
        val centre = Offset(x, size.height - bend - span * (index + 0.5f) / dots.size)
        drawCircle(Palette.harbor, 7.dp.toPx(), centre)
        when (dot) {
            LaneDot.Working -> drawCircle(colour, 4.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
            else -> drawCircle(if (dot == LaneDot.Failed) FAILED else colour, 5.dp.toPx(), centre)
        }
    }
}

private fun DrawScope.drawEnd(kind: LaneKind, colour: Color, tip: Offset, rail: Float) {
    when (kind) {
        LaneKind.Merged -> drawCircle(colour, 5.dp.toPx(), Offset(rail, 3.dp.toPx()))
        LaneKind.Closed -> drawLine(colour, tip.copy(x = tip.x - 5.dp.toPx()), tip.copy(x = tip.x + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        else -> {
            drawCircle(Palette.harbor, 5.dp.toPx(), tip)
            drawCircle(colour, 4.5.dp.toPx(), tip, style = Stroke(2.dp.toPx()))
        }
    }
}
