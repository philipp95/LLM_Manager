package com.llmmanager.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForgeTest {
    private fun forge(remote: String) = Forge.of(RemoteUrl.of(remote))

    @Test
    fun `github remotes`() {
        listOf("https://github.com/acme/app.git", "git@github.com:acme/app.git", "ssh://git@github.com/acme/app").forEach {
            assertEquals(Forge.GitHub("acme", "app"), forge(it), it)
        }
        assertEquals("https://github.com/acme/app/tree/run/app-1", forge("https://github.com/acme/app")!!.branchUrl("run/app-1"))
    }

    @Test
    fun `gitlab remotes, including nested groups and self-hosted`() {
        assertEquals(Forge.GitLab("gitlab.com", "group/sub/app"), forge("https://gitlab.com/group/sub/app.git"))
        assertEquals(Forge.GitLab("gitlab.com", "group/app"), forge("git@gitlab.com:group/app.git"))
        val selfHosted = forge("https://oauth2:secret@gitlab.example.org/team/app.git") as Forge.GitLab
        assertEquals("gitlab.example.org", selfHosted.host)
        assertEquals("https://gitlab.example.org/api/v4", selfHosted.apiBase)
        assertEquals("https://gitlab.example.org/team/app/-/tree/codex/x", selfHosted.branchUrl("codex/x"))
    }

    @Test
    fun `azure devops remotes in all three forms`() {
        val expected = Forge.AzureDevOps("contoso", "Fabrikam Web", "app")
        assertEquals(expected, forge("https://contoso@dev.azure.com/contoso/Fabrikam%20Web/_git/app"))
        assertEquals(expected, forge("git@ssh.dev.azure.com:v3/contoso/Fabrikam%20Web/app"))
        assertEquals(expected, forge("https://contoso.visualstudio.com/DefaultCollection/Fabrikam%20Web/_git/app"))
        assertEquals("https://dev.azure.com/contoso/Fabrikam%20Web/_git/app", expected.webUrl)
        assertEquals("https://dev.azure.com/contoso/Fabrikam%20Web/_git/app?version=GBrun%2Fx", expected.branchUrl("run/x"))
        assertEquals("contoso/Fabrikam Web/app", expected.name)
    }

    @Test
    fun `unknown, local and lookalike remotes have no forge`() {
        listOf(
            "/home/llm/repos/app", "https://bitbucket.org/acme/app.git", "https://github.com.evil.example/acme/app",
            "https://notgitlab.com/acme/app", "https://dev.azure.com/contoso/app", "https://github.com/acme/app/extra",
            "https://gitlab.com/onlyone",
        ).forEach { assertNull(forge(it), it) }
    }
}
