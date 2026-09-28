package com.llmmanager.protocol

import kotlinx.serialization.Serializable

/**
 * Token counts for one model within a run.
 *
 * Counts are per-provider and NOT comparable across vendors — see
 * `.claude/skills/multi-provider-llm-apis`. Never sum raw token counts across
 * providers and present the total as a single number; sum cost instead.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadInputTokens = cacheReadInputTokens + other.cacheReadInputTokens,
        cacheCreationInputTokens = cacheCreationInputTokens + other.cacheCreationInputTokens,
    )

    companion object { val ZERO = TokenUsage() }
}

/**
 * Per-model usage and cost.
 *
 * [costUsd] is the harness's own figure where it reports one. Claude Code emits
 * `modelUsage.<model>.costUSD` on its result line; we record rather than
 * recompute it, so our number matches what the vendor bills.
 */
@Serializable
data class ModelUsage(
    val model: String,
    val usage: TokenUsage,
    val costUsd: Double?,
)

/** Roll-up for a whole run. Cost is summable across models and vendors; tokens are not. */
@Serializable
data class RunUsage(
    val perModel: List<ModelUsage> = emptyList(),
    val totalCostUsd: Double? = null,
) {
    val totalTokens: TokenUsage
        get() = perModel.fold(TokenUsage.ZERO) { acc, m -> acc + m.usage }
}
