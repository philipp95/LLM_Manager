package com.llmmanager.server.api

import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** The Android app's APK, offered for download when it has been built. */
class ApkFile(val file: File) {
    fun describe(): AndroidApp? = file.takeIf { it.isFile }?.let {
        AndroidApp(URL, it.length(), Instant.ofEpochMilli(it.lastModified()).atOffset(ZoneOffset.UTC).toString())
    }

    companion object {
        const val URL = "/download/llm-manager.apk"
        const val MEDIA_TYPE = "application/vnd.android.package-archive"
    }
}
