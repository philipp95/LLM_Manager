package com.llmmanager.server.api

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Where the dashboard listens. It must be reachable only over the tailnet, so the
 * address is the machine's Tailscale IPv4 (CGNAT range 100.64.0.0/10). Loopback is
 * accepted for local development. Wildcard and LAN/public addresses are refused.
 */
object BindAddress {

    /** Parses and validates [explicit], or asks `tailscale ip -4` when it is null. */
    fun resolve(explicit: String?, tailscaleIpv4: () -> String? = ::queryTailscale): Inet4Address {
        val text = explicit ?: tailscaleIpv4()
            ?: error("Could not read the Tailscale IPv4 from `tailscale ip -4`. Is Tailscale up? Refusing to bind elsewhere.")
        require(IPV4_LITERAL.matches(text)) { "Bind address must be an IPv4 literal, got '$text'" }
        // Safe: a dotted-quad literal never triggers a DNS lookup.
        val address = InetAddress.getByName(text) as Inet4Address
        require(!address.isAnyLocalAddress) { "Refusing to bind to the wildcard address $text" }
        require(isTailscale(address) || address.isLoopbackAddress) {
            "Refusing to bind to $text: only a Tailscale address (100.64.0.0/10) or loopback is allowed"
        }
        return address
    }

    fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    private fun queryTailscale(): String? = try {
        val process = ProcessBuilder("tailscale", "ip", "-4").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val finished = process.waitFor(TAILSCALE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = if (finished && process.exitValue() == 0) process.inputStream.bufferedReader().readText() else null
        process.destroyForcibly()
        output?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    private const val TAILSCALE_TIMEOUT_SECONDS = 5L
    private val IPV4_LITERAL = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
}
