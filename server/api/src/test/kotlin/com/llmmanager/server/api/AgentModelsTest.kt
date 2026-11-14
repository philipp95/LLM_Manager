package com.llmmanager.server.api

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AgentModelsTest {
    @TempDir
    lateinit var directory: Path

    private val home: File get() = directory.toFile()

    @Test
    fun `Codex offers what its own picker lists, in its order`() {
        File(home, ".codex").mkdirs()
        File(home, ".codex/models_cache.json").writeText(
            """
            {"models":[
              {"slug":"gpt-b","display_name":"GPT B","description":"Second","visibility":"list","priority":2},
              {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide","priority":0},
              {"slug":"gpt-a","display_name":"GPT A","description":"First","visibility":"list","priority":1},
              {"slug":"bad slug\"","display_name":"Bad","visibility":"list","priority":3}
            ]}
            """.trimIndent(),
        )

        val options = AgentModels(home, { null }).options(Agent.CODEX)

        assertEquals(listOf(ModelOption("gpt-a", "GPT A", "First"), ModelOption("gpt-b", "GPT B", "Second")), options)
    }

    @Test
    fun `without a readable list each agent still has choices`() {
        File(home, ".codex").mkdirs()
        File(home, ".codex/models_cache.json").writeText("not json")
        val models = AgentModels(home, { null })

        assertTrue(models.options(Agent.CODEX).isNotEmpty())
        assertEquals(listOf("fable", "opus", "sonnet", "haiku"), models.options(Agent.CLAUDE).map { it.id })
        assertEquals("auto", models.options(Agent.GEMINI).first().id)
    }

    @Test
    fun `the Gemini list is read from its CLI's own output`() {
        val output = """
            Available Gemini Models:

            auto (Auto)
              Description:   Let Gemini CLI decide the best model for the task: gemini-2.5-pro, gemini-3.5-flash
              Context limit: 1,048,576 input tokens / 8,192 output tokens

            gemini-3.5-flash (Gemini 3.5 Flash)
              Context limit: 1,048,576 input tokens (cached)
            not a model line
        """.trimIndent()

        assertEquals(
            listOf(ModelOption("auto", "Auto"), ModelOption("gemini-3.5-flash", "Gemini 3.5 Flash")),
            AgentModels.parseGemini(output),
        )
    }
}
