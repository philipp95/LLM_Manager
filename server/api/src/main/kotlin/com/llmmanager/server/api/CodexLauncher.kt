package com.llmmanager.server.api

import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
        val id = "${slug(located.project.name)}-${STAMP.format(clock.instant())}-${suffix()}"
        val branch = BRANCH_PREFIX + id
        val name = checkNotNull(sessionNameFor(branch))
        val root = settings.worktreeRoot.absoluteFile
        val path = File(root, id)
        val base = baseRevision(located)
        val failure = when {
            base == null -> "could not determine a base commit"
            !(root.isDirectory || root.mkdirs()) -> "cannot create ${root.path}"
            git.run(located.commonDir, "worktree", "add", "-b", branch, path.path, base) == null -> "git worktree add failed"
            !sessions.start(name, path, listOf(command) + CODEX_ARGS) -> {
                // Leave nothing behind for a session that never started.
                git.run(located.commonDir, "worktree", "remove", "--force", path.path)
                git.run(located.commonDir, "branch", "-D", branch)
                "tmux could not start the session"
            }
            else -> null
        }
        if (failure != null) return Result.Failed(failure)
        log.info("Started Codex session {} for {} in {}", name, located.project.name, path)
        return Result.Started(CodexSession(name, true, "tmux attach -t $name"), branch, path.path)
    }

    /**
     * Checkouts start from what the person is working on (the main worktree's HEAD,
     * including unpushed commits); mirrors start from the remote default branch.
     */
    private fun baseRevision(located: Located): String? =
        if (located.project.kind == Project.Kind.CHECKOUT) {
            located.project.worktrees.firstOrNull { it.main }?.head
        } else {
            located.project.defaultBranch?.let { default ->
                listOf("refs/remotes/origin/$default", "refs/heads/$default").firstOrNull { ref ->
                    git.run(located.commonDir, "rev-parse", "--verify", "--quiet", ref) != null
                }
            }
        }

    private fun slug(name: String): String =
        name.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(SLUG_CHARS).ifEmpty { "project" }

    private fun suffix(): String = "%04x".format(random.nextInt(SUFFIX_RANGE))

    companion object {
        const val BRANCH_PREFIX = "codex/"
        const val SESSION_PREFIX = "codex-"
        private const val SLUG_CHARS = 32
        private const val SUFFIX_RANGE = 0x10000
        private val CODEX_ARGS = listOf("--sandbox", "workspace-write", "--ask-for-approval", "on-request")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val random = SecureRandom()
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
        fun findCodex(): String? {
            val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "codex") }.firstOrNull { it.canExecute() }
            val userLocal = File(System.getProperty("user.home"), ".local/bin/codex").takeIf { it.canExecute() }
            return (onPath ?: userLocal)?.absolutePath
        }
    }
}

private typealias Located = ProjectScanner.Located
