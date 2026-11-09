package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * Repository config must not make the server run programs: neither the repo's own
 * config (fsmonitor, hooks) nor a git directory an agent substitutes for its worktree's.
 * Each attack is first shown to work with plain git, then shown not to work through the server.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "uses shell scripts as git hooks and filters")
class GitTrustTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()

    private fun repo(): File {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("app\n")
        git(repo, "add", "README.md")
        git(repo, "-c", "user.name=T", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "initial")
        return repo
    }

    private fun marker(name: String) = File(root, name)

    private fun script(name: String, body: String): File =
        File(root, "bin/$name").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n$body\n"); setExecutable(true) }

    @Test
    fun `fsmonitor set in repository config runs under plain git but not under the scanner`() {
        val repo = repo()
        val hook = script("fsmonitor", "touch '${marker("fsmonitor-ran").path}'\nexit 1")
        git(repo, "config", "core.fsmonitor", hook.path)
        File(repo, "README.md").writeText("changed\n")

        git(repo, "status", "--porcelain")
        assertTrue(marker("fsmonitor-ran").exists(), "control: plain git status runs the fsmonitor command")
        marker("fsmonitor-ran").delete()

        val project = ProjectScanner(listOf("${root.path}/repos/*")).scan().single()

        assertEquals(1, project.worktrees.single().changedFiles)
        assertFalse(marker("fsmonitor-ran").exists(), "the scanner must not run fsmonitor")
    }

    @Test
    fun `a hijacked worktree git link is detected and the engine refuses to commit`() {
        val repo = repo()
        val worktree = File(root, "runs/app-1")
        git(repo, "worktree", "add", "-q", "-b", "run/app-1", worktree.path)
        assertTrue(WorktreeGuard.intact(worktree, File(repo, ".git")))
        assertTrue(WorktreeGuard.intact(repo, File(repo, ".git")))

        // The "agent": its own git directory with an evil clean filter, and the worktree pointed at it.
        val evil = File(root, "evil.git")
        git(root, "init", "-q", "--bare", evil.path)
        val filter = script("filter", "touch '${marker("filter-ran").path}'\ncat")
        git(evil, "config", "filter.evil.clean", filter.path)
        git(evil, "config", "core.bare", "false")
        File(worktree, ".gitattributes").writeText("* filter=evil\n")
        File(worktree, "work.txt").writeText("agent work\n")
        File(worktree, ".git").writeText("gitdir: ${evil.path}\n")

        git(worktree, "add", "-A")
        assertTrue(marker("filter-ran").exists(), "control: plain git add runs the substituted filter")
        marker("filter-ran").delete()

        assertFalse(WorktreeGuard.intact(worktree, File(repo, ".git")))
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"))
        assertNull(scanner.scan().single().worktrees.single { it.branch == "run/app-1" }.changedFiles)

        val store = StateStore(File(root, "state"))
        val run = Run(
            id = "abcdef012345", jobId = null, projectId = scanner.scan().single().id, projectName = "app", agent = Agent.CLAUDE,
            stage = Stage.WORK, status = RunStatus.RUNNING, prompt = "task", worktree = worktree.path, branch = "run/app-1",
            createdAt = "2026-11-09T10:00:00Z", tmuxSession = "run-abcdef012345",
        )
        store.putRun(run)
        File(store.runDir(run.id), "events.jsonl").writeText("""{"type":"result","is_error":false,"result":"done"}""" + "\n")
        File(store.runDir(run.id), "exit").writeText("0\n")
        val engine = RunEngine(store, Workspace(scanner, SessionHost.None, AgentCommands("/bin/claude", null)), File(root, "runs"))

        val polled = engine.poll(run, emptySet())

        assertEquals(RunStatus.FAILED, polled.status)
        assertTrue(polled.error!!.contains(".git link was changed"), polled.error)
        assertFalse(marker("filter-ran").exists(), "the engine must not run git in a hijacked worktree")
        assertNull(polled.resultCommit)
    }

    @Test
    fun `symlinked or foreign git links are not trusted`() {
        val repo = repo()
        val worktree = File(root, "runs/app-2")
        git(repo, "worktree", "add", "-q", "-b", "run/app-2", worktree.path)
        val link = File(worktree, ".git")
        val content = link.readText()

        link.delete()
        Files.createSymbolicLink(link.toPath(), File(repo, ".git/worktrees/app-2").toPath())
        assertFalse(WorktreeGuard.intact(worktree, File(repo, ".git")), "symlink")

        link.delete()
        link.writeText("gitdir: ${File(root, "elsewhere").also { it.mkdirs() }.path}\n")
        assertFalse(WorktreeGuard.intact(worktree, File(repo, ".git")), "outside worktrees/")

        link.writeText(content)
        assertTrue(WorktreeGuard.intact(worktree, File(repo, ".git")), "restored")
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
