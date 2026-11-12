package com.llmmanager.server.runner

import com.llmmanager.server.domain.HarnessId
import com.llmmanager.server.domain.RunSpec
import kotlinx.coroutines.flow.Flow

/**
 * One implementation per vendor CLI. The orchestrator owns the worktree and
 * lifecycle; a harness only knows how to run one agent in one directory and
 * report what happened.
 */
interface AgentHarness {
    val id: HarnessId

    /** True when the underlying CLI is present and runnable. Checked before dispatch. */
    suspend fun isAvailable(): Boolean

    /**
     * Spawns the agent. The flow terminates after [HarnessEvent.Finished].
     * Cancelling the collector must destroy the process — a detached agent keeps
     * spending money.
     */
    fun spawn(spec: RunSpec): Flow<HarnessEvent>
}
