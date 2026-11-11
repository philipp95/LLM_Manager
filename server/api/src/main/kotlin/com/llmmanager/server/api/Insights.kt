package com.llmmanager.server.api

import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/** A pull request (or GitLab merge request) as the dashboard shows it. */
@Serializable
data class PullRequestSummary(
    val number: Long,
    val title: String,
    val url: String,
    /** open, merged or closed. */
    val state: String,
    val author: String? = null,
    val branch: String? = null,
    val createdAt: String? = null,
    val mergedAt: String? = null,
    /** The coding agent that made it, when known. */
    val agent: Agent? = null,
    /** CI/CD result: success, failure or pending; null when the repository has none. */
    val ci: String? = null,
)

/** Pull request activity on the forge, for the repo tile and its history. */
@Serializable
data class RepoInsights(
    val latestMerged: PullRequestSummary? = null,
    val mergedLastWeek: Int = 0,
    val openCount: Int = 0,
    /** Most recently updated pull requests, newest first. */
    val recent: List<PullRequestSummary> = emptyList(),
    val fetchedAt: String? = null,
    /** Why the forge could not be read (e.g. a missing token); earlier data is kept. */
    val error: String? = null,
)

/** What a forge reported, before agent attribution. */
data class ForgeActivity(val recent: List<RawPullRequest>, val openCount: Int, val mergedLastWeek: Int)

/**
 * A pull request plus the raw hints used to tell which agent made it, and (GitLab)
 * the commit whose pipeline reports its CI/CD result.
 */
data class RawPullRequest(
    val summary: PullRequestSummary,
    val body: String?,
    val lastCommitMessage: String?,
    val ciCommit: String? = null,
)

/**
 * Which coding agent made a pull request. In order: this server's own records for the
 * branch, the branch prefix agents use, markers agents leave in commits and descriptions,
 * then bot author names.
 */
object AgentAttribution {
    private val CO_AUTHOR = mapOf(
        Agent.CLAUDE to Regex("""(?i)co-authored-by:\s*claude|generated with \[?claude code|^claude \((work|fix|resume)\):"""),
        Agent.CODEX to Regex("""(?i)co-authored-by:\s*(codex|chatgpt)|chatgpt\.com/codex|^codex \((work|fix|resume)\):"""),
        Agent.GEMINI to Regex("""(?i)co-authored-by:\s*(gemini|jules)|gemini-code-assist|^gemini \((work|fix|resume)\):"""),
    )

    fun of(pr: RawPullRequest, ownRecord: Agent?): Agent? {
        val branch = pr.summary.branch.orEmpty().lowercase()
        val text = listOfNotNull(pr.lastCommitMessage, pr.body).joinToString("\n")
        val author = pr.summary.author.orEmpty().lowercase()
        return ownRecord
            ?: Agent.entries.firstOrNull { branch.startsWith(it.name.lowercase() + "/") }
            ?: CO_AUTHOR.entries.firstOrNull { (_, marker) -> text.lines().any { marker.containsMatchIn(it.trim()) } }?.key
            ?: when {
                author.contains("claude") -> Agent.CLAUDE
                author.contains("codex") || author.contains("chatgpt") -> Agent.CODEX
                author.contains("gemini") || author.contains("jules") -> Agent.GEMINI
                else -> null
            }
    }
}

/** Where pull request activity comes from; a seam for tests. */
fun interface ForgeActivitySource {
    /** Activity on [forge] since [since] for the weekly count, or a reason it could not be read. */
    fun fetch(forge: Forge, since: Instant): Result<ForgeActivity>
}

/** Reads pull request activity from GitHub (via `gh`), GitLab and Azure DevOps. */
class InsightSources(
    private val credentials: ForgeCredentials,
    gitlabApi: (Forge.GitLab) -> String = { it.apiBase },
    azureApi: String = "https://dev.azure.com",
) : ForgeActivitySource {
    private val github = GitHubActivity(credentials)
    private val gitlab = GitLabActivity(credentials, ForgeHttp(), gitlabApi)
    private val azure = AzureActivity(credentials, ForgeHttp(), azureApi)

    override fun fetch(forge: Forge, since: Instant): Result<ForgeActivity> {
        credentials.missing(forge)?.let { return Result.failure(IOException(it)) }
        return runCatching {
            when (forge) {
                is Forge.GitHub -> github.read(forge, since)
                is Forge.GitLab -> gitlab.read(forge, since)
                is Forge.AzureDevOps -> azure.read(forge, since)
            }
        }
    }

    internal companion object {
        const val RECENT = 20
        const val PAGE = 100
    }
}

