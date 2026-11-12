package com.llmmanager.server.api

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Decides whether the server may run git inside a worktree.
 *
 * A linked worktree finds its repository through its `.git` file. An agent that can write
 * the worktree could point that file at a git directory of its own, whose config defines
 * filter drivers; `git status` and `git add` run those filters. So before running git in a
 * linked worktree, the server checks that `.git` is a plain file pointing at
 * `<commonDir>/worktrees/<name>`, the administrative directory git itself created.
 */
object WorktreeGuard {
    fun intact(worktree: File, commonDir: File): Boolean = try {
        val dotGit = worktree.toPath().resolve(".git")
        val repository = commonDir.toPath().toRealPath()
        when {
            Files.isSymbolicLink(dotGit) -> false
            // The main worktree of a regular checkout: `.git` is the repository itself.
            Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS) -> dotGit.toRealPath() == repository
            Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS) -> pointsIntoAdmin(worktree.toPath(), dotGit, repository)
            else -> false
        }
    } catch (_: IOException) {
        false
    }

    private fun pointsIntoAdmin(worktree: Path, dotGit: Path, repository: Path): Boolean {
        val line = Files.newBufferedReader(dotGit).use { it.readLine() }?.trim() ?: return false
        if (!line.startsWith(PREFIX)) return false
        val target = Path.of(line.removePrefix(PREFIX)).let { if (it.isAbsolute) it else worktree.resolve(it) }
        val admin = target.toRealPath()
        return admin.parent == repository.resolve("worktrees") && Files.isDirectory(admin, LinkOption.NOFOLLOW_LINKS)
    }

    private const val PREFIX = "gitdir: "
}
