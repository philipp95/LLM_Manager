package com.llmmanager.android.ui

/** The agents the app knows, in the order it offers them. */
val AGENT_ORDER = listOf("claude", "codex", "gemini")

private val FORGE_NAMES = mapOf("github" to "GitHub", "gitlab" to "GitLab", "azure-devops" to "Azure DevOps")

/** Display name of a forge id, or null for repositories without one. */
fun forgeName(forge: String?): String? = FORGE_NAMES[forge]

/** GitLab numbers merge requests with "!", the others pull requests with "#". */
fun prRef(forge: String?, number: Long): String = (if (forge == "gitlab") "!" else "#") + number

fun agentLabel(agent: String) = when (agent) {
    "codex" -> "Codex"
    "gemini" -> "Gemini"
    else -> "Claude"
}

/** Who reviews a pipeline when the job does not say. */
fun defaultReviewer(agent: String) = if (agent == "claude") "codex" else "claude"

/** Two letters for a person or bot: "philipp95" → "PH", "Anna Smith" → "AS". */
fun initials(name: String): String {
    val words = name.removeSuffix("[bot]").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0][0]}${words[1][0]}".uppercase()
    }
}
