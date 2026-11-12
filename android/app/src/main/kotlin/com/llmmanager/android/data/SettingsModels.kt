package com.llmmanager.android.data

import kotlinx.serialization.Serializable

// Mirrors server/api's settings JSON. Keys, tokens and sign-in codes only ever go to the server.

@Serializable
data class SettingsResponse(
    val profile: Profile = Profile(),
    val defaultAgent: String? = null,
    val agents: List<AgentSettings> = emptyList(),
    val forges: List<ForgeAccount> = emptyList(),
    /** Set when a change went through, to show the person. */
    val message: String? = null,
)

/** The person using LLM Manager; the committer of agent commits. */
@Serializable
data class Profile(val name: String = "", val email: String = "")

@Serializable
data class AgentSettings(
    val agent: String,
    val installed: Boolean = false,
    val enabled: Boolean = true,
    val model: String = "",
    val signedIn: Boolean = false,
    /** How it is signed in, e.g. "Claude Pro", "ChatGPT", "Google account", "API key". */
    val method: String? = null,
    val account: String? = null,
    /** An API key can be used instead of an account. */
    val apiKey: Boolean = false,
    /** A sign-in in progress on the server. */
    val signIn: SignInView? = null,
)

/** A code host: GitHub, a GitLab instance, or Azure DevOps. */
@Serializable
data class ForgeAccount(
    val forge: String,
    val host: String = "",
    val connected: Boolean = false,
    val account: String? = null,
    val note: String? = null,
)

/** An agent CLI's sign-in running on the server, as the pop-up shows it. */
@Serializable
data class SignInView(
    val agent: String,
    /** idle, starting, waiting, checking, done or failed. */
    val state: String = "idle",
    /** The sign-in page; the server only passes on links to the agent's own sign-in hosts. */
    val url: String? = null,
    /** A code to enter on that page (Codex). */
    val userCode: String? = null,
    /** The CLI waits for the code the sign-in page shows (Claude, Gemini). */
    val needsCode: Boolean = false,
    val message: String? = null,
    /** The CLI's last lines, for when something unexpected happens. */
    val screen: List<String> = emptyList(),
    val expiresAt: String? = null,
) {
    /** Still going: the pop-up keeps asking the server. */
    val live: Boolean get() = state == "starting" || state == "waiting" || state == "checking"

    /** The link to open, only when it is an https link. */
    val safeUrl: String? get() = url?.takeIf { it.startsWith("https://") }
}

@Serializable
data class ProfileRequest(val name: String, val email: String, val defaultAgent: String? = null)

@Serializable
data class AgentPreferenceRequest(val enabled: Boolean, val model: String)

@Serializable
data class SignInRequest(val restart: Boolean = false)

@Serializable
data class SignInCodeRequest(val code: String)

@Serializable
data class ApiKeyRequest(val key: String)

@Serializable
data class ForgeTokenRequest(val token: String, val host: String? = null, val organization: String? = null)
