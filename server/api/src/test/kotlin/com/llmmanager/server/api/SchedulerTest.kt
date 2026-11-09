package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * Drives real git worktrees and the real wrapper script, with fake `claude` and
 * `codex` executables that emit the agents' event formats and edit files.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "runs bash wrappers and shell-script fake agents, as the Linux server does")
class SchedulerTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** Runs each session's command to completion right away; no session stays alive. */
    private class ImmediateSessions : SessionHost {
        val started = mutableListOf<String>()
        override fun list(): Set<String> = emptySet()
        override fun start(name: String, directory: File, command: List<String>): Boolean {
            started += name
            val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            return process.waitFor(60, TimeUnit.SECONDS)
        }
    }

    private val clock = MutableClock(Instant.parse("2026-11-09T12:00:00Z"))

    private fun script(name: String, body: String): String =
        File(root, "bin/$name").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env bash\n$body\n"); setExecutable(true) }.path

    /** Fake Claude: stream-json events; edits a file on work and fix; "LIMIT" in the prompt hits the plan limit. */
    private fun fakeClaude() = script(
        "claude",
        """
        prompt=${'$'}(cat)
        sid=""; prev=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--session-id" ] || [ "${'$'}prev" = "--resume" ]; then sid="${'$'}a"; fi
          prev="${'$'}a"
        done
        echo "${'$'}prompt" >> "${'$'}PWD/../claude-prompts.log"
        echo '{"type":"system","subtype":"init","session_id":"'"${'$'}sid"'"}'
        if [[ "${'$'}prompt" == *LIMIT* ]] && [[ "${'$'}*" != *--resume* ]]; then
          echo '{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1794232800,"rateLimitType":"five_hour"}}'
          echo '{"type":"result","is_error":true,"result":"Claude usage limit reached","session_id":"'"${'$'}sid"'"}'
          exit 1
        fi
        echo "change ${'$'}RANDOM" >> claude-work.txt
        echo '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"claude-work.txt"}}]}}'
        echo '{"type":"result","is_error":false,"result":"Implemented it.","session_id":"'"${'$'}sid"'"}'
        """.trimIndent(),
    )

    /** Fake Gemini: stream-json events; edits a file when not in plan (review) mode. */
    private fun fakeGemini() = script(
        "gemini",
        """
        prompt=${'$'}(cat)
        sid=""; prev=""; mode=""
        for a in "${'$'}@"; do
          if [ "${'$'}prev" = "--session-id" ] || [ "${'$'}prev" = "--resume" ]; then sid="${'$'}a"; fi
          if [ "${'$'}prev" = "--approval-mode" ]; then mode="${'$'}a"; fi
          prev="${'$'}a"
        done
        echo '{"type":"init","session_id":"'"${'$'}sid"'","model":"gemini-test"}'
        if [ "${'$'}mode" != "plan" ]; then echo "gemini ${'$'}RANDOM" >> gemini-work.txt; fi
        echo '{"type":"message","role":"assistant","content":"Gemini done.","delta":true}'
        echo '{"type":"result","status":"success","stats":{"input_tokens":10,"output_tokens":5,"cached":0,"models":{}}}'
        """.trimIndent(),
    )

    /** Fake Codex: `review` prints findings; `exec` emits JSON events. */
    private fun fakeCodex() = script(
        "codex",
        """
        cat > /dev/null
        if [ "${'$'}1" = "review" ]; then echo "1. claude-work.txt: missing trailing newline check"; exit 0; fi
        echo '{"type":"thread.started","thread_id":"codex-thread-1"}'
        echo "codex ${'$'}RANDOM" >> codex-work.txt
        echo '{"type":"item.completed","item":{"type":"agent_message","text":"Done."}}'
        echo '{"type":"turn.completed"}'
        """.trimIndent(),
    )

    private data class Fixture(val scheduler: Scheduler, val jobs: JobService, val store: StateStore, val repo: File, val projectId: String)

    private fun fixture(): Fixture {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("app\n")
        git(repo, "add", "README.md")
        git(repo, "-c", "user.name=Person", "-c", "user.email=person@example.invalid", "commit", "-m", "initial")
        val sessions = ImmediateSessions()
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"), sessions = sessions)
        val store = StateStore(File(root, "state"))
        val commands = AgentCommands(fakeClaude(), fakeCodex(), gemini = fakeGemini())
        val workspace = Workspace(scanner, sessions, commands)
        val engine = RunEngine(store, workspace, File(root, "runs"), clock = clock)
        val scheduler = Scheduler(store, engine, sessions, clock = clock)
        val jobs = JobService(store, scheduler, workspace, SessionCatalog(File(root, "home")), clock)
        return Fixture(scheduler, jobs, store, repo, scanner.scan().single().id)
    }

    @Test
    fun `pipeline builds, gets cross-reviewed, and the builder addresses the review in its own session`() {
        val f = fixture()

        val created = f.jobs.create(JobRequest(f.projectId, JobKind.PIPELINE, Agent.CLAUDE, "Add a greeting"))
        repeat(6) { f.scheduler.tick() }

        assertTrue(created is JobService.Created.Ok)
        val runs = f.store.runs().sortedBy { it.createdAt + it.id }
        assertEquals(listOf(Stage.WORK, Stage.REVIEW, Stage.FIX), runs.map { it.stage }.sortedBy { it.ordinal })
        assertTrue(runs.all { it.status == RunStatus.SUCCEEDED }, runs.joinToString { "${it.stage}:${it.status}:${it.error}" })
        val work = runs.single { it.stage == Stage.WORK }
        val review = runs.single { it.stage == Stage.REVIEW }
        val fix = runs.single { it.stage == Stage.FIX }
        assertEquals(Agent.CODEX, review.agent)
        assertEquals(work.worktree, review.worktree)
        assertEquals(work.resultCommit, review.resultCommit)
        assertTrue(review.summary!!.contains("missing trailing newline"))
        // The fix continues the builder's session, with the review in its prompt.
        assertEquals(Agent.CLAUDE, fix.agent)
        assertEquals(work.sessionId, fix.sessionId)
        assertTrue(fix.prompt.contains("Codex reviewed your change") && fix.prompt.contains("missing trailing newline"))
        // Two agent commits on the run branch, none on the person's branch, none authored by a person.
        val worktree = File(work.worktree!!)
        assertEquals("Claude via LLM Manager\nClaude via LLM Manager\nPerson", output(worktree, "log", "--format=%an", "-3"))
        assertTrue(work.branch!!.startsWith("run/app-"))
        assertEquals("initial", output(f.repo, "log", "--format=%s", "-1"))
    }

    @Test
    fun `gemini builds and the chosen reviewer reviews`() {
        val f = fixture()

        f.jobs.create(JobRequest(f.projectId, JobKind.PIPELINE, Agent.GEMINI, "Add a gemini file", reviewer = Agent.CLAUDE))
        repeat(6) { f.scheduler.tick() }

        val runs = f.store.runs().sortedBy { it.stage.ordinal }
        assertEquals(listOf(Stage.WORK to Agent.GEMINI, Stage.REVIEW to Agent.CLAUDE, Stage.FIX to Agent.GEMINI), runs.map { it.stage to it.agent })
        assertTrue(runs.all { it.status == RunStatus.SUCCEEDED }, runs.joinToString { "${it.stage}:${it.error}" })
        val work = runs.first()
        assertTrue(UUID_PATTERN.matches(work.sessionId!!), work.sessionId)
        assertEquals(work.sessionId, runs.last().sessionId)
        assertTrue(runs.last().prompt.startsWith("Claude reviewed your change"))
        assertEquals("Gemini via LLM Manager", output(File(work.worktree!!), "log", "--format=%an", "-1"))
        val invalid = f.jobs.create(JobRequest(f.projectId, JobKind.PIPELINE, Agent.GEMINI, "x", reviewer = Agent.GEMINI))
        assertEquals("Choose a different agent to review", (invalid as JobService.Created.Invalid).reason)
    }

    @Test
    fun `a run stopped by a usage limit resumes in the same session after the reset`() {
        val f = fixture()

        f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CLAUDE, "Big task LIMIT"))
        f.scheduler.tick()

        val limited = f.store.runs().single { it.status == RunStatus.LIMITED }
        assertEquals(RunStatus.LIMITED, limited.status)
        assertEquals(Instant.ofEpochSecond(1794232800).toString(), limited.limitResetsAt)
        val queued = f.store.runs().single { it.status == RunStatus.QUEUED }
        assertEquals(Stage.RESUME, queued.stage)
        assertEquals(limited.sessionId, queued.sessionId)
        assertEquals(Instant.ofEpochSecond(1794232800).plusSeconds(120).toString(), queued.notBefore)

        f.scheduler.tick() // before the reset: stays queued
        assertEquals(RunStatus.QUEUED, f.store.run(queued.id)!!.status)

        clock.now = Instant.ofEpochSecond(1794232800).plus(Duration.ofMinutes(3))
        f.scheduler.tick() // starts the resume
        f.scheduler.tick() // collects its result
        val resumed = f.store.run(queued.id)!!
        assertEquals(RunStatus.SUCCEEDED, resumed.status, resumed.error)
        assertEquals(limited.worktree, resumed.worktree)
        assertNotNull(resumed.resultCommit)
    }

    @Test
    fun `a run that fails unexpectedly is marked failed and the scheduler keeps going`() {
        val f = fixture()
        // A resume without a session cannot be started: building its command throws inside the engine.
        f.store.putRun(
            Run("0123456789ab", null, f.projectId, "app", Agent.CLAUDE, Stage.RESUME, RunStatus.QUEUED, "continue",
                worktree = f.repo.path, createdAt = "2026-11-09T11:00:00Z"),
        )
        f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CODEX, "Still runs"))

        repeat(3) { f.scheduler.tick() }

        val broken = f.store.run("0123456789ab")!!
        assertEquals(RunStatus.FAILED, broken.status)
        assertTrue(broken.error!!.startsWith("Internal error"), broken.error)
        assertEquals(RunStatus.SUCCEEDED, f.store.runs().single { it.agent == Agent.CODEX }.status)
    }

    @Test
    fun `daily jobs fire once per slot and invalid requests are refused`() {
        val f = fixture()
        val daily = f.jobs.create(
            JobRequest(f.projectId, JobKind.RUN, Agent.CODEX, "Nightly", schedule = Schedule(time = "13:00", zone = "UTC")),
        ) as JobService.Created.Ok
        f.scheduler.tick()
        assertTrue(f.store.runs().isEmpty(), "not due before 13:00")

        clock.now = Instant.parse("2026-11-09T13:00:30Z")
        f.scheduler.tick()
        f.scheduler.tick()
        assertEquals(1, f.store.runs().size)
        assertEquals(RunStatus.SUCCEEDED, f.store.runs().single().status, f.store.runs().single().error)
        assertEquals("2026-11-10T13:00:00Z", f.jobs.overview().jobs.single { it.job.id == daily.job.id }.nextRunAt)

        fun invalid(request: JobRequest) = (f.jobs.create(request) as JobService.Created.Invalid).reason
        assertTrue(invalid(JobRequest("nope", JobKind.RUN, Agent.CLAUDE, "x")).contains("Unknown project"))
        assertTrue(invalid(JobRequest(f.projectId, JobKind.RUN, Agent.CLAUDE, " ")).contains("Describe"))
        assertTrue(invalid(JobRequest(f.projectId, JobKind.RUN, Agent.CLAUDE, "x", schedule = Schedule(time = "25:00", zone = "UTC"))).contains("time"))
        val stranger = SessionRef(Agent.CLAUDE, "00000000-0000-0000-0000-000000000000", f.repo.path)
        assertTrue(invalid(JobRequest(f.projectId, JobKind.RESUME, Agent.CLAUDE, "", resume = stranger)).contains("not resumable"))
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f-]{36}")
    }

    private fun git(dir: File, vararg args: String) {
        output(dir, *args)
    }

    private fun output(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $text" }
        return text.trim()
    }
}
