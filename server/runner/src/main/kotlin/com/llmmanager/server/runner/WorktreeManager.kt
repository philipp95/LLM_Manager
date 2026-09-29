package com.llmmanager.server.runner

import java.io.File
import java.security.MessageDigest

/** A bare repository cache with one isolated worktree per run. */
class WorktreeManager(
    root: File,
    private val gitBinary: String = "git",
    private val timeoutMillis: Long = 300_000,
) {
    data class Worktree(val path: File, val branch: String)

    private val mirrorsDir = File(root.canonicalFile, "mirrors")
    private val worktreesDir = File(root.canonicalFile, "worktrees")

    /** Cache by full repository identity, never by its display name. */
    @Synchronized
    fun syncMirror(repoUrl: String): File {
        val identity = repositoryIdentity(repoUrl)
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        check(mirrorsDir.isDirectory || mirrorsDir.mkdirs())
        val mirror = File(mirrorsDir, "${key}.git")
        requireManaged(mirror, mirrorsDir)
        if (mirror.exists()) {
            val actual = git(mirror, "remote", "get-url", "origin").trim()
            check(repositoryIdentity(actual) == identity) { "Cached repository origin does not match the requested repository" }
        } else {
            git(mirrorsDir, "clone", "--bare", "--", identity, mirror.absolutePath)
        }
        // Remote-tracking refs keep fetch/prune from deleting agent branches in active worktrees.
        git(mirror, "fetch", "--prune", "origin", "+refs/heads/*:refs/remotes/origin/*")
        return mirror
    }

    fun create(mirror: File, runId: String, baseRef: String): Worktree {
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")))
        requireManaged(mirror, mirrorsDir)
        check(worktreesDir.isDirectory || worktreesDir.mkdirs())
        val path = File(worktreesDir, runId).canonicalFile
        requireManaged(path, worktreesDir)
        val branch = "agent/${runId}"
        git(mirror, "worktree", "add", "-b", branch, path.absolutePath, baseRef)
        return Worktree(path, branch)
    }

    /** Only worktrees under this manager's root can be removed; cleanup failures are observable. */
    fun remove(mirror: File, worktree: Worktree, force: Boolean = true) {
        requireManaged(mirror, mirrorsDir)
        requireManaged(worktree.path, worktreesDir)
        if (force) {
            git(mirror, "worktree", "remove", "--force", worktree.path.absolutePath)
        } else {
            git(mirror, "worktree", "remove", worktree.path.absolutePath)
        }
        git(mirror, "worktree", "prune")
    }

    fun defaultBranch(mirror: File): String {
        requireManaged(mirror, mirrorsDir)
        val branch = git(mirror, "symbolic-ref", "--short", "HEAD").trim()
        return "refs/remotes/origin/${branch}"
    }

    fun status(worktree: Worktree): String {
        requireManaged(worktree.path, worktreesDir)
        return git(worktree.path, "status", "--short")
    }

    private fun repositoryIdentity(repo: String): String {
        require(repo.isNotBlank() && !repo.startsWith("-"))
        val local = File(repo)
        return if (local.exists()) local.canonicalPath else repo.trimEnd('/')
    }

    private fun requireManaged(path: File, parent: File) {
        require(path.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath()) &&
            path.canonicalFile != parent.canonicalFile) { "Path is outside the managed directory" }
    }

    private fun git(cwd: File, vararg args: String): String {
        val output = StringBuffer()
        val redact = SecretRedactor().newStream()
        // stdout and stderr may arrive concurrently. Keep diagnostics bounded and sanitize before storing.
        val consume: (String) -> Unit = { line ->
            synchronized(output) {
                val safe = redact.redact(line)
                val available = MAX_OUTPUT_CHARS - output.length
                if (available > 0) output.append((safe + "\n").take(available))
            }
        }
        val result = ProcessRunner.run(listOf(gitBinary) + args, cwd, timeoutMillis, consume, consume)
        check(!result.timedOut) { "git ${args.firstOrNull()} timed out after ${timeoutMillis}ms" }
        check(result.exitCode == 0) {
            "git ${args.firstOrNull()} failed (${result.exitCode}): ${output.toString().take(2000)}"
        }
        return output.toString()
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
