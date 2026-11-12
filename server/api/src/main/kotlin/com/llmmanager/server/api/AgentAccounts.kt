package com.llmmanager.server.api

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/** Whether an agent CLI is signed in, and how; never any part of a key or token. */
@Serializable
data class AgentStatus(
    val agent: Agent,
    val installed: Boolean,
    val signedIn: Boolean,
    /** How it is signed in, e.g. "Claude Pro", "ChatGPT", "Google account", "API key". */
    val method: String? = null,
    /** The account's email, when the CLI tells. */
    val account: String? = null,
)

/** Runs a helper command, with [input] on stdin; a seam for tests. */
fun interface CommandRunner {
    fun run(command: List<String>, input: String?): Processes.Exec

    companion object {
        val Default = CommandRunner { command, input -> Processes.exec(command, timeoutMillis = 30_000, input = input) }
    }
}

/**
 * The agent CLIs' own sign-ins on this server: who each is signed in as, signing out, and
 * API keys for the CLIs that take one. Keys arrive on stdin or go to owner-only files, and
 * are never returned.
 */
class AgentAccounts(
    private val binary: (Agent) -> String?,
    home: File = File(System.getProperty("user.home")),
    private val runner: CommandRunner = CommandRunner.Default,
    private val environment: (String) -> String? = System::getenv,
    private val ttlMillis: Long = TTL_MS,
) {
    private class Entry(val status: AgentStatus, val at: Long)

    private val cache = ConcurrentHashMap<Agent, Entry>()
    val gemini = GeminiFiles(File(home, ".gemini"))
    private val log = LoggerFactory.getLogger(AgentAccounts::class.java)

    fun status(agent: Agent): AgentStatus {
        val now = System.nanoTime()
        cache[agent]?.takeIf { now - it.at < ttlMillis * NANOS_PER_MILLI }?.let { return it.status }
        return check(agent).also { cache[agent] = Entry(it, now) }
    }

    fun all(): List<AgentStatus> = Agent.entries.map(::status)

    fun invalidate() = cache.clear()

    /** Whether [agent] can use an API key instead of an account. */
    fun takesApiKey(agent: Agent): Boolean = agent != Agent.CLAUDE

    /** Signs [agent] out; returns why it failed, or null. */
    fun signOut(agent: Agent): String? {
        val cli = binary(agent) ?: return "${agent.label} CLI is not installed on the server"
        val problem = when (agent) {
            Agent.CLAUDE -> failure(runner.run(listOf(cli, "auth", "logout"), null))
            Agent.CODEX -> failure(runner.run(listOf(cli, "logout"), null))
            Agent.GEMINI -> gemini.signOut()
        }
        invalidate()
        log.info("{} sign-out: {}", agent.label, problem ?: "done")
        return problem
    }

    /** Signs [agent] in with an API [key]; returns why it failed, or null. */
    fun useApiKey(agent: Agent, key: String): String? {
        val cli = binary(agent) ?: return "${agent.label} CLI is not installed on the server"
        val trimmed = key.trim()
        val problem = when {
            !takesApiKey(agent) -> "${agent.label} signs in with an account, not an API key"
            !API_KEY.matches(trimmed) -> "That does not look like an API key"
            agent == Agent.CODEX -> failure(runner.run(listOf(cli, "login", "--with-api-key"), trimmed))
            else -> gemini.useApiKey(trimmed)
        }
        invalidate()
        log.info("{} API key: {}", agent.label, problem ?: "saved")
        return problem
    }

    private fun check(agent: Agent): AgentStatus {
        val cli = binary(agent) ?: return AgentStatus(agent, installed = false, signedIn = false)
        return when (agent) {
            Agent.CLAUDE -> claude(runner.run(listOf(cli, "auth", "status", "--json"), null).stdout)
            Agent.CODEX -> codex(runner.run(listOf(cli, "login", "status"), null))
            Agent.GEMINI -> gemini.status(environment)
        }
    }

    private fun failure(exec: Processes.Exec): String? =
        if (exec.ok) null else (exec.stderr + "\n" + exec.stdout).lines().lastOrNull { it.isNotBlank() && !it.startsWith("WARNING") }?.trim()
            ?: "exit ${exec.exitCode}"

    companion object {
        private const val TTL_MS = 20_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private val API_KEY = Regex("[A-Za-z0-9_.-]{20,300}")
        private val json = Json { ignoreUnknownKeys = true }

        /** `claude auth status --json`: loggedIn, authMethod, subscriptionType, email. */
        fun claude(output: String): AgentStatus {
            val root = runCatching { json.parseToJsonElement(output) as? JsonObject }.getOrNull()
            val signedIn = (root?.get("loggedIn") as? JsonPrimitive)?.booleanOrNull == true
            val method = when (root?.string("authMethod")) {
                null, "none" -> null
                "claude.ai" -> root.string("subscriptionType")?.let { "Claude ${it.replaceFirstChar(Char::uppercaseChar)}" } ?: "Claude account"
                else -> "Anthropic API"
            }
            val account = root?.string("email").takeIf { signedIn }
            return AgentStatus(Agent.CLAUDE, installed = true, signedIn = signedIn, method = method.takeIf { signedIn }, account = account)
        }

        /** `codex login status`: "Logged in using ChatGPT", "Logged in using an API key - …", "Not logged in". */
        fun codex(exec: Processes.Exec): AgentStatus {
            val text = exec.stdout + "\n" + exec.stderr
            val method = when {
                !exec.ok || !text.contains("Logged in") -> null
                text.contains("ChatGPT") -> "ChatGPT"
                text.contains("API key") -> "API key"
                else -> "Signed in"
            }
            return AgentStatus(Agent.CODEX, installed = true, signedIn = method != null, method = method)
        }

        internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
