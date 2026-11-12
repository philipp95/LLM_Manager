package com.llmmanager.android.data

import kotlinx.serialization.json.Json

/** One step of an agent's sign-in on the server. */
sealed interface SignInStep {
    /** Starts it, or picks up the one already running unless [restart]. */
    data class Start(val restart: Boolean = false) : SignInStep

    data object Poll : SignInStep

    /** Hands the code from the sign-in page to the waiting CLI. */
    data class Code(val code: String) : SignInStep

    data object Cancel : SignInStep
}

/** The settings page's part of the API. Changes answer with the whole settings page again. */
class SettingsClient(private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun load(server: ServerAddress, token: String): ApiResult<SettingsResponse> = page(server, token, Call("GET", "/api/settings"))

    suspend fun saveProfile(server: ServerAddress, token: String, profile: ProfileRequest): ApiResult<SettingsResponse> =
        page(server, token, Call("PUT", "/api/settings/profile", json.encodeToString(ProfileRequest.serializer(), profile)))

    suspend fun saveAgent(server: ServerAddress, token: String, agent: String, preference: AgentPreferenceRequest): ApiResult<SettingsResponse> =
        page(server, token, Call("PUT", agentPath(agent), json.encodeToString(AgentPreferenceRequest.serializer(), preference)))

    suspend fun signOut(server: ServerAddress, token: String, agent: String): ApiResult<SettingsResponse> =
        page(server, token, Call("POST", agentPath(agent) + "/sign-out"))

    /** The key goes in the request body to the configured server only, and is never sent back. */
    suspend fun useApiKey(server: ServerAddress, token: String, agent: String, key: String): ApiResult<SettingsResponse> =
        page(server, token, Call("PUT", agentPath(agent) + "/api-key", json.encodeToString(ApiKeyRequest.serializer(), ApiKeyRequest(key))))

    suspend fun connectForge(server: ServerAddress, token: String, forge: String, request: ForgeTokenRequest): ApiResult<SettingsResponse> =
        page(server, token, Call("PUT", "/api/settings/forges/${encode(forge)}", json.encodeToString(ForgeTokenRequest.serializer(), request)))

    suspend fun disconnectForge(server: ServerAddress, token: String, forge: String, host: String?): ApiResult<SettingsResponse> {
        val query = host?.let { "?host=" + encode(it) }.orEmpty()
        return page(server, token, Call("DELETE", "/api/settings/forges/${encode(forge)}$query"))
    }

    suspend fun signIn(server: ServerAddress, token: String, agent: String, step: SignInStep): ApiResult<SignInView> {
        val path = agentPath(agent) + "/sign-in"
        val call = when (step) {
            is SignInStep.Start -> Call("POST", path, json.encodeToString(SignInRequest.serializer(), SignInRequest(step.restart)))
            SignInStep.Poll -> Call("GET", path)
            is SignInStep.Code -> Call("POST", "$path/code", json.encodeToString(SignInCodeRequest.serializer(), SignInCodeRequest(step.code)))
            SignInStep.Cancel -> Call("DELETE", path)
        }
        return request(json, server, call, token) { json.decodeFromString(SignInView.serializer(), it) }
    }

    private suspend fun page(server: ServerAddress, token: String, call: Call): ApiResult<SettingsResponse> =
        request(json, server, call, token) { json.decodeFromString(SettingsResponse.serializer(), it) }

    private fun agentPath(agent: String) = "/api/settings/agents/${encode(agent)}"
}
