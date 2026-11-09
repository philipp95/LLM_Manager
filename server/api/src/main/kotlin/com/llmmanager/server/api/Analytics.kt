package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/** Day-bucketed activity across all projects, for the dashboard's charts. */
@Serializable
data class Analytics(
    /** ISO dates, oldest first, in the server's time zone; every per-day list aligns with it. */
    val days: List<String>,
    val commitsPerDay: List<Int>,
    /** Codex sessions started per day, from worktrees that still exist. */
    val codexStartsPerDay: List<Int>,
    /** Agent runs started per day, from worktrees that still exist. */
    val agentRunsPerDay: List<Int>,
    /** Projects with commits in the window, most first. */
    val busiest: List<ProjectCommits>,
) {
    companion object {
        private const val BUSIEST_LIMIT = 8

        fun of(window: ActivityWindow, projects: List<Project>): Analytics {
            val worktrees = projects.flatMap { it.worktrees }
            return Analytics(
                days = window.days.map { it.toString() },
                commitsPerDay = window.days.indices.map { day -> projects.sumOf { it.commitsPerDay.getOrElse(day) { 0 } } },
                codexStartsPerDay = window.count(worktrees.filter { it.codex != null }.mapNotNull { it.startedAt }),
                agentRunsPerDay = window.count(worktrees.filter { it.agentRun }.mapNotNull { it.startedAt }),
                busiest = projects.map { ProjectCommits(it.id, it.name, it.commitsPerDay.sum()) }
                    .filter { it.commits > 0 }
                    .sortedWith(compareByDescending<ProjectCommits> { it.commits }.thenBy { it.name.lowercase() })
                    .take(BUSIEST_LIMIT),
            )
        }
    }
}

@Serializable
data class ProjectCommits(val id: String, val name: String, val commits: Int)

/** The last [length] calendar days ending today, in [clock]'s zone. */
class ActivityWindow(private val clock: Clock, length: Int = DEFAULT_DAYS) {
    val days: List<LocalDate> = LocalDate.now(clock).let { today -> (length - 1 downTo 0).map { today.minusDays(it.toLong()) } }

    /** Buckets ISO-8601 timestamps by local day; values outside the window are ignored. */
    fun count(timestamps: List<String>): List<Int> {
        val counts = IntArray(days.size)
        val first = days.first()
        timestamps.forEach { text ->
            val day = parse(text)?.atZoneSameInstant(clock.zone)?.toLocalDate() ?: return@forEach
            val index = (day.toEpochDay() - first.toEpochDay()).toInt()
            if (index in counts.indices) counts[index]++
        }
        return counts.toList()
    }

    private fun parse(text: String): OffsetDateTime? = try {
        OffsetDateTime.parse(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val DEFAULT_DAYS = 30
    }
}

/** When a Codex session or agent run began, for the sessions-per-day chart. */
object RunStart {
    private val CODEX_STAMP = Regex("""-(\d{8}-\d{6})-[0-9a-f]{4}$""")
    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Codex branches carry their UTC start time in the name. Agent runs do not, so
     * their worktree's `.git` link file, written once by `git worktree add`, dates them.
     */
    fun of(branch: String, worktree: File?): String? = when {
        branch.startsWith(CodexLauncher.BRANCH_PREFIX) -> CODEX_STAMP.find(branch)?.groupValues?.get(1)?.let { stamp ->
            try {
                LocalDateTime.parse(stamp, STAMP_FORMAT).atOffset(ZoneOffset.UTC).toString()
            } catch (_: DateTimeParseException) {
                null
            }
        }
        branch.startsWith(ProjectScanner.AGENT_BRANCH_PREFIX) -> worktree?.let { File(it, ".git") }?.takeIf { it.isFile }?.let { link ->
            Files.readAttributes(link.toPath(), BasicFileAttributes::class.java).lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC).toString()
        }
        else -> null
    }
}
