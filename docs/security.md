# Security model

Status: reviewed 2026-11-09; pull request insights and chat publishing added 2026-11-11;
pairing and in-app updates added 2026-11-12. This describes what the code enforces today and what
still depends on the machine's configuration.

## What is being protected

The server runs as one Unix account on a home machine. That account holds:

- the dashboard's access token (`~/.config/llm-manager/api-token`);
- forge credentials: the GitHub CLI login (`~/.config/gh`), GitLab and Azure DevOps
  tokens (`~/.config/llm-manager/*-token`);
- the Android release signing key (`~/.config/llm-manager/android-release.jks`);
- the logins of Claude Code, Codex and Gemini;
- the repositories it works on.

The phone (web page or app) steers agents that edit those repositories, unattended.

## Threats considered

| Threat | Example |
| --- | --- |
| Other devices or people on the tailnet | A shared node browses the dashboard or calls the API |
| A web page in a browser on the tailnet | DNS rebinding to the server's address; CSRF |
| A malicious or prompt-injected agent run | Edits `build.gradle.kts`, then runs the allowlisted `./gradlew` |
| A hostile repository in a scanned folder | `.git/config` with `core.fsmonitor` or filter drivers |
| A token sent to the wrong host | A repository whose origin is some other GitLab instance |
| Vulnerable dependencies | HTTP request smuggling in the web server |

## Controls

| Control | Where |
| --- | --- |
| Binds only to the Tailscale IPv4 (or loopback); never `0.0.0.0` | `BindAddress` |
| Every `/api` endpoint needs `Authorization: Bearer <token>`: the access token (256-bit, compared in constant time) or a paired device's token. Open: the static page, `/readyz`, the APK, pairing | `DashboardModule`, `ApiToken`, `Access` |
| Pairing: tailscaled's whois says who is connecting. Only untagged devices of the person the server is signed in as can pair; the server's own node (so anything running on it, such as an agent) cannot. Each device gets its own 256-bit token, stored as a SHA-256 hash, accepted only from that device's node, listed and revocable; `--pairing off` disables it | `Pairing`, `Tailnet` |
| Pairing refuses requests another web site makes the browser send (`Sec-Fetch-Site`, `Origin` checked against the allowed hosts); its answer is not readable cross-origin anyway | `PairingRoutes` |
| App updates are offered from the configured server's `/download/` path and opened in the browser; Android's installer, which refuses an APK signed with another key, installs them. The app holds no permission to install packages | `AppDownload` |
| Host header must be the bind address or the machine's MagicDNS names (`--allowed-host` adds more); others get 421 | `DashboardModule`, `BindAddress.allowedHosts` |
| CSP allows only the page's own script (by SHA-256); `frame-ancestors 'none'`, COOP/CORP, `nosniff`, `no-referrer`, `no-store` | `DashboardModule` |
| Request bodies ≤ 64 KiB, `Content-Length` required; unexpected errors become JSON 500 without details | `DashboardModule` |
| Token, state and run logs private (0600 / 0700); forge token files refused unless owner-only | `SecureFiles`, `StateStore`, `ForgeCredentials` |
| Forge tokens bound to one host (`gitlab-token` = gitlab.com, `gitlab-token-<host>` otherwise; Azure DevOps = dev.azure.com), never sent across redirects, passed to `git push` via environment, not argv | `ForgeCredentials`, `ForgeClient` |
| Agent permissions pinned on the command line: edits plus an allowlist of build/test/read-only git commands (Claude, Gemini policy), Codex's workspace-write sandbox; reviews read-only | `AgentCommands` |
| Agents start with a minimal environment: no `SSH_AUTH_SOCK`, `GH_TOKEN`, tmux/D-Bus variables or other agents' keys | `AgentEnvironment` |
| With bubblewrap: read-only root; secrets, other agents' logins and runtime sockets hidden; only the worktree, the agent's own state and build caches writable; agent config (hooks, settings, MCP servers) read-only | `AgentSandbox` |
| Server-side git never runs repository-defined programs (fsmonitor, hooks, signing, `ext::`), and runs inside a linked worktree only while its `.git` link points into the repository | `Git.HARDENING`, `WorktreeGuard` |
| Publishing only for agent branches; pushes never forced; merges through the forge's API so its checks and approvals apply | `BranchService` |
| A chat message may ask for publishing; the server then pushes that run's own branch and opens a pull request after the run (and its review) finished, on a separate thread, never the default branch | `Scheduler` (`FollowUps`), `BranchService` |
| Pull request insights use the same host-bound credentials, read only; GitHub via `gh`, with the query passed as an argument | `InsightSources` |
| Repository and forge text (README sentence, pull request titles, authors, run output) is untrusted: the page and app render it as text, never HTML, and follow only `https://` links built or checked by the server | `index.html`, `android/app` |
| Secrets redacted from run logs before they reach the phone | `RunOutput` (`SecretRedactor`) |
| Android: token in Android Keystore (AES-GCM), excluded from backup; only Tailscale hosts accepted; no redirects with the token | `android/app` |
| Dependencies checked against OSV; Netty and Logback pinned to patched releases | `gradle/libs.versions.toml` |

## Admin steps this account cannot do itself

1. **Install bubblewrap** so agent runs are contained:
   `sudo apt install bubblewrap`. With the default `--agent-sandbox auto`, the next
   server start self-tests the profile with each agent's `--version` and sandboxes the
   agents that pass. The Schedule tab shows the result. Use `--agent-sandbox on` to
   refuse runs that cannot be sandboxed.
2. **Enable lingering** so the dashboard runs as a supervised service that starts at
   boot: `sudo loginctl enable-linger llm`, then install `deploy/llm-manager-api.service`.

## Operating guidance

- **Rotate the access token** by deleting `~/.config/llm-manager/api-token` and
  restarting the server, then paste the new token into each device.
- **Restrict the tailnet**: use Tailscale ACLs so only your devices reach port 8080.
- **Forge tokens**: give them the smallest scope (GitLab `api` on one instance; Azure
  DevOps organisation-scoped, Code read & write). Global Azure DevOps PATs stop working
  on 1 December 2026.
- **Repositories**: allowed build commands run the repository's own build scripts.
  Without bubblewrap, a run can do anything this account can.

## Residual risks

- **No OS sandbox until bubblewrap is installed.** Until then the environment allowlist
  and permission pinning reduce exposure, but a run that executes build scripts can read
  every file this account can read.
- **Network access is not restricted** for agents, even when sandboxed: they need their
  model APIs. A run can send anything it can read to the internet.
- **Build caches are writable** inside the sandbox (`~/.gradle`, `~/.npm`, ...), so a
  run could poison a cache used by later builds.
- **The access token is a single shared secret** without expiry. Paired devices have their
  own tokens, but the access token still works from anywhere on the tailnet.
- **Pairing trusts every app on your own Tailscale devices.** Any app on your phone or laptop
  that can reach the server can pair itself, just as it can reach any other service on your
  tailnet. Revoking a device makes it pair again; removing it from your Tailscale account
  locks it out. Use `--pairing off` if that is too much trust.
- **The sandbox profile is untested on this machine** because bubblewrap is not installed;
  it is unit-tested as an argument list and self-tested at startup before use.
