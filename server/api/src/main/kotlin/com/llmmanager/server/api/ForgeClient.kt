package com.llmmanager.server.api

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A pull request (GitHub, Azure DevOps) or merge request (GitLab) opened for an agent branch. */
@Serializable
data class PullRequestRef(val forge: String, val number: Long, val url: String, val state: String)

/**
 * Credentials for forges, all on the server and never sent to the phone. GitHub goes
 * through the signed-in `gh` CLI. GitLab and Azure DevOps tokens live in owner-only files
 * under [dir], each bound to one host: `gitlab-token` is for gitlab.com only, a self-hosted
 * instance needs `gitlab-token-<host>`, and `azure-devops-token` is sent only to
 * dev.azure.com. A token never goes to a host just because a repository's origin names it.
 */
class ForgeCredentials(val dir: File, val gh: String?) {
    /** The token for GitLab at [host], or null when there is none (or it is not private). */
    fun gitlabToken(host: String): String? = read(gitlabFile(host))

    fun azureToken(): String? = read(azureFile())

    /** What is missing to open pull requests on [forge], or null when it is ready. */
    fun missing(forge: Forge): String? = when (forge) {
        is Forge.GitHub -> if (gh == null) "Install the GitHub CLI (gh) on the server and run `gh auth login`" else null
        is Forge.GitLab -> problem(gitlabFile(forge.host), "a GitLab token (api scope) for ${forge.host}")
        is Forge.AzureDevOps -> problem(azureFile(), "an organisation-scoped Azure DevOps PAT (Code: Read & write)")
    }

    /**
     * HTTPS push credentials for GitLab and Azure DevOps, as `GIT_CONFIG_*` variables so the
     * token never appears on a command line, scoped to the forge's own host. Nothing is
     * returned when [originUrl] points anywhere else.
     */
    fun pushEnvironment(forge: Forge, originUrl: String): Map<String, String> {
        val host = runCatching { URI(originUrl) }.getOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase() ?: return emptyMap()
        val basic = when (forge) {
            is Forge.GitLab -> gitlabToken(forge.host)?.takeIf { host == forge.host }?.let { "oauth2:$it" }
            is Forge.AzureDevOps -> azureToken()?.takeIf { host == "dev.azure.com" || host.endsWith(".visualstudio.com") }?.let { ":$it" }
            is Forge.GitHub -> null
        } ?: return emptyMap()
        return mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.https://$host/.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Basic " + Base64.getEncoder().encodeToString(basic.toByteArray()),
        )
    }

    internal fun gitlabFile(host: String) = File(dir, if (host == "gitlab.com") GITLAB_FILE else "$GITLAB_FILE-$host")

    internal fun azureFile() = File(dir, AZURE_FILE)

    private fun problem(file: File, what: String): String? = when {
        !file.isFile -> "Add $what to $file (chmod 600)"
        !SecureFiles.isPrivate(file) -> "$file is readable by other users; run chmod 600 $file"
        file.readText().isBlank() -> "$file is empty"
        else -> null
    }

    /** Only owner-only token files are used, the same rule ssh applies to keys. */
    private fun read(file: File): String? =
        file.takeIf { it.isFile && SecureFiles.isPrivate(it) }?.readText()?.trim()?.ifEmpty { null }

    companion object {
        const val GITLAB_FILE = "gitlab-token"
        const val AZURE_FILE = "azure-devops-token"
    }
}

/** Opens and merges pull requests. Merges go through the forge, so its checks and approvals apply. */
class ForgeClient(
    private val credentials: ForgeCredentials,
    /** API roots; overridable so tests can point at a local server. */
    gitlabApi: (Forge.GitLab) -> String = { it.apiBase },
    azureApi: String = "https://dev.azure.com",
) {
    sealed interface Result {
        data class Ok(val pullRequest: PullRequestRef) : Result
        data class Failed(val reason: String) : Result
    }

    private val http = ForgeHttp()
    private val github = GitHubPulls(credentials)
    private val gitlab = GitLabPulls(credentials, http, gitlabApi)
    private val azure = AzurePulls(credentials, http, azureApi)

    fun open(forge: Forge, branch: String, base: String, title: String, body: String): Result = guarded(forge) {
        val request = PullRequestDraft(branch, base, title, body)
        when (forge) {
            is Forge.GitHub -> github.open(forge, request)
            is Forge.GitLab -> gitlab.open(forge, request)
            is Forge.AzureDevOps -> azure.open(forge, request)
        }
    }

    fun merge(forge: Forge, pr: PullRequestRef): Result = guarded(forge) {
        when (forge) {
            is Forge.GitHub -> github.merge(forge, pr)
            is Forge.GitLab -> gitlab.merge(forge, pr)
            is Forge.AzureDevOps -> azure.merge(forge, pr)
        }
    }

    private fun guarded(forge: Forge, action: () -> Result): Result {
        credentials.missing(forge)?.let { return Result.Failed(it) }
        return try {
            action()
        } catch (e: IOException) {
            Result.Failed("Could not reach ${forge.id}: ${e.message}")
        } catch (_: IllegalArgumentException) {
            Result.Failed("${forge.id} sent a response this server could not read")
        } catch (_: ClassCastException) {
            Result.Failed("${forge.id} sent a response this server could not read")
        }
    }
}

