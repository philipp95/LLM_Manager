package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RunPartsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `schedule times`() {
        val created = "2026-11-09T08:00:00Z"
        fun job(schedule: Schedule, last: String? = null, done: Boolean = false) =
            Job("j", "n", "p", "p", JobKind.RUN, Agent.CLAUDE, "x", schedule, createdAt = created, lastFiredAt = last, done = done)
        val weekdays = Schedule(time = "09:30", days = listOf(1, 2, 3, 4, 5), zone = "Europe/Brussels")
        // Monday 9 Nov 2026: 09:30 Brussels is 08:30Z.
        assertEquals(Instant.parse("2026-11-09T08:30:00Z"), ScheduleTimes.next(job(weekdays), Instant.parse("2026-11-09T08:00:00Z")))
        // Friday after the slot → next Monday.
        assertEquals(Instant.parse("2026-11-16T08:30:00Z"), ScheduleTimes.next(job(weekdays), Instant.parse("2026-11-13T09:00:00Z")))
        assertFalse(ScheduleTimes.isDue(job(weekdays), Instant.parse("2026-11-09T08:29:59Z")))
        assertTrue(ScheduleTimes.isDue(job(weekdays), Instant.parse("2026-11-09T08:30:00Z")))
        assertFalse(ScheduleTimes.isDue(job(weekdays, last = "2026-11-09T08:30:05Z"), Instant.parse("2026-11-09T10:00:00Z")))
        val once = Schedule(at = "2026-11-09T20:00:00Z")
        assertTrue(ScheduleTimes.isDue(job(once), Instant.parse("2026-11-10T07:00:00Z")), "missed one-off fires late")
        assertFalse(ScheduleTimes.isDue(job(once, done = true), Instant.parse("2026-11-10T07:00:00Z")))
        assertNull(ScheduleTimes.next(job(once, done = true), Instant.parse("2026-11-09T00:00:00Z")))
        assertEquals("Unknown time zone", ScheduleTimes.problem(Schedule(time = "09:00", zone = "Mars/Base")))
        assertEquals("Choose when the job runs", ScheduleTimes.problem(Schedule()))
        assertNull(ScheduleTimes.problem(weekdays))
    }

    @Test
    fun `commands pin permissions and never put the prompt on the command line`() {
        val commands = AgentCommands("/bin/claude", "/bin/codex")
        val work = commands.work(Agent.CLAUDE, "sid")
        assertEquals(
            listOf("/bin/claude", "-p", "--output-format", "stream-json", "--verbose", "--session-id", "sid", "--permission-mode", "acceptEdits"),
            work.take(9),
        )
        assertTrue("Bash(./gradlew:*)" in work && work.none { it.startsWith("Bash(rm") || it == "Bash" })
        assertEquals(listOf("/bin/codex", "exec", "--json", "--sandbox", "workspace-write", "resume", "t1", "-"), commands.resume(Agent.CODEX, "t1"))
        val review = commands.review(Agent.CLAUDE, "abc")
        assertTrue("default" in review && "Edit" !in review && "Write" !in review)
        assertTrue("sandbox_mode=\"read-only\"" in commands.review(Agent.CODEX, "abc"))
        assertFalse(AgentCommands(null, null).available(Agent.CLAUDE))
    }

    @Test
    fun `wrapper script quotes hostile paths`() {
        val weird = directory.resolve("it's \$(touch pwned) dir").toFile().also { it.mkdirs() }
        val runDir = directory.resolve("run").toFile().also { it.mkdirs() }
        File(runDir, "prompt.txt").writeText("hello; rm -rf /")
        val script = File(runDir, "run.sh").apply { writeText(RunEngine.wrapper(weird, runDir, listOf("cat"))) }

        val process = ProcessBuilder("bash", script.path).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))

        assertEquals("hello; rm -rf /", File(runDir, "events.jsonl").readText())
        assertEquals("0", File(runDir, "exit").readText().trim())
        assertFalse(File(weird, "pwned").exists())
        assertFalse(directory.resolve("pwned").toFile().exists())
    }

    @Test
    fun `event streams are parsed and secrets redacted`() {
        val dir = directory.toFile()
        val claude = File(dir, "claude.jsonl").apply {
            writeText(
                listOf(
                    """{"type":"system","subtype":"init","session_id":"s-1"}""",
                    """{"type":"assistant","message":{"content":[""" +
                        """{"type":"text","text":"Using key sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"},""" +
                        """{"type":"tool_use","name":"Bash","input":{"command":"./gradlew test"}}]}}""",
                    """{"type":"result","is_error":false,"result":"All done","session_id":"s-1"}""",
                ).joinToString("\n"),
            )
        }
        val outcome = RunOutput.outcome(Agent.CLAUDE, Stage.WORK, claude, File(dir, "none"), 0)
        assertEquals("s-1", outcome.sessionId)
        assertEquals("All done", outcome.summary)
        assertFalse(outcome.limited)
        val activity = RunActivity.activity(Agent.CLAUDE, Stage.WORK, claude)
        assertEquals(listOf("text", "tool", "done"), activity.map { it.kind })
        assertFalse(activity.first().text.contains("abcdefghijklmnop"), activity.first().text)
        assertEquals("Bash: ./gradlew test", activity[1].text)

        val codex = File(dir, "codex.jsonl").apply {
            writeText(
                """{"type":"thread.started","thread_id":"t-9"}""" + "\n" +
                    """{"type":"turn.failed","error":{"message":"You've hit your usage limit. Try again later."}}""",
            )
        }
        val failed = RunOutput.outcome(Agent.CODEX, Stage.WORK, codex, File(dir, "none"), 1)
        assertEquals("t-9", failed.sessionId)
        assertTrue(failed.limited)
    }
}
