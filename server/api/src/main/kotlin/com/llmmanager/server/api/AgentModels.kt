package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A model the settings page offers for an agent; [id] is what its CLI takes as the model option. */
@Serializable
data class ModelOption(val id: String, val label: String, val note: String? = null)

/**
 * The models each agent's CLI offers, for the settings page's list. Claude Code takes aliases
 * that follow its newest models; Codex keeps the list its account may use in a cache file;
 * Gemini lists its own when asked. Whatever cannot be read falls back to a short fixed list.
 */
class AgentModels(
    private val home: File,
    private val binary: (Agent) -> String?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Listed(val options: List<ModelOption>, val until: Instant)

    @Volatile private var gemini: Listed? = null

    fun options(agent: Agent): List<ModelOption> = when (agent) {
        Agent.CLAUDE -> CLAUDE
        Agent.CODEX -> codex().ifEmpty { CODEX }
        Agent.GEMINI -> gemini()
    }

    /** What the Codex CLI last fetched for this account: the models it shows in its own picker. */
    private fun codex(): List<ModelOption> = runCatching {
        val models = json.parseToJsonElement(File(home, ".codex/models_cache.json").readText()).jsonObject.getValue("models").jsonArray
        models.map { it.jsonObject }
            .filter { it["visibility"]?.jsonPrimitive?.content == "list" }
            .sortedBy { it["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { model ->
                val slug = model["slug"]?.jsonPrimitive?.content?.takeIf { PreferencesStore.modelProblem(it) == null && it.isNotEmpty() }
                slug?.let { ModelOption(it, clean(model["display_name"]?.jsonPrimitive?.content) ?: it, clean(model["description"]?.jsonPrimitive?.content)) }
            }
            .take(MAX_OPTIONS)
    }.getOrDefault(emptyList())

    private fun gemini(): List<ModelOption> {
        val now = Instant.now(clock)
        gemini?.takeIf { now < it.until }?.let { return it.options }
        val listed = binary(Agent.GEMINI)?.let { cli -> Processes.exec(listOf(cli, "--list-models"), timeoutMillis = LIST_TIMEOUT_MS) }
            ?.takeIf { it.ok }?.stdout?.let(::parseGemini).orEmpty()
        // A list that could not be read is asked for again soon; a good one is kept for hours.
        val kept = Listed(listed.ifEmpty { GEMINI }, now + if (listed.isEmpty()) RETRY else KEEP)
        gemini = kept
        return kept.options
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val GEMINI_LINE = Regex("""^([A-Za-z0-9][A-Za-z0-9._-]*) \((.+)\)$""")
        private const val MAX_OPTIONS = 12
        private const val MAX_TEXT = 90
        private const val LIST_TIMEOUT_MS = 20_000L
        private val KEEP: Duration = Duration.ofHours(6)
        private val RETRY: Duration = Duration.ofMinutes(5)

        private val CLAUDE = listOf(
            ModelOption("fable", "Fable", "Newest Fable model"),
            ModelOption("opus", "Opus", "Newest Opus model"),
            ModelOption("sonnet", "Sonnet", "Newest Sonnet model"),
            ModelOption("haiku", "Haiku", "Newest Haiku model: fastest"),
        )
        private val CODEX = listOf(ModelOption("gpt-5-codex", "GPT-5 Codex"))
        private val GEMINI = listOf(
            ModelOption("auto", "Auto", "Gemini CLI picks per task"),
            ModelOption("pro", "Pro"),
            ModelOption("flash", "Flash"),
            ModelOption("flash-lite", "Flash Lite"),
        )

        /** `name (Label)` lines of `gemini --list-models`; everything else there is detail. */
        internal fun parseGemini(output: String): List<ModelOption> = output.lineSequence()
            .mapNotNull { GEMINI_LINE.matchEntire(it.trimEnd()) }
            .map { ModelOption(it.groupValues[1], clean(it.groupValues[2]) ?: it.groupValues[1]) }
            .distinctBy { it.id }.take(MAX_OPTIONS).toList()

        private fun clean(text: String?): String? = text?.filterNot { it.isISOControl() }?.trim()?.take(MAX_TEXT)?.ifEmpty { null }
    }
}
