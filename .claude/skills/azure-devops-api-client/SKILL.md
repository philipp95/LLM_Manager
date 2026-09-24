---
name: azure-devops-api-client
description: >
  Use when calling the Azure DevOps REST API — building request URLs against
  dev.azure.com/{org}/{project}/_apis, the mandatory api-version parameter,
  choosing between PAT and Microsoft Entra ID OAuth (and the global-PAT
  retirement deadline), Basic-auth PAT encoding, continuation-token paging,
  repos / pull requests / pipelines / work item endpoints, and on-prem Azure
  DevOps Server version mapping. Triggers on dev.azure.com, _apis, api-version,
  Azure DevOps, ADO, VSTS, TFS, personal access token, Entra ID, MSAL,
  x-ms-continuationtoken, or any Azure DevOps repository integration.
---

# Azure DevOps REST API

Azure DevOps looks like GitHub from a distance and is different in almost every
mechanical detail. Do not port a GitHub client by search-and-replace.

## URL shape — `api-version` is mandatory

```
VERB https://dev.azure.com/{organization}/{project}/_apis/{area}/{resource}?api-version={version}
```

- `{project}` is **optional** depending on the endpoint — org-level resources
  (projects, users) omit it; repo and pipeline resources usually need it.
- **`api-version` is required on every single request.** Omitting it does not default
  to latest; it fails, or silently behaves as an old version. This is the single most
  common first-hour mistake.
- Current version for Azure DevOps Services is **7.2**. Preview resources use
  `7.2-preview.1` style suffixes, and the preview number is **per-resource** — two
  endpoints in the same area can require different preview suffixes.

Centralise it so it cannot be forgotten:

```kotlin
private const val API_VERSION = "7.2"

fun adoUrl(org: String, project: String?, path: String, version: String = API_VERSION) =
    HttpUrl.Builder()
        .scheme("https").host("dev.azure.com")
        .addPathSegment(org)
        .apply { project?.let { addPathSegment(it) } }
        .addPathSegment("_apis")
        .addPathSegments(path)
        .addQueryParameter("api-version", version)
        .build()
```

Better: an OkHttp `Interceptor` that appends `api-version` when absent, so a
hand-built URL cannot ship without it.

**On-prem is a different instance shape**: `{server:port}/tfs/{collection}/_apis/…`,
and the api-version is pinned to the server release (Server 2022 → 7.0, 2020 → 6.0,
2019 → 5.0). If this project must support on-prem Azure DevOps Server, the API
version becomes **per-connection configuration**, not a constant. Ask before
assuming Services-only.

## Authentication — the deadline matters

| Method | Status |
|---|---|
| **Microsoft Entra ID OAuth** | **Use this for new work.** Microsoft's recommendation. |
| Entra service principal / managed identity / workload identity federation | Right answer for a server-side integration with no interactive user. |
| Org-scoped PAT | Still works. Long-lived bearer secret — treat accordingly. |
| **Global PAT** | **Retiring.** New creation and regeneration blocked since 15 March 2026; they **stop working 1 December 2026**. |
| Azure DevOps OAuth (the old, non-Entra one) | **Deprecated.** No new app registrations since April 2025; full deprecation 2026. Do not start here. |

Given today's date, anything built on global PATs has weeks of life left. Verify the
current state of these deadlines before committing — they have moved before.

**For this product** the Beelink is the one holding credentials, not the phone (see
`hitl-approval-flows` and `agent-audit-trail`). That makes an Entra **service
principal with workload identity federation** the right target: no secret at rest,
no expiry surprise, and revocation is centralised. A PAT in a config file on a
mini-PC is the thing to migrate away from, not toward.

### PAT encoding — the trap

PAT auth is **HTTP Basic with an empty username**:

```
Authorization: Basic base64(":" + PAT)
```

Note the leading colon. Encoding `base64(PAT)` alone, or `base64(user:PAT)` with a
real username, both fail with a 401 that says nothing useful. Many tools Base64
for you — do not double-encode.

