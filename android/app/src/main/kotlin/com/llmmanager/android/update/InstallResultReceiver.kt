package com.llmmanager.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Android's answer to an update: ask the user to confirm it, or say why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmation(intent)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateEvents.post("Updated")
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateEvents.post("Update cancelled")
            else -> UpdateEvents.post("Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"))
        }
    }

    /** The system's confirmation screen; this receiver is not exported, so only the installer session reaches it. */
    private fun confirmation(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }
}

/** Update results for whichever screen is showing. */
object UpdateEvents {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = events.asSharedFlow()

    fun post(message: String) {
        events.tryEmit(message)
    }
}
