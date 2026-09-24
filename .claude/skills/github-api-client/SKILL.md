---
name: github-api-client
description: >
  Use when calling the GitHub API — choosing REST vs GraphQL, OAuth device flow
  for a phone, GitHub App vs OAuth App vs fine-grained PAT, installation tokens
  and their one-hour expiry, primary and secondary rate limits, conditional
  requests with ETags, cursor pagination via the Link header, webhooks vs polling,
  and the pull-request / checks / diff endpoints. Triggers on api.github.com,
  Octokit, GraphQL v4, device flow, installation token, X-RateLimit-Remaining,
  secondary rate limit, Link header, ETag, or any GitHub repository integration.
---

# GitHub API

## Choose the credential first — it constrains everything

| Credential | Use when | Notes |
|---|---|---|
| **GitHub App + installation token** | **Server-side, acting on repos.** | Per-repo scoping, higher limits, revocable per install, no user seat. Tokens expire in **1 hour** — refresh is mandatory, not optional. |
| **OAuth device flow** | User signs in **on the phone**. | No redirect URI, no embedded browser, no client secret on device. Correct for a mobile client. |
| Fine-grained PAT | Scripts, your own repos. | Per-repo, per-permission, expiring. Fine for a spike, wrong to ship. |
| Classic PAT | Never, for new work. | Coarse scopes, often non-expiring. |

**For this product**: the Beelink holds a **GitHub App installation token** and does
all repo work. The phone never sees a repo credential — it authenticates to *your
gateway*, and the gateway acts on GitHub. If a phone is lost, you revoke a device
token (see `openclaw-gateway-client`), not a GitHub credential.

Use device flow only if the user must link their own GitHub identity from the phone.

### Device flow, correctly

```
POST https://github.com/login/device/code         → device_code, user_code, verification_uri, interval
   show user_code + verification_uri on screen
POST https://github.com/login/oauth/access_token  → poll with grant_type=urn:ietf:params:oauth:grant-type:device_code
```

- **Respect `interval`.** Polling faster returns `slow_down`, which *increases* the
  required interval. An impatient loop makes login take longer.
- Handle `authorization_pending` (keep waiting), `slow_down` (back off),
  `expired_token` (restart), `access_denied` (user refused) as distinct states.
- Make `user_code` big, copyable, and unambiguous. Users type it on another device.

## REST or GraphQL

- **GraphQL** when you need a screen's worth of related data — a PR with its reviews,
  checks, and files is one query instead of five REST calls. On a phone that is a
  real latency win.
- **REST** for simple fetches, diffs as raw text, and anything with a dedicated media
  type.
- GraphQL costs are **points, not requests** — 5000 points/hour, and a badly nested
  query burns them fast. Query the `rateLimit { cost remaining }` field during
  development to see what a query actually costs.
- GraphQL returns **HTTP 200 with an `errors` array** on failure. A client that
  checks only the status code will treat a failed query as an empty result.

## Rate limits — two of them

**Primary**: 5000/hr authenticated user, 15000/hr for a GitHub App installation on
an Enterprise org, 60/hr unauthenticated.

```
X-RateLimit-Limit / -Remaining / -Reset / -Used / -Resource
```

Check `X-RateLimit-Resource` — `core`, `search`, and `graphql` have **separate
budgets**. Exhausting search does not affect core.

**Secondary** limits are the ones that actually bite: too many concurrent requests,
too many points in a short burst, too many content-creating requests. They return
403 or 429 with `Retry-After` and a "secondary rate limit" message.

- **Honour `Retry-After` exactly.** Retrying early extends the penalty.
- **Never fire concurrent writes.** Serialise anything that creates or mutates.
- On 403, distinguish permission-denied from secondary-limit by inspecting the body
  message — same status code, opposite remedies.

## Conditional requests are free

`ETag` responses that return **304 Not Modified do not count against the rate
limit.** For a polling client this is the difference between viable and not.

```kotlin
val req = Request.Builder().url(url)
    .apply { etagStore[url]?.let { header("If-None-Match", it) } }
    .build()
// 304 → serve cache, no quota consumed
```

Use `If-Modified-Since` where no ETag is offered. Cache per URL *and* per
credential — never let one user's cached response serve another.

## Pagination — the Link header

```
Link: <https://api.github.com/...?page=2>; rel="next", <...>; rel="last"
```

**Parse the header; never construct `page+1` yourself.** Several endpoints have
moved to opaque cursors where a synthesised page number silently returns wrong
results. `per_page` maxes at 100.

## Endpoints for this product

```
GET  /repos/{o}/{r}/pulls                      list PRs
GET  /repos/{o}/{r}/pulls/{n}                  PR detail
GET  /repos/{o}/{r}/pulls/{n}/files            changed files (paginated, 3000 file cap)
POST /repos/{o}/{r}/pulls/{n}/reviews          submit a review
GET  /repos/{o}/{r}/commits/{sha}/check-runs   CI status
POST /repos/{o}/{r}/pulls                      open a PR
```

**Diffs**: request `Accept: application/vnd.github.diff` on the PR endpoint for raw
unified diff, or `.patch` for a mail-formatted patch. Do not reconstruct a diff from
the files list — see `mobile-diff-review`.

Large PRs truncate: `/files` caps at 3000 files, and individual patches are omitted
above a size threshold. Detect truncation and say so rather than rendering a
plausible-but-incomplete diff.

## Webhooks beat polling

If the Beelink is reachable (it is — see `remote-server-connectivity`), take
webhooks for PR and check events and push to the phone via FCM
(`android-push-and-background`). Polling GitHub from a phone is quota you do not
need to spend and latency you do not need to accept.

- **Verify `X-Hub-Signature-256`** with HMAC-SHA256 and a constant-time compare.
  An unverified webhook endpoint is an unauthenticated command channel into your
  agent runtime.
- Webhooks redeliver. Dedupe on `X-GitHub-Delivery`.

## Traps

- **REST and GraphQL node IDs differ** and have been re-formatted before. Do not
  store a GraphQL `id` and use it in a REST path.
- **`/user/repos` is not `/users/{u}/repos`** — the first is your accessible repos
  including private, the second is someone's public ones.
- **Installation tokens expire in one hour.** Refresh proactively; a long agent run
  will outlive its token mid-task.
- **Abuse-prevention wants a `User-Agent`.** Requests without one are rejected.
- A merge that returns 405 usually means required checks failed, not a bad request.

## Checklist

- [ ] Credential chosen deliberately; repo credentials live on the server, not the phone
- [ ] Device flow respects `interval` and handles `slow_down` / `expired_token`
- [ ] GraphQL `errors` array checked despite HTTP 200
- [ ] `X-RateLimit-Resource` distinguished; secondary limits handled via `Retry-After`
- [ ] Writes serialised, never concurrent
- [ ] ETag conditional requests on anything polled
- [ ] Pagination follows `Link`, never synthesised page numbers
- [ ] Diff fetched via `Accept:` media type; truncation detected and surfaced
- [ ] Webhook signatures verified constant-time; deliveries deduped
- [ ] Installation token refreshed before expiry, mid-run
