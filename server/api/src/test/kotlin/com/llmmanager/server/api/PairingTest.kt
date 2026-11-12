package com.llmmanager.server.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class PairingTest {
    @TempDir
    lateinit var directory: Path

    private val state: File get() = directory.toFile()

    private val phone = "100.64.0.2"
    private val laptop = "100.64.0.3"
    private val friend = "100.64.0.4"
    private val tagged = "100.64.0.5"
    private val server = "100.64.0.1"

    /** The owner's phone and laptop, a friend's shared device, a tagged node, and the server itself. */
    private val tailnet = object : Tailnet {
        override fun whois(address: String) = when (address) {
            phone -> TailnetPeer("nPHONE", "pixel", userId = 1, os = "android")
            laptop -> TailnetPeer("nLAPTOP", "laptop", userId = 1, os = "windows")
            friend -> TailnetPeer("nFRIEND", "friends-phone", userId = 2)
            tagged -> TailnetPeer("nCI", "ci-runner", userId = 1, tagged = true)
            server -> TailnetPeer("nSERVER", "box", userId = 1)
            else -> null
        }

        override fun owner() = TailnetOwner("nSERVER", 1)
    }

    private fun paired(outcome: Pairing.Outcome) = (outcome as Pairing.Outcome.Paired)

    private fun refusal(outcome: Pairing.Outcome) = (outcome as Pairing.Outcome.Refused).reason

    @Test
    fun `the owner's devices pair, but not the server itself, tagged nodes, other people or strangers`() {
        val pairing = Pairing(tailnet, state)

        val app = paired(pairing.pair(phone, "app"))

        assertEquals("pixel", app.device.name)
        assertTrue(refusal(pairing.pair(server, "app")).contains("itself"), "an agent on the server must not pair itself")
        assertTrue(refusal(pairing.pair(tagged, "app")).contains("Tagged"))
        assertTrue(refusal(pairing.pair(friend, "app")).contains("owner's Tailscale account"))
        assertTrue(refusal(pairing.pair("192.168.1.20", "app")).contains("tailnet"))
        assertTrue(refusal(pairing.pair(phone, "cli")).contains("Unknown client"))
        val noOwner = Pairing(object : Tailnet by tailnet { override fun owner(): TailnetOwner? = null }, File(state, "other").apply { mkdirs() })
        assertTrue(refusal(noOwner.pair(phone, "app")).contains("as a person"))
    }

    @Test
    fun `a device token works only from its own device, until it is replaced or revoked`() {
        val pairing = Pairing(tailnet, state)
        val first = paired(pairing.pair(phone, "app"))
        val web = paired(pairing.pair(phone, "web"))

        assertNotNull(pairing.device(first.token, phone))
        assertNull(pairing.device(first.token, laptop), "a copied token is useless on another device")
        assertNull(pairing.device("x".repeat(43), phone))

        val second = paired(pairing.pair(phone, "app"))
        assertNull(pairing.device(first.token, phone), "pairing again replaces the earlier app token")
        assertNotNull(pairing.device(second.token, phone))
        assertNotNull(pairing.device(web.token, phone), "the browser on the same phone keeps its own token")

        // Only hashes are stored, and they survive a restart.
        val stored = File(state, "devices.json").readText()
        assertTrue(!stored.contains(second.token) && stored.contains(Pairing.hash(second.token)))
        val restarted = Pairing(tailnet, state)
        assertNotNull(restarted.device(second.token, phone))

        assertTrue(restarted.revoke(second.device.id))
        assertNull(restarted.device(second.token, phone))
        assertEquals(listOf("web"), restarted.list().map { it.client })
    }

    @Test
    fun `tailscale whois and status output`() {
        val whois = """
            {"Node":{"ID":5520553614748953,"StableID":"nxCtgmaG7k11CNTRL","Name":"philipps-s25.tail257d79.ts.net.","User":7653111543966194,
              "ComputedName":"philipps-s25","Hostinfo":{"OS":"android"}},
             "UserProfile":{"ID":7653111543966194,"LoginName":"someone@example.com","DisplayName":"Someone"}}
        """.trimIndent()
        assertEquals(
            TailnetPeer("nxCtgmaG7k11CNTRL", "philipps-s25", 7653111543966194, "someone@example.com", tagged = false, os = "android"),
            TailscaleCli.parseWhois(whois),
        )
        assertEquals(true, TailscaleCli.parseWhois("""{"Node":{"StableID":"nCI","Name":"ci.x.ts.net.","User":1,"Tags":["tag:ci"]}}""")?.tagged)
        assertNull(TailscaleCli.parseWhois("not json"))

        assertEquals(TailnetOwner("n6PQ", 7653111543966194), TailscaleCli.parseOwner("""{"Self":{"ID":"n6PQ","UserID":7653111543966194,"Tags":null}}"""))
        assertNull(TailscaleCli.parseOwner("""{"Self":{"ID":"n6PQ","UserID":1,"Tags":["tag:server"]}}"""), "a tagged server has no owner to match")
        assertTrue(TailscaleCli.isTailnetIpv4("100.111.224.117"))
        assertTrue(!TailscaleCli.isTailnetIpv4("192.168.1.2") && !TailscaleCli.isTailnetIpv4("--help") && !TailscaleCli.isTailnetIpv4("100.128.0.1"))
    }

    /** A request from the device at [address], to the server's tailnet name. */
    private fun HttpRequestBuilder.from(address: String) {
        header(PEER, address)
        header(HttpHeaders.Host, "box:8080")
    }

    @Test
    fun `pairing over HTTP, refused when another site makes the browser ask`() = testApplication {
        val services = DashboardServices(pairing = Pairing(tailnet, state), peerAddress = { it.request.headers[PEER].orEmpty() })
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken("t".repeat(40)), services, allowedHosts = setOf("box:8080")) }
        suspend fun pair(address: String, block: HttpRequestBuilder.() -> Unit = {}) = client.post("/api/pair") {
            from(address)
            contentType(ContentType.Application.Json)
            setBody("""{"client":"web"}""")
            block()
        }

        val response = pair(phone) { header(HttpHeaders.Origin, "http://box:8080"); header("Sec-Fetch-Site", "same-origin") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val token = Json.decodeFromString<PairResponse>(response.bodyAsText()).token
        assertEquals(HttpStatusCode.OK, client.get("/api/projects") { from(phone); header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/projects") { from(laptop); header(HttpHeaders.Authorization, "Bearer $token") }.status)

        assertEquals(HttpStatusCode.Forbidden, pair(phone) { header("Sec-Fetch-Site", "cross-site") }.status)
        assertEquals(HttpStatusCode.Forbidden, pair(phone) { header(HttpHeaders.Origin, "http://evil.example") }.status)
        assertEquals(HttpStatusCode.Forbidden, pair(phone) { header(HttpHeaders.Origin, "null") }.status)
        assertEquals(HttpStatusCode.Forbidden, pair(friend).status)

        // The app pairs without an Origin; then it can list devices and revoke one.
        val app = Json.decodeFromString<PairResponse>(client.post("/api/pair") { from(phone) }.bodyAsText())
        val devices = Json.decodeFromString<DevicesResponse>(
            client.get("/api/devices") { from(phone); header(HttpHeaders.Authorization, "Bearer ${app.token}") }.bodyAsText(),
        ).devices
        assertEquals(setOf("web" to false, "app" to true), devices.map { it.client to it.current }.toSet())
        val web = devices.single { it.client == "web" }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/devices/${web.id}/revoke") { from(phone); header(HttpHeaders.Authorization, "Bearer ${app.token}") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/projects") { from(phone); header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test
    fun `the apk carries its version and checksum`() {
        val dir = File(state, "apk").apply { mkdirs() }
        val apk = File(dir, "app-release.apk").apply { writeBytes(ByteArray(2048) { (it % 7).toByte() }) }
        File(dir, "output-metadata.json").writeText(
            """{"version":3,"elements":[{"type":"SINGLE","versionCode":600,"versionName":"0.6.0","outputFile":"app-release.apk"}]}""",
        )

        val app = ApkFile(apk).describe()!!

        assertEquals(600 to "0.6.0", app.versionCode to app.versionName)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apk.readBytes())), app.sha256)
        assertEquals(2048, app.sizeBytes)
        File(dir, "output-metadata.json").delete()
        assertNull(ApkFile(apk).describe()!!.versionCode, "without build metadata there is no version to compare")
    }

    private companion object {
        const val PEER = "X-Test-Peer"
    }
}
