---
name: terminal-log-streaming-ui
description: >
  Use when rendering an agent's terminal or log output in the Android app —
  parsing ANSI escape sequences into styled spans, handling carriage returns and
  progress bars that rewrite a line, capping and windowing huge scrollback,
  follow-tail versus manual scroll, search within output, copy and share, and
  keeping a 200k-line build log from exhausting memory. Triggers on ANSI, escape
  sequence, [, CSI, SGR, scrollback, "logs are laggy", "output jumps",
  carriage return, progress bar, tail, LazyColumn of log lines, or any console
  view.
---

# Rendering terminal output on a phone

This is a different problem from `android-llm-streaming`. LLM tokens are a slow
append-only stream of prose. Build output is a fast, bursty stream of
**structured bytes** that rewrite themselves, and a single `./gradlew build` can
emit hundreds of thousands of lines.

## Two hard constraints

1. **Volume.** A log can exceed available memory. Holding every line as a styled
   `AnnotatedString` will OOM.
2. **Rewriting.** Terminal output is not append-only. `\r` returns to column
   zero, and progress bars overwrite the same line thousands of times. Naive
   append yields 4000 copies of one progress bar.

Everything below follows from those two.

## Parse ANSI, don't strip it and don't render it raw

Three wrong answers: render escape codes literally (`[32m` everywhere), strip
them entirely (losing the red that marks the failure), or feed them to a
WebView (heavy, and an injection surface for agent-controlled text).

Parse a useful subset into styled spans:

```kotlin
data class LogLine(
    val seq: Long,                 // matches the session event cursor
    val spans: List<Span>,
    val level: Level,              // derived: ERROR | WARN | INFO
)

data class Span(val text: String, val fg: Color?, val bg: Color?, val bold: Boolean)
```

What to support, in priority order:

- **SGR (`ESC[…m`)** — colours and bold. The 90% case. Handle the 8 basic
  colours, their bright variants, 256-colour (`38;5;n`), and truecolour
  (`38;2;r;g;b`). `ESC[0m` resets.
- **Erase line (`ESC[2K`, `ESC[K`)** — paired with `\r` by progress bars.
- **Cursor up (`ESC[nA`)** — multi-line progress displays rewrite upward.

What to ignore safely: alternate screen buffers, full cursor addressing, mouse
reporting. A phone log view is not a terminal emulator; if a tool needs one, run
it non-interactively instead.

**Never interpret OSC sequences** (`ESC]…`) — they can set window titles and, in
some terminals, trigger actions. This text comes from an agent that may have
read an attacker-controlled file. Discard them.

### Carriage return is the one everyone gets wrong

```kotlin
// Within a chunk, \r means "restart this line"
fun applyCarriageReturn(current: String, chunk: String): String =
    chunk.substringAfterLast('\r').ifEmpty { chunk }
```

Treat a line as **mutable until its terminating `\n` arrives**. A progress bar
is one line updated in place, and must occupy one row in the list — not four
thousand. Get this wrong and both memory and scroll position are destroyed.

## Cap the buffer — decide the policy explicitly

```kotlin
class LogBuffer(private val maxLines: Int = 5_000) {
    private val lines = ArrayDeque<LogLine>()
    fun append(line: LogLine) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }
}
```

- **Keep the tail, drop the head.** The end of a log is where the failure is.
- **Say so in the UI.** A banner — "earlier output truncated, 182,405 lines
  hidden" — with a fetch-from-server action. Silent truncation makes users
  believe output they can't see never existed.
- **The server keeps the full log**, not the phone (see
  `agent-session-multiplexing`). The phone holds a window; "download full log"
  goes to the server.
- **Store plain text, style on render.** An `AnnotatedString` per line costs far
  more than a `String` plus span offsets. Parse to spans lazily in the composable.

## Rendering

- **`LazyColumn` with a stable key** per line (`seq`, not index). Index keys
  break every time the head is trimmed.
- **Fixed line height** and `contentType` so the list can reuse rows without
  re-measuring.
- **Never wrap by default.** Terminal output is column-aligned; wrapping destroys
  stack traces and tables. Scroll horizontally, with wrap as a toggle.
- **Monospace, and respect the user's font scale** — but cap the maximum, since
  very large text makes column-aligned output unreadable. Offer a size control.
- **Batch appends.** Output arrives in bursts; recomposing per line is wasted
  frames. Accumulate and publish at frame cadence — accumulate *then* conflate,
  never conflate a delta stream (`android-llm-streaming` covers why).

### Follow-tail

```kotlin
val atBottom by remember {
    derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        last != null && last.index >= listState.layoutInfo.totalItemsCount - 2
    }
}
LaunchedEffect(lines.size) { if (atBottom) listState.scrollToItem(lines.lastIndex) }
```

- **Auto-scroll only while pinned to the bottom.** The instant a user scrolls up
  they are reading something; yanking them back is infuriating.
- Show a **"jump to latest"** affordance with a new-line count when detached.
- Use `scrollToItem`, not `animateScrollToItem`, for a fast stream — animations
  queue up and lag behind.
- Re-pin when the user scrolls back to the bottom.

## Make it useful, not just visible

- **Filter by level** — errors only, warnings and above. On a phone this is the
  difference between usable and not.
- **Search** with match count and prev/next. Searching a windowed buffer must be
  explicit about scope: "42 matches in the last 5,000 lines" — offer a
  server-side search over the full log.
- **Tappable file:line** — jump to the diff (`mobile-diff-review`). Build output
  is mostly references to source.
- **Copy** a line, a selection, or the whole visible buffer. Users will want to
  paste a stack trace elsewhere.
- **Collapse repeated lines** — "× 340" beats 340 identical rows.

## Accessibility

A live region announcing every log line is unusable. Mark the log
`liveRegion = LiveRegionMode.Polite` at most, announce only level transitions
("build failed"), and make sure colour is never the sole error signal — prefix a
marker or use an icon, since red-on-black fails contrast for many users anyway.

## Security

Agent output is **untrusted**. It can contain anything a compiler, a test, or a
malicious file emits.

- Render as **text only** — no HTML, no markdown links, no clickable URLs from
  log content without explicit user action.
- Strip OSC and any sequence that isn't a known-safe SGR/erase code.
- **Redact secrets before display.** Build logs leak tokens constantly
  (`Authorization: Bearer …`, `ghp_…`, `sk-…`). Run a redaction pass on the
  server before the log reaches the phone — see `android-secure-credentials`.
- Cap line length. A single 10 MB line with no newline will hang the layout pass;
  truncate with an expand affordance.

## Testing

- Replay a recorded `./gradlew build` log — the realistic input, including its
  progress bars.
- A fixture with `\r`-driven progress: assert it occupies **one** line.
- Truncated escape sequences split across chunk boundaries (`ESC[3` then `1m`) —
  the parser must be resumable across reads.
- A 200k-line log: assert bounded memory and no dropped tail.
- Invalid UTF-8 mid-sequence — tools emit it; the parser must not throw.

## Checklist

- [ ] ANSI SGR parsed to spans; OSC and unknown sequences discarded
- [ ] Parser resumable across chunk boundaries
- [ ] `\r` rewrites the current line; progress bars occupy one row
- [ ] Buffer capped, tail-preserving, truncation visible with a fetch action
- [ ] Lines stored as text; styling applied at render
- [ ] `LazyColumn` keyed by `seq`; fixed line height
- [ ] No wrapping by default; horizontal scroll
- [ ] Auto-scroll only when pinned; "jump to latest" when detached
- [ ] Appends batched to frame cadence
- [ ] Filter, search (with explicit scope), copy, collapse-repeats
- [ ] Secrets redacted server-side; long lines truncated
- [ ] Colour is never the only error signal
