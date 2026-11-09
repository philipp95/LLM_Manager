package com.llmmanager.server.api

import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class UsageResponse(
    val days: List<String>,
    val claude: AgentUsage,
    val codex: AgentUsage,
    val gemini: AgentUsage = AgentUsage(emptyList(), null, emptyList(), emptyList(), emptyList()),
)

@Serializable
data class AgentUsage(
    /** Plan limit windows (e.g. 5-hour and weekly) as last observed; empty when unknown. */
    val windows: List<LimitWindow>,
    val windowsAsOf: String?,
    /** Tokens per day aligned with [UsageResponse.days]: input + output (+ cache writes), excluding cache reads. */
    val tokensPerDay: List<Long>,
    val cacheReadsPerDay: List<Long>,
    val models: List<ModelTokens>,
    val note: String? = null,
)

@Serializable
data class LimitWindow(val label: String, val usedPercent: Double, val resetsAt: String?)

@Serializable
data class ModelTokens(val model: String, val tokens: Long)

/**
 * Usage from local records only; nothing is fetched from a provider.
 * - Claude tokens: `~/.claude/projects/` session logs, one count per message id.
 * - Claude plan %: written by the status-line recorder (scripts/claude-statusline.py).
 * - Codex tokens and plan %: `token_count` events in `~/.codex/sessions/` rollouts.
 */
class UsageReader(home: File, stateDir: File, private val clock: Clock = Clock.systemDefaultZone()) {
    private val claude = ClaudeUsage(home, stateDir, clock)
    private val codex = CodexUsage(home, clock)
    private val gemini = GeminiUsage(stateDir, clock)

    @Synchronized
    fun read(): UsageResponse {
        val window = ActivityWindow(clock)
        return UsageResponse(window.days.map { it.toString() }, claude.read(window), codex.read(window), gemini.read(window))
    }

    /** The soonest reset of a window that is (nearly) used up, for retrying after a limit. */
    fun resetHint(agent: Agent): Instant? {
        val usage = read().let {
            when (agent) {
                Agent.CLAUDE -> it.claude
                Agent.CODEX -> it.codex
                Agent.GEMINI -> it.gemini
            }
        }
        val windows = usage.windows.filter { it.resetsAt != null }
        val pick = windows.filter { it.usedPercent >= FULL_PERCENT }.ifEmpty { windows }
        return pick.mapNotNull { ScheduleTimes.instant(it.resetsAt) }.minOrNull()
    }

    companion object {
        const val CLAUDE_LIMITS_FILE = "claude-rate-limits.json"
        private const val FULL_PERCENT = 99.0
    }
}

private val usageJson = Json { ignoreUnknownKeys = true }

private class ClaudeUsage(private val home: File, private val stateDir: File, private val clock: Clock) {
    private data class Entry(val id: String, val at: Instant, val model: String, val tokens: Long, val cacheReads: Long)

    private val logs = JsonlTail(::entry)

    fun read(window: ActivityWindow): AgentUsage {
        val since = window.start(clock)
        val files = File(home, ".claude/projects").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jsonl") && it.lastModified() >= since.toEpochMilli() }
        // Resumed sessions repeat earlier messages in a new file: count each message id once.
        val entries = files.flatMap(logs::read).distinctBy { it.id }.filter { !it.at.isBefore(since) }.toList()
        val (windows, asOf) = latestWindows()
        return AgentUsage(
            windows = windows,
            windowsAsOf = asOf?.toString(),
            tokensPerDay = window.sum(clock, entries.map { it.at to it.tokens }),
            cacheReadsPerDay = window.sum(clock, entries.map { it.at to it.cacheReads }),
            models = entries.groupBy { it.model }.map { (model, list) -> ModelTokens(model, list.sumOf { it.tokens }) }.sortedByDescending { it.tokens },
            note = if (asOf == null) "Plan limits appear after you next use Claude Code interactively (status-line recorder)." else null,
        )
    }

    private fun entry(line: JsonObject): Entry? {
        val message = (line["message"] as? JsonObject)?.takeIf { line.string("type") == "assistant" }
        val usage = message?.get("usage") as? JsonObject
        val id = message?.string("id")
        val at = usageInstant(line.string("timestamp"))
        if (usage == null || id == null || at == null) return null
        val tokens = usage.long("input_tokens") + usage.long("output_tokens") + usage.long("cache_creation_input_tokens")
        return Entry(id, at, message.string("model") ?: "unknown", tokens, usage.long("cache_read_input_tokens"))
    }

