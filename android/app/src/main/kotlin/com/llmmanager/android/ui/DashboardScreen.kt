package com.llmmanager.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.llmmanager.android.data.Project
import com.llmmanager.android.ui.DashboardViewModel.Event
import kotlinx.coroutines.delay

private const val REFRESH_INTERVAL_MS = 30_000L
private const val WORKING_INTERVAL_MS = 5_000L
private const val SETTINGS_TAB = 3

@Composable
fun DashboardScreen(models: ViewModels) {
    val viewModel = models.dashboard
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chatState by models.chats.state.collectAsStateWithLifecycle()
    val nav = rememberSaveable(saver = Navigation.Saver) { Navigation() }
    val open = nav.openId?.takeIf { nav.tab == 0 }?.let { id -> state.data?.projects?.firstOrNull { it.id == id } }
    val refreshTab: () -> Unit = { refresh(nav.tab, nav.openId, models) }
    val snackbar = remember { SnackbarHostState() }
    val dialogs = remember { DialogState() }
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }
    // While an agent is working on the open repository, look again every few seconds.
    val working = nav.openId != null && chatState.chats.orEmpty().any { it.busy }

    BackHandler(enabled = nav.openId != null) { nav.openId = null }
    Polling(PollKey(nav.tab, nav.openId, working), refreshTab)
    Events(viewModel, models.schedule, snackbar, dialogs)
    SettingsEvents(models.settings, models.signIn, models.schedule, snackbar)
    LaunchedEffect(models.chats) { models.chats.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(models.repos) { models.repos.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = Palette.harbor,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title = open?.name?.substringAfterLast('/') ?: if (nav.serverOpen) "Server chat" else "LLM Manager"
            TopBar(
                title, status = statusLine(state.connection, state.data), hasToken = state.hasToken,
                onBack = { nav.openId = null }.takeIf { nav.openId != null && nav.tab == 0 },
                onSettings = { dialogs.settings = true }, onRefresh = refreshTab,
            )
        },
        bottomBar = { BottomBar(nav.tab) { nav.tab = it } },
    ) { padding ->
        Content(models, nav, dialogs, copy, Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize())
    }
    DialogHost(dialogs, state, viewModel, models.schedule, models.signIn, copy)
}

/** Where the person is: the tab, and the repository (or the Server chat) open on the Repos tab. */
class Navigation(tab: Int = 0, openId: String? = null) {
    var tab by mutableIntStateOf(tab)
    var openId by mutableStateOf(openId)
    val serverOpen: Boolean get() = openId == SERVER_ID && tab == 0

    companion object {
        val Saver = listSaver<Navigation, Any?>(save = { listOf(it.tab, it.openId) }, restore = { Navigation(it[0] as Int, it[1] as String?) })
    }
}

/** The tab on screen, pulled down to refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(models: ViewModels, nav: Navigation, dialogs: DialogState, copy: (String) -> Unit, modifier: Modifier) {
    val viewModel = models.dashboard
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheduleState by models.schedule.state.collectAsStateWithLifecycle()
    val settingsState by models.settings.state.collectAsStateWithLifecycle()
    val chatState by models.chats.state.collectAsStateWithLifecycle()
    val repoState by models.repos.state.collectAsStateWithLifecycle()
    val open = nav.openId?.let { id -> state.data?.projects?.firstOrNull { it.id == id } }
    val refreshing = when (nav.tab) {
        0 -> state.refreshing
        SETTINGS_TAB -> settingsState.loading
        else -> scheduleState.loading
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh(nav.tab, nav.openId, models) }, modifier = modifier) {
        when {
            nav.tab == 1 -> ScheduleScreen(
                scheduleState, state.data?.projects.orEmpty().map { it.id to it.name }, models.schedule,
                onNeedToken = { dialogs.settings = true }, onCopy = copy,
            )
            nav.tab == 2 -> UsageScreen(scheduleState.usage, scheduleState.usageError, state.data?.analytics)
            nav.tab == SETTINGS_TAB -> SettingsScreen(
                settingsState, settingsActions(models.settings, models.signIn),
                PhoneSettings(
                    state.serverUrl.orEmpty(), state.pairedAs, state.hasToken, UpdateState(state.data?.androidApp, viewModel::update),
                    onServer = { dialogs.settings = true },
                ),
                onRetry = models.settings::load,
            )
            nav.serverOpen -> ServerChatScreen(chatState, scheduleState.schedule, chatActions(models, dialogs, copy))
            open != null -> RepoDetail(
                open, scheduleState.schedule, chatState, state.data?.codexAvailable == true, state.launching == open.id,
                repoActions(open, models, dialogs, copy),
            )
            else -> Overview(
                state, repoState,
                HomeActions(
                    onRetry = viewModel::refresh, onSettings = { dialogs.settings = true }, onOpen = { nav.openId = it },
                    onUpdate = viewModel::update, onPrefer = models.repos::prefer,
                    onAdd = { url, started -> models.repos.add(url, started, onCloned = viewModel::refresh) },
                ),
            )
        }
    }
}

/** What decides how often the screen looks again: fast while an agent is [working]. */
private data class PollKey(val tab: Int, val openId: String?, val working: Boolean)

