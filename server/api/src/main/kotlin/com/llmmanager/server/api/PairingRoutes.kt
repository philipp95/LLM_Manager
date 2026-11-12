package com.llmmanager.server.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pairing grants a token for who is calling, so unlike the token-carrying routes it must
 * refuse requests that another web site makes the browser send.
 */
internal fun Route.pairingRoutes(pairing: Pairing, access: Access, allowedHosts: Set<String>?) {
    post("/api/pair") {
        if (crossSite(call, allowedHosts)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("Pair from the dashboard itself"))
            return@post
        }
        val request = runCatching { call.receive<PairRequest>() }.getOrElse { PairRequest() }
        when (val outcome = withContext(Dispatchers.IO) { pairing.pair(access.addressOf(call), request.client) }) {
            is Pairing.Outcome.Paired -> call.respond(PairResponse(outcome.token, outcome.device.view(current = true)))
            is Pairing.Outcome.Refused -> call.respond(HttpStatusCode.Forbidden, ApiError(outcome.reason))
        }
    }
    get("/api/devices") {
        if (!call.authorized(access)) return@get
        val mine = access.presented(call)?.let(Pairing::hash)
        call.respond(DevicesResponse(pairing.list().map { it.view(current = it.tokenHash == mine) }))
    }
    post("/api/devices/{id}/revoke") {
        if (!call.authorized(access)) return@post
        if (pairing.revoke(call.parameters["id"].orEmpty())) call.respond(ApiOk()) else call.respond(HttpStatusCode.NotFound, ApiError("Unknown device"))
    }
}

private fun PairedDevice.view(current: Boolean) = DeviceView(id, name, os, client, pairedAt, lastSeenAt, current)

/** A browser request that another site caused: `Sec-Fetch-Site` says so, or the `Origin` is not this server. */
private fun crossSite(call: ApplicationCall, allowedHosts: Set<String>?): Boolean {
    val site = call.request.headers["Sec-Fetch-Site"]?.lowercase()
    val origin = call.request.headers[HttpHeaders.Origin]
    return site !in setOf(null, "same-origin", "none") || (origin != null && !ownOrigin(origin, allowedHosts))
}

private fun ownOrigin(origin: String, allowedHosts: Set<String>?): Boolean {
    val host = runCatching { URI(origin) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }?.rawAuthority?.lowercase()
    return host != null && (allowedHosts == null || host in allowedHosts)
}
