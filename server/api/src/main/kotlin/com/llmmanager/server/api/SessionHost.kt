package com.llmmanager.server.api

import java.io.File

/** Long-lived terminal sessions a person can attach to; tmux in production. */
interface SessionHost {
    /** Names of live sessions, or null when they cannot be listed. */
    fun list(): Set<String>?

    /** Starts [command] detached in [directory] under [name]; false if it could not be started. */
    fun start(name: String, directory: File, command: List<String>): Boolean

    /** For a server that does not launch anything. */
    object None : SessionHost {
        override fun list(): Set<String> = emptySet()
        override fun start(name: String, directory: File, command: List<String>) = false
    }
}

class TmuxSessions(private val binary: String = "tmux") : SessionHost {
    override fun list(): Set<String>? {
        // Exits nonzero when no tmux server is running, which simply means no sessions.
        if (Processes.run(listOf(binary, "-V")) == null) return null
        return Processes.run(listOf(binary, "list-sessions", "-F", "#{session_name}"))
            ?.lines()?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
    }

    override fun start(name: String, directory: File, command: List<String>): Boolean {
        require(SessionNames.isValid(name)) { "Invalid session name" }
        // Arguments after the options are exec'd directly by tmux, not parsed by a shell.
        return Processes.run(listOf(binary, "new-session", "-d", "-s", name, "-c", directory.path) + command) != null
    }
}

object SessionNames {
    private val VALID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")

    fun isValid(name: String): Boolean = VALID.matches(name)
}