    /**
     * Plan windows from whichever source saw them last: the status-line recorder
     * (interactive sessions) or a scheduled run's `rate_limit_event`.
     */
    private fun latestWindows(): Pair<List<LimitWindow>, Instant?> {
        val recorded = File(stateDir, UsageReader.CLAUDE_LIMITS_FILE).takeIf { it.isFile }
            ?.let { runCatching { usageJson.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
        val fromStatusLine = recorded?.let { r ->
            val at = usageInstant(r.string("capturedAt"))
            (r["rate_limits"] as? JsonObject)?.let { windows(it, "used_percentage", "resets_at", PERCENT) }?.let { it to at }
        }
        val fromRuns = latestRunEvent()
        return listOfNotNull(fromStatusLine, fromRuns).maxByOrNull { it.second ?: Instant.MIN } ?: (emptyList<LimitWindow>() to null)
    }

    /** The newest `unifiedWindows` reported by a scheduled Claude run. Utilisation there is a fraction. */
    private fun latestRunEvent(): Pair<List<LimitWindow>, Instant?>? {
        val events = File(stateDir, "runs").listFiles().orEmpty().map { File(it, "events.jsonl") }.filter { it.isFile }.maxByOrNull { it.lastModified() }
        val unified = events?.useLines { lines ->
            lines.filter { "rate_limit_event" in it }.mapNotNull { line ->
                runCatching { usageJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    ?.let { (it["rate_limit_info"] as? JsonObject)?.get("unifiedWindows") as? JsonObject }
            }.lastOrNull()
        } ?: return null
        return windows(unified, "utilization", "resetsAt", FRACTION) to Instant.ofEpochMilli(events.lastModified())
    }

    private fun windows(limits: JsonObject, usedKey: String, resetKey: String, scale: Double): List<LimitWindow> =
        listOf("five_hour" to "5-hour", "seven_day" to "Weekly").mapNotNull { (key, label) ->
            val w = limits[key] as? JsonObject ?: return@mapNotNull null
            val resets = (w[resetKey] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochSecond)
            limitWindow(clock, label, (w[usedKey] as? JsonPrimitive)?.doubleOrNull?.times(scale), resets)
        }

    private companion object {
        const val PERCENT = 1.0
        const val FRACTION = 100.0
    }
}

private class CodexUsage(private val home: File, private val clock: Clock) {
    private data class Entry(val at: Instant, val tokens: Long, val cacheReads: Long, val limits: JsonObject?)

    private val rollouts = JsonlTail(::entry)

    fun read(window: ActivityWindow): AgentUsage {
        val since = window.start(clock)
        val root = File(home, ".codex/sessions")
        val entries = root.walkTopDown()
            .filter { it.isFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") && it.lastModified() >= since.toEpochMilli() }
            .flatMap(rollouts::read).filter { !it.at.isBefore(since) }.toList()
        val latest = entries.filter { it.limits != null }.maxByOrNull { it.at }
        return AgentUsage(
            windows = latest?.let { windows(checkNotNull(it.limits), it.at) }.orEmpty(),
            windowsAsOf = latest?.at?.toString(),
            tokensPerDay = window.sum(clock, entries.map { it.at to it.tokens }),
            cacheReadsPerDay = window.sum(clock, entries.map { it.at to it.cacheReads }),
            models = emptyList(),
            note = when {
                !root.isDirectory -> "No Codex sessions recorded yet. Sign in with `codex login` on the server."
                latest == null -> "Plan limits appear after Codex's next request."
                else -> null
            },
        )
    }

    private fun entry(line: JsonObject): Entry? {
        val payload = (line["payload"] as? JsonObject)?.takeIf { it.string("type") == "token_count" }
        val at = usageInstant(line.string("timestamp"))
        if (payload == null || at == null) return null
        val last = (payload["info"] as? JsonObject)?.get("last_token_usage") as? JsonObject
        val cached = last?.long("cached_input_tokens") ?: 0
        val tokens = last?.let { it.long("input_tokens") - cached + it.long("output_tokens") } ?: 0
        return Entry(at, tokens, cached, payload["rate_limits"] as? JsonObject)
    }

    private fun windows(limits: JsonObject, observedAt: Instant): List<LimitWindow> = listOf("primary", "secondary").mapNotNull { key ->
        val w = limits[key] as? JsonObject ?: return@mapNotNull null
        val resets = (w["resets_at"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochSecond)
            ?: (w["resets_in_seconds"] as? JsonPrimitive)?.longOrNull?.let { observedAt.plusSeconds(it) }
        limitWindow(clock, label(key, (w["window_minutes"] as? JsonPrimitive)?.longOrNull), (w["used_percent"] as? JsonPrimitive)?.doubleOrNull, resets)
    }

    private fun label(key: String, minutes: Long?): String = when (minutes) {
        null -> key.replaceFirstChar { it.uppercase() }
        FIVE_HOURS_MIN -> "5-hour"
        WEEK_MIN -> "Weekly"
        else -> if (minutes % MIN_PER_DAY == 0L) "${minutes / MIN_PER_DAY}-day" else "${minutes / MIN_PER_HOUR}-hour"
    }

    private companion object {
        const val FIVE_HOURS_MIN = 300L
        const val WEEK_MIN = 10_080L
        const val MIN_PER_DAY = 1_440L
        const val MIN_PER_HOUR = 60L
    }
}

/**
 * Gemini tokens from the `result.stats` of runs this server started. Gemini's own
 * session files are not read, and it reports no plan limits, so windows stay empty.
 */
private class GeminiUsage(private val stateDir: File, private val clock: Clock) {
    private data class Entry(val at: Instant, val tokens: Long, val cacheReads: Long, val models: Map<String, Long>)

    private val results = JsonlTail(::entry)

    fun read(window: ActivityWindow): AgentUsage {
        val since = window.start(clock)
        val entries = File(stateDir, "runs").listFiles().orEmpty().map { File(it, "events.jsonl") }
            .filter { it.isFile && it.lastModified() >= since.toEpochMilli() }
            .flatMap(results::read).filter { !it.at.isBefore(since) }
        val models = entries.flatMap { it.models.entries }.groupBy({ it.key }, { it.value })
        return AgentUsage(
            windows = emptyList(),
            windowsAsOf = null,
            tokensPerDay = window.sum(clock, entries.map { it.at to it.tokens }),
            cacheReadsPerDay = window.sum(clock, entries.map { it.at to it.cacheReads }),
            models = models.map { (model, tokens) -> ModelTokens(model, tokens.sum()) }.sortedByDescending { it.tokens },
            note = "Counts Gemini runs started by LLM Manager; Gemini does not report plan limits.",
        )
    }

    private fun entry(line: JsonObject): Entry? {
        val stats = (line["stats"] as? JsonObject)?.takeIf { line.string("type") == "result" && it.containsKey("models") }
        val at = usageInstant(line.string("timestamp"))
        if (stats == null || at == null) return null
        val cached = stats.long("cached")
        val models = (stats["models"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            (value as? JsonObject)?.let { name to (it.long("input_tokens") - it.long("cached") + it.long("output_tokens")) }
        }.toMap()
        return Entry(at, stats.long("input_tokens") - cached + stats.long("output_tokens"), cached, models)
    }
}

/** Append-only JSONL logs, read incrementally: each call parses only lines completed since the last. */
private class JsonlTail<T>(private val parse: (JsonObject) -> T?) {
    private class State<T>(var offset: Long = 0, val items: MutableList<T> = mutableListOf())

    private val files = mutableMapOf<String, State<T>>()

    fun read(file: File): List<T> {
        val state = files.getOrPut(file.path) { State() }
        val length = file.length()
        if (length < state.offset) { // truncated or replaced: start over
            state.offset = 0
            state.items.clear()
        }
        if (length > state.offset) append(file, state, length)
        return state.items
    }

    private fun append(file: File, state: State<T>, length: Long) {
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(state.offset)
            ByteArray((length - state.offset).toInt()).also(raf::readFully)
        }
        // A partly written last line is left for the next read.
        val end = bytes.lastIndexOf('\n'.code.toByte())
        if (end < 0) return
        String(bytes, 0, end, Charsets.UTF_8).lineSequence()
            .mapNotNull { runCatching { usageJson.parseToJsonElement(it) as? JsonObject }.getOrNull()?.let(parse) }
            .forEach(state.items::add)
        state.offset += end + 1
    }
}

/** A window whose reset has passed is no longer meaningful, so it is dropped. */
private fun limitWindow(clock: Clock, label: String, used: Double?, resetsAt: Instant?): LimitWindow? =
    if (used == null || (resetsAt != null && resetsAt.isBefore(Instant.now(clock)))) {
        null
    } else {
        LimitWindow(label, Math.round(used * TENTHS) / TENTHS, resetsAt?.toString())
    }

private const val TENTHS = 10.0

private fun ActivityWindow.start(clock: Clock): Instant = days.first().atStartOfDay(clock.zone).toInstant()

private fun ActivityWindow.sum(clock: Clock, values: List<Pair<Instant, Long>>): List<Long> {
    val sums = LongArray(days.size)
    val first = days.first().toEpochDay()
    values.forEach { (at, v) ->
        val index = (LocalDate.ofInstant(at, clock.zone).toEpochDay() - first).toInt()
        if (index in sums.indices) sums[index] += v
    }
    return sums.toList()
}

private fun usageInstant(text: String?): Instant? = try {
    text?.let(Instant::parse)
} catch (_: DateTimeParseException) {
    null
}

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
