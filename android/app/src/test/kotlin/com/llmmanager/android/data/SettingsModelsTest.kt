package com.llmmanager.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the settings page as the server sends it`() {
        val body = """
            {"profile":{"name":"","email":""},"defaultAgent":null,
             "agents":[{"agent":"claude","installed":true,"enabled":true,"model":"","signedIn":true,"method":"Claude Pro","account":"me@example.com",
                        "apiKey":false,"signIn":null},
                       {"agent":"codex","installed":true,"enabled":true,"model":"","signedIn":false,"method":null,"account":null,"apiKey":true,
                        "signIn":{"agent":"codex","state":"waiting","url":"https://auth.openai.com/codex/device","userCode":"ABCD-12345",
                                  "needsCode":false,"message":null,"screen":["Welcome to Codex"],"expiresAt":"2026-11-12T22:00:25Z"}}],
             "forges":[{"forge":"github","host":"github.com","connected":true,"account":"philipp95","note":"Token scopes: repo"},
                       {"forge":"gitlab","host":"gitlab.com","connected":false,"account":null,"note":null}],
             "message":null,"later":1}
        """.trimIndent()

        val settings = json.decodeFromString(SettingsResponse.serializer(), body)

        assertEquals("Claude Pro", settings.agents[0].method)
        val signIn = settings.agents[1].signIn!!
        assertEquals("ABCD-12345", signIn.userCode)
        assertTrue(signIn.live)
        assertEquals("philipp95", settings.forges.first().account)
        assertNull(settings.defaultAgent)
    }

    @Test
    fun `only https sign-in links are opened, and finished sign-ins stop polling`() {
        assertNull(SignInView("claude", "waiting", url = "http://claude.com/oauth").safeUrl)
        assertNull(SignInView("claude", "waiting", url = "javascript:alert(1)").safeUrl)
        assertEquals("https://claude.com/oauth", SignInView("claude", "waiting", url = "https://claude.com/oauth").safeUrl)
        assertFalse(SignInView("claude", "done").live)
        assertFalse(SignInView("claude", "failed").live)
        assertTrue(SignInView("claude", "starting").live)
    }

    @Test
    fun `the chat asks for a sign-in only when the server says so`() {
        val schedule = json.decodeFromString(
            ScheduleResponse.serializer(),
            """{"now":"2026-11-12T10:00:00Z","agents":{"claude":true,"gemini":true},"signedIn":{"claude":true,"gemini":false},"defaultAgent":"gemini"}""",
        )

        assertTrue(schedule.needsSignIn("gemini"))
        assertFalse(schedule.needsSignIn("claude"))
        assertFalse("unknown counts as signed in", schedule.needsSignIn("codex"))
        assertEquals("gemini", schedule.defaultAgent)
        val older = json.decodeFromString(ScheduleResponse.serializer(), """{"now":"2026-11-12T10:00:00Z"}""")
        assertFalse("an older server never triggers the pop-up", older.needsSignIn("gemini"))
    }
}
