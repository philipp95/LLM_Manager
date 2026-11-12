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
    /** The person from Settings who commits agent work; null keeps the agent as committer too. */
    private val committer: () -> Profile? = { null },
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
        val refusal = workspace.sandbox.refusal(run.agent)
        val started = refusal == null && startSession(run)
        return when {
            refusal != null -> finish(run.copy(status = RunStatus.FAILED, error = refusal))
            !started -> finish(run.copy(status = RunStatus.FAILED, error = "tmux could not start the run"))
            else -> {
                log.info("Started {} {} run {} in {}", run.agent.label, run.stage, run.id, run.worktree)
                store.run(run.id) ?: run
            }
        }
    }

    /** Writes the prompt and run script, then starts the tmux session; records the run as RUNNING when it starts. */
    private fun startSession(run: Run): Boolean {
        val dir = store.runDir(run.id)
        File(dir, "prompt.txt").writeText(run.prompt)
        // New work: Claude and Gemini accept a chosen session id; Codex reports its own when it starts.
        // A resume or fix must name an existing session; a made-up id would "resume" nothing.
        val sessionId = run.sessionId ?: if (run.stage == Stage.WORK && run.agent != Agent.CODEX) UUID.randomUUID().toString() else null
        val command = when (run.stage) {
            Stage.WORK -> commands.work(run.agent, sessionId ?: "")
            Stage.REVIEW -> commands.review(run.agent, checkNotNull(run.resultCommit))
            Stage.FIX, Stage.RESUME -> commands.resume(run.agent, checkNotNull(sessionId) { "no session to resume" })
        }
        val worktree = File(checkNotNull(run.worktree))
        val wrapped = workspace.sandbox.wrap(run.agent, worktree, command, workspace.sandbox.scratchClaudeJson(run.agent, dir))
        val script = File(dir, "run.sh").apply { writeText(wrapper(worktree, dir, wrapped, run.agent)) }
        val tmux = "run-${run.id}"
        val started = sessions.start(tmux, worktree, listOf("bash", script.path))
        if (started) store.putRun(run.copy(status = RunStatus.RUNNING, sessionId = sessionId, tmuxSession = tmux, startedAt = now()))
        return started
    }

    /** For a RUNNING run: the finished run once its exit code is written, or the run unchanged. */
    fun poll(run: Run, liveSessions: Set<String>): Run {
        val dir = store.runDir(run.id)
        val exit = File(dir, "exit").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        return when {
            exit != null -> collect(run, dir, exit)
            run.tmuxSession in liveSessions -> run
            // Session gone without an exit code: killed, or the machine restarted.
            else -> finish(run.copy(status = RunStatus.FAILED, error = "The run's tmux session ended without finishing"))
        }
    }

    private fun collect(run: Run, dir: File, exit: Int): Run {
        val outcome = RunOutput.outcome(run.agent, run.stage, File(dir, "events.jsonl"), File(dir, "stderr.log"), exit)
        val status = when {
            outcome.limited -> RunStatus.LIMITED
            exit == 0 && outcome.error == null -> RunStatus.SUCCEEDED
            else -> RunStatus.FAILED
        }
        val finished = run.copy(
            status = status,
            sessionId = outcome.sessionId ?: run.sessionId,
            summary = outcome.summary,
            error = outcome.error,
            limitResetsAt = outcome.limitResetsAt?.toString(),
        )
        if (status != RunStatus.SUCCEEDED || run.stage == Stage.REVIEW) return finish(finished)
        // Committing runs git inside the worktree, so its link to the repository must be intact.
        val commonDir = workspace.scanner.locate(run.projectId)?.commonDir
        val intact = commonDir != null && WorktreeGuard.intact(File(checkNotNull(run.worktree)), commonDir)
        if (!intact) log.warn("Run {}: worktree .git link no longer points into its repository; not committing", run.id)
        return finish(
            if (intact) finished.copy(resultCommit = commitChanges(run))
            else finished.copy(status = RunStatus.FAILED, error = "The worktree's .git link was changed during the run; nothing was committed"),
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

    /**
     * Commits whatever the agent changed. The agent is always the author, never a person; the
     * person set up in Settings, when there is one, is the committer, so the commit is linked
     * to their account on the code host. Returns the commit or the previous one.
     */
    private fun commitChanges(run: Run): String? {
        val dir = File(checkNotNull(run.worktree))
        val dirty = git.run(dir, "status", "--porcelain", "--ignore-submodules=all")?.isNotBlank() == true
        if (!dirty) return run.resultCommit
        val title = run.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS) ?: "agent changes"
        val message = "${run.agent.label} (${run.stage.name.lowercase()}): $title\n\nRun ${run.id} started by LLM Manager.\n"
        val agent = "${run.agent.label} via LLM Manager"
        val person = committer()
        val identity = listOf("-c", "user.name=${person?.name ?: agent}", "-c", "user.email=${person?.email ?: AGENT_EMAIL}")
        val commit = listOf("commit", "--no-verify", "-q", "--author=$agent <$AGENT_EMAIL>", "-m", message)
        val committed = git.run(dir, "add", "-A") != null && git.run(dir, identity + commit) != null
        return if (committed) git.run(dir, "rev-parse", "HEAD")?.trim() else run.resultCommit
    }

    private fun finish(run: Run): Run {
        File(store.runDir(run.id), "claude.json").delete()
        return run.copy(finishedAt = now()).also(store::putRun)
    }

    private fun now(): String = Instant.now(clock).toString()

    companion object {
        const val RUN_BRANCH_PREFIX = "run"
        private const val TITLE_CHARS = 60
        private const val AGENT_EMAIL = "llm-manager@localhost"

        /** Single-quotes [value] for bash, so paths are never interpreted. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /**
         * The run script: prompt on stdin from a file, events and stderr to files, exit code
         * last. The agent starts with only [AgentEnvironment]'s variables for [agent].
         */
        fun wrapper(worktree: File, dir: File, command: List<String>, agent: Agent): String {
            fun file(name: String) = quote(File(dir, name).path)
            return """
                |#!/usr/bin/env bash
                |# Generated by LLM Manager. The prompt is read from a file, never from the command line.
                |cd ${quote(worktree.path)} || { echo 97 > ${file("exit.tmp")}; mv ${file("exit.tmp")} ${file("exit")}; exit 97; }
                |${AgentEnvironment.script(agent)}
                |env -i "${'$'}{keep[@]}" ${command.joinToString(" ", transform = ::quote)} < ${file("prompt.txt")} > ${file("events.jsonl")} 2> ${file("stderr.log")}
                |echo ${'$'}? > ${file("exit.tmp")} && mv ${file("exit.tmp")} ${file("exit")}
                |""".trimMargin()
        }

        /** An interactive session: same environment rules, attached to the tmux terminal. */
        fun interactive(worktree: File, command: List<String>, agent: Agent): String = """
            |#!/usr/bin/env bash
            |# Generated by LLM Manager for an interactive session.
            |cd ${quote(worktree.path)} || exit 97
            |${AgentEnvironment.script(agent)}
            |exec env -i "${'$'}{keep[@]}" ${command.joinToString(" ", transform = ::quote)}
            |""".trimMargin()
    }
}
