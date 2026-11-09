package com.llmmanager.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The read-only dashboard. Every route is a GET; there is deliberately no way to
 * start a run, write a file, or change git state through this server.
 */
fun Application.dashboardModule(scanner: ProjectScanner) {
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
            val projects = withContext(Dispatchers.IO) { scanner.scan() }
            call.respond(ProjectsResponse(OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), projects))
        }
        // Shape-checked by clients so a captive portal's 200 HTML is not mistaken for the server.
        get("/readyz") {
            call.respond(Readiness("ready"))
        }
    }
}

@Serializable
private data class Readiness(val status: String)

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
