package com.llmmanager.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun valid(input: String) = (ServerAddress.parse(input) as ServerAddress.Parsed.Valid).address.baseUrl

    @Test
    fun `tailnet addresses are accepted and normalised`() {
        assertEquals("http://100.101.102.103:8080", valid("http://100.101.102.103:8080"))
        assertEquals("http://100.101.102.103:8080", valid(" 100.101.102.103:8080/ "))
        assertEquals("https://box.tail1234.ts.net", valid("https://Box.tail1234.ts.net"))
        assertEquals("http://100.64.0.1", valid("http://100.64.0.1"))
        assertEquals("http://100.127.255.255:1", valid("http://100.127.255.255:1"))
    }

    @Test
    fun `anything that could leak the token elsewhere is rejected`() {
        listOf(
            "http://192.168.1.10:8080", "http://10.0.0.1", "http://8.8.8.8", "http://100.63.255.255", "http://100.128.0.1",
            "http://example.com", "http://ts.net", "http://evil.ts.net.example.com", "http://127.0.0.1:8080",
            "ftp://100.100.100.100", "http://user:pw@100.100.100.100", "http://100.100.100.100/api", "http://100.100.100.100?x=1",
            "http://256.100.100.100", "", "not a url at all",
        ).forEach { assertTrue(it, ServerAddress.parse(it) is ServerAddress.Parsed.Invalid) }
    }
}
