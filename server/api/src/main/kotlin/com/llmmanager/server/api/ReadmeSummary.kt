package com.llmmanager.server.api

import java.io.File

/**
 * A repository's one-line description: the first sentence of its README, read from the
 * default branch with `git show`, so it works for bare mirrors and never touches a checkout.
 */
object ReadmeSummary {
    private const val MAX_CHARS = 200
    private val README = Regex("(?i)readme(\\.(md|markdown|rst|txt))?")
    private val SKIP_LINE = Regex("""^\s*(#|<!--|<|!\[|\[!\[|\||```|~~~|>\s*\[!)""")

    /** A setext heading underline (or a thematic break): the lines above it were a heading. */
    private val UNDERLINE = Regex("""^\s*(=+|-{3,})\s*$""")
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+(?=["'(\[]?[A-Z0-9])""")

    /** The description on [defaultBranch] as the remote has it, else locally, else on whatever HEAD is. */
    fun onDefaultBranch(git: Git, commonDir: File, defaultBranch: String?): String? {
        val ref = (defaultBranch?.let { listOf("refs/remotes/origin/$it", "refs/heads/$it") }.orEmpty() + "HEAD")
            .firstOrNull { git.run(commonDir, "rev-parse", "--verify", "--quiet", "$it^{commit}") != null }
        return ref?.let { of(git, commonDir, it) }
    }

    /** The description of the repository at [commonDir] on [ref], or null without a README or prose. */
    fun of(git: Git, commonDir: File, ref: String): String? {
        val file = git.run(commonDir, "ls-tree", "--name-only", ref)?.lines()?.firstOrNull { README.matches(it.trim()) }?.trim()
            ?: return null
        val text = git.run(commonDir, "show", "$ref:$file") ?: return null
        return firstSentence(text)
    }

    /** The first sentence of the first prose paragraph of [markdown], stripped of formatting. */
    fun firstSentence(markdown: String): String? {
        val paragraph = firstParagraph(markdown.replace("\r\n", "\n")) ?: return null
        val plain = plainText(paragraph)
        val sentence = SENTENCE_END.split(plain, limit = 2).first().trim()
        return when {
            sentence.isEmpty() -> null
            sentence.length <= MAX_CHARS -> sentence
            else -> sentence.take(MAX_CHARS - 1).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
        }
    }

    private fun firstParagraph(text: String): String? {
        val body = text.removeFrontMatter().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val lines = body.lines()
        var inFence = false
        val paragraph = mutableListOf<String>()
        for (line in lines) {
            val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
            if (fence) inFence = !inFence
            val underline = !inFence && UNDERLINE.matches(line)
            val prose = !inFence && !fence && !underline && line.isNotBlank() && !SKIP_LINE.containsMatchIn(line)
            when {
                prose -> paragraph += line.trim().removePrefix(">").trim()
                underline -> paragraph.clear()
                paragraph.isNotEmpty() -> break
            }
        }
        return paragraph.joinToString(" ").ifBlank { null }
    }

    private fun String.removeFrontMatter(): String =
        if (startsWith("---\n")) substringAfter("\n---\n", this) else this

    private fun plainText(markdown: String): String = markdown
        .replace(Regex("""!\[[^]]*]\([^)]*\)"""), "") // images
        .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1") // links
        .replace(Regex("""\[([^]]+)]\[[^]]*]"""), "$1") // reference links
        .replace(Regex("<[^>]+>"), "") // inline HTML
        .replace(Regex("""(\*\*|\*|`)(?=\S)(.+?)(?<=\S)\1"""), "$2") // emphasis, code
        .replace(Regex("""(?<!\w)(__|_)(?=\S)(.+?)(?<=\S)\1(?!\w)"""), "$2") // underscore emphasis, not snake_case
        .replace(Regex("""\s+"""), " ")
        .trim()
}
