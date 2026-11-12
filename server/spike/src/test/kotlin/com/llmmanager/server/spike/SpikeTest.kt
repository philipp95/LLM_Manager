package com.llmmanager.server.spike

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.llmmanager.protocol.RunUsage
import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.RunSpec
import com.llmmanager.server.runner.AgentHarness
import com.llmmanager.server.runner.HarnessEvent
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.io.TempDir

class SpikeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `failed results unwind cleanup before returning an exit code`() {
        val root = setupRepository()
        val error = assertFailsWith<ProgramResult> {
            Spike(fakeHarness()).parse(arrayOf("--repo", root.path, "--prompt", "test", "--work-root", directory.resolve("managed").toString()))
        }
        assertEquals(1, error.statusCode)
        assertTrue(directory.resolve("managed/worktrees").toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `keep worktree also applies when the run fails`() {
        val root = setupRepository()
        assertFailsWith<ProgramResult> {
            Spike(fakeHarness()).parse(arrayOf(
                "--repo", root.path, "--prompt", "test", "--work-root", directory.resolve("managed").toString(),
                "--keep-worktree", "true",
            ))
        }
        assertEquals(1, directory.resolve("managed/worktrees").toFile().listFiles().orEmpty().size)
    }

    @Test
    fun `exceptions during streaming also clean up the worktree`() {
        val root = setupRepository()
        assertFailsWith<IllegalStateException> {
            Spike(fakeHarness(throws = true)).parse(arrayOf("--repo", root.path, "--prompt", "test", "--work-root", directory.resolve("managed").toString()))
        }
        assertTrue(directory.resolve("managed/worktrees").toFile().listFiles().orEmpty().isEmpty())
    }

    private fun fakeHarness(throws: Boolean = false) = object : AgentHarness {
        override val id = HarnessId.CLAUDE_CODE
        override suspend fun isAvailable() = true
        override fun spawn(spec: RunSpec): Flow<HarnessEvent> = flow {
            File(spec.worktreePath, "dirty.txt").writeText("test")
            if (throws) error("synthetic failure")
            emit(HarnessEvent.Finished(false, "failed", RunUsage(), 1, null, null))
        }
    }

    private fun setupRepository(): File {
        val root = directory.resolve("source").toFile().also { it.mkdirs() }
        for (args in listOf(
            listOf("init", "-b", "main"),
            listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "initial"),
        )) {
            val process = ProcessBuilder(listOf("git") + args).directory(root)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
            } finally {
                process.destroyForcibly()
            }
        }
        return root
    }
}
