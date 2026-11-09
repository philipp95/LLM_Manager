# LLM Manager

A project for steering coding agents from an Android app, with execution on a
separate computer. **The current implementation is a Kotlin/JVM CLI prototype**
for Claude Code, plus a read-only dashboard served over Tailscale. There is no
Android app yet, and nothing reachable over the network can start or change a run.

## Requirements

- JDK 21, selected through `JAVA_HOME` or your user-level Gradle configuration.
- Git on `PATH` (also used by integration tests).
- Claude Code on `PATH` and authenticated, only when running a live agent.

The Gradle wrapper is included. Builds and tests do not require Android Studio,
an Android SDK, provider credentials, or a live agent.

## Build and validate

```powershell
# Windows PowerShell
.\gradlew.bat check :server:spike:installDist :server:api:installDist
```

```sh
# Linux/macOS
bash ./gradlew check :server:spike:installDist :server:api:installDist
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

## Run the read-only dashboard

```sh
server/api/build/install/api/bin/api
```

It binds to this machine's Tailscale IPv4 (`tailscale ip -4`) on port 8080 and
refuses to start if Tailscale is not up. It never binds to `0.0.0.0`; only a
Tailscale address (100.64.0.0/10) or loopback is accepted via `--host`. Open
`http://<tailscale-ip>:8080/` on a device in your tailnet; `GET /api/projects`
returns the same data as JSON, and `GET /readyz` reports readiness.

Projects are the git repositories in `~/agent-work/mirrors/*`, `~/LLM_Manager`,
and `~/repos/*`. Override the list with `--projects` or `LLM_MANAGER_PROJECTS`
(entries separated by `:` on Linux/macOS, `;` on Windows; a trailing `/*` means
each child folder). Folders that do not exist are skipped. For each project the
dashboard shows its origin (credentials stripped, linked to GitHub), default
branch, and every worktree with branch, last commit time, and changed-file
count. Git is queried with `GIT_OPTIONAL_LOCKS=0` so inspection never rewrites
an index an agent is using.

There is no authentication beyond tailnet membership: anyone on your tailnet can
read paths, branch names, and remotes. Restrict it with Tailscale ACLs if the
tailnet is shared.

## Layout

| Module | Responsibility |
| --- | --- |
| `core/protocol` | Serializable IDs, envelopes, usage data |
| `server/domain` | Run specifications and statuses |
| `server/runner` | Claude Code harness, parsing, redaction, process and worktree lifetime |
| `server/spike` | Local CLI demonstrating one run |
| `server/api` | Read-only Ktor dashboard and `/api/projects` over Tailscale |

Shared agent skills live in `.agents/skills/`. `AGENTS.md` and `CLAUDE.md` direct
coding agents to that single library. Skills are loaded as needed for each task.

See [environment setup](docs/environment-setup.md) and the
[architecture status](docs/architecture-decision.md). The prototype is not a
security sandbox: worktrees isolate edits, not OS permissions. Network access,
device identity, approvals, durable audit storage, and other provider harnesses
remain future work.
