package com.llmmanager.server.api

import com.llmmanager.server.runner.SecretRedactor
import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What a finished run reported about itself. */
data class RunOutcome(
    val sessionId: String? = null,
    val summary: String? = null,
    val error: String? = null,
    /** Stopped by a plan usage limit. */
    val limited: Boolean = false,
    val limitResetsAt: Instant? = null,
)

@Serializable
data class ActivityLine(val kind: String, val text: String)

/**
 * Reads the agents' event streams: Claude's `--output-format stream-json` and
 * Codex's `exec --json`. Formats are parsed defensively; unknown events are skipped.
 */
object RunOutput {
    private val json = Json { ignoreUnknownKeys = true }
    private val LIMIT_TEXT = Regex("""usage limit|rate limit|hit your limit|limit reached|quota|RESOURCE_EXHAUSTED""", RegexOption.IGNORE_CASE)
    private const val MAX_SUMMARY = 8_000
    internal const val MAX_LINE = 400

    fun outcome(agent: Agent, stage: Stage, events: File, stderr: File, exitCode: Int?): RunOutcome {
        val parsed = when {
            stage == Stage.REVIEW && agent == Agent.CODEX -> RunOutcome(summary = events.tail(MAX_SUMMARY.toLong()).trim().ifEmpty { null })
            agent == Agent.CLAUDE -> EventFiles.parse(events, ::claude)
            agent == Agent.GEMINI -> EventFiles.parse(events, ::gemini)
            else -> EventFiles.parse(events, ::codex)
        }
        val stderrText = stderr.tail(MAX_SUMMARY.toLong())
        val limited = parsed.limited || (exitCode != 0 && LIMIT_TEXT.containsMatchIn(stderrText + (parsed.error ?: "")))
        val error = parsed.error ?: stderrText.lines().lastOrNull { it.isNotBlank() }?.takeIf { exitCode != 0 }
        return parsed.copy(
            limited = limited,
            error = error?.let { redact(it).take(MAX_LINE * 2) },
            summary = parsed.summary?.let(::redact),
        )
    }

    private fun claude(events: Sequence<JsonObject>): RunOutcome = events.fold(RunOutcome()) { outcome, event ->
        claudeEvent(event.string("session_id")?.let { outcome.copy(sessionId = it) } ?: outcome, event)
    }

    private fun claudeEvent(outcome: RunOutcome, event: JsonObject): RunOutcome = when (event.string("type")) {
        "rate_limit_event" -> rateLimit(event)
            ?.takeIf { (rejected, _) -> rejected }
            ?.let { (_, resetsAt) -> outcome.copy(limited = true, limitResetsAt = resetsAt ?: outcome.limitResetsAt) }
            ?: outcome
        "result" -> {
            val text = event.string("result")
            val isError = (event["is_error"] as? JsonPrimitive)?.booleanOrNull == true
            outcome.copy(
                summary = text?.take(MAX_SUMMARY),
                error = if (isError) (text ?: event.string("subtype") ?: "Claude reported an error") else null,
                limited = outcome.limited || (isError && LIMIT_TEXT.containsMatchIn(text.orEmpty())),
            )
        }
        else -> outcome
    }

