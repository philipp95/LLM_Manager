package com.llmmanager.server.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ApiTokenTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `token is created private, reused, and checked exactly`() {
        val file = directory.resolve("config/api-token").toFile()

        val token = ApiToken.loadOrCreate(file)
        val value = file.readText().trim()

        assertTrue(value.length >= 32)
        if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        }
        assertTrue(token.accepts("Bearer $value"))
        assertTrue(ApiToken.loadOrCreate(file).accepts("Bearer $value"), "existing token is reused")
        assertFalse(token.accepts(null))
        assertFalse(token.accepts("Bearer "))
        assertFalse(token.accepts("Bearer ${value}x"))
        assertFalse(token.accepts("Bearer " + value.dropLast(1)))
    }
}
