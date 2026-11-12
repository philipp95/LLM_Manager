package com.llmmanager.server.api

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class InsightsTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val since: Instant = Instant.parse("2026-11-04T00:00:00Z")
    private var server: HttpServer? = null
    private val requests = mutableListOf<String>()

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `README first sentence, whatever comes before it`() {
        assertEquals(
            "A project for steering coding agents from an Android app, with execution on a separate computer.",
            ReadmeSummary.firstSentence(
                "# LLM Manager\n\nA project for steering coding agents from an Android app, with execution on a\n" +
                    "separate computer. **The current implementation is a Kotlin/JVM CLI prototype**.\n",
            ),
        )
        val badges = """
            ---
            title: x
            ---
            <p align="center"><img src="logo.png"></p>

            [![CI](https://ci/badge.svg)](https://ci) ![cov](c.svg)

            Title
            =====

            <!-- hidden note -->
            > Fast **JSON** parsing for [Kotlin](https://kotlinlang.org), e.g. `decode()`. Second sentence.
        """.trimIndent()
        assertEquals("Fast JSON parsing for Kotlin, e.g. decode().", ReadmeSummary.firstSentence(badges))
        assertEquals("LLM_Manager keeps snake_case and my_var intact.", ReadmeSummary.firstSentence("LLM_Manager keeps snake_case and my_var intact. More."))
        assertNull(ReadmeSummary.firstSentence("# test_repo\n"))
        assertNull(ReadmeSummary.firstSentence("```\ncode only\n```\n"))
        assertTrue(ReadmeSummary.firstSentence("word ".repeat(80))!!.endsWith("…"))
    }

    @Test
    fun `scanner reads the description from the default branch`() {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("# App\n\nDoes one thing well. Then more.\n")
        git(repo, "add", "README.md")
        git(repo, "-c", "user.name=T", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "readme")
        // Uncommitted edits do not change the description: it is the default branch's README.
        File(repo, "README.md").writeText("Draft.\n")

        val project = ProjectScanner(listOf("${root.path}/repos/*")).scan().single()

        assertEquals("Does one thing well.", project.description)
    }

    @Test
    fun `agent attribution prefers own records, then branch, markers and author`() {
        fun pr(branch: String? = null, author: String? = null, body: String? = null, commit: String? = null) =
            RawPullRequest(PullRequestSummary(1, "t", "u", "merged", author = author, branch = branch), body, commit)

        assertEquals(Agent.GEMINI, AgentAttribution.of(pr(branch = "claude/x"), ownRecord = Agent.GEMINI))
        assertEquals(Agent.CLAUDE, AgentAttribution.of(pr(branch = "claude/fix-login"), null))
        assertEquals(Agent.CODEX, AgentAttribution.of(pr(branch = "codex/add-tests"), null))
        assertEquals(Agent.CLAUDE, AgentAttribution.of(pr(commit = "Fix\n\nCo-Authored-By: Claude Opus <noreply@anthropic.com>"), null))
        assertEquals(Agent.CLAUDE, AgentAttribution.of(pr(body = "Summary\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)"), null))
        assertEquals(Agent.GEMINI, AgentAttribution.of(pr(commit = "Gemini (work): add parser\n\nRun abc started by LLM Manager."), null))
        assertEquals(Agent.CODEX, AgentAttribution.of(pr(author = "chatgpt-codex-connector[bot]"), null))
        assertNull(AgentAttribution.of(pr(branch = "feature/login", author = "philipp95", body = "Hand-written."), null))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "fake gh is a shell script")
    fun `github activity comes from one GraphQL query through gh`() {
        val log = File(root, "gh.log")
        val response = """{"data":{"repository":{
            "open":{"totalCount":2},
            "recent":{"nodes":[
              {"number":12,"title":"Add retry","url":"https://github.com/acme/app/pull/12","state":"MERGED","createdAt":"2026-11-09T09:00:00Z",
               "mergedAt":"2026-11-10T08:00:00Z","headRefName":"run/app-1","bodyText":"Opened by LLM Manager","author":{"login":"philipp95"},
               "mergeCommit":{"statusCheckRollup":{"state":"SUCCESS"}},
               "commits":{"nodes":[{"commit":{"message":"Claude (work): Add retry","statusCheckRollup":{"state":"FAILURE"}}}]}},
              {"number":13,"title":"WIP docs","url":"https://github.com/acme/app/pull/13","state":"OPEN","createdAt":"2026-11-10T10:00:00Z",
               "mergedAt":null,"headRefName":"docs","bodyText":"","author":{"login":"someone"},"mergeCommit":null,
               "commits":{"nodes":[{"commit":{"message":"docs","statusCheckRollup":{"state":"PENDING"}}}]}}
            ]}},
          "merged":{"issueCount":5}}}"""
        val gh = File(root, "bin/gh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nprintf '%s\\n' \"\$@\" > '${log.path}'\ncat <<'JSON'\n$response\nJSON\n")
            setExecutable(true)
        }
        val sources = InsightSources(ForgeCredentials(File(root, "tokens"), gh.path))

        val activity = sources.fetch(Forge.GitHub("acme", "app"), since).getOrThrow()

        assertEquals(2, activity.openCount)
        assertEquals(5, activity.mergedLastWeek)
        val merged = activity.recent.first().summary
        assertEquals(PullRequestSummary(12, "Add retry", "https://github.com/acme/app/pull/12", "merged", "philipp95", "run/app-1",
            "2026-11-09T09:00:00Z", "2026-11-10T08:00:00Z", ci = "success"), merged)
        assertEquals("pending", activity.recent[1].summary.ci, "open PRs report their head's checks")
        val args = log.readLines()
        assertTrue("graphql" in args && "owner=acme" in args && "name=app" in args, args.toString())
        assertTrue("since=repo:acme/app is:pr is:merged merged:>=2026-11-04" in args, args.toString())
    }

    private fun fakeApi(routes: (String) -> Triple<Int, String, String>, headers: Map<String, String> = emptyMap()): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val (status, type, body) = routes(exchange.requestURI.toString())
            exchange.responseHeaders.add("Content-Type", type)
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
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX token permissions")
    fun `gitlab activity, with CI from the merge commit's pipeline`() {
        val api = fakeApi(routes = { uri ->
            when {
                uri.contains("/pipelines?sha=abc") -> Triple(200, "application/json", """[{"status":"failed"}]""")
                uri.contains("state=all") -> Triple(200, "application/json", """[
                    {"iid":7,"title":"Fix","web_url":"https://gitlab.example.com/team/app/-/merge_requests/7","state":"merged",
                     "author":{"username":"anna"},"source_branch":"codex/fix","created_at":"2026-11-08T10:00:00Z",
                     "merged_at":"2026-11-09T10:00:00Z","merge_commit_sha":"${"abc".padEnd(40, '0')}","sha":"${"def".padEnd(40, '0')}","description":"x"}]""")
                uri.contains("state=opened") -> Triple(200, "application/json", "[{}]")
                // One merged this week, one long ago: only the first counts.
                uri.contains("state=merged") ->
                    Triple(200, "application/json", """[{"merged_at":"2026-11-09T10:00:00Z"},{"merged_at":"2026-10-01T10:00:00Z"}]""")
                else -> Triple(404, "application/json", "{}")
            }
        }, headers = mapOf("X-Total" to "3"))
        val sources = InsightSources(ForgeCredentials(tokens("gitlab-token-gitlab.example.com" to "tok"), null), gitlabApi = { api })

        val activity = sources.fetch(Forge.GitLab("gitlab.example.com", "team/app"), since).getOrThrow()

        assertEquals(3, activity.openCount)
        assertEquals(1, activity.mergedLastWeek)
        val mr = activity.recent.single().summary
        assertEquals("merged", mr.state)
        assertEquals("anna", mr.author)
        assertEquals("failure", mr.ci)
        assertTrue(requests.any { it.contains("pipelines?sha=abc") }, requests.toString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "POSIX token permissions")
    fun `azure devops activity, and a sign-in page is reported as a token problem`() {
        var signIn = false
        val api = fakeApi(routes = { uri ->
            when {
                signIn -> Triple(203, "text/html", "<html>sign in</html>")
                uri.contains("/statuses") -> Triple(200, "application/json", """{"value":[{"state":"succeeded"},{"state":"succeeded"}]}""")
                uri.contains("status=all") -> Triple(200, "application/json", """{"value":[
                    {"pullRequestId":42,"title":"Add API","status":"completed","createdBy":{"displayName":"Anna"},"creationDate":"2026-11-08T10:00:00Z",
                     "closedDate":"2026-11-09T10:00:00Z","sourceRefName":"refs/heads/gemini/api","description":"d"}]}""")
                uri.contains("status=active") -> Triple(200, "application/json", """{"value":[{},{}]}""")
                uri.contains("status=completed") -> Triple(200, "application/json", """{"value":[{"closedDate":"2026-11-09T10:00:00Z"}]}""")
                else -> Triple(404, "application/json", "{}")
            }
        })
        val sources = InsightSources(ForgeCredentials(tokens("azure-devops-token" to "pat"), null), azureApi = api)
        val forge = Forge.AzureDevOps("contoso", "Web", "app")

        val activity = sources.fetch(forge, since).getOrThrow()

        assertEquals(2, activity.openCount)
        assertEquals(1, activity.mergedLastWeek)
        val pr = activity.recent.single().summary
        assertEquals(Triple("merged", "gemini/api", "success"), Triple(pr.state, pr.branch, pr.ci))
        assertEquals("https://dev.azure.com/contoso/Web/_git/app/pullrequest/42", pr.url)
        assertTrue(requests.all { it.contains("api-version=7.1") }, requests.toString())

        signIn = true
        val refused = sources.fetch(forge, since).exceptionOrNull()!!
        assertTrue(refused.message!!.contains("did not accept the token"), refused.message)
    }

    @Test
    fun `insights are cached, attributed, and keep earlier data when a refresh fails`() {
        val clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC)
        var fail = false
        val sources = object : ForgeActivitySource {
            override fun fetch(forge: Forge, since: Instant): Result<ForgeActivity> = if (fail) Result.failure(java.io.IOException("offline")) else
                Result.success(
                    ForgeActivity(
                        listOf(RawPullRequest(PullRequestSummary(3, "x", "u", "merged", branch = "run/app-9", mergedAt = "2026-11-10T00:00:00Z"), null, null)),
                        openCount = 1, mergedLastWeek = 4,
                    ),
                )
        }
        val service = InsightsService(sources, agentForBranch = { _, branch -> Agent.CLAUDE.takeIf { branch == "run/app-9" } }, clock)
        val forge = Forge.GitHub("acme", "app")

        service.refresh("p1", forge)
        fail = true
        service.refresh("p1", forge)

        val project = Project("p1", "acme/app", kind = Project.Kind.CHECKOUT, path = "/r", originUrl = "https://github.com/acme/app.git",
            gitHubUrl = null, defaultBranch = "main", worktrees = emptyList())
        val insights = service.annotate(listOf(project)).single().insights!!
        assertEquals(4, insights.mergedLastWeek)
        assertEquals(Agent.CLAUDE, insights.latestMerged!!.agent)
        assertEquals("offline", insights.error)
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }
}
