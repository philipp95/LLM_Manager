package com.llmmanager.server.runner

import com.llmmanager.protocol.ModelUsage
import com.llmmanager.protocol.RunUsage
import com.llmmanager.protocol.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parser for Claude Code's `--output-format stream-json` (JSONL).
 *
 * Written against output captured from claude-code 2.1.220 rather than from
 * documentation. Every field is read defensively: the schema is not versioned
 * and unknown shapes must degrade to [HarnessEvent.Unparsed], never throw.
 * A parser that crashes on an unrecognised line takes the whole run with it.
 */
class StreamJsonParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseLine(line: String): HarnessEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) return HarnessEvent.Unparsed(trimmed)

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return HarnessEvent.Unparsed(trimmed)

        // NOTE: `type` is not necessarily the first key — the result line carries
        // it near the end. Field order must never be assumed.
        return when (obj.str("type")) {
            "assistant" -> parseAssistant(obj)
            "result" -> parseResult(obj)
            // Startup banner and rate-limit notices carry no run content.
            "system", "rate_limit_event" -> null
            else -> HarnessEvent.Unparsed(trimmed)
        }
    }

    private fun parseAssistant(obj: JsonObject): HarnessEvent? {
        val message = obj["message"]?.jsonObjectOrNull() ?: return HarnessEvent.Unparsed(obj.toString())
        val usage = message["usage"]?.jsonObjectOrNull()?.toTokenUsage()
        return if (usage != null) HarnessEvent.Usage(message.str("model"), usage) else null
    }

    private fun parseResult(obj: JsonObject): HarnessEvent {
        val perModel = obj["modelUsage"]?.jsonObjectOrNull()?.entries.orEmpty().mapNotNull { (model, el) ->
            val m = el.jsonObjectOrNull() ?: return@mapNotNull null
            ModelUsage(
                model = model,
                usage = TokenUsage(
                    inputTokens = m.num("inputTokens"),
                    outputTokens = m.num("outputTokens"),
                    cacheReadInputTokens = m.num("cacheReadInputTokens"),
                    cacheCreationInputTokens = m.num("cacheCreationInputTokens"),
                ),
                costUsd = m["costUSD"]?.jsonPrimitive?.doubleOrNull,
            )
        }

        // `is_error` is authoritative; `subtype` is a human label and may change.
        val isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false

        return HarnessEvent.Finished(
            ok = !isError,
            summary = obj.str("result"),
            usage = RunUsage(
                perModel = perModel,
                totalCostUsd = obj["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
            ),
            exitCode = if (isError) 1 else 0,
            durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull,
            sessionId = obj.str("session_id"),
        )
    }

    private fun JsonObject.toTokenUsage() = TokenUsage(
        inputTokens = num("input_tokens"),
        outputTokens = num("output_tokens"),
        cacheReadInputTokens = num("cache_read_input_tokens"),
        cacheCreationInputTokens = num("cache_creation_input_tokens"),
    )

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.num(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject
}
