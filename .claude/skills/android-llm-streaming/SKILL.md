---
name: android-llm-streaming
description: >
  Use when consuming a streaming LLM response on Android — server-sent events (SSE)
  from an OpenAI-compatible or Anthropic endpoint, `data:` frame parsing, `[DONE]`
  sentinels, bridging the stream into a Flow, cancelling a generation so the server
  actually stops, why streaming requests must not be auto-retried, OkHttp timeouts
  for long-lived responses, and rendering token deltas in Compose without burning a
  frame per token. Triggers on SSE, EventSource, text/event-stream, @Streaming,
  ResponseBody.source(), callbackFlow, "stream the response", "tokens arrive", or
  a chat UI that stutters while generating.
---

# Streaming LLM responses on Android

Streaming is the default interaction for this app, and it breaks the assumptions
Retrofit, OkHttp, and Compose are each tuned for. Four things go wrong: the request
gets buffered, the retry duplicates a paid generation, cancellation doesn't reach
the server, and the UI recomposes per token.

## Do not let Retrofit buffer it

A normal Retrofit method with a converter reads the whole body before returning —
which defeats streaming entirely and looks like "the API is slow". Use `@Streaming`
and take the raw body:

```kotlin
interface CompletionsApi {
    @Streaming
    @POST("v1/chat/completions")
    suspend fun stream(@Body request: CompletionRequest): Response<ResponseBody>
}
```

Then read incrementally from `body.source()`. Do not call `body.string()` — that is
the buffering bug in one method call.

OkHttp's `okhttp-sse` `EventSource` is a reasonable alternative and handles frame
parsing for you; prefer it when the server is well-behaved SSE. Hand-parse when you
need access to non-standard fields or the server's framing is loose.

## Timeouts: the read timeout must not apply

The default OkHttp read timeout (10s) will kill a generation mid-thought as soon as
the model pauses. Use a **separate client instance** for streaming:

```kotlin
val streamingClient = baseClient.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)      // no idle-read cap
    .callTimeout(0, TimeUnit.MILLISECONDS)      // no total-call cap
    .connectTimeout(30, TimeUnit.SECONDS)       // still bound the connect
    .retryOnConnectionFailure(false)            // see below
    .build()
```

Keep connect timeouts. Only the read/call caps are the problem. Do not apply
`readTimeout(0)` to your normal client — you want short timeouts everywhere else.

Enforce your own inactivity watchdog instead: if no frame arrives for N seconds,
fail the stream deliberately. An unbounded read with no watchdog is a hang.

## Retries are not safe here

`retryOnConnectionFailure(true)` and any interceptor-level retry will **re-issue a
POST that already started generating**. The user is billed twice, and may see the
answer restart. This is the most expensive bug in the category and it is on by
default.

- Disable automatic retry on the streaming client.
- Retry only a stream that failed **before the first token**, and treat that as a
  product decision, not a transport detail.
- After the first token, a failure is a partial result: surface it as "response
  interrupted" with what you have, and let the user choose to regenerate.
- If the provider supports an idempotency key, send one.

## Parsing SSE frames

Wire format is line-oriented, frames separated by a blank line:

```
data: {"choices":[{"delta":{"content":"Hel"}}]}

data: {"choices":[{"delta":{"content":"lo"}}]}

data: [DONE]
```

Rules that bite:

- **`data:` can repeat within one event** — concatenate the payload lines with `\n`
  before parsing. A single-line assumption drops content on providers that wrap.
- **`[DONE]` is a literal sentinel, not JSON.** Check for it before deserialising,
  or the parser throws at the end of every successful stream.
- **Ignore comment lines** (starting `:`) — some servers use them as keepalives.
- **A partial line is not a frame.** Read by line from a buffered source; never
  parse a fixed-size byte chunk.
- Frames may carry an error object mid-stream after a 200 OK. A successful HTTP
  status does not mean a successful generation — inspect payloads.
- Use a lenient deserializer (`ignoreUnknownKeys = true` in `kotlinx.serialization`)
  — providers add fields without warning.

## Bridging to a Flow, with real cancellation

Cancellation must close the HTTP call, or the server keeps generating and keeps
billing after the user hit stop.

```kotlin
fun streamCompletion(request: Request): Flow<StreamEvent> = callbackFlow {
    val call = okHttpClient.newCall(request)
    val job = launch(Dispatchers.IO) {
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    close(StreamHttpException(response.code, response.message))
                    return@use
                }
                val source = response.body!!.source()
                val buffer = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()   // handles \n and \r\n
                    when {
                        line.isEmpty() -> {                  // frame boundary
                            emitFrame(buffer.toString())?.let { send(it) }
                            buffer.clear()
                        }
                        line.startsWith(":") -> Unit         // comment/keepalive
                        line.startsWith("data:") ->
                            // SSE strips exactly ONE leading space, not all whitespace
                            buffer.append(line.removePrefix("data:").removePrefix(" "))
                                  .append('\n')
                    }
                }
                close()
            }
        } catch (e: IOException) {
            if (isActive) close(e)                           // cancellation is not an error
        }
    }
    awaitClose {
        call.cancel()
        job.cancel()
    }
}.flowOn(Dispatchers.IO)
```

