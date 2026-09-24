---
name: remote-server-connectivity
description: >
  Use when the Android app must reach the home server (Beelink) that runs the
  agents — choosing between Tailscale, WireGuard, reverse proxy and SSH tunnel,
  handling the phone moving between home Wi-Fi / mobile data / foreign Wi-Fi,
  .local and mDNS discovery on LAN, self-signed or private-CA TLS trust,
  certificate pinning, captive portals, and the network-change and
  "which address do I use right now" logic. Triggers on Tailscale, tailnet,
  WireGuard, VPN, mDNS, NSD, .local, DDNS, port forward, reverse proxy, Caddy,
  self-signed cert, NetworkCallback, "can't reach the server", or any base-URL
  configuration screen.
---

# Reaching the home server from a phone

The phone leaves the house. That single fact is the hardest infrastructure problem
in this product, and getting it wrong shows up as "the app just doesn't work
sometimes" — the least debuggable class of bug.

## Never port-forward the gateway

The tempting answer — forward a port on the router, use DDNS — puts an agent
runtime that holds your GitHub and Azure DevOps credentials on the public internet,
protected by one shared secret. Do not do this. If a skill or prompt injection ever
convinces the agent to run something, the exposure is total.

The bar: **the server should not be reachable by anyone who is not on your private
network**, regardless of whether they know the password.

## Pick one: Tailscale

| Option | Verdict |
|---|---|
| **Tailscale / tailnet** | **Default.** WireGuard underneath, NAT traversal solved, per-device identity and ACLs, no open inbound ports, works on cellular. |
| WireGuard (self-hosted) | Fine if you already run it. You own NAT traversal, key distribution, and DDNS. More control, more rope. |
| SSH tunnel | Good for development, wrong as a product. Tunnels die and users cannot debug them. |
| Reverse proxy + public DNS | Only with mTLS or an identity-aware proxy in front. Otherwise it is port-forwarding with extra steps. |
| Port forward + DDNS | **No.** See above. |

Tailscale wins because device identity is the auth story, not just transport. A
revoked device is revoked everywhere, immediately, from one console — which matters
when the device in question is a phone you might lose.

OpenClaw supports this directly: `gateway.bind: "tailnet"` binds to the Tailscale
IPv4, and `gateway.auth.allowTailscale: true` lets tailnet identity headers satisfy
WebSocket and Control-UI auth. Note the documented split — **that setting does not
cover the HTTP API endpoints**, which always use the normal HTTP auth mode. See
`openclaw-gateway-client`.

## Address resolution: stop hardcoding a base URL

There is no single correct address. The same server is:

- `100.x.y.z` or `beelink.tailnet-name.ts.net` over the tailnet — **always works**
- `192.168.1.x` on home Wi-Fi — fastest, no VPN hop
- `beelink.local` via mDNS — convenient, unreliable (Wi-Fi APs drop multicast,
  Android's NSD is flaky, some networks block it entirely)

Model this as an **ordered candidate list with health-checked failover**, not a
setting:

```kotlin
data class Endpoint(val url: HttpUrl, val kind: Kind, val priority: Int) {
    enum class Kind { LAN_DIRECT, MDNS, TAILNET, MANUAL }
}

suspend fun resolveEndpoint(candidates: List<Endpoint>): Endpoint? = coroutineScope {
    // Probe in parallel, take the fastest that answers /readyz. Do NOT probe serially:
    // a dead LAN address costs a full connect timeout before you try the tailnet.
    candidates
        .map { async { if (probe(it)) it else null } }
        .awaitAll()
        .filterNotNull()
        .minByOrNull { it.priority }
}
```

Probe `/readyz`, not `/healthz` — the latter returns 200 while the Gateway is still
starting or draining, so you will connect to something that cannot serve you.

**Cache the winner with the network identity it was found on.** When the network
changes, re-resolve; do not reuse a LAN address discovered on a different Wi-Fi.

## React to network changes, don't poll

```kotlin
val request = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { /* re-resolve, reconnect */ }
    override fun onLost(network: Network) { /* mark offline, stop retrying hard */ }
    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered  = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // …
    }
})
```

Three traps:

- **`onAvailable` does not mean usable.** Wait for
  `NET_CAPABILITY_VALIDATED`, or you will reconnect onto a captive portal and get
  HTML back from a JSON endpoint.
- **Captive portals** return 200 with a login page. Your health check must verify
  the *response shape*, not just the status code. `/readyz` returning HTML is a
  portal, not a gateway.
- **Metered networks.** `NET_CAPABILITY_NOT_METERED` should gate bulk transfers
  (log backfill, diff prefetch), never the control channel. A user on cellular
  still needs to approve an agent action.

## TLS for a server with no public name

`wss://` is mandatory for public hosts, and a tailnet host can serve real certs via
Tailscale Serve — take that path when you can, because it means no custom trust code.

When you cannot and the Gateway presents a self-signed or private-CA cert
(`gateway.tls.autoGenerate` is documented as dev-only):

- **Pin the specific certificate or its private CA.** Add it as a trust anchor in a
  `network_security_config.xml` scoped to that one domain.
- **Never install a blanket `TrustManager` that accepts everything.** It is the most
  common "temporary" fix in this space and it disables TLS for the whole app.
- Pin with a **backup pin** and an expiry plan. A pinned cert that expires with no
  second pin bricks every installed app — and you cannot push a fix to a phone that
  cannot reach the server.
- Record the fingerprint at pairing time, when the user is physically at the machine
  and the connection is trustworthy. That is trust-on-first-use done at the one
  moment it is defensible.

## Connection state belongs in the UI

Users cannot debug this; the app must explain it. Surface a real state, not a
spinner:

```kotlin
sealed interface Link {
    data object Offline : Link                                  // no network at all
    data object NoRoute : Link                                  // network, server unreachable — VPN off?
    data class Connecting(val endpoint: Endpoint) : Link
    data class Connected(val endpoint: Endpoint, val rttMs: Long) : Link
    data class Degraded(val endpoint: Endpoint, val reason: String) : Link
    data class AuthFailed(val reason: String) : Link            // reachable but rejected
}
```

`NoRoute` should say **"Can't reach the Beelink — is Tailscale connected?"** with a
deep link to the VPN app. That single message resolves most real failures.
Distinguish it from `AuthFailed`, which means pairing is broken and needs a
different action entirely.

## Debug order

1. Can the *phone* reach it? Test from the device, not the dev machine — they are on
   different networks and that difference is the bug half the time.
2. VPN actually up? Tailscale disconnects silently on Android after battery
   optimisation kills it — exempt it from battery optimisation.
3. `/readyz` returns JSON, not HTML? HTML means captive portal or wrong port.
4. TLS handshake fails? Pin mismatch or expired cert.
5. Reachable but 401? Not a network problem — see `openclaw-gateway-client`.

## Checklist

- [ ] No port forwarding; server not reachable from the public internet
- [ ] Candidate endpoints probed in parallel, `/readyz` not `/healthz`
- [ ] Response *shape* validated, so captive portals fail closed
- [ ] `NetworkCallback` drives reconnect; `VALIDATED` required before use
- [ ] Resolved endpoint cached per network, invalidated on change
- [ ] TLS trust scoped to one domain; backup pin; no global permissive TrustManager
- [ ] Metered networks gate bulk transfer only, never the control channel
- [ ] `NoRoute` vs `AuthFailed` distinguished in the UI with different remedies
- [ ] VPN app exempted from battery optimisation, and the app detects when it isn't
