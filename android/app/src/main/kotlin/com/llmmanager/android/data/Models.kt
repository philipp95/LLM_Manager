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
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val kind: String = "checkout",
    val path: String = "",
    val originUrl: String? = null,
    val gitHubUrl: String? = null,
    val defaultBranch: String? = null,
    val worktrees: List<Worktree> = emptyList(),
    val commitsPerDay: List<Int> = emptyList(),
)

@Serializable
data class Worktree(
    val path: String,
    val branch: String? = null,
    val head: String? = null,
    val lastCommitAt: String? = null,
    val changedFiles: Int? = null,
    val agentRun: Boolean = false,
    val main: Boolean = false,
    val missing: Boolean = false,
    val codex: CodexSession? = null,
)

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
