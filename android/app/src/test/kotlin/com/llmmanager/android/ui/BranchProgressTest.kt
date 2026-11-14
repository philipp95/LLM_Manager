package com.llmmanager.android.ui

import com.llmmanager.android.data.Project
import com.llmmanager.android.data.Publish
import com.llmmanager.android.data.PullRequestRef
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchProgressTest {
    @Test
    fun `publish progression handles each forge and unpushed updates`() {
        assertEquals("Local branch", branchProgress(null).label)
        assertEquals(1, branchProgress(Publish(pushed = true, upToDate = true)).step)
        assertEquals("Push update", branchProgress(Publish(pushed = true)).label)
        for (state in listOf("open", "opened", "active")) {
            assertEquals(2, branchProgress(Publish(pullRequest = pr(state))).step)
        }
        for (state in listOf("merged", "completed")) {
            assertEquals(3, branchProgress(Publish(pullRequest = pr(state))).step)
        }
        assertEquals("PR closed", branchProgress(Publish(pullRequest = pr("abandoned"))).label)
    }

    @Test
    fun `automatic publishing requires an explicit choice and a forge`() {
        val project = Project("p", "a/b", forge = "github")
        assertEquals(false, publishRequested(project, false))
        assertEquals(true, publishRequested(project, true))
        assertEquals(false, publishRequested(project.copy(forge = null), true))
    }

    private fun pr(state: String) = PullRequestRef("github", 1, "https://github.com/a/b/pull/1", state)
}
