package com.llmmanager.server.api

import java.net.URI
import java.net.URISyntaxException

/** A remote URL that is safe to show: any embedded credentials have been removed. */
@JvmInline
value class RemoteUrl private constructor(val display: String) {

    /** `owner/repo` when the remote is on github.com, else null. */
    val gitHubSlug: String?
        get() {
            val path = SCP_LIKE.matchEntire(display)?.let { match ->
                match.groupValues[3].takeIf { match.groupValues[2].equals(GITHUB, ignoreCase = true) }
            } ?: parseUri(display)?.takeIf { it.host.equals(GITHUB, ignoreCase = true) }?.path
            val parts = path?.trim('/')?.removeSuffix(".git")?.split('/') ?: return null
            return parts.takeIf { it.size == 2 && it.all { part -> SLUG_PART.matches(part) } }?.joinToString("/")
        }

    /** The repository's page on github.com; built from a validated slug, never echoed from config. */
    val gitHubWebUrl: String? get() = gitHubSlug?.let { "https://$GITHUB/$it" }

    companion object {
        private const val GITHUB = "github.com"
        private val SCP_LIKE = Regex("""^(?:([^@/\s]+)@)?([^:/\s]+):(?!//)(.+)$""")
        private val SLUG_PART = Regex("""[A-Za-z0-9_.-]+""")

        /**
         * Removes `user:token@` from URL-style remotes. A clone made with a token in the
         * URL stores it in `remote.origin.url`, and this value is served to the phone.
         */
        fun of(raw: String): RemoteUrl {
            val trimmed = raw.trim()
            val uri = parseUri(trimmed)
            return RemoteUrl(if (uri == null) withoutScpSecret(trimmed) else withoutUriSecret(uri, trimmed))
        }

        /** SCP-style `user@host:path`: a user is fine, `user:secret@` is not. */
        private fun withoutScpSecret(remote: String): String {
            val scp = SCP_LIKE.matchEntire(remote) ?: return remote
            val user = scp.groupValues[1].takeIf { it.isNotEmpty() && ':' !in it }?.let { "$it@" }.orEmpty()
            return "$user${scp.groupValues[2]}:${scp.groupValues[3]}"
        }

        private fun withoutUriSecret(uri: URI, original: String): String {
            val userInfo = uri.rawUserInfo ?: return original
            // Keep a bare SSH user such as `git@`; drop anything that could be a secret.
            val keep = if (uri.scheme.startsWith("ssh") && ':' !in userInfo) "$userInfo@" else ""
            return "${uri.scheme}://$keep${uri.rawAuthority.substringAfterLast('@')}${uri.rawPath.orEmpty()}"
        }

        private fun parseUri(value: String): URI? = try {
            URI(value).takeIf { it.scheme != null && it.rawAuthority != null && it.host != null }
        } catch (_: URISyntaxException) {
            null
        }
    }
}
