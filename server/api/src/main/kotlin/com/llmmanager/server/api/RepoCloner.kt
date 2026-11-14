package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

@Serializable
data class CloneRequest(val url: String = "")

/** A repository being added by its URL, or how that ended. */
@Serializable
data class CloneStatus(
    val id: String,
    /** The repository's web page, built from the validated parts of what was typed. */
    val url: String,
    val name: String,
    /** cloning, done or failed. */
    val state: String,
    val message: String? = null,
    /** The new project, once it is done. */
    val projectId: String? = null,
)

@Serializable
data class ClonesResponse(val clones: List<CloneStatus>)

/**
 * Adds a repository from its GitHub, GitLab or Azure DevOps address: clones it into [dir] in
 * the background, with the credentials this server already has for that host.
 *
 * What is typed is only parsed: the clone address is rebuilt from the validated owner and
 * repository, always https, so nothing typed reaches git as an option, another protocol or
 * another host. Tokens travel in the environment, never on the command line. A clone appears
 * under its final name only once it is complete.
 */
class RepoCloner(
    private val dir: File,
    private val credentials: ForgeCredentials,
    private val scanner: ProjectScanner,
    /** Called when a repository has been added. */
    @Volatile var onAdded: () -> Unit = {},
    private val means: Means = Means(),
) {
    /** How clones are made; tests point [source] at a local repository and run [background] work at once. */
    data class Means(
        val git: String = "git",
        /** Where to clone a forge's repository from. */
        val source: (Forge) -> String = ::httpsUrl,
        val background: (() -> Unit) -> Unit = { work -> Thread.ofVirtual().name("clone").start(work) },
    )

    sealed interface Outcome {
        data class Started(val status: CloneStatus) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    private val lock = Any()
    private val statuses = ArrayDeque<CloneStatus>()

    init {
        // Whatever a stopped server left half-cloned.
        dir.listFiles { child -> child.name.startsWith(PARTIAL) }?.forEach { it.deleteRecursively() }
    }

    /** The latest clones, newest first. */
    fun list(): List<CloneStatus> = synchronized(lock) { statuses.toList() }

    fun start(request: CloneRequest): Outcome {
        val forge = parse(request.url) ?: return Outcome.Invalid(
            "Paste the repository's address on GitHub, GitLab or Azure DevOps, such as https://github.com/owner/repository",
        )
        val target = target(forge)
        val status = CloneStatus(newRunId(), forge.webUrl, forge.name, CLONING, "Cloning…")
        val problem = problemWith(forge, target) ?: register(status)
        if (problem != null || target == null) return Outcome.Invalid(problem ?: "Could not add ${forge.name}")
        means.background {
            finish(status, runCatching { clone(status.id, forge, target) }.getOrElse { Result(false, "Could not clone: ${it.message}") })
        }
        return Outcome.Started(status)
    }

    private fun problemWith(forge: Forge, target: File?): String? {
        val existing = scanner.scan().firstOrNull { it.forge == forge.id && it.name.equals(forge.name, ignoreCase = true) }
        return when {
            existing != null -> "${forge.name} is already here"
            target == null -> "A folder for ${forge.name} already exists in $dir"
            !dir.isDirectory && !dir.mkdirs() -> "Could not create $dir"
            else -> null
        }
    }

    /** Lists [status] as running; or why it may not start now. */
    private fun register(status: CloneStatus): String? = synchronized(lock) {
        val running = statuses.filter { it.state == CLONING }
        when {
            running.size >= MAX_RUNNING -> "Wait until the repositories being added are done"
            running.any { it.url == status.url } -> "${status.name} is being added already"
            else -> {
                statuses.addFirst(status)
                while (statuses.size > KEPT) statuses.removeLast()
                null
            }
        }
    }

    private class Result(val ok: Boolean, val message: String, val projectId: String? = null)

    private fun finish(status: CloneStatus, result: Result) {
        val done = status.copy(state = if (result.ok) DONE else FAILED, message = result.message, projectId = result.projectId)
        synchronized(lock) { statuses.indexOfFirst { it.id == status.id }.takeIf { it >= 0 }?.let { statuses[it] = done } }
        if (result.ok) onAdded()
    }

    private fun clone(id: String, forge: Forge, target: File): Result {
        val url = means.source(forge)
        val partial = File(dir, PARTIAL + id)
        val command = listOf(means.git) + Git.HARDENING + listOf("clone", "--quiet", "--", url, partial.path)
        val cloned = Processes.exec(command, CLONE_TIMEOUT_MS, ENVIRONMENT + credentials.pushEnvironment(forge, url))
        if (!cloned.ok) {
            partial.deleteRecursively()
            return Result(false, explain(forge, cloned.stderr))
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val project = scanner.scan().firstOrNull { File(it.path).canonicalFile == target.canonicalFile }
        return Result(true, "Added ${forge.name}", project?.id)
    }

    /** A free folder for the clone: the repository's name, or with its owner in front when that is taken. */
    private fun target(forge: Forge): File? {
        val parts = forge.name.split('/').map { part -> part.map { if (it.isLetterOrDigit() || it in "._-") it else '-' }.joinToString("").trim('.', '-') }
        val names = listOf(parts.last(), parts.takeLast(2).joinToString("-"), parts.joinToString("-")).filter { it.isNotEmpty() }.distinct()
        return names.map { File(dir, it) }.firstOrNull { !it.exists() }
    }

    private fun explain(forge: Forge, stderr: String): String {
        val text = RunOutput.redact(stderr).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val denied = text.any { line -> ACCESS.any { it in line.lowercase() } }
        return when {
            text.any { it == "timed out" } -> "Cloning ${forge.name} took too long and was stopped"
            denied -> "${forge.name} was not found, or this server has no access to it. " +
                (credentials.missing(forge) ?: "Check the account for it under Settings, Code hosts.")
            else -> "Could not clone ${forge.name}: " + (text.lastOrNull()?.take(MAX_ERROR) ?: "git failed")
        }
    }

    companion object {
        const val CLONING = "cloning"
        const val DONE = "done"
        const val FAILED = "failed"
        private const val PARTIAL = ".cloning-"
        private const val MAX_RUNNING = 2
        private const val KEPT = 10
        private const val MAX_URL = 400
        private const val MAX_ERROR = 200
        private const val CLONE_TIMEOUT_MS = 20 * 60_000L
        private val ENVIRONMENT = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_OPTIONAL_LOCKS" to "0")
        private val ACCESS = listOf("authentication failed", "could not read username", "not found", "access denied", "403", "401", "terminal prompts disabled")
        private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""")
        private val SCP = Regex("""^[^@/\s]+@[^:/\s]+:.+$""")

        /** The forge repository that [typed] names: a web or clone address, with or without `https://`. */
        fun parse(typed: String): Forge? {
            val text = typed.trim().removeSuffix("/")
            val plain = text.none { it.isWhitespace() || it.isISOControl() }
            if (text.isEmpty() || text.length > MAX_URL || !plain) return null
            val address = if (SCHEME.containsMatchIn(text) || SCP.matches(text)) text else "https://$text"
            return Forge.of(RemoteUrl.of(address))
        }

        /** The https clone address of [forge], from its validated parts. */
        fun httpsUrl(forge: Forge): String = when (forge) {
            is Forge.GitHub, is Forge.GitLab -> forge.webUrl + ".git"
            is Forge.AzureDevOps -> forge.webUrl
        }
    }
}
