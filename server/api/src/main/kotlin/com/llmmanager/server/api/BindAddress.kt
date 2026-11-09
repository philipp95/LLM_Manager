package com.llmmanager.server.api

import java.net.Inet4Address
import java.net.InetAddress

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

    /**
     * The machine's MagicDNS names, full (`host.tailnet.ts.net`) and short (`host`), so
     * clients can use them; any other Host header is refused.
     */
    fun tailscaleNames(status: () -> String? = { Processes.run(listOf("tailscale", "status", "--json"), timeoutMillis = 5_000L) }): List<String> {
        val dnsName = status()?.let { MAGIC_DNS.find(it)?.groupValues?.get(1) }?.trimEnd('.')?.lowercase() ?: return emptyList()
        if (!dnsName.matches(Regex("[a-z0-9.-]+"))) return emptyList()
        return listOf(dnsName, dnsName.substringBefore('.')).distinct()
    }

    /** `host:port` values the server answers to: the bind address, MagicDNS names and [extra]. */
    fun allowedHosts(address: Inet4Address, port: Int, names: List<String>, extra: List<String>): Set<String> =
        (listOf(address.hostAddress) + names + extra).map { it.lowercase() }.flatMap { name ->
            if (port == HTTP_PORT) listOf("$name:$port", name) else listOf("$name:$port")
        }.toSet()

    fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    private fun queryTailscale(): String? =
        Processes.run(listOf("tailscale", "ip", "-4"), timeoutMillis = TAILSCALE_TIMEOUT_MILLIS)
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    private const val TAILSCALE_TIMEOUT_MILLIS = 5_000L
    private val IPV4_LITERAL = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
    /** Self.DNSName in `tailscale status --json`; the first DNSName in the output is the local node's. */
    private val MAGIC_DNS = Regex("\"Self\"\\s*:\\s*\\{.*?\"DNSName\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
    private const val HTTP_PORT = 80
}