/** Polls what is on screen while the app is in the foreground; stops in the background to spare the battery. */
@Composable
private fun Polling(key: PollKey, refresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(lifecycleOwner, key) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                current()
                delay(if (key.working) WORKING_INTERVAL_MS else REFRESH_INTERVAL_MS)
            }
        }
    }
}

@Composable
private fun Events(viewModel: DashboardViewModel, scheduleViewModel: ScheduleViewModel, snackbar: SnackbarHostState, dialogs: DialogState) {
    val context = LocalContext.current
    LaunchedEffect(scheduleViewModel) { scheduleViewModel.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is Event.Message -> snackbar.showSnackbar(event.text)
                is Event.Started -> dialogs.started = event
                Event.NeedToken -> {
                    dialogs.settings = true
                    snackbar.showSnackbar("Enter the server's access token first")
                }
                is Event.OpenDownload -> {
                    val opened = runCatching { context.startActivity(event.intent) }.isSuccess
                    snackbar.showSnackbar(
                        if (opened) "Your browser downloads version ${event.version}; open the download to install it" else "No browser to download the update",
                    )
                }
            }
        }
    }
}

/** The repository screen's actions, routed to the view models. */
private fun repoActions(project: Project, models: ViewModels, dialogs: DialogState, copy: (String) -> Unit) = RepoActions(
    chat = chatActions(models, dialogs, copy),
    onStartCodex = { dialogs.confirming = project },
    onPublish = { branch, action -> models.dashboard.publish(project, branch, action) },
)

private fun chatActions(models: ViewModels, dialogs: DialogState, copy: (String) -> Unit) = ChatActions(
    onSend = { request, onSent ->
        models.chats.send(request) { sent ->
            onSent(sent)
            models.dashboard.refresh()
            models.schedule.refreshSchedule()
        }
    },
    onLog = { models.schedule.openLog(it.id) },
    onCancel = { dialogs.cancelling = it },
    onCopy = copy,
    onSignIn = { agent, then -> models.signIn.open(agent, then) },
    onBranch = { session, action -> models.chats.act(session, action) { models.dashboard.refresh() } },
)

@Composable
private fun DialogHost(
    dialogs: DialogState,
    state: DashboardViewModel.UiState,
    viewModel: DashboardViewModel,
    scheduleViewModel: ScheduleViewModel,
    signInViewModel: SignInViewModel,
    copy: (String) -> Unit,
) {
    val scheduleState by scheduleViewModel.state.collectAsStateWithLifecycle()
    val signIn by signInViewModel.state.collectAsStateWithLifecycle()
    SignInDialog(
        signIn,
        SignInActions(
            onSubmit = signInViewModel::submit, onApiKey = signInViewModel::useApiKey, onRestart = signInViewModel::restart,
            onCancel = signInViewModel::cancel, onClose = signInViewModel::close, onCopy = copy,
        ),
    )
    if (dialogs.settings) {
        SettingsDialog(
            serverUrl = state.serverUrl.orEmpty(), hasToken = state.hasToken, pairedAs = state.pairedAs,
            onSave = viewModel::saveSettings, onForgetToken = viewModel::forgetToken, onDismiss = { dialogs.settings = false },
            update = UpdateState(state.data?.androidApp, viewModel::update),
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

/** Refreshes what is on screen: the repositories (and, on a repository, its sessions), the schedule, usage, or settings. */
private fun refresh(tab: Int, openId: String?, models: ViewModels) {
    val overview = models.dashboard
    val schedule = models.schedule
    when (tab) {
        1 -> schedule.refreshSchedule()
        SETTINGS_TAB -> models.settings.load()
        2 -> {
            schedule.refreshUsage()
            overview.refresh()
        }
        else -> {
            overview.refresh()
            if (openId != null) {
                schedule.refreshSchedule()
                models.chats.load(openId)
            }
        }
    }
}

/** The Settings tab's changes, and the sign-in pop-up it opens. */
private fun settingsActions(settings: SettingsViewModel, signIn: SignInViewModel) = SettingsActions(
    onSaveProfile = settings::saveProfile,
    onSaveAgent = settings::saveAgent,
    onSignIn = { agent -> signIn.open(agent) },
    onSignOut = settings::signOut,
    onConnectForge = settings::connectForge,
    onDisconnectForge = settings::disconnectForge,
)
