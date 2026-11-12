package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class AgentSignInTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val home: File get() = File(root, "home")

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** Terminals whose screens the test writes. */
    private class FakeTerminals : Terminals {
        val commands = mutableMapOf<String, List<String>>()
        val screens = mutableMapOf<String, String>()
        val typed = mutableListOf<String>()

        override fun open(name: String, directory: File, command: List<String>): Boolean {
            commands[name] = command
            screens[name] = ""
            return true
        }

        override fun screen(name: String): String? = screens[name]

        override fun type(name: String, text: String): Boolean = typed.add(text)

        override fun close(name: String) {
            screens.remove(name)
        }
    }

    // In the past, so files the test writes are newer than the sign-in, as they would be.
    private val clock = MutableClock(Instant.parse("2020-01-01T10:00:00Z"))
    private val terminals = FakeTerminals()
    private var claudeSignedIn = false

    private val runner = CommandRunner { command, _ ->
        when {
            command.contains("status") && command.first().endsWith("claude") -> {
                val status = """{"loggedIn":$claudeSignedIn,"authMethod":"claude.ai","subscriptionType":"pro","email":"me@example.com"}"""
                Processes.Exec(if (claudeSignedIn) 0 else 1, status, "")
            }
            else -> Processes.Exec(1, "", "Not logged in")
        }
    }

    private fun signIn(installed: Boolean = true): AgentSignIn {
        val binary = { agent: Agent -> if (installed) "/opt/${agent.name.lowercase()}" else null }
        val accounts = AgentAccounts(binary, home, runner, environment = { null }, ttlMillis = 0)
        return AgentSignIn(terminals, accounts, binary, File(root, "state/sign-in"), clock)
    }

    private val claudeName = AgentSignIn.name(Agent.CLAUDE)

    @Test
    fun `claude - the link goes to the phone, the pasted code goes to the CLI, and the sign-in is confirmed`() {
        val flow = signIn()

        assertEquals(AgentSignIn.STARTING, flow.start(Agent.CLAUDE).state)
        val command = terminals.commands.getValue(claudeName)
        assertEquals(listOf("/opt/claude", "auth", "login", "--claudeai"), command.takeLast(4))
        assertTrue(command.containsAll(listOf("BROWSER=true", "-u", "DISPLAY")), "no browser is opened on the server: $command")

        val link = "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz"
        terminals.screens[claudeName] = "Opening browser to sign in…\nIf the browser didn't open, visit: $link\n\nPaste code here if prompted > \n\n"
        val waiting = flow.view(Agent.CLAUDE)
        assertEquals(AgentSignIn.WAITING, waiting.state)
        assertEquals(link, waiting.url)
        assertTrue(waiting.needsCode)

        assertTrue(flow.submit(Agent.CLAUDE, "abc def").message!!.contains("does not look like"), "spaces are refused")
        assertTrue(flow.submit(Agent.CLAUDE, "abc\u001b[2J").message!!.contains("does not look like"), "control characters are refused")
        assertTrue(terminals.typed.isEmpty())
        assertEquals(AgentSignIn.CHECKING, flow.submit(Agent.CLAUDE, "  AbC123#state_456  ").state)
        assertEquals(listOf("AbC123#state_456"), terminals.typed)

        terminals.screens[claudeName] = "visit: $link\nPaste code here if prompted > AbC123#state_456\n"
        val checking = flow.view(Agent.CLAUDE)
        assertEquals(AgentSignIn.CHECKING, checking.state)
        assertTrue(checking.screen.none { it.contains("AbC123") }, "the code is not echoed back: ${checking.screen}")

        claudeSignedIn = true
        terminals.screens[claudeName] = "Login successful.\n\n[llm-manager sign-in ended] 0\n"
        val done = flow.view(Agent.CLAUDE)
        assertEquals(AgentSignIn.DONE, done.state)
        assertEquals("Signed in as me@example.com", done.message)
        assertNull(terminals.screens[claudeName], "the terminal is closed")
        assertNull(flow.active(Agent.CLAUDE), "a finished sign-in is not offered to resume")
    }

    @Test
    fun `only links to the agent's own sign-in hosts are offered`() {
        val flow = signIn()
        flow.start(Agent.CLAUDE)
        terminals.screens[claudeName] = "visit: https://claude.com.evil.example/oauth\nPaste code here if prompted > "

        val view = flow.view(Agent.CLAUDE)

        assertNull(view.url)
        assertEquals(AgentSignIn.STARTING, view.state)
    }

    @Test
    fun `codex - the device code is shown, and a failed sign-in reports the CLI's last lines`() {
        val flow = signIn()
        val name = AgentSignIn.name(Agent.CODEX)
        flow.start(Agent.CODEX)
        assertEquals(listOf("/opt/codex", "login", "--device-auth"), terminals.commands.getValue(name).takeLast(3))
        terminals.screens[name] = """
            Follow these steps to sign in with ChatGPT using device code authorization:
            1. Open this link in your browser and sign in to your account
               https://auth.openai.com/codex/device
            2. Enter this one-time code (expires in 15 minutes)
               ER84-7HRSJ
        """.trimIndent()

        val waiting = flow.view(Agent.CODEX)
        assertEquals("https://auth.openai.com/codex/device" to "ER84-7HRSJ", waiting.url to waiting.userCode)
        assertEquals(false, waiting.needsCode)
        assertEquals("The sign-in is not waiting for a code", flow.submit(Agent.CODEX, "ABCD-1234").message)

        terminals.screens[name] = terminals.screens.getValue(name) + "\nError: device code expired\n\n[llm-manager sign-in ended] 1\n"
        val failed = flow.view(Agent.CODEX)
        assertEquals(AgentSignIn.FAILED, failed.state)
        assertEquals("Error: device code expired", failed.screen.last())
        assertNull(terminals.screens[name])
        assertEquals(failed, flow.view(Agent.CODEX), "the outcome stays readable after the terminal is gone")
    }

    @Test
    fun `gemini - Google sign-in is chosen in its settings, and the cached sign-in ends the flow`() {
        val gemini = File(home, ".gemini").apply { mkdirs() }
        File(gemini, "settings.json").writeText("""{"ui":{"theme":"Dracula"},"security":{"folderTrust":{"enabled":true}}}""")
        val flow = signIn()
        val name = AgentSignIn.name(Agent.GEMINI)

        flow.start(Agent.GEMINI)

        val settings = Json.parseToJsonElement(File(gemini, "settings.json").readText()).jsonObject
        assertEquals("oauth-personal", settings["security"]!!.jsonObject["auth"]!!.jsonObject["selectedType"]!!.jsonPrimitive.content)
        assertEquals("Dracula", settings["ui"]!!.jsonObject["theme"]!!.jsonPrimitive.content, "other settings are kept")
        assertTrue((settings["security"] as JsonObject).containsKey("folderTrust"))
        assertTrue(terminals.commands.getValue(name).contains("NO_BROWSER=true"))

        terminals.screens[name] = "Please visit the following URL to authorize the application:\n\n" +
            "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fcodeassist.google.com%2Fauthcode&state=s\n\nEnter the authorization code: "
        assertTrue(flow.view(Agent.GEMINI).needsCode)
        flow.submit(Agent.GEMINI, "4/0AVG7fiQ-abc_DEF")

        // A half-written token file does not count; a complete one does.
        File(gemini, "oauth_creds.json").writeText("""{"access_token":"ya29""")
        assertTrue(flow.view(Agent.GEMINI).state != AgentSignIn.DONE)
        File(gemini, "oauth_creds.json").writeText("""{"access_token":"ya29.x","refresh_token":"1//r","expiry_date":1}""")
        File(gemini, "google_accounts.json").writeText("""{"active":"me@example.com","old":[]}""")
        assertEquals("Signed in as me@example.com", flow.view(Agent.GEMINI).message)
    }

    @Test
    fun `a sign-in nobody finishes times out and its terminal is closed`() {
        val flow = signIn()
        val name = AgentSignIn.name(Agent.CODEX)
        flow.start(Agent.CODEX)
        terminals.screens[name] = "https://auth.openai.com/codex/device\n   ABCD-12345\n"

        clock.now = clock.now.plus(Duration.ofMinutes(16))

        val view = flow.view(Agent.CODEX)
        assertEquals(AgentSignIn.FAILED, view.state)
        assertTrue(view.message!!.contains("timed out"))
        assertNull(terminals.screens[name])
    }

    @Test
    fun `starting twice resumes the same sign-in, cancelling closes it, and a missing CLI cannot sign in`() {
        val flow = signIn()
        flow.start(Agent.CLAUDE)
        val first = terminals.commands.getValue(claudeName)
        terminals.commands.clear()

        flow.start(Agent.CLAUDE)
        assertTrue(terminals.commands.isEmpty(), "the running sign-in is shown again, not restarted")
        flow.start(Agent.CLAUDE, restart = true)
        assertEquals(first, terminals.commands.getValue(claudeName))

        assertEquals(AgentSignIn.IDLE, flow.cancel(Agent.CLAUDE).state)
        assertNull(terminals.screens[claudeName])
        assertEquals(AgentSignIn.IDLE, flow.view(Agent.CLAUDE).state)

        assertEquals("Claude CLI is not installed on the server", signIn(installed = false).start(Agent.CLAUDE).message)
    }
}
