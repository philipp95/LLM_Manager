package com.llmmanager.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are trimmed copies of what the real server returned. */
class ScheduleModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource(name)).readText()

    @Test
    fun `decodes a real usage response`() {
        val usage = json.decodeFromString(UsageResponse.serializer(), fixture("usage.json"))
        assertEquals(3, usage.days.size)
        assertEquals(listOf("5-hour", "Weekly"), usage.claude.windows.map { it.label })
        assertTrue(usage.claude.windows.all { it.usedPercent in 0.0..100.0 && it.resetsAt != null })
        assertEquals(3, usage.claude.tokensPerDay.size)
        assertTrue(usage.codex.windows.isEmpty())
        assertTrue(usage.codex.note!!.contains("codex login"))
    }

    @Test
    fun `decodes a real schedule response`() {
        val schedule = json.decodeFromString(ScheduleResponse.serializer(), fixture("schedule.json"))
        assertEquals(mapOf("claude" to true, "codex" to true), schedule.agents)
        val run = schedule.runs.single()
        assertEquals("succeeded", run.status)
        assertEquals("work", run.stage)
        assertEquals("OK", run.summary)
        assertTrue(schedule.resumable.single().session.agent == "claude")
    }

    @Test
    fun `job requests leave defaults to the server`() {
        val now = json.encodeToString(JobRequest.serializer(), JobRequest("p", "pipeline", "claude", "Add tests"))
        val fields = json.parseToJsonElement(now).jsonObject.keys
        // No schedule means "now"; the limit-resume default stays the server's.
        assertEquals(setOf("projectId", "kind", "agent", "prompt"), fields)
        val daily = json.encodeToString(
            JobRequest.serializer(),
            JobRequest("p", "run", "codex", "x", Schedule(time = "09:30", days = listOf(1, 2), zone = "Europe/Brussels"), resumeOnLimitReset = false),
        )
        assertTrue(daily.contains("\"time\":\"09:30\"") && daily.contains("\"resumeOnLimitReset\":false"))
        assertFalse(daily.contains("\"at\""))
    }
}
