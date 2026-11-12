package com.llmmanager.server.api

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

fun main(args: Array<String>) = ApiServer().main(args)

class ApiServer : CliktCommand(name = "api") {
    private val projects by option(
        "--projects",
        envvar = "LLM_MANAGER_PROJECTS",
        help = "Project folders separated by '${File.pathSeparator}'. A trailing /* means each child folder. " +
            "Default: ${ProjectScanner.DEFAULT_SOURCES.joinToString(File.pathSeparator)}",
    ).split(File.pathSeparator).default(ProjectScanner.DEFAULT_SOURCES)
    private val host by option("--host", help = "IPv4 to bind; must be a Tailscale address or loopback. Default: `tailscale ip -4`")
    private val port by option("--port", help = "TCP port").int().restrictTo(1, 65535).default(8080)
    private val codex by option("--codex", help = "Codex CLI to launch. Default: `codex` on PATH or ~/.local/bin/codex")
    private val codexWorktrees by option("--codex-worktrees", help = "Where Codex worktrees are created")
        .default(File(System.getProperty("user.home"), "agent-work/codex").path)
    private val apk by option("--apk", help = "Android APK offered for download at ${ApkFile.URL}")
        .default(File(System.getProperty("user.home"), "LLM_Manager/android/app/build/outputs/apk/release/app-release.apk").path)
    private val gemini by option("--gemini", help = "Gemini CLI for scheduled runs. Default: `gemini` on PATH or ~/.local/bin/gemini")
    private val claude by option("--claude", help = "Claude Code CLI for scheduled runs. Default: `claude` on PATH or ~/.local/bin/claude")
    private val stateDir by option("--state-dir", help = "Schedules, run history and run logs")
        .default(File(System.getProperty("user.home"), ".local/share/llm-manager").path)
    private val runWorktrees by option("--run-worktrees", help = "Where scheduled runs get their worktrees")
        .default(File(System.getProperty("user.home"), "agent-work/runs").path)
    private val allowedHost by option(
        "--allowed-host",
        help = "Extra host name clients may use (repeatable); the bind address and MagicDNS names are always allowed",
    ).multiple()
    private val agentSandbox by option(
        "--agent-sandbox",
        help = "auto: sandbox agent runs with bubblewrap when it works; on: refuse runs that cannot be sandboxed; off: never",
    ).choice("auto" to AgentSandbox.Mode.AUTO, "on" to AgentSandbox.Mode.ON, "off" to AgentSandbox.Mode.OFF).default(AgentSandbox.Mode.AUTO)
    private val pairingMode by option(
        "--pairing",
        help = "on: devices signed in to this server's Tailscale account pair without the access token; off: the token only",
    ).choice("on", "off").default("on")
    private val tokenFile by option("--token-file", help = "Access token for launching sessions; created if missing")
        .default(File(System.getProperty("user.home"), ".config/llm-manager/api-token").path)

