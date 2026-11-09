# LLM Manager

A project for steering coding agents from an Android app, with execution on a
separate computer. **The current implementation is a Kotlin/JVM CLI prototype**
for Claude Code, plus a dashboard served over Tailscale that lists projects and
can start interactive Codex sessions in tmux. There is no Android app yet.

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

## Run the dashboard

```sh
tmux new-session -d -s api ~/LLM_Manager/server/api/build/install/api/bin/api
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

Reading needs only tailnet membership: anyone on your tailnet can see paths,
branch names, and remotes. Restrict it with Tailscale ACLs if the tailnet is
shared.

### Codex sessions

**Start Codex** on a project creates a new worktree on a `codex/<project>-<time>`
branch under `~/agent-work/codex/` and runs Codex there in a detached tmux
session named after the branch. Attach from an SSH session with the
`tmux attach -t codex-…` command shown on the card; detach with `Ctrl-b d`.
Checkouts start from the main worktree's current commit (unpushed work
included); mirrors start from the remote default branch. Existing checkouts and
agent worktrees are never modified, and nothing is pushed.

Codex is launched with `--sandbox workspace-write --ask-for-approval on-request`
regardless of `~/.codex/config.toml`. At most four `codex-*` sessions run at
once. Ending Codex ends the tmux session; the worktree and branch are kept for
review and are removed by hand (`git worktree remove`, `git branch -D`).

Starting a session requires an access token, created on first start in
`~/.config/llm-manager/api-token` (mode 600). Paste it once into the page via the
lock button; it is stored only in that browser. Install and sign in to Codex on
the server first:

```sh
npm install -g --prefix ~/.local @openai/codex
~/.local/bin/codex login --device-auth
```

Options: `--codex`, `--codex-worktrees`, `--token-file`.

## Layout

| Module | Responsibility |
| --- | --- |
| `core/protocol` | Serializable IDs, envelopes, usage data |
| `server/domain` | Run specifications and statuses |
| `server/runner` | Claude Code harness, parsing, redaction, process and worktree lifetime |
| `server/spike` | Local CLI demonstrating one run |
| `server/api` | Ktor dashboard over Tailscale: project overview, Codex sessions in tmux |

Shared agent skills live in `.agents/skills/`. `AGENTS.md` and `CLAUDE.md` direct
coding agents to that single library. Skills are loaded as needed for each task.

See [environment setup](docs/environment-setup.md) and the
[architecture status](docs/architecture-decision.md). The prototype is not a
security sandbox: worktrees isolate edits, not OS permissions. Network access,
device identity, approvals, durable audit storage, and other provider harnesses
remain future work.
