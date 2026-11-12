---
name: agent-session-multiplexing
description: >
  Use when the app tracks several long-running agent sessions at once — modelling
  concurrent Claude / Codex / Gemini runs, attaching and detaching from a running
  session, resuming after the app is killed, reconciling client state against the
  server on reconnect, per-session event cursors and backfill, cancelling a run,
  and deciding what is authoritative when phone and server disagree. Triggers on
  session, run, mission, attach, detach, resume, reconnect, backfill, cursor,
  sequence number, "which agent is running", "state is stale after reopening",
  or any screen listing more than one agent.
---

# Multiplexing agent sessions

The phone is a **viewport onto server-held state**, never the owner of it. Every
bug in this area comes from forgetting that: caching a run's status locally,
treating a missed event as "it didn't happen", or letting the UI's idea of
"running" diverge from the server's.

## The rule

> The server owns session state. The client holds a **cache plus a cursor**, and
> reconciles on every connect.

If the phone is destroyed, nothing is lost. If the phone shows something the server
disagrees with, the server wins — always, without a merge prompt.

## Model

```kotlin
@JvmInline value class SessionId(val value: String)

data class AgentSession(
    val id: SessionId,
    val harness: Harness,              // CLAUDE_CODE | CODEX | GEMINI
    val project: ProjectId,
    val repo: RepoRef,                 // which repo + which worktree/branch
    val status: Status,
    val lastEventSeq: Long,            // the cursor — see Backfill
    val startedAt: Instant,
    val title: String,                 // human label, not the raw prompt
)

sealed interface Status {
    data object Queued : Status
    data class Running(val activity: String?) : Status   // "editing src/Foo.kt"
    data class Blocked(val request: ApprovalRequest) : Status   // needs a human
    data class Done(val outcome: Outcome) : Status
    data class Failed(val error: String) : Status
    data object Cancelled : Status
}
```

`Blocked` is a first-class status, not a flavour of `Running`. It is the only one
that demands the user's attention *right now*, it drives the high-priority
notification channel (`android-push-and-background`), and it must be impossible to
miss in a list of twelve sessions. Sort and badge on it.

Keep `harness` explicit rather than abstracting it away. The three behave
differently enough — see `multi-provider-llm-apis` — that a leaky common
abstraction costs more than a `when`.

## Attach / detach

Attaching must never mutate the run. A user opening a session to look at it must not
restart it, re-prompt it, or change its dispatch.

```kotlin
sealed interface Attachment {
    data object Detached : Attachment                  // not subscribed; push only
    data class Live(val since: Long) : Attachment      // subscribed, streaming
}
```

- **Subscribe only to what is on screen.** Twelve concurrent sessions streaming
  output into a phone is wasted battery and bandwidth for eleven of them.
- **Detach on background.** Keep the cursor; drop the stream.
- **One subscription per session**, refcounted. Two composables observing the same
  session must not open two streams — that is the classic source of doubled output.

## Backfill: cursors, not timestamps

Every session event carries a monotonic sequence number. The client stores the
highest seq it has durably processed and asks for everything after it:

```kotlin
suspend fun attach(id: SessionId, from: Long): Flow<SessionEvent> =
    transport.subscribe(id, afterSeq = from)   // server replays, then streams live
```

- **Never use wall-clock time as the cursor.** Phone and server clocks differ, and
  the phone's can jump. Sequence numbers are the only sound answer.
- **Detect gaps.** If an event arrives with `seq > lastSeq + 1`, you missed
  something: re-request from `lastSeq` rather than rendering a hole. Silent gaps are
  how a user ends up reading a transcript that never mentions the file that broke.
- **Persist the cursor only after the event is durably stored**, or a crash between
  render and write loses events permanently.
- **Bound the backfill.** After a week offline, do not replay 400k lines. Ask for a
  summary plus the tail, and offer "load full history" on demand.

## Resuming after process death

Android will kill the app. Recovery must be indistinguishable from a normal open:

1. On start, load known sessions from the local store (Room) — instant UI, marked
   stale.
2. Connect and call the server's session list.
3. **Reconcile**, server-authoritative:
   - session on server, not local → insert
   - local, not on server → it ended and was reaped; mark terminal, never delete
     silently (the user may be looking at it)
   - status differs → **take the server's**
4. Backfill from each cursor for anything the user is viewing.

Do not show a merge conflict UI. There is no conflict: the server is right.

## Cancellation is a request, not a fact

`cancel()` sends an intent. The agent may be mid-tool-call, may take seconds, may
have already finished, may ignore it.

- Move to a distinct `Cancelling` state; do not jump straight to `Cancelled`.
- Only the **server's** terminal event may set `Cancelled`.
- Offer escalation (force-kill) only after a timeout, and make clear it may leave
  the worktree dirty.
- A cancelled run still produced work — link to its partial diff rather than
  discarding it. See `mobile-diff-review`.

## Concurrency limits

The Beelink is the constraint, not the app. Three parallel coding agents is a
plausible ceiling (DevFleet defaults to 3); measure rather than assume.

- The **server** enforces the cap. The client shows queue position.
- `Queued` must be visible and explained — "waiting for a slot" — or users will
  assume the app is broken and start more.
- Show what is consuming the slots so the user can choose what to cancel.

## Two agents, one repo

This is where multi-agent coding actually breaks. Do not let two agents write the
same working tree.

- **One git worktree per session** is the proven pattern (DevFleet, and see
  `agent-orchestration-worktrees` if present). Shared history, isolated checkout.
- The session model must therefore carry its worktree/branch, and the UI must show
  it — "Codex is on `feat/auth`, Claude is on `feat/api`" is essential context, not
  a detail.
- Merge/integration is a **separate, explicit, human-gated step**. Never auto-merge
  two agents' branches without review; that is how you get plausible code that
  compiles and is wrong.

## Testing

- Kill the process mid-stream (`adb shell am kill`); assert the transcript after
  relaunch is complete and gap-free.
- Drop the network mid-run; assert reconnect backfills exactly the missed events,
  with no duplicates and no holes.
- Deliver events out of order and duplicated — the transport will do this — and
  assert the cursor logic is idempotent.
- Run twelve sessions; assert only visible ones hold subscriptions.
- Simulate server-says-done while client-says-running; assert the server wins with
  no prompt.

## Checklist

- [ ] Server authoritative; client is cache + cursor
- [ ] `Blocked` is a distinct status, badged and sorted first
- [ ] Attach never mutates the run; detach on background
- [ ] Refcounted single subscription per session
- [ ] Monotonic seq cursors, never timestamps; gaps detected and refilled
- [ ] Cursor persisted only after durable event write
- [ ] Backfill bounded, with explicit "load more"
- [ ] Reconcile on connect, server wins, no merge UI
- [ ] `Cancelling` distinct from `Cancelled`; only server confirms terminal
- [ ] One worktree per session; worktree/branch visible in UI
- [ ] Integration of two agents' work is human-gated
