package com.llmmanager.server.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures captured verbatim from claude-code 2.1.220, not written from docs.
 */
class StreamJsonParserTest {

    private val parser = StreamJsonParser()

    @Test
    fun `result line yields Finished with per-model cost`() {
        val line = """
            {"is_error":false,"duration_api_ms":3100,"num_turns":1,"stop_reason":"end_turn",
             "session_id":"5ea01310","total_cost_usd":0.076126,
             "modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":522,"outputTokens":13,
              "cacheReadInputTokens":0,"cacheCreationInputTokens":0,"costUSD":0.000587},
              "claude-opus-5[1m]":{"inputTokens":2,"outputTokens":4,"cacheReadInputTokens":17578,
              "cacheCreationInputTokens":6664,"costUSD":0.075539}},
             "subtype":"success","result":"PING","type":"result","duration_ms":4094}
        """.trimIndent().replace("\n", "")

        val event = parser.parseLine(line)
        assertIs<HarnessEvent.Finished>(event)
        assertTrue(event.ok)
        assertEquals("PING", event.summary)
        assertEquals("5ea01310", event.sessionId)
        assertEquals(4094L, event.durationMs)
        assertEquals(0.076126, event.usage.totalCostUsd)
        assertEquals(2, event.usage.perModel.size)

        val opus = event.usage.perModel.single { it.model.startsWith("claude-opus-5") }
        assertEquals(17578L, opus.usage.cacheReadInputTokens)
        assertEquals(0.075539, opus.costUsd)

        // Tokens roll up across models; cost is the number that is comparable.
        assertEquals(524L, event.usage.totalTokens.inputTokens)
    }

    @Test
    fun `is_error true produces a failed Finished`() {
        val line = """{"is_error":true,"subtype":"error","type":"result","result":"boom"}"""
        val event = parser.parseLine(line)
        assertIs<HarnessEvent.Finished>(event)
        assertTrue(!event.ok)
        assertEquals(1, event.exitCode)
    }

    @Test
    fun `assistant line yields incremental usage`() {
        val line = """
            {"type":"assistant","message":{"model":"claude-opus-5","role":"assistant",
             "content":[{"type":"text","text":"PING"}],
             "usage":{"input_tokens":2,"cache_creation_input_tokens":6664,
             "cache_read_input_tokens":17578,"output_tokens":1}}}
        """.trimIndent().replace("\n", "")

        val event = parser.parseLine(line)
        assertIs<HarnessEvent.Usage>(event)
        assertEquals("claude-opus-5", event.model)
        assertEquals(6664L, event.usage.cacheCreationInputTokens)
    }

    @Test
    fun `system and rate_limit lines carry no run content`() {
        assertNull(parser.parseLine("""{"type":"system","subtype":"init","cwd":"/tmp"}"""))
        assertNull(parser.parseLine("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""))
    }

    @Test
    fun `unknown and malformed lines degrade to Unparsed rather than throwing`() {
        assertIs<HarnessEvent.Unparsed>(parser.parseLine("""{"type":"brand_new_kind","x":1}"""))
        assertIs<HarnessEvent.Unparsed>(parser.parseLine("""{"broken":"""))
        assertIs<HarnessEvent.Unparsed>(parser.parseLine("not json at all"))
        assertNull(parser.parseLine("   "))
    }

    @Test
    fun `field order is not assumed`() {
        // `type` sits at the end here, as it does on the real result line.
        val line = """{"result":"ok","is_error":false,"type":"result"}"""
        assertIs<HarnessEvent.Finished>(parser.parseLine(line))
    }
}
