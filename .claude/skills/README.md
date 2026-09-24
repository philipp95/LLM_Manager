# Skills

Agent skills for this project. Each subdirectory holds a `SKILL.md` that agents
load on demand when its `description` matches the work at hand.

## Android / Kotlin (vendored)

Curated from two upstream collections, not installed wholesale. Skills irrelevant
to an Android-only LLM-model manager were left out: `paging`, `coil-compose`,
`kmp-ktor`, `kmp-boundaries`, `rxjava-migration`, `pdf-annotations`,
`android-benchmark-comparison`, `release-kotlin-library`, `compose-focus-navigation`,
and the GitHub-workflow skills.

**From [rcosteira79/android-skills](https://github.com/rcosteira79/android-skills)** — MIT.
Broad coverage of building an Android app.

`android-dev` (baseline router) · `compose` · `android-ux` · `android-data-layer` ·
`android-retrofit` · `datastore` · `koin` · `kotlin-coroutines` · `kotlin-flows` ·
`android-testing` · `android-debugging` · `android-gradle-logic` ·
`gradle-build-performance` · `modularization` · `android-source-search`

**From [chrisbanes/skills](https://github.com/chrisbanes/skills)** — Apache 2.0.
Depth on Compose and Kotlin quality, with androidx source receipts.

`compose-state-and-effects` · `compose-performance` · `compose-component-design` ·
`compose-animations` · `compose-ui-testing-patterns` · `kotlin-api-design` ·
`kotlin-control-flow`

The overlap on Compose is deliberate: `compose` is the broad how-to, the `compose-*`
skills are narrow deep-dives on state, performance, and API shape.

### Local modifications to vendored files

These are **not** pristine copies. Re-vendoring from upstream will revert these:

- **`android-dev`** — routing table rewritten. Upstream mandates a fully-qualified
  `android-skills:` prefix and routes to six skills not vendored here; both were
  broken in this layout. Now routes to plain local names and lists the exclusions.
- **`kotlin-flows`** — corrected a factual error. Upstream claimed
  `launch { emit() }` on a `SharedFlow` means "the effect is never silently lost".
  With a default `MutableSharedFlow()` and **zero subscribers**, `emit` returns
  immediately and the value is dropped. The surrounding recommendation (prefer
  `Channel.receiveAsFlow()` for one-shot effects) was already right; only the
  justification was wrong.

### Deliberately dropped

- **`gradle-run`** (chrisbanes) — mandates a bundled Python wrapper, forbids running
  Gradle directly as a fallback, and requires spawning a persistent "Solver
  diagnostic owner" subagent. That is upstream's own orchestration policy; it would
  block ordinary `./gradlew` use in this repo.
- **`kotlin-concurrency-and-flow`** (chrisbanes) — duplicates `kotlin-coroutines` +
  `kotlin-flows`.

### Updating

These are vendored copies and do not auto-update. Re-copy from upstream (then
re-apply the local modifications above), or switch to the marketplace:

```
/plugin marketplace add rcosteira79/android-skills
/plugin marketplace add chrisbanes/skills
```

## Written for this repo

### Android gaps

Two areas central to this app had no upstream coverage:

- **`android-secure-credentials`** — Keystore AES-GCM + DataStore for provider API
  keys. Covers the `androidx.security:security-crypto` deprecation (all APIs
  deprecated as of 1.1.0), per-provider key aliases, host-scoped auth headers so a
  key is never sent to the wrong provider, backup/log/clipboard exclusion, and
  `KeyPermanentlyInvalidatedException` recovery.
- **`android-llm-streaming`** — SSE consumption: `@Streaming`, frame parsing,
  disabling retry so a failed stream doesn't re-bill a generation,
  `awaitClose { call.cancel() }`, and rendering deltas without a frame per token.

### OpenClaw

No upstream agent skills existed for OpenClaw, so these were written from the
official docs at <https://docs.openclaw.ai>.

- **`openclaw-gateway-client`** — the WebSocket protocol
  (`connect.challenge` → `connect` → hello-ok, `req`/`res`/`event` envelopes),
  Ed25519 device identity and the `PAIRING_REQUIRED` flow, `deviceToken` and
  operator scopes, bind modes and port 18789, `ws://` vs `wss://` host rules,
  `/health` and `/ready` probes, and the `models.list` vs `/v1/models` distinction.
- **`openclaw-skill-authoring`** — OpenClaw's own `SKILL.md` format: frontmatter,
  the seven-tier load precedence, `metadata.openclaw` gating, per-agent allowlists
  (and their limits), ClawHub CLI.
- **`openclaw-plugin-dev`** — TypeScript plugins inside the Gateway: the two
  manifests including the mandatory `configSchema`, `registerTool`,
  `contracts.tools`, optional tools, packaging.

## Provenance and verification

| Item | Value |
|---|---|
| Vendored | 2026-09-24, upstream `main` at clone time, `--depth 1` |
| OpenClaw docs | read 2026-09-24 from `docs.openclaw.ai` |
| OpenClaw version tested against | **none — not yet verified against a running Gateway** |

> **The OpenClaw protocol is on dated pre-release versions and moves.** The gateway
> skill instructs the agent to feature-detect from the hello-ok `features.methods` /
> `features.events` list rather than assume a documented method exists, to probe
> `/readyz` rather than guess endpoints, and to read the current protocol version
> and `device` field names from the handshake docs rather than copy them out of the
> skill. Record the Gateway version once you test against a real one.

An earlier draft of `openclaw-gateway-client` was written from a third-party API
reference and had the envelope shape, auth header, health endpoint, and method
names wrong. It was rewritten against the official `docs.openclaw.ai/gateway/protocol/*`
pages after a cross-review. Treat non-official OpenClaw API references with
suspicion.

## Known gaps

Not covered by any skill here; worth adding when the work comes up:

- **Release signing** — signing configs, upload key vs app signing key, CI secrets
- **CI** — build workflow, lint/test gates, artifact and mapping-file retention
- **R8 / ProGuard** — `android-debugging` covers retracing and keep rules, but there
  is no optimized-release build and validation workflow
- **WorkManager** — `kotlin-coroutines` mentions it; no implementation guidance for
  constraints, unique work, retry, or process-death recovery
- **Notifications and runtime permissions** — relevant only if work continues in the
  background
- **Model binary downloads** — resumable downloads, integrity checks, disk-space
  handling, if the app ever manages on-device weights rather than remote endpoints

## Licenses

Vendored skills retain their upstream licenses — MIT (rcosteira79) and Apache 2.0
(chrisbanes); see the linked repositories for full text.
