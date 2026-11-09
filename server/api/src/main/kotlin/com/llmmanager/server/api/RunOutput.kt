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
    private val LIMIT_TEXT = Regex("""usage limit|rate limit|hit your limit|limit reached|quota""", RegexOption.IGNORE_CASE)
    private const val MAX_SUMMARY = 8_000
    internal const val MAX_LINE = 400

    fun outcome(agent: Agent, stage: Stage, events: File, stderr: File, exitCode: Int?): RunOutcome {
        val parsed = when {
            stage == Stage.REVIEW && agent == Agent.CODEX -> RunOutcome(summary = events.readTextOrEmpty().trim().take(MAX_SUMMARY).ifEmpty { null })
            agent == Agent.CLAUDE -> claude(lines(events))
            else -> codex(lines(events))
        }
        val stderrText = stderr.readTextOrEmpty().takeLast(MAX_SUMMARY)
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

    internal fun lines(file: File): Sequence<JsonObject> {
        if (!file.isFile) return emptySequence()
        return file.readLines().asSequence().mapNotNull { line ->
            runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        }
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

internal fun File.readTextOrEmpty(): String = if (isFile) readText() else ""

/** A run's recent steps in readable form, for the run log on the phone. Redacted. */
object RunActivity {
    /** The last [max] readable steps of a run, redacted. */
    fun activity(agent: Agent, stage: Stage, events: File, max: Int = 150): List<ActivityLine> {
        if (stage == Stage.REVIEW && agent == Agent.CODEX) {
            return events.readTextOrEmpty().lines().filter { it.isNotBlank() }.takeLast(max)
                .map { ActivityLine("text", RunOutput.redact(it).take(RunOutput.MAX_LINE)) }
        }
        val steps = RunOutput.lines(events).flatMap { if (agent == Agent.CLAUDE) claudeSteps(it) else codexSteps(it) }
        return steps.toList().takeLast(max).map { it.copy(text = RunOutput.redact(it.text).take(RunOutput.MAX_LINE)) }
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
        return listOf("command", "file_path", "pattern", "path", "url", "description").firstNotNullOfOrNull { obj.string(it) }.orEmpty()
    }

}
