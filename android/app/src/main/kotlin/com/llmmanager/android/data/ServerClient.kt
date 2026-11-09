package com.llmmanager.android.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** Network up but the server did not answer: usually Tailscale is off. */
    data class NoRoute(val detail: String) : ApiResult<Nothing>

    /** Reachable, but the token was missing or rejected. */
    data object Unauthorized : ApiResult<Nothing>

    data class Failed(val message: String) : ApiResult<Nothing>
}

/** The dashboard's HTTP API. Every call targets a validated [ServerAddress]. */
class ServerClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun projects(server: ServerAddress): ApiResult<ProjectsResponse> =
        request(server, "GET", "/api/projects", token = null) { json.decodeFromString(ProjectsResponse.serializer(), it) }

    suspend fun startCodex(server: ServerAddress, projectId: String, token: String): ApiResult<LaunchResponse> =
        request(server, "POST", "/api/projects/${URLEncoder.encode(projectId, "UTF-8")}/codex", token) {
            json.decodeFromString(LaunchResponse.serializer(), it)
        }

    private suspend fun <T> request(
        server: ServerAddress,
        method: String,
        path: String,
        token: String?,
        decode: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val connection = try {
            URL(server.baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext ApiResult.Failed(e.message ?: "Invalid address")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // The token is scoped to this host: never follow a redirect elsewhere with it.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val status = connection.responseCode
            val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            interpret(status, connection.contentType, body, decode)
        } catch (e: IOException) {
            ApiResult.NoRoute(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            ApiResult.Failed("Unexpected response: ${e.message?.take(120)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> interpret(status: Int, contentType: String?, body: String, decode: (String) -> T): ApiResult<T> {
        // A captive portal answers 200 with HTML; only a JSON body counts as the server.
        val isJson = contentType?.startsWith("application/json") == true
        return when {
            status == HttpURLConnection.HTTP_UNAUTHORIZED -> ApiResult.Unauthorized
            !isJson -> ApiResult.NoRoute("Unexpected response ($status) — not the LLM Manager server")
            status !in 200..299 -> ApiResult.Failed(errorMessage(body) ?: "Server error ($status)")
            else -> ApiResult.Ok(decode(body))
        }
    }

    private fun errorMessage(body: String): String? = try {
        json.decodeFromString(ApiError.serializer(), body).error
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
