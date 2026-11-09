package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CodexLauncherTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)

    /** Records starts instead of running tmux. */
    private class FakeSessions(var live: MutableSet<String>? = mutableSetOf(), private val succeed: Boolean = true) : SessionHost {
        val started = mutableListOf<Triple<String, File, List<String>>>()
        override fun list(): Set<String>? = live?.toSet()
        override fun start(name: String, directory: File, command: List<String>): Boolean {
            started += Triple(name, directory, command)
            if (succeed) live?.add(name)
            return succeed
        }
    }

    private fun checkout(): File {
        val upstream = File(root, "upstream").also { it.mkdirs() }
        git(upstream, "init", "-b", "main")
        commit(upstream, "initial")
        val checkout = File(root, "checkouts/app")
        git(root, "clone", upstream.path, checkout.path)
        git(checkout, "remote", "set-url", "origin", "https://github.com/acme/app.git")
        return checkout
    }

    private fun launcher(sessions: FakeSessions, codex: String? = "/opt/codex", max: Int = 4) =
        ProjectScanner(listOf("${root.path}/checkouts/*"), sessions = sessions).let { scanner ->
            scanner to CodexLauncher(scanner, sessions, CodexLauncher.Settings(File(root, "codex-worktrees"), codex, max), clock = clock)
        }

    @Test
    fun `starts codex in a new worktree from the checkout's current commit`() {
        val checkout = checkout()
        git(checkout, "switch", "-c", "feature")
        commit(checkout, "unpushed work")
        val head = output(checkout, "rev-parse", "HEAD")
        val sessions = FakeSessions()
        val (scanner, launcher) = launcher(sessions)
        val project = scanner.scan().single()

        val result = assertIs<CodexLauncher.Result.Started>(launcher.launch(project.id))

        assertTrue(result.branch.startsWith("codex/app-20261109-120000-"))
        val path = File(result.path)
        assertEquals(head, output(path, "rev-parse", "HEAD"))
        assertEquals(result.branch, output(path, "branch", "--show-current"))
        assertEquals("feature", output(checkout, "branch", "--show-current"), "the person's checkout is untouched")
        val (name, dir, command) = sessions.started.single()
        assertEquals("codex-" + result.branch.removePrefix("codex/"), name)
        assertEquals(path.canonicalFile, dir.canonicalFile)
        assertEquals(listOf("/opt/codex", "--sandbox", "workspace-write", "--ask-for-approval", "on-request"), command)
        assertEquals("tmux attach -t $name", result.session.attachCommand)

        val reported = scanner.scan().single().worktrees.single { it.branch == result.branch }
        assertEquals(CodexSession(name, true, "tmux attach -t $name"), reported.codex)
        sessions.live!!.remove(name)
        assertFalse(scanner.scan().single().worktrees.single { it.branch == result.branch }.codex!!.running)
    }

    @Test
    fun `mirrors start from the remote default branch`() {
        val upstream = File(root, "upstream").also { it.mkdirs() }
        git(upstream, "init", "-b", "main")
        commit(upstream, "initial")
        val mirrors = File(root, "mirrors").also { it.mkdirs() }
        val mirror = File(mirrors, "m.git")
        git(mirrors, "clone", "--bare", upstream.path, mirror.path)
        git(mirror, "fetch", "origin", "+refs/heads/*:refs/remotes/origin/*")
        commit(upstream, "newer upstream")
        git(mirror, "fetch", "origin", "+refs/heads/*:refs/remotes/origin/*")
        val sessions = FakeSessions()
        val scanner = ProjectScanner(listOf("${mirrors.path}/*"), sessions = sessions)
        val launcher = CodexLauncher(scanner, sessions, CodexLauncher.Settings(File(root, "codex-worktrees"), "/opt/codex"), clock = clock)

        val result = assertIs<CodexLauncher.Result.Started>(launcher.launch(scanner.scan().single().id))

        assertEquals(output(upstream, "rev-parse", "HEAD"), output(File(result.path), "rev-parse", "HEAD"))
    }

    @Test
    fun `failed session start leaves no worktree or branch behind`() {
        val checkout = checkout()
        val sessions = FakeSessions(succeed = false)
        val (scanner, launcher) = launcher(sessions)

        val result = launcher.launch(scanner.scan().single().id)

        assertIs<CodexLauncher.Result.Failed>(result)
        assertEquals("", output(checkout, "branch", "--list", "codex/*"))
        assertEquals(1, scanner.scan().single().worktrees.size)
        assertTrue(File(root, "codex-worktrees").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `refuses unknown projects, missing codex, missing tmux and too many sessions`() {
        checkout()
        val (_, launcher) = launcher(FakeSessions())
        assertEquals(CodexLauncher.Result.UnknownProject, launcher.launch("not-a-project"))

        val (scanner, noCodex) = launcher(FakeSessions(), codex = null)
        assertEquals(CodexLauncher.Result.CodexUnavailable, noCodex.launch(scanner.scan().single().id))

        val (scanner2, noTmux) = launcher(FakeSessions(live = null))
        assertIs<CodexLauncher.Result.Failed>(noTmux.launch(scanner2.scan().single().id))

        val busy = FakeSessions(mutableSetOf("codex-a", "codex-b", "unrelated"))
        val (scanner3, capped) = launcher(busy, max = 2)
        assertEquals(CodexLauncher.Result.TooManyRunning, capped.launch(scanner3.scan().single().id))
        assertTrue(busy.started.isEmpty())
    }

    @Test
    fun `only codex branches map to session names`() {
        assertEquals("codex-app-1", CodexLauncher.sessionNameFor("codex/app-1"))
        assertEquals(null, CodexLauncher.sessionNameFor("agent/run-1"))
        assertEquals(null, CodexLauncher.sessionNameFor("codex/bad;name"))
    }

    private fun commit(repo: File, message: String) =
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", message)

    private fun output(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $text" }
        return text.trim()
    }

    private fun git(directory: File, vararg args: String) {
        output(directory, *args)
    }
}
