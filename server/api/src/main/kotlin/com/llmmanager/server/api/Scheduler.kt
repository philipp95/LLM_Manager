package com.llmmanager.server.api

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
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
    hooks: FollowUpHooks = FollowUpHooks(),
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)
    private val followUps = FollowUps(store, clock, hooks)

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

    /**
     * Queues the first run of [job] now, and records the firing. A job that continues an agent
     * session (a resume, or a chat message in an existing session) starts by resuming it, in its
     * worktree; a pipeline then goes on to review and fix as usual.
     */
    @Synchronized
    fun fire(job: Job): Run {
        val now = Instant.now(clock).toString()
        store.putJob(job.copy(lastFiredAt = now, done = job.schedule.at != null))
        val target = job.resume
        val run = Run(
            id = newRunId(), jobId = job.id, projectId = job.projectId, projectName = job.projectName,
            agent = target?.agent ?: job.agent,
            stage = if (target != null) Stage.RESUME else Stage.WORK,
            status = RunStatus.QUEUED,
            prompt = job.context?.let { "$it\n\n${job.prompt}" } ?: job.prompt,
            pipelineId = newRunId().takeIf { job.kind == JobKind.PIPELINE },
            sessionId = target?.sessionId,
            worktree = target?.worktree ?: job.worktree,
            createdAt = now,
            // A scheduled job's every firing is a session of its own; a resume joins the session it continues.
            chatId = job.chatId ?: target?.let { chatOf(it) } ?: newRunId(),
        )
        store.putRun(run)
        log.info("Fired job {} ({}) as run {}", job.name, job.kind, run.id)
        return run
    }

    private fun chatOf(session: SessionRef): String? =
        store.runs().firstOrNull { it.sessionId == session.sessionId && it.worktree == session.worktree && it.chatId != null }?.chatId

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
    summary = null, error = null, limitResetsAt = null, resumeScheduled = false, tmuxSession = null, notBefore = null, publishNote = null,
)

/** What the scheduler asks of the rest of the server once a run has finished. */
class FollowUpHooks(
    /** When a limit-stopped run without a reported reset time may retry. */
    val limitResetHint: (Agent) -> Instant? = { null },
    /** Pushes a finished run's branch and opens its pull request; returns what happened. */
    val publisher: (Run) -> String = { "Publishing is not configured" },
)

/**
 * What happens after a run finishes: the next pipeline stage, a resume after a usage
 * limit, or, when the job asked for it and nothing else follows, publishing the branch.
 */
private class FollowUps(
    private val store: StateStore,
    private val clock: Clock,
    private val hooks: FollowUpHooks,
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /** Pushing and opening pull requests talks to the network: never inside the scheduler's tick. */
    private val publishing = Executors.newSingleThreadExecutor { Thread(it, "publish").apply { isDaemon = true } }

    /** Queues what follows [run]: the next pipeline stage, a resume once a usage limit resets, or publishing. */
    fun after(run: Run) {
        when {
            run.status == RunStatus.LIMITED -> resumeAfterLimit(run)
            run.status == RunStatus.SUCCEEDED && run.pipelineId != null -> if (!nextStage(run)) publishIfAsked(run)
            run.status == RunStatus.SUCCEEDED -> publishIfAsked(run)
        }
    }

    /** The job's work is complete: if it asked to publish and produced commits, open the pull request. */
    private fun publishIfAsked(run: Run) {
        val job = run.jobId?.let(store::job)
        if (job?.publish != true || run.branch == null) return
        val chain = store.runs().filter { it.branch == run.branch && it.jobId == run.jobId }
        if (chain.none { it.resultCommit != null && it.resultCommit != it.baseCommit }) {
            store.putRun(run.copy(publishNote = "No changes to publish"))
            return
        }
        publishing.execute {
            val note = runCatching { hooks.publisher(run) }.getOrElse { "Could not open a pull request: ${it.message}" }
            store.updateRun(run.id) { it.copy(publishNote = note) }
            log.info("Run {}: {}", run.id, note)
        }
    }

    private fun resumeAfterLimit(run: Run) {
        val job = run.jobId?.let(store::job)
        if (run.resumeScheduled || job?.resumeOnLimitReset == false) return
        val canResume = run.stage == Stage.REVIEW || (run.sessionId != null && run.worktree != null)
        if (!canResume) return
        val reset = ScheduleTimes.instant(run.limitResetsAt) ?: hooks.limitResetHint(run.agent) ?: Instant.now(clock).plus(FALLBACK_WAIT)
        val retry = if (run.stage == Stage.REVIEW) {
            run
        } else {
            run.copy(stage = Stage.RESUME, continues = run.continues ?: run.stage, prompt = AgentCommands.CONTINUE_PROMPT)
        }
        store.putRun(run.copy(resumeScheduled = true))
        store.putRun(retry.requeued(Instant.now(clock)).copy(notBefore = reset.plus(RESET_MARGIN).toString()))
        log.info("Run {} hit a usage limit; resuming after {}", run.id, reset)
    }

    /** Queues the pipeline's next stage; false when the pipeline is complete. */
    private fun nextStage(run: Run): Boolean {
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
        return next != null
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
