package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The person using LLM Manager: shown in the apps, and the committer of agent commits. */
@Serializable
data class Profile(val name: String = "", val email: String = "") {
    val complete: Boolean get() = name.isNotBlank() && email.isNotBlank()
}

@Serializable
data class AgentPreference(
    /** Offered in the chat and job pickers. */
    val enabled: Boolean = true,
    /** Passed as the CLI's model option; empty means the CLI's own default. */
    val model: String = "",
)

@Serializable
data class Preferences(
    val profile: Profile = Profile(),
    /** Preselected in the chat. */
    val defaultAgent: Agent? = null,
    val agents: Map<Agent, AgentPreference> = emptyMap(),
    /** Who a code host's token belongs to, as checked when it was saved, e.g. "gitlab:gitlab.com" to a user name. */
    val forgeAccounts: Map<String, String> = emptyMap(),
)

/** [Preferences] in `settings.json` in the state folder, written atomically. Nothing secret is kept here. */
class PreferencesStore(dir: File) {
    private val file = File(dir, "settings.json")

    @Volatile private var current: Preferences = load()

    fun get(): Preferences = current

    @Synchronized
    fun update(change: (Preferences) -> Preferences): Preferences {
        val updated = change(current)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(Preferences.serializer(), updated))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        current = updated
        return updated
    }

    fun enabled(agent: Agent): Boolean = current.agents[agent]?.enabled ?: true

    fun model(agent: Agent): String? = current.agents[agent]?.model?.ifBlank { null }

    /** The person agent commits are committed by, once both name and email are set. */
    fun committer(): Profile? = current.profile.takeIf { it.complete }

    private fun load(): Preferences =
        if (file.isFile) runCatching { json.decodeFromString(Preferences.serializer(), file.readText()) }.getOrDefault(Preferences()) else Preferences()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        private val EMAIL = Regex("""[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+""")
        private val MODEL = Regex("""[A-Za-z0-9][A-Za-z0-9._:/\[\]-]{0,99}""")
        private const val MAX_NAME = 80
        private const val MAX_EMAIL = 120

        /** Why [profile] cannot be saved, or null. Git strips `<`, `>` and line breaks from identities, so they are refused. */
        fun problem(profile: Profile): String? = when {
            profile.name.isBlank() != profile.email.isBlank() -> "Enter both a name and an email, or neither"
            profile.name.length > MAX_NAME || profile.name.any { it.isISOControl() || it == '<' || it == '>' } ->
                "Use a plain name of at most $MAX_NAME characters"
            profile.email.isNotEmpty() && (profile.email.length > MAX_EMAIL || !EMAIL.matches(profile.email)) -> "That does not look like an email address"
            else -> null
        }

        /** Why [model] cannot be passed to a CLI, or null; it must never read as an option. */
        fun modelProblem(model: String): String? =
            if (model.isEmpty() || MODEL.matches(model)) null else "Use a model name such as the CLI lists (letters, digits, . _ : / - [ ])"
    }
}
