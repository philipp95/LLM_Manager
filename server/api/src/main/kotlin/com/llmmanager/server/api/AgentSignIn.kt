package com.llmmanager.server.api

import java.io.File
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** A sign-in in progress, as the pop-up shows it. */
@Serializable
data class SignInView(
    val agent: Agent,
    /** idle, starting, waiting, checking, done or failed. */
    val state: String,
    /** The sign-in page to open; only ever on the agent's own sign-in hosts. */
    val url: String? = null,
    /** A code to enter on that page (Codex). */
    val userCode: String? = null,
    /** The CLI waits for the code the page shows after signing in (Claude, Gemini). */
    val needsCode: Boolean = false,
    val message: String? = null,
    /** The last lines of the sign-in screen, for when something unexpected happens. */
    val screen: List<String> = emptyList(),
    val expiresAt: String? = null,
)

/**
 * Signs the agent CLIs in from the phone. Each CLI's own sign-in runs in a terminal on the
 * server ([Terminals]); its link, the code it shows, and its prompt for the code the sign-in
 * page hands back are relayed to the pop-up, and the code typed there goes back to the CLI.
 * The credentials end up exactly where the CLI's own sign-in puts them.
 */
class AgentSignIn(
    private val terminals: Terminals,
    private val accounts: AgentAccounts,
    private val binary: (Agent) -> String?,
    /** An empty folder to run in, so no project's settings or `.env` apply. */
    private val workDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Attempt(val agent: Agent, val startedAt: Instant, val expiresAt: Instant) {
        var result: SignInView? = null
    }

    private val attempts = mutableMapOf<Agent, Attempt>()
    private val log = LoggerFactory.getLogger(AgentSignIn::class.java)
    private val sweeper = Executors.newSingleThreadScheduledExecutor { Thread(it, "sign-in-sweeper").apply { isDaemon = true } }

    /** Starts [agent]'s sign-in, or returns the one already waiting. With [restart], always starts afresh. */
    @Synchronized
    fun start(agent: Agent, restart: Boolean = false): SignInView {
        val running = attempts[agent]?.takeIf { it.result == null }
        if (running != null && !restart) return view(agent)
        running?.let { finish(it, failed(it, "Started again")) }
        val problem = launch(agent)
        return if (problem != null) SignInView(agent, FAILED, message = problem) else view(agent)
    }

    /** Opens the CLI's sign-in in a fresh terminal; returns why it could not, or null. */
    private fun launch(agent: Agent): String? {
        val cli = binary(agent) ?: return "${agent.label} CLI is not installed on the server"
        val flow = SignInFlow.of(agent)
        val problem = (if (agent == Agent.GEMINI) accounts.gemini.select("oauth-personal") else null) ?: run {
            SecureFiles.privateDir(workDir)
            terminals.close(name(agent))
            "The server could not start the sign-in".takeUnless { terminals.open(name(agent), workDir, flow.command(cli)) }
        }
        if (problem == null) {
            val now = Instant.now(clock)
            attempts[agent] = Attempt(agent, now, now.plus(flow.timeout))
            log.info("{} sign-in started", agent.label)
            sweeper.schedule({ view(agent) }, flow.timeout.toMillis() + SWEEP_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        return problem
    }

    /** What the sign-in shows now; finishes it once the CLI is signed in, gives up, or time runs out. */
    @Synchronized
    fun view(agent: Agent): SignInView {
        val attempt = attempts[agent] ?: return SignInView(agent, IDLE)
        attempt.result?.let { return it }
        val flow = SignInFlow.of(agent)
        val screen = terminals.screen(name(agent))
        val exit = screen?.let { EXIT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val signedIn = flow.finishedWithoutExit && accounts.gemini.hasGoogleSignIn(attempt.startedAt.toEpochMilli() - CLOCK_SLACK_MS)
        return when {
            signedIn || exit == 0 -> finish(attempt, succeeded(attempt, screen))
            screen == null -> finish(attempt, failed(attempt, "The sign-in stopped"))
            exit != null -> finish(attempt, failed(attempt, "The sign-in did not finish", screen))
            Instant.now(clock).isAfter(attempt.expiresAt) -> finish(attempt, failed(attempt, "The sign-in timed out. Start again for a new link.", screen))
            else -> flow.read(agent, screen, attempt.expiresAt)
        }
    }

    /** Types the code the sign-in page showed into the waiting CLI. */
    @Synchronized
    fun submit(agent: Agent, code: String): SignInView {
        val current = view(agent)
        val trimmed = code.trim()
        return when {
            current.state != WAITING || !current.needsCode -> current.copy(message = "The sign-in is not waiting for a code")
            !CODE.matches(trimmed) -> current.copy(message = "That does not look like a sign-in code; copy it from the page again")
            !terminals.type(name(agent), trimmed) -> current.copy(message = "The code could not be passed on; start again")
            else -> current.copy(state = CHECKING, needsCode = false, message = "Checking the code…")
        }
    }

    @Synchronized
    fun cancel(agent: Agent): SignInView {
        attempts[agent]?.takeIf { it.result == null }?.let { finish(it, failed(it, "Cancelled")) }
        attempts.remove(agent)
        return SignInView(agent, IDLE)
    }

    /** A sign-in still in progress, for the settings page; finished ones are left out. */
    @Synchronized
    fun active(agent: Agent): SignInView? =
        attempts[agent]?.takeIf { it.result == null }?.let { view(agent) }?.takeIf { it.state != DONE && it.state != FAILED }

    private fun succeeded(attempt: Attempt, screen: String?): SignInView {
        accounts.invalidate()
        val status = accounts.status(attempt.agent)
        return if (status.signedIn) {
            SignInView(attempt.agent, DONE, message = "Signed in" + (status.account?.let { " as $it" } ?: status.method?.let { " with $it" } ?: ""))
        } else {
            failed(attempt, "${attempt.agent.label} finished, but is not signed in", screen)
        }
    }

    private fun failed(attempt: Attempt, message: String, screen: String? = null) =
        SignInView(attempt.agent, FAILED, message = message, screen = screen?.let { SignInFlow.lines(it) }.orEmpty())

    private fun finish(attempt: Attempt, result: SignInView): SignInView {
        attempt.result = result
        accounts.invalidate()
        val agent = attempt.agent
        if (result.state == DONE && agent == Agent.GEMINI) {
            // Gemini goes on into its chat once signed in; give it a moment to finish writing its files first.
            val close = Runnable { synchronized(this) { if (attempts[agent] === attempt) terminals.close(name(agent)) } }
            sweeper.schedule(close, CLOSE_DELAY_MS, TimeUnit.MILLISECONDS)
        } else {
            terminals.close(name(agent))
        }
        log.info("{} sign-in {}: {}", agent.label, result.state, result.message)
        return result
    }

    companion object {
        const val IDLE = "idle"
        const val STARTING = "starting"
        const val WAITING = "waiting"
        const val CHECKING = "checking"
        const val DONE = "done"
        const val FAILED = "failed"

        /** OAuth codes as the sign-in pages show them: no spaces, no control characters. */
        private val CODE = Regex("[A-Za-z0-9#_.~+/=:%&?-]{4,2048}")
        val EXIT = Regex("""\[llm-manager sign-in ended] (\d+)""")
        private const val SWEEP_GRACE_MS = 5_000L
        private const val CLOSE_DELAY_MS = 3_000L
        private const val CLOCK_SLACK_MS = 2_000L

        fun name(agent: Agent) = "sign-in-${agent.name.lowercase()}"
    }
}

/** How one CLI signs in: its command, where its link may point, and how its screen reads. */
internal class SignInFlow(
    private val arguments: List<String>,
    private val environment: List<String>,
    private val hosts: Set<String>,
    /** The prompt the CLI shows while it waits for the code from the sign-in page. */
    private val codePrompt: String?,
    /** Gemini goes on into its chat after signing in, so success shows in its files, not as an exit. */
    val finishedWithoutExit: Boolean,
    val timeout: Duration,
) {
    /**
     * The CLI under bash, which prints a marker with the exit code when it ends and then keeps
     * the terminal open, so the last screen can still be read.
     */
    fun command(cli: String): List<String> =
        listOf("bash", "-c", "\"\$@\"; code=\$?; printf '\\n%s %s\\n' \"\$0\" \"\$code\"; exec sleep 600", "[llm-manager sign-in ended]", "env") +
            environment + cli + arguments

    fun read(agent: Agent, screen: String, expiresAt: Instant): SignInView {
        val url = LINK.findAll(screen).map { it.value.trimEnd('.', ',', ')', ']') }.lastOrNull(::allowed)
        val userCode = screen.lines().firstNotNullOfOrNull { USER_CODE.matchEntire(it.trim())?.value }
        val waitingForCode = codePrompt != null && screen.lines().lastOrNull { it.isNotBlank() }?.trim()?.endsWith(codePrompt) == true
        // The prompt with something typed after it: the code is with the CLI.
        val answered = codePrompt != null && screen.lines().any { it.contains(codePrompt) && it.substringAfter(codePrompt).isNotBlank() }
        val state = when {
            url == null -> AgentSignIn.STARTING
            !waitingForCode && answered -> AgentSignIn.CHECKING
            else -> AgentSignIn.WAITING
        }
        // Prompt lines are left out: once answered, they hold the code.
        val shown = lines(screen).filterNot { codePrompt != null && it.contains(codePrompt) }
        return SignInView(agent, state, url = url, userCode = userCode, needsCode = waitingForCode, screen = shown, expiresAt = expiresAt.toString())
    }

    private fun allowed(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host in hosts
    }

    companion object {
        private val LINK = Regex("""https://[^\s"'<>\\]+""")
        private val USER_CODE = Regex("[A-Z0-9]{4,5}-[A-Z0-9]{4,6}")
        private val CONTROL = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|[\u0000-\u0008\u000B-\u001F\u007F]""")
        private const val MAX_LINES = 15
        private const val MAX_LINE = 400

        private val claude = SignInFlow(
            listOf("auth", "login", "--claudeai"),
            // No browser on the server; the link goes to the phone instead.
            environment = listOf("-u", "DISPLAY", "-u", "WAYLAND_DISPLAY", "BROWSER=true"),
            hosts = setOf("claude.ai", "claude.com", "platform.claude.com", "console.anthropic.com"),
            codePrompt = "Paste code here if prompted >",
            finishedWithoutExit = false,
            timeout = Duration.ofMinutes(10),
        )
        private val codex = SignInFlow(
            listOf("login", "--device-auth"), environment = emptyList(), hosts = setOf("auth.openai.com"),
            codePrompt = null, finishedWithoutExit = false, timeout = Duration.ofMinutes(15),
        )
        private val gemini = SignInFlow(
            emptyList(), environment = listOf("NO_BROWSER=true"), hosts = setOf("accounts.google.com"),
            codePrompt = "Enter the authorization code:",
            // Gemini itself gives up after five minutes.
            finishedWithoutExit = true, timeout = Duration.ofMinutes(5),
        )

        fun of(agent: Agent): SignInFlow = when (agent) {
            Agent.CLAUDE -> claude
            Agent.CODEX -> codex
            Agent.GEMINI -> gemini
        }

        /** The screen's last lines as plain text: no escape sequences, no blank lines, no exit marker. */
        fun lines(screen: String): List<String> = screen.lines()
            .map { it.replace(CONTROL, "").trimEnd().take(MAX_LINE) }
            .filter { it.isNotBlank() && !AgentSignIn.EXIT.containsMatchIn(it) }
            .takeLast(MAX_LINES)
    }
}
