package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Shared secret for endpoints that start processes. Reading the dashboard needs only
 * tailnet membership; launching Codex additionally needs this token, so another
 * device or a web page on the tailnet cannot start sessions on its own.
 */
class ApiToken(private val value: String) {
    init {
        require(value.length >= MIN_LENGTH) { "API token is too short" }
    }

    /** Constant-time check of an `Authorization: Bearer <token>` header. */
    fun accepts(authorization: String?): Boolean {
        val presented = authorization?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val MIN_LENGTH = 32
        private const val BYTES = 32

        /** Reads the token from [file], creating it owner-readable only (0600) on first use. */
        fun loadOrCreate(file: File): ApiToken {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(SecureRandom()::nextBytes))
                val path = file.toPath()
                if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else {
                    Files.createFile(path)
                }
                Files.writeString(path, token + "\n")
            }
            return ApiToken(file.readText().trim())
        }
    }
}
