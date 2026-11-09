package com.llmmanager.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** What the dashboard can do beyond listing projects; each part is optional. */
class DashboardServices(
    val launcher: CodexLauncher? = null,
    val apk: ApkFile? = null,
    val schedule: ScheduleApi? = null,
)

/**
 * The dashboard's HTTP surface.
 *
 * Open: the static page, `/readyz` and the APK download, none of which carries data.
 * Everything under `/api` needs [token]. Requests must name an [allowedHosts] host
 * (the bind address or the machine's tailnet names), which defeats DNS rebinding.
 */
fun Application.dashboardModule(
    scanner: ProjectScanner,
    token: ApiToken,
    services: DashboardServices = DashboardServices(),
    /** `host:port` values accepted in the Host header; null disables the check (tests only). */
    allowedHosts: Set<String>? = null,
) {
    val snapshots = TtlCache(SNAPSHOT_TTL_MS) { projectsResponse(scanner, services) }

    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; explicitNulls = true })
    }
    install(JsonErrors)
    guards(allowedHosts)

    routing {
        get("/") {
            call.respondText(DashboardResources.page, ContentType.Text.Html.withParameter("charset", "utf-8"))
        }
        get("/api/projects") {
            if (!call.authorized(token)) return@get
            call.respond(withContext(Dispatchers.IO) { snapshots.get() })
        }
        services.launcher?.let { codexRoute(it, token, snapshots) }
        // Open: a browser download link cannot carry the token, and the APK holds no secret.
        get(ApkFile.URL) {
            val file = services.apk?.file?.takeIf { it.isFile }
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("The Android app has not been built on this server"))
                return@get
            }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"llm-manager.apk\"")
            call.respondFile(file)
        }
        services.schedule?.let { scheduleRoutes(it, token, onChange = snapshots::invalidate) }
        // Shape-checked by clients so a captive portal's 200 HTML is not mistaken for the server.
        get("/readyz") {
            call.respond(Readiness("ready"))
        }
    }
}

/**
 * Order matters: headers first, so every response carries them; then the guards, which
 * end the call when they refuse it ([finish]), so neither later plugins nor routes run.
 */
private fun Application.guards(allowedHosts: Set<String>?) {
    intercept(ApplicationCallPipeline.Plugins) { securityHeaders(call) }
    allowedHosts?.map { it.lowercase() }?.toSet()?.let { hosts ->
        intercept(ApplicationCallPipeline.Plugins) {
            val host = call.request.headers[HttpHeaders.Host]?.lowercase()
            if (host == null || host !in hosts) {
                call.respond(MISDIRECTED, ApiError("This server does not answer for that host name"))
                finish()
            }
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        bodyProblem(call)?.let { (status, message) ->
            call.respond(status, ApiError(message))
            finish()
        }
    }
}

private fun Route.codexRoute(launcher: CodexLauncher, token: ApiToken, snapshots: TtlCache<*>) {
    post("/api/projects/{id}/codex") {
        if (!call.authorized(token)) return@post
        val result = withContext(Dispatchers.IO) { launcher.launch(call.parameters["id"].orEmpty()) }
        snapshots.invalidate()
        when (result) {
            is CodexLauncher.Result.Started -> call.respond(HttpStatusCode.Created, LaunchResponse(result.session, result.branch, result.path))
            CodexLauncher.Result.UnknownProject -> call.respond(HttpStatusCode.NotFound, ApiError("Unknown project"))
            CodexLauncher.Result.CodexUnavailable -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Codex CLI is not installed on the server"))
            CodexLauncher.Result.TooManyRunning ->
                call.respond(HttpStatusCode.TooManyRequests, ApiError("Too many Codex sessions are running; exit one first"))
            is CodexLauncher.Result.Failed -> call.respond(HttpStatusCode.InternalServerError, ApiError("Could not start Codex: ${result.reason}"))
        }
    }
}

/** Responds 401 and returns false unless the request carries the access token. */
internal suspend fun ApplicationCall.authorized(token: ApiToken): Boolean {
    if (token.accepts(request.headers[HttpHeaders.Authorization])) return true
    respond(HttpStatusCode.Unauthorized, ApiError("Missing or wrong access token"))
    return false
}

/** The overview: projects with analytics, publish state for agent branches, and the APK when built. */
private fun projectsResponse(scanner: ProjectScanner, services: DashboardServices): ProjectsResponse {
    val snapshot = scanner.snapshot()
    val projects = services.schedule?.branches?.annotate(snapshot.projects) ?: snapshot.projects
    val now = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    return ProjectsResponse(now, services.launcher?.available == true, projects, snapshot.analytics, services.apk?.describe())
}

/**
 * A value recomputed at most every [ttlMillis]. Scans spawn many git processes; this keeps
 * several polling clients from multiplying that work.
 */
class TtlCache<T : Any>(private val ttlMillis: Long, private val compute: () -> T) {
    private var value: T? = null
    private var at = 0L

    @Synchronized
    fun get(): T {
        val now = System.nanoTime()
        val cached = value
        if (cached != null && now - at < ttlMillis * NANOS_PER_MILLI) return cached
        return compute().also {
            value = it
            at = now
        }
    }

    @Synchronized
    fun invalidate() {
        value = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

@Serializable
private data class Readiness(val status: String)

@Serializable
data class ApiError(val error: String)

@Serializable
data class LaunchResponse(val session: CodexSession, val branch: String, val path: String)

private const val SNAPSHOT_TTL_MS = 5_000L
private const val MAX_BODY_BYTES = 64L * 1024
private val MISDIRECTED = HttpStatusCode(421, "Misdirected Request")

internal object DashboardResources {
    val page: String = checkNotNull(javaClass.getResource("/dashboard/index.html")) { "dashboard page missing" }.readText()

    /** CSP source allowing exactly the page's one inline script, and nothing injected beside it. */
    val scriptHash: String = run {
        val script = checkNotNull(Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page)) { "page has no script" }.groupValues[1]
        "'sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))) + "'"
    }
}

private fun securityHeaders(call: ApplicationCall) {
    with(call.response.headers) {
        append(
            "Content-Security-Policy",
            "default-src 'none'; script-src ${DashboardResources.scriptHash}; style-src 'unsafe-inline'; connect-src 'self'; " +
                "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
        append("X-Content-Type-Options", "nosniff")
        append("X-Frame-Options", "DENY")
        append("Referrer-Policy", "no-referrer")
        append("Cross-Origin-Opener-Policy", "same-origin")
        append("Cross-Origin-Resource-Policy", "same-origin")
        append("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
        append(HttpHeaders.CacheControl, "no-store")
    }
}

/** Request bodies are small JSON: large or unsized (chunked) ones are refused before reading. */
private fun bodyProblem(call: ApplicationCall): Pair<HttpStatusCode, String>? {
    val method = call.request.httpMethod
    if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) return null
    val length = call.request.contentLength()
    return when {
        call.request.headers[HttpHeaders.TransferEncoding] != null -> HttpStatusCode.LengthRequired to "Send a Content-Length"
        length != null && length > MAX_BODY_BYTES -> HttpStatusCode.PayloadTooLarge to "Request body too large"
        else -> null
    }
}

/** Unexpected failures: logged here, a plain JSON error for the client, no details leaked. */
private val JsonErrors = createApplicationPlugin("JsonErrors") {
    val log = LoggerFactory.getLogger("com.llmmanager.server.api.http")
    on(CallFailed) { call, cause ->
        log.error("Request {} {} failed", call.request.httpMethod.value, call.request.path(), cause)
        call.respond(HttpStatusCode.InternalServerError, ApiError("Internal error"))
    }
}
