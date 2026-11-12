package com.llmmanager.server.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
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
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parentFile.toPath())))
        }
        assertTrue(token.accepts("Bearer $value"))
        assertTrue(ApiToken.loadOrCreate(file).accepts("Bearer $value"), "existing token is reused")
        assertFalse(token.accepts(null))
        assertFalse(token.accepts("Bearer "))
        assertFalse(token.accepts(value), "the Bearer scheme is required")
        assertFalse(token.accepts("Basic $value"))
        assertFalse(token.accepts("Bearer ${value}x"))
        assertFalse(token.accepts("Bearer " + value.dropLast(1)))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX permissions")
    fun `a token file readable by others is tightened`() {
        val file = directory.resolve("api-token").toFile().apply { writeText("x".repeat(43)) }
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        ApiToken.loadOrCreate(file)

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }
}
