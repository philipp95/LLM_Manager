package com.llmmanager.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BindAddressTest {
    @Test
    fun `tailscale address from the CLI is used by default`() {
        assertEquals("100.101.102.103", BindAddress.resolve(null) { "100.101.102.103" }.hostAddress)
    }

    @Test
    fun `explicit tailscale and loopback addresses are accepted`() {
        assertEquals("100.64.0.1", BindAddress.resolve("100.64.0.1") { error("not consulted") }.hostAddress)
        assertEquals("100.127.255.254", BindAddress.resolve("100.127.255.254").hostAddress)
        assertEquals("127.0.0.1", BindAddress.resolve("127.0.0.1").hostAddress)
    }

    @Test
    fun `wildcard, LAN, public and non-literal addresses are refused`() {
        listOf("0.0.0.0", "192.168.1.10", "10.0.0.5", "100.63.255.255", "100.128.0.1", "8.8.8.8", "::", "localhost", "example.com")
            .forEach { host -> assertFailsWith<IllegalArgumentException>(host) { BindAddress.resolve(host) } }
    }

    @Test
    fun `missing tailscale fails instead of falling back`() {
        assertFailsWith<IllegalStateException> { BindAddress.resolve(null) { null } }
    }

    @Test
    fun `magic dns names come from the local node and become the host allowlist`() {
        val status = """{"Version":"1.90","Self":{"ID":"n1","HostName":"os-name","DNSName":"Box.Tail257.ts.net.","TailscaleIPs":["100.1.2.3"]},""" +
            """"Peer":{"k":{"DNSName":"other.tail257.ts.net."}}}"""
        assertEquals(listOf("box.tail257.ts.net", "box"), BindAddress.tailscaleNames { status })
        assertEquals(emptyList(), BindAddress.tailscaleNames { null })
        assertEquals(emptyList(), BindAddress.tailscaleNames { """{"Self":{"DNSName":"bad name;rm.ts.net."}}""" })

        val hosts = BindAddress.allowedHosts(BindAddress.resolve("100.101.102.103"), 8080, listOf("box.tail257.ts.net", "box"), listOf("Extra.example"))
        assertEquals(setOf("100.101.102.103:8080", "box.tail257.ts.net:8080", "box:8080", "extra.example:8080"), hosts)
    }
}
