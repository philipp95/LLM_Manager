package com.llmmanager.server.api

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class WorkItemsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `cached work items survive a failed refresh with a visible error`() {
        var fail = false
        val service = WorkItemsService(WorkItemSource {
            if (fail) Result.failure(IOException("offline"))
            else Result.success(RepoWorkItems(items = listOf(RepoWorkItem(42, "Fix login", "https://github.com/a/b/issues/42", "open"))))
        }, Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC))
        val forge = Forge.GitHub("a", "b")
        service.refresh("p", forge)
        fail = true
        service.refresh("p", forge)
        val result = service.annotate(listOf(project())).single().workItems!!
        assertEquals(42, result.items.single().number)
        assertEquals("offline", result.error)
        assertEquals("2026-11-14T12:00:00Z", result.fetchedAt)
        assertNull(service.annotate(listOf(project().copy(originUrl = null))).single().workItems)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "fixture uses POSIX token permissions")
    fun `azure only includes artifact links for this repository and never follows redirects`() {
        val requests = mutableListOf<String>()
        var redirect = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            val path = call.requestURI.toString()
            requests += path
            assertTrue(path.contains("api-version=7.1"))
            assertEquals("Basic OnRlc3QtdG9rZW4=", call.requestHeaders.getFirst("Authorization"))
            val body = when {
                redirect -> "{}"
                path.contains("/git/repositories/") -> """{"id":"repo-id","project":{"id":"project-id"}}"""
                path.contains("/wiql") -> {
                    assertTrue(call.requestBody.bufferedReader().readText().contains("@project"))
                    """{"workItems":[{"id":1},{"id":2},{"id":3}]}"""
                }
                else -> """{"value":[
                    {"id":1,"fields":{"System.Title":"Linked task","System.State":"Active","System.WorkItemType":"Task","System.AssignedTo":{"displayName":"Alex"}},
                     "relations":[{"rel":"ArtifactLink","url":"vstfs:///Git/Ref/project-id%2Frepo-id%2FGBfeature%2Flogin"}]},
                    {"id":2,"fields":{"System.Title":"Other repo"},"relations":[{"rel":"ArtifactLink","url":"vstfs:///Git/Commit/project-id%2Fother-repo%2Fabcd"}]},
                    {"id":3,"fields":{"System.Title":"No repository link"}}
                ]}"""
            }
            call.responseHeaders.add("Content-Type", "application/json")
            if (redirect) call.responseHeaders.add("Location", "/do-not-follow")
            val bytes = body.toByteArray()
            call.sendResponseHeaders(if (redirect) 302 else 200, bytes.size.toLong())
            call.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tokens = directory.resolve("tokens").toFile().apply { mkdirs() }
            val token = File(tokens, ForgeCredentials.AZURE_FILE).apply { writeText("test-token") }
            Files.setPosixFilePermissions(token.toPath(), PosixFilePermissions.fromString("rw-------"))
            val source = WorkItemSources(ForgeCredentials(tokens, null), "http://127.0.0.1:${server.address.port}")
            val forge = Forge.AzureDevOps("org", "Project name", "repo")
            val result = source.fetch(forge).getOrThrow()
            assertEquals(listOf(1L), result.items.map { it.number })
            assertEquals("Alex", result.items.single().assignee)
            assertEquals("Task", result.items.single().type)
            assertEquals("https://dev.azure.com/org/Project%20name/_workitems/edit/1", result.items.single().url)
            assertEquals(3, requests.size)
            redirect = true
            assertTrue(source.fetch(forge).isFailure)
            assertEquals(4, requests.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "fake gh is a shell script")
    fun `github reads open issues through gh with labels and assignees`() {
        val gh = directory.resolve("gh").toFile().apply {
            val response = """[{"number":7,"title":"Bug","url":"https://github.com/a/b/issues/7","state":"OPEN",
                "labels":[{"name":"bug"}],"assignees":[{"login":"alex"}],"updatedAt":"2026-11-14T12:00:00Z"}]"""
            writeText("#!/bin/sh\ncat <<'JSON'\n$response\nJSON\n")
            setExecutable(true)
        }
        val result = WorkItemSources(ForgeCredentials(directory.toFile(), gh.path)).fetch(Forge.GitHub("a", "b")).getOrThrow()
        assertEquals(listOf("bug"), result.items.single().labels)
        assertEquals("alex", result.items.single().assignee)
        assertEquals("open", result.items.single().state)
    }

    private fun project() = Project("p", "a/b", kind = Project.Kind.CHECKOUT, path = "/repo", originUrl = "https://github.com/a/b.git",
        gitHubUrl = "https://github.com/a/b", defaultBranch = "main", worktrees = emptyList())
}
