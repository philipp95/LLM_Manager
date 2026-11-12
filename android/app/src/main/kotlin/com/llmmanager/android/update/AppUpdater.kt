package com.llmmanager.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.llmmanager.android.data.AndroidApp
import com.llmmanager.android.data.ServerAddress
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Updates the app from the server: downloads the APK it offers straight into an Android
 * package-installer session, checks its SHA-256, and lets Android ask the user to confirm.
 * Android itself refuses an APK that is not signed with this app's key.
 */
class AppUpdater(private val context: Context) {
    sealed interface Outcome {
        /** The user must first allow this app to install apps (once). */
        data object NeedsPermission : Outcome

        /** Android now shows its confirmation; the app restarts after the update. */
        data object Started : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** Opens the system setting that lets this app install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(server: ServerAddress, app: AndroidApp): Outcome = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) return@withContext Outcome.NeedsPermission
        if (!app.url.startsWith("/download/")) return@withContext Outcome.Failed("The server offered an unexpected download address")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (app.sizeBytes > 0) setSize(app.sizeBytes)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                val sha256 = download(server, app, session)
                if (app.sha256 != null && !sha256.equals(app.sha256, ignoreCase = true)) {
                    session.abandon()
                    return@withContext Outcome.Failed("The download was damaged; try again")
                }
                session.commit(resultIntent(sessionId).intentSender)
                Outcome.Started
            } catch (e: IOException) {
                session.abandon()
                Outcome.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Streams the APK into [session] and returns its SHA-256 in hex. */
    private fun download(server: ServerAddress, app: AndroidApp, session: PackageInstaller.Session): String {
        val connection = URL(server.baseUrl + app.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                session.openWrite(APK_NAME, 0, app.sizeBytes.takeIf { it > 0 } ?: -1).use { output ->
                    copy(input, output, digest)
                    session.fsync(output)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
    }

    /** Where Android reports the session's result; the receiver is not exported, so only this app's session reaches it. */
    private fun resultIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
        // Android fills in the result, so the PendingIntent must be mutable; it names our own receiver explicitly.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private companion object {
        const val APK_NAME = "llm-manager.apk"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_BYTES = 64 * 1024
    }
}
