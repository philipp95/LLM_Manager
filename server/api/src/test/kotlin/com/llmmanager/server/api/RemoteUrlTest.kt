package com.llmmanager.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteUrlTest {
    @Test
    fun `github remotes in every common form map to the repository page`() {
        listOf(
            "https://github.com/acme/widgets.git",
            "https://github.com/acme/widgets",
            "https://github.com/acme/widgets/",
            "git@github.com:acme/widgets.git",
            "ssh://git@github.com/acme/widgets.git",
        ).forEach { raw ->
            assertEquals("https://github.com/acme/widgets", RemoteUrl.of(raw).gitHubWebUrl, raw)
        }
    }

    @Test
    fun `credentials are removed before display`() {
        assertEquals("https://github.com/acme/w.git", RemoteUrl.of("https://x-access-token:ghs_abc@github.com/acme/w.git").display)
        assertEquals("https://github.com/acme/w.git", RemoteUrl.of("https://ghp_abc@github.com/acme/w.git").display)
        assertEquals("ssh://git@host.example/acme/w.git", RemoteUrl.of("ssh://git@host.example/acme/w.git").display)
        assertEquals("host.example:acme/w.git", RemoteUrl.of("user:hunter2@host.example:acme/w.git").display)
        assertEquals("git@host.example:acme/w.git", RemoteUrl.of("git@host.example:acme/w.git").display)
    }

    @Test
    fun `non-github and unusual remotes get no link`() {
        assertNull(RemoteUrl.of("https://dev.azure.com/org/project/_git/repo").gitHubWebUrl)
        assertNull(RemoteUrl.of("/home/llm/repos/local").gitHubWebUrl)
        assertNull(RemoteUrl.of("https://github.com/acme").gitHubWebUrl)
        assertNull(RemoteUrl.of("https://github.com/acme/w/extra").gitHubWebUrl)
        assertNull(RemoteUrl.of("https://github.com.evil.example/acme/w").gitHubWebUrl)
    }
}
