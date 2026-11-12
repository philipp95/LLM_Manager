---
name: openclaw-gateway-client
description: >
  Use when writing or reviewing client code that talks to an OpenClaw Gateway —
  the WebSocket protocol (connect.challenge / connect / hello-ok handshake, req /
  res / event envelopes, device identity and pairing, deviceToken, operator
  scopes), the OpenAI-compatible HTTP endpoints, /health and /ready probes,
  ws:// vs wss:// host rules, bind modes and port 18789, models.list vs
  /v1/models, or debugging PAIRING_REQUIRED, handshake rejections, 401s, and
  reconnect/resubscribe behaviour from an Android or desktop client.
---

# OpenClaw Gateway client

The Gateway owns sessions, routing, and channel connections. A client — phone app,
CLI, dashboard — is a remote to it. Model the app as a thin client over
Gateway-held state.

> **Version discipline.** This protocol is on dated pre-release versions and moves.
> Everything below reflects the docs at <https://docs.openclaw.ai/gateway/protocol/>.
> Never hardcode a method name you have not seen in the connection's own
> `features` list (see *Handshake*). Record the Gateway version you tested against.

## Probe before you code

```bash
curl -s http://127.0.0.1:18789/healthz    # HTTP server live
curl -s http://127.0.0.1:18789/readyz     # startup done, agents admitted, channels ready
openclaw --version
```

Three unauthenticated `GET`/`HEAD` probe pairs exist:

| Path | Means |
|---|---|
| `/health`, `/healthz` | the HTTP server is live — liveness/restart decisions |
| `/startup`, `/startupz` | startup sidecars settled, not draining |
| `/ready`, `/readyz` | startup complete, agent DBs admitted, channels pass deep readiness |

`/healthz` returns 200 while the HTTP server is up **even when other things are
failing** — use `/readyz` to decide whether to send traffic. Remote unauthenticated
responses carry only `ok` and `status`; local or authenticated callers also get
`version`, `uptimeMs`, `pendingReason`.

There is no `/status` endpoint. Do not probe arbitrary paths and treat HTTP 200 as
success — a route you invented may be answered by something other than the API.

## Reachability

| Setting | Default | Values |
|---|---|---|
| `gateway.port` | `18789` | WebSocket and HTTP share it |
| `gateway.bind` | `loopback` | `loopback` \| `lan` \| `tailnet` \| `auto` \| `custom` |

`bind: "loopback"` means **the phone cannot reach it**. Pick one:

- `adb reverse tcp:18789 tcp:18789` — emulator/USB device reaches host loopback.
  Best for development: nothing is exposed to the network.
- `bind: "tailnet"` — Tailscale IPv4. Best for "my phone, anywhere".
- `bind: "lan"` + auth — real listener on the Wi-Fi; the token is now a credential.
- SSH tunnel: `ssh -N -L 18789:127.0.0.1:18789 user@host`.

Docker: the default loopback bind is unreachable across a bridge network — use
`--network host` or `bind: "lan"`.

### ws:// vs wss:// is enforced

Plaintext `ws://` is accepted only for loopback, private/LAN (RFC 1918),
link-local, CGNAT, `.local`, and `.ts.net` hosts. **Public hosts must use `wss://`.**
A rejected connection means the host is public, not that you need a bypass toggle.

On Android keep `usesCleartextTraffic` false in release. If LAN dev needs cleartext,
scope it to a debug-only `network_security_config.xml` domain exception.

Non-loopback binds require auth: `auth.mode: "none"` + `bind: "lan"` is refused by
design.

## Handshake — three messages, in this order

**1. Gateway → client**, unprompted on connect:

```json
{ "type": "event", "event": "connect.challenge", "payload": { "nonce": "…", "ts": 1234567890 } }
```

Device-auth clients must use this `ts` as their `connect.params.device.signedAt`.

**2. Client → Gateway:**

```json
{
  "type": "req", "id": "1", "method": "connect",
  "params": {
    "minProtocol": <n>, "maxProtocol": <n>,
    "client": { "id": "…", "version": "…", "platform": "android", "mode": "…" },
    "role": "operator",
    "scopes": ["operator.read", "operator.write"],
    "auth": { "token": "…" },
    "device": { "id": "…", "publicKey": "…", "signature": "…", "nonce": "…", "signedAt": 1234567890 }
  }
}
```

**Read the current protocol version number and the exact `device` field names from
<https://docs.openclaw.ai/gateway/protocol/handshake> before implementing** — this
is the fastest-moving part of the API and the shape above is illustrative. Do not
copy a version number out of this file.

**3. Gateway → client**, the hello-ok:

```json
{
  "type": "res", "id": "1", "ok": true,
  "payload": {
    "protocol": 1, "serverVersion": "…", "connectionId": "…",
    "features": { "methods": ["…"], "events": ["…"] },
    "auth": { "role": "operator", "scopes": ["…"], "deviceToken": "…" },
    "limits": { }
  }
}
```

**Feature-detect from `features.methods` / `features.events`** rather than assuming
a method exists because a doc mentions it. Treat absence as "not supported for this
connection" and degrade gracefully — the list is what the Gateway advertises to
you, which is the only capability signal you have at runtime.

Credentials go in `connect.params.auth` — an application-level connect exchange, not
the HTTP upgrade. Do not put tokens in the WebSocket query string.

## Device identity and pairing

A native client is expected to:

1. **Persist an Ed25519 device identity.** Generate once. Ed25519 is not universally
   supported by the `AndroidKeyStore` provider, so either generate a hardware-backed
   EC key if the Gateway accepts one, or generate the Ed25519 seed in-app and store
   it **encrypted under a Keystore AES key** — the Keystore protects the key that
   protects the seed; it does not store arbitrary bytes itself. See
   `android-secure-credentials`. Exclude it from backup and device transfer.
