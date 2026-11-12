package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectScannerTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()

    private fun repository(relative: String, origin: String? = null): File {
        val repo = File(root, relative).also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        commit(repo, "initial")
        origin?.let { git(repo, "remote", "add", "origin", it) }
        return repo
    }

    @Test
    fun `mirror lists agent runs with branch, commit time and changed files`() {
        val upstream = repository("upstream")
        val mirrors = File(root, "work/mirrors").also { it.mkdirs() }
        val mirror = File(mirrors, "abc123.git")
        git(mirrors, "clone", "--bare", upstream.path, mirror.path)
        git(mirror, "remote", "set-url", "origin", "https://user:ghp_secret@github.com/acme/widgets.git")
        val run = File(root, "work/worktrees/run-1")
        git(mirror, "worktree", "add", "-b", "agent/run-1", run.path, "main")
        File(run, "new.txt").writeText("untracked")
        File(run, "tracked.txt").writeText("x")
        git(run, "add", "tracked.txt")
        val idle = File(root, "work/worktrees/run-2")
        git(mirror, "worktree", "add", "-b", "agent/run-2", idle.path, "main")

        val project = ProjectScanner(listOf("${mirrors.path}/*")).scan().single()

        assertEquals("acme/widgets", project.name)
        assertEquals(Project.Kind.MIRROR, project.kind)
        assertEquals("https://github.com/acme/widgets", project.gitHubUrl)
        assertEquals("https://github.com/acme/widgets.git", project.originUrl)
        assertFalse(project.originUrl!!.contains("secret"))
        assertEquals("main", project.defaultBranch)
        val byBranch = project.worktrees.associateBy { it.branch }
        assertEquals(setOf("agent/run-1", "agent/run-2"), byBranch.keys)
        assertTrue(project.worktrees.all { it.agentRun && !it.main && !it.missing })
        assertEquals(2, byBranch.getValue("agent/run-1").changedFiles)
        assertEquals(0, byBranch.getValue("agent/run-2").changedFiles)
        assertNotNull(byBranch.getValue("agent/run-1").lastCommitAt)
    }

    @Test
    fun `checkout reports main and linked worktrees once, with default branch from origin`() {
        val upstream = repository("upstream")
        val checkout = File(root, "LLM_Manager")
        git(root, "clone", upstream.path, checkout.path)
        git(checkout, "remote", "set-url", "origin", "git@github.com:acme/manager.git")
        val linked = File(root, "repos/feature")
        git(checkout, "worktree", "add", "-b", "feature", linked.path)
        commit(linked, "work on feature")
        File(checkout, "README.md").writeText("dirty")

        // The linked worktree is also reachable through repos/*; it must not become a second project.
        val projects = ProjectScanner(listOf(checkout.path, "${root.path}/repos/*")).scan()

        val project = projects.single()
        assertEquals("acme/manager", project.name)
        assertEquals(Project.Kind.CHECKOUT, project.kind)
        assertEquals(checkout.canonicalPath, File(project.path).canonicalPath)
        assertEquals("main", project.defaultBranch)
        val main = project.worktrees.first()
        assertTrue(main.main)
        assertEquals("main", main.branch)
        assertEquals(1, main.changedFiles)
        val feature = project.worktrees.single { it.branch == "feature" }
        assertFalse(feature.agentRun)
        assertEquals(0, feature.changedFiles)
    }

    @Test
    fun `missing and non-repository sources are skipped`() {
        File(root, "repos/not-a-repo").mkdirs()
        val local = repository("repos/local")
        val projects = ProjectScanner(listOf("${root.path}/does-not-exist/*", "${root.path}/nope", "${root.path}/repos/*")).scan()

        val project = projects.single()
        assertEquals("local", project.name)
        assertNull(project.originUrl)
        assertNull(project.gitHubUrl)
        assertEquals("main", project.defaultBranch)
        assertEquals(local.canonicalPath, File(project.path).canonicalPath)
    }

    @Test
    fun `deleted worktree directory is reported as missing`() {
        val repo = repository("repo")
        val gone = File(root, "gone")
        git(repo, "worktree", "add", "-b", "agent/gone", gone.path)
        assertTrue(gone.deleteRecursively())

        val worktree = ProjectScanner(listOf(repo.path)).scan().single().worktrees.single { it.branch == "agent/gone" }

        assertTrue(worktree.missing)
        assertNull(worktree.changedFiles)
        assertNotNull(worktree.lastCommitAt)
    }

    @Test
    fun `scanning does not modify the repository`() {
        val repo = repository("repo")
        File(repo, "file.txt").writeText("content")
        git(repo, "add", "file.txt")
        commit(repo, "add file")
        // Touch a tracked file so a locking `git status` would want to refresh the index.
        File(repo, "file.txt").setLastModified(System.currentTimeMillis() + 60_000)
        val index = File(repo, ".git/index")
        val before = index.readBytes()
        val modified = index.lastModified()

        repeat(3) { ProjectScanner(listOf(repo.path)).scan() }

        assertTrue(before.contentEquals(index.readBytes()))
        assertEquals(modified, index.lastModified())
        assertFalse(File(repo, ".git/index.lock").exists())
    }

    private fun commit(repo: File, message: String) =
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", message)

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
