package com.llmmanager.android.data

import java.net.URI
import java.net.URISyntaxException

/**
 * A dashboard base URL the app is willing to talk to. Only tailnet hosts qualify:
 * the server binds to its Tailscale IPv4, and the access token must never be sent
 * anywhere else, so a typo or a pasted public URL is rejected up front.
 */
@JvmInline
value class ServerAddress private constructor(val baseUrl: String) {

    sealed interface Parsed {
        data class Valid(val address: ServerAddress) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    companion object {
        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
        private val TS_NET = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\.ts\.net$""")

        fun parse(input: String): Parsed {
            val text = input.trim().trimEnd('/')
            val uri = try {
                URI(if ("://" in text) text else "http://$text")
            } catch (_: URISyntaxException) {
                return Parsed.Invalid("Not a valid address")
            }
            val host = uri.host?.lowercase()
            return when {
                uri.scheme !in setOf("http", "https") -> Parsed.Invalid("Use http:// or https://")
                host == null -> Parsed.Invalid("Missing host")
                uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ->
                    Parsed.Invalid("Enter only the address and port, e.g. http://100.101.102.103:8080")
                !isTailnetHost(host) -> Parsed.Invalid("Only Tailscale addresses (100.64.0.0/10 or *.ts.net) are allowed")
                else -> Parsed.Valid(ServerAddress("${uri.scheme}://$host" + if (uri.port > 0) ":${uri.port}" else ""))
            }
        }

        fun isTailnetHost(host: String): Boolean {
            val ip = IPV4.matchEntire(host)
            if (ip == null) return TS_NET.matches(host)
            val octets = ip.groupValues.drop(1).map { it.toInt() }
            return octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
        }
    }
}
