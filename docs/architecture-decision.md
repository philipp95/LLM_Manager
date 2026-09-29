# Architecture status

Status: **CLI prototype implemented; production runtime undecided** · 2026-09-29

## Product direction

An Android app will steer coding agents working on repositories. Code runs on
a separate computer; the phone is the control interface.

## What is implemented

The repository currently contains four Kotlin/JVM modules:

- `core/protocol`: serializable IDs, usage, and a proposed WebSocket envelope.
- `server/domain`: framework-independent run specifications and statuses.
- `server/runner`: a Claude Code process harness, sanitized JSONL events,
  bounded process execution, and Git worktree management.
- `server/spike`: a local CLI that demonstrates one agent run.

There is no Ktor server, OpenClaw plugin, database, Android module, pairing
service, or externally reachable API. Protocol types and unused catalog entries
are preparation, not implemented transport or storage.

## Current execution model

The CLI resolves a repository to a bare cache keyed by its full identity and
verifies the origin on reuse. Fetch updates remote-tracking refs, leaving active
agent branches alone. Each run creates a separate branch and worktree.

The harness streams text, tool calls, usage, and sanitized diagnostics. Decoded
JSON is redacted before events leave the parser. Raw diagnostic streams retain
redaction state across PEM lines. Malformed JSON is withheld when it cannot be
sanitized reliably. Redaction is defense in depth, not a guarantee that arbitrary
unknown credential formats can be recognized.

Process execution drains both pipes while enforcing a deadline. It tracks and
terminates observed descendants on timeout, interruption, and normal exit, and
bounds output cleanup. Process-tree observation is not OS containment: a process
that deliberately detaches before observation requires platform job objects,
process groups, or a container. Add that containment before running untrusted
repositories or exposing remote execution.

A run produces one terminal event after process completion. Timeouts and nonzero
process exits cannot be overwritten by an earlier success message. Worktree
cleanup runs on failure unless preservation was explicitly requested.

## Production runtime decision

The original proposal favored OpenClaw for transport, device identity, and
approvals. The standalone spike validates the agent execution boundary without
committing to that choice. A custom server and an OpenClaw integration remain
alternatives; the current code does not validate either production architecture.

Before adding a remote control interface, decide and test:

- runtime ownership and deployment boundaries;
- pairing, authentication, authorization, and approval integrity;
- durable events, recovery, and reconnect semantics;
- credential isolation and OS-level process containment;
- multi-provider orchestration and repository concurrency limits.

Skills in `.agents/skills/` provide implementation guidance. They describe
target practices, not features already delivered by this prototype.
