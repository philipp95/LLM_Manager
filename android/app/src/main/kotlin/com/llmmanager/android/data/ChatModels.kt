package com.llmmanager.android.data

import kotlinx.serialization.Serializable

// Mirrors server/api's chat sessions.

/** One message in a session and what came of it: the run, or a pipeline's build, review and fix. */
@Serializable
data class ChatTurn(val message: String, val createdAt: String = "", val runs: List<Run> = emptyList())

/** A chat session: one conversation with one agent, on one branch. */
@Serializable
data class ChatView(
    val id: String,
    val projectId: String = "",
    /** The first message, shortened. */
    val title: String = "",
    val agent: String = "claude",
    val branch: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    /** A message is still being worked on: the next one waits until it is done. */
    val busy: Boolean = false,
    /** Its branch is still checked out, so it can take another message. */
    val open: Boolean = true,
    /** Other sessions work on the same branch. */
    val sharesBranch: Boolean = false,
    val turns: List<ChatTurn> = emptyList(),
) {
    /** How the session is doing: a run still working, or else the last run. */
    val state: Run? get() = turns.lastOrNull()?.runs?.let { runs -> runs.firstOrNull { it.active } ?: runs.lastOrNull() }
}

@Serializable
data class ChatsResponse(val chats: List<ChatView> = emptyList())

/** A message for a repository's chat: the first of a new session, or the next one in [chatId]. */
@Serializable
data class ChatRequest(
    val projectId: String,
    val prompt: String,
    /** The agent of a new session; a session's next message goes to that session's agent. */
    val agent: String,
    val reviewer: String? = null,
    val chatId: String? = null,
    /** For a new session: work on this session's branch instead of a new one. */
    val sameBranchAs: String? = null,
    val publish: Boolean = false,
)