internal data class PullRequestDraft(val branch: String, val base: String, val title: String, val body: String)

/** GitHub through the signed-in gh CLI, which holds the credentials. */
private class GitHubPulls(private val credentials: ForgeCredentials) {
    fun open(forge: Forge.GitHub, pr: PullRequestDraft): ForgeClient.Result {
        val created = gh(
            "api", "-X", "POST", "repos/${forge.name}/pulls",
            "-f", "head=${pr.branch}", "-f", "base=${pr.base}", "-f", "title=${pr.title}", "-f", "body=${pr.body}",
        )
        if (created.ok) return ref(forgeJson.parseToJsonElement(created.stdout) as JsonObject)
        // Already open for this branch: return that one instead of failing.
        val existing = gh("api", "repos/${forge.name}/pulls?head=${forge.owner}:${query(pr.branch)}&state=open")
        val open = (parseOrNull(existing.stdout) as? JsonArray)?.firstOrNull() as? JsonObject
        return open?.let(::ref) ?: ForgeClient.Result.Failed(error(created))
    }

    fun merge(forge: Forge.GitHub, pr: PullRequestRef): ForgeClient.Result {
        val merged = gh("api", "-X", "PUT", "repos/${forge.name}/pulls/${pr.number}/merge", "-f", "merge_method=merge")
        return if (merged.ok) ForgeClient.Result.Ok(pr.copy(state = "merged")) else ForgeClient.Result.Failed(error(merged))
    }

    private fun gh(vararg args: String): Processes.Exec =
        Processes.exec(listOf(checkNotNull(credentials.gh)) + args, environment = mapOf("GH_PROMPT_DISABLED" to "1"))

    private fun ref(o: JsonObject): ForgeClient.Result {
        val state = if (o.string("merged_at") != null) "merged" else o.string("state") ?: "open"
        return ForgeClient.Result.Ok(PullRequestRef("github", o.long("number"), o.string("html_url").orEmpty(), state))
    }

    private fun error(exec: Processes.Exec): String =
        (parseOrNull(exec.stdout) as? JsonObject)?.string("message") ?: exec.stderr.lines().lastOrNull { it.isNotBlank() } ?: "gh failed"
}

private class GitLabPulls(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: (Forge.GitLab) -> String) {
    fun open(forge: Forge.GitLab, pr: PullRequestDraft): ForgeClient.Result {
        val requests = "${api(forge)}/projects/${segment(forge.path)}/merge_requests"
        val payload = buildJsonObject {
            put("source_branch", pr.branch)
            put("target_branch", pr.base)
            put("title", pr.title)
            put("description", pr.body)
        }
        val created = http.send(request(forge, requests).POST(HttpRequest.BodyPublishers.ofString(payload.toString())))
        if (created.statusCode() == HTTP_CONFLICT) {
            val open = http.send(request(forge, "$requests?source_branch=${query(pr.branch)}&state=opened").GET())
            ((parseOrNull(open.body()) as? JsonArray)?.firstOrNull() as? JsonObject)?.let { return ref(it) }
        }
        return if (created.statusCode() in OK_RANGE) ref(http.parse(created)) else ForgeClient.Result.Failed(http.error("GitLab", created))
    }

    fun merge(forge: Forge.GitLab, pr: PullRequestRef): ForgeClient.Result {
        val url = "${api(forge)}/projects/${segment(forge.path)}/merge_requests/${pr.number}/merge"
        val merged = http.send(request(forge, url).PUT(HttpRequest.BodyPublishers.noBody()))
        return if (merged.statusCode() in OK_RANGE) ref(http.parse(merged)) else ForgeClient.Result.Failed(http.error("GitLab", merged))
    }

    /** The token for this forge's host only. */
    private fun request(forge: Forge.GitLab, url: String) = http.request(url).header("PRIVATE-TOKEN", checkNotNull(credentials.gitlabToken(forge.host)))

    private fun ref(o: JsonObject) =
        ForgeClient.Result.Ok(PullRequestRef("gitlab", o.long("iid"), o.string("web_url").orEmpty(), o.string("state") ?: "opened"))
}

