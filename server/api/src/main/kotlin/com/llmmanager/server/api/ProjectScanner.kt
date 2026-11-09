package com.llmmanager.server.api

import java.io.File
import java.security.MessageDigest
import java.time.Clock

/**
 * Discovers repositories under the configured [sources] and reports their worktrees.
 *
 * A source is a directory, or a directory path ending in a slash and an asterisk,
 * meaning "each child of it".
 * Sources that do not exist or are not git repositories are skipped, so a configured
 * folder that has not been created yet is not an error.
 */
class ProjectScanner(
    private val sources: List<String>,
    private val git: Git = Git(),
    private val sessions: SessionHost = SessionHost.None,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** One consistent view: projects plus analytics computed over the same day window. */
    data class Snapshot(val projects: List<Project>, val analytics: Analytics)

    /** A project together with the git directory that git commands for it must run in. */
    data class Located(val commonDir: File, val project: Project)

    /** Projects ordered by most recent commit across their worktrees. */
    fun scan(): List<Project> = snapshot().projects

    fun snapshot(): Snapshot {
        val window = ActivityWindow(clock)
        val projects = discover(window).map { it.project }
        return Snapshot(projects, Analytics.of(window, projects))
    }

    /** Resolves a client-supplied [id] against this scan only; clients never name paths. */
    fun locate(id: String): Located? = discover(ActivityWindow(clock)).firstOrNull { it.project.id == id }

    private fun discover(window: ActivityWindow): List<Located> {
        val running = sessions.list().orEmpty()
        return candidates()
            .mapNotNull { dir -> git.run(dir, "rev-parse", "--path-format=absolute", "--git-common-dir")?.trim()?.let(::File) }
            .map { it.canonicalFile }
            // A linked worktree resolves to its repository's common dir, so a repo is listed once.
            .distinct()
            .mapNotNull { dir -> describe(dir, running, window)?.let { Located(dir, it) } }
            .sortedWith(
                compareByDescending<Located> { located -> located.project.worktrees.maxOfOrNull { it.lastCommitAt.orEmpty() }.orEmpty() }
                    .thenBy { it.project.name.lowercase() },
            )
    }

    private fun candidates(): List<File> = sources.flatMap { source ->
        val wildcard = source.endsWith("/*") || source.endsWith("\\*")
        val dir = File(expandHome(if (wildcard) source.dropLast(2) else source))
        when {
            !dir.isDirectory -> emptyList()
            wildcard -> dir.listFiles { child -> child.isDirectory }.orEmpty().sortedBy { it.name }
            else -> listOf(dir)
        }
    }

    private fun describe(commonDir: File, running: Set<String>, window: ActivityWindow): Project? {
        val bare = git.run(commonDir, "rev-parse", "--is-bare-repository")?.trim() == "true"
        val worktrees = git.run(commonDir, "worktree", "list", "--porcelain")?.let { parseWorktrees(commonDir, it, running) } ?: return null
        val origin = git.run(commonDir, "config", "--get", "remote.origin.url")?.trim()?.takeIf { it.isNotEmpty() }?.let(RemoteUrl::of)
        val path = if (bare) commonDir else worktrees.firstOrNull { it.main }?.let { File(it.path) } ?: commonDir.parentFile
        return Project(
            id = projectId(commonDir),
            name = origin?.gitHubSlug ?: fallbackName(path, origin, bare),
            kind = if (bare) Project.Kind.MIRROR else Project.Kind.CHECKOUT,
            path = path.path,
            originUrl = origin?.display,
            gitHubUrl = origin?.gitHubWebUrl,
            defaultBranch = defaultBranch(commonDir, bare),
            worktrees = worktrees.sortedWith(
                compareByDescending<Worktree> { it.main }.thenByDescending { it.lastCommitAt.orEmpty() },
            ),
            // Not `--since`: git stops walking at the first older commit, which drops
            // newer ones behind it when dates are skewed. Filter by day ourselves instead.
            commitsPerDay = window.count(
                git.run(commonDir, "log", "--all", "--max-count=$MAX_COMMITS_SCANNED", "--format=%cI")?.lines()?.filter { it.isNotBlank() }.orEmpty(),
            ),
        )
    }

    private fun defaultBranch(commonDir: File, bare: Boolean): String? {
        // A bare clone's HEAD is the remote's default branch at clone time.
        if (bare) return git.run(commonDir, "symbolic-ref", "--short", "HEAD")?.trim()
        git.run(commonDir, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it.removePrefix("origin/") }
        // Without origin/HEAD, fall back to the conventional names that actually exist.
        return CONVENTIONAL_DEFAULTS.firstOrNull { name ->
            listOf("refs/remotes/origin/$name", "refs/heads/$name").any { git.run(commonDir, "rev-parse", "--verify", "--quiet", it) != null }
        }
    }

    private fun parseWorktrees(commonDir: File, porcelain: String, running: Set<String>): List<Worktree> = porcelain
        .split(Regex("\n\n+"))
        .map { block -> block.lines().filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }
        // The first entry of `git worktree list` is the main worktree (the bare dir for a mirror).
        .mapIndexedNotNull { index, lines ->
            val fields = lines.associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
            val path = fields["worktree"]
            if (path == null || "bare" in fields) return@mapIndexedNotNull null
            val head = fields["HEAD"]?.takeUnless { it.all { c -> c == '0' } }
            val branch = fields["branch"]?.removePrefix("refs/heads/")
            val directory = File(path)
            val present = directory.isDirectory && "prunable" !in fields
            Worktree(
                path = path,
                branch = branch,
                head = head,
                // Commits live in the shared object store, so this works even for a missing worktree.
                lastCommitAt = head?.let { git.run(commonDir, "log", "-1", "--format=%cI", it) }?.trim()?.takeIf { it.isNotEmpty() },
                changedFiles = if (present) git.run(directory, "status", "--porcelain")?.lines()?.count { it.isNotBlank() } else null,
                agentRun = branch?.startsWith(AGENT_BRANCH_PREFIX) == true,
                scheduledRun = branch?.startsWith(RunEngine.RUN_BRANCH_PREFIX + "/") == true,
                main = index == 0,
                missing = !present,
                codex = branch?.let { CodexLauncher.sessionFor(it, running) },
                startedAt = branch?.let { RunStart.of(it, directory.takeIf { present }) },
            )
        }

    private fun fallbackName(path: File, origin: RemoteUrl?, bare: Boolean): String {
        val fromOrigin = origin?.display?.trimEnd('/')?.substringAfterLast('/')?.substringAfterLast(':')?.removeSuffix(".git")
        return if (bare && !fromOrigin.isNullOrEmpty()) fromOrigin else path.name.removeSuffix(".git")
    }

    private fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path

    companion object {
        const val AGENT_BRANCH_PREFIX = "agent/"
        private const val ID_HEX_CHARS = 16
        private const val MAX_COMMITS_SCANNED = 5000

        fun projectId(commonDir: File): String =
            MessageDigest.getInstance("SHA-256").digest(commonDir.canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(ID_HEX_CHARS)
        private val CONVENTIONAL_DEFAULTS = listOf("main", "master", "trunk", "develop")

        /** The runner's mirrors, this repository, and any checkouts under `~/repos`. */
        val DEFAULT_SOURCES = listOf("~/agent-work/mirrors/*", "~/LLM_Manager", "~/repos/*")
    }
}
