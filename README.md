# LLM Manager

A project for steering coding agents from an Android app, with execution on a
separate computer. **The current implementation is a Kotlin/JVM CLI prototype**
for Claude Code. There is no Android app or network server yet.

## Requirements

- JDK 21, selected through `JAVA_HOME` or your user-level Gradle configuration.
- Git on `PATH` (also used by integration tests).
- Claude Code on `PATH` and authenticated, only when running a live agent.

The Gradle wrapper is included. Builds and tests do not require Android Studio,
an Android SDK, provider credentials, or a live agent.

## Build and validate

```powershell
# Windows PowerShell
.\gradlew.bat check :server:spike:installDist
```

```sh
# Linux/macOS
bash ./gradlew check :server:spike:installDist
```

`check` runs tests and Detekt static analysis. Tests cover JSON parsing,
redaction, process timeouts/cancellation, repository isolation, and CLI cleanup.
They launch local Java fixtures and temporary Git repositories, without paid
provider calls. CI runs the same checks on Windows and Linux. Reports are in
each module's `build/reports/tests/` and the root `build/reports/detekt/`.

## Run the prototype

```powershell
.\server\spike\build\install\spike\bin\spike.bat `
  --repo C:/path/to/repository `
  --prompt "Add a multiply function and test it." `
  --work-root C:/path/to/agent-work `
  --keep-worktree true
```

On Linux/macOS use `server/spike/build/install/spike/bin/spike`. A live run uses
your Claude Code account and may incur charges. `--keep-worktree true` retains
changes for inspection even if the run fails; by default the temporary worktree
is removed. Run branches remain in the cache for recovery. Use `--timeout` for
a deadline in seconds (default 1800, range 1–604800). Permission modes are
`acceptEdits` (default), `plan`, and `bypass`.

## Layout

| Module | Responsibility |
| --- | --- |
| `core/protocol` | Serializable IDs, envelopes, usage data |
| `server/domain` | Run specifications and statuses |
| `server/runner` | Claude Code harness, parsing, redaction, process and worktree lifetime |
| `server/spike` | Local CLI demonstrating one run |

Shared agent skills live in `.agents/skills/`. `AGENTS.md` and `CLAUDE.md` direct
coding agents to that single library. Skills are loaded as needed for each task.

See [environment setup](docs/environment-setup.md) and the
[architecture status](docs/architecture-decision.md). The prototype is not a
security sandbox: worktrees isolate edits, not OS permissions. Network access,
device identity, approvals, durable audit storage, and other provider harnesses
remain future work.
