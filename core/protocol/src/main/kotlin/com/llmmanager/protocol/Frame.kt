package com.llmmanager.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The single multiplexed WebSocket envelope.
 *
 * One connection carries request/response and server-pushed events. The
 * discriminator is `type`, matching the shape documented in
 * `.claude/skills/openclaw-gateway-client` — a sound reference design even
 * though this is not OpenClaw's protocol.
 */
@Serializable
sealed interface Frame {

    @Serializable
    @SerialName("req")
    data class Req(
        val id: String,
        val method: String,
        val params: JsonElement? = null,
    ) : Frame

    @Serializable
    @SerialName("res")
    data class Res(
        val id: String,
        val ok: Boolean,
        val payload: JsonElement? = null,
        val error: ErrorBody? = null,
    ) : Frame

    /**
     * Server-pushed event. [seq] is the log position: a client seeing
     * `seq > lastSeq + 1` has missed events and must re-request from `lastSeq`
     * rather than render a hole.
     */
    @Serializable
    @SerialName("event")
    data class Event(
        val seq: Seq,
        val topic: String,
        val payload: JsonElement,
    ) : Frame
}

@Serializable
data class ErrorBody(
    val code: ErrorCode,
    val message: String,
    /** Honour this before inventing a retry policy. */
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
)

@Serializable
enum class ErrorCode {
    UNAUTHENTICATED,
    PAIRING_REQUIRED,
    FORBIDDEN_SCOPE,
    NOT_FOUND,
    INVALID_REQUEST,
    CONFLICT,
    RATE_LIMITED,
    INTERNAL,
}
