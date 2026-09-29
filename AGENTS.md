# Project guidance

Shared skills live in `.agents/skills/`. Consult `.agents/skills/README.md` to
identify relevant skills, then read their `SKILL.md` files before implementation
or review. Resolve references relative to each skill directory. Load only the
skills needed for the task; do not load the entire library.

These skills apply to all coding agents, regardless of their original vendor
or plugin prefixes. Use the local directory name to resolve a skill reference.
Explicit user instructions take precedence over skill guidance.

The current implementation is a Kotlin/JVM CLI prototype. Read `README.md` for
setup and validation commands, and `docs/architecture-decision.md` for current
scope. Use JDK 21; keep machine-specific settings outside tracked files.