```kotlin
val header = "Basic " + Base64.encodeToString(":$pat".toByteArray(), Base64.NO_WRAP)
```

`NO_WRAP` matters — Android's default inserts newlines, which produces an invalid
header.

### The 401-that-is-really-a-redirect

An unauthenticated or wrongly-authenticated request to Azure DevOps frequently
returns **HTTP 203 with an HTML sign-in page**, not a 401. A client that only checks
`isSuccessful` will hand HTML to a JSON parser and report a deserialization bug.

Guard explicitly: if `Content-Type` is not JSON, treat it as an auth failure
regardless of status code.

## Paging — continuation tokens, not page numbers

Azure DevOps pages with an opaque token in a **response header**:

```
x-ms-continuationtoken: <opaque>
```

Pass it back as `continuationToken` in the query string. There is no total count, no
`Link` header, no page index.

```kotlin
suspend fun <T> pageAll(fetch: suspend (String?) -> Pair<List<T>, String?>): List<T> {
    val all = mutableListOf<T>()
    var token: String? = null
    do {
        val (items, next) = fetch(token)
        all += items
        token = next
    } while (token != null && all.size < HARD_CAP)   // always cap
    return all
}
```

- **Always cap.** A malformed token that echoes itself is an infinite loop that
  drains a phone battery.
- `$top` limits page size; it is **not** a total limit.
- Responses are `{ "value": [...], "count": n }` — `count` is the count *in this
  page*, not the total. Treating it as a total is a common bug.

## Endpoints you will actually need

```
GET  _apis/projects                                     org's projects
GET  {project}/_apis/git/repositories                   repos
GET  {project}/_apis/git/repositories/{repoId}/pullrequests
POST {project}/_apis/git/repositories/{repoId}/pullrequests     create PR
GET  {project}/_apis/git/repositories/{repoId}/diffs/commits    diff between refs
GET  {project}/_apis/build/builds                       pipeline runs
GET  {project}/_apis/wit/workitems?ids={csv}            work items (batched!)
POST {project}/_apis/wit/wiql                           work item query
```

Two quirks worth knowing before you design screens around them:

- **Work items are fetched by id list, not by filter.** You run a WIQL query to get
  ids, then batch-fetch. Two round trips, always. The batch is capped (200) — chunk it.
- **Work item updates use JSON Patch** (`application/json-patch+json`) with
  `/fields/System.Title`-style paths, not a normal JSON body. A plain PATCH body is
  rejected.
- Branch refs are full: `refs/heads/main`, not `main`, in most parameters.

## Rate limits

Azure DevOps throttles on **Test Units (TSTUs)**, not request counts, so a few
expensive queries can throttle you faster than many cheap ones.

- Honour `Retry-After` on 429. It is authoritative — do not substitute your own
  backoff.
- `X-RateLimit-Remaining` / `X-RateLimit-Limit` appear when you are near the cap.
- WIQL queries are expensive. Cache aggressively; do not poll them from a phone.

## Errors

Error bodies are `{ "message": "...", "typeKey": "...", "errorCode": n }`.
`typeKey` is the stable discriminator — branch on it, not on the human-readable
`message`, which is localised and changes.

## Checklist

- [ ] `api-version` on every request, enforced by an interceptor
- [ ] API version configurable if on-prem Server must be supported
- [ ] PAT encoded as `base64(":" + pat)` with `NO_WRAP` — or, better, Entra OAuth
- [ ] Not building on global PATs (retiring 1 Dec 2026)
- [ ] Non-JSON content-type treated as auth failure, whatever the status code
- [ ] Continuation-token paging with a hard cap; `count` not mistaken for a total
- [ ] Work item writes use `application/json-patch+json`
- [ ] Full `refs/heads/…` ref names
- [ ] `Retry-After` honoured on 429
- [ ] Errors branched on `typeKey`, not `message`
