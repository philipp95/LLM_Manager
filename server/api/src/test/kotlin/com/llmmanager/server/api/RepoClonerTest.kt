package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RepoClonerTest {
    @TempDir
    lateinit var directory: Path

    private val root: File get() = directory.toFile()
    private val clones: File get() = File(root, "repos")

    /** A repository to clone from, standing in for the forge. */
    private fun upstream(name: String = "upstream"): File {
        val repo = File(root, name).also { it.mkdirs() }
        gitOutput(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("demo\n")
        gitOutput(repo, "add", "README.md")
        gitOutput(repo, "-c", "user.name=Person", "-c", "user.email=person@example.invalid", "commit", "-m", "initial")
        return repo
    }

    private fun cloner(source: (Forge) -> String, added: MutableList<String> = mutableListOf()): RepoCloner {
        val scanner = ProjectScanner(listOf("${clones.path}/*"))
        val credentials = ForgeCredentials(File(root, "tokens").apply { mkdirs() }, gh = null)
        return RepoCloner(clones, credentials, scanner, onAdded = { added += "added" }, RepoCloner.Means(source = source, background = { it() }))
    }

    private fun RepoCloner.Outcome.status() = (this as RepoCloner.Outcome.Started).status

    private fun RepoCloner.Outcome.reason() = (this as RepoCloner.Outcome.Invalid).reason

    @Test
    fun `web and clone addresses of the three hosts are understood, anything else is not`() {
        assertEquals("acme/demo", RepoCloner.parse(" github.com/acme/demo/ ")?.name)
        assertEquals("acme/demo", RepoCloner.parse("https://github.com/acme/demo.git")?.name)
        assertEquals("acme/demo", RepoCloner.parse("git@github.com:acme/demo.git")?.name)
        assertEquals("https://gitlab.com/group/sub/app.git", RepoCloner.parse("https://gitlab.com/group/sub/app")?.let(RepoCloner::httpsUrl))
        val azure = assertNotNull(RepoCloner.parse("https://dev.azure.com/org/Project/_git/Repo"))
        assertEquals("https://dev.azure.com/org/Project/_git/Repo", RepoCloner.httpsUrl(azure))
        assertEquals("https://github.com/acme/demo.git", RepoCloner.parse("https://user:secret@github.com/acme/demo")?.let(RepoCloner::httpsUrl))

        for (bad in listOf("", "demo", "https://example.com/acme/demo", "/etc/passwd", "file:///srv/repo", "--upload-pack=x", "github.com/acme/de mo")) {
            assertNull(RepoCloner.parse(bad), bad)
        }
    }

    @Test
    fun `a repository is cloned in the background and then found as a project`() {
        val upstream = upstream()
        val added = mutableListOf<String>()
        val cloner = cloner({ upstream.path }, added)

        val started = cloner.start(CloneRequest("https://github.com/acme/demo")).status()

        assertEquals(RepoCloner.CLONING, started.state)
        assertEquals("https://github.com/acme/demo", started.url)
        val done = cloner.list().single()
        assertEquals(RepoCloner.DONE, done.state, done.message)
        assertTrue(File(clones, "demo/README.md").isFile)
        assertEquals(ProjectScanner(listOf("${clones.path}/*")).scan().single().id, done.projectId)
        assertEquals(listOf("added"), added)
        assertTrue(clones.list().orEmpty().none { it.startsWith(".") }, "nothing half-cloned is left")
    }

    @Test
    fun `a taken folder name gets the owner in front, and the same repository is not added twice`() {
        val upstream = upstream()
        gitOutput(upstream, "remote", "add", "origin", "https://github.com/acme/demo.git")
        val cloner = cloner({ upstream.path })
        cloner.start(CloneRequest("github.com/acme/demo"))
        // The clone's origin is the local path here; a real one carries the forge address.
        gitOutput(File(clones, "demo"), "remote", "set-url", "origin", "https://github.com/acme/demo.git")

        assertTrue(cloner.start(CloneRequest("https://github.com/acme/demo.git")).reason().contains("already here"))
        cloner.start(CloneRequest("https://github.com/other/demo"))

        assertTrue(File(clones, "other-demo/README.md").isFile)
    }

    @Test
    fun `a clone that fails says why and leaves nothing behind`() {
        File(clones, ".cloning-stale/objects").mkdirs()
        val cloner = cloner({ File(root, "missing").path })

        assertFalse(File(clones, ".cloning-stale").exists(), "left by a server that stopped")
        cloner.start(CloneRequest("https://gitlab.com/group/app"))

        val failed = cloner.list().single()
        assertEquals(RepoCloner.FAILED, failed.state)
        assertTrue(checkNotNull(failed.message).contains("group/app"), failed.message)
        assertTrue(clones.list().orEmpty().isEmpty())
        assertTrue(cloner.start(CloneRequest("not a url")).reason().contains("GitHub, GitLab or Azure DevOps"))
    }

    @Test
    fun `hidden folders are not projects`() {
        val upstream = upstream()
        gitOutput(root, "clone", "--quiet", upstream.path, File(clones, ".cloning-abc").path)
        gitOutput(root, "clone", "--quiet", upstream.path, File(clones, "real").path)

        assertEquals(listOf("real"), ProjectScanner(listOf("${clones.path}/*")).scan().map { it.name })
    }
}
