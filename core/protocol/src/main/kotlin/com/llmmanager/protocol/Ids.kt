package com.llmmanager.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class ProjectId(val value: String)
@JvmInline @Serializable value class RunId(val value: String)
@JvmInline @Serializable value class AgentSpecId(val value: String)
@JvmInline @Serializable value class DeviceId(val value: String)

/**
 * Monotonic, gapless position in the server's append-only event log.
 *
 * The client persists the highest [Seq] it has *durably stored* and resumes with it.
 * Persisting after render but before write loses events on a crash.
 */
@JvmInline @Serializable value class Seq(val value: Long) : Comparable<Seq> {
    override fun compareTo(other: Seq): Int = value.compareTo(other.value)
    operator fun inc(): Seq = Seq(value + 1)
}
