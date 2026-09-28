package com.llmmanager.server.runner

import com.llmmanager.protocol.RunUsage
import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.PermissionMode
import com.llmmanager.server.domain.RunSpec
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs Claude Code headless in a worktree and normalises its JSONL output.
 *
 * Verified against claude-code 2.1.220.
 */
class ClaudeCodeHarness(
    private val binary: String = "claude",
    private val redactor: SecretRedactor = SecretRedactor(),
) : AgentHarness {

    override val id = HarnessId.CLAUDE_CODE

    private val parser = StreamJsonParser()

    private val PermissionMode.cliValue: String
        get() = when (this) {
            PermissionMode.ACCEPT_EDITS -> "acceptEdits"
            PermissionMode.BYPASS -> "bypassPermissions"
            PermissionMode.PLAN -> "plan"
        }

    override suspend fun isAvailable(): Boolean = runCatching {
        val p = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else p.exitValue() == 0
    }.getOrDefault(false)

    override fun spawn(spec: RunSpec): Flow<HarnessEvent> = callbackFlow {
        val command = buildList {
            add(binary)
            add("-p"); add(spec.prompt)
            add("--output-format"); add("stream-json")
            add("--verbose")
            // Without this a headless run stalls: the default mode prompts for
            // approval and there is no one to answer.
            add("--permission-mode"); add(spec.permissionMode.cliValue)
            spec.model?.let { add("--model"); add(it) }
        }

        val process = ProcessBuilder(command)
            .directory(File(spec.worktreePath))
            .redirectErrorStream(false)
            .start()

        // Close stdin: without it the CLI waits for input that never arrives and
        // the run hangs instead of failing.
        runCatching { process.outputStream.close() }

        var sawFinished = false

        val stdoutJob = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    if (!isActive) break
                    val line = redactor.redact(raw)
                    val event = parser.parseLine(line) ?: continue
                    if (event is HarnessEvent.Finished) sawFinished = true
                    // send(), not trySend(): dropping a usage or result event
                    // silently corrupts the run record.
                    send(event)
                }
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    if (!isActive) break
                    send(HarnessEvent.Stderr(redactor.redact(raw)))
                }
            }
        }

        val waiter = launch(Dispatchers.IO) {
            val finishedInTime = process.waitFor(spec.timeoutSeconds, TimeUnit.SECONDS)
            if (!finishedInTime) process.destroyForcibly()
            stdoutJob.join()
            stderrJob.join()

            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            // The result line is the authoritative outcome. Only synthesise one
            // when the process died without emitting it, so a crash or timeout
            // still terminates the flow with a recorded failure.
            if (!sawFinished) {
                send(
                    HarnessEvent.Finished(
                        ok = false,
                        summary = if (finishedInTime) null else "timed out after ${spec.timeoutSeconds}s",
                        usage = RunUsage(),
                        exitCode = exit,
                        durationMs = null,
                        sessionId = null,
                    )
                )
            }
            close()
        }

        awaitClose {
            // Cancellation must kill the process. A detached agent keeps spending.
            process.destroyForcibly()
            waiter.cancel(); stdoutJob.cancel(); stderrJob.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
