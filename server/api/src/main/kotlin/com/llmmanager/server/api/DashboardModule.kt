package com.llmmanager.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The dashboard. Reads are open to the tailnet. The single state-changing route,
 * starting a Codex session, requires [token]; there is no route that edits files,
 * deletes worktrees, or pushes.
 */
fun Application.dashboardModule(
    scanner: ProjectScanner,
    launcher: CodexLauncher? = null,
    token: ApiToken? = null,
    apk: ApkFile? = null,
) {
    val page = DashboardResources.page()

    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; explicitNulls = true })
    }
    install(SecurityHeaders)

    routing {
        get("/") {
            call.respondText(page, ContentType.Text.Html.withParameter("charset", "utf-8"))
        }
        get("/api/projects") {
            val snapshot = withContext(Dispatchers.IO) { scanner.snapshot() }
            val now = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            call.respond(ProjectsResponse(now, launcher?.available == true, snapshot.projects, snapshot.analytics, apk?.describe()))
        }
        post("/api/projects/{id}/codex") {
            if (launcher == null || token == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Launching is disabled on this server"))
                return@post
            }
            if (!token.accepts(call.request.headers[HttpHeaders.Authorization])) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Missing or wrong access token"))
                return@post
            }
            val id = call.parameters["id"].orEmpty()
            val result = withContext(Dispatchers.IO) { launcher.launch(id) }
            if (result is CodexLauncher.Result.Started) {
                call.respond(HttpStatusCode.Created, LaunchResponse(result.session, result.branch, result.path))
                return@post
            }
            val (status, message) = when (result) {
                is CodexLauncher.Result.Started -> error("handled above")
                CodexLauncher.Result.UnknownProject -> HttpStatusCode.NotFound to "Unknown project"
                CodexLauncher.Result.CodexUnavailable -> HttpStatusCode.ServiceUnavailable to "Codex CLI is not installed on the server"
                CodexLauncher.Result.TooManyRunning -> HttpStatusCode.TooManyRequests to "Too many Codex sessions are running; exit one first"
                is CodexLauncher.Result.Failed -> HttpStatusCode.InternalServerError to "Could not start Codex: ${result.reason}"
            }
            call.respond(status, ApiError(message))
        }
        get(ApkFile.URL) {
            val file = apk?.file?.takeIf { it.isFile }
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("The Android app has not been built on this server"))
                return@get
            }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"llm-manager.apk\"")
            call.respondFile(file)
        }
        // Shape-checked by clients so a captive portal's 200 HTML is not mistaken for the server.
        get("/readyz") {
            call.respond(Readiness("ready"))
        }
    }
}

@Serializable
private data class Readiness(val status: String)

@Serializable
data class ApiError(val error: String)

@Serializable
data class LaunchResponse(val session: CodexSession, val branch: String, val path: String)

private object DashboardResources {
    fun page(): String = checkNotNull(javaClass.getResource("/dashboard/index.html")) { "dashboard page missing" }.readText()
}

/** The page runs one inline script and talks only to its own origin. */
private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        with(call.response.headers) {
            append(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; " +
                    "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
            append("X-Content-Type-Options", "nosniff")
            append("Referrer-Policy", "no-referrer")
            append(HttpHeaders.CacheControl, "no-store")
        }
    }
}
