package com.llmmanager.server.spike

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.llmmanager.protocol.ProjectId
import com.llmmanager.protocol.RunId
import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.PermissionMode
import com.llmmanager.server.domain.RunSpec
import com.llmmanager.server.runner.AgentHarness
import com.llmmanager.server.runner.ClaudeCodeHarness
import com.llmmanager.server.runner.HarnessEvent
import com.llmmanager.server.runner.WorktreeManager
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * M0 — the harness spike.
 *
 * Proves the riskiest unknown in the product without any Android involved:
 * clone a repo, cut a worktree, run an agent in it, stream structured output,
 * and read real usage and cost back out.
 */
class Spike : CliktCommand(name = "spike") {

    private val repo by option("--repo", help = "Repository URL or local path").required()
    private val prompt by option("--prompt", help = "Task for the agent").required()
    private val workRoot by option("--work-root", help = "Where mirrors and worktrees live")
        .default(File(System.getProperty("java.io.tmpdir"), "llm-manager").absolutePath)
    private val model by option("--model", help = "Override the harness model")
    private val keepWorktree by option("--keep-worktree", help = "Leave the worktree for inspection")
        .default("false")
    private val timeoutSeconds by option("--timeout", help = "Run timeout in seconds")
        .default("1800")
    private val permissionMode by option(
        "--permission-mode",
        help = "acceptEdits (default) | bypass | plan",
    ).default("acceptEdits")

    override fun run() = runBlocking {
        val harness: AgentHarness = ClaudeCodeHarness()
        if (!harness.isAvailable()) {
            System.err.println("claude CLI not found on PATH — cannot run the spike.")
            exitProcess(2)
        }

        val runId = RunId("run-" + UUID.randomUUID().toString().take(8))
        val manager = WorktreeManager(File(workRoot))
        val repoKey = repo.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifEmpty { "repo" }

        echo("run      : ${runId.value}")
        echo("repo     : $repo")
        echo("work root: $workRoot")
        echo("")

        echo("[1/4] syncing mirror…")
        val mirror = manager.syncMirror(repo, repoKey)

        echo("[2/4] creating worktree…")
        val baseRef = manager.defaultBranch(mirror)
        val worktree = manager.create(mirror, runId.value, baseRef)
        echo("      ${worktree.path} on ${worktree.branch} (from $baseRef)")

        try {
            echo("[3/4] running agent…")
            echo("")
            val spec = RunSpec(
                runId = runId,
                projectId = ProjectId("spike"),
                harness = HarnessId.CLAUDE_CODE,
                prompt = prompt,
                worktreePath = worktree.path.absolutePath,
                model = model,
                permissionMode = when (permissionMode.lowercase()) {
                    "bypass", "bypasspermissions" -> PermissionMode.BYPASS
                    "plan" -> PermissionMode.PLAN
                    else -> PermissionMode.ACCEPT_EDITS
                },
                timeoutSeconds = timeoutSeconds.toLong(),
            )

            var finished: HarnessEvent.Finished? = null
            var usageEvents = 0

            harness.spawn(spec).collect { event ->
                when (event) {
                    is HarnessEvent.Usage -> {
                        usageEvents++
                        echo("  - usage ${event.model ?: "?"}  in=${event.usage.inputTokens} out=${event.usage.outputTokens}")
                    }
                    is HarnessEvent.Text -> echo("  - ${event.text}")
                    is HarnessEvent.ToolCall -> echo("  - tool ${event.name}")
                    is HarnessEvent.Stderr -> System.err.println("  ! ${event.line}")
                    is HarnessEvent.Unparsed -> echo("  ? ${event.raw.take(160)}")
                    is HarnessEvent.Finished -> finished = event
                }
            }

            echo("")
            echo("[4/4] result")
            val result = finished
            if (result == null) {
                System.err.println("no result line — the harness terminated without reporting.")
                exitProcess(1)
            }

            echo("  ok        : ${result.ok}")
            echo("  duration  : ${result.durationMs?.let { "${it}ms" } ?: "unknown"}")
            echo("  session   : ${result.sessionId ?: "unknown"}")
            echo("  usage evts: $usageEvents")
            result.usage.perModel.forEach { m ->
                echo("  model     : ${m.model}")
                echo("      tokens : in=${m.usage.inputTokens} out=${m.usage.outputTokens} " +
                     "cacheRead=${m.usage.cacheReadInputTokens} cacheWrite=${m.usage.cacheCreationInputTokens}")
                echo("      cost   : ${m.costUsd?.let { usd(it) } ?: "n/a"}")
            }
            echo("  TOTAL     : ${result.usage.totalCostUsd?.let { usd(it) } ?: "n/a"}")
            echo("")
            echo("  summary   : ${result.summary?.take(500) ?: "(none)"}")

            echo("")
            echo("  git status in worktree:")
            ProcessBuilder("git", "status", "--short")
                .directory(worktree.path).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
                .lines().filter { it.isNotBlank() }.take(20)
                .forEach { echo("      $it") }

            if (!result.ok) exitProcess(1)
        } finally {
            if (keepWorktree.toBoolean()) {
                echo("")
                echo("worktree kept at ${worktree.path}")
            } else {
                manager.remove(mirror, worktree)
            }
        }
    }
}

/** Always Locale.ROOT: a BE-format JVM renders 0.23 as "0,23". */
private fun usd(value: Double): String = "$" + String.format(Locale.ROOT, "%.6f", value)

fun main(args: Array<String>) = Spike().main(args)
