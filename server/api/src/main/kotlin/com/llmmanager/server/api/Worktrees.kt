package com.llmmanager.server.api

import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Creates the isolated worktree an agent works in: a new `<prefix>/<project>-<time>-<rand>`
 * branch checked out under [root]. Existing checkouts are never touched.
 */
class Worktrees(private val git: Git, private val root: File, private val clock: Clock) {
    data class Created(val id: String, val branch: String, val path: File, val base: String)

    sealed interface Result {
        data class Ok(val worktree: Created) : Result
        data class Failed(val reason: String) : Result
    }

    fun create(located: ProjectScanner.Located, branchPrefix: String): Result {
        val id = "${slug(located.project.name)}-${STAMP.format(clock.instant())}-${"%04x".format(random.nextInt(SUFFIX_RANGE))}"
        val branch = "$branchPrefix/$id"
        val dir = root.absoluteFile
        val path = File(dir, id)
        val base = baseRevision(located)
        return when {
            base == null -> Result.Failed("could not determine a base commit")
            !(dir.isDirectory || dir.mkdirs()) -> Result.Failed("cannot create ${dir.path}")
            git.run(located.commonDir, "worktree", "add", "-b", branch, path.path, base) == null -> Result.Failed("git worktree add failed")
            else -> Result.Ok(Created(id, branch, path, base))
        }
    }

    /** Removes a worktree and its branch, e.g. after a launch that never started. */
    fun discard(located: ProjectScanner.Located, created: Created) {
        git.run(located.commonDir, "worktree", "remove", "--force", created.path.path)
        git.run(located.commonDir, "branch", "-D", created.branch)
    }

    /**
     * Checkouts start from what the person is working on (the main worktree's HEAD,
     * including unpushed commits); mirrors start from the remote default branch.
     */
    private fun baseRevision(located: ProjectScanner.Located): String? =
        if (located.project.kind == Project.Kind.CHECKOUT) {
            located.project.worktrees.firstOrNull { it.main }?.head
        } else {
            located.project.defaultBranch?.let { default ->
                listOf("refs/remotes/origin/$default", "refs/heads/$default").firstNotNullOfOrNull { ref ->
                    git.run(located.commonDir, "rev-parse", "--verify", "--quiet", "$ref^{commit}")?.trim()
                }
            }
        }

    private companion object {
        const val SLUG_CHARS = 32
        const val SUFFIX_RANGE = 0x10000
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        val random = SecureRandom()

        fun slug(name: String): String =
            name.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(SLUG_CHARS).ifEmpty { "project" }
    }
}
