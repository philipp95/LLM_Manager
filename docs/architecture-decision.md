# Runtime architecture: what runs on the Beelink

Status: **proposed, not decided** · 2026-09-24

## The product

An Android app that steers Claude, Codex, and Gemini as they work on repositories
in GitHub and Azure DevOps. Code executes on a Beelink mini-PC on the LAN. The
phone is a control plane, not the compute.

## What already exists (and why that matters)

Before choosing, it is worth being precise about what you would be rebuilding.

### OpenClaw's official Android app

OpenClaw already ships a native Android app. It is a *companion node* to a Gateway
running on desktop. It does:

- device pairing, multi-Gateway, automatic reconnection
- chat with synced history, voice input, camera commands
- **push notifications for pending requests and approvals**
- **approval workflows** for admin-level operators, answered via native cards
- durable offline queue (50 messages / 48 MB per Gateway)
- Wear OS companion

It explicitly does **not** do:

- **any git or repo integration** — no source control operations
- code editing — workspace files are **read-only**
- Gateway hosting on the phone
- background device commands

Reception has been mixed; the Android build is described as rougher than iOS.

**Read that list twice.** Everything hard about *mobile* — pairing, auth, scopes,
push, approvals, offline queue, reconnection — is solved. Everything about *your
actual product* — repos, PRs, diffs, multi-agent project steering — is absent.

### ECC's claude-devfleet

A separate project ([LEC-AI/claude-devfleet](https://github.com/LEC-AI/claude-devfleet)),
surfaced through ECC's `claude-devfleet` skill:

- FastAPI + SQLite backend on **:18801**, React UI on 3100/3101
- dispatches agents into **isolated git worktrees**, auto-merges on completion
- missions form a **DAG**; a Mission Watcher auto-dispatches when deps are satisfied
- **SSE** output streaming, structured reports
- MCP tools: `plan_project`, `dispatch_mission`, `get_mission_status`, `get_report`,
  `get_dashboard`, …
- **Claude only** (other MCP clients can orchestrate it, but not run as agents)
- max 3 concurrent agents by default
- **auth unspecified** — appears to assume localhost trust

The worktree-per-agent model is the right answer to "two agents touch one repo" and
worth copying regardless of which path you take.

## The three options

### A. OpenClaw Gateway as the runtime

Phone talks the OpenClaw WS protocol; orchestration lives in an OpenClaw plugin.

**For:** inherits pairing, device identity, operator scopes, approvals, push, offline
queue, reconnect — the six hardest mobile problems, already solved and already
documented in `.claude/skills/openclaw-*`. Claude/Codex/Gemini are already modelled
as swappable agent harnesses.

**Against:** you are building a second Android client for a system that has one. You
inherit a **fast-moving pre-release protocol** (dated beta versions; our own skills
had to be rewritten once already when a third-party API reference proved wrong) and
a plugin API on dated betas. A breaking change upstream breaks your app. You are
also constrained to OpenClaw's session model, which is chat-centric, not
project-centric.

### B. Custom server agent on the Beelink

You design the API; the app is its only client.

**For:** exact fit to project/repo/mission semantics. No upstream to track. Free
choice of transport (gRPC streaming is a genuinely good fit here).

**Against:** you personally rebuild device pairing, token issuance and rotation,
scope enforcement, push delivery, approval integrity, offline queueing, and
reconnect-and-resync. That is *most of a year* of the work OpenClaw already did, and
every one of those is a security-sensitive surface where a mistake exposes a machine
that holds your GitHub and Azure DevOps credentials.

### C. DevFleet + thin client

**For:** orchestration already solved — worktrees, DAG, auto-merge, SSE, dashboard.

**Against:** Claude-only, so Codex and Gemini need building anyway; no Azure DevOps;
no mobile auth story at all. Exposing an unauthenticated FastAPI on a LAN that your
phone reaches from outside is not acceptable without putting something in front of it.

## Recommendation: A, with a custom orchestration plugin

Run **OpenClaw on the Beelink as the transport, identity, and approval backbone**,
and put your product logic in an **OpenClaw plugin** that owns projects, repos, and
multi-agent missions. Build the custom Android app against that.

```
Android app (custom, project-centric)
   │  OpenClaw WS :18789  — pairing, deviceToken, scopes, approvals, push
   ▼
OpenClaw Gateway (Beelink)
   ├── your plugin: projects · missions · repo binding · agent fan-out
   │        └─ worktree-per-agent (borrowed from DevFleet's model)
   ├── harness plugins: Claude Code · Codex · Gemini
   └── GitHub + Azure DevOps clients
```

Why this split:

1. **You do not rebuild the security-critical mobile plumbing.** Pairing, scopes,
   token rotation, and approval delivery are exactly where a homegrown version goes
   wrong, and the blast radius is a machine holding your source credentials.
2. **The plugin is where your product actually lives** — and `contracts.tools` plus
   custom Gateway RPC is a legitimate extension point, not a hack.
3. **The differentiator is real and unclaimed.** OpenClaw's app has *no git
   integration*. A project-centric, repo-aware, multi-agent Android client is not a
   reskin of it.
4. **Worst case is survivable.** If OpenClaw's protocol churn becomes intolerable,
   the plugin boundary is where you cut: the orchestration logic is yours, and only
   the transport needs replacing.

### What would change my mind

- If you want **Azure DevOps as a first-class citizen early** — nothing upstream
  helps, and the plugin boundary may just add friction.
- If **agent fan-out across three vendors** turns out to fight OpenClaw's session
  model rather than fit it. Spike this before committing.
- If the **plugin API breaks you twice** in the first month.

### Decide by spiking this, not by reading

Two days, in this order:

1. Install OpenClaw on the Beelink. Pair the **official** Android app. Ask it to do
   something on a repo. Find out exactly where it stops being enough. *This is the
   cheapest possible answer to "should I build this at all."*
2. Write a trivial OpenClaw plugin registering one tool that shells out to
   `codex exec` in a git worktree. If that works, option A is validated end to end.
3. Only then start the app.

## Open questions

- **Off-LAN access**: Tailscale is the strong default (OpenClaw supports tailnet
  bind and Tailscale Serve, and `wss://` is mandatory for public hosts). WireGuard
  is the alternative. See `remote-server-connectivity`.
- **Azure DevOps auth**: PAT is easy and bad (long-lived, broad); Entra ID OAuth is
  correct and more work. Decide before writing the client.
- **Who holds repo credentials** — the Beelink, surely, never the phone. The phone
  should never see a GitHub token; it asks the server to act.
- **Concurrency limit**: DevFleet caps at 3 agents. Whatever a Beelink sustains for
  three parallel coding agents is an empirical question — measure it.
