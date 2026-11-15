package com.llmmanager.server.api

import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** An issue (GitHub, GitLab) or work item (Azure DevOps) as the dashboard shows it. */
@Serializable
data class IssueSummary(
    val number: Long,
    val title: String,
    val url: String,
    /** open or closed. */
    val state: String,
    /** Azure DevOps: the work item type, such as Bug or User Story. */
    val kind: String? = null,
    /** Azure DevOps: the state in the team's own words, such as Active. */
    val status: String? = null,
    val author: String? = null,
    val assignees: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val comments: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** A repository's issues on its forge: the open ones, most recently updated first, and the latest closed ones. */
@Serializable
data class RepoIssues(
    val openCount: Int = 0,
    val open: List<IssueSummary> = emptyList(),
    val closed: List<IssueSummary> = emptyList(),
    /** The forge's own page of them. */
    val url: String? = null,
    val fetchedAt: String? = null,
    /** Why the forge could not be read (e.g. a missing token); earlier data is kept. */
    val error: String? = null,
)

/** Where issues come from; a seam for tests. */
fun interface IssueSource {
    fun fetch(forge: Forge): Result<RepoIssues>
}

/** Reads issues from GitHub (via `gh`) and GitLab, and work items from Azure DevOps. Read only. */
class IssueSources(
    private val credentials: ForgeCredentials,
    gitlabApi: (Forge.GitLab) -> String = { it.apiBase },
    azureApi: String = "https://dev.azure.com",
) : IssueSource {
    private val github = GitHubIssues(credentials)
    private val gitlab = GitLabIssues(credentials, ForgeHttp(), gitlabApi)
    private val azure = AzureWorkItems(credentials, ForgeHttp(), azureApi)

    override fun fetch(forge: Forge): Result<RepoIssues> {
        credentials.missing(forge)?.let { return Result.failure(IOException(it)) }
        return runCatching {
            when (forge) {
                is Forge.GitHub -> github.read(forge)
                is Forge.GitLab -> gitlab.read(forge)
                is Forge.AzureDevOps -> azure.read(forge)
            }
        }
    }

    internal companion object {
        const val OPEN = 50
        const val CLOSED = 15
    }
}

/** GitHub: one GraphQL query through the signed-in gh CLI. */
private class GitHubIssues(private val credentials: ForgeCredentials) {
    fun read(forge: Forge.GitHub): RepoIssues {
        val command = listOf(checkNotNull(credentials.gh), "api", "graphql", "-f", "query=$QUERY", "-f", "owner=${forge.owner}", "-f", "name=${forge.repo}")
        val result = Processes.exec(command, environment = mapOf("GH_PROMPT_DISABLED" to "1"))
        val body = issueJson.parseToJsonElement(result.stdout.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        val errors = body.array("errors")?.mapNotNull { (it as? JsonObject)?.text("message") }
        if (!result.ok || !errors.isNullOrEmpty()) {
            throw IOException(errors?.firstOrNull() ?: result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "gh failed")
        }
        val repository = body.obj("data")?.obj("repository") ?: throw IOException("Repository not found on GitHub")
        fun issues(key: String) = repository.obj(key)?.array("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.let(::issue) }
        return RepoIssues(repository.obj("open")?.int("totalCount") ?: 0, issues("open"), issues("closed"), "${forge.webUrl}/issues")
    }

    private fun issue(node: JsonObject) = IssueSummary(
        number = node.long("number"), title = node.text("title").orEmpty(), url = node.text("url").orEmpty(),
        state = if (node.text("state") == "CLOSED") "closed" else "open",
        author = node.obj("author")?.text("login"),
        assignees = node.obj("assignees")?.array("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.text("login") },
        labels = node.obj("labels")?.array("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
        comments = node.obj("comments")?.int("totalCount"),
        createdAt = node.text("createdAt"), updatedAt = node.text("updatedAt"),
    )

    private companion object {
        const val FIELDS = "number title url state createdAt updatedAt author { login } " +
            "assignees(first: 5) { nodes { login } } labels(first: 8) { nodes { name } } comments { totalCount }"
        val QUERY = """
            query(${'$'}owner: String!, ${'$'}name: String!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                open: issues(first: ${IssueSources.OPEN}, states: OPEN, orderBy: {field: UPDATED_AT, direction: DESC}) { totalCount nodes { $FIELDS } }
                closed: issues(first: ${IssueSources.CLOSED}, states: CLOSED, orderBy: {field: UPDATED_AT, direction: DESC}) { nodes { $FIELDS } }
              }
            }
        """.trimIndent()
    }
}

/** GitLab REST, with the token bound to the instance's host. */
private class GitLabIssues(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: (Forge.GitLab) -> String) {
    fun read(forge: Forge.GitLab): RepoIssues {
        val issues = "${api(forge)}/projects/${segment(forge.path)}/issues?order_by=updated_at&sort=desc"
        fun get(url: String) = http.send(http.request(url).header("PRIVATE-TOKEN", checkNotNull(credentials.gitlabToken(forge.host))).GET())
            .requireOk(http, "GitLab")
        fun list(response: HttpResponse<String>) = (issueJson.parseToJsonElement(response.body()) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::issue) }
        val open = get("$issues&state=opened&per_page=${IssueSources.OPEN}")
        val shown = list(open)
        val total = open.headers().firstValue("X-Total").orElse(null)?.toIntOrNull() ?: shown.size
        return RepoIssues(total, shown, list(get("$issues&state=closed&per_page=${IssueSources.CLOSED}")), "${forge.webUrl}/-/issues")
    }

    private fun issue(node: JsonObject) = IssueSummary(
        number = node.long("iid"), title = node.text("title").orEmpty(), url = node.text("web_url").orEmpty(),
        state = if (node.text("state") == "closed") "closed" else "open",
        author = node.obj("author")?.text("username"),
        assignees = node.array("assignees").orEmpty().mapNotNull { (it as? JsonObject)?.text("username") },
        labels = node.array("labels").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        comments = node.int("user_notes_count"),
        createdAt = node.text("created_at"), updatedAt = node.text("updated_at"),
    )
}

/**
 * Azure DevOps keeps work items per project, not per repository: these are the project's.
 * A WIQL query finds them, a second call reads their fields. The token needs Work Items: Read.
 */
private class AzureWorkItems(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: String) {
    fun read(forge: Forge.AzureDevOps): RepoIssues {
        val project = "$api/${segment(forge.organization)}/${segment(forge.project)}"
        val auth = "Basic " + Base64.getEncoder().encodeToString(":${checkNotNull(credentials.azureToken())}".toByteArray())
        fun send(request: HttpRequest.Builder): JsonObject? {
            val response = http.send(request.header("Authorization", auth))
            if (!http.isJson(response)) {
                throw IOException("Azure DevOps did not accept the token (HTTP ${response.statusCode()}, sign-in page instead of JSON)")
            }
            if (response.statusCode() == HTTP_UNAUTHORIZED || response.statusCode() == HTTP_FORBIDDEN) {
                throw IOException("Azure DevOps refused: the token needs the scope Work Items: Read")
            }
            return issueJson.parseToJsonElement(response.requireOk(http, "Azure DevOps").body()) as? JsonObject
        }
        fun ids(done: Boolean, top: Int): List<Long> {
            val query = "Select [System.Id] From WorkItems Where [System.TeamProject] = @project And [System.State] " +
                (if (done) "In" else "Not In") + " ($DONE) Order By [System.ChangedDate] Desc"
            val body = buildJsonObject { put("query", query) }.toString()
            return send(http.request("$project/_apis/wit/wiql?%24top=$top&api-version=$VERSION").POST(HttpRequest.BodyPublishers.ofString(body)))
                ?.array("workItems").orEmpty().mapNotNull { (it as? JsonObject)?.long("id")?.takeIf { id -> id > 0 } }
        }
        fun items(ids: List<Long>, state: String): List<IssueSummary> {
            if (ids.isEmpty()) return emptyList()
            val found = send(http.request("$project/_apis/wit/workitems?ids=${ids.joinToString(",")}&fields=$FIELDS&api-version=$VERSION").GET())
                ?.array("value").orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.long("id") }
            return ids.mapNotNull { id -> found[id]?.obj("fields")?.let { workItem(forge, id, state, it) } }
        }
        val open = ids(done = false, top = COUNTED)
        return RepoIssues(
            open.size, items(open.take(IssueSources.OPEN), "open"), items(ids(done = true, top = IssueSources.CLOSED), "closed"),
            "${webBase(forge)}/_workitems",
        )
    }

    private fun workItem(forge: Forge.AzureDevOps, id: Long, state: String, fields: JsonObject) = IssueSummary(
        number = id, title = fields.text("System.Title").orEmpty(), url = "${webBase(forge)}/_workitems/edit/$id", state = state,
        kind = fields.text("System.WorkItemType"), status = fields.text("System.State"),
        author = person(fields["System.CreatedBy"]), assignees = listOfNotNull(person(fields["System.AssignedTo"])),
        labels = fields.text("System.Tags").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() },
        comments = fields.int("System.CommentCount"),
        createdAt = fields.text("System.CreatedDate"), updatedAt = fields.text("System.ChangedDate"),
    )

    /** People come as an object, or as "Name <mail>" from older servers. */
    private fun person(value: JsonElement?): String? = when (value) {
        is JsonObject -> value.text("displayName") ?: value.text("uniqueName")
        is JsonPrimitive -> value.contentOrNull?.substringBefore(" <")?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun webBase(forge: Forge.AzureDevOps) = "https://dev.azure.com/${segment(forge.organization)}/${segment(forge.project)}"

    private companion object {
        const val VERSION = "7.1"
        const val COUNTED = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403

        /** The states the stock processes (Agile, Scrum, Basic, CMMI) use for finished work. */
        const val DONE = "'Closed', 'Done', 'Removed', 'Resolved', 'Completed'"
        val FIELDS = listOf(
            "System.Title", "System.State", "System.WorkItemType", "System.AssignedTo", "System.CreatedBy",
            "System.CreatedDate", "System.ChangedDate", "System.Tags", "System.CommentCount",
        ).joinToString(",")
    }
}

/**
 * Issues per project, read when someone looks and kept for [ttl], so a page that polls
 * does not multiply API calls. When a forge cannot be read, what was known stays.
 */
class IssuesService(private val source: IssueSource, private val clock: Clock = Clock.systemUTC(), private val ttl: Duration = Duration.ofMinutes(1)) {
    private data class Entry(val issues: RepoIssues, val at: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun of(projectId: String, forge: Forge): RepoIssues {
        val now = Instant.now(clock)
        val entry = cache[projectId]
        if (entry != null && entry.at.plus(ttl).isAfter(now)) return entry.issues
        val issues = source.fetch(forge).fold(
            onSuccess = { it.copy(fetchedAt = now.toString()) },
            onFailure = { (entry?.issues ?: RepoIssues()).copy(error = it.message ?: it.javaClass.simpleName) },
        )
        cache[projectId] = Entry(issues, now)
        return issues
    }
}

private val issueJson = Json { ignoreUnknownKeys = true }
private val OK = 200..299

private fun HttpResponse<String>.requireOk(http: ForgeHttp, name: String): HttpResponse<String> =
    if (statusCode() in OK) this else throw IOException(http.error(name, this))

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
