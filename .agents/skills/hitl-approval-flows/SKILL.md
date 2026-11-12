---
name: hitl-approval-flows
description: >
  Use when the user must approve an agent action from the phone — designing the
  approval request and its integrity guarantees, deciding what must be shown
  before a human can meaningfully consent, binding an approval to the exact
  action it authorised, expiry and replay protection, allow-listing and
  "don't ask again" scopes, biometric gating for destructive operations, and
  what happens when an approval times out or the phone is offline. Triggers on
  approval, permission request, "agent is asking", confirm, allowlist,
  auto-approve, "yolo mode", destructive command, force push, or any UI where a
  tap causes code to run on the server.
---

# Human-in-the-loop approval

A tap on a phone causes an agent on the Beelink to run a command with your GitHub
and Azure DevOps credentials. This is the highest-consequence surface in the
product. Treat it as an authorisation protocol, not a dialog.

## The failure mode to design against

Users approve everything. After the twentieth prompt, approval becomes reflex, and a
prompt that says "Agent wants to run a command" trains exactly that reflex. An
approval system that is noisy is worse than none, because it manufactures consent
without informing it.

Two consequences:

1. **Ask rarely.** Most actions should be pre-authorised by scope, not prompted.
2. **When you do ask, show the specific thing** — not a category.

## The request

```kotlin
data class ApprovalRequest(
    val id: ApprovalId,
    val sessionId: SessionId,
    val agent: Harness,                 // who is asking
    val project: ProjectId,
    val kind: ActionKind,               // SHELL | GIT_PUSH | FILE_WRITE | NETWORK | MERGE | …
    val summary: String,                // one line, human, server-authored
    val detail: ActionDetail,           // the EXACT payload: argv, diff, target ref
    val risk: Risk,                     // LOW | MEDIUM | DESTRUCTIVE
    val requestedAt: Instant,
    val expiresAt: Instant,
    val actionHash: String,             // hash of the exact action — see Binding
)
```

**`detail` must be the literal action.** For a shell command that means the exact
argv, the working directory, and the worktree/branch. "Agent wants to run a git
command" is not consent; `git push --force origin main` in `/srv/work/api-feat` is.

## Binding: approve *this*, not "whatever happens next"

The classic vulnerability is time-of-check/time-of-use — the user approves command A,
and the server executes command B.

- The server computes `actionHash` over the **canonical, fully-resolved action** and
  includes it in the request.
- The client returns that hash with the approval.
- The server **re-derives the hash at execution time and refuses if it differs.**

This makes an approval a signature over a specific action rather than a general
permission. It also means the phone can display exactly what will run and be right.

Do not let the server "helpfully" re-plan after approval. A re-planned action is a
new action and needs a new approval.

## Expiry and replay

- **Every request expires** — minutes, not hours. An approval tapped from a
  notification an hour later is approving a stale world state.
- **Single use.** The server marks it consumed; a replayed approval is rejected and
  logged as an anomaly (see `agent-audit-trail`).
- **Show the countdown** in the UI. An expired card must visibly become expired, not
  sit there looking actionable.
- If the user was offline and it expired, say so and offer to re-request — never
  silently re-approve.

## Risk tiers drive the interaction

| Risk | Examples | Interaction |
|---|---|---|
| `LOW` | read a file, run tests, `git status` | pre-authorised by scope; no prompt, visible in the log |
| `MEDIUM` | write a file, commit, open a PR | one tap, summary + detail |
| `DESTRUCTIVE` | `push --force`, delete branch, `rm -rf`, prod deploy, credential access | **biometric confirm** and full detail, never from the notification shade |

For `DESTRUCTIVE`, require `BiometricPrompt` — not because the attacker is
necessarily someone else holding the phone, but because it breaks the reflex. The
extra second is the entire point.

Never allow a destructive approval to be actionable directly from a notification
action button. Force the app open, where the full detail is visible.

## Scopes beat repetition

"Don't ask again" must be **scoped and revocable**, never global:

```kotlin
data class StandingGrant(
    val kind: ActionKind,
    val project: ProjectId,          // never "all projects"
    val session: SessionId?,         // null = project-wide
    val expiresAt: Instant,          // ALWAYS bounded
    val constraints: List<Constraint> // e.g. branch != main, path prefix
)
```

- Grants always expire. A permanent grant is an un-auditable standing privilege.
- Grants are **per project**, at most. Never per-account.
- A "run tests freely in this session" grant is genuinely useful and low risk. A
  "push freely" grant is not — some things should always prompt regardless of what
  the user asks for.
- Show active grants somewhere reachable, with one-tap revoke. If the user cannot
  see what they have granted, they have not really granted it.

## Offline and timeout

The agent is blocked while waiting. Decide the default and make it explicit:

- **Default must be deny-on-timeout.** An approval that defaults to "yes" after 10
  minutes is not an approval.
- The blocked session stays `Blocked` (see `agent-session-multiplexing`) and the
  work is preserved — timing out must not discard the agent's context.
- Queue approvals made offline and deliver on reconnect, **but re-validate expiry
  server-side**. A queued approval for an expired request is rejected, not honoured.
- If several approvals stack up, do **not** offer "approve all". Batch approval is
  reflex approval with a nicer name.

## What the UI must show

Before any approval the user needs, without scrolling:

1. **Which agent** and which project/branch — with three agents running, this is not
   obvious.
2. **The literal action.**
3. **Why** — the agent's stated reason, server-supplied.
4. **What it touches** — file paths, target ref, whether it is remote-affecting.
5. **Reversibility.** "This cannot be undone" is the single most useful sentence on
   the screen, and you can derive it from `kind`.

Render untrusted content as **text, never as markup**. The summary and reason come
from an LLM, which means they are attacker-influenceable if the agent ever reads a
malicious file or issue. A prompt-injected "reason" that renders a fake
approve-button, or styles itself to look like a system message, is a real attack on
this screen. Escape everything and keep chrome visually distinct from content.

## Testing

- Approve, then have the server alter the action → must refuse on hash mismatch.
- Replay an approval → rejected and audited.
- Approve after expiry → rejected.
- Approve from a notification for a `DESTRUCTIVE` action → must force the app open.
- Kill the app while an approval is pending → still pending after relaunch, never
  auto-resolved.
- Inject markup and control characters into `summary`/reason → renders inert.

## Checklist

- [ ] Request carries the literal, fully-resolved action
- [ ] `actionHash` bound at approval and re-verified at execution
- [ ] Re-planned actions require fresh approval
- [ ] Short expiry, single use, replay rejected and logged
- [ ] Risk tiers; biometric for destructive; no destructive action from the shade
- [ ] Standing grants scoped per project, bounded, listed and revocable
- [ ] Deny-on-timeout; blocked work preserved
- [ ] No "approve all"
- [ ] Agent-supplied text rendered inert; chrome distinguishable from content
- [ ] Every decision written to the audit trail (`agent-audit-trail`)
