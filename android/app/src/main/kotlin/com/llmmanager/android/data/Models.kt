package com.llmmanager.android.data

import kotlinx.serialization.Serializable

// Mirrors server/api's JSON. Decoded with ignoreUnknownKeys so a newer server stays readable,
// and every field the app can live without has a default.

@Serializable
data class ProjectsResponse(
    val generatedAt: String,
    val codexAvailable: Boolean = false,
    val projects: List<Project> = emptyList(),
    val analytics: Analytics? = null,
    /** The APK the server offers, when it has one. */
    val androidApp: AndroidApp? = null,
)

/** The app build the server offers for download; its version decides whether to offer an update. */
@Serializable
data class AndroidApp(
    val url: String,
    val sizeBytes: Long = 0,
    val builtAt: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    /** Hex SHA-256 of the APK; the download is checked against it before installing. */
    val sha256: String? = null,
) {
    /** True when this build is newer than the one running ([current] is its version code). */
    fun newerThan(current: Int): Boolean = versionCode != null && versionCode > current && url.startsWith("/download/")
}

@Serializable
data class PairRequest(val client: String = "app")

/** A device paired with the server through Tailscale. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val os: String? = null,
    val client: String = "app",
    val pairedAt: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class PairResponse(val token: String, val device: PairedDevice)

@Serializable
data class Project(
    val id: String,
    val name: String,
    /** First sentence of the README on the default branch. */
    val description: String? = null,
    val kind: String = "checkout",
    val path: String = "",
    val originUrl: String? = null,
    val gitHubUrl: String? = null,
    /** github, gitlab or azure-devops. */
    val forge: String? = null,
    val webUrl: String? = null,
    val defaultBranch: String? = null,
    val worktrees: List<Worktree> = emptyList(),
    val commitsPerDay: List<Int> = emptyList(),
    /** Pull request activity on the forge, once the server has read it. */
    val insights: RepoInsights? = null,
)

/** A pull request (GitLab: merge request) as the dashboard shows it. */
@Serializable
data class PullRequestSummary(
    val number: Long,
    val title: String = "",
    val url: String = "",
    /** open, merged or closed. */
    val state: String = "open",
    val author: String? = null,
    val branch: String? = null,
    val createdAt: String? = null,
    val mergedAt: String? = null,
    /** claude, codex or gemini when the server could tell which agent made it. */
    val agent: String? = null,
    /** success, failure or pending; null when the repository has no CI/CD. */
    val ci: String? = null,
)

@Serializable
data class RepoInsights(
    val latestMerged: PullRequestSummary? = null,
    val mergedLastWeek: Int = 0,
    val openCount: Int = 0,
    /** Most recently updated pull requests, newest first. */
    val recent: List<PullRequestSummary> = emptyList(),
    /** Set once the forge has been read successfully. */
    val fetchedAt: String? = null,
    /** Why the forge could not be read; earlier data is kept. */
    val error: String? = null,
)

@Serializable
data class Worktree(
    val path: String,
    val branch: String? = null,
    val head: String? = null,
    val lastCommitAt: String? = null,
    val changedFiles: Int? = null,
    val agentRun: Boolean = false,
    val scheduledRun: Boolean = false,
    val main: Boolean = false,
    val missing: Boolean = false,
    val codex: CodexSession? = null,
    val publish: Publish? = null,
)

/** Push and pull request state of an agent branch. */
@Serializable
data class Publish(
    val pushed: Boolean = false,
    val upToDate: Boolean = false,
    val branchUrl: String? = null,
    val pullRequest: PullRequestRef? = null,
    val blocked: String? = null,
)

@Serializable
data class PullRequestRef(val forge: String, val number: Long, val url: String, val state: String) {
    val open: Boolean get() = state in setOf("open", "opened", "active")
}

@Serializable
data class BranchRequest(val branch: String)

@Serializable
data class BranchResult(val message: String)

@Serializable
data class CodexSession(val name: String, val running: Boolean, val attachCommand: String)

