package com.llmmanager.server.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
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

    private val secret = "t".repeat(40)
    private val token = ApiToken(secret)

    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $secret")

    private fun scanner(sessions: SessionHost = SessionHost.None): ProjectScanner {
        val repo = directory.resolve("repos/demo").toFile().also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "initial")
        git(repo, "remote", "add", "origin", "https://github.com/acme/demo.git")
        return ProjectScanner(listOf("${directory.resolve("repos")}/*"), sessions = sessions)
    }

    private fun scheduleApi(scanner: ProjectScanner): ScheduleApi {
        val state = directory.resolve("state").toFile()
        val store = StateStore(state)
        val workspace = Workspace(scanner, SessionHost.None, AgentCommands(null, null))
        val scheduler = Scheduler(store, RunEngine(store, workspace, directory.resolve("runs").toFile()), SessionHost.None)
        val jobs = JobService(store, scheduler, workspace, SessionCatalog(directory.resolve("home").toFile()))
        val credentials = ForgeCredentials(directory.resolve("tokens").toFile(), gh = null)
        val branches = BranchService(store, BranchStore(state), workspace, ForgeClient(credentials), credentials)
        return ScheduleApi(jobs, store, UsageReader(directory.toFile(), state), branches)
    }

    @Test
    fun `projects endpoint returns JSON to token holders`() = testApplication {
        val scanner = scanner()
        application { dashboardModule(scanner, token) }

        val response = client.get("/api/projects") { auth() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.Json))
        assertEquals("no-store", response.headers["Cache-Control"])
        val project = Json.decodeFromString<ProjectsResponse>(response.bodyAsText()).projects.single()
        assertEquals("acme/demo", project.name)
        assertEquals("https://github.com/acme/demo", project.gitHubUrl)
        assertEquals("main", project.worktrees.single().branch)
    }

    @Test
    fun `every data endpoint needs the token, with the Bearer scheme`() = testApplication {
        val scanner = scanner()
        application { dashboardModule(scanner, token, DashboardServices(schedule = scheduleApi(scanner))) }

        for (path in listOf("/api/projects", "/api/usage", "/api/schedule", "/api/runs/x/log")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, secret) }.status, "raw token $path")
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, "Bearer wrong") }.status, path)
        }
        val writes = listOf("/api/jobs", "/api/jobs/x/run", "/api/jobs/x/delete", "/api/runs/x/cancel", "/api/projects/x/branches/push")
        for (path in writes) {
            assertEquals(HttpStatusCode.Unauthorized, client.post(path).status, path)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/usage") { auth() }.status)
        val overview = Json.decodeFromString<ScheduleResponse>(client.get("/api/schedule") { auth() }.bodyAsText())
        assertEquals(mapOf("claude" to false, "codex" to false, "gemini" to false), overview.agents)
        assertEquals("off", overview.sandbox!!.mode)
        val refused = client.post("/api/jobs") {
            auth()
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"projectId":"${scanner.scan().single().id}","kind":"run","agent":"claude","prompt":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("not installed"))
    }

    @Test
    fun `the page, readiness and the apk stay open and carry no data`() = testApplication {
        val apkFile = directory.resolve("app-release.apk").toFile().apply { writeBytes(ByteArray(1234) { 7 }) }
        application { dashboardModule(ProjectScanner(emptyList()), token, DashboardServices(apk = ApkFile(apkFile))) }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals("""{"status":"ready"}""", client.get("/readyz").bodyAsText())
        val download = client.get(ApkFile.URL)
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(ApkFile.MEDIA_TYPE, download.contentType()?.withoutParameters()?.toString())
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.contains("llm-manager.apk"))
        assertEquals(1234, download.bodyAsText(Charsets.ISO_8859_1).length)
    }

    @Test
    fun `root serves the page under a CSP that allows only its own script`() = testApplication {
        application { dashboardModule(scanner(), token) }

        val response = client.get("/")

        assertTrue(response.contentType()!!.match(ContentType.Text.Html))
        val html = response.bodyAsText()
        assertTrue(html.contains("/api/projects") && html.contains("color-scheme"))
        // No CDNs or other external resources.
        assertFalse(Regex("""(src|href)\s*=\s*["']https?://""").containsMatchIn(html))
        val csp = response.headers["Content-Security-Policy"]!!
        val script = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        assertTrue(csp.contains("default-src 'none'") && csp.contains("script-src 'sha256-$hash'"), csp)
        assertFalse(Regex("script-src[^;]*unsafe-inline").containsMatchIn(csp), csp)
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("same-origin", response.headers["Cross-Origin-Resource-Policy"])
        // Exactly one inline script, and no inline event-handler attributes the hash would not cover.
        assertEquals(1, Regex("<script").findAll(html).count())
        assertFalse(Regex("""\son[a-z]+\s*=\s*["']""").containsMatchIn(html.replace(script, "")))
    }

    @Test
    fun `a refused host never reaches the route, even with a valid token`() = testApplication {
        val sessions = object : SessionHost {
            val started = mutableListOf<String>()
            override fun list() = started.toSet()
            override fun start(name: String, directory: File, command: List<String>) = started.add(name)
        }
        val scanner = scanner(sessions)
        val launcher = CodexLauncher(
            scanner, sessions,
            CodexLauncher.Settings(directory.resolve("codex").toFile(), "/opt/codex", scriptDir = directory.resolve("scripts").toFile()),
        )
        application { dashboardModule(scanner, token, DashboardServices(launcher), allowedHosts = setOf("box:8080")) }

        val refused = client.post("/api/projects/${scanner.scan().single().id}/codex") { auth(); header(HttpHeaders.Host, "rebound.example") }

        assertEquals(421, refused.status.value)
        assertTrue(sessions.started.isEmpty(), "the route must not run")
        assertTrue(refused.headers["Content-Security-Policy"] != null, "refusals carry the security headers too")
    }

    @Test
    fun `requests for other host names are refused, which defeats DNS rebinding`() = testApplication {
        application { dashboardModule(ProjectScanner(emptyList()), token, allowedHosts = setOf("100.101.102.103:8080", "box.tail.ts.net:8080")) }

        assertEquals(421, client.get("/readyz").status.value) // test client sends Host: localhost
        assertEquals(421, client.get("/") { header(HttpHeaders.Host, "attacker.example:8080") }.status.value)
        assertEquals(HttpStatusCode.OK, client.get("/readyz") { header(HttpHeaders.Host, "100.101.102.103:8080") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/readyz") { header(HttpHeaders.Host, "BOX.tail.ts.net:8080") }.status)
    }

    /** Sends one raw HTTP/1.1 request to a real Netty server and returns the status line. */
    private fun raw(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(request.replace("\n", "\r\n").toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun `over real HTTP, wrong hosts, oversized and chunked bodies are refused before reading`() {
        val scanner = scanner()
        val api = scheduleApi(scanner)
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            dashboardModule(scanner, token, DashboardServices(schedule = api), allowedHosts = setOf("127.0.0.1:$port"))
        }.start(wait = false)
        try {
            val host = "Host: 127.0.0.1"
            assertTrue(raw(port, "GET /readyz HTTP/1.1\nHost: evil.example:$port\nConnection: close\n\n").contains(" 421 "))
            assertTrue(raw(port, "GET /readyz HTTP/1.1\n$host:$port\nConnection: close\n\n").contains(" 200 "))
            val chunked = "POST /api/jobs HTTP/1.1\n$host:$port\nAuthorization: Bearer $secret\nContent-Type: application/json\n" +
                "Transfer-Encoding: chunked\nConnection: close\n\n2\n{}\n0\n\n"
            assertTrue(raw(port, chunked).contains(" 411 "))
            val big = "POST /api/jobs HTTP/1.1\n$host:$port\nAuthorization: Bearer $secret\nContent-Type: application/json\n" +
                "Content-Length: ${70 * 1024}\nConnection: close\n\n"
            assertTrue(raw(port, big).contains(" 413 "))
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `no route accepts a write it does not define`() = testApplication {
        application { dashboardModule(scanner(), token) }

        for (path in listOf("/", "/api/projects", "/readyz", "/api/runs")) {
            for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) {
                val status = client.request(path) { this.method = method; auth() }.status
                assertFalse(status.value in 200..299, "$method $path returned $status")
            }
        }
    }

    @Test
    fun `starting codex requires the token and a known project`() = testApplication {
        val sessions = object : SessionHost {
            val started = mutableListOf<String>()
            override fun list() = started.toSet()
            override fun start(name: String, directory: File, command: List<String>) = started.add(name)
        }
        val scanner = scanner(sessions)
        val launcher = CodexLauncher(
            scanner, sessions,
            CodexLauncher.Settings(directory.resolve("codex").toFile(), "/opt/codex", scriptDir = directory.resolve("scripts").toFile()),
        )
        application { dashboardModule(scanner, token, DashboardServices(launcher)) }
        val id = scanner.scan().single().id

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/projects/$id/codex").status)
        assertTrue(sessions.started.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/projects/nope/codex") { auth() }.status)

        val response = client.post("/api/projects/$id/codex") { auth() }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<LaunchResponse>(response.bodyAsText())
        assertEquals(sessions.started.single(), body.session.name)
        // The session runs a generated script that drops the inherited environment before starting Codex.
        val script = directory.resolve("scripts/${body.session.name}.sh").toFile().readText()
        assertTrue(script.contains("exec env -i \"\${keep[@]}\" '/opt/codex' '--sandbox' 'workspace-write'"), script)
        val listed = Json.decodeFromString<ProjectsResponse>(client.get("/api/projects") { auth() }.bodyAsText())
        assertTrue(listed.codexAvailable)
        assertTrue(listed.projects.single().worktrees.any { it.codex?.running == true })
    }

    @Test
    fun `apk is offered in the overview once it exists`() = testApplication {
        val apkFile = directory.resolve("app-release.apk").toFile()
        val scanner = scanner()
        application { dashboardModule(scanner, token, DashboardServices(apk = ApkFile(apkFile))) }

        assertEquals(null, Json.decodeFromString<ProjectsResponse>(client.get("/api/projects") { auth() }.bodyAsText()).androidApp)
        assertEquals(HttpStatusCode.NotFound, client.get(ApkFile.URL).status)
    }

    private fun git(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