Four details that are easy to get wrong here:

- **Use `send`, not `trySend`.** `trySend` fails silently when the channel buffer is
  full and returns a `ChannelResult` nobody checks — which drops tokens and corrupts
  the response. We are inside a coroutine, so `send` can suspend and apply
  backpressure. Only use `trySend` where dropping is acceptable; it never is for
  deltas.
- **Don't use Retrofit's `HttpException` here** — it takes a `retrofit2.Response`,
  not an OkHttp one. Use your own exception type.
- **Strip one leading space, not `.trim()`.** The SSE spec removes a single space
  after `data:`. For JSON payloads `.trim()` happens to be harmless, but on
  providers that stream raw text it eats significant whitespace — and LLM deltas are
  frequently `" the"`.
- **The `IOException` catch must not report an error when cancelled.** Cancelling an
  OkHttp call throws; reporting it produces a spurious error every time the user
  navigates away.

`readUtf8LineStrict` handles `\n` and `\r\n`. A strictly spec-compliant parser also
accepts a lone `\r` as a terminator — rare from real servers, but if you hit a
provider that does it, you will see one giant never-terminating frame.

### Cancellation is best-effort

`call.cancel()` closes the connection, which is the signal that lets a provider stop
generating — most do. It is **not a guarantee**: the request already reached the
model, and whether generation and billing actually stop is the provider's behaviour,
not something the client controls. Do not promise the user "stopped, not charged."
If exact accounting matters, reconcile against the provider's usage API.

Collect it lifecycle-aware, and decide deliberately whether a generation should
survive backgrounding. If it should, own it in the ViewModel's scope (or a service)
and let the UI re-attach — do not let a `repeatOnLifecycle` collector silently abort
a paid generation when the user checks a notification.

## Rendering without a frame per token

Tokens arrive faster than 60fps and each one is a state write. Naive
`state.value += delta` in a `LazyColumn` item recomposes and re-measures the whole
message list per token.

- **Accumulate first, *then* throttle — never throttle the delta stream.**
  `conflate()` and `sample()` **drop** emissions. Applied to a flow of deltas they
  silently delete text. The order matters:

  ```kotlin
  // WRONG — drops deltas, produces corrupt text
  deltaFlow.conflate().collect { state.append(it) }

  // RIGHT — accumulate to a cumulative snapshot, then drop redundant snapshots
  deltaFlow
      .runningFold(StringBuilder()) { acc, delta -> acc.append(delta) }
      .map { it.toString() }        // cumulative text, each value self-contained
      .conflate()                   // safe: dropping a stale snapshot loses nothing
      .collect { text -> uiState.update { it.copy(streamingText = text) } }
  ```

  Because each emission is the *full* text so far, skipping one is free. Then make
  sure the **final** value is always rendered — `conflate` can drop the last item if
  the collector is mid-frame, so emit a terminal "complete" event carrying the final
  text rather than relying on the last throttled snapshot.
- **Hoist the streaming text into its own small composable** so recomposition is
  scoped to the text node, not the list item, the bubble, and the timestamp.
- **Give list items stable `key`s** so `LazyColumn` does not rebuild the world when
  the last item grows.
- **String concatenation is O(n²) over a long response.** `StringBuilder` in the
  collector; convert to `String` once per published frame.
- **Auto-scroll deliberately.** Scrolling to bottom on every delta fights the user
  the moment they scroll up. Track "is pinned to bottom" and only follow then.

If the streaming text is still janky, that is a recomposition-scope problem — load
`compose-performance` rather than guessing.

## Accessibility

A screen reader announcing every token is unusable. Do not put streaming text in a
live region per delta. Announce once when generation starts ("generating response"),
then announce the completed message, and expose a "stop generating" control that is
reachable and labelled. `compose-ui-testing-patterns` covers asserting this.

## Testing

- Serve canned SSE from `MockWebServer` with a chunked body and explicit delays —
  that is the only way to test framing, `[DONE]`, and mid-stream errors.
- Assert that cancelling the collector cancels the `Call`: `MockWebServer` will
  record the disconnect.
- Include a fixture with a **multi-line `data:`**, one with a comment keepalive, and
  one that errors after 200 OK. Those three are where hand-rolled parsers fail.
- Use a `TestScope` virtual clock for the inactivity watchdog rather than real
  delays.

## Checklist

- [ ] `@Streaming` + incremental source read; no `body.string()`
- [ ] Separate client with read/call timeouts disabled, connect timeout kept
- [ ] Automatic retry disabled; post-first-token failures surface as partial
- [ ] Multi-line `data:`, comments, and `[DONE]` handled before JSON parsing
- [ ] `awaitClose { call.cancel() }` present; `send` not `trySend` for deltas
- [ ] Cancellation does not surface as an error to the user
- [ ] Accumulate **then** conflate — never `conflate`/`sample` a delta stream
- [ ] Final text guaranteed rendered via a terminal event, not the last snapshot
- [ ] Streaming text in its own composable; list items keyed
- [ ] Auto-scroll respects manual scroll-up
- [ ] MockWebServer fixtures for framing, keepalive, and mid-stream error
