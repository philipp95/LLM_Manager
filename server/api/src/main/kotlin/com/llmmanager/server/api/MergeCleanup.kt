package com.llmmanager.server.api

import java.io.File

/** What the forge last reported about a pull request this server opened; null when it is not known (yet). */
fun interface PullRequestLookup {
    fun find(projectId: String, number: Long): PullRequestSummary?
}

/**
 * After a merge the branch has served its purpose: it is deleted on the forge, its worktree
 * and the local branch are removed, and the merged default branch is fetched. Only ever
 * called for an agent branch in a linked worktree; nothing is forced, so a worktree with
 * changes that were never committed stays, together with its branch.
 */
class MergeCleanup(private val git: Git) {
    /** Sentences about what stayed behind; empty when everything was removed. */
    fun run(commonDir: File, worktree: Worktree, environment: Map<String, String>): List<String> {
        val branch = checkNotNull(worktree.branch)
        val remote = git.exec(commonDir, listOf("push", "origin", ":refs/heads/$branch"), environment)
        // Forges that delete merged branches themselves answer that the branch is already gone.
        val remoteGone = remote.ok || GONE.any { it in remote.stderr }
        val removed = git.exec(commonDir, listOf("worktree", "remove", worktree.path)).ok
        if (removed) git.exec(commonDir, listOf("branch", "-D", branch))
        git.exec(commonDir, listOf("fetch", "origin"), environment)
        return listOfNotNull(
            "The branch could not be deleted on the forge.".takeIf { !remoteGone },
            "The worktree has changes that were never committed, so it and the local branch were kept.".takeIf { !removed },
        )
    }

    private companion object {
        val GONE = listOf("remote ref does not exist", "unable to delete")
    }
}

/**
 * A session's pull request: this server's record of it, brought up to date with what the
 * forge last reported, so a merge done on the forge itself shows too.
 */
class SessionPulls(private val branches: BranchStore, private val forge: PullRequestLookup) {
    data class State(val pullRequest: PullRequestRef, val ci: String?, val mergedAt: String?)

    fun of(projectId: String, branch: String): State? {
        val record = branches.get(projectId, branch)
        val pr = record?.pullRequest ?: return null
        val seen = forge.find(projectId, pr.number)?.takeIf { it.branch == null || it.branch == branch }
        val merged = isMerged(pr.state) || seen?.state == MERGED
        return State(
            pullRequest = pr.copy(state = if (merged) MERGED else seen?.state ?: plain(pr.state)),
            ci = seen?.ci,
            mergedAt = record.mergedAt ?: seen?.mergedAt,
        )
    }

    companion object {
        const val MERGED = "merged"

        /** Forges name the same states differently: GitLab says opened, Azure DevOps active, completed and abandoned. */
        fun plain(state: String): String = when (state) {
            "opened", "active" -> "open"
            "completed" -> MERGED
            "abandoned" -> "closed"
            else -> state
        }

        fun isMerged(state: String): Boolean = plain(state) == MERGED
    }
}
