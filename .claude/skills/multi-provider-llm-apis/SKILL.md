---
name: multi-provider-llm-apis
description: >
  Use when code must speak to more than one LLM vendor — Anthropic (Claude),
  OpenAI, and Google (Gemini) — and the differences matter: where the system
  prompt goes, how tool calls and tool results are shaped, reasoning/thinking
  blocks, streaming event formats, auth headers, model id strings, and stop
  reasons. Covers designing one internal domain model that all three map onto
  without a lossy lowest-common-denominator. Triggers on Claude vs GPT vs
  Gemini, anthropic-version, x-api-key, tool_use, tool_calls, functionCall,
  content_block_delta, "data: [DONE]", systemInstruction, adaptive thinking,
  reasoning tokens, or any provider-abstraction layer.
---

# Talking to Claude, OpenAI, and Gemini

These three APIs look similar from a distance and differ in almost every
mechanical detail. The common mistake is an abstraction that flattens them to
their intersection — plain text in, plain text out — which throws away tool
calls, reasoning, and citations, i.e. everything that makes an agent an agent.

**Model the union, not the intersection.** Define your own domain types, then
write one adapter per vendor. Adapters may return `null` for capabilities a
vendor lacks; they must never silently drop content.

> **Where this runs.** In this project the Beelink holds the provider keys and
> makes these calls — the phone never talks to a provider directly (see
> `android-secure-credentials`). This skill is about the server's provider
> layer, or about an on-device client only if that decision is ever revisited.

## The four differences that actually bite

| | Claude | OpenAI | Gemini |
|---|---|---|---|
| **Auth header** | `x-api-key: <key>` **plus** `anthropic-version: 2023-06-01` | `Authorization: Bearer <key>` | `x-goog-api-key: <key>` (or `?key=`) |
| **System prompt** | top-level `system` param — **not** a message | a message with role `system`/`developer` | top-level `systemInstruction` |
| **Turn roles** | `user` / `assistant` | `user` / `assistant` | `user` / **`model`** |
| **Tool args** | `input` — already a **parsed object** | `arguments` — a **JSON string you must parse** | `args` — parsed object |

The system-prompt row causes the most bugs: code that appends a `system`
message to Claude's `messages` array gets a 400, and code that sets
Anthropic's top-level `system` on OpenAI silently sends no system prompt at all.

Anthropic's `anthropic-version` header is easy to forget and its absence is a
hard failure, not a default.

## Request shapes

**Claude** — `POST https://api.anthropic.com/v1/messages`. `max_tokens` is
**required**:

```json
{
  "model": "claude-opus-5",
  "max_tokens": 16000,
  "system": "You are a build agent.",
  "thinking": { "type": "adaptive" },
  "output_config": { "effort": "high" },
  "messages": [{ "role": "user", "content": "..." }],
  "tools": [{ "name": "run_tests", "description": "...", "input_schema": { } }]
}
```

**OpenAI** — `POST /v1/chat/completions`. System is a message; tools are wrapped
in a `{"type": "function", "function": {...}}` envelope.

**Gemini** — `POST /v1beta/models/{model}:generateContent`. `contents[].parts[]`
rather than `content`, `systemInstruction` separate, tools under
`tools[].functionDeclarations[]`.

## Content is a list of typed blocks — preserve it

Claude returns `content` as an **array of blocks**, and a response routinely
contains several:

```json
{ "type": "thinking", "thinking": "..." }
{ "type": "text",     "text": "..." }
{ "type": "tool_use", "id": "toolu_…", "name": "run_tests", "input": { } }
```

Never do `content[0].text` — with thinking on, index 0 is a `thinking` block.
Filter by `type`.

Your domain model should be a list of blocks too:

```kotlin
sealed interface Block {
    data class Text(val text: String) : Block
    data class Reasoning(val summary: String?) : Block   // may be unreadable
    data class ToolCall(val id: String, val name: String, val args: JsonObject) : Block
}
```

