package com.llmmanager.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.BuildConfig
import com.llmmanager.android.data.AndroidApp
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.ui.DashboardViewModel.Connection

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** The Repos tab: a grid of repository tiles, or what stands in the way of showing it. */
@Composable
fun Overview(
    state: DashboardViewModel.UiState,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (Project) -> Unit,
    onUpdate: (AndroidApp) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two tiles across on a phone, more on wider screens.
        val columns = maxOf(2, (maxWidth / 180.dp).toInt())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            connectionNotice(state.connection, onSettings = onSettings, onRetry = onRetry, context = context)
            state.data?.androidApp?.takeIf { it.newerThan(BuildConfig.VERSION_CODE) }?.let { app ->
                item(key = "update") { UpdateBanner(app, state.updating) { onUpdate(app) } }
            }
            state.data?.let { data -> repositories(data, columns, onOpen) }
        }
    }
}

private fun LazyListScope.repositories(data: ProjectsResponse, columns: Int, onOpen: (Project) -> Unit) {
    if (data.projects.isEmpty()) {
        item(key = "empty") { BackgroundNote("No git repositories found in the server's configured folders.") }
        return
    }
    repoGrid(data.projects, columns, onOpen)
}

private fun LazyListScope.connectionNotice(
    connection: Connection,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    context: Context,
) {
    val (title, body) = when (connection) {
        Connection.NotConfigured -> "Set the server address" to "Enter the dashboard's Tailscale address, e.g. http://100.101.102.103:8080."
        is Connection.NeedsToken -> "Access token needed" to
            "Phones signed in to the server's Tailscale account pair automatically. " +
            (connection.reason?.let { "$it. " } ?: "This one could not. ") +
            "Enter the server's access token instead (on the server: cat ~/.config/llm-manager/api-token)."
        is Connection.NoRoute -> "Can't reach the server" to "Is Tailscale connected on this phone? (${connection.detail})"
        is Connection.Failed -> "Something went wrong" to connection.message
        Connection.Loading, Connection.Online -> return
    }
    item(key = "notice") {
        Panel {
            Text(title, color = Palette.badInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(body, color = Palette.ink2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (connection) {
                    Connection.NotConfigured, is Connection.NeedsToken -> Button(onClick = onSettings) { Text("Settings") }
                    is Connection.NoRoute -> {
                        val tailscale = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
                        if (tailscale != null) Button(onClick = { context.startActivity(tailscale) }) { Text("Open Tailscale") }
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                    else -> OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
