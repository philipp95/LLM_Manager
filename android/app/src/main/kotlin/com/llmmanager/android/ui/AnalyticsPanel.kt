package com.llmmanager.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.Analytics

@Composable
fun AnalyticsPanel(analytics: Analytics) {
    if (analytics.days.isEmpty()) return
    val commits = analytics.commitsPerDay.sum()
    val codex = analytics.codexStartsPerDay.sum()
    val agents = analytics.agentRunsPerDay.sum()
    var commitDay by remember(analytics) { mutableStateOf<Int?>(null) }
    var sessionDay by remember(analytics) { mutableStateOf<Int?>(null) }

    Panel {
        SectionTitle("Activity · last ${analytics.days.size} days")
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("%,d".format(commits), color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(if (commits == 1) "commit across all projects" else "commits across all projects", color = Palette.muted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Readout(commitDay?.let { "${longDay(analytics.days[it])} · ${plural(analytics.commitsPerDay[it], "commit", "commits")}" } ?: "Tap a day for details")
        if (commits > 0) {
            ColumnChart(
                analytics.days, listOf(ChartSeries(analytics.commitsPerDay, Palette.commits)), commitDay, { commitDay = it },
                "Commits per day, ${plural(commits, "commit", "commits")} in total",
            )
        } else {
            Muted("No commits in the last ${analytics.days.size} days.")
        }

        Spacer(Modifier.height(18.dp))
        Text("Sessions started per day", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(Palette.codex, "Codex")
            LegendItem(Palette.agent, "Agent runs")
        }
        Readout(
            sessionDay?.let {
                "${longDay(analytics.days[it])} · ${plural(analytics.codexStartsPerDay[it], "Codex session", "Codex sessions")}, " +
                    plural(analytics.agentRunsPerDay[it], "agent run", "agent runs")
            } ?: "${plural(codex, "Codex session", "Codex sessions")} · ${plural(agents, "agent run", "agent runs")}",
        )
        if (codex + agents > 0) {
            ColumnChart(
                analytics.days,
                listOf(ChartSeries(analytics.codexStartsPerDay, Palette.codex), ChartSeries(analytics.agentRunsPerDay, Palette.agent)),
                sessionDay, { sessionDay = it },
                "Sessions per day: $codex Codex, $agents agent runs",
            )
        } else {
            Muted("No Codex sessions or agent runs yet. Start one from a project below.")
        }

        Spacer(Modifier.height(18.dp))
        Text("Busiest projects", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val top = analytics.busiest.firstOrNull()?.commits ?: 0
        if (top == 0) Muted("No commits in this window.")
        analytics.busiest.forEach { project ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(project.name, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(0.6f).height(8.dp).background(Palette.line, RoundedCornerShape(50))) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((project.commits.toFloat() / top).coerceAtLeast(0.03f))
                            .background(Palette.commits, RoundedCornerShape(50)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(project.commits.toString(), color = Palette.muted, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun Readout(text: String) {
    Text(text, color = Palette.muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
fun Muted(text: String) {
    Column { Text(text, color = Palette.faint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) }
}
