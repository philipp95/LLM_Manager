package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * The environment an agent process keeps. Everything else is dropped, notably
 * `SSH_AUTH_SOCK`, forge tokens such as `GH_TOKEN`, tmux and D-Bus sockets, and
 * other agents' API keys.
 */
object AgentEnvironment {
    private val BASE = listOf(
        "HOME", "USER", "LOGNAME", "PATH", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TZ", "TMPDIR",
        "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME",
        "JAVA_HOME", "GRADLE_USER_HOME", "GOPATH", "CARGO_HOME", "RUSTUP_HOME",
    )
    private val KEYS = mapOf(
        Agent.CLAUDE to listOf(
            "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX",
            "AWS_REGION", "AWS_PROFILE", "CLOUD_ML_REGION", "ANTHROPIC_VERTEX_PROJECT_ID",
        ),
        Agent.CODEX to listOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL"),
        Agent.GEMINI to listOf(
            "GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GENAI_USE_VERTEXAI", "GOOGLE_GENAI_USE_GCA",
            "GOOGLE_CLOUD_PROJECT", "GOOGLE_CLOUD_LOCATION", "GOOGLE_APPLICATION_CREDENTIALS",
        ),
    )

    fun names(agent: Agent): List<String> = BASE + KEYS.getValue(agent)

    /**
     * Bash that fills the array `keep` with `NAME=value` for each kept variable that is set.
     * Values come from the session's environment when the script runs and are never written
     * to disk; unset variables stay unset (an empty `JAVA_HOME` would break builds).
     */
    fun script(agent: Agent): String {
        val names = names(agent).joinToString(" ")
        return """keep=(); for name in $names; do if [ -n "${'$'}{!name+x}" ]; then keep+=("${'$'}name=${'$'}{!name}"); fi; done"""
    }
}

@Serializable
data class SandboxStatus(
    /** auto, on or off, as configured. */
    val mode: String,
    /** Per agent: whether its runs are sandboxed. */
    val agents: Map<String, Boolean>,
    val note: String,
)

/**
 * OS-level containment for agent processes with bubblewrap (`bwrap`), when installed.
 *
 * The profile makes the root filesystem read-only and hides secrets (this server's tokens
 * and state, forge and cloud credentials, SSH and GPG keys, other agents' logins) and the
 * runtime sockets of tmux, SSH agents and D-Bus. The agent may write only its worktree,
 * its own state directory and build caches; its configuration files (hooks, settings,
 * MCP servers, global instructions) stay read-only so a run cannot plant code that runs
 * later outside the sandbox. Network access is unchanged: agents need their APIs.
 *
 * Modes: [Mode.AUTO] sandboxes when bwrap passes a startup self-test and otherwise runs
 * unsandboxed with a warning; [Mode.ON] refuses runs it cannot sandbox; [Mode.OFF] never sandboxes.
 */
