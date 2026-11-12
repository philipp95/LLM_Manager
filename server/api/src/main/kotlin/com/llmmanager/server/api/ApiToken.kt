package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Shared secret for every API endpoint. Tailnet membership only gets a device to the
 * login page; data and actions need this token, so another device on the tailnet, or a
 * web page using DNS rebinding, cannot read repositories or start agents.
 */
class ApiToken(private val value: String) {
    init {
        require(value.length >= MIN_LENGTH) { "API token is too short" }
    }

    /** Constant-time check of an `Authorization: Bearer <token>` header; nothing else is accepted. */
    fun accepts(authorization: String?): Boolean {
        val presented = bearer(authorization) ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val MIN_LENGTH = 32
        private const val BYTES = 32
        private const val SCHEME = "Bearer "

        /** The token of an `Authorization: Bearer <token>` header, or null for anything else. */
        fun bearer(authorization: String?): String? =
            authorization?.takeIf { it.startsWith(SCHEME) }?.substring(SCHEME.length)?.trim()?.ifEmpty { null }

        /** Reads the token from [file], creating it owner-readable only (0600) on first use. */
        fun loadOrCreate(file: File): ApiToken {
            file.absoluteFile.parentFile?.let(SecureFiles::privateDir)
            if (!file.exists()) {
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(SecureRandom()::nextBytes))
                val path = file.toPath()
                if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else {
                    Files.createFile(path)
                }
                Files.writeString(path, token + "\n")
            }
            SecureFiles.makePrivate(file)
            return ApiToken(file.readText().trim())
        }
    }
}
