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
    private val tokenFile by option("--token-file", help = "Access token for launching sessions; created if missing")
        .default(File(System.getProperty("user.home"), ".config/llm-manager/api-token").path)

    override fun run() {
        val address = BindAddress.resolve(host)
        val sessions = TmuxSessions()
        val scanner = ProjectScanner(projects.filter { it.isNotBlank() }, sessions = sessions)
        val codexPath = codex ?: CodexLauncher.findCodex()
        val launcher = CodexLauncher(scanner, sessions, CodexLauncher.Settings(File(codexWorktrees), codexPath))
        val token = ApiToken.loadOrCreate(File(tokenFile))
        val log = LoggerFactory.getLogger(ApiServer::class.java)
        log.info("Dashboard on http://{}:{}/ scanning {}", address.hostAddress, port, projects)
        log.info("Codex: {}. Launch token in {}", codexPath ?: "not found, launching disabled", tokenFile)
        embeddedServer(Netty, port = port, host = address.hostAddress) { dashboardModule(scanner, launcher, token, ApkFile(File(apk))) }.start(wait = true)
    }
}
