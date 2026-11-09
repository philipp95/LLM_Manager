package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class AnalyticsTest {
    @TempDir
    lateinit var directory: Path

    // 2026-11-09 10:00 in Brussels; the window is the 30 local days ending that day.
    private val clock = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneId.of("Europe/Brussels"))

    @Test
    fun `window buckets timestamps by local day and ignores those outside it`() {
        val window = ActivityWindow(clock, length = 3)

        assertEquals(listOf("2026-11-07", "2026-11-08", "2026-11-09"), window.days.map { it.toString() })
        val counts = window.count(
            listOf(
                "2026-11-08T23:30:00Z", // 00:30 on the 9th in Brussels
                "2026-11-08T12:00:00+01:00",
                "2026-11-07T00:00:00+01:00",
                "2026-11-06T22:59:59Z", // still the 6th locally: outside
                "2026-11-10T00:00:00+01:00", // future: outside
                "not a date",
            ),
        )
        assertEquals(listOf(1, 1, 1), counts)
    }

    @Test
    fun `projects carry commits per day and analytics aggregate and rank them`() {
        val busy = repository("repos/busy")
        commitAt(busy, "2026-11-09T08:00:00+01:00")
        commitAt(busy, "2026-11-09T09:00:00+01:00")
        commitAt(busy, "2026-11-01T12:00:00+01:00")
        // Before the window, and dated after the newer commits: `git log --since` would stop here.
        commitAt(busy, "2026-09-01T12:00:00+02:00")
        val quiet = repository("repos/quiet")
        commitAt(quiet, "2026-11-08T12:00:00+01:00")
        repository("repos/idle")
        // A codex worktree dates itself by its branch name.
        git(quiet, "worktree", "add", "-b", "codex/quiet-20261108-153000-abcd", File(directory.toFile(), "wt/codex").path)

        val snapshot = ProjectScanner(listOf("${directory.resolve("repos")}/*"), clock = clock).snapshot()

        val byName = snapshot.projects.associateBy { it.name }
        assertEquals(30, byName.getValue("busy").commitsPerDay.size)
        assertEquals(2, byName.getValue("busy").commitsPerDay.last())
        assertEquals(3, byName.getValue("busy").commitsPerDay.sum())
        assertEquals(listOf("busy", "quiet"), snapshot.analytics.busiest.map { it.name })
        assertEquals(listOf(3, 1), snapshot.analytics.busiest.map { it.commits })
        assertEquals(4, snapshot.analytics.commitsPerDay.sum())
        assertEquals("2026-11-09", snapshot.analytics.days.last())
        val codex = byName.getValue("quiet").worktrees.single { it.codex != null }
        assertEquals("2026-11-08T15:30Z", codex.startedAt)
        assertEquals(1, snapshot.analytics.codexStartsPerDay[28])
        assertEquals(0, snapshot.analytics.agentRunsPerDay.sum())
    }

    private fun repository(relative: String): File {
        val repo = directory.resolve(relative).toFile().also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        return repo
    }

    private fun commitAt(repo: File, iso: String) {
        val process = ProcessBuilder(
            "git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "at $iso",
        ).directory(repo).redirectErrorStream(true)
        process.environment()["GIT_AUTHOR_DATE"] = iso
        process.environment()["GIT_COMMITTER_DATE"] = iso
        val started = process.start()
        val output = started.inputStream.bufferedReader().readText()
        check(started.waitFor(30, TimeUnit.SECONDS) && started.exitValue() == 0) { "commit failed: $output" }
    }

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
