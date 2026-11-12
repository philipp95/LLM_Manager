package com.llmmanager.server.api

import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** A message for a repository's chat: the first of a new session, or the next one in a session. */
@Serializable
data class ChatRequest(
    val projectId: String,
    val prompt: String,
    /** The agent of a new session; the next message in a session goes to that session's agent. */
    val agent: Agent = Agent.CLAUDE,
    /** Another agent reviews the change first, then the session's agent addresses the review. */
    val reviewer: Agent? = null,
    /** The session to continue; null starts a new session on a branch of its own. */
    val chatId: String? = null,
    /** For a new session: work on this session's branch instead of a new one. Not the default. */
    val sameBranchAs: String? = null,
    /** Push the branch and open (or update) its pull request once the message is done. */
    val publish: Boolean = false,
)

/** One message in a session and what came of it: the run, or a pipeline's build, review and fix. */
@Serializable
data class ChatTurn(val message: String, val createdAt: String, val runs: List<Run>)

/** A chat session: one conversation with one agent, on one branch. */
@Serializable
data class ChatView(
    val id: String,
    val projectId: String,
    /** The first message, shortened. */
    val title: String,
    val agent: Agent,
    val branch: String?,
    val createdAt: String,
    val updatedAt: String,
    /** A message is still being worked on: the next one waits until it is done. */
    val busy: Boolean,
    /** Its branch is still checked out, so it can take another message. */
    val open: Boolean,
    /** Other sessions work on the same branch. */
    val sharesBranch: Boolean,
    val turns: List<ChatTurn>,
)

@Serializable
data class ChatsResponse(val chats: List<ChatView>)

/**
 * The repository chat, as sessions. A new session starts a new branch in a worktree of its own;
 * each further message continues the same agent session there, so the session's pull request
 * grows with it. A new session may share another session's branch, when asked: their runs then
 * take turns in that worktree.
 */
