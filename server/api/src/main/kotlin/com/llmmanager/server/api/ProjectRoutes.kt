package com.llmmanager.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pinned repositories first, then the most stars; otherwise the order they came in. */
fun List<Project>.arranged(preferences: Map<String, RepoPreference>): List<Project> =
    map { project -> preferences[project.id]?.let { project.copy(pinned = it.pinned, stars = it.stars) } ?: project }
        .sortedWith(compareByDescending<Project> { it.pinned }.thenByDescending { it.stars })

/** Pin and star a repository. */
fun Route.repoPreferenceRoute(preferences: PreferencesStore, scanner: ProjectScanner, access: Access, onChange: () -> Unit) {
    put("/api/projects/{id}/preference") {
        if (!call.authorized(access)) return@put
        val id = call.parameters["id"].orEmpty()
        val request = runCatching { call.receive<RepoPreference>() }.getOrNull()
        val problem = when {
            request == null -> "Malformed request"
            request.stars !in 0..MAX_STARS -> "Stars go from 0 to $MAX_STARS"
            withContext(Dispatchers.IO) { scanner.scan().none { it.id == id } } -> "Unknown project"
            else -> null
        }
        if (problem != null || request == null) {
            call.respond(if (problem == "Unknown project") HttpStatusCode.NotFound else HttpStatusCode.BadRequest, ApiError(problem ?: "Malformed request"))
            return@put
        }
        preferences.update { saved ->
            // Nothing is kept for a repository left at the default.
            val repos = if (request == RepoPreference()) saved.repos - id else saved.repos + (id to request)
            saved.copy(repos = repos)
        }
        onChange()
        call.respond(request)
    }
}

/** Add a repository by its address, and follow the clones. */
fun Route.cloneRoutes(cloner: RepoCloner, access: Access) {
    post("/api/projects/clone") {
        if (!call.authorized(access)) return@post
        val request = runCatching { call.receive<CloneRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Malformed request"))
            return@post
        }
        when (val outcome = withContext(Dispatchers.IO) { cloner.start(request) }) {
            is RepoCloner.Outcome.Started -> call.respond(HttpStatusCode.Accepted, outcome.status)
            is RepoCloner.Outcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(outcome.reason))
        }
    }
    get("/api/projects/clones") {
        if (!call.authorized(access)) return@get
        call.respond(ClonesResponse(cloner.list()))
    }
}

private const val MAX_STARS = 5
