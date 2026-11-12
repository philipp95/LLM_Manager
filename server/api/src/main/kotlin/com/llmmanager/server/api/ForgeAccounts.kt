package com.llmmanager.server.api

import com.llmmanager.server.api.AgentAccounts.Companion.string
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A code host this server can push to and open pull requests on. */
@Serializable
data class ForgeAccount(
    /** github, gitlab or azure-devops. */
    val forge: String,
    val host: String,
    val connected: Boolean,
    val account: String? = null,
    val note: String? = null,
)

@Serializable
data class ForgeTokenRequest(
    val token: String = "",
    /** GitLab: the instance, gitlab.com by default. */
    val host: String? = null,
    /** Azure DevOps: the organisation the token is checked against. */
    val organization: String? = null,
)

/**
 * Code-host sign-ins: GitHub through the `gh` CLI's own login, GitLab and Azure DevOps through
 * owner-only token files ([ForgeCredentials]). A token is checked with its host before it is
 * saved, and none is ever sent back to a client.
 */
class ForgeAccounts(
    private val credentials: ForgeCredentials,
    private val preferences: PreferencesStore,
    private val runner: CommandRunner = CommandRunner.Default,
    /** API roots; overridable so tests can point at a local server. */
    private val gitlabApi: (String) -> String = { "https://$it/api/v4" },
    private val azureApi: String = "https://dev.azure.com",
) {
    sealed interface Outcome {
        data class Ok(val message: String) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    private val http = ForgeHttp()
    private val github = GitHubLogin(credentials.gh, runner)

    fun list(): List<ForgeAccount> = listOf(github.status()) + gitlabHosts().map(::gitlab) + azure()

    fun connect(forge: String, request: ForgeTokenRequest): Outcome {
        val token = request.token.trim()
        val outcome = try {
            when (forge) {
                "github" -> github.connect(token)
                "gitlab" -> connectGitLab(request.host?.trim()?.lowercase()?.ifEmpty { null } ?: GITLAB_COM, token)
                "azure-devops" -> connectAzure(request.organization?.trim().orEmpty(), token)
                else -> Outcome.Invalid("Unknown code host")
            }
        } catch (e: IOException) {
            Outcome.Invalid("Could not reach the code host: ${e.message}")
        }
        return outcome
    }

    fun disconnect(forge: String, host: String?): Outcome {
        val outcome = when (forge) {
            "github" -> github.disconnect()
            "gitlab" -> (host?.lowercase() ?: GITLAB_COM).let { gitlabHost ->
                if (!isGitLabHost(gitlabHost)) return Outcome.Invalid("Unknown GitLab host")
                credentials.gitlabFile(gitlabHost).delete()
                forget("gitlab:$gitlabHost")
                Outcome.Ok("GitLab token for $gitlabHost removed")
            }
            "azure-devops" -> {
                credentials.azureFile().delete()
                forget(AZURE)
                Outcome.Ok("Azure DevOps token removed")
            }
            else -> Outcome.Invalid("Unknown code host")
        }
        return outcome
    }

    private fun connectGitLab(host: String, token: String): Outcome {
        if (!isGitLabHost(host)) return Outcome.Invalid("Use the GitLab host name, such as gitlab.com or gitlab.example.com")
        if (!GITLAB_TOKEN.matches(token)) return Outcome.Invalid("That does not look like a GitLab token")
        val response = http.send(http.request("${gitlabApi(host)}/user").header("PRIVATE-TOKEN", token).GET())
        val user = response.takeIf { it.statusCode() == HTTP_OK && http.isJson(it) }?.let { http.parse(it).string("username") }
            ?: return Outcome.Invalid(if (response.statusCode() in REFUSED) "GitLab on $host did not accept this token" else http.error("GitLab", response))
        SecureFiles.writePrivate(credentials.gitlabFile(host), token + "\n")
        remember("gitlab:$host", user)
        return Outcome.Ok("GitLab on $host: signed in as $user")
    }

    /** Checked against the organisation: an Azure DevOps token is usually scoped to one. */
    private fun connectAzure(organization: String, token: String): Outcome {
        if (!ORGANIZATION.matches(organization)) return Outcome.Invalid("Enter your organisation, the name in dev.azure.com/<organisation>")
        if (!AZURE_TOKEN.matches(token)) return Outcome.Invalid("That does not look like an Azure DevOps token")
        val basic = Base64.getEncoder().encodeToString(":$token".toByteArray())
        val response = http.send(http.request("$azureApi/$organization/_apis/connectionData").header("Authorization", "Basic $basic").GET())
        val user = response.takeIf { it.statusCode() == HTTP_OK && http.isJson(it) }
            ?.let { http.parse(it)["authenticatedUser"] as? JsonObject }
            // An unrecognised token can be answered as the anonymous user.
            ?.takeUnless { it.string("descriptor").orEmpty().contains("UnauthenticatedIdentity") }
            ?.let { it.string("providerDisplayName") ?: it.string("customDisplayName") }
            ?: return Outcome.Invalid("Azure DevOps did not accept this token for $organization")
        SecureFiles.writePrivate(credentials.azureFile(), token + "\n")
        remember(AZURE, "$user ($organization)")
        return Outcome.Ok("Azure DevOps: signed in as $user")
    }

    private fun gitlabHosts(): List<String> {
        val custom = credentials.dir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("${ForgeCredentials.GITLAB_FILE}-") }
            .map { it.removePrefix("${ForgeCredentials.GITLAB_FILE}-") }.filter(::isGitLabHost)
        return listOf(GITLAB_COM) + custom.sorted()
    }

    private fun gitlab(host: String): ForgeAccount {
        val connected = credentials.gitlabToken(host) != null
        val problem = credentials.gitlabFile(host).takeIf { it.exists() && !connected }?.let { credentials.missing(Forge.GitLab(host, "x/y")) }
        return ForgeAccount(GITLAB, host, connected, account = preferences.get().forgeAccounts["gitlab:$host"].takeIf { connected }, note = problem)
    }

    private fun azure(): ForgeAccount {
        val connected = credentials.azureToken() != null
        val problem = credentials.azureFile().takeIf { it.exists() && !connected }?.let { credentials.missing(Forge.AzureDevOps("x", "y", "z")) }
        return ForgeAccount(AZURE, "dev.azure.com", connected, account = preferences.get().forgeAccounts[AZURE].takeIf { connected }, note = problem)
    }

    private fun remember(key: String, account: String) = preferences.update { it.copy(forgeAccounts = it.forgeAccounts + (key to account.take(MAX_ACCOUNT))) }

    private fun forget(key: String) = preferences.update { it.copy(forgeAccounts = it.forgeAccounts - key) }

    companion object {
        const val GITHUB = "github"
        const val GITLAB = "gitlab"
        const val AZURE = "azure-devops"
        private const val GITLAB_COM = "gitlab.com"
        private const val HTTP_OK = 200
        private const val MAX_ACCOUNT = 120
        private const val MAX_HOST = 253
        private val REFUSED = setOf(401, 403)
        private val GITLAB_TOKEN = Regex("[A-Za-z0-9_.-]{16,255}")
        private val AZURE_TOKEN = Regex("[A-Za-z0-9]{20,255}")
        private val ORGANIZATION = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
        private val HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

        /** The same hosts [Forge] recognises as GitLab: gitlab.com, or a name with a "gitlab" label. */
        fun isGitLabHost(host: String): Boolean =
            host.length <= MAX_HOST && HOST.matches(host) && !host.contains("..") && (host == GITLAB_COM || host.split('.').any { it == "gitlab" })
    }
}

