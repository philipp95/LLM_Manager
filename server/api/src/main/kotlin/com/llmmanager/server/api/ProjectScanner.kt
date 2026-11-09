package com.llmmanager.server.api

import java.io.File

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
    private val git: GitReader = GitReader(),
) {
    fun scan(): List<Project> = candidates()
        .mapNotNull { dir -> git.query(dir, "rev-parse", "--path-format=absolute", "--git-common-dir")?.trim()?.let(::File) }
        .map { it.canonicalFile }
        // A linked worktree resolves to its repository's common dir, so a repo is listed once.
        .distinct()
        .mapNotNull(::describe)
        .sortedBy { it.name.lowercase() }

    private fun candidates(): List<File> = sources.flatMap { source ->
        val wildcard = source.endsWith("/*") || source.endsWith("\\*")
        val dir = File(expandHome(if (wildcard) source.dropLast(2) else source))
        when {
            !dir.isDirectory -> emptyList()
            wildcard -> dir.listFiles { child -> child.isDirectory }.orEmpty().sortedBy { it.name }
            else -> listOf(dir)
        }
    }

    private fun describe(commonDir: File): Project? {
        val bare = git.query(commonDir, "rev-parse", "--is-bare-repository")?.trim() == "true"
        val worktrees = git.query(commonDir, "worktree", "list", "--porcelain")?.let { parseWorktrees(commonDir, it) } ?: return null
        val origin = git.query(commonDir, "config", "--get", "remote.origin.url")?.trim()?.takeIf { it.isNotEmpty() }?.let(RemoteUrl::of)
        val path = if (bare) commonDir else worktrees.firstOrNull { it.main }?.let { File(it.path) } ?: commonDir.parentFile
        return Project(
            name = origin?.gitHubSlug ?: fallbackName(path, origin, bare),
            kind = if (bare) Project.Kind.MIRROR else Project.Kind.CHECKOUT,
            path = path.path,
            originUrl = origin?.display,
            gitHubUrl = origin?.gitHubWebUrl,
            defaultBranch = defaultBranch(commonDir, bare),
            worktrees = worktrees.sortedWith(
                compareByDescending<Worktree> { it.main }.thenByDescending { it.lastCommitAt.orEmpty() },
            ),
        )
    }

    private fun defaultBranch(commonDir: File, bare: Boolean): String? {
        // A bare clone's HEAD is the remote's default branch at clone time.
        if (bare) return git.query(commonDir, "symbolic-ref", "--short", "HEAD")?.trim()
        git.query(commonDir, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it.removePrefix("origin/") }
        // Without origin/HEAD, fall back to the conventional names that actually exist.
        return CONVENTIONAL_DEFAULTS.firstOrNull { name ->
            listOf("refs/remotes/origin/$name", "refs/heads/$name").any { git.query(commonDir, "rev-parse", "--verify", "--quiet", it) != null }
        }
    }

    private fun parseWorktrees(commonDir: File, porcelain: String): List<Worktree> = porcelain
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
                lastCommitAt = head?.let { git.query(commonDir, "log", "-1", "--format=%cI", it) }?.trim()?.takeIf { it.isNotEmpty() },
                changedFiles = if (present) git.query(directory, "status", "--porcelain")?.lines()?.count { it.isNotBlank() } else null,
                agentRun = branch?.startsWith(AGENT_BRANCH_PREFIX) == true,
                main = index == 0,
                missing = !present,
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
        private val CONVENTIONAL_DEFAULTS = listOf("main", "master", "trunk", "develop")

        /** The runner's mirrors, this repository, and any checkouts under `~/repos`. */
        val DEFAULT_SOURCES = listOf("~/agent-work/mirrors/*", "~/LLM_Manager", "~/repos/*")
    }
}
