package com.llmmanager.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** What the settings page works with. */
class SettingsApi(
    val preferences: PreferencesStore,
    val accounts: AgentAccounts,
    val signIn: AgentSignIn,
    val forges: ForgeAccounts? = null,
)

/** One agent on the settings page: its preferences and how its CLI is signed in. */
@Serializable
data class AgentSettings(
    val agent: Agent,
    val installed: Boolean,
    val enabled: Boolean,
    val model: String,
    val signedIn: Boolean,
    val method: String? = null,
    val account: String? = null,
    /** An API key can be used instead of an account. */
    val apiKey: Boolean,
    /** A sign-in in progress, so the pop-up can pick up where it was. */
    val signIn: SignInView? = null,
)

@Serializable
data class SettingsResponse(
    val profile: Profile,
    val defaultAgent: Agent?,
    val agents: List<AgentSettings>,
    val forges: List<ForgeAccount>,
    /** Set when a change went through, to show the person. */
    val message: String? = null,
)

@Serializable
data class ProfileRequest(val name: String = "", val email: String = "", val defaultAgent: Agent? = null)

@Serializable
data class AgentPreferenceRequest(val enabled: Boolean = true, val model: String = "")

@Serializable
data class SignInRequest(val restart: Boolean = false)

@Serializable
data class SignInCodeRequest(val code: String = "")

@Serializable
data class ApiKeyRequest(val key: String = "")

/**
 * The settings page: profile, agent preferences and sign-ins, code hosts. Every route needs
 * the access token or a paired device; keys, tokens and codes are accepted, never returned.
 */
fun Route.settingsRoutes(api: SettingsApi, access: Access, onChange: () -> Unit = {}) {
    get("/api/settings") {
        if (!call.authorized(access)) return@get
        call.respond(withContext(Dispatchers.IO) { api.view() })
    }
    put("/api/settings/profile") {
        if (!call.authorized(access)) return@put
        val request = call.body<ProfileRequest>() ?: return@put
        val profile = Profile(request.name.trim(), request.email.trim())
        PreferencesStore.problem(profile)?.let { return@put call.badRequest(it) }
        api.preferences.update { it.copy(profile = profile, defaultAgent = request.defaultAgent) }
        onChange()
        call.respond(withContext(Dispatchers.IO) { api.view("Profile saved") })
    }
    put("/api/settings/agents/{agent}") {
        if (!call.authorized(access)) return@put
        val agent = call.agent() ?: return@put
        val request = call.body<AgentPreferenceRequest>() ?: return@put
        val model = request.model.trim()
        PreferencesStore.modelProblem(model)?.let { return@put call.badRequest(it) }
        api.preferences.update { it.copy(agents = it.agents + (agent to AgentPreference(request.enabled, model))) }
        onChange()
        call.respond(withContext(Dispatchers.IO) { api.view("${agent.label} saved") })
    }
    accountRoutes(api, access, onChange)
    signInRoutes(api, access, onChange)
    api.forges?.let { forgeRoutes(it, api, access) }
}

/** Sign out, or sign in with an API key. */
private fun Route.accountRoutes(api: SettingsApi, access: Access, onChange: () -> Unit) {
    post("/api/settings/agents/{agent}/sign-out") {
        if (!call.authorized(access)) return@post
        val agent = call.agent() ?: return@post
        withContext(Dispatchers.IO) { api.accounts.signOut(agent) }?.let { return@post call.badRequest(it) }
        onChange()
        call.respond(withContext(Dispatchers.IO) { api.view("${agent.label} signed out") })
    }
    put("/api/settings/agents/{agent}/api-key") {
        if (!call.authorized(access)) return@put
        val agent = call.agent() ?: return@put
        val request = call.body<ApiKeyRequest>() ?: return@put
        withContext(Dispatchers.IO) { api.accounts.useApiKey(agent, request.key) }?.let { return@put call.badRequest(it) }
        onChange()
        call.respond(withContext(Dispatchers.IO) { api.view("${agent.label} now uses the API key") })
    }
}