/** GitHub through the `gh` CLI's own login, which holds the token; its status is cached briefly. */
private class GitHubLogin(private val gh: String?, private val runner: CommandRunner) {
    private val cached = TtlCache(TTL_MS) { check() }

    fun status(): ForgeAccount = cached.get()

    /** `gh auth login --with-token` reads the token from stdin. */
    fun connect(token: String): ForgeAccounts.Outcome {
        val cli = gh ?: return ForgeAccounts.Outcome.Invalid("Install the GitHub CLI (gh) on the server first")
        if (!TOKEN.matches(token)) return ForgeAccounts.Outcome.Invalid("That does not look like a GitHub token")
        val result = runner.run(listOf(cli, "auth", "login", "--hostname", "github.com", "--with-token"), token)
        cached.invalidate()
        return if (result.ok) ForgeAccounts.Outcome.Ok("Signed in to GitHub" + (status().account?.let { " as $it" } ?: ""))
        else ForgeAccounts.Outcome.Invalid("GitHub did not accept the token: " + lastLine(result))
    }

    fun disconnect(): ForgeAccounts.Outcome {
        val cli = gh ?: return ForgeAccounts.Outcome.Invalid("The GitHub CLI (gh) is not installed on the server")
        val account = status().account?.takeIf { USER.matches(it) }
        val result = runner.run(listOf(cli, "auth", "logout", "--hostname", "github.com") + (account?.let { listOf("--user", it) } ?: emptyList()), null)
        cached.invalidate()
        return if (result.ok) ForgeAccounts.Outcome.Ok("Signed out of GitHub") else ForgeAccounts.Outcome.Invalid(lastLine(result))
    }

    private fun check(): ForgeAccount {
        val cli = gh ?: return ForgeAccount(ForgeAccounts.GITHUB, HOST, connected = false, note = "Install the GitHub CLI (gh) on the server")
        val result = runner.run(listOf(cli, "auth", "status", "--hostname", HOST), null)
        val text = result.stdout + "\n" + result.stderr
        val account = ACCOUNT.find(text)?.groupValues?.get(1)
        val scopes = SCOPES.find(text)?.groupValues?.get(1)?.replace("'", "")?.trim()
        return ForgeAccount(ForgeAccounts.GITHUB, HOST, connected = result.ok && account != null, account = account, note = scopes?.let { "Token scopes: $it" })
    }

    private fun lastLine(result: Processes.Exec): String =
        (result.stderr + "\n" + result.stdout).lines().lastOrNull { it.isNotBlank() }?.trim() ?: "exit ${result.exitCode}"

    private companion object {
        const val HOST = "github.com"
        const val TTL_MS = 60_000L
        val ACCOUNT = Regex("""Logged in to github\.com (?:account|as) ([A-Za-z0-9-]+)""")
        val SCOPES = Regex("""Token scopes: (.+)""")
        val USER = Regex("[A-Za-z0-9-]{1,39}")
        val TOKEN = Regex("[A-Za-z0-9_]{20,255}")
    }
}