/** Azure DevOps: api-version on every request, full refs, Basic auth with an empty user. */
private class AzurePulls(private val credentials: ForgeCredentials, private val http: ForgeHttp, private val api: String) {
    fun open(forge: Forge.AzureDevOps, pr: PullRequestDraft): ForgeClient.Result {
        val payload = buildJsonObject {
            put("sourceRefName", "refs/heads/${pr.branch}")
            put("targetRefName", "refs/heads/${pr.base}")
            put("title", pr.title)
            put("description", pr.body.take(DESCRIPTION_MAX))
        }
        val created = http.send(request("${pulls(forge)}?api-version=$API_VERSION").POST(HttpRequest.BodyPublishers.ofString(payload.toString())))
        if (created.statusCode() == HTTP_CONFLICT) {
            val source = query("refs/heads/${pr.branch}")
            val search = "${pulls(forge)}?searchCriteria.sourceRefName=$source&searchCriteria.status=active&api-version=$API_VERSION"
            val open = http.send(request(search).GET())
            (((parseOrNull(open.body()) as? JsonObject)?.get("value") as? JsonArray)?.firstOrNull() as? JsonObject)?.let { return ref(forge, it) }
        }
        return if (created.statusCode() in OK_RANGE && http.isJson(created)) ref(forge, http.parse(created)) else ForgeClient.Result.Failed(error(created))
    }

    /** Completing needs the pull request's current source commit, so it is read first. */
    fun merge(forge: Forge.AzureDevOps, pr: PullRequestRef): ForgeClient.Result {
        val url = "${pulls(forge)}/${pr.number}?api-version=$API_VERSION"
        val current = http.send(request(url).GET())
        val commit = current.takeIf { it.statusCode() in OK_RANGE && http.isJson(it) }
            ?.let { (http.parse(it)["lastMergeSourceCommit"] as? JsonObject)?.string("commitId") }
            ?: return ForgeClient.Result.Failed(if (http.isJson(current)) "Azure DevOps did not report the source commit" else error(current))
        val payload = buildJsonObject {
            put("status", "completed")
            put("lastMergeSourceCommit", buildJsonObject { put("commitId", commit) })
            put("completionOptions", buildJsonObject { put("deleteSourceBranch", false) })
        }
        val done = http.send(request(url).method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString())))
        return if (done.statusCode() in OK_RANGE && http.isJson(done)) ref(forge, http.parse(done)) else ForgeClient.Result.Failed(error(done))
    }

    private fun pulls(forge: Forge.AzureDevOps) =
        "$api/${segment(forge.organization)}/${segment(forge.project)}/_apis/git/repositories/${segment(forge.repo)}/pullrequests"

    private fun request(url: String) = http.request(url)
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(":${checkNotNull(credentials.azureToken())}".toByteArray()))

    private fun ref(forge: Forge.AzureDevOps, o: JsonObject): ForgeClient.Result {
        val id = o.long("pullRequestId")
        return ForgeClient.Result.Ok(PullRequestRef("azure-devops", id, forge.pullRequestUrl(id), o.string("status") ?: "active"))
    }

    /** A sign-in page (often HTTP 203) instead of JSON means the token was not accepted. */
    private fun error(response: HttpResponse<String>): String =
        if (!http.isJson(response)) "Azure DevOps did not accept the token (HTTP ${response.statusCode()}, sign-in page instead of JSON)"
        else http.error("Azure DevOps", response)

    private companion object {
        /** Generally available; 7.2 is still preview for several Git resources. */
        const val API_VERSION = "7.1"
        const val DESCRIPTION_MAX = 4000
    }
}

/** JSON over HTTPS without redirects, so a token is never carried to another host. */
internal class ForgeHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").header("Content-Type", "application/json")

    fun send(builder: HttpRequest.Builder): HttpResponse<String> = try {
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("interrupted", e)
    }

    fun isJson(response: HttpResponse<String>) = response.headers().firstValue("Content-Type").orElse("").contains("json")

    fun parse(response: HttpResponse<String>): JsonObject = forgeJson.parseToJsonElement(response.body()) as JsonObject

    fun error(name: String, response: HttpResponse<String>): String {
        val body = parseOrNull(response.body()) as? JsonObject
        val message = body?.let { it.string("message") ?: (it["message"] as? JsonArray)?.joinToString { m -> m.toString() } ?: it.string("error") }
        return "$name refused (HTTP ${response.statusCode()})" + (message?.let { ": $it" } ?: "")
    }
}

private val forgeJson = Json { ignoreUnknownKeys = true }
private const val HTTP_CONFLICT = 409
private val OK_RANGE = 200..299

private fun parseOrNull(text: String): JsonElement? = runCatching { forgeJson.parseToJsonElement(text) }.getOrNull()

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun query(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
