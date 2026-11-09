package com.llmmanager.server.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

class DashboardModuleTest {
    @TempDir
    lateinit var directory: Path

    private fun scanner(sessions: SessionHost = SessionHost.None): ProjectScanner {
        val repo = directory.resolve("repos/demo").toFile().also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "initial")
        git(repo, "remote", "add", "origin", "https://github.com/acme/demo.git")
        return ProjectScanner(listOf("${directory.resolve("repos")}/*"), sessions = sessions)
    }

    @Test
    fun `projects endpoint returns JSON`() = testApplication {
        val scanner = scanner()
        application { dashboardModule(scanner) }

        val response = client.get("/api/projects")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.Json))
        assertEquals("no-store", response.headers["Cache-Control"])
        val body = Json.decodeFromString<ProjectsResponse>(response.bodyAsText())
        val project = body.projects.single()
        assertEquals("acme/demo", project.name)
        assertEquals("https://github.com/acme/demo", project.gitHubUrl)
        assertEquals("main", project.worktrees.single().branch)
    }

    @Test
    fun `root serves the self-contained dashboard page`() = testApplication {
        val scanner = scanner()
        application { dashboardModule(scanner) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Text.Html))
        val html = response.bodyAsText()
        assertTrue(html.contains("/api/projects"))
        assertTrue(html.contains("color-scheme"))
        // No CDNs or other external resources; the CSP enforces the same on the client.
        assertFalse(Regex("""(src|href)\s*=\s*["']https?://""").containsMatchIn(html))
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
    }

    @Test
    fun `no route accepts a write`() = testApplication {
        val scanner = scanner()
        application { dashboardModule(scanner) }

        for (path in listOf("/", "/api/projects", "/readyz", "/api/runs")) {
            for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) {
                val status = client.request(path) { this.method = method }.status
                assertFalse(status.value in 200..299, "$method $path returned $status")
            }
        }
    }

    @Test
    fun `starting codex requires the token and a known project`() = testApplication {
        val token = ApiToken("t".repeat(40))
        val sessions = object : SessionHost {
            val started = mutableListOf<String>()
            override fun list() = started.toSet()
            override fun start(name: String, directory: File, command: List<String>) = started.add(name)
        }
        val scanner = scanner(sessions)
        val launcher = CodexLauncher(scanner, sessions, CodexLauncher.Settings(directory.resolve("codex").toFile(), "/opt/codex"))
        application { dashboardModule(scanner, launcher, token) }
        val id = scanner.scan().single().id

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/projects/$id/codex").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/projects/$id/codex") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
        assertTrue(sessions.started.isEmpty())
        val unknown = client.post("/api/projects/nope/codex") { header(HttpHeaders.Authorization, "Bearer ${"t".repeat(40)}") }
        assertEquals(HttpStatusCode.NotFound, unknown.status)

        val response = client.post("/api/projects/$id/codex") { header(HttpHeaders.Authorization, "Bearer ${"t".repeat(40)}") }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<LaunchResponse>(response.bodyAsText())
        assertEquals(sessions.started.single(), body.session.name)
        val listed = Json.decodeFromString<ProjectsResponse>(client.get("/api/projects").bodyAsText())
        assertTrue(listed.codexAvailable)
        assertTrue(listed.projects.single().worktrees.any { it.codex?.running == true })
    }

    @Test
    fun `readiness reports JSON`() = testApplication {
        application { dashboardModule(ProjectScanner(emptyList())) }

        val response = client.get("/readyz")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ready"}""", response.bodyAsText())
    }

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
