package com.llmmanager.server.api

import io.ktor.client.request.HttpRequestBuilder
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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

/** Pins and stars, adding a repository by its address, and the models offered in settings. */
class ProjectRoutesTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val secret = "t".repeat(40)
    private val json = Json { ignoreUnknownKeys = true }

    private fun HttpRequestBuilder.body(text: String) {
        header(HttpHeaders.Authorization, "Bearer $secret")
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    private fun repo(name: String, parent: String = "repos"): File {
        val repo = File(root, "$parent/$name").also { it.mkdirs() }
        gitOutput(repo, "init", "-b", "main")
        gitOutput(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "initial")
        return repo
    }

    private fun scanner() = ProjectScanner(listOf("${root.path}/repos/*"))

    private fun settingsApi(models: AgentModels? = null): SettingsApi {
        val preferences = PreferencesStore(File(root, "state").apply { mkdirs() })
        val binary = { agent: Agent -> "/opt/${agent.name.lowercase()}" }
        val accounts = AgentAccounts(binary, File(root, "home"), { _, _ -> Processes.Exec(1, "", "Not logged in") }, { null }, ttlMillis = 0)
        val terminals = object : Terminals {
            override fun open(name: String, directory: File, command: List<String>) = false
            override fun screen(name: String): String? = null
            override fun type(name: String, text: String) = false
            override fun close(name: String) = Unit
        }
        return SettingsApi(preferences, accounts, AgentSignIn(terminals, accounts, binary, File(root, "state/sign-in")), models = models)
    }

    private fun projects(text: String) = json.decodeFromString<ProjectsResponse>(text)

    @Test
    fun `pinned repositories come first, then the most stars`() = testApplication {
        listOf("a", "b", "c", "d").forEach(::repo)
        val scanner = scanner()
        val api = settingsApi()
        application { dashboardModule(scanner, ApiToken(secret), DashboardServices(settings = api)) }
        val ids = scanner.scan().associate { it.name to it.id }
        val before = projects(client.get("/api/projects") { body("") }.bodyAsText()).projects.map { it.name }

        assertEquals(HttpStatusCode.OK, client.put("/api/projects/${ids["b"]}/preference") { body("""{"stars":2}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/projects/${ids["d"]}/preference") { body("""{"stars":5}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/projects/${ids["c"]}/preference") { body("""{"pinned":true}""") }.status)

        val listed = projects(client.get("/api/projects") { body("") }.bodyAsText()).projects
        assertEquals(listOf("c", "d", "b") + (before - setOf("b", "c", "d")), listed.map { it.name })
        assertTrue(listed.first().pinned)
        assertEquals(listOf(0, 5, 2, 0), listed.map { it.stars })

        client.put("/api/projects/${ids["c"]}/preference") { body("""{"pinned":false,"stars":0}""") }
        assertFalse(ids["c"] in api.preferences.get().repos, "nothing is kept for a repository at the default")
    }

    @Test
    fun `a preference needs the token, a known repository and 0 to 5 stars`() = testApplication {
        repo("a")
        val scanner = scanner()
        application { dashboardModule(scanner, ApiToken(secret), DashboardServices(settings = settingsApi())) }
        val id = scanner.scan().single().id

        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/projects/$id/preference").status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/projects/$id/preference") { body("""{"stars":6}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/projects/$id/preference") { body("""{"stars":-1}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/projects/$id/preference") { body("nonsense") }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/projects/0000000000000000/preference") { body("""{"stars":1}""") }.status)
    }

    @Test
    fun `a repository is added by its address and then listed`() = testApplication {
        val upstream = repo("upstream", parent = "forge")
        val scanner = scanner()
        val credentials = ForgeCredentials(File(root, "tokens").apply { mkdirs() }, gh = null)
        val cloner = RepoCloner(File(root, "repos"), credentials, scanner, means = RepoCloner.Means(source = { upstream.path }, background = { it() }))
        application { dashboardModule(scanner, ApiToken(secret), DashboardServices(cloner = cloner, serverChat = true)) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/projects/clone").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/projects/clones").status)
        val refused = client.post("/api/projects/clone") { body("""{"url":"https://example.com/acme/demo"}""") }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("GitHub, GitLab or Azure DevOps"))

        val started = client.post("/api/projects/clone") { body("""{"url":"github.com/acme/demo"}""") }

        assertEquals(HttpStatusCode.Accepted, started.status)
        val clones = json.decodeFromString<ClonesResponse>(client.get("/api/projects/clones") { body("") }.bodyAsText()).clones
        assertEquals(RepoCloner.DONE, clones.single().state, clones.single().message)
        val response = projects(client.get("/api/projects") { body("") }.bodyAsText())
        assertTrue(response.canClone && response.serverChat)
        assertEquals(clones.single().projectId, response.projects.single().id)
    }

    @Test
    fun `settings offer each agent's models, and keep one saved earlier`() = testApplication {
        val api = settingsApi(AgentModels(File(root, "home"), { null }))
        application { dashboardModule(ProjectScanner(emptyList()), ApiToken(secret), DashboardServices(settings = api)) }

        client.put("/api/settings/agents/claude") { body("""{"enabled":true,"model":"claude-custom-1"}""") }
        client.put("/api/settings/agents/codex") { body("""{"enabled":true,"model":""}""") }

        val agents = json.decodeFromString<SettingsResponse>(client.get("/api/settings") { body("") }.bodyAsText()).agents.associateBy { it.agent }
        assertEquals(listOf("fable", "opus", "sonnet", "haiku", "claude-custom-1"), agents.getValue(Agent.CLAUDE).models.map { it.id })
        assertTrue(agents.getValue(Agent.CODEX).models.isNotEmpty())
        assertEquals("", agents.getValue(Agent.CODEX).model)
    }
}
