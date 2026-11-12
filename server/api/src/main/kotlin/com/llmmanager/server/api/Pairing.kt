package com.llmmanager.server.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** A device that paired itself; its token is stored only as a hash. */
@Serializable
data class PairedDevice(
    val id: String,
    /** Tailscale stable node ID: the token is accepted only from this node. */
    val node: String,
    val name: String,
    val os: String? = null,
    /** `app` or `web`: a phone can have both, each with its own token. */
    val client: String,
    /** SHA-256 of the token, base64url. */
    val tokenHash: String,
    val pairedAt: String,
    val lastSeenAt: String? = null,
)

/** A paired device as clients see it. */
@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    val os: String?,
    val client: String,
    val pairedAt: String,
    val lastSeenAt: String?,
    /** The device making this request. */
    val current: Boolean,
)

@Serializable
data class PairRequest(val client: String = "app")

@Serializable
data class PairResponse(val token: String, val device: DeviceView)

@Serializable
data class DevicesResponse(val devices: List<DeviceView>)

/**
 * Pairing without typing the access token. Tailscale vouches for who is connecting: a device
 * signed in to the same Tailscale account as the server gets its own token, bound to its
 * node and revocable. The server itself (so nothing running on it, such as an agent),
 * tagged nodes and other people's devices are refused and need the access token.
 */
class Pairing(
    private val tailnet: Tailnet,
    stateDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Outcome {
        data class Paired(val token: String, val device: PairedDevice) : Outcome

        data class Refused(val reason: String) : Outcome
    }

    private val file = JsonListFile(File(stateDir, "devices.json"), PairedDevice.serializer())
    private var devices: List<PairedDevice> = file.load()
    private val random = SecureRandom()
    private val log = LoggerFactory.getLogger(Pairing::class.java)

    /** Pairs the device at [address]; pairing again replaces that device's earlier token for [client]. */
    fun pair(address: String, client: String): Outcome {
        if (client !in CLIENTS) return Outcome.Refused("Unknown client")
        val owner = tailnet.owner() ?: return Outcome.Refused("The server must be signed in to Tailscale as a person to pair")
        val peer = tailnet.whois(address) ?: return Outcome.Refused("Only devices on your tailnet can pair")
        refusal(peer, owner)?.let { return Outcome.Refused(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val now = Instant.now(clock).toString()
        val id = HexFormat.of().formatHex(ByteArray(ID_BYTES).also(random::nextBytes))
        val device = PairedDevice(id, peer.node, peer.name, peer.os, client, hash(token), now, now)
        synchronized(this) {
            val others = devices.filterNot { it.node == peer.node && it.client == client }
            devices = (others + device).sortedByDescending { it.lastSeenAt ?: it.pairedAt }.take(MAX_DEVICES)
            file.save(devices)
        }
        log.info("Paired {} ({}, {}) as device {}", peer.name, peer.os, client, device.id)
        return Outcome.Paired(token, device)
    }

    /** The device [token] belongs to, when it is presented from that device's own node. */
    fun device(token: String, address: String): PairedDevice? {
        val presented = hash(token).toByteArray()
        val device = synchronized(this) { devices.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), presented) } }
        return device?.takeIf { tailnet.whois(address)?.node == it.node }?.also(::touch)
    }

    @Synchronized fun list(): List<PairedDevice> = devices

    @Synchronized fun revoke(id: String): Boolean {
        val remaining = devices.filterNot { it.id == id }
        if (remaining.size == devices.size) return false
        devices = remaining
        file.save(devices)
        return true
    }

    private fun refusal(peer: TailnetPeer, owner: TailnetOwner): String? = when {
        peer.node == owner.node -> "The server cannot pair with itself"
        peer.tagged -> "Tagged devices cannot pair"
        peer.userId != owner.userId -> "Only devices signed in to the server owner's Tailscale account can pair"
        else -> null
    }

    /** Records when a device was last seen, at most every few minutes so polling does not rewrite the file. */
    @Synchronized private fun touch(device: PairedDevice) {
        val now = Instant.now(clock)
        val last = device.lastSeenAt?.let(Instant::parse)
        if (last != null && now.isBefore(last.plus(TOUCH_EVERY))) return
        devices = devices.map { if (it.id == device.id) it.copy(lastSeenAt = now.toString()) else it }
        file.save(devices)
    }

    companion object {
        private val CLIENTS = setOf("app", "web")
        private const val TOKEN_BYTES = 32
        private const val ID_BYTES = 8
        private const val MAX_DEVICES = 20
        private val TOUCH_EVERY: Duration = Duration.ofMinutes(10)

        fun hash(token: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }
}

/** Who may use the API: the holder of the access token, or a paired device calling from its own node. */
class Access(
    private val token: ApiToken,
    val pairing: Pairing? = null,
    /** The client's address on the tailnet. */
    val addressOf: (ApplicationCall) -> String = { it.request.local.remoteAddress },
) {
    /** Blocking: may ask tailscaled who is calling. */
    fun accepts(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]
        return token.accepts(header) || ApiToken.bearer(header)?.let { pairing?.device(it, addressOf(call)) } != null
    }

    /** The bearer token [call] presents, if any. */
    fun presented(call: ApplicationCall): String? = ApiToken.bearer(call.request.headers[HttpHeaders.Authorization])
}
