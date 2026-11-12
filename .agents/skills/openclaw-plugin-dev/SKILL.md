---
name: openclaw-plugin-dev
description: >
  Use when building, packaging, testing, or debugging an OpenClaw plugin — the
  TypeScript/ESM module that runs inside the Gateway process to add tools,
  messaging channels, model providers, CLI backends, hooks, media providers, or
  custom Gateway RPC. Covers package.json + openclaw.plugin.json manifests,
  definePluginEntry, api.registerTool, required vs optional tools,
  contracts.tools, activation, and `openclaw plugins install clawhub:`.
  Use this rather than openclaw-skill-authoring when the extension must register
  Gateway capabilities — tools, providers, channels, RPC, or lifecycle hooks —
  rather than supply instructions for tools that already exist.
---

# Building OpenClaw plugins

## Skill or plugin?

Reach for a **skill** first. A skill is a `SKILL.md` that instructs the agent: no
build step, no version pinning, no code loaded into the Gateway. (It is *not*
inert — a skill can direct the agent to run scripts and commands, so it is not
"safe" in an absolute sense. The distinction is that it supplies instructions for
existing tools rather than registering new Gateway capabilities.)

Write a **plugin** only when you need to register something the Gateway itself
must own:

- register a real tool with a typed parameter schema
- add a messaging channel, model provider, or CLI backend
- run on Gateway startup, or hook into its lifecycle
- expose custom Gateway RPC

A plugin runs **inside the Gateway process** with access to internal APIs. A crash
or a blocking loop there takes down the user's whole assistant — including its
other channels. That weight is the reason to default to skills.

## Runtime

**Node 24.16+ or Node 26.1+**, TypeScript ESM. The SDK is imported through focused
subpaths, not a barrel:

```typescript
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
```

## Two manifests, both required

`package.json` — dependencies and compatibility:

```json
{
  "name": "@myorg/openclaw-my-plugin",
  "type": "module",
  "peerDependencies": {
    "openclaw": ">=2026.3.24-beta.2"
  },
  "openclaw": {
    "extensions": ["./index.ts"],
    "compat": {
      "pluginApi": ">=2026.3.24-beta.2"
    }
  }
}
```

`openclaw.plugin.json` — capabilities and activation. **`id` and `configSchema` are
the two required fields.** Every plugin must ship a JSON Schema *even if it accepts
no config*; omitting it can stop the plugin loading:

```json
{
  "id": "my-plugin",
  "name": "My Plugin",
  "configSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {}
  },
  "contracts": {
    "tools": ["my_tool"]
  },
  "activation": {
    "onStartup": true
  }
}
```

ESM (`"type": "module"`) is the right default and matches the SDK's subpath
imports, though the package-entry docs do also describe CommonJS runtime
candidates — so treat ESM as the house choice, not a universal law.

Keep the `peerDependencies` range and `compat.pluginApi` honest. The plugin API is
on dated pre-release versions, so an over-broad range means silent breakage instead
of a clean refusal to load. **Record the version you actually tested against** — a
compatibility floor is a claim, not evidence.

Set `activation.onStartup: true` only if the plugin genuinely must be live before
first use. Startup work is paid by every Gateway boot.

## Registering a tool

```typescript
api.registerTool({
  name: "my_tool",
  description: "Echo input",
  parameters: Type.Object({ input: Type.String() }),
  async execute(_id, params) {
    return {
      content: [{ type: "text", text: `Got: ${params.input}` }],
      details: { input: params.input }
    };
  }
});
```

**Every registered tool must also be listed in `contracts.tools`.** A tool
registered but undeclared is the single most common "my plugin loads but the agent
can't see my tool" bug.

The `description` and the `parameters` schema are the whole interface the model
sees. Spend the effort there: say when to use the tool and when not to, and make
illegal states unrepresentable in the schema rather than validating in `execute`.

### Required vs optional tools

Required tools load automatically. `optional: true` makes a tool **opt-in
exposure** — the user chooses to enable it. It is *not* a per-call consent prompt;
runtime permission requests are a separate mechanism. Mark anything that spends
money, sends outbound messages, or mutates state outside the process as optional.

A context factory is an independent feature — it gives the tool access to runtime
delivery capabilities. It is not tied to optionality:

```typescript
api.registerTool(
  (toolContext) => ({
    name: "workflow_tool",
    description: "Run the workflow and report progress",
    parameters: Type.Object({ target: Type.String() }),
    async execute(_id, params) {
      await toolContext.delivery?.send({ text: `Started ${params.target}` });
      return { content: [{ type: "text", text: "done" }] };
    }
  }),
  { name: "workflow_tool", optional: true }
);
```

**`parameters` is not optional** — registrations without it are skipped, which
looks exactly like the tool never registering.

`delivery?.send` reports progress into the user's channel during the **active
turn**; that capability expires with the turn. It is not a durable background-job
notification API — do not build "notify me when the long job finishes tomorrow" on
it. Note the `?.`: delivery is not always present.

## Custom Gateway RPC

An advanced entry point. Keep every method under a **plugin-specific prefix** so it
cannot collide with core methods or another plugin's. Do not shadow built-in
method names.

## Install and local testing

```bash
openclaw plugins install clawhub:your-org/your-plugin   # clawhub: prefix → registry resolution
openclaw plugins install <npm-package>                  # bare npm package
```

Local loop — test the *installed artifact*, not the source tree:

```bash
npm pack --pack-destination /tmp                             # build the tarball
openclaw plugins install npm-pack:/tmp/<pkg>-<version>.tgz   # reproduce the published install path
openclaw plugins inspect my-plugin --runtime --json
```

The `npm-pack:` prefix matters — it runs the same dependency resolution a published
install would. Pointing at a raw tarball path skips managed dependency installation,
so the plugin can appear to work while missing deps users would hit.

Packing and inspecting the working directory proves nothing. The classic failure is
a plugin that works in-tree but ships broken because `files`/`.npmignore` excluded
something, or because the manifest points at TypeScript sources with no compiled
runtime output alongside them. Install the tarball and inspect *that*.

In-repo bundled plugins live in the `extensions/*` workspace and need `pnpm install`.

## Before publishing

- [ ] `configSchema` present in `openclaw.plugin.json`, even if empty
- [ ] Packed tarball **installed** and inspected — not just tested in-tree
- [ ] Compiled runtime output ships with the package
- [ ] `openclaw plugins inspect --runtime` shows the tools you expect
- [ ] Every registered tool appears in `contracts.tools`
- [ ] Every tool has both `description` and `parameters`
- [ ] Side-effecting tools are `optional: true`
- [ ] Custom RPC methods are namespaced
- [ ] Tested OpenClaw version recorded in the README
- [ ] Unit tests pass; tested against a beta release before a stable one
- [ ] No unhandled rejections — remember this code shares the Gateway's process
