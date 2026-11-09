package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Starts runs in detached tmux sessions (`run-<id>`) and collects their results.
 *
 * Each run executes a generated wrapper script that feeds the prompt on stdin, writes
 * the agent's event stream and stderr to the run directory, then records the exit
 * code. Because the agent lives in tmux, it survives server restarts and can be
 * attached to; the server only needs the files to learn the outcome.
 */
class RunEngine(
    private val store: StateStore,
    private val workspace: Workspace,
    private val worktreeRoot: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val git = workspace.git
    private val sessions = workspace.sessions
    private val commands = workspace.commands

    private val log = LoggerFactory.getLogger(RunEngine::class.java)

    /** QUEUED → RUNNING, or FAILED with a reason when it cannot start. */
    fun start(run: Run): Run {
        val prepared = prepare(run)
        return if (prepared.error != null || prepared.worktree == null) finish(prepared.copy(status = RunStatus.FAILED)) else launch(prepared)
    }

    private fun launch(prepared: Run): Run {
        val run = prepared
        val dir = store.runDir(run.id)
        File(dir, "prompt.txt").writeText(prepared.prompt)
        val sessionId = prepared.sessionId ?: if (prepared.agent == Agent.CLAUDE) UUID.randomUUID().toString() else null
        val command = when (prepared.stage) {
            Stage.WORK -> commands.work(prepared.agent, sessionId ?: "")
            Stage.REVIEW -> commands.review(prepared.agent, checkNotNull(prepared.resultCommit))
            Stage.FIX, Stage.RESUME -> commands.resume(prepared.agent, checkNotNull(sessionId) { "no session to resume" })
        }
        val script = File(dir, "run.sh").apply { writeText(wrapper(File(checkNotNull(prepared.worktree)), dir, command)) }
        val tmux = "run-${run.id}"
        if (!sessions.start(tmux, File(checkNotNull(prepared.worktree)), listOf("bash", script.path))) {
            return finish(prepared.copy(status = RunStatus.FAILED, error = "tmux could not start the run"))
        }
        log.info("Started {} {} run {} in {}", prepared.agent.label, prepared.stage, run.id, prepared.worktree)
        return prepared.copy(status = RunStatus.RUNNING, sessionId = sessionId, tmuxSession = tmux, startedAt = now())
            .also(store::putRun)
    }

    /** For a RUNNING run: the finished run once its exit code is written, or the run unchanged. */
    fun poll(run: Run, liveSessions: Set<String>): Run {
        val dir = store.runDir(run.id)
        val exit = File(dir, "exit").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (exit == null) {
            // Session gone without an exit code: killed, or the machine restarted.
            return if (run.tmuxSession in liveSessions) run
            else finish(run.copy(status = RunStatus.FAILED, error = "The run's tmux session ended without finishing"))
        }
        val outcome = RunOutput.outcome(run.agent, run.stage, File(dir, "events.jsonl"), File(dir, "stderr.log"), exit)
        val status = when {
            outcome.limited -> RunStatus.LIMITED
            exit == 0 && outcome.error == null -> RunStatus.SUCCEEDED
            else -> RunStatus.FAILED
        }
        val commit = if (status == RunStatus.SUCCEEDED && run.stage != Stage.REVIEW) commitChanges(run) else run.resultCommit
        return finish(
            run.copy(
                status = status,
                sessionId = outcome.sessionId ?: run.sessionId,
                summary = outcome.summary,
                error = outcome.error,
                resultCommit = commit,
                limitResetsAt = outcome.limitResetsAt?.toString(),
            ),
        )
    }

    fun cancel(run: Run): Run {
        run.tmuxSession?.let { sessions.stop(it) }
        return finish(run.copy(status = RunStatus.CANCELLED, error = "Cancelled"))
    }

    /** Resolves the worktree: a new one for fresh work, the given one otherwise. */
    private fun prepare(run: Run): Run {
        if (!commands.available(run.agent)) return run.copy(error = "${run.agent.label} CLI is not installed on the server")
        if (run.worktree != null) {
            return if (File(run.worktree).isDirectory) run else run.copy(error = "Worktree ${run.worktree} no longer exists")
        }
        val located = workspace.scanner.locate(run.projectId) ?: return run.copy(error = "Project no longer found")
        return when (val made = Worktrees(git, worktreeRoot, clock).create(located, RUN_BRANCH_PREFIX)) {
            is Worktrees.Result.Failed -> run.copy(error = made.reason)
            is Worktrees.Result.Ok -> run.copy(worktree = made.worktree.path.path, branch = made.worktree.branch, baseCommit = made.worktree.base)
        }
    }

    /** Commits whatever the agent changed, attributed to the agent, never to a person. Returns the commit or the previous one. */
    private fun commitChanges(run: Run): String? {
        val dir = File(checkNotNull(run.worktree))
        val dirty = git.run(dir, "status", "--porcelain")?.isNotBlank() == true
        if (!dirty) return run.resultCommit
        val title = run.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS) ?: "agent changes"
        val message = "${run.agent.label} (${run.stage.name.lowercase()}): $title\n\nRun ${run.id} started by LLM Manager.\n"
        val identity = listOf("-c", "user.name=${run.agent.label} via LLM Manager", "-c", "user.email=llm-manager@localhost")
        val committed = git.run(dir, "add", "-A") != null && git.run(dir, identity + listOf("commit", "--no-verify", "-q", "-m", message)) != null
        return if (committed) git.run(dir, "rev-parse", "HEAD")?.trim() else run.resultCommit
    }

    private fun finish(run: Run): Run = run.copy(finishedAt = now()).also(store::putRun)

    private fun now(): String = Instant.now(clock).toString()

    companion object {
        const val RUN_BRANCH_PREFIX = "run"
        private const val TITLE_CHARS = 60

        /** Single-quotes [value] for bash, so paths are never interpreted. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun wrapper(worktree: File, dir: File, command: List<String>): String {
            fun file(name: String) = quote(File(dir, name).path)
            return """
                |#!/usr/bin/env bash
                |# Generated by LLM Manager. The prompt is read from a file, never from the command line.
                |cd ${quote(worktree.path)} || { echo 97 > ${file("exit.tmp")}; mv ${file("exit.tmp")} ${file("exit")}; exit 97; }
                |${command.joinToString(" ", transform = ::quote)} < ${file("prompt.txt")} > ${file("events.jsonl")} 2> ${file("stderr.log")}
                |echo ${'$'}? > ${file("exit.tmp")} && mv ${file("exit.tmp")} ${file("exit")}
                |""".trimMargin()
        }
    }
}
