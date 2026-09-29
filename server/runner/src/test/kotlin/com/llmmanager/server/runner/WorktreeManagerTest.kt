package com.llmmanager.server.runner

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorktreeManagerTest {
    @TempDir
    lateinit var directory: Path

    private fun repository(relative: String): File {
        val repo = directory.resolve(relative).toFile().also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "initial")
        return repo
    }

    @Test
    fun `repositories sharing a name have distinct mirrors and verified origins`() {
        val first = repository("ownerA/project")
        val second = repository("ownerB/project")
        val manager = WorktreeManager(directory.resolve("managed").toFile())
        val firstMirror = manager.syncMirror(first.path)
        val secondMirror = manager.syncMirror(second.path)
        assertNotEquals(firstMirror, secondMirror)
        assertEquals(first.canonicalFile, File(git(firstMirror, "remote", "get-url", "origin").trim()).canonicalFile)
        assertEquals(second.canonicalFile, File(git(secondMirror, "remote", "get-url", "origin").trim()).canonicalFile)
        git(firstMirror, "remote", "set-url", "origin", second.path)
        assertFailsWith<IllegalStateException> { manager.syncMirror(first.path) }
    }

    @Test
    fun `fetch preserves active worktrees and new worktrees see upstream changes`() {
        val repo = repository("source")
        val manager = WorktreeManager(directory.resolve("managed").toFile())
        val mirror = manager.syncMirror(repo.path)
        val first = manager.create(mirror, "run-one", manager.defaultBranch(mirror))
        File(first.path, "dirty.txt").writeText("uncommitted")
        File(repo, "upstream.txt").writeText("new upstream content")
        git(repo, "add", "upstream.txt")
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "update")
        manager.syncMirror(repo.path)
        val second = manager.create(mirror, "run-two", manager.defaultBranch(mirror))
        assertTrue(File(first.path, "dirty.txt").exists())
        assertFalse(File(first.path, "upstream.txt").exists())
        assertTrue(File(second.path, "upstream.txt").exists())
        manager.remove(mirror, first)
        manager.remove(mirror, second)
        assertFalse(first.path.exists())
        assertFalse(second.path.exists())
        assertFalse(git(mirror, "worktree", "list", "--porcelain").contains("run-one"))
    }

    @Test
    fun `paths outside the managed root are rejected`() {
        val repo = repository("source")
        val manager = WorktreeManager(directory.resolve("managed").toFile())
        val mirror = manager.syncMirror(repo.path)
        assertFailsWith<IllegalArgumentException> { manager.create(mirror, "../escape", "main") }
        assertFailsWith<IllegalArgumentException> { manager.remove(mirror, WorktreeManager.Worktree(repo, "main")) }
        assertTrue(repo.exists())
    }

    private fun git(directory: File, vararg args: String): String {
        val output = StringBuilder()
        val result = ProcessRunner.run(listOf("git") + args, directory, 15000, stdout = { output.appendLine(it) })
        check(!result.timedOut && result.exitCode == 0) { "Git fixture failed" }
        return output.toString()
    }
}
