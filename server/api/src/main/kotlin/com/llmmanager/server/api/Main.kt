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

    override fun run() {
        val address = BindAddress.resolve(host)
        val scanner = ProjectScanner(projects.filter { it.isNotBlank() })
        LoggerFactory.getLogger(ApiServer::class.java)
            .info("Read-only dashboard on http://{}:{}/ scanning {}", address.hostAddress, port, projects)
        embeddedServer(Netty, port = port, host = address.hostAddress) { dashboardModule(scanner) }.start(wait = true)
    }
}
