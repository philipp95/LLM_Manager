package com.llmmanager.server.runner

import com.llmmanager.protocol.ModelUsage
import com.llmmanager.protocol.RunUsage

/**
 * Normalised events from any agent harness.
 *
 * Deliberately the *union* of what the three vendors emit, not their
 * intersection — flattening to "text in, text out" would discard tool calls and
 * usage, which is most of what makes a run reviewable.
 */
sealed interface HarnessEvent {
    /** Assistant text. Cumulative-snapshot semantics are applied downstream, not here. */
    data class Text(val text: String) : HarnessEvent

    data class ToolCall(val name: String, val summary: String?) : HarnessEvent

    /** Incremental usage observed mid-run (per assistant turn). */
    data class Usage(val model: String?, val usage: com.llmmanager.protocol.TokenUsage) : HarnessEvent

    /** A line the parser did not recognise. Never dropped — schemas drift. */
    data class Unparsed(val raw: String) : HarnessEvent

    data class Stderr(val line: String) : HarnessEvent

    data class Finished(
        val ok: Boolean,
        val summary: String?,
        val usage: RunUsage,
        val exitCode: Int,
        val durationMs: Long?,
        val sessionId: String?,
    ) : HarnessEvent
}
