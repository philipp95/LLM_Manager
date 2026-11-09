package com.llmmanager.server.api

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** What a client sends to create a job. Everything is validated; nothing is trusted. */
@Serializable
data class JobRequest(
    val projectId: String,
    val kind: JobKind,
    val agent: Agent,
    val prompt: String = "",
    /** For a pipeline: who reviews; defaults to [Agent.defaultReviewer]. */
    val reviewer: Agent? = null,
    /** Null means "now". */
    val schedule: Schedule? = null,
    val resume: SessionRef? = null,
    val name: String? = null,
    val resumeOnLimitReset: Boolean = true,
)

@Serializable
data class JobView(val job: Job, val nextRunAt: String?)

/** A worktree that has a session to resume, with its project for display. */
@Serializable
data class ResumeTarget(val projectId: String, val projectName: String, val branch: String?, val session: ResumableSession)

@Serializable
data class ScheduleResponse(
    val now: String,
    val agents: Map<String, Boolean>,
    val jobs: List<JobView>,
    val runs: List<Run>,
    val resumable: List<ResumeTarget>,
    /** Whether agent runs are contained by the OS sandbox. */
    val sandbox: SandboxStatus? = null,
)

/** Creates, lists and changes jobs; the only path by which clients affect scheduling. */
class JobService(
    private val store: StateStore,
    private val scheduler: Scheduler,
    private val workspace: Workspace,
    private val catalog: SessionCatalog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val scanner = workspace.scanner
    private val commands = workspace.commands

    sealed interface Created {
        data class Ok(val job: Job) : Created
        data class Invalid(val reason: String) : Created
    }

    fun overview(): ScheduleResponse {
        val now = Instant.now(clock)
        return ScheduleResponse(
            now = now.toString(),
            agents = Agent.entries.associate { it.name.lowercase() to commands.available(it) },
            jobs = store.jobs().sortedBy { it.createdAt }.map { JobView(it, ScheduleTimes.next(it, now)?.toString()) },
            runs = store.runs().sortedByDescending { it.createdAt }.take(RECENT_RUNS),
            resumable = resumable(),
            sandbox = workspace.sandbox.status(),
        )
    }

    fun create(request: JobRequest): Created {
        val now = Instant.now(clock)
        val schedule = request.schedule ?: Schedule(at = now.toString())
        val located = scanner.locate(request.projectId)
        val problem = if (located == null) "Unknown project" else problem(request) ?: ScheduleTimes.problem(schedule)
        if (problem != null || located == null) return Created.Invalid(problem ?: "Unknown project")
        val job = Job(
            id = UUID.randomUUID().toString().replace("-", "").take(ID_CHARS),
            name = request.name?.trim()?.take(MAX_NAME)?.ifEmpty { null } ?: defaultName(request),
            projectId = request.projectId,
            projectName = located.project.name,
            kind = request.kind,
            agent = if (request.kind == JobKind.RESUME) checkNotNull(request.resume).agent else request.agent,
            reviewer = if (request.kind == JobKind.PIPELINE) reviewerOf(request) else null,
            prompt = request.prompt.trim().ifEmpty { "Continue where you left off." },
            schedule = schedule,
            resume = request.resume,
            resumeOnLimitReset = request.resumeOnLimitReset,
            createdAt = now.toString(),
        )
        store.putJob(job)
        scheduler.tick()
        return Created.Ok(store.job(job.id) ?: job)
    }

    fun delete(id: String): Boolean = store.job(id)?.also { store.removeJob(id) } != null

    fun setEnabled(id: String, enabled: Boolean): Job? = store.job(id)?.copy(enabled = enabled)?.also(store::putJob)

    fun runNow(id: String): Run? = store.job(id)?.let { job -> scheduler.fire(job).also { scheduler.tick() } }

    fun cancel(runId: String): Run? = store.run(runId)?.let(scheduler::cancel)

    /** Sessions in linked worktrees only: resuming in someone's main checkout would edit it unattended. */
    private fun resumable(): List<ResumeTarget> {
        val worktrees = scanner.scan().flatMap { project ->
            project.worktrees.filter { !it.main && !it.missing }.map { Triple(project, it.branch, it.path) }
        }
        val paths = worktrees.map { it.third }.toSet()
        // Sessions of runs this server started (any agent, Gemini included), plus Claude/Codex sessions found on disk.
        val fromRuns = store.runs().filter { it.sessionId != null && it.worktree in paths }.map {
            ResumableSession(it.agent, checkNotNull(it.sessionId), checkNotNull(it.worktree), it.finishedAt ?: it.createdAt)
        }
        val latest = (catalog.latest(paths) + fromRuns).groupBy { it.worktree to it.agent }.values.map { s -> s.maxBy { it.updatedAt } }
        return latest.mapNotNull { session ->
            val (project, branch) = worktrees.firstOrNull { it.third == session.worktree } ?: return@mapNotNull null
            ResumeTarget(project.id, project.name, branch, session)
        }.sortedByDescending { it.session.updatedAt }
    }

    private fun problem(request: JobRequest): String? = when {
        !commands.available(request.agent) -> "${request.agent.label} CLI is not installed on the server"
        request.kind == JobKind.PIPELINE && reviewerOf(request) == request.agent -> "Choose a different agent to review"
        request.kind == JobKind.PIPELINE && !commands.available(reviewerOf(request)) ->
            "${reviewerOf(request).label} CLI is needed to review but is not installed"
        request.kind != JobKind.RESUME && request.prompt.isBlank() -> "Describe the task"
        request.prompt.length > MAX_PROMPT -> "The task is too long (max $MAX_PROMPT characters)"
        request.kind == JobKind.RESUME -> resumeProblem(request)
        else -> null
    }

    private fun resumeProblem(request: JobRequest): String? {
        val target = request.resume ?: return "Choose a session to resume"
        val known = resumable().any { it.projectId == request.projectId && it.session.sessionId == target.sessionId && it.session.worktree == target.worktree }
        val worktreeName = target.worktree.substringAfterLast('/')
        val interactive = workspace.sessions.list().orEmpty().any { it == "${CodexLauncher.SESSION_PREFIX}$worktreeName" }
        return when {
            !known -> "That session is not resumable (only sessions in agent worktrees can be resumed)"
            !commands.available(target.agent) -> "${target.agent.label} CLI is not installed on the server"
            interactive -> "That worktree has an interactive Codex session open; exit it first"
            else -> null
        }
    }

    private companion object {
        const val ID_CHARS = 12
        const val MAX_PROMPT = 20_000
        const val MAX_NAME = 80
        const val RECENT_RUNS = 50
    }
}

private fun reviewerOf(request: JobRequest): Agent = request.reviewer ?: request.agent.defaultReviewer

private fun defaultName(request: JobRequest): String = when (request.kind) {
    JobKind.RUN -> "${request.agent.label} run"
    JobKind.PIPELINE -> "${request.agent.label} builds, ${reviewerOf(request).label} reviews"
    JobKind.RESUME -> "Resume ${request.resume?.agent?.label ?: ""} session".replace("  ", " ")
}
