package com.llmmanager.server.api

import java.net.Inet4Address
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A device on the tailnet, as tailscaled reports it for a connecting address. */
data class TailnetPeer(
    /** Tailscale's stable node ID, e.g. `nxCtgmaG7k11CNTRL`. */
    val node: String,
    /** Short MagicDNS name, e.g. `philipps-s25`. */
    val name: String,
    val userId: Long?,
    val login: String? = null,
    /** Tagged nodes belong to their tags, not to a person. */
    val tagged: Boolean = false,
    val os: String? = null,
)

/** This server on the tailnet: its own node, and the person it is signed in as. */
data class TailnetOwner(val node: String, val userId: Long)

/** What tailscaled says about addresses; a seam for tests. */
interface Tailnet {
    /** The device behind a tailnet [address], or null when it is not one (or tailscaled cannot say). */
    fun whois(address: String): TailnetPeer?

    /** This server's node and owner; null when it is signed in as a tag or Tailscale is down. */
    fun owner(): TailnetOwner?
}

/** [Tailnet] through the `tailscale` CLI; its LocalAPI answers read-only questions for every local user. */
class TailscaleCli(private val binary: String = "tailscale") : Tailnet {
    override fun whois(address: String): TailnetPeer? {
        // Only tailnet IPv4 literals (the server binds IPv4): nothing else reaches the command line.
        if (!isTailnetIpv4(address)) return null
        return Processes.run(listOf(binary, "whois", "--json", address), timeoutMillis = TIMEOUT_MS)?.let(::parseWhois)
    }

    override fun owner(): TailnetOwner? = Processes.run(listOf(binary, "status", "--json"), timeoutMillis = TIMEOUT_MS)?.let(::parseOwner)

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val json = Json { ignoreUnknownKeys = true }

        fun isTailnetIpv4(address: String): Boolean =
            IPV4.matches(address) && (InetAddress.getByName(address) as? Inet4Address)?.let(BindAddress::isTailscale) == true

        /** `tailscale whois --json` output. */
        fun parseWhois(text: String): TailnetPeer? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val node = root["Node"] as? JsonObject ?: return null
            val user = root["UserProfile"] as? JsonObject
            val id = node.string("StableID") ?: return null
            val name = node.string("ComputedName") ?: node.string("Name")?.substringBefore('.') ?: id
            return TailnetPeer(
                node = id, name = name, userId = node.long("User"), login = user?.string("LoginName"),
                tagged = (node["Tags"] as? JsonArray)?.isNotEmpty() == true,
                os = (node["Hostinfo"] as? JsonObject)?.string("OS"),
            )
        }

        /** `tailscale status --json` output; null when the server is signed in as a tag. */
        fun parseOwner(text: String): TailnetOwner? {
            val self = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get("Self") as? JsonObject
            val tagged = (self?.get("Tags") as? JsonArray)?.isNotEmpty() == true
            val node = self?.string("ID")
            val user = self?.long("UserID")
            return if (tagged || node == null || user == null) null else TailnetOwner(node, user)
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}

/**
 * Remembers tailscaled's answers for a while: a node keeps its address, and requests come in
 * bursts. Unknown addresses are forgotten sooner, so a newly joined device is seen quickly.
 */
class CachedTailnet(private val tailnet: Tailnet, private val clock: Clock = Clock.systemUTC()) : Tailnet {
    private class Entry<T>(val value: T, val at: Instant)

    private val peers = ConcurrentHashMap<String, Entry<TailnetPeer?>>()

    @Volatile private var owner: Entry<TailnetOwner?>? = null

    override fun whois(address: String): TailnetPeer? {
        val now = Instant.now(clock)
        peers[address]?.takeIf { now.isBefore(it.at.plus(if (it.value != null) KNOWN else UNKNOWN)) }?.let { return it.value }
        return tailnet.whois(address).also { peers[address] = Entry(it, now) }
    }

    override fun owner(): TailnetOwner? {
        val now = Instant.now(clock)
        owner?.takeIf { now.isBefore(it.at.plus(if (it.value != null) KNOWN else UNKNOWN)) }?.let { return it.value }
        return tailnet.owner().also { owner = Entry(it, now) }
    }

    private companion object {
        val KNOWN: Duration = Duration.ofMinutes(5)
        val UNKNOWN: Duration = Duration.ofSeconds(30)
    }
}
