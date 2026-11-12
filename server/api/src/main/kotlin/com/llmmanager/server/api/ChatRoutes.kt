package com.llmmanager.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The repository chat's sessions: list them, start one, or send the next message in one. */
fun Route.chatRoutes(chats: ChatService, access: Access, onChange: (projectId: String) -> Unit) {
    get("/api/projects/{id}/chats") {
        if (!call.authorized(access)) return@get
        call.respond(ChatsResponse(withContext(Dispatchers.IO) { chats.list(call.parameters["id"].orEmpty()) }))
    }
    post("/api/chats") {
        if (!call.authorized(access)) return@post
        val request = runCatching { call.receive<ChatRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Malformed request"))
            return@post
        }
        when (val outcome = withContext(Dispatchers.IO) { chats.send(request) }) {
            is ChatService.Outcome.Ok -> {
                onChange(request.projectId)
                call.respond(HttpStatusCode.Created, outcome.chat)
            }
            is ChatService.Outcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(outcome.reason))
        }
    }
}
