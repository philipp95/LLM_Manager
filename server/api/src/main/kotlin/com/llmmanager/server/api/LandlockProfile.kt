package com.llmmanager.server.api

import java.io.File

/** Where agents keep their state and where secrets live, below the home directory of the server's user. */
class AgentPaths(private val home: File) {
    val secrets: List<File> = SECRET_PATHS.map { File(home, it) }
    val caches: List<File> = CACHES.map { File(home, it) }

    fun stateOf(agent: Agent): List<File> = when (agent) {
        Agent.CLAUDE -> listOf(File(home, ".claude"))
        Agent.CODEX -> listOf(File(home, ".codex"))
        Agent.GEMINI -> listOf(File(home, ".gemini"))
    }

    /** The logins of the other agents. */
    fun otherAgents(agent: Agent): List<File> = Agent.entries.filter { it != agent }.flatMap {
        stateOf(it) + if (it == Agent.CLAUDE) listOf(claudeJson) else emptyList()
    }

    /** Claude's settings and MCP servers, next to its state directory. */
    val claudeJson: File get() = File(home, ".claude.json")

    private companion object {
        val SECRET_PATHS = listOf(
            ".config/llm-manager", ".config/gh", ".config/glab-cli", ".config/gcloud", ".config/git/credentials",
            ".ssh", ".gnupg", ".aws", ".azure", ".kube", ".docker", ".password-store", ".android",
            ".git-credentials", ".netrc", ".npmrc", ".pypirc",
        )
        val CACHES = listOf(".cache", ".gradle", ".m2", ".npm", ".cargo/registry", ".cargo/git", "go/pkg", ".local/share/pnpm")
    }
}

/**
 * Containment with Landlock, the kernel's unprivileged sandbox, for machines where bubblewrap
 * is missing or may not create its namespaces. It needs no root and no packages: a small
 * helper (the [launcher]) restricts itself and then starts the agent.
 *
 * The agent may write only its worktree, its own state directory, build caches, `/tmp` and
 * `/dev`. It may read the rest of the machine except what the bubblewrap profile hides: this
 * server's state, credentials, keys and the other agents' logins. Landlock allows rather than
 * hides, so everything readable is listed: each directory on the way to a hidden path is
 * replaced by its other entries. The helper also refuses Unix sockets (tmux, D-Bus, systemd
 * and Docker would run commands outside) and signals to processes outside.
 *
 * Weaker than bubblewrap in three ways: the agent's own configuration files stay writable,
 * `/tmp` is shared with the rest of the machine, and a file created later next to a hidden
 * one is not readable until the next run.
 */
class LandlockProfile(
    /** The helper and what runs it; see [launcher]. */
    private val launcher: List<String>,
    private val home: File,
    private val hiddenServerPaths: List<File>,
    private val visible: List<File>,
    private val uid: Long?,
    private val files: AgentSandbox.FileView = AgentSandbox.FileView.Real,
) : AgentSandbox.Confinement {
    private val paths = AgentPaths(home)
    override val summary = "Agent runs are confined with Landlock: an agent can change only its own worktree and cannot read this server's secrets"
    override val mountsOver = false
    override val replacesAgentSandbox = true

    override fun args(agent: Agent, worktree: File, scratchClaudeJson: File?, interactive: Boolean): List<String> = buildList {
        addAll(launcher)
        val writable = (listOf(worktree) + paths.stateOf(agent) + paths.caches + SHARED).filter(files::exists)
        writable.forEach { addAll(listOf("--rw", it.path)) }
        val hidden = (hiddenServerPaths + paths.secrets + paths.otherAgents(agent) + listOfNotNull(uid?.let { File("/run/user/$it") }))
            .map(files::resolved)
        val readable = readable(File("/"), hidden, MAX_DEPTH) + visible.filter(files::exists)
        (readable - writable.toSet()).forEach { addAll(listOf("--ro", it.path)) }
    }

    /**
     * The entries of [dir] an agent may read. One that leads to a hidden path is replaced by
     * its own readable entries; links are followed first, so a link to a secret is hidden too.
     */
    private fun readable(dir: File, hidden: List<File>, depth: Int): List<File> = files.children(dir).sortedBy { it.path }.flatMap { child ->
        val real = files.resolved(child)
        when {
            hidden.any { real.startsWith(it) } -> emptyList()
            hidden.none { it.startsWith(real) } -> listOf(child)
            depth > 0 && files.isDirectory(child) -> readable(child, hidden, depth - 1)
            else -> emptyList()
        }
    }

    /** Writing next to the worktree must fail. The path is outside `/tmp`, which is writable. */
    override fun escapeProbe(scratch: File): List<String> =
        listOf("/bin/sh", "-c", "if (: >\"\$0\") 2>/dev/null; then exit 1; fi", File(scratch.absoluteFile.parentFile, ".sandbox-denied").path)

    companion object {
        private val SHARED = listOf(File("/tmp"), File("/dev"))
        private const val MAX_DEPTH = 8
        private const val RESOURCE = "/sandbox/landlock.py"

        /** The command that starts [helper]; `-I` keeps Python from loading code from the worktree. */
        fun launcher(python: String, helper: File): List<String> = listOf(python, "-I", helper.path)

        /** Writes the helper into [dir] and returns it, or null when it is not packaged. */
        fun install(dir: File): File? {
            val source = LandlockProfile::class.java.getResourceAsStream(RESOURCE) ?: return null
            return File(dir, "landlock.py").also { target -> source.use { target.writeBytes(it.readBytes()) } }
        }
    }
}
