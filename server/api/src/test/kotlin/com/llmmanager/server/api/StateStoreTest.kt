package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class StateStoreTest {
    @TempDir
    lateinit var directory: Path

    private fun run(n: Int) = Run(
        id = "%012x".format(n), jobId = null, projectId = "p", projectName = "p", agent = Agent.CLAUDE, stage = Stage.WORK,
        status = RunStatus.SUCCEEDED, prompt = "x", createdAt = "2026-11-09T10:%02d:%02dZ".format(n / 60 % 60, n % 60),
    )

    @Test
    fun `pruned runs take their directories with them`() {
        val store = StateStore(directory.resolve("state").toFile())
        (0..StateStore.MAX_RUNS).forEach { n ->
            store.putRun(run(n))
            File(store.runDir(run(n).id), "events.jsonl").writeText("{}")
        }

        assertEquals(StateStore.MAX_RUNS, store.runs().size)
        assertFalse(File(directory.toFile(), "state/runs/${run(0).id}").exists(), "oldest run's files are gone")
        assertTrue(File(directory.toFile(), "state/runs/${run(StateStore.MAX_RUNS).id}/events.jsonl").exists())
    }

    @Test
    fun `orphaned run directories are pruned and run ids cannot escape`() {
        val state = directory.resolve("state").toFile()
        val store = StateStore(state)
        val orphan = File(state, "runs/0000deadbeef").also { it.mkdirs() }
        val unrelated = File(state, "runs/notes").also { it.mkdirs() }

        store.pruneRunDirs()

        assertFalse(orphan.exists())
        assertTrue(unrelated.exists(), "only run-id directories are ever deleted")
        listOf("../escape", "abc", "0123456789ab/../x").forEach { assertFailsWith<IllegalArgumentException>(it) { store.runDir(it) } }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX permissions")
    fun `the state directory is private, and an existing open one is tightened`() {
        val fresh = directory.resolve("fresh").toFile()
        StateStore(fresh)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh.toPath())))

        val open = directory.resolve("open").toFile().also { it.mkdirs() }
        Files.setPosixFilePermissions(open.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        StateStore(open)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(open.toPath())))
    }
}
