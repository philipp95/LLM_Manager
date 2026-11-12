---
name: mobile-diff-review
description: >
  Use when showing code changes or reviewing a pull request on a phone screen —
  parsing unified diff into hunks, split versus inline layout at narrow widths,
  syntax highlighting inside a diff, huge and binary and generated files,
  word-level intra-line highlighting, navigating file-to-file and hunk-to-hunk,
  leaving review comments, and approving or requesting changes. Triggers on
  unified diff, @@ hunk header, patch, side-by-side, review comment, approve PR,
  "show me the changes", or any screen rendering a diff.
---

# Reviewing diffs on a phone

A diff is designed for an 80–120 column terminal. A phone gives you ~40
columns. You cannot shrink your way out of that — the layout has to change.

The goal is not "GitHub's diff view, smaller." It is: **can the user decide
whether this change is correct, on a train, in two minutes?**

## Layout: inline, not split

Side-by-side needs two code columns plus gutters — roughly 100 columns. On a
phone it produces two unreadable 18-character columns.

**Use a unified/inline view.** It is the only layout that works at this width,
and it happens to be how diffs read natively.

```
  fun connect(url: HttpUrl) {
-     val client = OkHttpClient()
+     val client = streamingClient
      return client.newCall(...)
```

- Mark added and removed lines with **both colour and a `+`/`−` glyph**.
  Colour-only fails for colour-blind users and in sunlight.
- **Do not wrap code.** Wrapping destroys alignment and makes `+`/`−` columns
  meaningless. Scroll horizontally per hunk; offer wrap as a toggle for prose.
- **Line numbers in a narrow fixed gutter**, old and new. They are what a
  reviewer references in a comment.
- Consider **split view on tablets and landscape** — same parser, different
  composable, chosen on width class (`android-ux`).

## Parse into a real model

Don't render raw diff text. Parse it:

```kotlin
data class FileDiff(
    val oldPath: String?, val newPath: String?,   // null = added / deleted
    val status: Status,                            // ADDED|MODIFIED|DELETED|RENAMED|BINARY
    val hunks: List<Hunk>,
    val isGenerated: Boolean,                      // lockfiles, generated sources
    val truncated: Boolean,
)

data class Hunk(val oldStart: Int, val oldLines: Int,
                val newStart: Int, val newLines: Int,
                val header: String,                // the text after @@ — often the enclosing fn
                val lines: List<DiffLine>)

sealed interface DiffLine {
    data class Context(val text: String) : DiffLine
    data class Added(val text: String) : DiffLine
    data class Removed(val text: String) : DiffLine
    data object NoNewlineAtEof : DiffLine          // "\ No newline at end of file"
}
```

Parsing notes that bite:

- **`@@ -a,b +c,d @@` counts are omissible** — `@@ -1 +1 @@` means one line.
  A parser assuming both numbers throws on real diffs.
- **The trailing text after `@@`** is usually the enclosing function. Show it as
  a sticky header — it is the cheapest context you get.
- **`\ No newline at end of file`** is a real line and not a content line.
- **Renames** carry `similarity index` and may have zero hunks. Render as a
  rename, not as delete-plus-add.
- **Binary files** say `Binary files … differ` and have no hunks.

## Big, generated, and binary files

The reason mobile review feels broken is that a 4,000-line lockfile diff buries
the 3-line change that matters.

- **Collapse by default** above a threshold (~200 lines) with a line count and
  tap-to-expand.
- **Detect generated files** — `*.lock`, `package-lock.json`, `*.pb.go`,
  `build/`, `.generated.` — and collapse them with a "generated" label. Honour
  `.gitattributes linguist-generated` if available.
- **Binary and images:** show a before/after thumbnail for images; for everything
  else, size delta only.
- **Respect server truncation.** GitHub's `/files` caps at 3000 files and omits
  patches above a size threshold; Azure DevOps paginates. Say "diff truncated"
  rather than implying the change is small (`github-api-client`,
  `azure-devops-api-client`).

## The file list is the primary screen

