package com.llmmanager.android.data

import kotlinx.serialization.json.Json

/** The repository chat's sessions. */
class ChatClient(private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun chats(server: ServerAddress, token: String, projectId: String): ApiResult<List<ChatView>> =
        request(json, server, Call("GET", "/api/projects/${encode(projectId)}/chats"), token) {
            json.decodeFromString(ChatsResponse.serializer(), it).chats
        }

    /** Starts a session, or sends the next message in one; answers with that session. */
    suspend fun send(server: ServerAddress, token: String, message: ChatRequest): ApiResult<ChatView> =
        request(json, server, Call("POST", "/api/chats", json.encodeToString(ChatRequest.serializer(), message)), token) {
            json.decodeFromString(ChatView.serializer(), it)
        }
}
