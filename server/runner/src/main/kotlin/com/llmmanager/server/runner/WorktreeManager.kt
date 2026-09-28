package com.llmmanager.server.runner

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One git worktree per run, cut from a local bare mirror.
 *
 * This is the answer to "two agents touch one repo": shared object store, fully
 * isolated checkouts. Without it, concurrent agents produce branch collisions
 * and stash chaos.
 */
class WorktreeManager(
    private val root: File,
    private val gitBinary: String = "git",
) {
    data class Worktree(val path: File, val branch: String)

    private val mirrorsDir = File(root, "mirrors")
    private val worktreesDir = File(root, "worktrees")

    /** Clones or updates the bare mirror for [repoUrl]; returns its path. */
    fun syncMirror(repoUrl: String, repoKey: String): File {
        mirrorsDir.mkdirs()
        val mirror = File(mirrorsDir, "$repoKey.git")
        if (mirror.exists()) {
            git(mirror, "remote", "update", "--prune")
        } else {
            git(mirrorsDir, "clone", "--mirror", repoUrl, mirror.absolutePath)
        }
        return mirror
    }

    fun create(mirror: File, runId: String, baseRef: String): Worktree {
        worktreesDir.mkdirs()
        val path = File(worktreesDir, runId)
        val branch = "agent/$runId"
        git(mirror, "worktree", "add", "-b", branch, path.absolutePath, baseRef)
        return Worktree(path, branch)
    }

    /**
     * Removes the worktree. [force] because an interrupted agent commonly leaves
     * a dirty tree, and leaking worktrees exhausts disk over time.
     */
    fun remove(mirror: File, worktree: Worktree, force: Boolean = true) {
        val args = buildList {
            add("worktree"); add("remove")
            if (force) add("--force")
            add(worktree.path.absolutePath)
        }
        runCatching { git(mirror, *args.toTypedArray()) }
        runCatching { git(mirror, "worktree", "prune") }
    }

    fun defaultBranch(mirror: File): String =
        runCatching {
            git(mirror, "symbolic-ref", "--short", "HEAD").trim().ifEmpty { "main" }
        }.getOrDefault("main")

    private fun git(cwd: File, vararg args: String): String {
        val process = ProcessBuilder(listOf(gitBinary) + args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${args.joinToString(" ")} timed out after ${GIT_TIMEOUT_SECONDS}s")
        }
        check(process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed (${process.exitValue()}): ${output.take(2000)}"
        }
        return output
    }

    private companion object { const val GIT_TIMEOUT_SECONDS = 300L }
}