On a phone, the diff view is the *detail*. The list is where review happens.

- Sort with a bias to relevance — source before tests before generated.
- Per file: path (**middle-ellipsised**, never head-truncated — the filename is
  the identifying part), `+N/−M`, status badge, viewed checkbox.
- **Persist a per-file "viewed" flag** across sessions. Review on a phone is
  interrupted constantly; resuming from scratch is why people give up.
- Show review progress: "7 of 23 files viewed".

## Navigation

Reviewing means jumping, not scrolling.

- **Next/previous hunk** buttons that move between *changes*, skipping context.
  This is the single highest-value control on the screen.
- **Next file with changes** without returning to the list.
- **Sticky hunk header** showing file + enclosing function while scrolling.
- Deep link from a log line (`terminal-log-streaming-ui`) to `file:line`.

## Syntax highlighting

Worth it — it makes code scannable at a glance — but:

- **Highlight off the main thread**, cached per file. A regex/lexer pass over a
  2,000-line file will drop frames if done in composition.
- **Diff colour wins over syntax colour.** Use a background tint for add/remove
  and let syntax colour the foreground; don't fight over the same channel.
- **Degrade gracefully** — an unknown language renders as plain monospace, never
  an error.
- **Word-level intra-line diff** on modified lines is the highest-value refinement
  after basic highlighting: when one identifier changed on a long line, showing
  *which* saves a careful character-by-character read. Compute it only for
  paired add/remove lines of similar length.

## Commenting and approving

- **Tap a line to comment.** The anchor is `(path, side, line)` — side matters:
  a comment on a removed line is on the *old* side.
- **Draft locally, submit as a batch.** Phone connectivity drops mid-review;
  losing a typed comment is unforgivable. Persist drafts immediately.
- **Approve / request changes / comment** are the three terminal actions.
  Requesting changes without a comment should prompt for one.
- **Approving is a real decision** — in this app it may merge agent-written code.
  Show the summary (files, ± lines, checks status) at the point of approval, and
  treat it with the same care as `hitl-approval-flows`.

## Rendering untrusted content

Diff content is attacker-influenceable — an agent may have pulled a branch from
anywhere.

- Render as **text only**. No markdown, no HTML, no auto-linked URLs from diff
  content.
- **Normalise bidirectional Unicode.** Trojan Source attacks use RTL override
  characters to make code read differently than it compiles. Render bidi control
  characters as **visible escapes** (`<U+202E>`) rather than applying them, and
  flag files that contain them.
- Make **invisible characters visible** on demand — zero-width spaces,
  non-breaking spaces, tabs-vs-spaces, and trailing whitespace. A phone review
  that can't show these will miss the class of bug that hides in them.
- **Truncate very long lines** with expand. A minified 2 MB single-line file
  will hang layout.

## Testing

- Fixtures: rename with no hunks, binary, empty file added, file deleted,
  `@@ -1 +1 @@` single-line counts, `\ No newline at end of file`, CRLF,
  a 5,000-line lockfile, a minified single-line file, and a file containing bidi
  controls.
- Assert the parser never throws on malformed input — it degrades to raw text.
- Assert "viewed" state survives process death.
- Assert draft comments survive process death and offline.

## Checklist

- [ ] Inline layout on phones; split only at tablet/landscape widths
- [ ] Add/remove marked by glyph **and** colour
- [ ] No code wrapping by default; horizontal scroll
- [ ] Hunk header (enclosing function) sticky while scrolling
- [ ] Parser handles omitted counts, renames, binary, no-newline marker
- [ ] Large and generated files collapsed with counts; server truncation surfaced
- [ ] File list with per-file viewed state persisted across process death
- [ ] Next/previous hunk and next-changed-file navigation
- [ ] Syntax highlighting off the main thread and cached; word-level intra-line diff
- [ ] Comments anchored to (path, side, line); drafts persisted, submitted as a batch
- [ ] Bidi controls shown as escapes; invisible characters revealable
- [ ] Long lines truncated with expand
