package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** The Landlock profile as an argument list, and the helper itself where the kernel offers Landlock. */
@EnabledOnOs(OS.LINUX)
class LandlockProfileTest {
    @TempDir
    lateinit var directory: Path

    private val home = File("/home/u")
    private val state = File("/home/u/.local/share/llm-manager")
    private val policy = File(state, "gemini-policy.toml")
    private val worktree = File("/home/u/agent-work/runs/app-1")

    /** A small machine: directories with their entries; everything else is a file. */
    private val tree = mapOf(
        "/" to listOf("usr", "etc", "home", "run", "tmp", "dev"),
        "/home" to listOf("u", "other"),
        "/home/u" to listOf(".ssh", ".config", ".claude", ".claude.json", ".codex", ".gemini", ".local", ".cache", "agent-work", "notes.txt", "keys"),
        "/home/u/.config" to listOf("llm-manager", "gh", "git"),
        "/home/u/.config/git" to listOf("config", "credentials"),
        "/home/u/.local" to listOf("share", "bin"),
        "/home/u/.local/share" to listOf("llm-manager", "applications"),
        "/home/u/agent-work" to listOf("runs"),
        "/home/u/agent-work/runs" to listOf("app-1", "app-2"),
        "/run" to listOf("user", "systemd"),
        "/run/user" to listOf("1001"),
    )
    private val directories = tree.keys + listOf(
        "/usr", "/etc", "/tmp", "/dev", "/home/other", "/home/u/.ssh", "/home/u/.claude", "/home/u/.codex", "/home/u/.gemini",
        "/home/u/.cache", "/home/u/.config/llm-manager", "/home/u/.config/gh", state.path, worktree.path, "/home/u/agent-work/runs/app-2",
        "/run/user/1001", "/run/systemd", "/home/u/.local/bin", "/home/u/.local/share/applications",
    )

    private val view = object : AgentSandbox.FileView {
        private fun known(path: String) = path in directories || tree.any { (dir, names) -> names.any { File(dir, it).path == path } } || path == policy.path
        override fun exists(file: File) = known(file.path)
        override fun isDirectory(file: File) = file.path in directories
        override fun children(dir: File) = tree[dir.path].orEmpty().map { File(dir, it) }

        // A link in the home directory that points at the SSH keys.
        override fun resolved(file: File) = if (file.path == "/home/u/keys") File("/home/u/.ssh") else file
    }

    private val profile = LandlockProfile(
        LandlockProfile.launcher("/usr/bin/python3", File(state, "landlock.py")), home,
        hiddenServerPaths = listOf(state, File("/home/u/.config/llm-manager")), visible = listOf(policy), uid = 1001, files = view,
    )

    private fun List<String>.after(flag: String) = windowed(2).filter { it[0] == flag }.map { it[1] }

    @Test
    fun `an agent may write its worktree and own state, and read all but the secrets`() {
        val args = profile.args(Agent.CODEX, worktree, null, interactive = false)

        assertEquals(listOf("/usr/bin/python3", "-I", File(state, "landlock.py").path), args.take(3))
        assertEquals(setOf(worktree.path, "/home/u/.codex", "/home/u/.cache", "/tmp", "/dev"), args.after("--rw").toSet())
        val readable = args.after("--ro").toSet()
        assertTrue(readable.containsAll(listOf("/usr", "/etc", "/home/other", "/home/u/notes.txt", "/home/u/.local/bin", "/run/systemd", policy.path)))
        assertTrue("/home/u/agent-work" in readable, "other worktrees can be read, not written")
        assertTrue("/home/u/.config/git/config" in readable && "/home/u/.config/git/credentials" !in readable)
        val hidden = listOf(
            "/", "/home", "/home/u", "/home/u/.ssh", "/home/u/keys", "/home/u/.config", "/home/u/.config/llm-manager", "/home/u/.config/gh",
            "/home/u/.claude", "/home/u/.claude.json", "/home/u/.gemini", state.path, "/home/u/.local", "/run", "/run/user", "/run/user/1001",
        )
        for (path in hidden) assertFalse(path in readable, "$path must not be readable as a whole")
        assertFalse(readable.any { it in args.after("--rw") }, "a writable path is not listed twice")
    }

    @Test
    fun `each agent sees only its own login`() {
        val claude = profile.args(Agent.CLAUDE, worktree, null, interactive = false)

        assertTrue("/home/u/.claude" in claude.after("--rw"))
        assertTrue("/home/u/.claude.json" in claude.after("--ro"))
        assertFalse(claude.any { it == "/home/u/.codex" || it == "/home/u/.gemini" })
        assertTrue(profile.replacesAgentSandbox && !profile.mountsOver)
    }

    private fun helper(): File = checkNotNull(LandlockProfile.install(directory.toFile()))

    private fun confined(helper: File, inside: File, script: String) = Processes.exec(
        listOf("python3", "-I", helper.path, "--rw", inside.path, "--ro", "/usr", "--ro", "/bin", "--ro", "/lib", "--ro", "/lib64", "--ro", "/etc",
            "--rw", "/dev", "--", "/bin/sh", "-c", script),
    )

    @Test
    fun `the helper confines writes and reads, and refuses Unix sockets`() {
        val helper = helper()
        val inside = File(directory.toFile(), "inside").apply { mkdirs() }
        val outside = File(directory.toFile(), "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("hidden")
        assumeTrue(confined(helper, inside, "true").ok, "this kernel has no Landlock, or no python3")

        assertTrue(confined(helper, inside, "echo ok > '${inside.path}/made.txt'").ok)
        assertEquals("ok", File(inside, "made.txt").readText().trim())
        assertFalse(confined(helper, inside, "echo no > '${outside.path}/made.txt'").ok, "a write outside is refused")
        assertFalse(File(outside, "made.txt").exists())
        assertFalse(confined(helper, inside, "cat '${outside.path}/secret.txt'").ok, "a read outside is refused")
        val socket = "import socket,sys\ntry:\n socket.socket(socket.AF_UNIX)\nexcept PermissionError:\n sys.exit(7)\n"
        File(inside, "socket.py").writeText(socket)
        assertEquals(7, confined(helper, inside, "exec python3 -I '${inside.path}/socket.py'").exitCode, "tmux and D-Bus are out of reach")
    }

    @Test
    fun `the helper never runs a command it cannot confine`() {
        val result = Processes.exec(listOf("python3", "-I", helper().path, "--bogus", "x", "--", "/bin/true"))
        assumeTrue(result.exitCode != 127, "no python3")

        assertEquals(96, result.exitCode)
    }
}
