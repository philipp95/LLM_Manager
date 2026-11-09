package com.llmmanager.server.api

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Where a repository is hosted, parsed from its origin URL. Every part is validated
 * against a strict pattern, so web links built from it can be served to the phone.
 */
sealed interface Forge {
    /** Stable identifier for clients: github, gitlab, azure-devops. */
    val id: String

    /** Display name, e.g. owner/repo. */
    val name: String

    /** The repository's web page. */
    val webUrl: String

    fun branchUrl(branch: String): String

    data class GitHub(val owner: String, val repo: String) : Forge {
        override val id = "github"
        override val name = "$owner/$repo"
        override val webUrl = "https://github.com/$owner/$repo"
        override fun branchUrl(branch: String) = "$webUrl/tree/${path(branch)}"
    }

    /** gitlab.com or a self-hosted GitLab; [path] may contain nested groups. */
    data class GitLab(val host: String, val path: String) : Forge {
        override val id = "gitlab"
        override val name = path
        override val webUrl = "https://$host/$path"
        override fun branchUrl(branch: String) = "$webUrl/-/tree/${path(branch)}"
        val apiBase: String get() = "https://$host/api/v4"
    }

    data class AzureDevOps(val organization: String, val project: String, val repo: String) : Forge {
        override val id = "azure-devops"
        override val name = "$organization/$project/$repo"
        override val webUrl = "https://dev.azure.com/${segment(organization)}/${segment(project)}/_git/${segment(repo)}"
        override fun branchUrl(branch: String) = "$webUrl?version=GB${query(branch)}"
        fun pullRequestUrl(id: Long) = "$webUrl/pullrequest/$id"
    }

    companion object {
        private val NAME = Regex("""[A-Za-z0-9_.-]+""")
        private val ADO_NAME = Regex("""[A-Za-z0-9_. -]+""")
        private val HOST = Regex("""[a-z0-9.-]+""")
        private val SCP = Regex("""^(?:[^@/\s]+@)?([^:/\s]+):(?!//)(.+)$""")

        /** The forge for [remote], or null for local paths and unknown hosts. */
        fun of(remote: RemoteUrl): Forge? {
            val (host, rawPath) = hostAndPath(remote.display) ?: return null
            val parts = decode(rawPath).trim('/').removeSuffix(".git").split('/').filter { it.isNotEmpty() }
            return when {
                host == "github.com" -> github(parts)
                host == "dev.azure.com" -> azure(parts.takeIf { it.size == 4 && it[2] == "_git" }?.let { listOf(it[0], it[1], it[3]) })
                host == "ssh.dev.azure.com" -> azure(parts.takeIf { it.size == 4 && it[0] == "v3" }?.drop(1))
                host.endsWith(".visualstudio.com") -> azure(visualStudio(host, parts))
                else -> gitlab(host, parts)
            }
        }

        private fun github(parts: List<String>): GitHub? = parts.takeIf { it.size == 2 && it.all(NAME::matches) }?.let { GitHub(it[0], it[1]) }

        /** gitlab.com, or a self-hosted instance whose host name has a "gitlab" label. */
        private fun gitlab(host: String, parts: List<String>): GitLab? {
            val isGitLab = (host == "gitlab.com" || host.split('.').any { it == "gitlab" }) && HOST.matches(host)
            return parts.takeIf { isGitLab && it.size >= 2 && it.all(NAME::matches) }?.let { GitLab(host, it.joinToString("/")) }
        }

        private fun azure(parts: List<String>?): AzureDevOps? =
            parts?.takeIf { it.size == 3 && NAME.matches(it[0]) && ADO_NAME.matches(it[1]) && ADO_NAME.matches(it[2]) }
                ?.let { AzureDevOps(it[0], it[1], it[2]) }

        /** Legacy https://{org}.visualstudio.com/[DefaultCollection/]{project}/_git/{repo}. */
        private fun visualStudio(host: String, parts: List<String>): List<String>? {
            val rest = if (parts.firstOrNull() == "DefaultCollection") parts.drop(1) else parts
            return rest.takeIf { it.size == 3 && it[1] == "_git" }?.let { listOf(host.removeSuffix(".visualstudio.com"), it[0], it[2]) }
        }

        private fun hostAndPath(remote: String): Pair<String, String>? {
            val uri = try {
                URI(remote).takeIf { it.scheme != null && it.host != null }
            } catch (_: URISyntaxException) {
                null
            }
            return uri?.let { it.host.lowercase() to (it.rawPath ?: "") }
                ?: SCP.matchEntire(remote)?.let { it.groupValues[1].lowercase() to it.groupValues[2] }
        }

        private fun decode(path: String): String = java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /** Branch names keep their slashes in web paths. */
        private fun path(branch: String): String = branch.split('/').joinToString("/") { segment(it) }
    }
}