    override fun run() {
        val address = BindAddress.resolve(host)
        val state = SecureFiles.privateDir(File(stateDir), tighten = true)
        val token = ApiToken.loadOrCreate(File(tokenFile))
        val sessions = TmuxSessions()
        val scanner = ProjectScanner(projects.filter { it.isNotBlank() }, sessions = sessions)
        val codexPath = codex ?: CodexLauncher.findCodex()
        val policy = File(state, "gemini-policy.toml").apply { writeText(AgentCommands.geminiPolicy()) }
        val preferences = PreferencesStore(state)
        val commands = AgentCommands(
            claude ?: CodexLauncher.findBinary("claude"), codexPath,
            gemini = gemini ?: CodexLauncher.findBinary("gemini"), geminiPolicyFile = policy.path, model = preferences::model,
        )
        val sandbox = sandbox(state, policy, commands)
        val launcher = CodexLauncher(
            scanner, sessions,
            CodexLauncher.Settings(File(codexWorktrees), codexPath, scriptDir = File(state, "sessions"), sandbox = sandbox),
        )
        val workspace = Workspace(scanner, sessions, commands, sandbox = sandbox)
        val credentials = ForgeCredentials(File(tokenFile).absoluteFile.parentFile, CodexLauncher.findBinary("gh"))
        val accounts = AgentAccounts(commands::binary)
        val signIn = AgentSignIn(TmuxTerminals(), accounts, commands::binary, File(state, "sign-in"))
        val settings = SettingsApi(preferences, accounts, signIn, ForgeAccounts(credentials, preferences))
        val store = StateStore(state).also {
            it.pruneRunDirs()
            it.adoptIntoSessions()
        }
        val insights = InsightsService(InsightSources(credentials), agentForBranch = { projectId, branch -> agentOf(store, projectId, branch) })
        val schedule = schedule(store, workspace, credentials, settings, onPublished = { insights.invalidate(it.projectId) })
        val hosts = BindAddress.allowedHosts(address, port, BindAddress.tailscaleNames(), allowedHost)
        val pairing = if (pairingMode == "on") Pairing(CachedTailnet(TailscaleCli()), state) else null
        val log = LoggerFactory.getLogger(ApiServer::class.java)
        log.info("Dashboard on http://{}:{}/ scanning {}", address.hostAddress, port, projects)
        log.info("Codex: {}. Token in {}. State in {}. Answering for {}", codexPath ?: "not found", tokenFile, stateDir, hosts)
        log.info("Pairing: {}", if (pairing != null) "devices of this server's Tailscale account pair without the token" else "off")
        embeddedServer(Netty, port = port, host = address.hostAddress) {
            val services = DashboardServices(launcher, ApkFile(File(apk)), schedule, insights, pairing, settings = settings)
            dashboardModule(scanner, token, services, allowedHosts = hosts)
        }.start(wait = true)
    }

    /** Agent containment: bubblewrap with secrets hidden, when it is installed and passes its self-test. */
    private fun sandbox(state: File, policy: File, commands: AgentCommands): AgentSandbox {
        val home = File(System.getProperty("user.home"))
        val uid = runCatching { java.nio.file.Files.getAttribute(java.nio.file.Path.of("/proc/self"), "unix:uid") as Int }.getOrNull()?.toLong()
        val profile = { bwrap: String ->
            AgentSandbox.Profile(
                bwrap, home,
                hiddenServerPaths = listOf(state, File(tokenFile).absoluteFile.parentFile),
                visible = listOf(policy),
                uid = uid,
            )
        }
        val agents = Agent.entries.associateWith { commands.binary(it) }
        return AgentSandbox.detect(agentSandbox, profile, agents, File(state, "sandbox-check"))
    }

    /** Scheduling and publishing: persistent jobs and runs, ticking in the background for the server's lifetime. */
    private fun schedule(
        store: StateStore,
        workspace: Workspace,
        credentials: ForgeCredentials,
        settings: SettingsApi,
        onPublished: (Run) -> Unit,
    ): ScheduleApi {
        val usage = UsageReader(File(System.getProperty("user.home")), store.dir)
        val preferences = settings.preferences
        val engine = RunEngine(store, workspace, File(runWorktrees), committer = preferences::committer)
        val branches = BranchService(store, BranchStore(store.dir), workspace, ForgeClient(credentials), credentials)
        val publisher = { run: Run -> branches.publish(run).also { onPublished(run) } }
        val scheduler = Scheduler(store, engine, workspace.sessions, hooks = FollowUpHooks(usage::resetHint, publisher))
        scheduler.launch(CoroutineScope(SupervisorJob()))
        val choices = AgentChoices(preferences::enabled, { settings.accounts.status(it).signedIn }, { preferences.get().defaultAgent })
        val jobs = JobService(store, scheduler, workspace, SessionCatalog(), choices = choices)
        return ScheduleApi(jobs, store, usage, branches, ChatService(store, scheduler, workspace, choices))
    }

    /** The agent this server ran on [branch]: the first run's, or Codex for an interactive `codex/` session. */
    private fun agentOf(store: StateStore, projectId: String, branch: String): Agent? =
        store.runs().filter { it.projectId == projectId && it.branch == branch }.minByOrNull { it.createdAt }?.agent
            ?: Agent.CODEX.takeIf { branch.startsWith(CodexLauncher.BRANCH_PREFIX) }
}