@Serializable
data class Analytics(
    val days: List<String> = emptyList(),
    val commitsPerDay: List<Int> = emptyList(),
    val codexStartsPerDay: List<Int> = emptyList(),
    val agentRunsPerDay: List<Int> = emptyList(),
    val busiest: List<ProjectCommits> = emptyList(),
)

@Serializable
data class ProjectCommits(val id: String, val name: String, val commits: Int)

@Serializable
data class LaunchResponse(val session: CodexSession, val branch: String, val path: String)

@Serializable
data class ApiError(val error: String)

// ---- usage (GET /api/usage, open to the tailnet) ----

@Serializable
data class UsageResponse(
    val days: List<String> = emptyList(),
    val claude: AgentUsage = AgentUsage(),
    val codex: AgentUsage = AgentUsage(),
    val gemini: AgentUsage? = null,
)

@Serializable
data class AgentUsage(
    val windows: List<LimitWindow> = emptyList(),
    val windowsAsOf: String? = null,
    val tokensPerDay: List<Long> = emptyList(),
    val cacheReadsPerDay: List<Long> = emptyList(),
    val models: List<ModelTokens> = emptyList(),
    val note: String? = null,
)

@Serializable
data class LimitWindow(val label: String, val usedPercent: Double, val resetsAt: String? = null)

@Serializable
data class ModelTokens(val model: String, val tokens: Long)

// ---- scheduling (token required) ----

@Serializable
data class ScheduleResponse(
    val now: String,
    val agents: Map<String, Boolean> = emptyMap(),
    val jobs: List<JobView> = emptyList(),
    val runs: List<Run> = emptyList(),
    val resumable: List<ResumeTarget> = emptyList(),
    val sandbox: SandboxStatus? = null,
)

/** Whether agent runs are contained by the OS sandbox on the server. */
@Serializable
data class SandboxStatus(val mode: String = "auto", val agents: Map<String, Boolean> = emptyMap(), val note: String = "")

@Serializable
data class JobView(val job: Job, val nextRunAt: String? = null)

@Serializable
data class Job(
    val id: String,
    val name: String,
    val projectId: String,
    val projectName: String = "",
    val kind: String,
    val agent: String,
    val reviewer: String? = null,
    val prompt: String = "",
    val schedule: Schedule = Schedule(),
    val enabled: Boolean = true,
    val done: Boolean = false,
    val lastFiredAt: String? = null,
    /** Push the branch and open a pull request when the work is done. */
    val publish: Boolean = false,
)

@Serializable
data class Schedule(val at: String? = null, val time: String? = null, val days: List<Int> = emptyList(), val zone: String? = null)

@Serializable
data class Run(
    val id: String,
    val jobId: String? = null,
    val projectId: String = "",
    val projectName: String = "",
    val agent: String,
    val stage: String,
    val status: String,
    val prompt: String = "",
    val branch: String? = null,
    val pipelineId: String? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val notBefore: String? = null,
    val resumeScheduled: Boolean = false,
    val tmuxSession: String? = null,
    /** What publishing did when the work was done: the pull request it opened, or why not. */
    val publishNote: String? = null,
) {
    val active: Boolean get() = status == "queued" || status == "running"
}

@Serializable
data class ResumeTarget(val projectId: String, val projectName: String, val branch: String? = null, val session: ResumableSession)

@Serializable
data class ResumableSession(val agent: String, val sessionId: String, val worktree: String, val updatedAt: String)

@Serializable
data class SessionRef(val agent: String, val sessionId: String, val worktree: String)

@Serializable
data class JobRequest(
    val projectId: String,
    val kind: String,
    val agent: String,
    val prompt: String,
    val schedule: Schedule? = null,
    val resume: SessionRef? = null,
    val resumeOnLimitReset: Boolean = true,
    val reviewer: String? = null,
    /** Push the branch and open a pull request when the work is done. */
    val publish: Boolean = false,
)

@Serializable
data class ActivityLine(val kind: String, val text: String)

@Serializable
data class RunLog(val run: Run, val activity: List<ActivityLine> = emptyList())
