package com.llmmanager.server.runner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {

    private val redactor = SecretRedactor()

    @Test
    fun `redacts known credential shapes`() {
        val cases = listOf(
            "token=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
            "github_pat_11ABCDEFG0aBcDeFgHiJkL_mNoPqRsTuVwXyZ",
            "key: sk-ant-api03-AbCdEfGhIjKlMnOpQrSt",
            "slack xoxb-123456789012-abcdefghijkl",
            "Authorization: Bearer eyJhbGciOi.eyJzdWIiOiI.SflKxwRJSM",
            "x-api-key: supersecretvalue123",
        )
        for (case in cases) {
            val out = redactor.redact(case)
            assertTrue(out.contains("[REDACTED]"), "no redaction in: $case -> $out")
        }
    }

    @Test
    fun `keeps the header name so the line stays readable`() {
        val out = redactor.redact("Authorization: Bearer ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")
        assertTrue(out.startsWith("Authorization: Bearer "), out)
        assertFalse(out.contains("ghp_"), out)
    }

    @Test
    fun `redacts an exactly known secret`() {
        val r = redactor.withKnownSecret("hunter2-the-long-one")
        val out = r.redact("connecting with hunter2-the-long-one now")
        assertFalse(out.contains("hunter2"), out)
    }

    @Test
    fun `ignores short known secrets that would redact ordinary words`() {
        val r = redactor.withKnownSecret("abc")
        assertTrue(r.redact("abc def").contains("abc"))
    }

    @Test
    fun `leaves innocuous output alone`() {
        val text = "BUILD SUCCESSFUL in 4s\n12 tests passed"
        assertTrue(redactor.redact(text) == text)
    }

    @Test
    fun `redacts a PEM private key block`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----"
        assertFalse(redactor.redact(pem).contains("MIIEow"))
    }
}
