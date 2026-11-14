package com.llmmanager.server.api

import java.io.File

/**
 * Detached terminals running one program each, whose screen can be read and typed into.
 * Sign-in flows use them: the agent CLIs print a link, then wait for a code typed at their prompt.
 */
interface Terminals {
    /** Starts [command] in [directory] under [name]; false when it could not start. */
    fun open(name: String, directory: File, command: List<String>): Boolean

    /** The screen's text, wrapped lines joined; null when the terminal is gone. */
    fun screen(name: String): String?

    /** Types [text] at the program's prompt and presses Enter. */
    fun type(name: String, text: String): Boolean

    fun close(name: String)
}

/** [Terminals] as tmux sessions on this server's tmux, wide enough that links are not wrapped. */
class TmuxTerminals(private val binary: String = "tmux") : Terminals {
    override fun open(name: String, directory: File, command: List<String>): Boolean {
        require(SessionNames.isValid(name)) { "Invalid session name" }
        // Arguments after the options are exec'd directly by tmux, not parsed by a shell.
        val start = listOf(binary, "new-session", "-d", "-x", WIDTH, "-y", HEIGHT, "-s", name, "-c", directory.path)
        return Processes.run(start + command) != null
    }

    override fun screen(name: String): String? = Processes.run(listOf(binary, "capture-pane", "-p", "-J", "-t", target(name), "-S", "-$HISTORY"))

    /**
     * The text goes through a tmux buffer filled from stdin, so it never appears on a command
     * line; the buffer is deleted as it is pasted.
     */
    override fun type(name: String, text: String): Boolean {
        val buffer = "llm-manager-$name"
        return Processes.exec(listOf(binary, "load-buffer", "-b", buffer, "-"), timeoutMillis = TIMEOUT_MS, input = text).ok &&
            Processes.run(listOf(binary, "paste-buffer", "-d", "-b", buffer, "-t", target(name))) != null &&
            Processes.run(listOf(binary, "send-keys", "-t", target(name), "Enter")) != null
    }

    override fun close(name: String) {
        Processes.run(listOf(binary, "kill-session", "-t", "=$name"))
    }

    /** Exactly this session's active pane: `=` turns off tmux's prefix matching of names. */
    private fun target(name: String): String {
        require(SessionNames.isValid(name)) { "Invalid session name" }
        return "=$name:"
    }

    private companion object {
        const val WIDTH = "400"
        const val HEIGHT = "60"
        const val HISTORY = 200
        const val TIMEOUT_MS = 5_000L
    }
}
