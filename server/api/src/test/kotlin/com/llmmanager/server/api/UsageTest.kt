package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UsageTest {
    @TempDir
    lateinit var directory: Path

    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val home: File get() = directory.resolve("home").toFile()
    private val state: File get() = directory.resolve("state").toFile()

    /** [io] is input to output tokens. */
    private fun claudeLine(id: String, at: String, io: Pair<Int, Int>, cacheRead: Int = 0, model: String = "claude-opus-5-5") =
        """{"type":"assistant","timestamp":"$at","message":{"id":"$id","model":"$model","usage":""" +
            """{"input_tokens":${io.first},"output_tokens":${io.second},"cache_creation_input_tokens":0,"cache_read_input_tokens":$cacheRead}}}"""

    @Test
    fun `claude tokens count each message once and read appended lines incrementally`() {
        val log = File(home, ".claude/projects/-repo/s1.jsonl").apply { parentFile.mkdirs() }
        // Claude Code logs one line per content block, repeating the message's usage.
        log.writeText(
            listOf(
                claudeLine("m1", "2026-11-09T10:00:00Z", 10 to 100, cacheRead = 5000),
                claudeLine("m1", "2026-11-09T10:00:00Z", 10 to 100, cacheRead = 5000),
                """{"type":"user","timestamp":"2026-11-09T10:00:01Z"}""",
                claudeLine("m2", "2026-11-08T10:00:00Z", 1 to 9, model = "claude-sonnet-5-5"),
                claudeLine("old", "2026-09-01T10:00:00Z", 1000 to 1000),
            ).joinToString("\n", postfix = "\n"),
        )
        val reader = UsageReader(home, state, clock)

        val first = reader.read().claude
        assertEquals(110, first.tokensPerDay.last())
        assertEquals(5000, first.cacheReadsPerDay.last())
        assertEquals(10, first.tokensPerDay[first.tokensPerDay.size - 2])
        assertEquals(listOf("claude-opus-5-5" to 110L, "claude-sonnet-5-5" to 10L), first.models.map { it.model to it.tokens })
        assertNotNull(first.note) // no plan limits recorded yet

        log.appendText(claudeLine("m3", "2026-11-09T11:00:00Z", 0 to 40) + "\n" + """{"partial":""")
        assertEquals(150, reader.read().claude.tokensPerDay.last())
    }

    @Test
    fun `claude plan windows come from the status-line recorder and expired windows are dropped`() {
        state.mkdirs()
        File(state, UsageReader.CLAUDE_LIMITS_FILE).writeText(
            """{"capturedAt":"2026-11-09T11:58:00Z","rate_limits":{
                "five_hour":{"used_percentage":34.6,"resets_at":${Instant.parse("2026-11-09T14:00:00Z").epochSecond}},
                "seven_day":{"used_percentage":80,"resets_at":${Instant.parse("2026-11-08T00:00:00Z").epochSecond}}}}""",
        )

        val claude = UsageReader(home, state, clock).read().claude

        assertEquals(listOf(LimitWindow("5-hour", 34.6, "2026-11-09T14:00:00Z")), claude.windows)
        assertEquals("2026-11-09T11:58:00Z", claude.windowsAsOf)
        assertNull(claude.note)
    }

    @Test
    fun `a newer scheduled run's rate_limit_event wins over an older status-line record`() {
        state.mkdirs()
        File(state, UsageReader.CLAUDE_LIMITS_FILE).writeText(
            """{"capturedAt":"2026-11-09T08:00:00Z","rate_limits":{"five_hour":""" +
                """{"used_percentage":10,"resets_at":${Instant.parse("2026-11-09T13:00:00Z").epochSecond}}}}""",
        )
        val events = File(state, "runs/r1/events.jsonl").apply { parentFile.mkdirs() }
        events.writeText(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","unifiedWindows":{""" +
                """"five_hour":{"utilization":0.64,"resetsAt":${Instant.parse("2026-11-09T14:00:00Z").epochSecond}},""" +
                """"seven_day":{"utilization":0.29,"resetsAt":${Instant.parse("2026-11-12T09:00:00Z").epochSecond}}}}}""" + "\n",
        )
        events.setLastModified(Instant.parse("2026-11-09T11:30:00Z").toEpochMilli())

        val claude = UsageReader(home, state, clock).read().claude

        assertEquals(listOf("5-hour" to 64.0, "Weekly" to 29.0), claude.windows.map { it.label to Math.round(it.usedPercent * 10) / 10.0 })
        assertEquals("2026-11-09T11:30:00Z", claude.windowsAsOf)
    }

    @Test
    fun `gemini tokens come from the results of runs this server started`() {
        val events = File(state, "runs/g1/events.jsonl").apply { parentFile.mkdirs() }
        events.writeText(
            """{"type":"init","session_id":"g"}""" + "\n" +
                """{"type":"result","timestamp":"2026-11-09T10:00:00Z","status":"success","stats":{"total_tokens":1300,"input_tokens":1000,""" +
                """"output_tokens":300,"cached":600,"models":{"gemini-3-pro":{"input_tokens":1000,"output_tokens":300,"cached":600}}}}""" + "\n",
        )
        // A Claude result in the same folder has no stats and is ignored.
        File(state, "runs/c1/events.jsonl").apply { parentFile.mkdirs() }
            .writeText("""{"type":"result","timestamp":"2026-11-09T10:00:00Z","usage":{"input_tokens":5}}""" + "\n")

        val gemini = UsageReader(home, state, clock).read().gemini

        assertEquals(700, gemini.tokensPerDay.last())
        assertEquals(600, gemini.cacheReadsPerDay.last())
        assertEquals(listOf(ModelTokens("gemini-3-pro", 700)), gemini.models)
        assertTrue(gemini.windows.isEmpty() && gemini.note!!.contains("does not report plan limits"))
    }

    @Test
    fun `codex tokens and limits come from rollout token_count events`() {
        val rollout = File(home, ".codex/sessions/2026/11/09/rollout-1.jsonl").apply { parentFile.mkdirs() }
        rollout.writeText(
            listOf(
                """{"timestamp":"2026-11-09T09:00:00Z","type":"session_meta","payload":{"id":"t1","cwd":"/w"}}""",
                """{"timestamp":"2026-11-09T09:01:00Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":""" +
                    """{"input_tokens":1000,"cached_input_tokens":800,"output_tokens":50}},"rate_limits":{""" +
                    """"primary":{"used_percent":12.5,"window_minutes":300,"resets_at":${Instant.parse("2026-11-09T13:00:00Z").epochSecond}},""" +
                    """"secondary":{"used_percent":40.0,"window_minutes":10080,"resets_in_seconds":3600}}}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val reader = UsageReader(home, state, clock)

        val codex = reader.read().codex

        assertEquals(250, codex.tokensPerDay.last())
        assertEquals(800, codex.cacheReadsPerDay.last())
        // The weekly window reset at 10:01 (an hour after the event), before now: it is dropped.
        assertEquals(listOf(LimitWindow("5-hour", 12.5, "2026-11-09T13:00:00Z")), codex.windows)
        assertEquals("2026-11-09T09:01:00Z", codex.windowsAsOf)
        assertEquals(Instant.parse("2026-11-09T13:00:00Z"), reader.resetHint(Agent.CODEX))
    }
}
