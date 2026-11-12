package com.llmmanager.server.api

import com.llmmanager.server.api.AgentAccounts.Companion.string
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gemini CLI's sign-in state in `~/.gemini` ([dir]): the auth type chosen in `settings.json`,
 * the Google sign-in cached in `oauth_creds.json` (with its email in `google_accounts.json`),
 * and an API key in `.env`, which the CLI loads at startup.
 */
class GeminiFiles(val dir: File) {
    private val settings = File(dir, "settings.json")
    val credentials = File(dir, "oauth_creds.json")
    private val accounts = File(dir, "google_accounts.json")
    private val env = File(dir, ".env")

    /** `security.auth.selectedType`, e.g. oauth-personal or gemini-api-key; null when unset or unreadable. */
    fun selectedType(): String? =
        ((readObject(settings)?.get("security") as? JsonObject)?.get("auth") as? JsonObject)?.string("selectedType")

    /** Chooses [type] in settings.json, keeping every other setting; returns why it could not, or null. */
    fun select(type: String): String? {
        if (selectedType() == type) return null
        val current = if (!settings.isFile) JsonObject(emptyMap())
        else readObject(settings) ?: return "$settings could not be read; set security.auth.selectedType to $type there"
        val security = current["security"] as? JsonObject ?: JsonObject(emptyMap())
        val auth = security["auth"] as? JsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(current + ("security" to JsonObject(security + ("auth" to JsonObject(auth + ("selectedType" to JsonPrimitive(type)))))))
        dir.mkdirs()
        val temp = File(dir, ".settings.json.tmp")
        temp.writeText(pretty.encodeToString(JsonObject.serializer(), updated) + "\n")
        Files.move(temp.toPath(), settings.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return null
    }

    /** Whether a complete Google sign-in is cached: a token file that parses, written at or after [sinceMillis]. */
    fun hasGoogleSignIn(sinceMillis: Long = 0): Boolean {
        if (!credentials.isFile || credentials.lastModified() < sinceMillis) return false
        val creds = readObject(credentials) ?: return false
        return creds["refresh_token"] != null || creds["access_token"] != null
    }

    fun googleAccount(): String? = readObject(accounts)?.string("active")

    fun hasKeyFile(): Boolean = env.isFile && env.readLines().any { KEY_SET.matches(it) }

    /** Gemini's own rules: the chosen auth type decides; without one, a key or Vertex AI in the environment works. */
    fun status(environment: (String) -> String?): AgentStatus {
        val key = environment(KEY) != null || hasKeyFile()
        val google = hasGoogleSignIn()
        val method = when (selectedType()) {
            "oauth-personal" -> "Google account".takeIf { google }
            "gemini-api-key" -> "API key".takeIf { key }
            "vertex-ai" -> "Vertex AI"
            null -> when {
                key -> "API key"
                environment("GOOGLE_GENAI_USE_VERTEXAI") == "true" -> "Vertex AI"
                else -> null
            }
            else -> "Google Cloud"
        }
        val account = googleAccount().takeIf { method == "Google account" }
        return AgentStatus(Agent.GEMINI, installed = true, signedIn = method != null, method = method, account = account)
    }

    /** Saves [key] to `.env` (0600) and chooses API-key sign-in. */
    fun useApiKey(key: String): String? {
        val others = if (env.isFile) env.readLines().filterNot { KEY_LINE.matches(it) } else emptyList()
        SecureFiles.writePrivate(env, (others + "$KEY=$key").joinToString("\n", postfix = "\n"))
        return select("gemini-api-key")
    }

    /** Forgets the Google sign-in and the saved key, as Gemini's own sign-out does. */
    fun signOut(): String? {
        credentials.delete()
        if (env.isFile) {
            val others = env.readLines().filterNot { KEY_LINE.matches(it) }
            if (others.any { it.isNotBlank() }) SecureFiles.writePrivate(env, others.joinToString("\n", postfix = "\n")) else env.delete()
        }
        readObject(accounts)?.let { current ->
            val active = current["active"]
            val previous = listOfNotNull(active?.takeIf { it is JsonPrimitive && it !is JsonNull })
            val old = (current["old"] as? JsonArray).orEmpty().filter { it != active } + previous
            accounts.writeText(pretty.encodeToString(JsonObject.serializer(), JsonObject(mapOf("active" to JsonNull, "old" to JsonArray(old)))))
        }
        return if (credentials.exists()) "Could not remove $credentials" else null
    }

    private fun readObject(file: File): JsonObject? =
        if (!file.isFile) null else runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()

    private companion object {
        const val KEY = "GEMINI_API_KEY"
        val KEY_LINE = Regex("""\s*(export\s+)?GEMINI_API_KEY\s*=.*""")
        val KEY_SET = Regex("""\s*(export\s+)?GEMINI_API_KEY\s*=\s*\S.*""")
        val pretty = Json { prettyPrint = true }
    }
}