class AgentSandbox private constructor(
    private val mode: Mode,
    private val profile: Profile?,
    private val active: Set<Agent>,
    private val note: String,
) {
    enum class Mode { AUTO, ON, OFF }

    fun activeFor(agent: Agent): Boolean = agent in active

    /** Why a run of [agent] must not start, or null when it may. */
    fun refusal(agent: Agent): String? =
        if (mode == Mode.ON && !activeFor(agent)) "Agent sandbox required (--agent-sandbox on) but unavailable: $note" else null

    /**
     * [command] wrapped in bubblewrap when [agent] is sandboxed. [scratchClaudeJson], for
     * Claude, is a throwaway copy of `~/.claude.json` mounted over the real one.
     */
    fun wrap(agent: Agent, worktree: File, command: List<String>, scratchClaudeJson: File? = null, interactive: Boolean = false): List<String> {
        val p = profile
        return if (p == null || !activeFor(agent)) command else p.args(agent, worktree, scratchClaudeJson, interactive) + "--" + command
    }

    /**
     * For a sandboxed Claude run: a throwaway copy of `~/.claude.json` (which also holds the
     * user's MCP servers) in [dir], so the run cannot change configuration used outside it.
     */
    fun scratchClaudeJson(agent: Agent, dir: File): File? {
        val real = File(System.getProperty("user.home"), ".claude.json")
        if (agent != Agent.CLAUDE || !activeFor(agent) || !real.isFile) return null
        return File(dir, "claude.json").also { real.copyTo(it, overwrite = true) }
    }

    fun status(): SandboxStatus =
        SandboxStatus(mode.name.lowercase(), Agent.entries.associate { it.name.lowercase() to activeFor(it) }, note)

    /** What the profile needs to know about the file system; replaceable in tests. */
    interface FileView {
        fun exists(file: File): Boolean
        fun isDirectory(file: File): Boolean

        object Real : FileView {
            override fun exists(file: File) = Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            override fun isDirectory(file: File) = file.isDirectory
        }
    }

    /** The bwrap argument list; pure apart from [files], so it can be tested without bwrap. */
    class Profile(
        private val bwrap: String,
        private val home: File,
        /** Server state (tokens, run logs); hidden, except [visible] files inside it. */
        private val hiddenServerPaths: List<File>,
        private val visible: List<File>,
        private val uid: Long?,
        private val files: FileView = FileView.Real,
    ) {
        private fun exists(file: File) = files.exists(file)

        fun args(agent: Agent, worktree: File, scratchClaudeJson: File?, interactive: Boolean): List<String> = buildList {
            add(bwrap)
            addAll(listOf("--die-with-parent", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--unshare-cgroup-try"))
            // No controlling terminal for unattended runs: blocks TIOCSTI-style input injection.
            if (!interactive) add("--new-session")
            addAll(listOf("--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"))
            uid?.let { File("/run/user/$it") }?.takeIf(::exists)?.let { addAll(listOf("--tmpfs", it.path)) }
            (hiddenServerPaths + SECRET_PATHS.map { File(home, it) } + otherAgents(agent)).forEach { hide(it) }
            writable(agent, worktree)
            readOnlyConfigOf(agent).forEach { if (exists(it)) addAll(listOf("--ro-bind", it.path, it.path)) }
            if (agent == Agent.CLAUDE && scratchClaudeJson != null && exists(File(home, ".claude.json"))) {
                addAll(listOf("--bind", scratchClaudeJson.path, File(home, ".claude.json").path))
            }
            visible.forEach { if (exists(it)) addAll(listOf("--ro-bind", it.path, it.path)) }
            addAll(listOf("--chdir", worktree.path, "--setenv", "TMPDIR", "/tmp"))
        }

        /** The worktree, the agent's own state and build caches. */
        private fun MutableList<String>.writable(agent: Agent, worktree: File) {
            (listOf(worktree) + stateOf(agent) + CACHES.map { File(home, it) }).forEach { bindWritable(it) }
        }

        private fun MutableList<String>.hide(path: File) {
            if (!exists(path)) return
            if (files.isDirectory(path)) addAll(listOf("--tmpfs", path.path)) else addAll(listOf("--ro-bind", "/dev/null", path.path))
        }

        private fun MutableList<String>.bindWritable(path: File) {
            if (exists(path)) addAll(listOf("--bind", path.path, path.path))
        }

        private fun stateOf(agent: Agent): List<File> = when (agent) {
            Agent.CLAUDE -> listOf(File(home, ".claude"))
            Agent.CODEX -> listOf(File(home, ".codex"))
            Agent.GEMINI -> listOf(File(home, ".gemini"))
        }

        private fun otherAgents(agent: Agent): List<File> = Agent.entries.filter { it != agent }.flatMap {
            stateOf(it) + if (it == Agent.CLAUDE) listOf(File(home, ".claude.json")) else emptyList()
        }

        /** Files through which a run could make code run later, outside the sandbox. */
        private fun readOnlyConfigOf(agent: Agent): List<File> = when (agent) {
            Agent.CLAUDE -> listOf("settings.json", "settings.local.json", "CLAUDE.md", "commands", "agents", "skills", "plugins", "hooks", "output-styles")
                .map { File(home, ".claude/$it") }
            Agent.CODEX -> listOf("config.toml", "AGENTS.md", "AGENTS.override.md", "prompts", "skills", "rules").map { File(home, ".codex/$it") }
            Agent.GEMINI -> listOf("settings.json", "GEMINI.md", "commands", "extensions", "policies", "agents", "skills").map { File(home, ".gemini/$it") }
        }

        private companion object {
            val SECRET_PATHS = listOf(
                ".config/llm-manager", ".config/gh", ".config/glab-cli", ".config/gcloud", ".config/git/credentials",
                ".ssh", ".gnupg", ".aws", ".azure", ".kube", ".docker", ".password-store", ".android",
                ".git-credentials", ".netrc", ".npmrc", ".pypirc",
            )
            val CACHES = listOf(".cache", ".gradle", ".m2", ".npm", ".cargo/registry", ".cargo/git", "go/pkg", ".local/share/pnpm")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentSandbox::class.java)

        fun disabled(note: String = "Agent sandbox is off"): AgentSandbox = AgentSandbox(Mode.OFF, null, emptySet(), note)

        /**
         * Detects bwrap and self-tests the profile: a trivial command, then each installed
         * agent's `--version`, inside the sandbox. Only agents that pass are sandboxed.
         */
        fun detect(
            mode: Mode,
            profileFor: (String) -> Profile,
            agents: Map<Agent, String?>,
            scratch: File,
            /** Only the distribution's bwrap: it carries the AppArmor profile that permits its namespaces. */
            candidates: List<String> = listOf("/usr/bin/bwrap", "/bin/bwrap"),
        ): AgentSandbox {
            if (mode == Mode.OFF) return AgentSandbox(mode, null, emptySet(), "Agent sandbox is off (--agent-sandbox off)")
            val bwrap = candidates.firstOrNull { File(it).canExecute() }
                ?: return AgentSandbox(mode, null, emptySet(), "bubblewrap is not installed; an admin can run `sudo apt install bubblewrap`")
            val profile = profileFor(bwrap)
            scratch.mkdirs()
            val base = selfTest(profile, Agent.CODEX, scratch, listOf("/bin/true"))
            if (base != null) return AgentSandbox(mode, null, emptySet(), "bubblewrap failed its self-test: $base")
            val results = agents.filterValues { it != null }
                .mapValues { (agent, binary) -> selfTest(profile, agent, scratch, listOf(checkNotNull(binary), "--version")) }
            val active = results.filterValues { it == null }.keys
            val failed = results.filterValues { it != null }
            val note = if (failed.isEmpty()) "Agent runs are sandboxed with bubblewrap"
            else "Sandboxed: ${active.joinToString { it.label }.ifEmpty { "none" }}; failed self-test: " +
                failed.entries.joinToString { "${it.key.label} (${it.value})" }
            log.info("Agent sandbox: {}", note)
            return AgentSandbox(mode, profile, active, note)
        }

        private fun selfTest(profile: Profile, agent: Agent, scratch: File, command: List<String>): String? {
            val args = profile.args(agent, scratch, null, interactive = false) + "--" + command
            // Same reduced environment as real runs, set directly so no value appears in a command line.
            val environment = AgentEnvironment.names(agent).mapNotNull { name -> System.getenv(name)?.let { name to it } }.toMap()
            val result = Processes.exec(args, timeoutMillis = SELF_TEST_MS, environment = environment, inheritEnvironment = false)
            return if (result.ok) null else (result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}").take(NOTE_CHARS)
        }

        private const val SELF_TEST_MS = 30_000L
        private const val NOTE_CHARS = 200
    }
}
