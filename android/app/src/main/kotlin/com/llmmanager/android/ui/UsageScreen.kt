package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.AgentUsage
import com.llmmanager.android.data.LimitWindow
import com.llmmanager.android.data.UsageResponse
import kotlin.math.roundToInt

@Composable
fun UsageScreen(usage: UsageResponse?, error: String?) {
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        if (error != null) item { Panel { Text(error, color = Palette.bad) } }
        if (usage == null) {
            if (error == null) item { Muted("Loading usage…") }
            return@LazyColumn
        }
        item { AgentUsagePanel("Claude", Palette.agent, usage.claude, usage.days) }
        item { AgentUsagePanel("Codex", Palette.codex, usage.codex, usage.days) }
        item { Muted("From local records on the server: Claude Code and Codex session logs, and Claude's status line. Tokens exclude cache reads.") }
    }
}

@Composable
private fun AgentUsagePanel(name: String, color: Color, usage: AgentUsage, days: List<String>) {
    var selected by remember(usage) { mutableStateOf<Int?>(null) }
    val total = usage.tokensPerDay.sum()
    val cache = usage.cacheReadsPerDay.sum()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Text(name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("Plan limits")
        usage.windows.forEach { LimitMeter(it) }
        usage.windowsAsOf?.let { Muted("As of ${relativeTime(it)}") }
        usage.note?.let { Muted(it) }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Tokens · last ${days.size} days")
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(compact(total), color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(if (cache > 0) "tokens · ${compact(cache)} more read from cache" else "tokens", color = Palette.muted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(
            selected?.let { "${longDay(days[it])} · ${compact(usage.tokensPerDay.getOrElse(it) { 0 })} tokens" } ?: "Tap a day for details",
            color = Palette.muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
        if (total > 0 && days.isNotEmpty()) {
            ColumnChart(
                days, listOf(ChartSeries(usage.tokensPerDay.map { it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }, color)),
                selected, { selected = it }, "$name tokens per day, ${compact(total)} in total",
                formatTick = { compact(it.toLong()) },
            )
        } else {
            Muted("No usage recorded in this window.")
        }
        if (usage.models.size > 1) {
            Spacer(Modifier.height(14.dp))
            SectionTitle("By model")
            val top = usage.models.first().tokens.coerceAtLeast(1)
            usage.models.forEach { model ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.model, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(0.55f).height(8.dp).background(Palette.line, RoundedCornerShape(50))) {
                        val share = (model.tokens.toFloat() / top).coerceAtLeast(0.03f)
                        Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(color, RoundedCornerShape(50)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(compact(model.tokens), color = Palette.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** The fill carries severity; the track is a lighter step of the same colour. */
@Composable
private fun LimitMeter(window: LimitWindow) {
    val pct = window.usedPercent.coerceIn(0.0, 100.0)
    val fill = when {
        pct >= 90 -> Palette.bad
        pct >= 70 -> Palette.warn
        else -> Palette.accent
    }
    Column(Modifier.padding(top = 10.dp).semantics { contentDescription = "${window.label} limit ${pct.roundToInt()}% used" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(window.label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f).height(10.dp).background(fill.copy(alpha = 0.18f), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100).toFloat()).background(fill, RoundedCornerShape(50)))
            }
            Spacer(Modifier.width(10.dp))
            Text("${pct.roundToInt()}%", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        window.resetsAt?.let {
            Text(
                "resets ${untilTime(it)} · ${localDateTime(it)}", color = Palette.muted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 64.dp, top = 2.dp),
            )
        }
    }
}
