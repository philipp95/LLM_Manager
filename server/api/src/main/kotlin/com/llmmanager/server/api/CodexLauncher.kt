package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import org.slf4j.LoggerFactory

/**
 * Starts an interactive Codex session for a project, in a fresh worktree on its own
 * `codex/<id>` branch, inside a detached tmux session the user attaches to over SSH.
 *
 * Codex never touches an existing checkout: a person's uncommitted work and the
 * runner's agent worktrees stay as they are. The sandbox and approval policy are
 * pinned on the command line so a button press cannot start Codex with full access.
 */
class CodexLauncher(
    private val scanner: ProjectScanner,
    private val sessions: SessionHost,
    private val settings: Settings,
    private val git: Git = Git(),
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Settings(
        /** Parent of the worktrees Codex sessions run in. */
        val worktreeRoot: File,
        /** Codex CLI executable; null disables launching. */
        val codexCommand: String?,
        /** Upper bound on concurrent `codex-*` tmux sessions. */
        val maxRunning: Int = 4,
        /** Where the per-session start scripts are written. */
        val scriptDir: File = File(System.getProperty("java.io.tmpdir"), "llm-manager-sessions"),
        val sandbox: AgentSandbox = AgentSandbox.disabled(),
    )

    sealed interface Result {
        data class Started(val session: CodexSession, val branch: String, val path: String) : Result
        data object UnknownProject : Result
        data object CodexUnavailable : Result
        data object TooManyRunning : Result
        data class Failed(val reason: String) : Result
    }

    val available: Boolean get() = settings.codexCommand != null

    @Synchronized
    fun launch(projectId: String): Result {
        val command = settings.codexCommand
        val located = scanner.locate(projectId)
        val running = sessions.list()
        return when {
            command == null -> Result.CodexUnavailable
            located == null -> Result.UnknownProject
            running == null -> Result.Failed("tmux is not available")
            running.count { it.startsWith(SESSION_PREFIX) } >= settings.maxRunning -> Result.TooManyRunning
            else -> start(located, command)
        }
    }

    private fun start(located: Located, command: String): Result {
        settings.sandbox.refusal(Agent.CODEX)?.let { return Result.Failed(it) }
        val worktrees = Worktrees(git, settings.worktreeRoot, clock)
        return when (val made = worktrees.create(located, BRANCH_PREFIX.trimEnd('/'))) {
            is Worktrees.Result.Failed -> Result.Failed(made.reason)
            is Worktrees.Result.Ok -> open(located, made.worktree, worktrees, command)
        }
    }

    /** Starts the tmux session in a fresh worktree; removes the worktree again if that fails. */
    private fun open(located: Located, created: Worktrees.Created, worktrees: Worktrees, command: String): Result {
        val name = checkNotNull(sessionNameFor(created.branch))
        // Same environment rules (and sandbox, when available) as unattended runs.
        val wrapped = settings.sandbox.wrap(Agent.CODEX, created.path, listOf(command) + CODEX_ARGS, interactive = true)
        val script = File(SecureFiles.privateDir(settings.scriptDir), "$name.sh")
            .apply { writeText(RunEngine.interactive(created.path, wrapped, Agent.CODEX)) }
        if (!sessions.start(name, created.path, listOf("bash", script.path))) {
            worktrees.discard(located, created)
            return Result.Failed("tmux could not start the session")
        }
        log.info("Started Codex session {} for {} in {}", name, located.project.name, created.path)
        return Result.Started(CodexSession(name, true, "tmux attach -t $name"), created.branch, created.path.path)
    }

    companion object {
        const val BRANCH_PREFIX = "codex/"
        const val SESSION_PREFIX = "codex-"
        private val CODEX_ARGS = listOf("--sandbox", "workspace-write", "--ask-for-approval", "on-request")
        private val log = LoggerFactory.getLogger(CodexLauncher::class.java)

        /** `codex/<id>` maps to tmux session `codex-<id>`; anything else has no session. */
        fun sessionNameFor(branch: String): String? =
            branch.takeIf { it.startsWith(BRANCH_PREFIX) }
                ?.let { SESSION_PREFIX + it.removePrefix(BRANCH_PREFIX) }
                ?.takeIf(SessionNames::isValid)

        /** The session for a `codex/<id>` worktree, live when tmux lists it in [running]. */
        fun sessionFor(branch: String, running: Set<String>): CodexSession? =
            sessionNameFor(branch)?.let { CodexSession(it, it in running, "tmux attach -t $it") }

        /** The codex binary on PATH, else the npm user-prefix location; null when absent. */
        fun findCodex(): String? = findBinary("codex")

        /** [name] on PATH, else in ~/.local/bin; null when absent. */
        fun findBinary(name: String): String? {
            val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, name) }.firstOrNull { it.canExecute() }
            val userLocal = File(System.getProperty("user.home"), ".local/bin/$name").takeIf { it.canExecute() }
            return (onPath ?: userLocal)?.absolutePath
        }
    }
}

private typealias Located = ProjectScanner.Located
