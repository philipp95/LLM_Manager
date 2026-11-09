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
        request(server, Call("GET", "/api/projects"), token = null) { json.decodeFromString(ProjectsResponse.serializer(), it) }

    suspend fun startCodex(server: ServerAddress, projectId: String, token: String): ApiResult<LaunchResponse> =
        request(server, Call("POST", "/api/projects/${encode(projectId)}/codex"), token) {
            json.decodeFromString(LaunchResponse.serializer(), it)
        }

    suspend fun usage(server: ServerAddress): ApiResult<UsageResponse> =
        request(server, Call("GET", "/api/usage"), token = null) { json.decodeFromString(UsageResponse.serializer(), it) }

    suspend fun schedule(server: ServerAddress, token: String): ApiResult<ScheduleResponse> =
        request(server, Call("GET", "/api/schedule"), token) { json.decodeFromString(ScheduleResponse.serializer(), it) }

    suspend fun createJob(server: ServerAddress, token: String, job: JobRequest): ApiResult<Unit> =
        request(server, Call("POST", "/api/jobs", json.encodeToString(JobRequest.serializer(), job)), token) { }

    /** [action] is one of run, pause, enable, delete. */
    suspend fun jobAction(server: ServerAddress, token: String, jobId: String, action: String): ApiResult<Unit> =
        request(server, Call("POST", "/api/jobs/${encode(jobId)}/${encode(action)}"), token) { }

    suspend fun cancelRun(server: ServerAddress, token: String, runId: String): ApiResult<Unit> =
        request(server, Call("POST", "/api/runs/${encode(runId)}/cancel"), token) { }

    suspend fun runLog(server: ServerAddress, token: String, runId: String): ApiResult<RunLog> =
        request(server, Call("GET", "/api/runs/${encode(runId)}/log"), token) { json.decodeFromString(RunLog.serializer(), it) }

    /** One HTTP request: [body], when present, is sent as JSON. */
    private data class Call(val method: String, val path: String, val body: String? = null)

    private suspend fun <T> request(server: ServerAddress, call: Call, token: String?, decode: (String) -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        val (method, path, body) = call
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
                val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                if (body != null) connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            interpret(json, status, connection.contentType, text, decode)
        } catch (e: IOException) {
            ApiResult.NoRoute(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            ApiResult.Failed("Unexpected response: ${e.message?.take(120)}")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8")

private fun <T> interpret(json: Json, status: Int, contentType: String?, body: String, decode: (String) -> T): ApiResult<T> {
    // A captive portal answers 200 with HTML; only a JSON body counts as the server.
    val isJson = contentType?.startsWith("application/json") == true
    return when {
        status == HttpURLConnection.HTTP_UNAUTHORIZED -> ApiResult.Unauthorized
        !isJson -> ApiResult.NoRoute("Unexpected response ($status) — not the LLM Manager server")
        status !in 200..299 -> ApiResult.Failed(errorMessage(json, body) ?: "Server error ($status)")
        else -> ApiResult.Ok(decode(body))
    }
}

private fun errorMessage(json: Json, body: String): String? = try {
    json.decodeFromString(ApiError.serializer(), body).error
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
