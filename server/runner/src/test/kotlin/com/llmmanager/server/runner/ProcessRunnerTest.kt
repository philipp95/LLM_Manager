package com.llmmanager.server.runner

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProcessRunnerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a silent process cannot block past its timeout`() {
        val marker = directory.resolve("pid").toFile()
        val started = System.nanoTime()
        val outcome = ProcessRunner.run(fixtureCommand("hang", marker), null, 1500)
        assertTrue(outcome.timedOut)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 7000)
        assertFalse(isRunning(marker))
    }

    @Test
    fun `both output pipes are drained without deadlocking`() {
        var stdout = 0
        var stderr = 0
        val outcome = ProcessRunner.run(
            fixtureCommand("flood", directory.resolve("pid").toFile()), null, 15000,
            stdout = { stdout++ }, stderr = { stderr++ },
        )
        assertFalse(outcome.timedOut)
        assertEquals(0, outcome.exitCode)
        assertEquals(20000, stdout)
        assertEquals(20000, stderr)
    }

    @Test
    fun `timeout terminates descendants holding output handles`() {
        val marker = directory.resolve("pid").toFile()
        val outcome = ProcessRunner.run(fixtureCommand("tree", marker), null, 2500)
        assertTrue(outcome.timedOut)
        assertTrue(File("$marker.child").exists())
        assertFalse(isRunning(marker))
        assertFalse(isRunning(File("$marker.child")))
    }
}

internal fun fixtureCommand(mode: String, marker: File): List<String> = listOf(
    File(System.getProperty("java.home"), "bin/java").absolutePath,
    "-cp", File(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).absolutePath,
    ProcessFixture::class.java.name, mode, marker.absolutePath,
)

internal fun isRunning(marker: File): Boolean =
    ProcessHandle.of(marker.readText().trim().toLong()).map { it.isAlive }.orElse(false)
