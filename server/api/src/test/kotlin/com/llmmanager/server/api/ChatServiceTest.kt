package com.llmmanager.server.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** Chat sessions over real git worktrees and the real run wrapper, with fake agents. */
@DisabledOnOs(OS.WINDOWS, disabledReason = "runs bash wrappers and shell-script fake agents, as the Linux server does")
class ChatServiceTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val clock = MutableClock(Instant.parse("2026-11-12T12:00:00Z"))

    private class Fixture(
        val chats: ChatService,
        val scheduler: Scheduler,
        val store: StateStore,
        val projectId: String,
        val published: MutableList<Run>,
        val schedule: ScheduleApi,
    ) {
        fun send(request: ChatRequest) = chats.send(request)

        fun chat(id: String) = chats.list(projectId).single { it.id == id }

        /** Lets queued runs start and finished ones be collected, a few rounds. */
        fun settle() = repeat(4) { scheduler.tick() }
    }

    private fun fixture(): Fixture {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        gitOutput(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("app\n")
        gitOutput(repo, "add", "README.md")
        gitOutput(repo, "-c", "user.name=Person", "-c", "user.email=person@example.invalid", "commit", "-m", "initial")
        val sessions = ImmediateSessions()
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"), sessions = sessions)
        val store = StateStore(File(root, "state"))
        val agents = FakeAgents(root)
        val workspace = Workspace(scanner, sessions, AgentCommands(agents.claude(), agents.codex(), gemini = agents.gemini()))
        val published = CopyOnWriteArrayList<Run>()
        val hooks = FollowUpHooks(publisher = { run -> published += run; "Opened pull request #1" })
        val scheduler = Scheduler(store, RunEngine(store, workspace, File(root, "runs"), clock), sessions, clock = clock, hooks = hooks)
        val chats = ChatService(store, scheduler, workspace, clock = clock)
        val jobs = JobService(store, scheduler, workspace, SessionCatalog(File(root, "home")), clock)
        val schedule = ScheduleApi(jobs, store, UsageReader(root, File(root, "state")), chats = chats)
        return Fixture(chats, scheduler, store, scanner.scan().single().id, published, schedule)
    }

    private fun ChatService.Outcome.chat() = (this as ChatService.Outcome.Ok).chat

    private fun ChatService.Outcome.refusal() = (this as ChatService.Outcome.Invalid).reason

    @Test
    fun `each new session gets a branch of its own, and its next message continues it there`() {
        val f = fixture()

        val first = f.send(ChatRequest(f.projectId, "Add a greeting", Agent.CLAUDE, publish = true)).chat()
        assertTrue(first.busy)
        assertTrue(f.send(ChatRequest(f.projectId, "And more", chatId = first.id)).refusal().contains("still working"))
        f.settle()
        val other = f.send(ChatRequest(f.projectId, "Fix a typo", Agent.CLAUDE)).chat()
        f.settle()
        f.send(ChatRequest(f.projectId, "Now add tests", Agent.CODEX, chatId = first.id, publish = true)).chat()
        f.settle()

        val session = f.chat(first.id)
        assertNotEquals(session.branch, f.chat(other.id).branch, "a new session never shares a branch unless asked")
        assertFalse(session.sharesBranch)
        assertEquals(listOf("Add a greeting", "Now add tests"), session.turns.map { it.message })
        val runs = session.turns.flatMap { it.runs }
        assertEquals(1, runs.map { it.worktree to it.branch }.distinct().size, "one worktree, one branch")
        val next = runs.last()
        assertEquals(Stage.RESUME to Agent.CLAUDE, next.stage to next.agent, "the session's own agent continues its own session")
        assertEquals(runs.first().sessionId, next.sessionId)
        assertEquals("Now add tests", next.prompt, "a resumed agent remembers; it only gets the new message")
        assertEquals(runs.first().resultCommit, next.baseCommit, "the next message starts where the last one ended")
        assertEquals("Claude via LLM Manager\nClaude via LLM Manager\nPerson", gitOutput(File(next.worktree!!), "log", "--format=%an", "-3"))
        awaitTrue { f.published.size == 2 }
        assertEquals(setOf(session.branch), f.published.map { it.branch }.toSet(), "each message updates the session's pull request")
        assertTrue(f.schedule.jobs.overview().jobs.isEmpty(), "chat messages stay out of the Schedule tab")
    }

    @Test
    fun `a new session shares another session's branch only when asked`() {
        val f = fixture()
        val first = f.send(ChatRequest(f.projectId, "Add a greeting", Agent.CLAUDE)).chat()
        f.settle()

        val shared = f.send(ChatRequest(f.projectId, "Add a footer", Agent.GEMINI, sameBranchAs = first.id)).chat()
        f.settle()

        val a = f.chat(first.id)
        val b = f.chat(shared.id)
        assertEquals(a.branch, b.branch)
        assertTrue(a.sharesBranch && b.sharesBranch)
        assertEquals(Agent.GEMINI, b.agent)
        val run = b.turns.single().runs.first()
        assertEquals(Stage.WORK, run.stage, "its own agent session, not the other session's")
        assertEquals(a.turns.single().runs.single().worktree, run.worktree)
        assertEquals(a.turns.single().runs.single().resultCommit, run.baseCommit)
        assertEquals(RunStatus.SUCCEEDED, run.status, run.error)

        assertTrue(f.send(ChatRequest(f.projectId, "x", sameBranchAs = "nope")).refusal().contains("no longer checked out"))
        assertTrue(f.send(ChatRequest(f.projectId, "x", chatId = "nope")).refusal().contains("Unknown session"))
        assertTrue(f.send(ChatRequest(f.projectId, "  ")).refusal().contains("Write a message"))
        assertTrue(f.send(ChatRequest(f.projectId, "x", Agent.CLAUDE, reviewer = Agent.CLAUDE)).refusal().contains("different agent"))
    }

    @Test
    fun `when the first message failed, the next one starts the agent afresh on the same branch and tells it what was asked`() {
        val f = fixture()
        val first = f.send(ChatRequest(f.projectId, "BREAK the build", Agent.CLAUDE)).chat()
        f.settle()
        val failed = f.chat(first.id).turns.single().runs.single()
        assertEquals(RunStatus.FAILED, failed.status)

        f.send(ChatRequest(f.projectId, "Try again", chatId = first.id)).chat()
        f.settle()

        val session = f.chat(first.id)
        val retry = session.turns.last().runs.single()
        assertEquals(Stage.WORK, retry.stage)
        assertEquals(failed.worktree, retry.worktree)
        assertNotEquals(failed.sessionId, retry.sessionId)
        assertTrue(retry.prompt.contains("- BREAK the build") && retry.prompt.endsWith("Try again"), retry.prompt)
        assertEquals(listOf("BREAK the build", "Try again"), session.turns.map { it.message }, "the person sees their own messages")
        assertEquals(RunStatus.SUCCEEDED, retry.status, retry.error)
    }

    @Test
    fun `runs from before sessions become sessions, and repeating jobs keep starting new ones`() {
        val store = StateStore(File(root, "legacy"))
        fun job(id: String, schedule: Schedule) =
            Job(id, id, "p", "acme/app", JobKind.RUN, Agent.CLAUDE, prompt = "x", schedule = schedule, createdAt = "2026-11-01T00:00:00Z")
        store.putJob(job("chat", Schedule(at = "2026-11-01T00:00:00Z")))
        store.putJob(job("daily", Schedule(time = "09:00", zone = "UTC")))
        fun run(id: String, jobId: String?, branch: String?, pipeline: String? = null, stage: Stage = Stage.WORK) =
            Run(
                id, jobId, "p", "acme/app", Agent.CLAUDE, stage, RunStatus.SUCCEEDED, "x",
                pipelineId = pipeline, branch = branch, createdAt = "2026-11-01T00:00:0${id.last()}Z",
            )
        store.putRun(run("aaaaaaaaaaa1", "chat", "run/a", pipeline = "pipe"))
        store.putRun(run("aaaaaaaaaaa2", "chat", "run/a", pipeline = "pipe", stage = Stage.REVIEW))
        store.putRun(run("aaaaaaaaaaa3", "daily", "run/b"))
        store.putRun(run("aaaaaaaaaaa4", null, "run/c"))

        store.adoptIntoSessions()

        val runs = store.runs()
        assertTrue(runs.all { it.chatId != null })
        assertEquals(3, runs.map { it.chatId }.distinct().size)
        assertEquals(1, runs.filter { it.pipelineId == "pipe" }.map { it.chatId }.distinct().size)
        assertEquals(runs.first { it.pipelineId == "pipe" }.chatId, store.job("chat")?.chatId, "a chat message moves into its session")
        assertNull(store.job("daily")?.chatId, "a repeating job starts a new session each time it fires")
        val before = store.runs()
        store.adoptIntoSessions()
        assertEquals(before, store.runs(), "only once")
    }

    @Test
    fun `sessions over HTTP need the token`() = testApplication {
        val f = fixture()
        val secret = "t".repeat(40)
        application { dashboardModule(ProjectScanner(listOf("${root.path}/repos/*")), ApiToken(secret), DashboardServices(schedule = f.schedule)) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/projects/${f.projectId}/chats").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chats").status)
        val created = client.post("/api/chats") {
            header(HttpHeaders.Authorization, "Bearer $secret")
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"${f.projectId}","prompt":"Add a greeting","agent":"claude"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val listed = client.get("/api/projects/${f.projectId}/chats") { header(HttpHeaders.Authorization, "Bearer $secret") }
        val chats = Json { ignoreUnknownKeys = true }.decodeFromString<ChatsResponse>(listed.bodyAsText()).chats
        assertEquals("Add a greeting", chats.single().title)
        assertNotNull(chats.single().branch)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && !condition()) Thread.sleep(50)
        assertTrue(condition(), "timed out waiting")
    }
}