/** The sign-in pop-up: start, follow, hand over the code, cancel. */
private fun Route.signInRoutes(api: SettingsApi, access: Access, onChange: () -> Unit) {
    post("/api/settings/agents/{agent}/sign-in") {
        if (!call.authorized(access)) return@post
        val agent = call.agent() ?: return@post
        val restart = call.body<SignInRequest>(required = false)?.restart == true
        call.respond(withContext(Dispatchers.IO) { api.signIn.start(agent, restart) })
    }
    get("/api/settings/agents/{agent}/sign-in") {
        if (!call.authorized(access)) return@get
        val agent = call.agent() ?: return@get
        val view = withContext(Dispatchers.IO) { api.signIn.view(agent) }
        if (view.state == AgentSignIn.DONE) onChange()
        call.respond(view)
    }
    post("/api/settings/agents/{agent}/sign-in/code") {
        if (!call.authorized(access)) return@post
        val agent = call.agent() ?: return@post
        val request = call.body<SignInCodeRequest>() ?: return@post
        call.respond(withContext(Dispatchers.IO) { api.signIn.submit(agent, request.code) })
    }
    delete("/api/settings/agents/{agent}/sign-in") {
        if (!call.authorized(access)) return@delete
        val agent = call.agent() ?: return@delete
        call.respond(withContext(Dispatchers.IO) { api.signIn.cancel(agent) })
    }
}

private fun Route.forgeRoutes(forges: ForgeAccounts, api: SettingsApi, access: Access) {
    put("/api/settings/forges/{forge}") {
        if (!call.authorized(access)) return@put
        val request = call.body<ForgeTokenRequest>() ?: return@put
        when (val outcome = withContext(Dispatchers.IO) { forges.connect(call.parameters["forge"].orEmpty(), request) }) {
            is ForgeAccounts.Outcome.Ok -> call.respond(withContext(Dispatchers.IO) { api.view(outcome.message) })
            is ForgeAccounts.Outcome.Invalid -> call.badRequest(outcome.reason)
        }
    }
    delete("/api/settings/forges/{forge}") {
        if (!call.authorized(access)) return@delete
        val forge = call.parameters["forge"].orEmpty()
        when (val outcome = withContext(Dispatchers.IO) { forges.disconnect(forge, call.request.queryParameters["host"]) }) {
            is ForgeAccounts.Outcome.Ok -> call.respond(withContext(Dispatchers.IO) { api.view(outcome.message) })
            is ForgeAccounts.Outcome.Invalid -> call.badRequest(outcome.reason)
        }
    }
}

/** The whole page; blocking, as it asks each CLI how it is signed in (cached briefly). */
fun SettingsApi.view(message: String? = null): SettingsResponse {
    val saved = preferences.get()
    val agents = Agent.entries.map { agent ->
        val status = accounts.status(agent)
        AgentSettings(
            agent, status.installed, preferences.enabled(agent), saved.agents[agent]?.model.orEmpty(),
            status.signedIn, status.method, status.account, apiKey = accounts.takesApiKey(agent), signIn = signIn.active(agent),
        )
    }
    return SettingsResponse(saved.profile, saved.defaultAgent, agents, forges?.list().orEmpty(), message)
}

/** The `{agent}` path segment; responds 404 and returns null when it names no agent. */
private suspend fun ApplicationCall.agent(): Agent? {
    val agent = Agent.entries.firstOrNull { it.name.lowercase() == parameters["agent"] }
    if (agent == null) respond(HttpStatusCode.NotFound, ApiError("Unknown agent"))
    return agent
}

/** The JSON body; responds 400 and returns null when it is malformed (or, when [required], missing). */
private suspend inline fun <reified T : Any> ApplicationCall.body(required: Boolean = true): T? {
    val body = runCatching { receive<T>() }.getOrNull()
    if (body == null && required) respond(HttpStatusCode.BadRequest, ApiError("Malformed request"))
    return body
}

private suspend fun ApplicationCall.badRequest(reason: String) = respond(HttpStatusCode.BadRequest, ApiError(reason))
