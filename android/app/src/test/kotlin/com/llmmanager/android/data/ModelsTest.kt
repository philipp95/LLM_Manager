package com.llmmanager.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the server response, ignoring fields this app does not know`() {
        val body = """
            {"generatedAt":"2026-11-09T13:00:00Z","codexAvailable":true,"futureField":{"x":1},
             "projects":[{"id":"abc","name":"acme/app","kind":"checkout","path":"/r","originUrl":null,"gitHubUrl":"https://github.com/acme/app",
               "defaultBranch":"main","commitsPerDay":[0,2,1],
               "worktrees":[{"path":"/r","branch":"main","head":"aaaa","lastCommitAt":"2026-11-09T12:00:00+01:00","changedFiles":3,
                 "agentRun":false,"main":true,"missing":false,"codex":null,"startedAt":null},
                {"path":"/w","branch":"codex/app-1","head":null,"lastCommitAt":null,"changedFiles":null,"agentRun":false,"main":false,"missing":true,
                 "codex":{"name":"codex-app-1","running":true,"attachCommand":"tmux attach -t codex-app-1"}}]}],
             "analytics":{"days":["2026-11-08","2026-11-09"],"commitsPerDay":[1,2],"codexStartsPerDay":[0,1],"agentRunsPerDay":[0,0],
               "busiest":[{"id":"abc","name":"acme/app","commits":3}]},
             "androidApp":null}
        """.trimIndent()

        val response = json.decodeFromString(ProjectsResponse.serializer(), body)

        assertTrue(response.codexAvailable)
        val project = response.projects.single()
        assertEquals(listOf(0, 2, 1), project.commitsPerDay)
        assertEquals(3, project.worktrees[0].changedFiles)
        assertNull(project.worktrees[1].changedFiles)
        assertEquals("tmux attach -t codex-app-1", project.worktrees[1].codex?.attachCommand)
        assertEquals(3, response.analytics?.busiest?.single()?.commits)
    }

    @Test
    fun `decodes a repository's description and pull request insights`() {
        val body = """
            {"generatedAt":"2026-11-11T13:00:00Z","projects":[{"id":"p","name":"acme/app","description":"Does one thing well.",
              "forge":"github","webUrl":"https://github.com/acme/app","worktrees":[],
              "insights":{"latestMerged":{"number":12,"title":"Add retry","url":"https://github.com/acme/app/pull/12","state":"merged",
                  "author":"philipp95","branch":"run/app-1","mergedAt":"2026-11-10T08:00:00Z","agent":"claude","ci":"success"},
                "mergedLastWeek":3,"openCount":1,"recent":[],"fetchedAt":"2026-11-11T12:59:00Z","error":null,"newField":true}}]}
        """.trimIndent()

        val project = json.decodeFromString(ProjectsResponse.serializer(), body).projects.single()

        assertEquals("Does one thing well.", project.description)
        val latest = project.insights?.latestMerged
        assertEquals(12L, latest?.number)
        assertEquals("claude" to "success", latest?.agent to latest?.ci)
        assertEquals(3, project.insights?.mergedLastWeek)
    }

    @Test
    fun `a chat message asks the server to publish, and an ordinary job does not say so`() {
        val encoder = Json { ignoreUnknownKeys = true }
        val chat = encoder.encodeToString(JobRequest.serializer(), JobRequest("p", "run", "gemini", "Add a test", publish = true))
        val plain = encoder.encodeToString(JobRequest.serializer(), JobRequest("p", "run", "gemini", "Add a test"))
        assertTrue(chat, chat.contains("\"publish\":true"))
        assertTrue(plain, !plain.contains("publish"))
    }

    @Test
    fun `an older server without analytics still decodes`() {
        val response = json.decodeFromString(ProjectsResponse.serializer(), """{"generatedAt":"2026-11-09T13:00:00Z","projects":[]}""")
        assertNull(response.analytics)
        assertEquals(false, response.codexAvailable)
    }
}
