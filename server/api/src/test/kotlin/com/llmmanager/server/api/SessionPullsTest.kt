package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class SessionPullsTest {
    @TempDir
    lateinit var directory: Path

    private fun store(state: String): BranchStore = BranchStore(File(directory.toFile(), state).also { it.mkdirs() }).also {
        it.put(BranchRecord("p", "run/a", pullRequest = PullRequestRef("gitlab", 7, "https://gitlab.example.com/t/a/-/merge_requests/7", state)))
    }

    @Test
    fun `the record is brought up to date with what the forge reports`() {
        val seen = PullRequestSummary(7, "t", "u", "merged", branch = "run/a", mergedAt = "2026-11-14T10:00:00Z", ci = "success")
        val pulls = SessionPulls(store("opened")) { _, number -> seen.takeIf { number == 7L } }

        val state = pulls.of("p", "run/a")!!

        assertEquals("merged", state.pullRequest.state)
        assertEquals("success", state.ci)
        assertEquals("2026-11-14T10:00:00Z", state.mergedAt)
        assertNull(pulls.of("p", "run/other"))
    }

    @Test
    fun `forge names for states become open, merged and closed, and another branch's pull request is ignored`() {
        val other = PullRequestSummary(7, "t", "u", "closed", branch = "feature/x", ci = "failure")
        val open = SessionPulls(store("active")) { _, _ -> other }.of("p", "run/a")!!
        assertEquals("open", open.pullRequest.state)
        assertNull(open.ci)
        assertEquals("merged", SessionPulls(store("completed")) { _, _ -> null }.of("p", "run/a")!!.pullRequest.state)
    }
}
