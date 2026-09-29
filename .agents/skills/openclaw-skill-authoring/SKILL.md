---
name: openclaw-skill-authoring
description: >
  Use when writing, installing, debugging, or publishing an OpenClaw SKILL.md —
  choosing frontmatter fields, deciding where a skill lives (workspace vs global vs
  .agents), gating a skill with metadata.openclaw requires/os, scoping visibility
  with per-agent allowlists, or working out why a skill is not being picked up,
  is shadowed by another source, or will not load on a given machine. Also use for
  `openclaw skills install/update/verify` and ClawHub publishing.
---

# Authoring OpenClaw skills

An OpenClaw skill is a folder containing `SKILL.md`: YAML frontmatter for metadata,
markdown body for instructions to the agent. That is the whole contract.

```markdown
---
name: hello-world
description: A simple skill that prints a greeting.
---

# Hello World

When the user asks for a greeting, use the `exec` tool to run:

    echo "Hello from your custom skill!"
```

## Frontmatter

**Required**

| Field | Rule |
|---|---|
| `name` | Unique slug: lowercase letters, digits, hyphens |
| `description` | One line, shown to the agent and in discovery output |

**Optional**

| Field | Default | Use |
|---|---|---|
| `user-invocable` | `true` | Set `false` to hide from slash-command discovery |
| `disable-model-invocation` | `false` | Set `true` for skills only a human should trigger |
| `command-dispatch` / `command-tool` | — | Route the skill straight at a tool |
| `command-arg-mode` | `raw` | How the invocation's arguments are parsed |
| `homepage` | — | Source/docs link |
| `metadata.openclaw` | — | Gating (see below) |

The **`description` is the main signal for *automatic* selection** — explicit
invocation and eligibility gating also decide whether a skill is reachable, but the
description is what you control. Write it as trigger conditions, not a summary. "Use when X, Y, or Z —
including the symptoms A and B" beats "Helps with X." Name the concrete API names,
error strings, and file names that should pull it in.

## The name comes from frontmatter, not the folder

Skills are discovered by finding `SKILL.md` up to **6 levels deep** under each
root, and the skill's name is the frontmatter `name`. Both of these resolve to a
skill named `research`:

```
<workspace>/skills/research/SKILL.md
<workspace>/skills/personal/research/SKILL.md
```

So subfolders are free organisation — but two skills with the same frontmatter
`name` in different folders collide.

## Where to put it — precedence, highest wins

1. Workspace skills — `<workspace>/skills`
2. Project agent skills — `<workspace>/.agents/skills`
3. Personal agent skills — `~/.agents/skills`
4. Managed/local — `<state-dir>/skills` (i.e. `~/.openclaw/skills`)
5. Workshop — `<state-dir>/agents/<agentId>/agent/workshop-skills`
6. Bundled and Custodian skills (shipped with the install)
7. Extra dirs and plugin skills — `skills.load.extraDirs`

Collisions where a workspace or project skill overrides something lower are logged
at **info**; other collisions are logged as **warnings**. If a skill edit seems to
have no effect, check the logs for a shadowing message before editing further —
you are probably editing a copy that something higher is overriding.

Visibility follows placement:

| Scope | Path | Seen by |
|---|---|---|
| Per-agent | `<workspace>/skills` | that agent |
| Project-agent | `<workspace>/.agents/skills` | that workspace's agent |
| Personal-agent | `~/.agents/skills` | agents on default state |
| Shared managed | `<state-dir>/skills` | all agents in that state |
| Workshop | `<state-dir>/agents/<id>/agent/workshop-skills` | that agent only |

Workshop skills learned by one agent are **not** shared with another.

Sessions using a different execution workspace load that workspace's `skills/`
and `.agents/skills/` too; within it, `skills/` wins over `.agents/skills/`.

## Gating with `metadata.openclaw`

Declare what the skill needs so it disappears cleanly instead of failing mid-run:

```markdown
metadata:
  {
    "openclaw":
      {
        "requires": { "bins": ["uv"], "env": ["API_KEY"], "config": ["browser.enabled"] },
        "os": ["darwin", "linux"],
      },
  }
```

