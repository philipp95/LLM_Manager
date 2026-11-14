package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** Repository sessions stay in their repository; the Server chat is the one place that may change the machine. */
@DisabledOnOs(OS.WINDOWS, disabledReason = "runs bash wrappers and shell-script fake agents, as the Linux server does")
class ServerChatTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val clock = MutableClock(Instant.parse("2026-11-14T12:00:00Z"))

    private class Fixture(val chats: ChatService, val scheduler: Scheduler, val store: StateStore, val projectId: String, val home: File) {
        fun settle() = repeat(4) { scheduler.tick() }
    }

    /** A sandbox that would refuse or wrap every run, to show the Server chat goes around it. */
    private fun refusingSandbox() =
        AgentSandbox.detect(
            AgentSandbox.Mode.ON, AgentSandbox.Means({ error("no bubblewrap in this test") }, candidates = emptyList()),
            emptyMap(), File(root, "sandbox-check"),
        )

    private fun fixture(serverChat: Boolean = true, sandbox: AgentSandbox? = null): Fixture {
        val repo = File(root, "repos/app").also { it.mkdirs() }
        gitOutput(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("app\n")
        gitOutput(repo, "add", "README.md")
        gitOutput(repo, "-c", "user.name=Person", "-c", "user.email=person@example.invalid", "commit", "-m", "initial")
        // The fake agents log their prompts one folder above where they run.
        val home = File(root, "machine/home").also { it.mkdirs() }
        val sessions = ImmediateSessions()
        val scanner = ProjectScanner(listOf("${root.path}/repos/*"), sessions = sessions)
        val store = StateStore(File(root, "state"))
        val agents = FakeAgents(root)
        val commands = AgentCommands(agents.claude(), agents.codex(), gemini = agents.gemini())
        val chat = if (serverChat) ServerChat(home, listOf("The server listens on port 8080.")) else null
        val workspace = if (sandbox == null) Workspace(scanner, sessions, commands, serverChat = chat)
        else Workspace(scanner, sessions, commands, sandbox = sandbox, serverChat = chat)
        val scheduler = Scheduler(store, RunEngine(store, workspace, File(root, "runs"), clock), sessions, clock = clock)
        return Fixture(ChatService(store, scheduler, workspace, clock = clock), scheduler, store, scanner.scan().single().id, home)
    }

    private fun ChatService.Outcome.chat() = (this as ChatService.Outcome.Ok).chat

    private fun ChatService.Outcome.refusal() = (this as ChatService.Outcome.Invalid).reason

    @Test
    fun `a repository session is told to stay in its repository, whatever the message says`() {
        val f = fixture()

        val chat = f.chats.send(ChatRequest(f.projectId, "Also fix the other repository", Agent.CLAUDE)).chat()
        f.settle()

        val run = f.store.runs().single { it.chatId == chat.id }
        val prompt = File(f.store.runDir(run.id), "prompt.txt").readText()
        assertTrue(prompt.startsWith("Also fix the other repository"))
        assertTrue(prompt.contains("Change files only inside the current working directory"))
        assertTrue(prompt.contains("Do not change, create or delete anything in another repository"))
        assertTrue(prompt.contains("Server chat"), "it says where such a request belongs")
        assertEquals("Also fix the other repository", f.chats.list(f.projectId).single().turns.single().message, "the rules are not shown as the message")
    }

    @Test
    fun `the Server chat works in the server's home, on no branch, and nothing is committed`() {
        val f = fixture(sandbox = refusingSandbox())

        val chat = f.chats.send(ChatRequest(ServerChat.PROJECT_ID, "Tidy the logs", Agent.CLAUDE, publish = true)).chat()
        f.settle()

        val run = f.store.runs().single()
        assertEquals(RunStatus.SUCCEEDED, run.status, run.error)
        assertEquals(f.home.path, run.worktree)
        assertNull(run.branch)
        assertNull(run.resultCommit)
        assertNull(run.publishNote, "there is no branch to publish")
        assertTrue(File(f.home, "claude-work.txt").isFile, "the agent worked in the home directory")
        val prompt = File(f.store.runDir(run.id), "prompt.txt").readText()
        assertTrue(prompt.contains("This is the Server chat") && prompt.contains("port 8080"))
        assertFalse(prompt.contains("Change files only inside the current working directory"))
        assertEquals(chat.id, f.chats.list(ServerChat.PROJECT_ID).single().id)

        f.chats.send(ChatRequest(ServerChat.PROJECT_ID, "And the caches", chatId = chat.id)).chat()
        f.settle()
        val followUp = f.store.runs().single { it.id != run.id }
        assertEquals(Stage.RESUME, followUp.stage)
        assertEquals(RunStatus.SUCCEEDED, followUp.status, followUp.error)
        assertEquals(run.sessionId, followUp.sessionId)
    }

    @Test
    fun `the Server chat has no review and no shared branches, and can be turned off`() {
        val f = fixture()
        val session = f.chats.send(ChatRequest(f.projectId, "Start", Agent.CLAUDE)).chat()

        val reviewed = f.chats.send(ChatRequest(ServerChat.PROJECT_ID, "Change it", Agent.CLAUDE, reviewer = Agent.CODEX))
        val shared = f.chats.send(ChatRequest(ServerChat.PROJECT_ID, "Change it", Agent.CLAUDE, sameBranchAs = session.id))

        assertTrue(reviewed.refusal().contains("no branches and no review"))
        assertTrue(shared.refusal().contains("no branches and no review"))
        File(root, "second").mkdirs()
        val off = ServerChatTest().also { it.directory = File(root, "second").toPath() }.fixture(serverChat = false)
        assertTrue(off.chats.send(ChatRequest(ServerChat.PROJECT_ID, "Change it", Agent.CLAUDE)).refusal().contains("turned off"))
    }

    @Test
    fun `only the Server chat gets unrestricted commands`() {
        val contained = AgentCommands("claude", "codex", gemini = "gemini").apply { codexContained = { true } }
        val plain = AgentCommands("claude", "codex", gemini = "gemini")

        assertTrue(plain.work(Agent.CODEX, "").containsAll(listOf("--sandbox", "workspace-write")))
        assertTrue(contained.work(Agent.CODEX, "").contains("danger-full-access"), "the server confines it instead")
        assertFalse(contained.work(Agent.CODEX, "").contains("--skip-git-repo-check"))
        assertTrue(plain.work(Agent.CODEX, "", server = true).containsAll(listOf("danger-full-access", "--skip-git-repo-check")))
        assertTrue(plain.resume(Agent.CODEX, "t1", server = true).containsAll(listOf("danger-full-access", "resume", "t1")))
        assertTrue(plain.work(Agent.CLAUDE, "s", server = true).takeLast(1) == listOf("Bash"))
        assertFalse(plain.work(Agent.CLAUDE, "s").takeLast(1) == listOf("Bash"), "repository runs get a list of commands")
        assertTrue(plain.work(Agent.GEMINI, "s", server = true).containsAll(listOf("--approval-mode", "yolo")))
        assertTrue(plain.work(Agent.GEMINI, "s").containsAll(listOf("--approval-mode", "auto_edit")))
    }
}