class ChatService(
    private val store: StateStore,
    private val scheduler: Scheduler,
    private val workspace: Workspace,
    private val choices: AgentChoices = AgentChoices(),
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Outcome {
        data class Ok(val chat: ChatView) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    /** Where a message runs: the agent, its session to resume (if any), and the worktree. */
    private data class Plan(val agent: Agent, val chatId: String, val resume: SessionRef?, val worktree: String?, val context: String?)

    private sealed interface Planned {
        data class Ready(val plan: Plan) : Planned
        data class Refused(val reason: String) : Planned
    }

    private val history = ChatHistory(store)

    /** The project's sessions, most recently active first. */
    fun list(projectId: String): List<ChatView> = history.list(projectId)

    fun send(request: ChatRequest): Outcome {
        val located = workspace.scanner.locate(request.projectId) ?: return Outcome.Invalid("Unknown project")
        val prompt = request.prompt.trim()
        val chatRuns = request.chatId?.let { id -> history.runsOf(request.projectId, id) }
        val planned = when {
            prompt.isEmpty() -> Planned.Refused("Write a message first")
            prompt.length > MAX_PROMPT -> Planned.Refused("The message is too long (max $MAX_PROMPT characters)")
            chatRuns != null && chatRuns.isEmpty() -> Planned.Refused("Unknown session")
            chatRuns != null -> followUp(chatRuns)
            else -> start(request)
        }.let { if (it is Planned.Ready) reviewProblem(it.plan.agent, request.reviewer)?.let(Planned::Refused) ?: it else it }
        return when (planned) {
            is Planned.Refused -> Outcome.Invalid(planned.reason)
            is Planned.Ready -> {
                store.putJob(job(request, located.project.name, prompt, planned.plan))
                scheduler.tick()
                pruneChatJobs()
                Outcome.Ok(list(request.projectId).first { it.id == planned.plan.chatId })
            }
        }
    }

    /** The next message of a session: resume its agent session in its worktree, once the last message is done. */
    private fun followUp(runs: List<Run>): Planned {
        val agent = runs.minBy { it.createdAt }.agent
        val chatId = checkNotNull(runs.first().chatId)
        val worktree = runs.sortedByDescending { it.createdAt }.firstNotNullOfOrNull { it.worktree }
        val session = runs.filter { it.agent == agent && it.sessionId != null && it.worktree == worktree && it.status in RESUMABLE }
            .maxByOrNull { it.createdAt }
        return when {
            runs.any { !it.status.finished } -> Planned.Refused("${agent.label} is still working on the last message in this session")
            !available(agent) -> Planned.Refused(unavailable(agent))
            // Its first message never got as far as a worktree: start it now.
            worktree == null -> Planned.Ready(Plan(agent, chatId, resume = null, worktree = null, context = history.context(runs)))
            !File(worktree).isDirectory -> Planned.Refused("This session's worktree was removed; start a new session")
            session != null -> Planned.Ready(Plan(agent, chatId, SessionRef(agent, checkNotNull(session.sessionId), worktree), worktree, context = null))
            // No agent session to continue (the first message failed early): a fresh one, told what came before.
            else -> Planned.Ready(Plan(agent, chatId, resume = null, worktree = worktree, context = history.context(runs)))
        }
    }

    /** A new session: a new branch, unless it is asked to share another session's. */
    private fun start(request: ChatRequest): Planned {
        val agent = request.agent
        val shared = request.sameBranchAs?.let { id ->
            history.runsOf(request.projectId, id).sortedByDescending { it.createdAt }.firstNotNullOfOrNull { it.worktree }
        }
        return when {
            !available(agent) -> Planned.Refused(unavailable(agent))
            request.sameBranchAs == null -> Planned.Ready(Plan(agent, newChatId(), resume = null, worktree = null, context = null))
            shared == null || !File(shared).isDirectory -> Planned.Refused("That session's branch is no longer checked out")
            else -> Planned.Ready(Plan(agent, newChatId(), resume = null, worktree = shared, context = null))
        }
    }

    private fun job(request: ChatRequest, projectName: String, prompt: String, plan: Plan): Job {
        val now = Instant.now(clock).toString()
        return Job(
            id = UUID.randomUUID().toString().replace("-", "").take(ID_CHARS),
            name = "${plan.agent.label} in chat",
            projectId = request.projectId, projectName = projectName,
            kind = if (request.reviewer != null) JobKind.PIPELINE else JobKind.RUN,
            agent = plan.agent, reviewer = request.reviewer, prompt = prompt,
            schedule = Schedule(at = now), resume = plan.resume, resumeOnLimitReset = true, publish = request.publish,
            chatId = plan.chatId, worktree = plan.worktree.takeIf { plan.resume == null }, context = plan.context, createdAt = now,
        )
    }

    private fun reviewProblem(agent: Agent, reviewer: Agent?): String? = when {
        reviewer == null -> null
        reviewer == agent -> "Choose a different agent to review"
        !workspace.commands.available(reviewer) -> "${reviewer.label} CLI is needed to review but is not installed"
        else -> null
    }

    private fun available(agent: Agent) = workspace.commands.available(agent) && choices.enabled(agent)

    private fun unavailable(agent: Agent) =
        if (!workspace.commands.available(agent)) "${agent.label} CLI is not installed on the server" else "${agent.label} is turned off in Settings"

    /** A chat message's job is only needed while its runs are in the history. */
    private fun pruneChatJobs() {
        val referenced = store.runs().mapNotNull { it.jobId }.toSet()
        store.jobs().filter { it.chatId != null && it.done && it.id !in referenced }.forEach { store.removeJob(it.id) }
    }

    companion object {
        private const val MAX_PROMPT = 20_000
        private const val ID_CHARS = 12
        private val RESUMABLE = setOf(RunStatus.SUCCEEDED, RunStatus.LIMITED)

        fun newChatId(): String = newRunId()
    }
}

/** Sessions as the runs record them: grouped by session, with each message and what came of it. */
private class ChatHistory(private val store: StateStore) {
    fun list(projectId: String): List<ChatView> {
        val runs = store.runs().filter { it.projectId == projectId && it.chatId != null }
        val byChat = runs.groupBy { checkNotNull(it.chatId) }
        val branches = byChat.mapValues { (_, chatRuns) -> latestBranch(chatRuns) }
        return byChat.map { (id, chatRuns) ->
            val branch = branches[id]
            view(id, chatRuns, sharesBranch = branch != null && branches.any { (other, b) -> other != id && b == branch })
        }.sortedByDescending { it.updatedAt }
    }

    fun runsOf(projectId: String, chatId: String) = store.runs().filter { it.projectId == projectId && it.chatId == chatId }

    private fun view(id: String, runs: List<Run>, sharesBranch: Boolean): ChatView {
        val sorted = runs.sortedBy { it.createdAt }
        val first = sorted.first()
        val worktree = sorted.lastOrNull { it.worktree != null }?.worktree
        val turns = sorted.groupBy { it.jobId ?: it.pipelineId ?: it.id }.values.map { turnRuns ->
            ChatTurn(message(turnRuns), turnRuns.first().createdAt, turnRuns)
        }
        return ChatView(
            id = id, projectId = first.projectId,
            title = turns.first().message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS) ?: "Session",
            agent = first.agent, branch = latestBranch(sorted),
            createdAt = first.createdAt, updatedAt = sorted.maxOf { it.finishedAt ?: it.startedAt ?: it.createdAt },
            busy = sorted.any { !it.status.finished },
            open = worktree == null || File(worktree).isDirectory,
            sharesBranch = sharesBranch, turns = turns,
        )
    }

    private fun latestBranch(runs: List<Run>): String? = runs.sortedByDescending { it.createdAt }.firstNotNullOfOrNull { it.branch }

    /** What was asked before, for an agent that starts afresh in a session. */
    fun context(runs: List<Run>): String {
        val messages = runs.sortedBy { it.createdAt }.groupBy { it.jobId ?: it.id }.values
            .map { message(it).trim().take(CONTEXT_CHARS) }
        return "This continues earlier work in this session. What was asked before, oldest first:\n" +
            messages.joinToString("\n") { "- " + it.replace("\n", " ") } + "\n\nThe new request:"
    }

    /** The message as the person typed it: a chat job's prompt, without any context added for the agent. */
    private fun message(runs: List<Run>): String = runs.first().jobId?.let(store::job)?.prompt ?: runs.first().prompt

    private companion object {
        const val TITLE_CHARS = 80
        const val CONTEXT_CHARS = 300
    }
}

/**
 * Runs from before sessions existed join one: each pipeline, or each branch, becomes a session,
 * and its one-off jobs (chat messages) are marked as part of it, so they leave the Schedule list.
 */
fun StateStore.adoptIntoSessions() = synchronized(this) {
    val legacy = runs().filter { it.chatId == null }
    if (legacy.isEmpty()) return@synchronized
    val ids = legacy.map { it.pipelineId ?: it.branch ?: it.id }.distinct().associateWith { ChatService.newChatId() }
    legacy.forEach { run ->
        val chatId = ids.getValue(run.pipelineId ?: run.branch ?: run.id)
        putRun(run.copy(chatId = chatId))
        run.jobId?.let(::job)?.takeIf { it.chatId == null && it.schedule.at != null }?.let { putJob(it.copy(chatId = chatId)) }
    }
}
