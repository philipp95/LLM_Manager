package com.llmmanager.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.llmmanager.android.R
import com.llmmanager.android.data.Project
import com.llmmanager.android.data.ProjectsResponse
import com.llmmanager.android.ui.DashboardViewModel.Connection
import com.llmmanager.android.ui.DashboardViewModel.Event
import com.llmmanager.android.update.UpdateEvents
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

private const val REFRESH_INTERVAL_MS = 30_000L
private val TABS = listOf("Repos", "Schedule", "Usage")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel, scheduleViewModel: ScheduleViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduleState by scheduleViewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The repository whose screen is open on the Repos tab.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openId?.takeIf { tab == 0 }?.let { id -> state.data?.projects?.firstOrNull { it.id == id } }
    val refreshTab: () -> Unit = { refresh(tab, openId != null, viewModel, scheduleViewModel) }
    val snackbar = remember { SnackbarHostState() }
    val dialogs = remember { DialogState() }
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }

    BackHandler(enabled = openId != null) { openId = null }
    Polling(tab, openId, refreshTab)
    Events(viewModel, scheduleViewModel, snackbar, dialogs)

    Scaffold(
        containerColor = Palette.harbor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopBar(
                title = open?.name?.substringAfterLast('/') ?: "LLM Manager", status = statusLine(state.connection, state.data),
                hasToken = state.hasToken, tab = tab, onBack = open?.let { { openId = null } },
                onSettings = { dialogs.settings = true }, onRefresh = refreshTab, onTab = { tab = it },
            )
        },
    ) { padding ->
        val refreshing = if (tab == 0) state.refreshing else scheduleState.loading
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refreshTab, modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == 1 -> ScheduleScreen(
                    scheduleState, state.data?.projects.orEmpty().map { it.id to it.name }, scheduleViewModel,
                    onNeedToken = { dialogs.settings = true }, onCopy = copy,
                )
                tab == 2 -> UsageScreen(scheduleState.usage, scheduleState.usageError, state.data?.analytics)
                open != null -> RepoDetail(
                    open, scheduleState.schedule, state.data?.codexAvailable == true, state.launching == open.id, scheduleState.submitting,
                    repoActions(open, viewModel, scheduleViewModel, dialogs, copy),
                )
                else -> Overview(
                    state, onRetry = viewModel::refresh, onSettings = { dialogs.settings = true }, onOpen = { openId = it.id },
                    onUpdate = viewModel::update,
                )
            }
        }
    }
    DialogHost(dialogs, state, viewModel, scheduleViewModel, copy)
}

/** Polls what is on screen while the app is in the foreground; stops in the background to spare the battery. */
@Composable
private fun Polling(tab: Int, openId: String?, refresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(lifecycleOwner, tab, openId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                current()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
}

@Composable
private fun Events(viewModel: DashboardViewModel, scheduleViewModel: ScheduleViewModel, snackbar: SnackbarHostState, dialogs: DialogState) {
    val context = LocalContext.current
    LaunchedEffect(scheduleViewModel) { scheduleViewModel.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { UpdateEvents.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is Event.Message -> snackbar.showSnackbar(event.text)
                is Event.Started -> dialogs.started = event
                Event.NeedToken -> {
                    dialogs.settings = true
                    snackbar.showSnackbar("Enter the server's access token first")
                }
                is Event.AllowInstalls -> {
                    context.startActivity(event.settings)
                    snackbar.showSnackbar("Allow LLM Manager to install apps, then tap Update again")
                }
            }
        }
    }
}

/** The repository screen's actions, routed to the view models. */
private fun repoActions(
    project: Project,
    viewModel: DashboardViewModel,
    scheduleViewModel: ScheduleViewModel,
    dialogs: DialogState,
    copy: (String) -> Unit,
) = RepoActions(
    chat = ChatActions(
        onSend = { request, onSent ->
            scheduleViewModel.create(request, message = "${agentLabel(request.agent)} is on it") {
                onSent()
                viewModel.refresh()
            }
        },
        onLog = { scheduleViewModel.openLog(it.id) },
        onCancel = { dialogs.cancelling = it },
        onCopy = copy,
    ),
    onStartCodex = { dialogs.confirming = project },
    onPublish = { branch, action -> viewModel.publish(project, branch, action) },
)

