package com.llmmanager.server.spike

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
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
import kotlinx.coroutines.runBlocking

/**
 * M0 — the harness spike.
 *
 * Proves the riskiest unknown in the product without any Android involved:
 * clone a repo, cut a worktree, run an agent in it, stream structured output,
 * and read real usage and cost back out.
 */
class Spike(private val harness: AgentHarness = ClaudeCodeHarness()) : CliktCommand(name = "spike") {

    private val repo by option("--repo", help = "Repository URL or local path").required()
    private val prompt by option("--prompt", help = "Task for the agent").required()
    private val workRoot by option("--work-root", help = "Where mirrors and worktrees live")
        .default(File(System.getProperty("java.io.tmpdir"), "llm-manager").absolutePath)
    private val model by option("--model", help = "Override the harness model")
    private val keepWorktree by option("--keep-worktree", help = "Leave the worktree for inspection")
        .convert { it.toBooleanStrictOrNull() ?: fail("Expected true or false") }.default(false)
    private val timeoutSeconds by option("--timeout", help = "Run timeout in seconds")
        .long().restrictTo(1, 604800).default(1800)
    private val permissionMode by option(
        "--permission-mode",
        help = "acceptEdits (default) | bypass | plan",
    ).choice(
        "acceptEdits" to PermissionMode.ACCEPT_EDITS,
        "bypass" to PermissionMode.BYPASS,
        "bypassPermissions" to PermissionMode.BYPASS,
        "plan" to PermissionMode.PLAN,
    ).default(PermissionMode.ACCEPT_EDITS)

    override fun run() = runBlocking {
        if (!harness.isAvailable()) {
            System.err.println("claude CLI not found on PATH — cannot run the spike.")
            throw ProgramResult(2)
        }

        val runId = RunId("run-" + UUID.randomUUID())
        val manager = WorktreeManager(File(workRoot))

        echo("run      : ${runId.value}")
        echo("repo     : $repo")
        echo("work root: $workRoot")
        echo("")

        echo("[1/4] syncing mirror…")
        val mirror = manager.syncMirror(repo)

        echo("[2/4] creating worktree…")
        val baseRef = manager.defaultBranch(mirror)
        val worktree = manager.create(mirror, runId.value, baseRef)
        echo("      ${worktree.path} on ${worktree.branch} (from $baseRef)")

        try {
            val spec = RunSpec(
                runId = runId,
                projectId = ProjectId("spike"),
                harness = HarnessId.CLAUDE_CODE,
                prompt = prompt,
                worktreePath = worktree.path.absolutePath,
                model = model,
                permissionMode = permissionMode,
                timeoutSeconds = timeoutSeconds,
            )
            val result = execute(spec)
            echo("  git status in worktree:")
            manager.status(worktree).lines().filter { it.isNotBlank() }.take(20).forEach { echo("      $it") }
            if (!result.ok) throw ProgramResult(1)
        } finally {
            if (keepWorktree) {
                echo("worktree kept at ${worktree.path}")
            } else {
                manager.remove(mirror, worktree)
            }
        }
    }

    private suspend fun execute(spec: RunSpec): HarnessEvent.Finished {
        echo("[3/4] running agent…")
        var finished: HarnessEvent.Finished? = null
        var usageEvents = 0
        harness.spawn(spec).collect { event ->
            if (event is HarnessEvent.Finished) {
                finished = event
            } else {
                if (event is HarnessEvent.Usage) usageEvents++
                printEvent(event)
            }
        }
        val result = finished ?: run {
            System.err.println("no result line — the harness terminated without reporting.")
            throw ProgramResult(1)
        }
        printResult(result, usageEvents)
        return result
    }

    private fun printEvent(event: HarnessEvent) {
        when (event) {
            is HarnessEvent.Usage ->
                echo("  - usage ${event.model ?: "?"}  in=${event.usage.inputTokens} out=${event.usage.outputTokens}")
            is HarnessEvent.Text -> echo("  - ${event.text}")
            is HarnessEvent.ToolCall -> echo("  - tool ${event.name}")
            is HarnessEvent.Stderr -> System.err.println("  ! ${event.line}")
            is HarnessEvent.Unparsed -> echo("  ? ${event.raw.take(160)}")
            is HarnessEvent.Finished -> Unit
        }
    }

    private fun printResult(result: HarnessEvent.Finished, usageEvents: Int) {
        echo("[4/4] result")
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
    }
}

/** Always Locale.ROOT: a BE-format JVM renders 0.23 as "0,23". */
private fun usd(value: Double): String = "$" + String.format(Locale.ROOT, "%.6f", value)
