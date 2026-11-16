package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable

/** What the server knows about publishing one agent branch. */
@Serializable
data class BranchRecord(
    val projectId: String,
    val branch: String,
    val pushedCommit: String? = null,
    val pushedAt: String? = null,
    val pullRequest: PullRequestRef? = null,
    val mergedAt: String? = null,
)

/** Shown per worktree: whether its branch can be published, and how far it got. */
@Serializable
data class Publish(
    val pushed: Boolean,
    /** The pushed commit is the branch's current HEAD. */
    val upToDate: Boolean,
    val branchUrl: String? = null,
    val pullRequest: PullRequestRef? = null,
    /** Why pull requests cannot be opened yet (e.g. a missing token), or null. */
    val blocked: String? = null,
)

/**
 * Push, open a pull request for, and merge agent branches (issue #1). Only branches
 * that agents created (`run/`, `codex/`, `agent/`) in linked worktrees qualify; the
 * default branch and people's checkouts are never touched, and pushes are never forced.
 * A merged branch is cleaned up: see [MergeCleanup].
 */
class BranchService(
    private val store: StateStore,
    private val branches: BranchStore,
    private val workspace: Workspace,
    private val forges: ForgeClient,
    private val credentials: ForgeCredentials,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Outcome {
        data class Ok(val record: BranchRecord, val message: String) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    /** What the forge last said about a pull request, to notice one that was merged there. */
    var pullRequests: PullRequestLookup = PullRequestLookup { _, _ -> null }
    private val cleanup = MergeCleanup(workspace.git)

    private data class Target(val located: ProjectScanner.Located, val worktree: Worktree, val forge: Forge?, val base: String?)

    /** Adds publish state to every agent worktree in [projects]. */
    fun annotate(projects: List<Project>): List<Project> = projects.map { project ->
        val forge = project.originUrl?.let { Forge.of(RemoteUrl.of(it)) }
        project.copy(worktrees = project.worktrees.map { worktree ->
            val branch = worktree.branch
            if (branch == null || worktree.main || !isAgentBranch(branch)) return@map worktree
            val record = branches.get(project.id, branch)
            worktree.copy(
                publish = Publish(
                    pushed = record?.pushedCommit != null,
                    upToDate = record?.pushedCommit != null && record.pushedCommit == worktree.head,
                    branchUrl = forge?.takeIf { record?.pushedCommit != null }?.branchUrl(branch),
                    pullRequest = record?.pullRequest,
                    blocked = forge?.let(credentials::missing) ?: if (forge == null) "Pull requests need a GitHub, GitLab or Azure DevOps remote" else null,
                ),
            )
        })
    }

    fun push(projectId: String, branch: String): Outcome = withTarget(projectId, branch) { target -> pushTarget(target) }


    /** Pushes if needed, then opens (or finds) the pull request into the default branch. */
    fun openPullRequest(projectId: String, branch: String): Outcome = withTarget(projectId, branch) { target ->
        val forge = target.forge ?: return@withTarget Outcome.Invalid("Pull requests need a GitHub, GitLab or Azure DevOps remote")
        val base = target.base ?: return@withTarget Outcome.Invalid("The project's default branch is unknown")
        val pushed = pushTarget(target)
        if (pushed is Outcome.Invalid) return@withTarget pushed
        when (val result = forges.open(forge, describe(projectId, branch, base))) {
            is ForgeClient.Result.Failed -> Outcome.Invalid(result.reason)
            is ForgeClient.Result.Ok -> record(projectId, branch) { it.copy(pullRequest = result.pullRequest) }
                .let { Outcome.Ok(it, "Opened ${label(forge)} ${result.pullRequest.url}") }
        }
    }

    /**
     * Merges through the forge, so required checks, approvals and branch protection apply, then
     * cleans the branch up. Commits the forge has not seen are pushed first, so what is merged is
     * what the session shows; a pull request that was already merged on the forge is only cleaned up.
     */
    fun merge(projectId: String, branch: String): Outcome = withTarget(projectId, branch) { target ->
        val forge = target.forge ?: return@withTarget Outcome.Invalid("No forge for this project")
        val known = branches.get(projectId, branch)
        val pr = known?.pullRequest ?: return@withTarget Outcome.Invalid("Open a pull request first")
        if (store.runs().any { it.projectId == projectId && it.branch == branch && !it.status.finished }) {
            return@withTarget Outcome.Invalid("An agent is still working on this branch")
        }
        val alreadyMerged = SessionPulls.isMerged(pr.state) || pullRequests.find(projectId, pr.number)?.state == SessionPulls.MERGED
        if (!alreadyMerged && known.pushedCommit != target.worktree.head) {
            (pushTarget(target) as? Outcome.Invalid)?.let { return@withTarget it }
        }
        when (val result = if (alreadyMerged) ForgeClient.Result.Ok(pr.copy(state = SessionPulls.MERGED)) else forges.merge(forge, pr)) {
            is ForgeClient.Result.Failed -> Outcome.Invalid(result.reason)
            is ForgeClient.Result.Ok -> cleanUp(target, forge, result.pullRequest)
        }
    }

    private fun cleanUp(target: Target, forge: Forge, merged: PullRequestRef): Outcome {
        val record = record(target.located.project.id, checkNotNull(target.worktree.branch)) {
            it.copy(pullRequest = merged, mergedAt = it.mergedAt ?: Instant.now(clock).toString())
        }
        val env = credentials.pushEnvironment(forge, rawOrigin(target.located.commonDir))
        val kept = cleanup.run(target.located.commonDir, target.worktree, env)
        val done = if (kept.isEmpty()) "The branch and its worktree were removed." else kept.joinToString(" ")
        return Outcome.Ok(record, "Merged into ${target.base}. $done")
    }

    private fun withTarget(projectId: String, branch: String, action: (Target) -> Outcome): Outcome {
        val located = workspace.scanner.locate(projectId) ?: return Outcome.Invalid("Unknown project")
        val worktree = located.project.worktrees.firstOrNull { it.branch == branch && !it.main && !it.missing }
        val base = located.project.defaultBranch
        return when {
            !isAgentBranch(branch) -> Outcome.Invalid("Only branches created by agents (run/, codex/, agent/) can be published here")
            worktree == null -> Outcome.Invalid("No agent worktree has that branch")
            branch == base -> Outcome.Invalid("Refusing to publish the default branch")
            located.project.originUrl == null -> Outcome.Invalid("The project has no origin remote")
            else -> action(Target(located, worktree, located.project.originUrl?.let { Forge.of(RemoteUrl.of(it)) }, base))
        }
    }

    private fun pushTarget(target: Target): Outcome {
        val branch = checkNotNull(target.worktree.branch)
        val head = target.worktree.head
        val env = target.forge?.let { credentials.pushEnvironment(it, rawOrigin(target.located.commonDir)) }.orEmpty()
        // An explicit refspec pushes exactly this branch; without "+" it is never forced.
        val pushed = head?.let { workspace.git.exec(target.located.commonDir, listOf("push", "origin", "refs/heads/$branch:refs/heads/$branch"), env) }
        return when {
            head == null || pushed == null -> Outcome.Invalid("The branch has no commits")
            !pushed.ok -> Outcome.Invalid("Push failed: " + RunOutput.redact(pushed.stderr.lines().lastOrNull { it.isNotBlank() } ?: "git push failed"))
            else -> {
                val record = record(target.located.project.id, branch) { it.copy(pushedCommit = head, pushedAt = Instant.now(clock).toString()) }
                Outcome.Ok(record, "Pushed $branch")
            }
        }
    }

    /** The configured origin URL itself (credentials included, if any), only to choose the auth host. */
    private fun rawOrigin(commonDir: File): String = workspace.git.run(commonDir, "config", "--get", "remote.origin.url")?.trim().orEmpty()

    /** Title and body from the run that produced the branch, when there is one; the issues its messages referred to are closed by it. */
    private fun describe(projectId: String, branch: String, base: String): PullRequestDraft {
        val runs = store.runs().filter { it.projectId == projectId && it.branch == branch }.sortedBy { it.createdAt }
        val first = runs.firstOrNull()
        val task = first?.let(store::task)?.trim().orEmpty()
        val issues = runs.mapNotNull { it.jobId }.distinct().mapNotNull(store::job).flatMap { it.issues }.map { it.number }.distinct()
        val title = task.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS) ?: "Agent changes from $branch"
        val stages = runs.joinToString("\n") { "- ${it.agent.label} ${it.stage.name.lowercase()}: ${it.status.name.lowercase()}" }
        val review = runs.lastOrNull { it.stage == Stage.REVIEW }?.summary?.let { "\n\n### Review\n\n$it" }.orEmpty()
        val body = buildString {
            append("Opened by LLM Manager from branch `$branch`.")
            if (task.isNotEmpty()) append("\n\n### Task\n\n$task")
            if (stages.isNotEmpty()) append("\n\n### Runs\n\n$stages")
            append(review)
        }
        return PullRequestDraft(branch, base, title, body, closes = issues)
    }

    private fun record(projectId: String, branch: String, change: (BranchRecord) -> BranchRecord): BranchRecord =
        change(branches.get(projectId, branch) ?: BranchRecord(projectId, branch)).also(branches::put)

    companion object {
        private const val TITLE_CHARS = 72
        val AGENT_PREFIXES = listOf("run/", "codex/", "agent/")

        fun isAgentBranch(branch: String): Boolean = AGENT_PREFIXES.any { branch.startsWith(it) } && !branch.contains("..")
    }
}

/** Pushes [run]'s branch and opens its pull request; says what happened, for the run's record. */
fun BranchService.publish(run: Run): String = when (val outcome = openPullRequest(run.projectId, checkNotNull(run.branch))) {
    is BranchService.Outcome.Ok -> outcome.message
    is BranchService.Outcome.Invalid -> "Could not open a pull request: ${outcome.reason}"
}

private fun label(forge: Forge) = if (forge is Forge.GitLab) "merge request" else "pull request"