2. Wait for `connect.challenge` and sign the challenge-bound device payload using
   its timestamp as `signedAt`.
3. Send `connect` with the requested `role: "operator"` and the narrowest scopes
   that work — `operator.read`, `operator.write`, `operator.approvals`. Ask for
   read-only if the app only displays state.
4. Handle a structured **`PAIRING_REQUIRED`** response: it carries a request id, and
   the human approves on the host with `openclaw devices approve <requestId>`.
   Surface that command in the UI — an unexplained failure here is the single most
   confusing first-run experience.
5. After approval, **persist `hello-ok.auth.deviceToken`** with its negotiated role
   and scopes, and authenticate with that token on later connections instead of the
   bootstrap secret.

Store the deviceToken and bootstrap token encrypted under a Keystore key — not in
plain SharedPreferences, `strings.xml`, or `BuildConfig`. Never log tokens or raw
handshake frames. See `android-secure-credentials` for the storage pattern.

## Message envelopes

```json
{ "type": "req", "id": "…", "method": "…", "params": {} }
{ "type": "res", "id": "…", "ok": true, "payload": {} }
{ "type": "res", "id": "…", "ok": false,
  "error": { "code": "…", "message": "…", "retryable": true, "retryAfterMs": 1000 } }
{ "type": "event", "event": "…", "payload": {}, "seq": 12 }
```

Honour `error.retryable` and `error.retryAfterMs` instead of inventing your own
retry policy. Use `seq` to detect dropped events.

Known method/event names include `chat.send`, `chat.history`, `sessions.list`,
`sessions.subscribe`, `models.list`, `status`; events `tick` (periodic keepalive),
`session.message` (transcript), `session.operation` (in-flight operation),
`session.tool` (tool event stream), `chat` (UI chat updates such as `chat.inject`).
**Confirm each against `features` at runtime.**

Bootstrap shortcut: `sessions.subscribe` with a non-empty list param
(`{ limit: 60, ownerFirst: true }`) subscribes *and* loads the initial roster in one
request; `{}` subscribes without a snapshot.

## HTTP API

Same host and port. OpenAI-compatible endpoints are **opt-in** via config:

- `gateway.http.endpoints.chatCompletions.enabled` → `POST /v1/chat/completions`
- `gateway.http.endpoints.responses.enabled` → responses API

Auth is `Authorization: Bearer <secret>` in **both** token mode (the
`gateway.auth.token`) and password mode (the `gateway.auth.password`). It is not
Basic auth and not a custom password header.

`gateway.auth.allowTailscale: true` lets Tailscale identity headers satisfy
**WebSocket and Control-UI** auth — but **HTTP API endpoints always use the normal
HTTP auth mode**. A client that works over WS and 401s over HTTP on the same tailnet
is hitting exactly this.

### The model-list trap — important for this app

`GET /v1/models` lists **top-level agent targets** (`openclaw`, `openclaw/default`,
`openclaw/<agentId>`) — *not* backend provider models, and not sub-agents. OpenClaw
treats the OpenAI `model` field as an **agent target**, not a raw provider model id.

For an app whose job is *managing LLM models*, that distinction is the whole design:

- To list the **runtime model catalog** (the actual provider models), use the
  `models.list` RPC over the WebSocket.
- `/v1/models` answers "which agents can I address", which is a different screen.
- `x-openclaw-model` overrides the backend model on a request. Shared-secret callers
  may use it directly; identity-bearing callers need the `operator.admin` scope.
  Requesting `operator.admin` just to switch models is a real privilege decision —
  make it explicit in the UI, not a silent default.

Conflating these two lists produces a model manager that shows three entries and
cannot explain why.

## Client behaviour on mobile

- **Reconnect with jittered, capped exponential backoff.** Radio handoffs and doze
  drop sockets constantly; a tight retry loop is a battery bug.
- **On reconnect, re-establish subscriptions**, call `chat.history` for the selected
  session, adopt any `inFlightRun` state, and reconcile `activeRunIds`. Do not start
  a fresh run — you will duplicate work the Gateway is already doing.
- **Own the socket deliberately.** Scope it to whatever actually needs it: a
  screen-scoped `repeatOnLifecycle(STARTED)` collector for a chat screen, or a
  service/singleton if the connection legitimately spans screens or must survive
  backgrounding. State the owner and its cancellation policy; the bug to avoid is an
  *unowned* socket, not a long-lived one.
- **Use `tick` for liveness.** Missing keepalives are the earliest signal of a
  half-open socket the OS has not torn down yet.
- **Pin or explicitly trust the TLS cert** when using `gateway.tls.autoGenerate`
  (documented as dev-only) — trust it narrowly, never disable verification.
- **Surface `session.tool` events in the UI.** Users need to see what the agent did
  on their behalf; silent tool execution is what makes agent apps feel unsafe.

## Debugging order

1. `curl /readyz` **from the device**, not the dev machine — isolates bind/network
   from auth.
2. Reachable but the socket closes at connect → auth in `connect.params.auth`, a
   scope you were not granted, or the ws/wss host-class rule.
3. `PAIRING_REQUIRED` → not an error; the human must run
   `openclaw devices approve <requestId>`.
4. WS fine but HTTP 401 → the `allowTailscale`/HTTP-auth split above, or you sent
   Basic instead of Bearer.
5. Unknown method → check `features.methods` from the hello-ok and the Gateway
   version before assuming the name.
