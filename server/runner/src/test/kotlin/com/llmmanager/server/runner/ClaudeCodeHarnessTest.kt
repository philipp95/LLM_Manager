package com.llmmanager.server.runner

import com.llmmanager.protocol.ProjectId
import com.llmmanager.protocol.RunId
import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.RunSpec
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

class ClaudeCodeHarnessTest {
    @TempDir
    lateinit var directory: Path

    private fun spec(timeout: Long = 10) = RunSpec(
        RunId("test"), ProjectId("test"), HarnessId.CLAUDE_CODE, "test", directory.toString(), timeoutSeconds = timeout,
    )

    @Test
    fun `content usage and sanitized output precede exactly one final outcome`() = runBlocking {
        val harness = ClaudeCodeHarness(commandPrefix = fixtureCommand("normal", directory.resolve("pid").toFile()))
        val events = withTimeout(15000) { harness.spawn(spec()).toList() }
        assertTrue(events.contains(HarnessEvent.Text("hello")))
        assertTrue(events.any { it is HarnessEvent.ToolCall })
        assertTrue(events.any { it is HarnessEvent.Usage })
        assertFalse(events.toString().contains("SYNTHETIC-KEY-MATERIAL"))
        assertFalse(events.toString().contains("syntheticCredential123"))
        assertEquals(1, events.filterIsInstance<HarnessEvent.Finished>().size)
        assertTrue(assertIs<HarnessEvent.Finished>(events.last()).ok)
    }

    @Test
    fun `timeout overrides an earlier success result`() = runBlocking {
        val harness = ClaudeCodeHarness(commandPrefix = fixtureCommand("result-hang", directory.resolve("pid").toFile()))
        val events = withTimeout(10000) { harness.spawn(spec(2)).toList() }
        val result = assertIs<HarnessEvent.Finished>(events.last())
        assertFalse(result.ok)
        assertEquals(-1, result.exitCode)
        assertTrue(result.summary!!.contains("timed out"))
    }

    @Test
    fun `cancelling collection terminates the CLI and its child`() = runBlocking {
        val marker = directory.resolve("pid").toFile()
        val child = File("$marker.child")
        val harness = ClaudeCodeHarness(commandPrefix = fixtureCommand("tree", marker))
        val job = launch { harness.spawn(spec(60)).toList() }
        try {
            withTimeout(10000) { while (!child.exists() || child.length() == 0L) delay(20) }
        } finally {
            withTimeout(8000) { job.cancelAndJoin() }
        }
        assertFalse(isRunning(marker))
        assertFalse(isRunning(child))
    }

    @Test
    fun `exit without a result is a failed run`() = runBlocking {
        val harness = ClaudeCodeHarness(commandPrefix = fixtureCommand("exit", directory.resolve("pid").toFile()))
        val result = withTimeout(10000) { harness.spawn(spec()).toList().last() }
        assertFalse(assertIs<HarnessEvent.Finished>(result).ok)
    }
}
