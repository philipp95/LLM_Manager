package com.llmmanager.server.api

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Real pushes to a local bare repository (origin URLs are rewritten with git's
 * `insteadOf`), and forge APIs served by a local HTTP server or a fake `gh`.
 */
class BranchServiceTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** Serves [respond] for every request and records method, path+query and the auth headers. */
    private fun fakeApi(respond: (method: String, path: String) -> Triple<Int, String, String>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val headers = listOf("Authorization", "PRIVATE-TOKEN").mapNotNull { h -> exchange.requestHeaders.getFirst(h)?.let { h to it } }.toMap()
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += Triple(exchange.requestMethod + " " + exchange.requestURI, body, headers)
            val (status, type, text) = respond(exchange.requestMethod, exchange.requestURI.toString())
            exchange.responseHeaders.add("Content-Type", type)
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private data class Fixture(val service: BranchService, val store: BranchStore, val projectId: String, val origin: File, val branch: String)

    /** A checkout whose origin looks like [remote] but pushes to a local bare repo, with an agent worktree. */
    private fun fixture(remote: String, client: (ForgeCredentials) -> ForgeClient, gh: String? = null): Fixture {
        val origin = File(root, "origin.git")
        git(root, "init", "--bare", "-b", "main", origin.path)
        val checkout = File(root, "repos/app").also { it.mkdirs() }
        git(checkout, "init", "-b", "main")
        File(checkout, "README.md").writeText("app\n")
        git(checkout, "add", "README.md")
        commit(checkout, "initial")
        git(checkout, "remote", "add", "origin", remote)
        git(checkout, "config", "url.${origin.path}.insteadOf", remote)
        git(checkout, "push", "origin", "main")
        val branch = "run/app-20261109-1"
        val wt = File(root, "runs/app-1")
        git(checkout, "worktree", "add", "-b", branch, wt.path)
        File(wt, "feature.txt").writeText("agent work\n")
        git(wt, "add", "feature.txt")
        commit(wt, "agent work")
        val tokens = File(root, "config").also { it.mkdirs() }
        File(tokens, ForgeCredentials.GITLAB_FILE).writeText("glpat-test-token\n")
        File(tokens, ForgeCredentials.AZURE_FILE).writeText("ado-pat-value\n")
        val credentials = ForgeCredentials(tokens, gh)
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"))
        val store = StateStore(File(root, "state"))
        store.putRun(
            Run("r1", null, scanner.scan().single().id, "app", Agent.CLAUDE, Stage.WORK, RunStatus.SUCCEEDED, "Add a feature file\nwith details",
                branch = branch, createdAt = "2026-11-09T10:00:00Z"),
        )
        val branches = BranchStore(File(root, "state"))
        val workspace = Workspace(scanner, SessionHost.None, AgentCommands(null, null))
        val service = BranchService(store, branches, workspace, client(credentials), credentials)
        return Fixture(service, branches, scanner.scan().single().id, origin, branch)
    }

    @Test
    fun `gitlab merge request is opened after pushing, with the token only in its header`() {
        val api = fakeApi { method, _ ->
            val state = if (method == "POST") "opened" else "merged"
            Triple(if (method == "POST") 201 else 200, "application/json", """{"iid":7,"web_url":"$MR_URL","state":"$state"}""")
        }
        val f = fixture("https://gitlab.example.com/team/app.git", { ForgeClient(it, gitlabApi = { _ -> api }) })

        val opened = f.service.openPullRequest(f.projectId, f.branch)

        assertTrue(opened is BranchService.Outcome.Ok, opened.toString())
        // Really pushed: the bare origin has the branch at the worktree's commit.
        val pushed = output(f.origin, "rev-parse", "refs/heads/${f.branch}")
        assertEquals(pushed, f.store.get(f.projectId, f.branch)!!.pushedCommit)
        val (call, body, headers) = requests.single()
        assertEquals("POST /projects/team%2Fapp/merge_requests", call)
        assertEquals("glpat-test-token", headers["PRIVATE-TOKEN"])
        assertTrue(body.contains("\"source_branch\":\"${f.branch}\"") && body.contains("\"target_branch\":\"main\""))
        assertTrue(body.contains("\"title\":\"Add a feature file\""))
        val expected = PullRequestRef("gitlab", 7, "https://gitlab.example.com/team/app/-/merge_requests/7", "opened")
        assertEquals(expected, f.store.get(f.projectId, f.branch)!!.pullRequest)

        val merged = f.service.merge(f.projectId, f.branch)
        assertTrue(merged is BranchService.Outcome.Ok, merged.toString())
        assertEquals("PUT /projects/team%2Fapp/merge_requests/7/merge", requests.last().first)
        assertEquals("merged", f.store.get(f.projectId, f.branch)!!.pullRequest!!.state)
    }

    @Test
    fun `azure devops uses basic auth with an empty user, api-version and full refs, and reports sign-in pages`() {
        var signIn = false
        val api = fakeApi { method, _ ->
            when {
                signIn -> Triple(203, "text/html", "<html>Sign in</html>")
                method == "POST" -> Triple(201, "application/json", """{"pullRequestId":42,"status":"active"}""")
                method == "GET" -> Triple(200, "application/json", """{"pullRequestId":42,"status":"active","lastMergeSourceCommit":{"commitId":"abc123"}}""")
                else -> Triple(200, "application/json", """{"pullRequestId":42,"status":"completed"}""")
            }
        }
        val f = fixture("https://contoso@dev.azure.com/contoso/Web/_git/app", { ForgeClient(it, azureApi = api) })

        val opened = f.service.openPullRequest(f.projectId, f.branch) as BranchService.Outcome.Ok

        assertEquals("https://dev.azure.com/contoso/Web/_git/app/pullrequest/42", opened.record.pullRequest!!.url)
        val (call, body, headers) = requests.single()
        assertEquals("POST /contoso/Web/_apis/git/repositories/app/pullrequests?api-version=7.1", call)
        assertEquals("Basic " + Base64.getEncoder().encodeToString(":ado-pat-value".toByteArray()), headers["Authorization"])
        assertTrue(body.contains("\"sourceRefName\":\"refs/heads/${f.branch}\"") && body.contains("\"targetRefName\":\"refs/heads/main\""))

        val merged = f.service.merge(f.projectId, f.branch) as BranchService.Outcome.Ok
        assertEquals("completed", merged.record.pullRequest!!.state)
        assertTrue(requests.last().second.contains("\"commitId\":\"abc123\""))

        signIn = true
        val refused = f.service.merge(f.projectId, f.branch) as BranchService.Outcome.Invalid
        assertTrue(refused.reason.contains("did not accept the token"), refused.reason)
    }

    @Test
    fun `github goes through gh and only agent branches can be published`() {
        val log = File(root, "gh-args.log")
        val gh = File(root, "bin/gh").apply {
            parentFile.mkdirs()
            writeText("#!/usr/bin/env bash\nprintf '%s\\n' \"\$@\" >> '${log.path}'\n" +
                "echo '{\"number\":12,\"html_url\":\"https://github.com/acme/app/pull/12\",\"state\":\"open\"}'\n")
            setExecutable(true)
        }
        val f = fixture("https://github.com/acme/app.git", { ForgeClient(it) }, gh = gh.path)

        val opened = f.service.openPullRequest(f.projectId, f.branch) as BranchService.Outcome.Ok

        assertEquals(PullRequestRef("github", 12, "https://github.com/acme/app/pull/12", "open"), opened.record.pullRequest)
        val args = log.readLines()
        assertTrue("repos/acme/app/pulls" in args && "head=${f.branch}" in args && "base=main" in args, args.toString())
        assertTrue((f.service.push(f.projectId, "main") as BranchService.Outcome.Invalid).reason.contains("Only branches created by agents"))
        assertTrue((f.service.push(f.projectId, "run/does-not-exist") as BranchService.Outcome.Invalid).reason.contains("No agent worktree"))
        assertTrue((f.service.push("nope", f.branch) as BranchService.Outcome.Invalid).reason.contains("Unknown project"))
    }

    @Test
    fun `missing credentials are explained, and push auth stays off the command line`() {
        val credentials = ForgeCredentials(File(root, "empty"), gh = null)
        val gitlab = Forge.GitLab("gitlab.example.com", "team/app")
        assertTrue(credentials.missing(gitlab)!!.contains("gitlab-token"))
        assertTrue(credentials.missing(Forge.AzureDevOps("o", "p", "r"))!!.contains("organisation-scoped"))
        assertTrue(credentials.missing(Forge.GitHub("o", "r"))!!.contains("gh auth login"))

        File(root, "cfg").mkdirs()
        File(root, "cfg/${ForgeCredentials.GITLAB_FILE}").writeText("tok")
        val env = ForgeCredentials(File(root, "cfg"), null).pushEnvironment(gitlab, "https://gitlab.example.com/team/app.git")
        assertEquals("http.https://gitlab.example.com/.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Basic " + Base64.getEncoder().encodeToString("oauth2:tok".toByteArray()), env["GIT_CONFIG_VALUE_0"])
        assertTrue(ForgeCredentials(File(root, "cfg"), null).pushEnvironment(gitlab, "git@gitlab.example.com:team/app.git").isEmpty())
    }

    private companion object {
        const val MR_URL = "https://gitlab.example.com/team/app/-/merge_requests/7"
    }

    private fun commit(dir: File, message: String) = git(dir, "-c", "user.name=T", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", message)

    private fun git(dir: File, vararg args: String) {
        output(dir, *args)
    }

    private fun output(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $text" }
        return text.trim()
    }
}
