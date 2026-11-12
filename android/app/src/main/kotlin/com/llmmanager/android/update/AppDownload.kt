package com.llmmanager.android.update

import android.content.Intent
import android.net.Uri
import com.llmmanager.android.data.AndroidApp
import com.llmmanager.android.data.ServerAddress

/**
 * Updates go through the browser: the app opens the server's download link, the browser saves
 * the APK, and Android installs it when the user opens the download. The app never installs
 * packages itself: Google Play Protect blocks sideloaded apps that update themselves.
 */
object AppDownload {
    /** A browser intent for [app]'s APK on [server]; null when the link is not the server's own download. */
    fun intent(server: ServerAddress, app: AndroidApp): Intent? =
        app.url.takeIf { it.startsWith("/download/") }?.let { Intent(Intent.ACTION_VIEW, Uri.parse(server.baseUrl + it)) }
}
