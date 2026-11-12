package com.llmmanager.server.runner

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Blocking, interruptible process lifetime; callers own the thread/coroutine. */
internal object ProcessRunner {
    data class Outcome(val exitCode: Int, val timedOut: Boolean)

    fun run(
        command: List<String>,
        directory: File?,
        timeoutMillis: Long,
        stdout: (String) -> Unit = {},
        stderr: (String) -> Unit = {},
    ): Outcome {
        require(command.isNotEmpty())
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return ManagedProcess(ProcessBuilder(command).directory(directory).start()).use {
            it.execute(deadline, stdout, stderr)
        }
    }

    private class ManagedProcess(private val process: Process) : AutoCloseable {
        private val readers = Executors.newVirtualThreadPerTaskExecutor()
        private val descendants = linkedMapOf<Long, ProcessHandle>()
        private val tasks = mutableListOf<Future<*>>()

        fun execute(deadline: Long, stdout: (String) -> Unit, stderr: (String) -> Unit): Outcome {
            process.outputStream.close()
            tasks += readers.submit { readLines(process.inputStream, stdout) }
            tasks += readers.submit { readLines(process.errorStream, stderr) }
            val exited = awaitExit(deadline)
            // Completed CLIs do not transfer ownership of background tools to the host.
            terminateChildren()
            val drained = exited && tasks.all { awaitReader(it, deadline - System.nanoTime()) }
            return Outcome(if (exited) process.exitValue() else -1, !drained)
        }

        private fun awaitExit(deadline: Long): Boolean {
            while (process.isAlive) {
                rememberChildren()
                val remaining = deadline - System.nanoTime()
                tasks.filter { it.isDone }.forEach { awaitReader(it, remaining) }
                if (remaining <= 0) return false
                process.waitFor(
                    minOf(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)),
                    TimeUnit.MILLISECONDS,
                )
            }
            rememberChildren()
            return true
        }

        private fun rememberChildren() {
            process.descendants().use { handles -> handles.forEach { descendants[it.pid()] = it } }
            // Retain children after the original parent exits or reparents them.
            descendants.values.toList().forEach { child ->
                child.descendants().use { handles -> handles.forEach { descendants[it.pid()] = it } }
            }
        }

        private fun terminateChildren() {
            descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
        }

        override fun close() {
            rememberChildren()
            terminateChildren()
            process.destroyForcibly()
            tasks.forEach { it.cancel(true) }
            // A blocked native read/close must never make cancellation unbounded.
            readers.submit { process.inputStream.close() }
            readers.submit { process.errorStream.close() }
            readers.shutdown()
            val interrupted = Thread.interrupted()
            try {
                awaitTermination()
            } finally {
                readers.shutdownNow()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun awaitTermination() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            val handles = descendants.values + process.toHandle()
            while (handles.any { it.isAlive } && System.nanoTime() < deadline) {
                handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
                Thread.sleep(POLL_MILLIS)
            }
            readers.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private fun readLines(stream: InputStream, consume: (String) -> Unit) {
        stream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(consume) }
    }

    // A timeout is an explicit outcome, not an output-consumer failure.
    @Suppress("SwallowedException")
    private fun awaitReader(task: Future<*>, remainingNanos: Long): Boolean = try {
        task.get(remainingNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (failure: ExecutionException) {
        throw IOException("Process output could not be consumed", failure.cause)
    }

    private const val POLL_MILLIS = 20L
}
