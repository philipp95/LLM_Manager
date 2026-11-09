package com.llmmanager.server.api

import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs short-lived helper commands (git, tmux) with a deadline and bounded output. */
object Processes {
    /** Stdout when the command exits 0 in time; null when it fails, times out, or cannot start. */
    fun run(command: List<String>, directory: File? = null, timeoutMillis: Long = 10_000, environment: Map<String, String> = emptyMap()): String? {
        val process = try {
            ProcessBuilder(command).directory(directory)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment().putAll(environment) }
                .start()
        } catch (_: IOException) {
            return null
        }
        process.outputStream.close()
        val output = reader.submit<String> { readBounded(process) }
        return try {
            val exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (exited && process.exitValue() == 0) output.get(timeoutMillis, TimeUnit.MILLISECONDS) else null
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        } finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    }

    private fun readBounded(process: Process): String {
        val buffer = CharArray(BUFFER_CHARS)
        val text = StringBuilder()
        process.inputStream.bufferedReader().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // Keep draining past the cap so the child never blocks on a full pipe.
                if (text.length < MAX_OUTPUT_CHARS) text.appendRange(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - text.length))
            }
        }
        return text.toString()
    }

    private const val BUFFER_CHARS = 8 * 1024
    private const val MAX_OUTPUT_CHARS = 1024 * 1024
    private val reader = Executors.newVirtualThreadPerTaskExecutor()
}

/**
 * Git with `GIT_OPTIONAL_LOCKS=0`, so `git status` never refreshes the index and
 * inspecting a worktree an agent is using never takes `index.lock`.
 */
class Git(private val binary: String = "git", private val timeoutMillis: Long = 10_000) {
    fun run(directory: File, vararg args: String): String? = run(directory, args.asList())

    fun run(directory: File, args: List<String>): String? =
        Processes.run(listOf(binary, "-C", directory.path) + args, timeoutMillis = timeoutMillis, environment = ENVIRONMENT)

    private companion object {
        val ENVIRONMENT = mapOf("GIT_OPTIONAL_LOCKS" to "0", "GIT_TERMINAL_PROMPT" to "0")
    }
}
