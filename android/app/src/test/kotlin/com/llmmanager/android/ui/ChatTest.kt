package com.llmmanager.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTest {
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
