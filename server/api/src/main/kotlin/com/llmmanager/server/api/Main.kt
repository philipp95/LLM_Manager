package com.llmmanager.server.api

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
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
    private val tokenFile by option("--token-file", help = "Access token for launching sessions; created if missing")
        .default(File(System.getProperty("user.home"), ".config/llm-manager/api-token").path)

    override fun run() {
        val address = BindAddress.resolve(host)
        val sessions = TmuxSessions()
        val scanner = ProjectScanner(projects.filter { it.isNotBlank() }, sessions = sessions)
        val codexPath = codex ?: CodexLauncher.findCodex()
        val launcher = CodexLauncher(scanner, sessions, CodexLauncher.Settings(File(codexWorktrees), codexPath))
        val token = ApiToken.loadOrCreate(File(tokenFile))
        val schedule = schedule(scanner, sessions, codexPath)
        val log = LoggerFactory.getLogger(ApiServer::class.java)
        log.info("Dashboard on http://{}:{}/ scanning {}", address.hostAddress, port, projects)
        log.info("Codex: {}. Launch token in {}. State in {}", codexPath ?: "not found, launching disabled", tokenFile, stateDir)
        embeddedServer(Netty, port = port, host = address.hostAddress) {
            dashboardModule(scanner, launcher, token, ApkFile(File(apk)), schedule)
        }.start(wait = true)
    }

    /** Scheduling: persistent jobs and runs, ticking in the background for the server's lifetime. */
    private fun schedule(scanner: ProjectScanner, sessions: SessionHost, codexPath: String?): ScheduleApi {
        val state = File(stateDir)
        val store = StateStore(state)
        val usage = UsageReader(File(System.getProperty("user.home")), state)
        // Gemini's command allowlist mirrors Claude's; written fresh at every start.
        val policy = File(state, "gemini-policy.toml").apply { parentFile.mkdirs(); writeText(AgentCommands.geminiPolicy()) }
        val commands = AgentCommands(
            claude ?: CodexLauncher.findBinary("claude"), codexPath,
            gemini = gemini ?: CodexLauncher.findBinary("gemini"), geminiPolicyFile = policy.path,
        )
        val workspace = Workspace(scanner, sessions, commands)
        val engine = RunEngine(store, workspace, File(runWorktrees))
        val scheduler = Scheduler(store, engine, sessions, limitResetHint = usage::resetHint)
        scheduler.launch(CoroutineScope(SupervisorJob()))
        val credentials = ForgeCredentials(File(tokenFile).absoluteFile.parentFile, CodexLauncher.findBinary("gh"))
        val branches = BranchService(store, BranchStore(state), workspace, ForgeClient(credentials), credentials)
        return ScheduleApi(JobService(store, scheduler, workspace, SessionCatalog()), store, usage, branches)
    }
}