A `String` response type is the wrong shape and you will pay for it later.

## Reasoning blocks — the biggest divergence

| | Claude | OpenAI | Gemini |
|---|---|---|---|
| Enable | `thinking: {type: "adaptive"}` | reasoning-model specific | thinking config |
| Visible? | summary only, **opt-in** | summary only | summary only |
| In history | **echo back unchanged** | model-specific | model-specific |

Claude specifics worth pinning down:

- **`display` defaults to `"omitted"`** on current models — the block is present
  but its text is empty. If you render reasoning, you must ask:
  `thinking: {type: "adaptive", display: "summarized"}`. To a streaming UI the
  default looks like a long silent pause.
- **`budget_tokens` is removed** on current models and returns 400. Depth is
  `output_config.effort` (`low`/`medium`/`high`/`xhigh`/`max`), not a token count.
- **`temperature`, `top_p`, `top_k` are rejected** on current Claude models.
  Steer with the prompt. An abstraction that always forwards a `temperature` will
  400 on Claude and work on the other two — a classic one-vendor-only bug.
- **Echo thinking blocks back unchanged** when continuing on the same model.
  Editing or reconstructing them breaks the turn.

None of the three returns a raw chain of thought. Do not build a feature that
depends on reading one.

## Tool calls

**Claude** — `tool_use` block, `input` already parsed. Results go back as a
**`user` message** containing `tool_result` blocks:

```json
{ "role": "user", "content": [
  { "type": "tool_result", "tool_use_id": "toolu_…", "content": "42 passed" }
]}
```

Errors set `"is_error": true` on the block. **Return all results from one
assistant turn in a single user message** — splitting them trains the model out
of parallel tool calls.

**OpenAI** — `tool_calls`, and `function.arguments` is a **JSON string**:

```kotlin
val args = Json.parseToJsonElement(call.function.arguments)  // required
```

Results go back as separate messages with `role: "tool"` and `tool_call_id`.

**Gemini** — a `functionCall` part with parsed `args`; results are
`functionResponse` parts inside a `user`-role content.

Three different envelopes for the same idea. Normalise at the adapter boundary:
parse OpenAI's string args there, so nothing downstream has to know.

## Streaming

All three use SSE; the payloads share nothing.

**Claude** emits typed events:

```
event: message_start
event: content_block_start      {"content_block": {"type": "text"|"thinking"|"tool_use"}}
event: content_block_delta      {"delta": {"type": "text_delta"|"thinking_delta"|"input_json_delta"}}
event: content_block_stop
event: message_delta            {"delta": {"stop_reason": "..."}, "usage": {...}}
event: message_stop
```

Note `index` on block events: **blocks interleave**, so you must accumulate per
index, not into one buffer. Tool arguments arrive as `input_json_delta`
fragments that are **not valid JSON until the block closes** — concatenate, then
parse at `content_block_stop`.

**OpenAI** sends `choices[0].delta` objects terminated by a literal
`data: [DONE]` sentinel that is not JSON. **Gemini** needs `?alt=sse` and emits
whole `GenerateContentResponse` objects.

Claude has **no `[DONE]` sentinel** — `message_stop` is the terminator. A parser
written for OpenAI will hang on Claude waiting for a sentinel that never comes.

See `android-llm-streaming` for the client-side framing and rendering mechanics.

## Stop reasons

Claude's `stop_reason`: `end_turn`, `max_tokens`, `stop_sequence`, `tool_use`,
`pause_turn`, `refusal`.

Two that have no OpenAI/Gemini equivalent and are easy to mishandle:

- **`refusal`** — arrives as a normal **HTTP 200**. `content` may be empty or
  partial. **Check `stop_reason` before reading `content`**, or code that does
  `content[0]` crashes on a refusal. `stop_details` carries a category but may be
  `null` — branch on `stop_reason`, never on `stop_details`.
