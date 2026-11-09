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
            Job(
                id = "j", name = "n", projectId = "p", projectName = "p", kind = JobKind.RUN, agent = Agent.CLAUDE, prompt = "x",
                schedule = schedule, createdAt = created, lastFiredAt = last, done = done,
            )
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
    fun `gemini edits under its policy, reviews in plan mode, and reads the prompt from stdin`() {
        val commands = AgentCommands("/bin/claude", "/bin/codex", gemini = "/bin/gemini", geminiPolicyFile = "/state/gemini-policy.toml")
        val expected = listOf("/bin/gemini", "--output-format", "stream-json", "--skip-trust", "--session-id", "sid") +
            listOf("--approval-mode", "auto_edit", "--policy", "/state/gemini-policy.toml")
        assertEquals(expected, commands.work(Agent.GEMINI, "sid"))
        assertTrue(commands.resume(Agent.GEMINI, "sid").containsAll(listOf("--resume", "sid", "auto_edit")))
        val review = commands.review(Agent.GEMINI, "abc")
        assertTrue("plan" in review && "auto_edit" !in review && "--policy" !in review)
        assertTrue(listOf(commands.work(Agent.GEMINI, "s"), review).none { args -> args.any { it == "-p" || it == "--prompt" || it == "--yolo" } })

        val policy = AgentCommands.geminiPolicy(listOf("Read", "Bash(./gradlew:*)", "Bash(git \"x\":*)"))
        assertEquals(2, Regex("""\[\[rule]]""").findAll(policy).count())
        assertTrue(policy.contains("commandPrefix = \"./gradlew\"") && policy.contains("commandPrefix = \"git \\\"x\\\"\""))
        assertTrue(policy.contains("decision = \"allow\"") && !policy.contains("yolo"))
    }

    @Test
    fun `gemini stream gives the session, the final answer, and quota limits`() {
        val dir = directory.toFile()
        val ok = File(dir, "gemini.jsonl").apply {
            writeText(
                listOf(
                    """{"type":"init","session_id":"g-1","model":"gemini-3-pro"}""",
                    """{"type":"message","role":"user","content":"task"}""",
                    """{"type":"message","role":"assistant","content":"Let me look","delta":true}""",
                    """{"type":"tool_use","tool_name":"run_shell_command","tool_id":"t1","parameters":{"command":"./gradlew test"}}""",
                    """{"type":"tool_result","tool_id":"t1","status":"success","output":"ok"}""",
                    """{"type":"message","role":"assistant","content":"All ","delta":true}""",
                    """{"type":"message","role":"assistant","content":"done.","delta":true}""",
                    """{"type":"result","status":"success","stats":{"total_tokens":10}}""",
                ).joinToString("\n"),
            )
        }
        val outcome = RunOutput.outcome(Agent.GEMINI, Stage.WORK, ok, File(dir, "none"), 0)
        assertEquals("g-1", outcome.sessionId)
        assertEquals("All done.", outcome.summary)
        assertEquals(listOf("text", "tool", "text", "done"), RunActivity.activity(Agent.GEMINI, Stage.WORK, ok).map { it.kind })
        assertEquals("run_shell_command: ./gradlew test", RunActivity.activity(Agent.GEMINI, Stage.WORK, ok)[1].text)

        val quota = File(dir, "quota.jsonl").apply {
            writeText(
                """{"type":"init","session_id":"g-2"}""" + "\n" +
                    """{"type":"result","status":"error","error":{"type":"API","message":"[429] RESOURCE_EXHAUSTED: quota exceeded"}}""",
            )
        }
        val limited = RunOutput.outcome(Agent.GEMINI, Stage.WORK, quota, File(dir, "none"), 1)
        assertTrue(limited.limited)
        assertEquals("g-2", limited.sessionId)
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
