package com.llmmanager.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Agent(val label: String) {
    @SerialName("claude")
    CLAUDE("Claude"),

    @SerialName("codex")
    CODEX("Codex"),

    @SerialName("gemini")
    GEMINI("Gemini"),
    ;

    /** Who reviews this agent's work in a pipeline unless the job names a reviewer. */
    val defaultReviewer: Agent get() = if (this == CLAUDE) CODEX else CLAUDE
}

/** What a job does each time it fires. */
@Serializable
enum class JobKind {
    /** One agent works on the prompt in a new worktree. */
    @SerialName("run")
    RUN,

    /** The builder implements, the other agent reviews the commit, the builder addresses the review. */
    @SerialName("pipeline")
    PIPELINE,

    /** Continue an existing agent session in its worktree. */
    @SerialName("resume")
    RESUME,
}

/** When a job fires: once at [at], or every listed weekday at [time] in [zone]. */
@Serializable
data class Schedule(
    /** ISO-8601 instant for a one-off job; null for a repeating one. */
    val at: String? = null,
    /** "HH:mm" for a repeating job. */
    val time: String? = null,
    /** ISO weekdays 1 (Monday) to 7 (Sunday); empty means every day. */
    val days: List<Int> = emptyList(),
    /** IANA zone the [time] is in. */
    val zone: String? = null,
)

/** An existing agent session to continue: identified by agent, session id and the worktree it lives in. */
@Serializable
data class SessionRef(val agent: Agent, val sessionId: String, val worktree: String)

@Serializable
data class Job(
    val id: String,
    val name: String,
    val projectId: String,
    val projectName: String,
    val kind: JobKind,
    /** The agent that works (for a pipeline: the builder). */
    val agent: Agent,
    /** For a pipeline: who reviews; defaults to [Agent.defaultReviewer]. */
    val reviewer: Agent? = null,
    val prompt: String,
    val schedule: Schedule,
    val resume: SessionRef? = null,
    /** When a run stops at a plan limit, continue it automatically once the limit resets. */
    val resumeOnLimitReset: Boolean = true,
    /** When the work is done (and reviewed, for a pipeline), push the branch and open a pull request. */
    val publish: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: String,
    val lastFiredAt: String? = null,
    /** Set when a one-off job has fired, so it never fires twice. */
    val done: Boolean = false,
)

@Serializable
enum class RunStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,

    /** Stopped by a plan usage limit; may be resumed at [Run.limitResetsAt]. */
    @SerialName("limited") LIMITED,
    @SerialName("cancelled") CANCELLED,
    ;

    val finished: Boolean get() = this != QUEUED && this != RUNNING
}

@Serializable
enum class Stage {
    @SerialName("work") WORK,
    @SerialName("review") REVIEW,
    @SerialName("fix") FIX,
    @SerialName("resume") RESUME,
}

@Serializable
data class Run(
    val id: String,
    val jobId: String?,
    val projectId: String,
    val projectName: String,
    val agent: Agent,
    val stage: Stage,
    val status: RunStatus,
    val prompt: String,
    /** Groups the stages of one pipeline execution. */
    val pipelineId: String? = null,
    val branch: String? = null,
    val worktree: String? = null,
    /** Commit the run started from, and the commit it produced (null when nothing changed). */
    val baseCommit: String? = null,
    val resultCommit: String? = null,
    val sessionId: String? = null,
    val tmuxSession: String? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    /** The agent's final message, trimmed. */
    val summary: String? = null,
    val error: String? = null,
    val limitResetsAt: String? = null,
    /** Set on a LIMITED run once its automatic resume has been scheduled. */
    val resumeScheduled: Boolean = false,
    /** A queued run does not start before this instant (e.g. when a usage limit resets). */
    val notBefore: String? = null,
    /** For a resume after a limit: the stage it continues, so a pipeline carries on afterwards. */
    val continues: Stage? = null,
    /** What publishing did once the work was done: the pull request opened, or why not. */
    val publishNote: String? = null,
)