- **`pause_turn`** — a server-side tool loop hit its iteration cap. Resend the
  conversation with the assistant turn appended to continue. Do **not** add a
  "Continue." user message; the API resumes on its own. Cap resumes so a stuck
  loop cannot run forever.

Map all of these into one domain enum with an explicit `Other(raw)` case — a
`when` that silently swallows an unknown stop reason will hide the next one
a vendor adds.

## Model ids — never construct them

Use exact strings. Do not append date suffixes to aliases, and do not build ids
by pattern.

Current Claude ids: `claude-opus-5`, `claude-sonnet-5`, `claude-haiku-4-5`,
`claude-fable-5`. A wrong id is a 404, and a *plausible* wrong id (a guessed
date suffix) is the hardest version of that bug to spot.

**Do not hardcode a model list in the app.** For Claude, `GET /v1/models`
returns live ids plus `max_input_tokens`, `max_tokens`, and a `capabilities`
tree — query it rather than shipping a table that goes stale. All three vendors
have a list-models endpoint; use them, and cache with a TTL.

**Model ids are not portable.** Whatever mapping you build (`"fast"` →
per-vendor id) must be per-vendor; there is no shared naming scheme.

## Token counting

Do not estimate with `tiktoken` for Claude — it is OpenAI's tokenizer and
undercounts Claude by a wide margin, more so on code. Claude has
`POST /v1/messages/count_tokens`; use it, and pass the same model id you will
use for inference (counts are model-specific).

Budgeting and cost display must be per-provider. One "tokens" number across
three vendors is wrong in three different ways.

## Errors and retries

| Status | Meaning | Retry? |
|---|---|---|
| 400 | malformed / unsupported param for that model | no — fix the request |
| 401 | bad key, or Claude: OAuth token sent via `x-api-key` | no |
| 429 | rate limited | yes, honour `retry-after` |
| 500 / 529 | server error / overloaded | yes, backoff |

The official SDKs retry 408/409/429/5xx with backoff by default — don't wrap
your own retry loop around that or you multiply the attempts.

**Per-model 400s are the migration trap.** `temperature` and `budget_tokens`
are accepted on older Claude models and rejected on current ones. A request
built from a shared config will work on one model and 400 on another with no
code change — validate per-model at the adapter, not globally.

## Designing the abstraction

- **One adapter per vendor**, each owning its own request builder, SSE parser,
  and error mapper. No `if (provider == OPENAI)` in shared code.
- **Normalise at the boundary.** Parse OpenAI's string tool args, map roles
  (`assistant` ↔ `model`), and move the system prompt to the right place inside
  the adapter.
- **Keep the raw response** alongside the normalised one, at least in debug
  builds. When a vendor adds a block type you don't model, you want the bytes.
- **Capabilities are per-model, not per-vendor.** "Supports tools" varies within
  a vendor's lineup. Query the model, don't assume the brand.
- **Don't abstract away what only one vendor has.** Anthropic effort levels or
  a vendor-specific safety control belong in a vendor-specific options object
  that passes through, not in a lowest-common-denominator façade.

## Checklist

- [ ] `anthropic-version` header present on every Claude request
- [ ] System prompt routed correctly per vendor (top-level vs message)
- [ ] Roles mapped (`assistant` ↔ Gemini `model`)
- [ ] Content handled as a block list; never `content[0].text`
- [ ] `thinking.display: "summarized"` set if reasoning is shown
- [ ] Thinking blocks echoed back unchanged on continuation
- [ ] `temperature`/`top_p`/`budget_tokens` not sent to current Claude models
- [ ] OpenAI tool args JSON-parsed at the adapter
- [ ] Claude tool results batched into one user message
- [ ] Streaming accumulates per block `index`; tool JSON parsed at block stop
- [ ] `[DONE]` expected for OpenAI only; `message_stop` terminates Claude
- [ ] `stop_reason` checked **before** reading content; `refusal` and `pause_turn` handled
- [ ] Model ids from a live list endpoint, never constructed
- [ ] Token counts per-provider; no `tiktoken` for Claude
