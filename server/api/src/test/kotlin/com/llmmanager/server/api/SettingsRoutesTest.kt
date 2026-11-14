package com.llmmanager.server.api

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class SettingsRoutesTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val secret = "t".repeat(40)
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $secret")

    private fun HttpRequestBuilder.body(text: String) {
        auth()
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    /** A GitLab and an Azure DevOps that accept exactly one token each. */
    private fun forgeServer(): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api/v4/user") { exchange ->
            val ok = exchange.requestHeaders.getFirst("PRIVATE-TOKEN") == "glpat-good-token-123456"
            val body = if (ok) """{"username":"gitlab-me"}""" else """{"message":"401 Unauthorized"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (ok) 200 else 401, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/acme/_apis/connectionData") { exchange ->
            val good = "Basic " + java.util.Base64.getEncoder().encodeToString(":${"a".repeat(52)}".toByteArray())
            val user = if (exchange.requestHeaders.getFirst("Authorization") == good) {
                """{"providerDisplayName":"Ada","descriptor":"Microsoft.IdentityModel.Claims.ClaimsIdentity;x"}"""
            } else {
                """{"providerDisplayName":"Anonymous","descriptor":"Microsoft.TeamFoundation.UnauthenticatedIdentity;S-1-0-0"}"""
            }
            val body = """{"authenticatedUser":$user}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private class Gh : CommandRunner {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        override fun run(command: List<String>, input: String?): Processes.Exec {
            calls += command to input
            return when {
                command.contains("status") && command.contains("github.com") ->
                    Processes.Exec(0, "github.com\n  ✓ Logged in to github.com account octo-me (keyring)\n  - Token scopes: 'repo', 'read:org'\n", "")
                command.contains("status") -> Processes.Exec(1, "", "Not logged in")
                else -> Processes.Exec(0, "", "")
            }
        }
    }

    private val gh = Gh()

    private fun settingsApi(forgeUrl: String = "http://127.0.0.1:9"): SettingsApi {
        val preferences = PreferencesStore(File(root, "state").apply { mkdirs() })
        val binary = { agent: Agent -> "/opt/${agent.name.lowercase()}" }
        val accounts = AgentAccounts(binary, File(root, "home"), gh, { null }, ttlMillis = 0)
        val terminals = object : Terminals {
            var screen: String? = null
            override fun open(name: String, directory: File, command: List<String>) = true.also { screen = "" }
            override fun screen(name: String) = screen?.let { "https://auth.openai.com/codex/device\n   WXYZ-12345\n" }
            override fun type(name: String, text: String) = true
            override fun close(name: String) {
                screen = null
            }
        }
        val signIn = AgentSignIn(terminals, accounts, binary, File(root, "state/sign-in"))
        val credentials = ForgeCredentials(File(root, "tokens").apply { mkdirs() }, gh = "/opt/gh")
        val forges = ForgeAccounts(credentials, preferences, gh, gitlabApi = { "$forgeUrl/api/v4" }, azureApi = forgeUrl)
        return SettingsApi(preferences, accounts, signIn, forges)
    }

    private fun settings(text: String) = json.decodeFromString<SettingsResponse>(text)

    @Test
    fun `every settings route needs the token`() = testApplication {
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = settingsApi())) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/settings").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/settings/agents/codex/sign-in").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/settings/agents/codex/sign-in").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/settings/agents/codex/sign-out").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/settings/agents/codex/sign-in").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/settings/profile").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/settings/forges/gitlab").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/settings/forges/gitlab").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/settings/agents/nobody/sign-in") { auth() }.status)
    }

    @Test
    fun `profile and agent preferences are validated and saved`() = testApplication {
        val api = settingsApi()
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = api)) }

        val missingEmail = client.put("/api/settings/profile") { body("""{"name":"Ada","email":""}""") }
        assertEquals(HttpStatusCode.BadRequest, missingEmail.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/settings/profile") { body("""{"name":"Ada <x>","email":"ada@example.com"}""") }.status)
        val saved = client.put("/api/settings/profile") { body("""{"name":" Ada ","email":"ada@example.com","defaultAgent":"gemini"}""") }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(Profile("Ada", "ada@example.com"), settings(saved.bodyAsText()).profile)
        assertEquals(Profile("Ada", "ada@example.com"), api.preferences.committer())

        for (model in listOf("--dangerously-skip-permissions", "-m", "a b", "x;y")) {
            assertEquals(HttpStatusCode.BadRequest, client.put("/api/settings/agents/claude") { body("""{"enabled":true,"model":"$model"}""") }.status, model)
        }
        val agent = client.put("/api/settings/agents/gemini") { body("""{"enabled":false,"model":"gemini-2.5-pro"}""") }
        val gemini = settings(agent.bodyAsText()).agents.single { it.agent == Agent.GEMINI }
        assertEquals(false to "gemini-2.5-pro", gemini.enabled to gemini.model)
        assertEquals("gemini-2.5-pro", api.preferences.model(Agent.GEMINI))
        assertTrue(PreferencesStore(File(root, "state")).get().agents.getValue(Agent.GEMINI).model == "gemini-2.5-pro", "kept across restarts")
    }

    @Test
    fun `the sign-in pop-up over HTTP`() = testApplication {
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = settingsApi())) }

        val started = json.decodeFromString<SignInView>(client.post("/api/settings/agents/codex/sign-in") { body("{}") }.bodyAsText())
        assertEquals(AgentSignIn.WAITING, started.state)
        assertEquals("WXYZ-12345", started.userCode)
        val listed = settings(client.get("/api/settings") { auth() }.bodyAsText()).agents.single { it.agent == Agent.CODEX }
        assertEquals("WXYZ-12345", listed.signIn?.userCode, "the settings page can pick the sign-in up again")
        assertEquals(AgentSignIn.IDLE, json.decodeFromString<SignInView>(client.delete("/api/settings/agents/codex/sign-in") { auth() }.bodyAsText()).state)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "checks POSIX permissions")
    fun `code host tokens are checked with the host, saved owner-only, and never sent back`() = testApplication {
        val url = forgeServer()
        val api = settingsApi(url)
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = api)) }

        val wrong = client.put("/api/settings/forges/gitlab") { body("""{"token":"glpat-wrong-token-12345"}""") }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertFalse(File(root, "tokens/gitlab-token").exists(), "a refused token is not saved")
        val elsewhere = client.put("/api/settings/forges/gitlab") { body("""{"token":"glpat-good-token-123456","host":"evil.example"}""") }
        assertEquals(HttpStatusCode.BadRequest, elsewhere.status, "a token only goes to GitLab hosts")

        val saved = client.put("/api/settings/forges/gitlab") { body("""{"token":"glpat-good-token-123456"}""") }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val file = File(root, "tokens/gitlab-token")
        assertEquals("glpat-good-token-123456\n", file.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        val gitlab = settings(saved.bodyAsText()).forges.single { it.forge == "gitlab" }
        assertEquals(true to "gitlab-me", gitlab.connected to gitlab.account)

        val anonymous = client.put("/api/settings/forges/azure-devops") { body("""{"token":"${"b".repeat(52)}","organization":"acme"}""") }
        assertEquals(HttpStatusCode.BadRequest, anonymous.status)
        val azure = client.put("/api/settings/forges/azure-devops") { body("""{"token":"${"a".repeat(52)}","organization":"acme"}""") }
        assertEquals("Ada (acme)", settings(azure.bodyAsText()).forges.single { it.forge == "azure-devops" }.account)

        val page = client.get("/api/settings") { auth() }.bodyAsText()
        assertFalse(page.contains("glpat-good") || page.contains("a".repeat(52)), "no token is ever returned")
        assertEquals("octo-me", settings(page).forges.single { it.forge == "github" }.account)

        val removed = client.delete("/api/settings/forges/gitlab") { auth() }
        assertFalse(file.exists())
        assertFalse(settings(removed.bodyAsText()).forges.single { it.forge == "gitlab" }.connected)
    }

    @Test
    fun `github sign-in hands the token to gh on stdin`() = testApplication {
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = settingsApi())) }

        assertEquals(HttpStatusCode.BadRequest, client.put("/api/settings/forges/github") { body("""{"token":"ghp_x y"}""") }.status)
        val response = client.put("/api/settings/forges/github") { body("""{"token":"test_token_abcdefghijklmnopqrstuvwxyz0123"}""") }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val login = gh.calls.single { it.first.first() == "/opt/gh" && it.first.contains("login") }
        assertEquals(listOf("/opt/gh", "auth", "login", "--hostname", "github.com", "--with-token"), login.first)
        assertEquals("test_token_abcdefghijklmnopqrstuvwxyz0123", login.second)
        client.delete("/api/settings/forges/github") { auth() }
        assertEquals(listOf("/opt/gh", "auth", "logout", "--hostname", "github.com", "--user", "octo-me"), gh.calls.single { "logout" in it.first }.first)
    }

    @Test
    fun `gitlab hosts are the ones the forge parser accepts`() {
        for (host in listOf("gitlab.com", "gitlab.example.com", "code.gitlab.acme.io")) {
            assertTrue(ForgeAccounts.isGitLabHost(host), host)
        }
        for (host in listOf("evil.example", "gitlab..com", "-gitlab.com", "../gitlab", "gitlab.com/x", "GitLab.com", "")) {
            assertFalse(ForgeAccounts.isGitLabHost(host), host)
        }
    }

    @Test
    fun `models reach each CLI as an option value, and only once chosen`() {
        val models = mapOf(Agent.CLAUDE to "opus", Agent.CODEX to "gpt-5-codex", Agent.GEMINI to "gemini-2.5-pro")
        val chosen = AgentCommands("/c", "/x", gemini = "/g", model = models::get)
        val plain = AgentCommands("/c", "/x", gemini = "/g")

        assertTrue(chosen.work(Agent.CLAUDE, "s").windowed(2).contains(listOf("--model", "opus")))
        assertEquals(listOf("/x", "-c", "model=\"gpt-5-codex\"", "exec"), chosen.work(Agent.CODEX, "").take(4))
        assertEquals(listOf("/x", "-c", "model=\"gpt-5-codex\"", "review"), chosen.review(Agent.CODEX, "abc").take(4))
        assertTrue(chosen.resume(Agent.GEMINI, "s").windowed(2).contains(listOf("--model", "gemini-2.5-pro")))
        assertTrue(Agent.entries.none { agent -> plain.work(agent, "s").any { it.contains("model") } })
    }
}
