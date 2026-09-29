package com.llmmanager.server.runner

import com.llmmanager.protocol.RunUsage
import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.PermissionMode
import com.llmmanager.server.domain.RunSpec
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible

/** Runs Claude Code headless. Each collection owns its parser, redaction state, and process tree. */
class ClaudeCodeHarness(
    private val binary: String = "claude",
    private val redactor: SecretRedactor = SecretRedactor(),
    private val commandPrefix: List<String> = listOf(binary),
) : AgentHarness {
    override val id = HarnessId.CLAUDE_CODE

    override suspend fun isAvailable(): Boolean = try {
        runInterruptible(Dispatchers.IO) {
            val outcome = ProcessRunner.run(commandPrefix + "--version", null, 20_000)
            !outcome.timedOut && outcome.exitCode == 0
        }
    } catch (_: IOException) {
        false
    }

    override fun spawn(spec: RunSpec): Flow<HarnessEvent> = channelFlow {
        require(spec.timeoutSeconds in 1..MAX_TIMEOUT_SECONDS)
        val parser = StreamJsonParser(redactor)
        val stderr = redactor.newStream()
        val result = AtomicReference<HarnessEvent.Finished?>()
        val command = buildList {
            addAll(commandPrefix)
            add("-p")
            add(spec.prompt)
            addAll(listOf("--output-format", "stream-json", "--verbose", "--permission-mode"))
            add(when (spec.permissionMode) {
                PermissionMode.ACCEPT_EDITS -> "acceptEdits"
                PermissionMode.BYPASS -> "bypassPermissions"
                PermissionMode.PLAN -> "plan"
            })
            spec.model?.let { addAll(listOf("--model", it)) }
        }
        val outcome = runInterruptible(Dispatchers.IO) {
            ProcessRunner.run(
                command, File(spec.worktreePath), spec.timeoutSeconds * 1000,
                stdout = { line ->
                    for (event in parser.parseLine(line)) {
                        if (event is HarnessEvent.Finished) {
                            result.set(event)
                        } else {
                            // Blocking sends apply backpressure; cancellation interrupts these readers.
                            trySendBlocking(event).getOrThrow()
                        }
                    }
                },
                stderr = { line -> trySendBlocking(HarnessEvent.Stderr(stderr.redact(line))).getOrThrow() },
            )
        }
        val reported = result.get()
        val ok = !outcome.timedOut && outcome.exitCode == 0 && reported?.ok == true
        send(
            HarnessEvent.Finished(
                ok = ok,
                summary = if (outcome.timedOut) "timed out after ${spec.timeoutSeconds}s" else reported?.summary,
                usage = reported?.usage ?: RunUsage(),
                exitCode = if (outcome.timedOut) -1 else outcome.exitCode,
                durationMs = reported?.durationMs,
                sessionId = reported?.sessionId,
            ),
        )
    }

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 7 * 24 * 60 * 60L
    }
}
