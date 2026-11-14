package com.llmmanager.android.data

import kotlinx.serialization.Serializable

// Mirrors server/api: arranging the home screen, and adding a repository by its address.

/** Pinned repositories come first, then the most stars. */
@Serializable
data class RepoPreference(val pinned: Boolean = false, val stars: Int = 0)

@Serializable
data class CloneRequest(val url: String)

/** A repository the server is cloning, or has cloned, since it started. */
@Serializable
data class CloneStatus(
    val id: String,
    val url: String = "",
    val name: String = "",
    /** cloning, done or failed. */
    val state: String = "cloning",
    val message: String? = null,
    val projectId: String? = null,
)

@Serializable
data class ClonesResponse(val clones: List<CloneStatus> = emptyList())

/** A model an agent's CLI offers; [id] is what its model option takes. */
@Serializable
data class ModelOption(val id: String, val label: String = "", val note: String? = null)