    /** (rejected, resetsAt) from a rate_limit_event, wherever in the event the fields sit. */
    private fun rateLimit(event: JsonObject): Pair<Boolean, Instant?>? {
        val info = findObject(event) { it.containsKey("status") && (it.containsKey("resetsAt") || it.containsKey("rateLimitType")) } ?: return null
        val resetsAt = (info["resetsAt"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochSecond)
        return (info.string("status") == "rejected") to resetsAt
    }

    /** Gemini `--output-format stream-json`: init, message (deltas), tool_use, tool_result, error, result. */
    private fun gemini(events: Sequence<JsonObject>): RunOutcome {
        var outcome = RunOutcome()
        val text = StringBuilder()
        events.forEach { event ->
            when (event.string("type")) {
                "init" -> event.string("session_id")?.let { outcome = outcome.copy(sessionId = it) }
                // The final answer is what the model said after its last tool call.
                "tool_use" -> text.clear()
                "message" -> if (event.string("role") == "assistant") text.append(event.string("content").orEmpty())
                "error" -> event.string("message")?.takeIf { event.string("severity") == "error" }?.let { message ->
                    outcome = outcome.copy(error = message, limited = outcome.limited || LIMIT_TEXT.containsMatchIn(message))
                }
                "result" -> if (event.string("status") == "error") {
                    val message = (event["error"] as? JsonObject)?.string("message") ?: "Gemini reported an error"
                    outcome = outcome.copy(error = message, limited = outcome.limited || LIMIT_TEXT.containsMatchIn(message))
                }
            }
        }
        return outcome.copy(summary = text.toString().trim().take(MAX_SUMMARY).ifEmpty { null })
    }

    private fun codex(events: Sequence<JsonObject>): RunOutcome {
        var outcome = RunOutcome()
        events.forEach { event ->
            when (event.string("type")) {
                "thread.started" -> event.string("thread_id")?.let { outcome = outcome.copy(sessionId = it) }
                "item.completed" -> (event["item"] as? JsonObject)?.takeIf { it.string("type") == "agent_message" }?.string("text")
                    ?.let { outcome = outcome.copy(summary = it.take(MAX_SUMMARY)) }
                "turn.failed", "error" -> {
                    val message = (event["error"] as? JsonObject)?.string("message") ?: event.string("message") ?: "Codex reported an error"
                    outcome = outcome.copy(error = message, limited = outcome.limited || LIMIT_TEXT.containsMatchIn(message))
                }
            }
        }
        return outcome
    }

    private fun findObject(element: JsonElement, predicate: (JsonObject) -> Boolean): JsonObject? = when (element) {
        is JsonObject -> if (predicate(element)) element else element.values.firstNotNullOfOrNull { findObject(it, predicate) }
        is JsonArray -> element.firstNotNullOfOrNull { findObject(it, predicate) }
        else -> null
    }

    private val redactor = SecretRedactor()

    fun redact(text: String): String = redactor.redact(text)

}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The last [maxBytes] of this file as text, starting at a line boundary; empty if missing. */
internal fun File.tail(maxBytes: Long): String {
    if (!isFile) return ""
    java.io.RandomAccessFile(this, "r").use { raf ->
        val start = (raf.length() - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt()).also(raf::readFully)
        val text = String(bytes, Charsets.UTF_8)
        // Starting mid-file: drop the partial first line.
        return if (start == 0L) text else text.substringAfter('\n', "")
    }
}

/** Reading the JSON-lines event files agents write: streamed, or only their recent tail. */
internal object EventFiles {
    private val json = Json { ignoreUnknownKeys = true }
    private const val RECENT_BYTES = 2L * 1024 * 1024

    /** Streams [file]'s JSON lines into [parse] without loading the file; long runs produce large logs. */
    fun <T> parse(file: File, parse: (Sequence<JsonObject>) -> T): T =
        if (!file.isFile) parse(emptySequence()) else file.useLines { lines -> parse(lines.mapNotNull(::line)) }

    /** JSON lines from at most the last [maxBytes] of [file], for showing recent activity. */
    fun recent(file: File, maxBytes: Long = RECENT_BYTES): Sequence<JsonObject> = file.tail(maxBytes).lineSequence().mapNotNull(::line)

