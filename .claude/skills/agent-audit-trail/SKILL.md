---
name: agent-audit-trail
description: >
  Use when recording what an agent did and who authorised it — designing the
  append-only event log, what must be captured for an action to be
  reconstructable, tamper-evidence, linking an approval to the action it
  authorised, attributing a commit to an agent plus a human approver, redacting
  secrets before they are persisted, retention, and answering "why is this code
  in my repo". Triggers on audit, audit log, provenance, attribution,
  "who approved this", "what did the agent do", tamper-evident, hash chain,
  Co-Authored-By, retention, or any compliance or forensics question about
  agent activity.
---

# Auditing what the agent did

Three agents from three vendors are pushing to your repositories while you are
asleep. Six months later a line of code is wrong and the question is: *what
produced this, what was it asked, and who said yes?*

If the answer is "scroll the chat history", there is no audit trail.

## What an audit record is for

Not debugging — debugging is what logs are for. An audit record answers, for
any change that left the machine:

1. **What** was done — the exact action, not a paraphrase.
2. **Which agent** did it, which model and version, at which effort.
3. **What it was asked** — the prompt/mission that led here.
4. **Who authorised it**, when, and from which device.
5. **What happened** — exit code, files touched, commit sha.

Anything that cannot answer all five is a log line, not an audit record.

## Append-only, server-side, separate from the app

The Beelink writes the trail. The phone is a *viewer* and a *source of approval
events* — never the store.

- **Append-only.** No updates, no deletes. A correction is a new record that
  references the old one.
- **Separate storage** from the working data. If an agent can `rm -rf` the
  workspace, it must not be able to touch the audit store — different directory,
  different permissions, ideally a different process's ownership.
- **Write before acting**, not after. A record written only on success loses
  exactly the events you most need: the ones that crashed or were killed.

The ordering is: record `intent` → execute → record `outcome`, linked by id. An
intent with no outcome is itself a finding.

## The record

```kotlin
data class AuditRecord(
    val id: AuditId,
    val seq: Long,                    // monotonic, gapless per store
    val at: Instant,                  // server clock — never the phone's
    val kind: Kind,                   // INTENT | OUTCOME | APPROVAL | DENIAL | CONFIG_CHANGE
    val actor: Actor,                 // AGENT(harness, model, version) | HUMAN(deviceId) | SYSTEM
    val sessionId: SessionId,
    val project: ProjectId,
    val repo: RepoRef?,               // repo + branch + worktree
    val action: ActionDetail,         // the literal argv / API call / diff hash
    val actionHash: String,           // same hash the approval bound to
    val approvalId: ApprovalId?,      // present iff this required authorisation
    val outcome: Outcome?,            // exit code, sha, error — on OUTCOME records
    val prevHash: String,             // hash chain — see Tamper-evidence
)
```

Two fields carry most of the weight:

- **`actionHash`** is the join to `hitl-approval-flows`. The approval signed a
  hash; the execution record carries the same hash. That pair is what proves the
  user approved *this* action and not a similar one.
- **`prevHash`** makes the log tamper-evident.

**Use the server clock.** Phone clocks are wrong and user-settable; an approval
timestamped from the device is not evidence.

## Tamper-evidence without a blockchain

Hash-chain the records:

```kotlin
fun hash(r: AuditRecord): String =
    sha256(r.prevHash + r.seq + r.at + r.kind + r.actor + r.actionHash + r.outcome)
```

Each record commits to the previous. Removing or editing any record breaks the
chain from that point on, and verification is a single pass.

This detects tampering; it does not *prevent* it — someone with write access can
rewrite the whole chain. To raise that bar, periodically publish the current head
hash somewhere append-only the agent cannot reach (a signed commit in a separate
repo, a log service, an emailed digest). Anchoring the head daily means at most a
day's worth of history can be silently rewritten.

Don't over-build this. A hash chain plus an off-box anchor is proportionate for a
personal build server; a distributed ledger is not.

## Redact before writing, not before reading

Agent output leaks credentials constantly — a `curl -v`, an env dump, a build
log with `Authorization: Bearer …`.

- **Redact on the write path.** A secret that reaches an append-only store cannot
  be removed, by definition.
- Pattern-match the known shapes (`ghp_`, `github_pat_`, `sk-`, `xox[baprs]-`,
  JWT triplets, PEM blocks, `Authorization:` values) *and* the specific
  credentials you know you hold — the app knows its own tokens, so it can redact
  them exactly rather than by guesswork.
- **Store a hash of the redacted value**, so you can still answer "was this the
  same token?" without storing the token.
- A record whose redaction is uncertain should be marked as such, not silently
  passed through.

This is the one place where "we'll clean it up later" is impossible. Get it right
before the first write.

## Attribution in git

The audit store answers questions for you. Git metadata answers them for anyone
who clones the repo later — and it must be honest.

- **Author** = the human who owns the work. **`Co-Authored-By:`** the agent:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  ```
- Put the **audit id** in the commit trailer, so a commit links back to the full
  record:
  ```
  Agent-Session: sess_01J…
  Audit-Ref: aud_01J…
  ```
- **Never impersonate a human.** An agent commit authored as a person, with no
  co-author trailer, is the failure mode that destroys trust in the whole system
  — and it is indistinguishable from that person having written it.
- If commits are signed, be clear about **whose key** signs an agent commit. A
  human's GPG key on unreviewed agent output asserts something untrue.

## Retention and access

- **Decide a retention period and enforce it.** Indefinite retention of full
  prompts and diffs is a growing liability, not diligence.
- **Tier it**: full detail (prompts, diffs, output) for weeks; the record
  skeleton (who/what/when/approval/sha) for far longer. The skeleton is small and
  answers most questions.
- **Expiry must preserve the hash chain** — replace an expired record's payload
  with a tombstone carrying its hash, rather than deleting the row.
- Reading the audit log is itself an event worth recording if anyone but you
  will use this.

## What the UI must show

The trail is worthless if it is only queryable by SQL.

- **Per session**: a timeline of intents, approvals, and outcomes.
- **Per commit**: "which agent, which prompt, who approved" — reachable from the
  diff view (`mobile-diff-review`).
- **Unapproved actions** — anything executed with no `approvalId` that should
  have had one. This is the alert, not a filter.
- **Chain verification status**, shown plainly: verified as of *timestamp*.
- **Export** — a signed bundle for a specific session or date range.

## Testing

- Verify the chain across restarts and concurrent writers.
- Kill the process between intent and outcome; assert the dangling intent is
  visible rather than lost.
- Feed known credential shapes through the write path; assert none are persisted.
- Tamper with a record in storage; assert verification fails at the right seq.
- Expire a record; assert the chain still verifies via the tombstone.
- Assert clock skew from a client cannot set a record's timestamp.

## Checklist

- [ ] Append-only store, server-side, isolated from agent-writable paths
- [ ] Intent written **before** execution; outcome linked by id
- [ ] Server clock only
- [ ] `actionHash` joins execution to its approval
- [ ] Hash chain, with the head anchored off-box on a schedule
- [ ] Redaction on the write path; hashes stored instead of secrets
- [ ] Agent attribution via `Co-Authored-By:` — never impersonating a human
- [ ] Audit/session ids in commit trailers
- [ ] Tiered retention; expiry preserves the chain via tombstones
- [ ] UI surfaces unapproved actions and chain-verification status
- [ ] Export produces a verifiable bundle
