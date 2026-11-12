package com.llmmanager.server.api

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Android app's APK, offered for download when it has been built. Its version comes from
 * the `output-metadata.json` the Android build writes next to it, so the app can tell when an
 * update is waiting; the SHA-256 lets it check the download before installing.
 */
class ApkFile(val file: File) {
    private class Digest(val length: Long, val modified: Long, val sha256: String)

    @Volatile private var digest: Digest? = null

    fun describe(): AndroidApp? {
        if (!file.isFile) return null
        val (code, name) = version()
        return AndroidApp(
            URL, file.length(), Instant.ofEpochMilli(file.lastModified()).atOffset(ZoneOffset.UTC).toString(),
            versionCode = code, versionName = name, sha256 = sha256(),
        )
    }

    private fun version(): Pair<Int?, String?> {
        val metadata = File(file.parentFile, "output-metadata.json").takeIf { it.isFile } ?: return null to null
        val root = runCatching { json.parseToJsonElement(metadata.readText()) as? JsonObject }.getOrNull() ?: return null to null
        val elements = (root["elements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val element = elements.firstOrNull { (it["outputFile"] as? JsonPrimitive)?.contentOrNull == file.name } ?: elements.singleOrNull()
        return (element?.get("versionCode") as? JsonPrimitive)?.intOrNull to (element?.get("versionName") as? JsonPrimitive)?.contentOrNull
    }

    /** Hashed again only when the file changes. */
    private fun sha256(): String {
        val length = file.length()
        val modified = file.lastModified()
        digest?.takeIf { it.length == length && it.modified == modified }?.let { return it.sha256 }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(sha.digest()).also { digest = Digest(length, modified, it) }
    }

    companion object {
        const val URL = "/download/llm-manager.apk"
        const val MEDIA_TYPE = "application/vnd.android.package-archive"
        private const val BUFFER_BYTES = 64 * 1024
        private val json = Json { ignoreUnknownKeys = true }
    }
}
