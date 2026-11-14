package com.llmmanager.server.api

import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/** Runs short-lived helper commands (git, tmux, gh) with a deadline and bounded output. */
object Processes {
    private val log = LoggerFactory.getLogger(Processes::class.java)
    private val lastStartFailure = AtomicLong(0)
    /** Outcome of [exec]; exitCode is -1 when the command could not start or timed out. */
    data class Exec(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Like [run], but keeps stderr and the exit code so failures can be explained to the user.
     * With [inheritEnvironment] false the child gets only [environment]. [input] goes to stdin,
     * which keeps secrets such as tokens off the command line.
     */
    fun exec(
        command: List<String>,
        timeoutMillis: Long = 60_000,
        environment: Map<String, String> = emptyMap(),
        inheritEnvironment: Boolean = true,
        input: String? = null,
    ): Exec {
        val process = try {
            ProcessBuilder(command).also {
                if (!inheritEnvironment) it.environment().clear()
                it.environment().putAll(environment)
            }.start()
        } catch (e: IOException) {
            startFailed(command, e)
            return Exec(-1, "", e.message ?: "could not start ${command.first()}")
        }
        write(process, input)
        val out = reader.submit<String> { readBounded(process.inputStream) }
        val err = reader.submit<String> { readBounded(process.errorStream) }
        return try {
            val exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            val stdout = out.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val stderr = err.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (exited) Exec(process.exitValue(), stdout, stderr) else Exec(-1, stdout, "timed out")
        } catch (_: TimeoutException) {
            Exec(-1, "", "timed out")
        } catch (_: ExecutionException) {
            Exec(-1, "", "output could not be read")
        } finally {
            process.destroyForcibly()
        }
    }

    /** Stdout when the command exits 0 in time; null when it fails, times out, or cannot start. */
    fun run(command: List<String>, directory: File? = null, timeoutMillis: Long = 10_000, environment: Map<String, String> = emptyMap()): String? {
        val process = try {
            ProcessBuilder(command).directory(directory)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment().putAll(environment) }
                .start()
        } catch (e: IOException) {
            startFailed(command, e)
            return null
        }
        process.outputStream.close()
        val output = reader.submit<String> { readBounded(process.inputStream) }
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

    /** Sends [input] (if any) and closes stdin; a child that exits without reading it is not an error. */
    private fun write(process: Process, input: String?) {
        try {
            process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
        } catch (_: IOException) {
            // The child closed its stdin early; its exit code says whether that mattered.
        }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val buffer = CharArray(BUFFER_CHARS)
        val text = StringBuilder()
        stream.bufferedReader().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // Keep draining past the cap so the child never blocks on a full pipe.
                if (text.length < MAX_OUTPUT_CHARS) text.appendRange(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - text.length))
            }
        }
        return text.toString()
    }

    /** Logged at most once a minute: a server that cannot start git shows no repositories at all. */
    private fun startFailed(command: List<String>, e: IOException) {
        val now = System.currentTimeMillis()
        val last = lastStartFailure.get()
        if (now - last >= MINUTE_MS && lastStartFailure.compareAndSet(last, now)) log.warn("Could not start {}: {}", command.first(), e.message)
    }

    private const val MINUTE_MS = 60_000L
    private const val BUFFER_CHARS = 8 * 1024
    private const val MAX_OUTPUT_CHARS = 1024 * 1024
    private val reader = Executors.newVirtualThreadPerTaskExecutor()
}

/**
 * Git with `GIT_OPTIONAL_LOCKS=0`, so `git status` never refreshes the index and
 * inspecting a worktree an agent is using never takes `index.lock`.
 *
 * Every call also overrides the settings through which repository config can make git
 * run programs: fsmonitor, hooks, signature verification and signing, and the `ext::`
 * transport. Agents can write to their worktrees, and repositories in the scanned
 * folders may come from anywhere, so their config is not trusted to execute anything
 * in this process. Filter drivers are the remaining path; see [WorktreeGuard].
 */
class Git(private val binary: String = "git", private val timeoutMillis: Long = 10_000) {
    fun run(directory: File, vararg args: String): String? = run(directory, args.asList())

    fun run(directory: File, args: List<String>): String? =
        Processes.run(command(directory, args), timeoutMillis = timeoutMillis, environment = ENVIRONMENT)

    /** For network operations: keeps the error text, and accepts extra environment such as `GIT_CONFIG_*` auth. */
    fun exec(directory: File, args: List<String>, environment: Map<String, String> = emptyMap()): Processes.Exec =
        Processes.exec(command(directory, args), timeoutMillis = NETWORK_TIMEOUT_MS, environment = ENVIRONMENT + environment)

    private fun command(directory: File, args: List<String>) = listOf(binary, "-C", directory.path) + HARDENING + args

    companion object {
        private val ENVIRONMENT = mapOf("GIT_OPTIONAL_LOCKS" to "0", "GIT_TERMINAL_PROMPT" to "0")
        private const val NETWORK_TIMEOUT_MS = 120_000L

        /** Command-line config wins over every config file, including a substituted one. */
        val HARDENING = listOf(
            "core.fsmonitor=false", "core.hooksPath=/dev/null", "log.showSignature=false",
            "commit.gpgSign=false", "tag.gpgSign=false", "protocol.ext.allow=never",
        ).flatMap { listOf("-c", it) }
    }
}
