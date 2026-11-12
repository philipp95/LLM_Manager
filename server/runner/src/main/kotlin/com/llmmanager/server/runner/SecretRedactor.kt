package com.llmmanager.server.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Redacts credentials **on the write path**, before anything reaches the
 * append-only log.
 *
 * This ordering is not negotiable: a secret that reaches an append-only store
 * cannot be removed, by definition. Redacting on read is not a substitute.
 * See `.agents/skills/agent-audit-trail`.
 *
 * Pattern matching catches the known shapes. Callers that hold specific
 * credentials should also register them via [withKnownSecret] so those are
 * removed exactly rather than by guesswork.
 */
class SecretRedactor private constructor(
    private val knownSecrets: List<String>,
) {
    constructor() : this(emptyList())

    fun withKnownSecret(secret: String): SecretRedactor =
        if (secret.length < MIN_KNOWN_SECRET_LENGTH) this
        else SecretRedactor((knownSecrets + secret).distinct().sortedByDescending { it.length })

    /** Decode first so escaping cannot conceal credentials or damage JSON delimiters. */
    fun redactJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { (key, element) ->
            redact(key) to if (key.equals("authorization", true) || key.equals("x-api-key", true)) {
                JsonPrimitive(REDACTED)
            } else {
                redactJson(element)
            }
        })
        is JsonArray -> JsonArray(value.map(::redactJson))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
    }

    /** State belongs to one stdout/stderr stream, never to a shared redactor. */
    fun newStream(): Stream = Stream()

    inner class Stream internal constructor() {
        private var inPrivateKey = false

        fun redact(line: String): String {
            val out = StringBuilder()
            var remaining = line
            while (true) {
                if (inPrivateKey) {
                    val end = PEM_END.find(remaining) ?: return out.append(REDACTED).toString()
                    remaining = remaining.substring(end.range.last + 1)
                    inPrivateKey = false
                }
                val begin = PEM_BEGIN.find(remaining)
                    ?: return out.append(this@SecretRedactor.redact(remaining)).toString()
                out.append(this@SecretRedactor.redact(remaining.substring(0, begin.range.first)))
                out.append(REDACTED)
                remaining = remaining.substring(begin.range.last + 1)
                inPrivateKey = true
            }
        }
    }

    fun redact(input: String): String {
        var out = input
        for (secret in knownSecrets) {
            if (secret.isNotEmpty()) out = out.replace(secret, REDACTED)
        }
        for (pattern in PATTERNS) {
            out = pattern.replace(out) { m ->
                // Keep any leading header/prefix group so the line stays readable.
                val prefix = m.groups.size.takeIf { it > 1 }?.let { m.groupValues[1] } ?: ""
                prefix + REDACTED
            }
        }
        return out
    }

    private companion object {
        const val REDACTED = "[REDACTED]"
        const val MIN_KNOWN_SECRET_LENGTH = 8
        val PEM_BEGIN = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")
        val PEM_END = Regex("""-----END [A-Z ]*PRIVATE KEY-----""")

        val PATTERNS: List<Regex> = listOf(
            // GitHub: classic, fine-grained, and the oauth/app/server variants.
            Regex("""gh[pousr]_[A-Za-z0-9]{16,}"""),
            Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
            // OpenAI / Anthropic style keys.
            Regex("""sk-[A-Za-z0-9\-_]{16,}"""),
            // Slack.
            Regex("""xox[baprs]-[A-Za-z0-9\-]{10,}"""),
            // Authorization headers, keeping the header name visible.
            Regex("""(?i)(Authorization\s*:\s*(?:Bearer|Basic|token)\s+)\S+"""),
            Regex("""(?i)(x-api-key\s*:\s*)\S+"""),
            // JWTs.
            Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""),
            // PEM private key blocks.
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"""),
        )
    }
}
