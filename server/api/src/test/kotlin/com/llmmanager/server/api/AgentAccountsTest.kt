package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class AgentAccountsTest {
    @TempDir
    lateinit var directory: Path

    private val home: File get() = directory.toFile()
    private val gemini: File get() = File(home, ".gemini").apply { mkdirs() }

    private class Recorder : CommandRunner {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        override fun run(command: List<String>, input: String?): Processes.Exec {
            calls += command to input
            return Processes.Exec(0, "Successfully logged in", "")
        }
    }

    private fun accounts(runner: CommandRunner = Recorder(), environment: Map<String, String> = emptyMap()) =
        AgentAccounts({ "/opt/${it.name.lowercase()}" }, home, runner, environment::get, ttlMillis = 0)

    @Test
    fun `claude and codex status, without any part of a key`() {
        val pro = AgentAccounts.claude("""{"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"pro","email":"me@example.com","orgName":"x"}""")
        assertEquals(AgentStatus(Agent.CLAUDE, installed = true, signedIn = true, method = "Claude Pro", account = "me@example.com"), pro)
        val out = AgentAccounts.claude("""{"loggedIn":false,"authMethod":"none","email":"stale@example.com"}""")
        assertEquals(false to null, out.signedIn to out.account)
        assertFalse(AgentAccounts.claude("not json").signedIn)

        assertEquals("ChatGPT", AgentAccounts.codex(Processes.Exec(0, "", "Logged in using ChatGPT")).method)
        val key = AgentAccounts.codex(Processes.Exec(0, "Logged in using an API key - sk-proj-***ABCD", ""))
        assertEquals("API key", key.method)
        assertFalse(key.toString().contains("ABCD"), "no fragment of the key is reported")
        assertFalse(AgentAccounts.codex(Processes.Exec(1, "", "Not logged in")).signedIn)
    }

    @Test
    fun `gemini follows its own rules - the chosen auth type decides`() {
        assertFalse(accounts().status(Agent.GEMINI).signedIn, "no settings and no key: Gemini asks for an auth method")
        assertEquals("API key", accounts(environment = mapOf("GEMINI_API_KEY" to "k")).status(Agent.GEMINI).method)

        File(gemini, "settings.json").writeText("""{"security":{"auth":{"selectedType":"oauth-personal"}}}""")
        assertFalse(accounts().status(Agent.GEMINI).signedIn, "Google chosen but not signed in")
        File(gemini, "oauth_creds.json").writeText("""{"access_token":"a","refresh_token":"r"}""")
        File(gemini, "google_accounts.json").writeText("""{"active":"me@example.com","old":[]}""")
        assertEquals(AgentStatus(Agent.GEMINI, true, true, "Google account", "me@example.com"), accounts().status(Agent.GEMINI))
        assertFalse(AgentAccounts({ null }, home, Recorder(), { null }, ttlMillis = 0).status(Agent.GEMINI).installed)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "checks POSIX permissions")
    fun `an API key goes to gemini's env file, owner-only, and to codex on stdin`() {
        File(gemini, ".env").writeText("OTHER=1\nGEMINI_API_KEY=old\n")
        val accounts = accounts()

        assertNull(accounts.useApiKey(Agent.GEMINI, "AIzaSyA-1234567890abcdefghijklmnopqrstu"))

        val env = File(gemini, ".env")
        assertEquals("OTHER=1\nGEMINI_API_KEY=AIzaSyA-1234567890abcdefghijklmnopqrstu\n", env.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(env.toPath())))
        assertEquals("API key", accounts.status(Agent.GEMINI).method)

        val runner = Recorder()
        assertNull(accounts(runner).useApiKey(Agent.CODEX, "sk-proj-abcdefghijklmnopqrstuvwxyz"))
        val (command, input) = runner.calls.single()
        assertEquals(listOf("/opt/codex", "login", "--with-api-key"), command)
        assertEquals("sk-proj-abcdefghijklmnopqrstuvwxyz", input, "the key arrives on stdin, never on the command line")

        assertTrue(accounts.useApiKey(Agent.CODEX, "short")!!.contains("does not look like"))
        assertTrue(accounts.useApiKey(Agent.CODEX, "sk-proj-abc\n--config=x")!!.contains("does not look like"))
        assertTrue(accounts.useApiKey(Agent.CLAUDE, "sk-ant-abcdefghijklmnopqrstuvwxyz")!!.contains("account"))
    }

    @Test
    fun `signing gemini out forgets the google sign-in and the key, and keeps other env lines`() {
        File(gemini, "oauth_creds.json").writeText("""{"refresh_token":"r"}""")
        File(gemini, "google_accounts.json").writeText("""{"active":"me@example.com","old":["older@example.com"]}""")
        File(gemini, ".env").writeText("OTHER=1\nexport GEMINI_API_KEY=k\n")

        assertNull(accounts().signOut(Agent.GEMINI))

        assertFalse(File(gemini, "oauth_creds.json").exists())
        assertEquals("OTHER=1\n", File(gemini, ".env").readText())
        val accountsFile = File(gemini, "google_accounts.json").readText()
        assertTrue(accountsFile.contains("\"active\": null") && accountsFile.contains("me@example.com"), accountsFile)

        val runner = Recorder()
        accounts(runner).signOut(Agent.CLAUDE)
        accounts(runner).signOut(Agent.CODEX)
        assertEquals(listOf(listOf("/opt/claude", "auth", "logout"), listOf("/opt/codex", "logout")), runner.calls.map { it.first })
    }
}