/** GitHub: one GraphQL query through the signed-in gh CLI. */
private class GitHubActivity(private val credentials: ForgeCredentials) {
    fun read(forge: Forge.GitHub, since: Instant): ForgeActivity {
        val search = "repo:${forge.name} is:pr is:merged merged:>=${since.atOffset(ZoneOffset.UTC).toLocalDate()}"
        val command = listOf(checkNotNull(credentials.gh), "api", "graphql", "-f", "query=$QUERY") +
            listOf("-f", "owner=${forge.owner}", "-f", "name=${forge.repo}", "-f", "since=$search")
        val result = Processes.exec(command, environment = mapOf("GH_PROMPT_DISABLED" to "1"))
        val body = insightJson.parseToJsonElement(result.stdout.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        val errors = (body["errors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("message") }
        if (!result.ok || !errors.isNullOrEmpty()) {
            throw IOException(errors?.firstOrNull() ?: result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "gh failed")
        }
        val data = body.obj("data")
        val repository = data?.obj("repository") ?: throw IOException("Repository not found on GitHub")
        val recent = repository.obj("recent")?.array("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.let(::pullRequest) }
        return ForgeActivity(recent, repository.obj("open")?.int("totalCount") ?: 0, data.obj("merged")?.int("issueCount") ?: 0)
    }

    private fun pullRequest(node: JsonObject): RawPullRequest {
        val lastCommit = node.obj("commits")?.array("nodes")?.firstOrNull()?.let { (it as JsonObject).obj("commit") }
        val merged = node.text("mergedAt") != null
        // For a merged PR, CI/CD on the merge commit (what shipped); otherwise on the PR's head.
        val rollup = (if (merged) node.obj("mergeCommit")?.obj("statusCheckRollup") else null) ?: lastCommit?.obj("statusCheckRollup")
        val summary = PullRequestSummary(
            number = node.long("number"), title = node.text("title").orEmpty(), url = node.text("url").orEmpty(),
            state = when (node.text("state")) {
                "MERGED" -> "merged"
                "CLOSED" -> "closed"
                else -> "open"
            },
            author = node.obj("author")?.text("login"), branch = node.text("headRefName"),
            createdAt = node.text("createdAt"), mergedAt = node.text("mergedAt"),
            ci = when (rollup?.text("state")) {
                "SUCCESS" -> "success"
                "FAILURE", "ERROR" -> "failure"
                "PENDING", "EXPECTED" -> "pending"
                else -> null
            },
        )
        return RawPullRequest(summary, node.text("bodyText"), lastCommit?.text("message"))
    }

    private companion object {
        /** One query: open count, recent pull requests with CI rollups, and an exact merged-this-week count. */
        val QUERY = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}since: String!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                open: pullRequests(states: OPEN) { totalCount }
                recent: pullRequests(first: 20, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  nodes {
                    number title url state createdAt mergedAt headRefName bodyText
                    author { login }
                    mergeCommit { statusCheckRollup { state } }
                    commits(last: 1) { nodes { commit { message statusCheckRollup { state } } } }
                  }
                }
              }
              merged: search(query: ${'$'}since, type: ISSUE, first: 1) { issueCount }
            }
        """.trimIndent()
    }
}

/** GitLab REST, with the token bound to the instance's host. */
private class GitLabActivity(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: (Forge.GitLab) -> String) {
    fun read(forge: Forge.GitLab, since: Instant): ForgeActivity {
        val project = "${api(forge)}/projects/${segment(forge.path)}"
        fun get(url: String) = http.send(http.request(url).header("PRIVATE-TOKEN", checkNotNull(credentials.gitlabToken(forge.host))).GET())
            .requireOk(http, "GitLab")
        val requests = "$project/merge_requests"
        val recent = jsonArray(get("$requests?state=all&order_by=updated_at&sort=desc&per_page=${InsightSources.RECENT}"))
            .mapNotNull { (it as? JsonObject)?.let(::mergeRequest) }
        val open = get("$requests?state=opened&per_page=1").headers().firstValue("X-Total").orElse(null)?.toIntOrNull() ?: 0
        val merged = jsonArray(get("$requests?state=merged&updated_after=${query(since.toString())}&per_page=${InsightSources.PAGE}"))
            .count { mergedSince((it as? JsonObject)?.text("merged_at"), since) }
        // CI/CD only for the latest merged MR: one pipeline lookup, on the commit that shipped.
        val latest = recent.filter { it.summary.state == "merged" }.maxByOrNull { it.summary.mergedAt.orEmpty() }
        val withCi = recent.map { pr ->
            val sha = pr.ciCommit?.takeIf { pr == latest && SHA.matches(it) } ?: return@map pr
            val pipelines = jsonArray(get("$project/pipelines?sha=$sha&per_page=1"))
            pr.copy(summary = pr.summary.copy(ci = pipelineResult((pipelines.firstOrNull() as? JsonObject)?.text("status"))))
        }
        return ForgeActivity(withCi, open, merged)
    }

    private fun mergeRequest(mr: JsonObject): RawPullRequest {
        val summary = PullRequestSummary(
            number = mr.long("iid"), title = mr.text("title").orEmpty(), url = mr.text("web_url").orEmpty(),
            state = when (mr.text("state")) {
                "merged" -> "merged"
                "closed", "locked" -> "closed"
                else -> "open"
            },
            author = mr.obj("author")?.text("username"), branch = mr.text("source_branch"),
            createdAt = mr.text("created_at"), mergedAt = mr.text("merged_at"),
        )
        return RawPullRequest(summary, mr.text("description"), null, ciCommit = mr.text("merge_commit_sha") ?: mr.text("sha"))
    }

    private fun jsonArray(response: HttpResponse<String>): JsonArray =
        insightJson.parseToJsonElement(response.body()) as? JsonArray ?: JsonArray(emptyList())

    private fun query(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun pipelineResult(status: String?): String? = when (status) {
        "success" -> "success"
        "failed", "canceled" -> "failure"
        "running", "pending", "created", "preparing", "waiting_for_resource", "scheduled", "manual" -> "pending"
        else -> null
    }

    private companion object {
        val SHA = Regex("[0-9a-f]{40}")
    }
}

/** Azure DevOps REST: api-version on every call, Basic auth with an empty user name. */
private class AzureActivity(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: String) {
    fun read(forge: Forge.AzureDevOps, since: Instant): ForgeActivity {
        val pulls = "$api/${segment(forge.organization)}/${segment(forge.project)}/_apis/git/repositories/${segment(forge.repo)}/pullrequests"
        val auth = "Basic " + Base64.getEncoder().encodeToString(":${checkNotNull(credentials.azureToken())}".toByteArray())
        fun get(url: String): JsonObject? {
            val response = http.send(http.request(url).header("Authorization", auth).GET())
            if (!http.isJson(response)) {
                throw IOException("Azure DevOps did not accept the token (HTTP ${response.statusCode()}, sign-in page instead of JSON)")
            }
            return insightJson.parseToJsonElement(response.requireOk(http, "Azure DevOps").body()) as? JsonObject
        }
        fun list(status: String, top: Int) = get("$pulls?searchCriteria.status=$status&%24top=$top&api-version=$VERSION")
            ?.array("value").orEmpty().mapNotNull { it as? JsonObject }
        val recent = list("all", InsightSources.RECENT).map { pullRequest(forge, it) }
        val open = list("active", InsightSources.PAGE).size
        val merged = list("completed", InsightSources.PAGE).count { mergedSince(it.text("closedDate"), since) }
        val latest = recent.filter { it.summary.state == "merged" }.maxByOrNull { it.summary.mergedAt.orEmpty() }
        val withCi = recent.map { pr ->
            if (pr != latest) return@map pr
            val states = get("$pulls/${pr.summary.number}/statuses?api-version=$VERSION")?.array("value").orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("state") }
            pr.copy(summary = pr.summary.copy(ci = checksResult(states)))
        }
        return ForgeActivity(withCi, open, merged)
    }

    private fun pullRequest(forge: Forge.AzureDevOps, pr: JsonObject): RawPullRequest {
        val id = pr.long("pullRequestId")
        val summary = PullRequestSummary(
            number = id, title = pr.text("title").orEmpty(), url = forge.pullRequestUrl(id),
            state = when (pr.text("status")) {
                "completed" -> "merged"
                "abandoned" -> "closed"
                else -> "open"
            },
            author = pr.obj("createdBy")?.let { it.text("displayName") ?: it.text("uniqueName") },
            branch = pr.text("sourceRefName")?.removePrefix("refs/heads/"),
            createdAt = pr.text("creationDate"), mergedAt = pr.text("closedDate")?.takeIf { pr.text("status") == "completed" },
        )
        return RawPullRequest(summary, pr.text("description"), null)
    }

    private fun checksResult(states: List<String>): String? = when {
        states.any { it == "failed" || it == "error" } -> "failure"
        states.any { it == "pending" } -> "pending"
        states.any { it == "succeeded" } -> "success"
        else -> null
    }

    private companion object {
        const val VERSION = "7.1"
    }
}

private val insightJson = Json { ignoreUnknownKeys = true }
private val OK_STATUS = 200..299

private fun HttpResponse<String>.requireOk(http: ForgeHttp, name: String): HttpResponse<String> =
    if (statusCode() in OK_STATUS) this else throw IOException(http.error(name, this))

/** Whether [text], an ISO timestamp, is at or after [since]; false when it is missing or malformed. */
private fun mergedSince(text: String?, since: Instant): Boolean = try {
    text?.let { !java.time.OffsetDateTime.parse(it).toInstant().isBefore(since) } == true
} catch (_: DateTimeParseException) {
    false
}

/**
 * Pull request insights per project, cached for [ttl] and refreshed in the background:
 * pages never wait on a forge, and polling clients do not multiply API calls.
 */
class InsightsService(
    private val sources: ForgeActivitySource,
    /** The agent this server ran on a branch, from its own records. */
    private val agentForBranch: (projectId: String, branch: String) -> Agent?,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(5),
) {
    private data class Entry(val insights: RepoInsights?, val at: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "insights").apply { isDaemon = true } }
    private val log = LoggerFactory.getLogger(InsightsService::class.java)

    /** [projects] with their latest known insights; stale or missing ones are refreshed in the background. */
    fun annotate(projects: List<Project>): List<Project> = projects.map { project ->
        val forge = project.originUrl?.let { Forge.of(RemoteUrl.of(it)) } ?: return@map project
        val entry = cache[project.id]
        if (entry == null || entry.at.plus(ttl).isBefore(Instant.now(clock))) refreshLater(project.id, forge)
        project.copy(insights = entry?.insights)
    }

    /** Fetches now, on the caller's thread; for tests and after publishing. */
    fun refresh(projectId: String, forge: Forge) {
        val since = Instant.now(clock).minus(WEEK)
        val previous = cache[projectId]?.insights
        val insights = sources.fetch(forge, since).fold(
            onSuccess = { activity ->
                val recent = activity.recent.map { pr ->
                    pr.summary.copy(agent = AgentAttribution.of(pr, pr.summary.branch?.let { agentForBranch(projectId, it) }))
                }
                RepoInsights(
                    latestMerged = recent.filter { it.state == "merged" }.maxByOrNull { it.mergedAt.orEmpty() },
                    mergedLastWeek = activity.mergedLastWeek, openCount = activity.openCount, recent = recent,
                    fetchedAt = Instant.now(clock).toString(),
                )
            },
            onFailure = { (previous ?: RepoInsights()).copy(error = it.message ?: it.javaClass.simpleName) },
        )
        cache[projectId] = Entry(insights, Instant.now(clock))
    }

    fun invalidate(projectId: String) {
        cache.remove(projectId)
    }

    private fun refreshLater(projectId: String, forge: Forge) {
        if (!refreshing.add(projectId)) return
        executor.execute {
            try {
                refresh(projectId, forge)
            } catch (e: IOException) {
                log.warn("Could not read pull requests for {}: {}", projectId, e.message)
            } finally {
                refreshing.remove(projectId)
            }
        }
    }

    private companion object {
        val WEEK: Duration = Duration.ofDays(7)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

