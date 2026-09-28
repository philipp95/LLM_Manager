package com.llmmanager.server.domain

import com.llmmanager.protocol.ProjectId
import com.llmmanager.protocol.RunId
import com.llmmanager.protocol.RunUsage
import kotlinx.serialization.Serializable

@Serializable
enum class HarnessId { CLAUDE_CODE, CODEX, GEMINI }

/**
 * How much the agent may do without asking.
 *
 * A run owns an isolated worktree, so editing files inside it is low risk and
 * [ACCEPT_EDITS] is the sane default for autonomous work — headless runs with
 * the interactive default simply stall, because nobody is there to approve.
 *
 * [BYPASS] disables the harness's own gating entirely. Reserve it for cases you
 * have deliberately chosen; anything remote-affecting (push, deploy, credential
 * access) belongs behind this product's own approval flow instead, where the
 * decision is recorded and bound to the exact action.
 * See `.claude/skills/hitl-approval-flows`.
 */
@Serializable
enum class PermissionMode { ACCEPT_EDITS, BYPASS, PLAN }

/** What to run. Resolved server-side; the phone never supplies credentials. */
@Serializable
data class RunSpec(
    val runId: RunId,
    val projectId: ProjectId,
    val harness: HarnessId,
    val prompt: String,
    /** Absolute path to the git worktree this run owns exclusively. */
    val worktreePath: String,
    val model: String? = null,
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val timeoutSeconds: Long = 30 * 60,
)

/**
 * `Blocked` is a first-class status, not a flavour of `Running` — it is the only
 * one that needs the user *now*, and it drives the high-priority notification
 * channel. See `.claude/skills/agent-session-multiplexing`.
 */
@Serializable
sealed interface RunStatus {
    @Serializable object Queued : RunStatus
    @Serializable data class Running(val activity: String? = null) : RunStatus
    @Serializable data class Blocked(val reason: String) : RunStatus
    @Serializable object Cancelling : RunStatus
    @Serializable data class Done(val summary: String?, val usage: RunUsage) : RunStatus
    @Serializable data class Failed(val error: String, val usage: RunUsage) : RunStatus
    @Serializable object Cancelled : RunStatus

    val isTerminal: Boolean
        get() = this is Done || this is Failed || this is Cancelled
}
