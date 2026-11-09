package com.llmmanager.server.api

import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ResumableSession(val agent: Agent, val sessionId: String, val worktree: String, val updatedAt: String)

/**
 * Finds the latest Claude and Codex session recorded for a worktree, from the agents'
 * own session files: Claude's `~/.claude/projects/<folder>/<session>.jsonl` and Codex's
 * `~/.codex/sessions/.../rollout-*.jsonl` (whose first line names the session and folder).
 */
class SessionCatalog(private val home: File = File(System.getProperty("user.home"))) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Codex rollout → (session id, cwd). The first line never changes, so it is read once per file. */
    private val codexHeaders = mutableMapOf<String, Pair<String, String>?>()

    @Synchronized
    fun latest(worktrees: Collection<String>): List<ResumableSession> {
        val wanted = worktrees.toSet()
        val codex = codexSessions().filter { it.worktree in wanted }
            .groupBy { it.worktree }.values.mapNotNull { sessions -> sessions.maxByOrNull { it.updatedAt } }
        return wanted.mapNotNull(::claudeSession) + codex
    }

    private fun claudeSession(worktree: String): ResumableSession? {
        val folder = File(home, ".claude/projects/${worktree.replace(Regex("[^A-Za-z0-9]"), "-")}")
        val newest = folder.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && UUID_LIKE.matches(f.name.removeSuffix(".jsonl")) }
            .orEmpty().maxByOrNull { it.lastModified() }
        return newest?.let { ResumableSession(Agent.CLAUDE, it.name.removeSuffix(".jsonl"), worktree, Instant.ofEpochMilli(it.lastModified()).toString()) }
    }

    private fun codexSessions(): List<ResumableSession> {
        val root = File(home, ".codex/sessions")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }.mapNotNull { file ->
            val (id, cwd) = codexHeaders.getOrPut(file.path) { header(file) } ?: return@mapNotNull null
            ResumableSession(Agent.CODEX, id, cwd, Instant.ofEpochMilli(file.lastModified()).toString())
        }.toList()
    }

    private fun header(file: File): Pair<String, String>? {
        val first = file.bufferedReader().use { it.readLine() } ?: return null
        val payload = runCatching { (json.parseToJsonElement(first) as? JsonObject)?.get("payload") as? JsonObject }.getOrNull() ?: return null
        val id = (payload["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val cwd = (payload["cwd"] as? JsonPrimitive)?.contentOrNull ?: return null
        return id to cwd
    }

    private companion object {
        val UUID_LIKE = Regex("[0-9a-fA-F-]{36}")
    }
}