@Composable
private fun DialogHost(
    dialogs: DialogState,
    state: DashboardViewModel.UiState,
    viewModel: DashboardViewModel,
    scheduleViewModel: ScheduleViewModel,
    copy: (String) -> Unit,
) {
    val scheduleState by scheduleViewModel.state.collectAsStateWithLifecycle()
    if (dialogs.settings) {
        SettingsDialog(
            serverUrl = state.serverUrl.orEmpty(), hasToken = state.hasToken, pairedAs = state.pairedAs,
            onSave = viewModel::saveSettings, onForgetToken = viewModel::forgetToken, onDismiss = { dialogs.settings = false },
            update = UpdateState(state.data?.androidApp, state.updating, viewModel::update),
        )
    }
    dialogs.confirming?.let { project ->
        ConfirmStartDialog(
            project.name,
            onConfirm = {
                dialogs.confirming = null
                viewModel.startCodex(project)
            },
            onDismiss = { dialogs.confirming = null },
        )
    }
    dialogs.cancelling?.let { run ->
        AlertDialog(
            onDismissRequest = { dialogs.cancelling = null },
            text = { Text("Cancel this run? Its worktree and branch are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    dialogs.cancelling = null
                    scheduleViewModel.cancel(run.id)
                }) { Text("Cancel run") }
            },
            dismissButton = { TextButton(onClick = { dialogs.cancelling = null }) { Text("Keep running") } },
        )
    }
    dialogs.started?.let { event -> StartedDialog(event, onCopy = copy, onDismiss = { dialogs.started = null }) }
    scheduleState.log?.let { LogDialog(it, onDismiss = scheduleViewModel::closeLog) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    status: String,
    hasToken: Boolean,
    tab: Int,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTab: (Int) -> Unit,
) {
    Column(Modifier.background(Palette.harbor)) {
        TopAppBar(
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All repositories") }
                } else {
                    Image(
                        painterResource(R.drawable.logo_mark), contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(34.dp),
                    )
                }
            },
            title = {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, color = Palette.onHarbor2, fontSize = 12.sp)
                }
            },
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Lock, contentDescription = "Server and access token", tint = if (hasToken) Palette.ok else Palette.onHarbor)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Palette.harbor,
                titleContentColor = Palette.onHarbor,
                navigationIconContentColor = Palette.onHarbor,
                actionIconContentColor = Palette.onHarbor,
            ),
        )
        TabRow(
            selectedTabIndex = tab, containerColor = Palette.harbor, contentColor = Palette.onHarbor,
            indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = Palette.rust) },
            divider = {},
        ) {
            TABS.forEachIndexed { index, name ->
                Tab(
                    selected = tab == index, onClick = { onTab(index) },
                    selectedContentColor = Palette.onHarbor, unselectedContentColor = Palette.onHarbor2,
                    text = { Text(name, fontWeight = FontWeight.SemiBold) },
                )
            }
        }
    }
}

/** Refreshes what is on screen: the repositories (and, on a repository, its runs), the schedule, or usage. */
private fun refresh(tab: Int, repository: Boolean, overview: DashboardViewModel, schedule: ScheduleViewModel) {
    when (tab) {
        1 -> schedule.refreshSchedule()
        2 -> {
            schedule.refreshUsage()
            overview.refresh()
        }
        else -> {
            overview.refresh()
            if (repository) schedule.refreshSchedule()
        }
    }
}

private fun statusLine(connection: Connection, data: ProjectsResponse?): String = when (connection) {
    Connection.Loading -> "Connecting…"
    Connection.NotConfigured -> "Not configured"
    is Connection.NoRoute -> "Offline"
    is Connection.NeedsToken -> "Locked"
    is Connection.Failed -> "Error"
    Connection.Online -> data?.generatedAt?.let { updated(it) } ?: "Online"
}

private fun updated(iso: String): String = try {
    "Updated " + OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: DateTimeParseException) {
    "Online"
}
