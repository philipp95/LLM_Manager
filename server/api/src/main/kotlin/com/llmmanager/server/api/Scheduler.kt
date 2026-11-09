package com.llmmanager.server.api

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Drives jobs and runs. Each [tick] fires due jobs, collects finished runs, queues
 * follow-ups (the next pipeline stage, or a resume after a usage limit resets) and
 * starts queued runs within the concurrency limit.
 */
class Scheduler(
    private val store: StateStore,
    private val engine: RunEngine,
    private val sessions: SessionHost,
    private val clock: Clock = Clock.systemUTC(),
    private val maxConcurrent: Int = 3,
    /** When a limit-stopped run without a reported reset time may retry. */
    private val limitResetHint: (Agent) -> Instant? = { null },
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)
    private val followUps = FollowUps(store, clock, limitResetHint)

    fun launch(scope: CoroutineScope, interval: Duration = Duration.ofSeconds(15)): CoroutineJob = scope.launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { tick() }.onFailure { log.error("Scheduler tick failed", it) }
            delay(interval.toMillis())
        }
    }

    @Synchronized
    fun tick() {
        val now = Instant.now(clock)
        store.jobs().filter { ScheduleTimes.isDue(it, now) }.forEach { fire(it) }
        val live = sessions.list() ?: return
        store.runs().filter { it.status == RunStatus.RUNNING }.forEach { running ->
            val polled = isolated(running) { engine.poll(running, live) }
            if (polled.status.finished) followUps.after(polled)
        }
        startQueued(now)
    }

    /** One run's failure is recorded on that run; it never stops the scheduler for the others. */
    @Suppress("TooGenericExceptionCaught") // the isolation boundary: any failure belongs to this run alone
    private fun isolated(run: Run, step: () -> Run): Run = try {
        step()
    } catch (e: Exception) {
        log.error("Run {} failed unexpectedly", run.id, e)
        val error = "Internal error: ${e.message ?: e.javaClass.simpleName}"
        run.copy(status = RunStatus.FAILED, error = error, finishedAt = Instant.now(clock).toString()).also(store::putRun)
    }

    /** Queues the first run of [job] now, and records the firing. */
    @Synchronized
    fun fire(job: Job): Run {
        val now = Instant.now(clock).toString()
        store.putJob(job.copy(lastFiredAt = now, done = job.schedule.at != null))
        val base = Run(
            id = newRunId(), jobId = job.id, projectId = job.projectId, projectName = job.projectName, agent = job.agent,
            stage = Stage.WORK, status = RunStatus.QUEUED, prompt = job.prompt, createdAt = now,
        )
        val run = when (job.kind) {
            JobKind.RUN -> base
            JobKind.PIPELINE -> base.copy(pipelineId = newRunId())
            JobKind.RESUME -> {
                val target = checkNotNull(job.resume)
                base.copy(stage = Stage.RESUME, agent = target.agent, sessionId = target.sessionId, worktree = target.worktree)
            }
        }
        store.putRun(run)
        log.info("Fired job {} ({}) as run {}", job.name, job.kind, run.id)
        return run
    }

    @Synchronized
    fun cancel(run: Run): Run = when (run.status) {
        RunStatus.RUNNING -> engine.cancel(run)
        RunStatus.QUEUED -> run.copy(status = RunStatus.CANCELLED, finishedAt = Instant.now(clock).toString()).also(store::putRun)
        else -> run
    }

    private fun startQueued(now: Instant) {
        val running = store.runs().filter { it.status == RunStatus.RUNNING }
        val busy = running.mapNotNull { it.worktree }.toMutableSet()
        var slots = maxConcurrent - running.size
        store.runs().filter { it.status == RunStatus.QUEUED }.sortedBy { it.createdAt }.forEach { queued ->
            val ready = ScheduleTimes.instant(queued.notBefore)?.let { !it.isAfter(now) } ?: true
            if (slots <= 0 || !ready || queued.worktree in busy) return@forEach
            val started = isolated(queued) { engine.start(queued) }
            started.worktree?.let(busy::add)
            if (started.status == RunStatus.RUNNING) slots-- else followUps.after(started)
        }
    }

}

private const val RUN_ID_CHARS = 12

