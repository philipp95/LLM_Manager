package com.llmmanager.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes sessions as the server sends them`() {
        val body = """
            {"chats":[{"id":"9b4f957d1be8","projectId":"p","title":"Add a greeting","agent":"claude","branch":"run/demo-1",
              "createdAt":"2026-11-12T22:10:58Z","updatedAt":"2026-11-12T22:11:40Z","busy":true,"open":true,"sharesBranch":true,
              "turns":[{"message":"Add a greeting","createdAt":"2026-11-12T22:10:58Z","runs":[
                  {"id":"r1","jobId":"j1","projectId":"p","projectName":"demo","agent":"claude","stage":"work","status":"succeeded",
                   "prompt":"Add a greeting","chatId":"9b4f957d1be8","createdAt":"2026-11-12T22:10:58Z","sessionId":"b8ad3120"}]},
                {"message":"Now add a test","createdAt":"2026-11-12T22:11:30Z","runs":[
                  {"id":"r2","agent":"claude","stage":"resume","status":"succeeded","createdAt":"2026-11-12T22:11:30Z"},
                  {"id":"r3","agent":"codex","stage":"review","status":"running","createdAt":"2026-11-12T22:11:40Z"}]}]}]}
        """.trimIndent()

        val session = json.decodeFromString(ChatsResponse.serializer(), body).chats.single()

        assertEquals("run/demo-1", session.branch)
        assertEquals(listOf("Add a greeting", "Now add a test"), session.turns.map { it.message })
        assertEquals("9b4f957d1be8", session.turns.first().runs.single().chatId)
        assertEquals("r3", session.state?.id)
    }

    @Test
    fun `a session's state is a run still working, else its last run`() {
        fun run(id: String, status: String) = Run(id = id, agent = "claude", stage = "work", status = status, createdAt = "2026-11-12T10:00:00Z")
        val done = ChatView("c", turns = listOf(ChatTurn("x", runs = listOf(run("a", "succeeded"), run("b", "failed")))))
        val working = ChatView("c", turns = listOf(ChatTurn("x", runs = listOf(run("a", "running"), run("b", "queued")))))

        assertEquals("b", done.state?.id)
        assertEquals("a", working.state?.id)
        assertNull(ChatView("empty").state)
    }

    @Test
    fun `a message names its session only when it continues one`() {
        val compact = Json { explicitNulls = false }
        val first = compact.encodeToString(ChatRequest.serializer(), ChatRequest("p", "Add a greeting", "claude", publish = true))
        val next = compact.encodeToString(ChatRequest.serializer(), ChatRequest("p", "And a test", "claude", chatId = "c1"))

        assertEquals("""{"projectId":"p","prompt":"Add a greeting","agent":"claude","publish":true}""", first)
        assertEquals("""{"projectId":"p","prompt":"And a test","agent":"claude","chatId":"c1"}""", next)
    }
}
