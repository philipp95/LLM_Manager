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
}
