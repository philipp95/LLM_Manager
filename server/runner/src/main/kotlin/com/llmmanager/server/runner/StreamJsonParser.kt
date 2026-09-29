package com.llmmanager.server.runner

import com.llmmanager.protocol.ModelUsage
import com.llmmanager.protocol.RunUsage
import com.llmmanager.protocol.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One parser per stream. Decoding and redaction precede event emission. */
class StreamJsonParser(private val redactor: SecretRedactor = SecretRedactor()) {
    private val rawLines = redactor.newStream()

    /** One message can contain several content blocks plus usage. */
    fun parseLine(line: String): List<HarnessEvent> {
        val trimmed = line.trim()
        return when {
            trimmed.isEmpty() -> emptyList()
            !trimmed.startsWith("{") -> listOf(HarnessEvent.Unparsed(rawLines.redact(trimmed)))
            else -> parseJson(trimmed)
        }
    }

    private fun parseJson(trimmed: String): List<HarnessEvent> {
        val sanitized = try {
            redactor.redactJson(Json.parseToJsonElement(trimmed))
        } catch (_: IllegalArgumentException) {
            null
        }
        if (sanitized == null) return listOf(HarnessEvent.Unparsed("[Malformed JSON withheld]"))
        val unknown = listOf(HarnessEvent.Unparsed(sanitized.toString()))
        val obj = sanitized as? JsonObject
        return if (obj == null) unknown else try {
            when (obj.string("type")) {
                "assistant" -> assistant(obj)
                "result" -> listOf(result(obj))
                "system", "rate_limit_event" -> emptyList()
                else -> unknown
            }
        } catch (_: IllegalArgumentException) {
            unknown
        }
    }

    private fun assistant(obj: JsonObject): List<HarnessEvent> {
        val message = requireNotNull(obj["message"] as? JsonObject)
        return buildList {
            message["content"]?.let { content ->
                require(content is JsonArray)
                for (element in content) {
                    val block = requireNotNull(element as? JsonObject)
                    add(
                        when (block.string("type")) {
                            "text" -> HarnessEvent.Text(requireNotNull(block.string("text")))
                            "tool_use" -> HarnessEvent.ToolCall(
                                requireNotNull(block.string("name")), block["input"]?.toString(),
                            )
                            else -> HarnessEvent.Unparsed(block.toString())
                        },
                    )
                }
            }
            message["usage"]?.let { usage ->
                require(usage is JsonObject)
                add(HarnessEvent.Usage(message.string("model"), usage.tokens()))
            }
            if (isEmpty()) add(HarnessEvent.Unparsed(obj.toString()))
        }
    }

    private fun result(obj: JsonObject): HarnessEvent.Finished {
        val outcome = requireNotNull(obj["is_error"] as? JsonPrimitive)
        require(!outcome.isString)
        val isError = requireNotNull(outcome.booleanOrNull)
        val models = obj["modelUsage"]?.let { requireNotNull(it as? JsonObject) }
        val perModel = models?.map { (model, element) ->
            val usage = requireNotNull(element as? JsonObject)
            ModelUsage(
                model,
                TokenUsage(
                    inputTokens = usage.count("inputTokens"),
                    outputTokens = usage.count("outputTokens"),
                    cacheReadInputTokens = usage.count("cacheReadInputTokens"),
                    cacheCreationInputTokens = usage.count("cacheCreationInputTokens"),
                ),
                usage.cost("costUSD"),
            )
        }.orEmpty()
        return HarnessEvent.Finished(
            ok = !isError,
            summary = obj.string("result"),
            usage = RunUsage(perModel, obj.cost("total_cost_usd")),
            exitCode = if (isError) 1 else 0,
            durationMs = obj.number("duration_ms"),
            sessionId = obj.string("session_id"),
        )
    }

    private fun JsonObject.tokens() = TokenUsage(
        inputTokens = count("input_tokens"),
        outputTokens = count("output_tokens"),
        cacheReadInputTokens = count("cache_read_input_tokens"),
        cacheCreationInputTokens = count("cache_creation_input_tokens"),
    )

    private fun JsonObject.primitive(key: String): JsonPrimitive? {
        val value = this[key]
        return if (value == null || value == JsonNull) null else requireNotNull(value as? JsonPrimitive)
    }

    private fun JsonObject.string(key: String): String? = primitive(key)?.let {
        require(it.isString)
        it.content
    }

    private fun JsonObject.number(key: String): Long? = primitive(key)?.let {
        require(!it.isString)
        requireNotNull(it.longOrNull).also { value -> require(value >= 0) }
    }

    private fun JsonObject.count(key: String): Long = number(key) ?: 0

    private fun JsonObject.cost(key: String): Double? = primitive(key)?.let {
        require(!it.isString)
        requireNotNull(it.doubleOrNull).also { value -> require(value.isFinite() && value >= 0) }
    }
}
