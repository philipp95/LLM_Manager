package com.llmmanager.server.api

/**
 * Command lines for unattended agent runs. The prompt always arrives on stdin, so
 * no prompt text is ever parsed by a shell or mistaken for a flag.
 *
 * Permissions are pinned here, never taken from the agents' own config:
 * - work: Claude may edit files and run only [claudeAllowedTools]; anything else is
 *   denied (print mode never prompts). Codex runs in its workspace-write sandbox,
 *   which confines writes to the worktree and has no network.
 * - review: read-only for both.
 */
class AgentCommands(
    private val claude: String?,
    private val codex: String?,
    private val claudeAllowedTools: List<String> = DEFAULT_CLAUDE_TOOLS,
) {
    fun available(agent: Agent): Boolean = binary(agent) != null

    /** A new session; Claude's id is chosen up front so it is known before the run ends. */
    fun work(agent: Agent, newSessionId: String): List<String> = when (agent) {
        Agent.CLAUDE -> claudeBase() + listOf("--session-id", newSessionId, "--permission-mode", "acceptEdits", "--allowedTools") + claudeAllowedTools
        Agent.CODEX -> codexExec() + "-"
    }

    fun resume(agent: Agent, sessionId: String): List<String> = when (agent) {
        Agent.CLAUDE -> claudeBase() + listOf("--resume", sessionId, "--permission-mode", "acceptEdits", "--allowedTools") + claudeAllowedTools
        Agent.CODEX -> codexExec() + listOf("resume", sessionId, "-")
    }

    /** Reviews [commit] without changing anything; the instructions arrive on stdin. */
    fun review(agent: Agent, commit: String): List<String> = when (agent) {
        Agent.CLAUDE -> claudeBase() + listOf("--permission-mode", "default", "--allowedTools") + CLAUDE_REVIEW_TOOLS
        Agent.CODEX -> listOf(required(Agent.CODEX), "review", "-c", "sandbox_mode=\"read-only\"", "--commit", commit, "-")
    }

    private fun claudeBase() = listOf(required(Agent.CLAUDE), "-p", "--output-format", "stream-json", "--verbose")

    private fun codexExec() = listOf(required(Agent.CODEX), "exec", "--json", "--sandbox", "workspace-write")

    private fun binary(agent: Agent) = if (agent == Agent.CLAUDE) claude else codex

    private fun required(agent: Agent) = checkNotNull(binary(agent)) { "${agent.label} CLI is not installed" }

    companion object {
        /** Build, test and read-only git commands an unattended Claude run may use. */
        val DEFAULT_CLAUDE_TOOLS = listOf(
            "Read", "Glob", "Grep", "Edit", "Write",
            "Bash(git status:*)", "Bash(git diff:*)", "Bash(git log:*)", "Bash(git show:*)",
            "Bash(./gradlew:*)", "Bash(bash ./gradlew:*)",
            "Bash(npm test:*)", "Bash(npm run test:*)", "Bash(npm run build:*)", "Bash(npm run lint:*)",
            "Bash(pytest:*)", "Bash(python -m pytest:*)", "Bash(python3 -m pytest:*)",
            "Bash(cargo test:*)", "Bash(cargo build:*)", "Bash(go test:*)", "Bash(go build:*)", "Bash(make test:*)",
        )
        private val CLAUDE_REVIEW_TOOLS = listOf("Read", "Glob", "Grep", "Bash(git show:*)", "Bash(git diff:*)", "Bash(git log:*)")

        fun reviewPrompt(builder: Agent, base: String, commit: String, task: String): String = """
            |You are reviewing work that ${builder.label} did on this task:
            |
            |$task
            |
            |The change is commit $commit (compare with its parent $base: `git diff $base $commit`).
            |Review it for correctness bugs, missing tests, security problems and violations of AGENTS.md.
            |Do not edit any files. Reply with a numbered list of findings, most severe first; for each give
            |the file and line, what is wrong, and a concrete fix. If nothing needs changing, reply exactly: No findings.
            """.trimMargin()

        fun fixPrompt(reviewer: Agent, review: String): String = """
            |${reviewer.label} reviewed your change and wrote:
            |
            |$review
            |
            |Address the findings that are valid. For any you reject, say briefly why. Run the relevant tests.
            """.trimMargin()

        const val CONTINUE_PROMPT = "You were stopped by a usage limit. Continue the task where you left off."
    }
}
