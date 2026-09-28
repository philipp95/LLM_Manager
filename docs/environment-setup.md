# Environment setup

Findings from bringing up the build on the current dev machine (2026-09-28).
Each of these cost time to discover; none are obvious from an error message.

## JDK

The machine ships **JDK 11.0.0.2** (a 2018-era build). Two problems:

1. Its TLS stack cannot negotiate with Maven Central — dependency resolution
   fails with `Received fatal alert: handshake_failure`.
2. Modern AGP requires JDK 17+ regardless.

Gradle **toolchain auto-provisioning does not solve this**. Toolchains govern
*compilation*; Gradle itself runs on the JDK that launched it, and that JDK is
what performs dependency resolution.

Fix: Temurin 21 is unpacked at `~/.jdks/temurin-21` and pinned in
`gradle.properties` via `org.gradle.java.home`. It is not a system install —
deleting the folder reverses it. The path uses **forward slashes**, because
`.properties` treats `\` as an escape (`\t` in `...\temurin-21` became a tab).

## Android SDK — not yet installed

There is no Android SDK and no Android Studio on this machine. Android modules
are therefore **deliberately absent from `settings.gradle.kts`**; the server
side builds and runs without them.

Before milestone M2 (the app), install Android Studio or the command-line tools
and set `ANDROID_HOME`, then add the app modules.

## Claude Code headless — two things that block autonomous runs

Discovered by running M0, not from documentation:

1. **A permission mode is mandatory.** With the interactive default, every file
   operation is denied and the run burns tokens achieving nothing — one such run
   cost **$0.23** and produced no edit. The harness now always passes
   `--permission-mode` (default `acceptEdits`, which is appropriate because each
   run owns an isolated worktree).

2. **8.3 short paths are rejected.** A worktree under `C:\Users\PHILIP~1\...`
   triggers Claude Code's *"suspicious Windows path pattern requires manual
   approval"* heuristic and every tool call is denied. Always hand the harness a
   long path.

## Locale

`user.country.format = BE` on this machine, so `"%.6f".format(0.23)` renders as
`0,23`. All money formatting uses `Locale.ROOT` explicitly.

## Running the M0 spike

```bash
./gradlew :server:spike:installDist

JAVA_HOME=C:/Users/PhilippEngels/.jdks/temurin-21 \
./server/spike/build/install/spike/bin/spike \
  --repo   C:/path/to/repo \
  --prompt "Add a multiply(a, b) function to calc.py." \
  --work-root C:/path/to/work \
  --keep-worktree true
```
