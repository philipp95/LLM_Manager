package com.llmmanager.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Everything the scheduling and usage routes need. */
class ScheduleApi(val jobs: JobService, val store: StateStore, val usage: UsageReader, val branches: BranchService? = null)

@Serializable
data class BranchRequest(val branch: String)

@Serializable
data class ApiOk(val status: String = "ok")

@Serializable
data class RunLog(val run: Run, val activity: List<ActivityLine>)

/**
 * Usage is open to the tailnet like the project overview. Schedules, run logs and
 * every change need the access token: they reveal prompts and start agents.
 */
fun Route.scheduleRoutes(api: ScheduleApi, token: ApiToken) {
    get("/api/usage") {
        call.respond(withContext(Dispatchers.IO) { api.usage.read() })
    }
    jobRoutes(api, token)
    runRoutes(api, token)
    api.branches?.let { branchRoutes(it, token) }
}

/** Issue #1: push an agent branch, open its pull request, merge it through the forge. */
private fun Route.branchRoutes(branches: BranchService, token: ApiToken) {
    post("/api/projects/{id}/branches/{action}") {
        if (!call.authorized(token)) return@post
        val branch = runCatching { call.receive<BranchRequest>() }.getOrNull()?.branch
        val id = call.parameters["id"].orEmpty()
        val outcome = when {
            branch == null -> BranchService.Outcome.Invalid("Malformed request")
            else -> withContext(Dispatchers.IO) {
                when (call.parameters["action"]) {
                    "push" -> branches.push(id, branch)
                    "pull-request" -> branches.openPullRequest(id, branch)
                    "merge" -> branches.merge(id, branch)
                    else -> BranchService.Outcome.Invalid("Unknown action")
                }
            }
        }
        when (outcome) {
            is BranchService.Outcome.Ok -> call.respond(BranchResult(outcome.message, outcome.record))
            is BranchService.Outcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(outcome.reason))
        }
    }
}

@Serializable
data class BranchResult(val message: String, val record: BranchRecord)

private fun Route.jobRoutes(api: ScheduleApi, token: ApiToken) {
    get("/api/schedule") {
        if (!call.authorized(token)) return@get
        call.respond(withContext(Dispatchers.IO) { api.jobs.overview() })
    }
    post("/api/jobs") {
        if (!call.authorized(token)) return@post
        val request = runCatching { call.receive<JobRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Malformed request"))
            return@post
        }
        when (val created = withContext(Dispatchers.IO) { api.jobs.create(request) }) {
            is JobService.Created.Ok -> call.respond(HttpStatusCode.Created, created.job)
            is JobService.Created.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(created.reason))
        }
    }
    jobActions(api, token)
}

private fun Route.jobActions(api: ScheduleApi, token: ApiToken) {
    post("/api/jobs/{id}/{action}") {
        if (!call.authorized(token)) return@post
        val id = call.parameters["id"].orEmpty()
        val found = withContext(Dispatchers.IO) {
            when (call.parameters["action"]) {
                "run" -> api.jobs.runNow(id) != null
                "pause" -> api.jobs.setEnabled(id, false) != null
                "enable" -> api.jobs.setEnabled(id, true) != null
                "delete" -> api.jobs.delete(id)
                else -> null
            }
        }
        when (found) {
            null -> call.respond(HttpStatusCode.NotFound, ApiError("Unknown action"))
            false -> call.respond(HttpStatusCode.NotFound, ApiError("Unknown job"))
            true -> call.respond(HttpStatusCode.OK, ApiOk())
        }
    }
}

private fun Route.runRoutes(api: ScheduleApi, token: ApiToken) {
    post("/api/runs/{id}/cancel") {
        if (!call.authorized(token)) return@post
        val run = withContext(Dispatchers.IO) { api.jobs.cancel(call.parameters["id"].orEmpty()) }
        if (run == null) call.respond(HttpStatusCode.NotFound, ApiError("Unknown run")) else call.respond(run)
    }
    get("/api/runs/{id}/log") {
        if (!call.authorized(token)) return@get
        val run = api.store.run(call.parameters["id"].orEmpty())
        if (run == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("Unknown run"))
            return@get
        }
        val events = File(api.store.runDir(run.id), "events.jsonl")
        call.respond(RunLog(run, withContext(Dispatchers.IO) { RunActivity.activity(run.agent, run.stage, events) }))
    }
}

/** Responds 401 and returns false unless the request carries the access token. */
private suspend fun ApplicationCall.authorized(token: ApiToken): Boolean {
    if (token.accepts(request.headers[HttpHeaders.Authorization])) return true
    respond(HttpStatusCode.Unauthorized, ApiError("Missing or wrong access token"))
    return false
}