internal fun newRunId(): String = UUID.randomUUID().toString().replace("-", "").take(RUN_ID_CHARS)

/** A fresh queued run that carries over the project, worktree, session and pipeline of this one. */
internal fun Run.requeued(now: Instant): Run = copy(
    id = newRunId(), status = RunStatus.QUEUED, createdAt = now.toString(), startedAt = null, finishedAt = null,
    summary = null, error = null, limitResetsAt = null, resumeScheduled = false, tmuxSession = null, notBefore = null,
)

/** What happens after a run finishes: the next pipeline stage, or a resume after a usage limit. */
private class FollowUps(
    private val store: StateStore,
    private val clock: Clock,
    private val limitResetHint: (Agent) -> Instant?,
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /** Queues what follows [run]: the next pipeline stage, or a resume once a usage limit resets. */
    fun after(run: Run) {
        when {
            run.status == RunStatus.LIMITED -> resumeAfterLimit(run)
            run.status == RunStatus.SUCCEEDED && run.pipelineId != null -> nextStage(run)
        }
    }

    private fun resumeAfterLimit(run: Run) {
        val job = run.jobId?.let(store::job)
        if (run.resumeScheduled || job?.resumeOnLimitReset == false) return
        val canResume = run.stage == Stage.REVIEW || (run.sessionId != null && run.worktree != null)
        if (!canResume) return
        val reset = ScheduleTimes.instant(run.limitResetsAt) ?: limitResetHint(run.agent) ?: Instant.now(clock).plus(FALLBACK_WAIT)
        val retry = if (run.stage == Stage.REVIEW) {
            run
        } else {
            run.copy(stage = Stage.RESUME, continues = run.continues ?: run.stage, prompt = AgentCommands.CONTINUE_PROMPT)
        }
        store.putRun(run.copy(resumeScheduled = true))
        store.putRun(retry.requeued(Instant.now(clock)).copy(notBefore = reset.plus(RESET_MARGIN).toString()))
        log.info("Run {} hit a usage limit; resuming after {}", run.id, reset)
    }

    private fun nextStage(run: Run) {
        val pipeline = store.runs().filter { it.pipelineId == run.pipelineId }
        val builder = pipeline.minByOrNull { it.createdAt }?.agent ?: run.agent
        val job = run.jobId?.let(store::job)
        val task = job?.prompt ?: pipeline.minByOrNull { it.createdAt }?.prompt.orEmpty()
        val reviewer = job?.reviewer ?: builder.defaultReviewer
        val next = when (run.continues ?: run.stage) {
            Stage.WORK, Stage.RESUME -> reviewOf(run, builder, reviewer, task)
            Stage.REVIEW -> fixFor(run, builder, pipeline)
            Stage.FIX -> null
        }
        next?.let(store::putRun)
    }

    /** A review by the other agent, unless the work changed nothing. */
    private fun reviewOf(run: Run, builder: Agent, reviewer: Agent, task: String): Run? {
        val commit = run.resultCommit?.takeIf { it != run.baseCommit } ?: return null
        val prompt = AgentCommands.reviewPrompt(builder, run.baseCommit.orEmpty(), commit, task)
        return run.requeued(Instant.now(clock)).copy(stage = Stage.REVIEW, agent = reviewer, sessionId = null, prompt = prompt, continues = null)
    }

    /** The builder addresses the review in its own session, unless the reviewer found nothing. */
    private fun fixFor(run: Run, builder: Agent, pipeline: List<Run>): Run? {
        val review = run.summary?.trim()?.takeUnless { it.isEmpty() || it.trimEnd('.').equals("No findings", ignoreCase = true) }
        val session = pipeline.filter { it.agent == builder && it.sessionId != null }.maxByOrNull { it.createdAt }?.sessionId
        if (review == null || session == null) return null
        return run.requeued(Instant.now(clock)).copy(
            stage = Stage.FIX, agent = builder, sessionId = session, continues = null,
            prompt = AgentCommands.fixPrompt(run.agent, review), baseCommit = run.resultCommit,
        )
    }

    private companion object {
        val FALLBACK_WAIT: Duration = Duration.ofHours(1)
        val RESET_MARGIN: Duration = Duration.ofMinutes(2)
    }
}
