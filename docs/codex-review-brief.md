You are doing an independent second-opinion review. Be blunt and specific; disagree where warranted. Do not modify any files.

## Context

This repo is the skill layer for an Android app that acts as a **mobile control plane for multi-agent coding work**:

- Steers Claude (Claude Code), ChatGPT (Codex), and Gemini as long-running coding agents — not chat.
- Those agents work together on the same repositories, hosted on **GitHub and Azure DevOps**.
- The agents and code actually execute on a **Beelink mini-PC on the user's LAN**. The phone is a remote control, not the compute.
- The server-side runtime is **not yet decided** — see `docs/architecture-decision.md`, which recommends running OpenClaw as the transport/identity/approval backbone with a custom orchestration plugin, but the spike hasn't been run.

`.claude/skills/` now holds 37 skills. 26 of them (23 vendored Android/Kotlin + 3 OpenClaw) were already reviewed by you in an earlier session and corrected. **Those are not in scope here.**

## In scope — the 10 unreviewed skills

These were written in one pass and have had **no review at all**:

1. `remote-server-connectivity`
2. `android-push-and-background`
3. `agent-session-multiplexing`
4. `azure-devops-api-client`
5. `github-api-client`
6. `hitl-approval-flows`
7. `multi-provider-llm-apis`
8. `terminal-log-streaming-ui`
9. `mobile-diff-review`
10. `agent-audit-trail`

Also in scope: `docs/architecture-decision.md`.

## Your task

### 1. Factual accuracy — the priority

For each skill, verify claims against current primary sources. Quote the specific line and cite the doc when you disagree. **Flag anything that looks hallucinated, version-fragile, or that was true 18 months ago and isn't now.**

Specific claims I am least confident about — check these first:

- **`azure-devops-api-client`**: that global PATs stop working **1 Dec 2026** and new-creation was blocked **15 Mar 2026**; that Azure DevOps OAuth (non-Entra) is deprecated with no new registrations since April 2025; that PAT auth is `base64(":" + pat)` with an empty username; that `api-version` is mandatory on every request; that work-item writes require `application/json-patch+json`. Also: is the org-scoped vs global PAT distinction I drew actually correct?
- **`github-api-client`**: installation-token 1-hour expiry; that 304s don't count against rate limit; that `/files` caps at 3000 files; that secondary rate limits return 403 *or* 429; that `X-RateLimit-Resource` separates core/search/graphql budgets.
- **`multi-provider-llm-apis`**: the Claude details came from the bundled `claude-api` skill and should be right, but check the **OpenAI and Gemini** sides — role names, `systemInstruction`, `functionCall`/`functionResponse` shapes, the `data: [DONE]` sentinel, `?alt=sse`, and whether `max_completion_tokens` has superseded `max_tokens` anywhere I said otherwise.
- **`android-push-and-background`**: Android 14 `foregroundServiceType` + typed permission requirement; that `dataSync` is capped/stoppable; that FCM payload limit is 4KB; that high-priority messages pierce Doze; that `POST_NOTIFICATIONS` stops prompting after two dismissals.
- **`terminal-log-streaming-ui`** and **`mobile-diff-review`**: these are more design than API, but check the concrete claims — ANSI sequence forms, `@@` header count-omission, `\ No newline at end of file` handling, Trojan Source/bidi mitigation advice.

### 2. Security review

`hitl-approval-flows` and `agent-audit-trail` describe security mechanisms. Attack them:

- Is the `actionHash` bind-at-approval / verify-at-execution scheme actually sound as described? What does it fail to cover?
- Is the hash-chain + off-box anchor proportionate, or is it security theatre at this scale?
- Does "redact on the write path" survive contact with reality, or will secrets leak anyway?
- Anything in these two that would give a false sense of safety is a **critical** finding, more important than a factual slip.

### 3. Contradictions and overlap

These 10 cross-reference each other and the pre-existing 26. Find:

- Direct contradictions between two skills.
- Advice in a new skill that contradicts a vendored one (especially `android-llm-streaming` vs `terminal-log-streaming-ui` on streaming/rendering, and `android-secure-credentials` vs `agent-audit-trail` on secret handling).
- Dead cross-references to skills that don't exist.
- Genuine redundancy where two skills should be merged.

### 4. What's still missing

Given the product described above, what does this 37-skill set still not cover that will bite early? Be ruthless — name only things that are genuinely load-bearing, not a wish list. I already know about: release signing, CI, R8, server-side orchestration/worktree lifecycle.

### 5. Architecture opinion

Read `docs/architecture-decision.md`. It recommends OpenClaw-as-backbone + custom plugin over a from-scratch server or DevFleet.

- What is the **strongest argument against** that recommendation that the doc does not already make?
- The doc notes OpenClaw's own Android app has no git/repo integration, and treats that as the differentiator. Is that differentiator as real as claimed, or is it a thin wrapper around work OpenClaw will absorb?
- Is the 2-day spike plan the right sequence?

## Output format

Per skill: **KEEP / FIX / DROP**, then the specific findings with file path, line number, and the quoted text you dispute. Rank findings by severity — security and factual errors above stylistic ones. End with sections 3, 4, and 5 as prose.

Do not modify files. If a claim is correct, say so briefly rather than padding — I care about what's wrong.