    private fun line(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

/** A run's recent steps in readable form, for the run log on the phone. Redacted. */
object RunActivity {
    private const val ACTIVITY_TAIL_BYTES = 256L * 1024

    /** The last [max] readable steps of a run, redacted. */
    fun activity(agent: Agent, stage: Stage, events: File, max: Int = 150): List<ActivityLine> {
        if (stage == Stage.REVIEW && agent == Agent.CODEX) {
            return events.tail(ACTIVITY_TAIL_BYTES).lines().filter { it.isNotBlank() }.takeLast(max)
                .map { ActivityLine("text", RunOutput.redact(it).take(RunOutput.MAX_LINE)) }
        }
        val steps = when (agent) {
            Agent.CLAUDE -> EventFiles.recent(events).flatMap(::claudeSteps).toList()
            Agent.CODEX -> EventFiles.recent(events).flatMap(::codexSteps).toList()
            Agent.GEMINI -> geminiSteps(EventFiles.recent(events))
        }
        return steps.takeLast(max).map { it.copy(text = RunOutput.redact(it.text).take(RunOutput.MAX_LINE)) }
    }

    /** Gemini streams assistant text in deltas; consecutive deltas become one line. */
    private fun geminiSteps(events: Sequence<JsonObject>): List<ActivityLine> {
        val steps = mutableListOf<ActivityLine>()
        val text = StringBuilder()
        events.forEach { event ->
            if (event.string("type") == "message") {
                if (event.string("role") == "assistant") text.append(event.string("content").orEmpty())
                return@forEach
            }
            val step = geminiStep(event) ?: return@forEach
            if (text.isNotBlank()) steps += ActivityLine("text", text.toString().trim())
            text.clear()
            steps += step
        }
        if (text.isNotBlank()) steps += ActivityLine("text", text.toString().trim())
        return steps
    }

    private fun geminiStep(event: JsonObject): ActivityLine? {
        val error = (event["error"] as? JsonObject)?.string("message")
        return when (event.string("type")) {
            "tool_use" -> ActivityLine("tool", "${event.string("tool_name")}: ${toolSummary(event["parameters"])}")
            "tool_result" -> if (event.string("status") == "error") ActivityLine("error", error ?: "tool failed") else null
            "error" -> ActivityLine(if (event.string("severity") == "error") "error" else "tool", event.string("message").orEmpty())
            "result" -> if (event.string("status") == "error") ActivityLine("error", error ?: "Failed") else ActivityLine("done", "Finished")
            else -> null
        }
    }

    private fun claudeSteps(event: JsonObject): Sequence<ActivityLine> = when (event.string("type")) {
        "assistant" -> ((event["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().asSequence()
            .filterIsInstance<JsonObject>()
            .mapNotNull { block ->
                when (block.string("type")) {
                    "text" -> block.string("text")?.takeIf { it.isNotBlank() }?.let { ActivityLine("text", it) }
                    "tool_use" -> ActivityLine("tool", "${block.string("name")}: ${toolSummary(block["input"])}")
                    else -> null
                }
            }
        "result" -> sequenceOf(
            if ((event["is_error"] as? JsonPrimitive)?.booleanOrNull == true) ActivityLine("error", event.string("result") ?: "Failed")
            else ActivityLine("done", "Finished"),
        )
        else -> emptySequence()
    }

    private fun codexSteps(event: JsonObject): Sequence<ActivityLine> {
        val line = when (event.string("type")) {
            "item.completed" -> (event["item"] as? JsonObject)?.let(::codexItem)
            "turn.failed", "error" -> ActivityLine("error", (event["error"] as? JsonObject)?.string("message") ?: event.string("message") ?: "error")
            "turn.completed" -> ActivityLine("done", "Finished")
            else -> null
        }
        return listOfNotNull(line).asSequence()
    }

    private fun codexItem(item: JsonObject): ActivityLine? = when (item.string("type")) {
        "agent_message" -> item.string("text")?.let { ActivityLine("text", it) }
        "command_execution" -> ActivityLine("tool", "$ ${item.string("command")} (exit ${item.string("exit_code") ?: "?"})")
        "file_change" -> ActivityLine("tool", "edited ${(item["changes"] as? JsonArray)?.size ?: ""} file(s)")
        else -> null
    }

    private fun toolSummary(input: JsonElement?): String {
        val obj = input as? JsonObject ?: return ""
        return listOf("command", "file_path", "absolute_path", "pattern", "path", "dir_path", "url", "description")
            .firstNotNullOfOrNull { obj.string(it) }.orEmpty()
    }

}
