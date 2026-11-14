package com.llmmanager.server.api

import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Shared wire representation of a GitHub issue or a repository-linked Azure work item. */
@Serializable
data class RepoWorkItem(
    val number: Long,
    val title: String,
    val url: String,
    val state: String,
    val type: String = "Issue",
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class RepoWorkItems(
    val items: List<RepoWorkItem> = emptyList(),
    val fetchedAt: String? = null,
    val error: String? = null,
    /** Explains the provider's selection and any scan limit, including an empty result. */
    val scope: String = "Open issues in this repository.",
    val truncated: Boolean = false,
)

fun interface WorkItemSource {
    fun fetch(forge: Forge): Result<RepoWorkItems>
}

/** Provider credentials stay on the server; requests never follow redirects. */
internal class WorkItemSources(
    private val credentials: ForgeCredentials,
    private val azureApi: String = "https://dev.azure.com",
) : WorkItemSource {
    private val http = ForgeHttp()

    override fun fetch(forge: Forge): Result<RepoWorkItems> = runCatching {
        when (forge) {
            is Forge.GitHub -> github(forge)
            is Forge.AzureDevOps -> azure(forge)
            is Forge.GitLab -> throw IOException("Issue browsing is available for GitHub and Azure DevOps")
        }
    }

    private fun github(forge: Forge.GitHub): RepoWorkItems {
        val gh = credentials.gh ?: throw IOException("Sign in to GitHub in Settings → Code hosts")
        val result = Processes.exec(
            listOf(gh, "issue", "list", "--repo", forge.name, "--state", "open", "--limit", "101", "--json",
                "number,title,url,state,labels,assignees,updatedAt"),
            environment = mapOf("GH_PROMPT_DISABLED" to "1"),
        )
        if (!result.ok) throw IOException(RunOutput.redact(result.stderr.trim().ifEmpty { "GitHub issues could not be read" }))
        val issues = readArray(result.stdout)
        val items = issues.take(ISSUE_LIMIT).map { element ->
            val issue = element as JsonObject
            RepoWorkItem(
                number = issue.number("number"), title = issue.text("title").orEmpty(), url = issue.text("url").orEmpty(),
                state = issue.text("state").orEmpty().lowercase(), updatedAt = issue.text("updatedAt"),
                labels = issue.array("labels").mapNotNull { (it as? JsonObject)?.text("name") },
                assignee = issue.array("assignees").mapNotNull { (it as? JsonObject)?.text("login") }.joinToString(", ").ifEmpty { null },
            )
        }
        return RepoWorkItems(items, truncated = issues.size > ISSUE_LIMIT,
            scope = if (issues.size > ISSUE_LIMIT) "Showing the first $ISSUE_LIMIT open issues. Open GitHub for the full list."
            else "Open issues in this repository.")
    }

    private fun azure(forge: Forge.AzureDevOps): RepoWorkItems {
        val token = credentials.azureToken() ?: throw IOException("Add an Azure DevOps PAT with Code: Read and Work Items: Read in Settings → Code hosts")
        val auth = "Basic " + Base64.getEncoder().encodeToString(":$token".toByteArray())
        val root = "$azureApi/${segment(forge.organization)}/${segment(forge.project)}/_apis"
        val repository = azureRequest(root, auth, "git/repositories/${segment(forge.repo)}")
        val repoId = repository.requiredText("id")
        val projectId = (repository["project"] as? JsonObject).orEmptyObject().requiredText("id")
        val query = buildJsonObject {
            put("query", "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = @project ORDER BY [System.ChangedDate] DESC")
        }
        val candidates = azureRequest(root, auth, "wit/wiql?%24top=${AZURE_SCAN_LIMIT + 1}", query).requiredArray("workItems")
        val ids = candidates.take(AZURE_SCAN_LIMIT).map { (it as JsonObject).number("id") }
        val items = if (ids.isEmpty()) emptyList() else {
            azureRequest(root, auth, "wit/workitems?ids=${ids.joinToString(",")}&%24expand=Relations").requiredArray("value")
                .map { it as JsonObject }.filter { linkedToRepository(it, projectId, repoId) }.map { azureItem(it, forge) }
        }
        val truncated = candidates.size > AZURE_SCAN_LIMIT
        return RepoWorkItems(items, truncated = truncated, scope =
            "Work items linked to this repository's branches, commits or pull requests. " +
                if (truncated) "Checked the $AZURE_SCAN_LIMIT most recently changed items in this Azure project; older links may be missing."
                else "Items without a repository link are excluded.")
    }

    private fun azureItem(item: JsonObject, forge: Forge.AzureDevOps): RepoWorkItem {
        val fields = (item["fields"] as? JsonObject).orEmptyObject()
        val number = item.number("id")
        return RepoWorkItem(
            number, fields.text("System.Title").orEmpty(),
            "https://dev.azure.com/${segment(forge.organization)}/${segment(forge.project)}/_workitems/edit/$number",
            fields.text("System.State").orEmpty(), fields.text("System.WorkItemType") ?: "Work item",
            assignee = (fields["System.AssignedTo"] as? JsonObject)?.text("displayName") ?: fields.text("System.AssignedTo"),
            labels = fields.text("System.Tags")?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
            updatedAt = fields.text("System.ChangedDate"),
        )
    }

    private fun azureRequest(root: String, auth: String, path: String, body: JsonObject? = null): JsonObject {
        val builder = http.request("$root/$path${if ('?' in path) "&" else "?"}api-version=7.1").header("Authorization", auth)
        if (body == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        val response = http.send(builder)
        if (!http.isJson(response)) {
            throw IOException("Azure DevOps returned a sign-in page. Check the PAT and Work Items: Read scope in Settings.")
        }
        if (response.statusCode() !in 200..299) throw IOException(http.error("Azure DevOps (Work Items: Read required)", response))
        return readObject(response.body())
    }

    private fun linkedToRepository(item: JsonObject, projectId: String, repoId: String): Boolean = item.array("relations").any { element ->
        val relation = element as? JsonObject
        val url = relation?.text("url").orEmpty()
        if (relation?.text("rel") != "ArtifactLink" || !url.startsWith("vstfs:///Git/")) return@any false
        val kindAndId = url.removePrefix("vstfs:///Git/").split('/', limit = 2)
        if (kindAndId.size != 2 || kindAndId[0] !in setOf("Commit", "Ref", "PullRequestId")) return@any false
        val parts = URLDecoder.decode(kindAndId[1].replace("+", "%2B"), Charsets.UTF_8).split('/')
        parts.size >= 3 && parts[0].equals(projectId, ignoreCase = true) && parts[1].equals(repoId, ignoreCase = true)
    }

    private companion object {
        const val ISSUE_LIMIT = 100
        const val AZURE_SCAN_LIMIT = 200
    }
}

/** Cached independently of PRs so a missing work-item permission does not hide branch history. */
class WorkItemsService(private val source: WorkItemSource, private val clock: Clock = Clock.systemUTC()) {
    private data class Entry(val value: RepoWorkItems, val at: Instant)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "work-items").apply { isDaemon = true } }

    fun annotate(projects: List<Project>): List<Project> = projects.map { project ->
        val forge = project.originUrl?.let { Forge.of(RemoteUrl.of(it)) }
        if (forge !is Forge.GitHub && forge !is Forge.AzureDevOps) return@map project
        val entry = cache[project.id]
        if ((entry == null || entry.at.plus(TTL).isBefore(Instant.now(clock))) && refreshing.add(project.id)) {
            executor.execute {
                try { refresh(project.id, forge) } finally { refreshing.remove(project.id) }
            }
        }
        project.copy(workItems = entry?.value)
    }

    fun refresh(projectId: String, forge: Forge) {
        val previous = cache[projectId]?.value
        val value = source.fetch(forge).fold(
            onSuccess = { it.copy(fetchedAt = Instant.now(clock).toString(), error = null) },
            onFailure = { (previous ?: RepoWorkItems()).copy(error = RunOutput.redact(it.message ?: "Could not read work items")) },
        )
        cache[projectId] = Entry(value, Instant.now(clock))
    }

    private companion object { val TTL: Duration = Duration.ofMinutes(5) }
}

private val workItemJson = Json { ignoreUnknownKeys = true }
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: throw IOException("Missing work item ID")
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun readObject(text: String): JsonObject =
    workItemJson.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid work item response")
private fun readArray(text: String): JsonArray =
    workItemJson.parseToJsonElement(text) as? JsonArray ?: throw IOException("Invalid issue list")
private fun JsonObject?.orEmptyObject(): JsonObject = this ?: JsonObject(emptyMap())
private fun JsonObject.requiredText(key: String): String = text(key) ?: throw IOException("Missing $key")
private fun JsonObject.requiredArray(key: String): JsonArray =
    this[key] as? JsonArray ?: throw IOException("Missing work item list: $key")
