package com.llmmanager.server.api

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class IssuesTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private var server: HttpServer? = null

    /** Method, address and body of each request the fake forge saw. */
    private val requests = mutableListOf<Triple<String, String, String>>()
    private val authorization = mutableListOf<String?>()

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun fakeApi(headers: Map<String, String> = emptyMap(), routes: (method: String, url: String, body: String) -> Pair<Int, String>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val sent = exchange.requestBody.readBytes().decodeToString()
            requests += Triple(exchange.requestMethod, exchange.requestURI.toString(), sent)
            authorization += exchange.requestHeaders.getFirst("Authorization") ?: exchange.requestHeaders.getFirst("PRIVATE-TOKEN")
            val (status, body) = routes(exchange.requestMethod, exchange.requestURI.toString(), sent)
            exchange.responseHeaders.add("Content-Type", if (body.startsWith("<")) "text/html" else "application/json")
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun tokens(vararg files: Pair<String, String>): File {
        val dir = File(root, "tokens").also { it.mkdirs() }
        files.forEach { (name, value) ->
            val file = File(dir, name).apply { writeText(value) }
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        return dir
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "fake gh is a shell script")
    fun `github issues come from one GraphQL query through gh`() {
        val log = File(root, "gh.log")
        val response = """{"data":{"repository":{
            "open":{"totalCount":73,"nodes":[
              {"number":41,"title":"Crash on empty prompt","url":"https://github.com/acme/app/issues/41","state":"OPEN",
               "createdAt":"2026-11-09T09:00:00Z","updatedAt":"2026-11-12T08:00:00Z","author":{"login":"philipp95"},
               "assignees":{"nodes":[{"login":"anna"},{"login":"ben"}]},"labels":{"nodes":[{"name":"bug"},{"name":"android"}]},
               "comments":{"totalCount":4}},
              {"number":40,"title":"From a deleted account","url":"https://github.com/acme/app/issues/40","state":"OPEN",
               "createdAt":"2026-11-01T09:00:00Z","updatedAt":"2026-11-02T08:00:00Z","author":null,
               "assignees":{"nodes":[]},"labels":{"nodes":[]},"comments":{"totalCount":0}}]},
            "closed":{"nodes":[
              {"number":7,"title":"Old","url":"https://github.com/acme/app/issues/7","state":"CLOSED","createdAt":"2026-10-01T09:00:00Z",
               "updatedAt":"2026-10-03T08:00:00Z","author":{"login":"anna"},"assignees":{"nodes":[]},"labels":{"nodes":[]},
               "comments":{"totalCount":1}}]}}}}"""
        val gh = File(root, "bin/gh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nprintf '%s\\n' \"\$@\" > '${log.path}'\ncat <<'JSON'\n$response\nJSON\n")
            setExecutable(true)
        }

        val issues = IssueSources(ForgeCredentials(File(root, "tokens"), gh.path)).fetch(Forge.GitHub("acme", "app")).getOrThrow()

        assertEquals(73, issues.openCount, "the forge's count, not the page's")
        assertEquals("https://github.com/acme/app/issues", issues.url)
        assertEquals(
            IssueSummary(
                41, "Crash on empty prompt", "https://github.com/acme/app/issues/41", "open", author = "philipp95",
                assignees = listOf("anna", "ben"), labels = listOf("bug", "android"), comments = 4,
                createdAt = "2026-11-09T09:00:00Z", updatedAt = "2026-11-12T08:00:00Z",
            ),
            issues.open.first(),
        )
        assertNull(issues.open[1].author)
        assertEquals(listOf(7L to "closed"), issues.closed.map { it.number to it.state })
        val args = log.readLines()
        assertTrue("graphql" in args && "owner=acme" in args && "name=app" in args, args.toString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "fake gh is a shell script")
    fun `a github error is reported in GitHub's words`() {
        val gh = File(root, "bin/gh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\necho '{\"data\":{\"repository\":null},\"errors\":[{\"message\":\"Could not resolve to a Repository\"}]}'\nexit 1\n")
            setExecutable(true)
        }

        val failure = IssueSources(ForgeCredentials(File(root, "tokens"), gh.path)).fetch(Forge.GitHub("acme", "gone")).exceptionOrNull()

        assertEquals("Could not resolve to a Repository", failure?.message)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX token permissions")
    fun `gitlab issues are read with the host's token`() {
        val api = fakeApi(mapOf("X-Total" to "120")) { _, url, _ ->
            val body = if ("state=opened" in url) {
                """[{"iid":9,"title":"Flaky sync","web_url":"https://gitlab.example.com/team/sub/app/-/issues/9","state":"opened",
                    "author":{"username":"anna"},"assignees":[{"username":"ben"}],"labels":["bug","p1"],"user_notes_count":3,
                    "created_at":"2026-11-08T09:00:00Z","updated_at":"2026-11-11T09:00:00Z"}]"""
            } else {
                """[{"iid":2,"title":"Done","web_url":"https://gitlab.example.com/team/sub/app/-/issues/2","state":"closed",
                    "author":{"username":"anna"},"assignees":[],"labels":[],"user_notes_count":0}]"""
            }
            200 to body
        }
        val sources = IssueSources(ForgeCredentials(tokens("gitlab-token-gitlab.example.com" to "tok"), null), gitlabApi = { api })

        val issues = sources.fetch(Forge.GitLab("gitlab.example.com", "team/sub/app")).getOrThrow()

        assertEquals(120, issues.openCount)
        assertEquals("https://gitlab.example.com/team/sub/app/-/issues", issues.url)
        val open = issues.open.single()
        assertEquals(listOf(9L, "Flaky sync", "open", "anna", listOf("ben"), listOf("bug", "p1"), 3),
            listOf(open.number, open.title, open.state, open.author, open.assignees, open.labels, open.comments))
        assertEquals("closed", issues.closed.single().state)
        assertTrue(requests.all { it.first == "GET" && it.second.startsWith("/projects/team%2Fsub%2Fapp/issues?") }, requests.toString())
        assertEquals(listOf<String?>("tok", "tok"), authorization)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX token permissions")
    fun `azure devops work items are found with a query and read in its order`() {
        val api = fakeApi { method, url, body ->
            when {
                method == "POST" && "Not In" in body -> 200 to """{"workItems":[{"id":31},{"id":30},{"id":12}]}"""
                method == "POST" -> 200 to """{"workItems":[{"id":5}]}"""
                "ids=31,30,12" in url -> 200 to """{"value":[
                    {"id":12,"fields":{"System.Title":"Twelve","System.State":"New","System.WorkItemType":"Task"}},
                    {"id":31,"fields":{"System.Title":"Login fails","System.State":"Active","System.WorkItemType":"Bug",
                      "System.AssignedTo":{"displayName":"Anna Peeters","uniqueName":"anna@example.com"},
                      "System.CreatedBy":"Ben Claes <ben@example.com>","System.Tags":"mobile; urgent","System.CommentCount":2,
                      "System.CreatedDate":"2026-11-08T09:00:00Z","System.ChangedDate":"2026-11-11T09:00:00Z"}},
                    {"id":30,"fields":{"System.Title":"Thirty","System.State":"New","System.WorkItemType":"User Story"}}]}"""
                else -> 200 to """{"value":[{"id":5,"fields":{"System.Title":"Shipped","System.State":"Closed","System.WorkItemType":"Bug"}}]}"""
            }
        }
        val sources = IssueSources(ForgeCredentials(tokens("azure-devops-token" to "pat"), null), azureApi = api)

        val issues = sources.fetch(Forge.AzureDevOps("org", "My Project", "app")).getOrThrow()

        assertEquals(3, issues.openCount)
        assertEquals("https://dev.azure.com/org/My%20Project/_workitems", issues.url)
        assertEquals(listOf(31L, 30L, 12L), issues.open.map { it.number }, "the query's order, most recently changed first")
        assertEquals(
            IssueSummary(
                31, "Login fails", "https://dev.azure.com/org/My%20Project/_workitems/edit/31", "open", kind = "Bug", status = "Active",
                author = "Ben Claes", assignees = listOf("Anna Peeters"), labels = listOf("mobile", "urgent"), comments = 2,
                createdAt = "2026-11-08T09:00:00Z", updatedAt = "2026-11-11T09:00:00Z",
            ),
            issues.open.first(),
        )
        assertEquals(listOf("Shipped" to "closed"), issues.closed.map { it.title to it.state })
        val queries = requests.filter { it.first == "POST" }
        assertEquals(2, queries.size)
        assertTrue(queries.all { it.second.startsWith("/org/My%20Project/_apis/wit/wiql?") && "api-version=7.1" in it.second }, queries.toString())
        assertTrue(queries.all { "[System.TeamProject] = @project" in it.third }, queries.toString())
        assertTrue(authorization.all { it == "Basic OnBhdA==" }, authorization.toString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX token permissions")
    fun `azure devops says which scope the token lacks`() {
        val sources = { api: String -> IssueSources(ForgeCredentials(tokens("azure-devops-token" to "pat"), null), azureApi = api) }

        val refused = sources(fakeApi { _, _, _ -> 401 to """{"message":"no"}""" }).fetch(Forge.AzureDevOps("org", "p", "app"))
        server?.stop(0)
        val signIn = sources(fakeApi { _, _, _ -> 203 to "<html>Sign in</html>" }).fetch(Forge.AzureDevOps("org", "p", "app"))

        assertTrue(refused.exceptionOrNull()!!.message!!.contains("Work Items: Read"))
        assertTrue(signIn.exceptionOrNull()!!.message!!.contains("did not accept the token"))
    }

    @Test
    fun `a forge without credentials is not asked`() {
        val failure = IssueSources(ForgeCredentials(File(root, "none"), null)).fetch(Forge.GitHub("acme", "app")).exceptionOrNull()

        assertTrue(failure is IOException && !failure.message.isNullOrBlank())
    }

    @Test
    fun `issues are kept for a while, and survive a forge that stops answering`() {
        var now = Instant.parse("2026-11-14T10:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        var calls = 0
        var broken = false
        val known = RepoIssues(1, listOf(IssueSummary(1, "One", "https://example.com/1", "open")))
        val service = IssuesService(
            { calls++; if (broken) Result.failure(IOException("offline")) else Result.success(known) }, clock, Duration.ofMinutes(1),
        )
        val forge = Forge.GitHub("acme", "app")

        val first = service.of("p", forge)
        service.of("p", forge)
        assertEquals(1, calls, "a second look within the minute costs nothing")
        assertEquals("2026-11-14T10:00:00Z", first.fetchedAt)

        broken = true
        now = now.plusSeconds(61)
        val later = service.of("p", forge)
        assertEquals(2, calls)
        assertEquals("offline", later.error)
        assertEquals(known.open, later.open, "what was known stays")
        assertEquals("offline", service.of("other", forge).error)
    }
}
