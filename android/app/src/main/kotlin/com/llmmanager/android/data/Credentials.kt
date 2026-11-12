package com.llmmanager.android.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The token for API calls: the one saved on this phone, or a new one from pairing. The server
 * pairs phones signed in to its owner's Tailscale account, so nobody has to copy its access
 * token; the server's token can still be typed in for any other phone.
 */
class Credentials(private val settings: SettingsStore, private val client: ServerClient) {
    private sealed interface Token {
        data class Ready(val value: String) : Token

        /** No token, and pairing was refused: [ApiResult.Failed] carries why. */
        data object Missing : Token

        data class Offline(val detail: String) : Token
    }

    private val mutex = Mutex()

    /** Why the server last refused to pair this phone; shown next to the token field. */
    @Volatile var refusal: String? = null
        private set

    /** Runs [request] with a token; when the server rejects it (revoked, replaced), pairs again once and retries. */
    suspend fun <T> call(server: ServerAddress, request: suspend (token: String) -> ApiResult<T>): ApiResult<T> {
        val first = token(server)
        val result = first.send(request)
        if (first !is Token.Ready || result != ApiResult.Unauthorized) return result
        return renew(server, first.value).send(request)
    }

    private suspend fun <T> Token.send(request: suspend (token: String) -> ApiResult<T>): ApiResult<T> = when (this) {
        is Token.Ready -> request(value)
        Token.Missing -> ApiResult.Unauthorized
        is Token.Offline -> ApiResult.NoRoute(detail)
    }

    private suspend fun token(server: ServerAddress): Token = mutex.withLock {
        settings.token()?.let { Token.Ready(it) } ?: pair(server)
    }

    /** [rejected] stopped working: forget it and pair again, unless another call already did. */
    private suspend fun renew(server: ServerAddress, rejected: String): Token = mutex.withLock {
        val current = settings.token()
        if (current != null && current != rejected) return@withLock Token.Ready(current)
        settings.clearToken()
        pair(server)
    }

    private suspend fun pair(server: ServerAddress): Token = when (val result = client.pair(server)) {
        is ApiResult.Ok -> {
            settings.saveToken(result.value.token, pairedAs = result.value.device.name)
            refusal = null
            Token.Ready(result.value.token)
        }
        is ApiResult.NoRoute -> Token.Offline(result.detail)
        is ApiResult.Failed -> {
            refusal = result.message
            Token.Missing
        }
        ApiResult.Unauthorized -> Token.Missing
    }
}
