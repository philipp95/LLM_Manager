package com.llmmanager.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `GET /api/projects`. */
@Serializable
data class ProjectsResponse(
    val generatedAt: String,
    /** Whether the server found a Codex CLI to launch. */
    val codexAvailable: Boolean,
    val projects: List<Project>,
    val analytics: Analytics,
    /** Present when an Android APK is available from [AndroidApp.url]. */
    val androidApp: AndroidApp? = null,
)

@Serializable
data class AndroidApp(val url: String, val sizeBytes: Long, val builtAt: String)

/** One git repository this machine works on, identified by its common git directory. */
@Serializable
data class Project(
    /** Opaque, stable handle derived from the git directory; the only way clients refer to a project. */
    val id: String,
    val name: String,
    val kind: Kind,
    /** Bare mirror directory, or the main checkout's top level. */
    val path: String,
    /** Origin with any embedded credentials removed. */
    val originUrl: String?,
    /** Set only for github.com remotes; built from a validated `owner/repo`. */
    val gitHubUrl: String?,
    val defaultBranch: String?,
    val worktrees: List<Worktree>,
    /** Commits reachable from any ref, per day; aligned with [Analytics.days]. */
    val commitsPerDay: List<Int> = emptyList(),
) {
    @Serializable
    enum class Kind {
        /** Bare cache created by the runner; its worktrees are agent runs. */
        @SerialName("mirror")
        MIRROR,

        /** A regular clone someone works in directly. */
        @SerialName("checkout")
        CHECKOUT,
    }
}

@Serializable
data class Worktree(
    val path: String,
    /** Short branch name, or null when HEAD is detached. */
    val branch: String?,
    val head: String?,
    /** Committer date of HEAD, ISO-8601 with offset; null for an unborn branch. */
    val lastCommitAt: String?,
    /** Entries in `git status --porcelain`, untracked files included; null when unreadable. */
    val changedFiles: Int?,
    /** On an `agent/<run-id>` branch, as created by the runner. */
    val agentRun: Boolean,
    /** On a `run/<id>` branch, created by a scheduled job. */
    val scheduledRun: Boolean = false,
    /** The repository's main worktree rather than a linked one. */
    val main: Boolean,
    /** Registered with git but its directory is gone (`git worktree prune` would drop it). */
    val missing: Boolean,
    /** Set for worktrees on a `codex/<id>` branch created by [CodexLauncher]. */
    val codex: CodexSession? = null,
    /** When a Codex session or agent run began (ISO-8601); null for other worktrees or when unknown. */
    val startedAt: String? = null,
)

@Serializable
data class CodexSession(
    /** tmux session name. */
    val name: String,
    val running: Boolean,
    val attachCommand: String,
)
