# LLM Manager

A project for steering coding agents from an Android app, with execution on a
separate computer. **The current implementation is a Kotlin/JVM CLI prototype**
for Claude Code, plus a dashboard served over Tailscale that lists projects,
charts their activity, schedules unattended Claude and Codex runs (including a
build-then-cross-review pipeline), resumes them after usage limits, and shows
plan usage, with a native Android app for the same dashboard.

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

Options: `--codex`, `--claude`, `--codex-worktrees`, `--run-worktrees`,
`--state-dir`, `--token-file`, `--apk`.

### Scheduled runs

The **Schedule** tab creates jobs that run now, once at a given time, or every
chosen weekday at a time. Each job is one of:

- **Run a task**: one agent works on the prompt.
- **Build + review**: the chosen agent implements; the server commits; the
  *other* agent reviews that commit (`codex review --commit` or a read-only
  Claude session); the builder then resumes its own session with the findings
  and addresses them. A review of "No findings" ends the pipeline.
- **Resume**: continue the latest Claude or Codex session found in an agent
  worktree (never your main checkout), with an optional message.

Runs execute unattended in a new `run/<project>-<time>` branch and worktree
under `~/agent-work/runs/`, inside a tmux session `run-<id>` (attachable, and
surviving server restarts). Permissions are pinned on the command line:

| Agent | Work | Review |
| --- | --- | --- |
| Claude | `-p --permission-mode acceptEdits` plus an allowlist of build, test and read-only git commands; anything else is denied | read-only tools and `git show/diff/log` |
| Codex | `exec --sandbox workspace-write` (writes confined to the worktree, no network) | `review` in the read-only sandbox |

After a successful build or fix stage the server commits the changes as
`Claude via LLM Manager` / `Codex via LLM Manager`. Nothing is pushed. Allowed
build commands such as `./gradlew` execute the repository's own build scripts,
so schedule runs only on repositories you trust.

When a run stops at a plan limit (Claude's `rate_limit_event` reports
`rejected`, or Codex reports a usage limit), it is marked **limited** and, unless
the job opted out, queued to resume in the same session two minutes after the
limit resets. At most three runs execute at once, one per worktree. Schedules,
run history and logs live in `~/.local/share/llm-manager/`; logs shown on the
phone are redacted.

### Usage

The **Usage** tab shows, from local records only:

- **Claude plan limits** (5-hour and weekly %) from the newest of: a scheduled
  run's `rate_limit_event`, or Claude Code's status line. Install the recorder
  with `cp scripts/claude-statusline.py ~/.local/share/llm-manager/` and in
  `~/.claude/settings.json`:
  `"statusLine": {"type": "command", "command": "python3 -I ~/.local/share/llm-manager/claude-statusline.py"}`.
- **Codex plan limits** from `token_count` events in `~/.codex/sessions/`.
- **Tokens per day** for both over 30 days (input + output, cache reads shown
  separately), and Claude tokens by model.

Claude and Codex both read `AGENTS.md`, so the skill library and project rules
are shared between them; the review prompt asks the reviewer to check the
change against it.

### Analytics

The dashboard charts the last 30 days in the server's time zone: commits per day
across all refs of every project, Codex sessions and agent runs started per day,
and the busiest projects by commits. Each project card also shows a 14-day trend.
Session counts come from worktrees that still exist; removing a worktree removes
it from the history.

## Android app

`android/app` is a Kotlin/Compose client for the same API: overview, analytics,
project cards, and Start Codex. It accepts only Tailscale addresses
(100.64.0.0/10 or `*.ts.net`), sends the access token only to that server with
redirects disabled, and keeps the token encrypted under an Android Keystore key,
excluded from backups and device transfer. The server is plain HTTP inside the
tailnet, whose WireGuard tunnel encrypts it; see
`android/app/src/main/res/xml/network_security_config.xml`.

The module is built only where an Android SDK is configured (see
[environment setup](docs/environment-setup.md)). Machine-specific values go in the
untracked `local.properties`:

```properties
sdk.dir=/home/you/Android/Sdk
llmmanager.serverUrl=http://100.x.y.z:8080          # prefilled in the app
llmmanager.signing.storeFile=/home/you/.config/llm-manager/android-release.jks
llmmanager.signing.storePassword=...
llmmanager.signing.keyAlias=llm-manager
```

```sh
bash ./gradlew :android:app:assembleRelease
```

Without a signing key the release build is signed with the debug key. Keep the
release keystore: Android only installs updates signed with the same key. When
`android/app/build/outputs/apk/release/app-release.apk` exists, the dashboard
shows **Get the Android app** and serves it at `/download/llm-manager.apk`; allow
your browser to install apps when Android asks.

## Layout

| Module | Responsibility |
| --- | --- |
| `core/protocol` | Serializable IDs, envelopes, usage data |
| `server/domain` | Run specifications and statuses |
| `server/runner` | Claude Code harness, parsing, redaction, process and worktree lifetime |
| `server/spike` | Local CLI demonstrating one run |
| `server/api` | Ktor dashboard over Tailscale: overview, analytics, Codex sessions, scheduled runs, usage |
| `android/app` | Compose app for the dashboard (built only when an Android SDK is configured) |

Shared agent skills live in `.agents/skills/`. `AGENTS.md` and `CLAUDE.md` direct
coding agents to that single library. Skills are loaded as needed for each task.

See [environment setup](docs/environment-setup.md) and the
[architecture status](docs/architecture-decision.md). The prototype is not a
security sandbox: worktrees isolate edits, not OS permissions. Network access,
device identity, approvals, durable audit storage, and other provider harnesses
remain future work.
