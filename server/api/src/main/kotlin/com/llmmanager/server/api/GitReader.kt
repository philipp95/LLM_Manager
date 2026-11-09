package com.llmmanager.server.api

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs read-only git queries with a deadline and bounded output.
 *
 * `GIT_OPTIONAL_LOCKS=0` stops `git status` from refreshing the index, so inspecting
 * a worktree an agent is using never takes `index.lock` or rewrites files. Only
 * query subcommands are issued by [ProjectScanner]; nothing here mutates a repository.
 */
class GitReader(
    private val gitBinary: String = "git",
    private val timeoutMillis: Long = 10_000,
) {
    /** Stdout of `git -C [directory] args`, or null when git fails, times out, or cannot start. */
    fun query(directory: File, vararg args: String): String? {
        val process = try {
            ProcessBuilder(listOf(gitBinary, "-C", directory.path) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also {
                    it.environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    it.environment()["GIT_TERMINAL_PROMPT"] = "0"
                }
                .start()
        } catch (_: java.io.IOException) {
            return null
        }
        process.outputStream.close()
        val output = reader.submit<String> { readBounded(process) }
        return try {
            val exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (exited && process.exitValue() == 0) output.get(timeoutMillis, TimeUnit.MILLISECONDS) else null
        } catch (_: java.util.concurrent.TimeoutException) {
            null
        } catch (_: java.util.concurrent.ExecutionException) {
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
                // Keep draining past the cap so git never blocks on a full pipe.
                if (text.length < MAX_OUTPUT_CHARS) text.appendRange(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - text.length))
            }
        }
        return text.toString()
    }

    private companion object {
        const val BUFFER_CHARS = 8 * 1024
        const val MAX_OUTPUT_CHARS = 1024 * 1024
        val reader = Executors.newVirtualThreadPerTaskExecutor()
    }
}
