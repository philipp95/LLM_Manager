package com.llmmanager.android.data

import kotlinx.serialization.json.Json

/** Arranging repositories and adding new ones by address. */
class RepoClient(private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun prefer(server: ServerAddress, token: String, projectId: String, preference: RepoPreference): ApiResult<Unit> {
        val body = json.encodeToString(RepoPreference.serializer(), preference)
        return request(json, server, Call("PUT", "/api/projects/${encode(projectId)}/preference", body), token) { }
    }

    /** Starts cloning [url] on the server; the clone goes on in the background there. */
    suspend fun clone(server: ServerAddress, token: String, url: String): ApiResult<CloneStatus> {
        val body = json.encodeToString(CloneRequest.serializer(), CloneRequest(url))
        return request(json, server, Call("POST", "/api/projects/clone", body), token) { json.decodeFromString(CloneStatus.serializer(), it) }
    }

    suspend fun clones(server: ServerAddress, token: String): ApiResult<List<CloneStatus>> =
        request(json, server, Call("GET", "/api/projects/clones"), token) { json.decodeFromString(ClonesResponse.serializer(), it).clones }
}
