package com.llmmanager.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.ui.DashboardViewModel.Connection
import com.llmmanager.android.ui.DashboardViewModel.Event
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

private const val REFRESH_INTERVAL_MS = 30_000L
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Project?>(null) }
    var started by remember { mutableStateOf<Event.Started?>(null) }
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }

    // Poll only while the screen is visible; stops in the background so it never drains the battery.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is Event.Message -> snackbar.showSnackbar(event.text)
                is Event.Started -> started = event
                Event.NeedToken -> {
                    showSettings = true
                    snackbar.showSnackbar("Enter the server's access token to start Codex")
                }
            }
        }
    }

    Scaffold(
        containerColor = Palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LLM Manager", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(statusLine(state.connection, state.data), color = Palette.muted, fontSize = 12.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Server and access token", tint = if (state.hasToken) Palette.ok else Palette.text)
                    }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.background,
                    titleContentColor = Palette.text,
                    actionIconContentColor = Palette.text,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                connectionNotice(state.connection, onSettings = { showSettings = true }, onRetry = viewModel::refresh, context = context)
                state.data?.let { data -> dashboard(data, state.launching, onStart = { confirming = it }, onCopy = copy) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            serverUrl = state.serverUrl.orEmpty(),
            hasToken = state.hasToken,
            onSave = viewModel::saveSettings,
            onForgetToken = viewModel::forgetToken,
            onDismiss = { showSettings = false },
        )
    }
    confirming?.let { project ->
        ConfirmStartDialog(project.name, onConfirm = { confirming = null; viewModel.startCodex(project) }, onDismiss = { confirming = null })
    }
    started?.let { event -> StartedDialog(event, onCopy = copy, onDismiss = { started = null }) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectionNotice(
    connection: Connection,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    context: Context,
) {
    val (title, body) = when (connection) {
        Connection.NotConfigured -> "Set the server address" to "Enter the dashboard's Tailscale address, e.g. http://100.101.102.103:8080."
        is Connection.NoRoute -> "Can't reach the server" to "Is Tailscale connected on this phone? (${connection.detail})"
        is Connection.Failed -> "Something went wrong" to connection.message
        Connection.Loading, Connection.Online -> return
    }
    item(key = "notice") {
        Panel {
            Text(title, color = Palette.bad, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(body, color = Palette.muted, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (connection) {
                    Connection.NotConfigured -> Button(onClick = onSettings) { Text("Settings") }
                    is Connection.NoRoute -> {
                        val tailscale = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
                        if (tailscale != null) {
                            Button(onClick = { context.startActivity(tailscale) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) {
                                Text("Open Tailscale")
                            }
                        }
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                    else -> OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dashboard(
    data: ProjectsResponse,
    launching: String?,
    onStart: (Project) -> Unit,
    onCopy: (String) -> Unit,
) {
    val worktrees = data.projects.flatMap { it.worktrees }
    item(key = "stats") {
        // Two by two: four across truncates the labels at phone widths.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(data.projects.size, "Projects", null, Modifier.weight(1f))
                StatTile(worktrees.count { it.codex?.running == true }, "Codex live", Palette.codex, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(worktrees.count { it.agentRun }, "Agent runs", Palette.agent, Modifier.weight(1f))
                StatTile(worktrees.count { (it.changedFiles ?: 0) > 0 }, "Uncommitted", Palette.warn, Modifier.weight(1f))
            }
        }
    }
    data.analytics?.let { analytics -> item(key = "analytics") { AnalyticsPanel(analytics) } }
    if (data.projects.isEmpty()) {
        item(key = "empty") { Muted("No git repositories found in the server's configured folders.") }
    }
    items(data.projects, key = { it.id }) { project ->
        ProjectCard(project, data.codexAvailable, launching == project.id, onStartCodex = { onStart(project) }, onCopy = onCopy)
    }
}

private fun statusLine(connection: Connection, data: ProjectsResponse?): String = when (connection) {
    Connection.Loading -> "Connecting…"
    Connection.NotConfigured -> "Not configured"
    is Connection.NoRoute -> "Offline"
    is Connection.Failed -> "Error"
    Connection.Online -> data?.generatedAt?.let { updated(it) } ?: "Online"
}

private fun updated(iso: String): String = try {
    "Updated " + OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: DateTimeParseException) {
    "Online"
}
