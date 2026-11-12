package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private val clock = MutableClock(Instant.parse("2026-11-09T12:00:00Z"))
    private val agents by lazy { FakeAgents(root) }

    private data class Fixture(val scheduler: Scheduler, val jobs: JobService, val store: StateStore, val repo: File, val projectId: String)

    private fun fixture(
        publisher: (Run) -> String = { "Publishing is not configured" },
        committer: Profile? = null,
        choices: AgentChoices = AgentChoices(),
    ): Fixture {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("app\n")
        git(repo, "add", "README.md")
        git(repo, "-c", "user.name=Person", "-c", "user.email=person@example.invalid", "commit", "-m", "initial")
        val sessions = ImmediateSessions()
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"), sessions = sessions)
        val store = StateStore(File(root, "state"))
        val commands = AgentCommands(agents.claude(), agents.codex(), gemini = agents.gemini())
        val workspace = Workspace(scanner, sessions, commands)
        val engine = RunEngine(store, workspace, File(root, "runs"), clock = clock, committer = { committer })
        val scheduler = Scheduler(store, engine, sessions, clock = clock, hooks = FollowUpHooks(publisher = publisher))
        val jobs = JobService(store, scheduler, workspace, SessionCatalog(File(root, "home")), clock, choices)
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
    fun `with a profile the person commits the agent's work, and turned-off agents are not offered`() {
        val choices = AgentChoices(enabled = { it != Agent.CODEX }, signedIn = { it == Agent.CLAUDE }, defaultAgent = { Agent.CODEX })
        val f = fixture(committer = Profile("Ada", "ada@example.com"), choices = choices)

        f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CLAUDE, "Add a greeting"))
        repeat(3) { f.scheduler.tick() }

        val run = f.store.runs().single()
        assertEquals(RunStatus.SUCCEEDED, run.status, run.error)
        val identity = output(File(run.worktree!!), "log", "--format=%an <%ae>|%cn <%ce>", "-1")
        assertEquals("Claude via LLM Manager <llm-manager@localhost>|Ada <ada@example.com>", identity, "the agent stays the author")
        val refused = f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CODEX, "Anything"))
        assertEquals("Codex is turned off in Settings", (refused as JobService.Created.Invalid).reason)
        val overview = f.jobs.overview()
        assertEquals(mapOf("claude" to true, "codex" to false, "gemini" to true), overview.agents)
        assertEquals(mapOf("claude" to true, "codex" to false, "gemini" to false), overview.signedIn)
        assertNull(overview.defaultAgent, "a turned-off agent is not preselected")
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
    fun `a chat job opens a pull request after its last stage, and only when there is something to publish`() {
        val published = CopyOnWriteArrayList<Run>()
        val f = fixture(publisher = { run -> published += run; "Opened pull request #7" })

        f.jobs.create(JobRequest(f.projectId, JobKind.PIPELINE, Agent.CLAUDE, "Add a greeting", publish = true))
        f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CLAUDE, "Only explain NOCHANGE", publish = true))
        f.jobs.create(JobRequest(f.projectId, JobKind.RUN, Agent.CODEX, "Not for a pull request"))
        repeat(8) { f.scheduler.tick() }

        val fix = awaitRun(f.store) { it.stage == Stage.FIX && it.publishNote != null }
        assertEquals("Opened pull request #7", fix.publishNote)
        assertEquals(listOf(fix.id), published.map { it.id }, "published once, after the review was addressed")
        val runs = f.store.runs()
        assertEquals("No changes to publish", runs.single { it.prompt.contains("NOCHANGE") }.publishNote)
        assertNull(runs.single { it.agent == Agent.CODEX && it.stage == Stage.WORK && it.pipelineId == null }.publishNote)
        assertTrue(runs.filter { it.stage != Stage.FIX && !it.prompt.contains("NOCHANGE") }.all { it.publishNote == null })
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

    private fun awaitRun(store: StateStore, predicate: (Run) -> Boolean): Run {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            store.runs().firstOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        error("No run matched: ${store.runs().joinToString { "${it.stage}:${it.status}:${it.publishNote}" }}")
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f-]{36}")
    }

    private fun git(dir: File, vararg args: String) {
        gitOutput(dir, *args)
    }

    private fun output(dir: File, vararg args: String): String = gitOutput(dir, *args)
}