- `bins` / `anyBins` — binaries that must be on PATH
- `env` — required environment variables
- `config` — `openclaw.json` paths that must be truthy
- `os` — `darwin` \| `linux` \| `win32`
- `always` — bypass dependency checks (the `os` filter still applies)

Gate anything that shells out. A skill that assumes `uv`, `adb`, or `gradle` and
gets loaded on a machine without it produces a confusing mid-task failure; gated,
it simply is not offered.

**`bins` checks the host, not the sandbox.** A binary present on PATH for the
Gateway process is not necessarily present inside a sandboxed execution step. If
the skill's commands run sandboxed, verify availability there too rather than
treating the gate as proof.

## Per-agent allowlists

Allowlists filter which skills an agent is *offered*, independent of where they
load from:

```json5
{
  agents: {
    defaults: { skills: ["github", "weather"] },
    entries: {
      docs: { skills: ["docs-search"] }, // replaces defaults, does not merge
      "locked-down": { skills: [] },
    },
  },
}
```

**A non-empty allowlist is final — it replaces defaults rather than merging.**
Omitting `skills` on an entry inherits defaults. The allowlist applies across
prompt building, slash-command discovery, sandbox sync, and skill snapshots, so a
skill missing from one surface is missing from all of them.

**An allowlist is not a sandbox.** It controls skill *availability*, not what the
agent can do. An agent with host execution access can still read files and run
programs; `"locked-down": { skills: [] }` narrows its instructions, not its
authority. For real confinement use sandboxing and tool permissions.

## Loading, snapshots, refresh

Eligible skills are snapshotted at session start. Refresh happens when `SKILL.md`
changes (file watcher), the Gateway restarts, new remote nodes connect, native
file-watch capacity is exhausted, or allowlists change.

Two separate mechanisms, easy to conflate:

- **Managed library selections** keep their exact revisions until an explicit
  library *attach or refresh*.
- **`openclaw skills update`** updates ClawHub-tracked installs.

Neither drifts on its own; if you expect a new version and do not have it, work out
which of the two you are actually using.

If an edit is not taking effect: confirm the watcher saw it, then restart the
Gateway before assuming the content is wrong.

## Connected nodes

Headless nodes publish skills from their own skills directory (`~/.openclaw/skills`
by default). They appear while the node is connected and vanish on disconnect. On
a name collision the **node** skill gets a deterministic node-prefixed name;
local/gateway skills keep priority.

## CLI

```bash
openclaw skills install @owner/<slug>               # from ClawHub
openclaw skills install git:owner/repo@ref          # from git
openclaw skills install ./path/to/skill --as name   # local dir
openclaw skills install @owner/<slug> --global      # → ~/.openclaw/skills, all agents
openclaw skills update --all
openclaw skills verify @owner/<slug>                # trust verification
```

Installs default to the workspace `skills/`; `--global` targets `~/.openclaw/skills`.

Publishing goes through the separate ClawHub CLI (`npm i -g clawhub`), which owns
publish and sync; the `openclaw skills` commands own install and update. See
<https://docs.openclaw.ai/clawhub/cli> for the current publish flow before shipping
anything public.

**Run `openclaw skills verify` on anything from ClawHub before trusting it.** The
registry is open and community-published; a skill is instructions handed to an
agent holding your credentials, so treat installing one like `curl | sh` — read
the body first, especially any `exec` blocks.

## Secrets

`skills.entries.<key>.env` and `.apiKey` inject values into the **host process
only, not sandboxes**. Do not write a skill that assumes a sandboxed step can read
an injected key, and never inline a secret into `SKILL.md` — the file gets copied,
snapshotted, and often published.

## Checklist before publishing

- [ ] `name` is a unique slug and matches how you want it invoked
- [ ] `description` reads as trigger conditions with concrete keywords
- [ ] Every external binary/env/config dependency is in `metadata.openclaw.requires`
- [ ] `os` set if the skill is platform-specific
- [ ] No secrets, no absolute paths from your machine
- [ ] Body is instructions to an agent, not documentation for a human
- [ ] Verified it actually loads: `openclaw skills install ./<dir> --as <name>` and check discovery output
