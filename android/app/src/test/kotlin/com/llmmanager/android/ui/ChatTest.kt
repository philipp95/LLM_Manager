package com.llmmanager.android.ui

import com.llmmanager.android.data.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTest {
    /** A finished run of project "p"; ids named after a stage are that stage. */
    private fun run(id: String, createdAt: String, pipelineId: String? = null, branch: String? = null) = Run(
        id = id, projectId = "p", agent = "claude", stage = id.takeIf { it in setOf("review", "fix", "resume") } ?: "work",
        status = "succeeded", createdAt = createdAt, pipelineId = pipelineId, branch = branch,
    )

    @Test
    fun `a pipeline's stages and a branch's resumes form one turn, oldest first`() {
        val runs = listOf(
            run("fix", "2026-11-11T10:20:00Z", pipelineId = "pipe", branch = "run/app-1"),
            run("work", "2026-11-11T10:00:00Z", pipelineId = "pipe", branch = "run/app-1"),
            run("review", "2026-11-11T10:10:00Z", pipelineId = "pipe", branch = "run/app-1"),
            run("solo", "2026-11-11T11:00:00Z", branch = "run/app-2"),
            run("resume", "2026-11-11T12:00:00Z", branch = "run/app-2"),
            run("queued", "2026-11-11T13:00:00Z"),
            run("elsewhere", "2026-11-11T09:00:00Z").copy(projectId = "other"),
        )

        val turns = conversationTurns(runs, "p")

        assertEquals(listOf(listOf("work", "review", "fix"), listOf("solo", "resume"), listOf("queued")), turns.map { turn -> turn.map { it.id } })
        assertEquals(listOf(listOf("queued")), conversationTurns(runs, "p", limit = 1).map { turn -> turn.map { it.id } })
    }

    @Test
    fun `names for people, forges and agents`() {
        assertEquals("PH", initials("philipp95"))
        assertEquals("AS", initials("Anna Smith"))
        assertEquals("CC", initials("chatgpt-codex-connector[bot]"))
        assertEquals("?", initials("--"))
        assertEquals("!7", prRef("gitlab", 7))
        assertEquals("#42", prRef("azure-devops", 42))
        assertEquals("Azure DevOps", forgeName("azure-devops"))
        assertEquals(null, forgeName(null))
        assertEquals("codex", defaultReviewer("claude"))
        assertEquals("claude", defaultReviewer("gemini"))
    }
}
