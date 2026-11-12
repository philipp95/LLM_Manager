package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** The bubblewrap profile, checked as an argument list (bwrap itself is not needed). */
@DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX paths; the sandbox is Linux-only")
class AgentSandboxTest {
    @TempDir
    lateinit var directory: Path

    private val home = File("/home/u")
    private val state = File("/home/u/.local/share/llm-manager")
    private val policy = File(state, "gemini-policy.toml")
    private val worktree = File("/home/u/agent-work/runs/app-1")
    private val existing = setOf(
        "/home/u/.config/llm-manager", "/home/u/.config/gh", "/home/u/.ssh", "/home/u/.git-credentials", "/home/u/.npmrc",
        "/home/u/.claude", "/home/u/.claude.json", "/home/u/.claude/settings.json", "/home/u/.claude/CLAUDE.md",
        "/home/u/.codex", "/home/u/.codex/config.toml", "/home/u/.gemini", "/home/u/.gradle", "/home/u/.cache",
        state.path, policy.path, worktree.path, "/run/user/1001",
    )
    private val files = setOf("/home/u/.git-credentials", "/home/u/.npmrc", "/home/u/.claude.json", "/home/u/.claude/settings.json",
        "/home/u/.claude/CLAUDE.md", "/home/u/.codex/config.toml", policy.path)

    private val view = object : AgentSandbox.FileView {
        override fun exists(file: File) = file.path in existing
        override fun isDirectory(file: File) = file.path in existing && file.path !in files
    }

    private val profile = AgentSandbox.Profile(
        "/usr/bin/bwrap", home, hiddenServerPaths = listOf(state, File("/home/u/.config/llm-manager")), visible = listOf(policy), uid = 1001,
        files = view,
    )

    private fun List<String>.has(vararg parts: String): Boolean = windowed(parts.size).any { it == parts.toList() }

    private fun List<String>.at(vararg parts: String): Int = windowed(parts.size).indexOfFirst { it == parts.toList() }

    @Test
    fun `root is read-only, secrets and sockets are hidden, the worktree is writable`() {
        val args = profile.args(Agent.CLAUDE, worktree, File(state, "runs/r/claude.json"), interactive = false)

        assertEquals("/usr/bin/bwrap", args.first())
        assertTrue(args.has("--ro-bind", "/", "/") && args.has("--tmpfs", "/tmp") && args.has("--unshare-pid") && args.has("--new-session"))
        assertTrue(args.has("--tmpfs", "/run/user/1001"), "tmux, ssh-agent and D-Bus sockets")
        for (dir in listOf("/home/u/.config/llm-manager", "/home/u/.config/gh", "/home/u/.ssh", state.path)) assertTrue(args.has("--tmpfs", dir), dir)
        for (file in listOf("/home/u/.git-credentials", "/home/u/.npmrc")) assertTrue(args.has("--ro-bind", "/dev/null", file), file)
        assertFalse(args.any { it.contains(".aws") }, "paths that do not exist are left alone")
        assertTrue(args.has("--bind", worktree.path, worktree.path) && args.has("--chdir", worktree.path))
        assertFalse(args.contains("--unshare-net"), "agents need their APIs")
        assertFalse(args.contains("--share-net"))
    }

    @Test
    fun `an agent sees its own state writable but its config read-only, and not other agents' logins`() {
        val args = profile.args(Agent.CLAUDE, worktree, File(state, "runs/r/claude.json"), interactive = false)

        assertTrue(args.has("--bind", "/home/u/.claude", "/home/u/.claude"))
        val settings = args.at("--ro-bind", "/home/u/.claude/settings.json", "/home/u/.claude/settings.json")
        assertTrue(settings > args.at("--bind", "/home/u/.claude", "/home/u/.claude"), "config overlay comes after the writable state bind")
        assertTrue(args.has("--ro-bind", "/home/u/.claude/CLAUDE.md", "/home/u/.claude/CLAUDE.md"))
        assertTrue(args.has("--bind", File(state, "runs/r/claude.json").path, "/home/u/.claude.json"), "throwaway ~/.claude.json")
        assertTrue(args.has("--tmpfs", "/home/u/.codex") && args.has("--tmpfs", "/home/u/.gemini"))
        assertTrue(args.has("--bind", "/home/u/.gradle", "/home/u/.gradle") && args.has("--bind", "/home/u/.cache", "/home/u/.cache"))

        val codex = profile.args(Agent.CODEX, worktree, null, interactive = true)
        assertTrue(codex.has("--tmpfs", "/home/u/.claude") && codex.has("--ro-bind", "/dev/null", "/home/u/.claude.json"))
        assertTrue(codex.has("--ro-bind", "/home/u/.codex/config.toml", "/home/u/.codex/config.toml"))
        assertFalse(codex.contains("--new-session"), "interactive sessions keep their terminal")
    }

    @Test
    fun `the gemini policy stays visible although the server state is hidden`() {
        val args = profile.args(Agent.GEMINI, worktree, null, interactive = false)
        val hidden = args.at("--tmpfs", state.path)
        val shown = args.at("--ro-bind", policy.path, policy.path)
        assertTrue(hidden >= 0 && shown > hidden, "re-exposed after the state directory is hidden")
    }

    @Test
    fun `without bubblewrap runs are unsandboxed in auto mode and refused in on mode`() {
        val scratch = directory.toFile()
        val claude = mapOf(Agent.CLAUDE to "/bin/claude")
        val missing = listOf("/nonexistent/bwrap")
        val auto = AgentSandbox.detect(AgentSandbox.Mode.AUTO, { profile }, claude, scratch, candidates = missing)
        assertFalse(auto.activeFor(Agent.CLAUDE))
        assertNull(auto.refusal(Agent.CLAUDE))
        assertTrue(auto.status().note.contains("apt install bubblewrap"))
        assertEquals(listOf("/bin/claude", "-p"), auto.wrap(Agent.CLAUDE, worktree, listOf("/bin/claude", "-p")))

        val on = AgentSandbox.detect(AgentSandbox.Mode.ON, { profile }, claude, scratch, candidates = missing)
        assertTrue(on.refusal(Agent.CLAUDE)!!.contains("--agent-sandbox on"))

        val off = AgentSandbox.detect(AgentSandbox.Mode.OFF, { profile }, emptyMap(), scratch)
        assertEquals("off", off.status().mode)
        assertNull(off.refusal(Agent.GEMINI))
    }
}
