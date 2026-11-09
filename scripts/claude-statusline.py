#!/usr/bin/env python3
"""Claude Code status line that also records plan usage for the LLM Manager dashboard.

Claude Code runs this on every status refresh and passes session details as JSON on
stdin. When the documented `rate_limits` object (5-hour and weekly windows) is
present, it is written to ~/.local/share/llm-manager/claude-rate-limits.json. The
line printed back shows the model, both windows and context use.

Install: copy to ~/.local/share/llm-manager/ and set in ~/.claude/settings.json:
  "statusLine": {"type": "command", "command": "python3 -I ~/.local/share/llm-manager/claude-statusline.py"}
"""
import json
import os
import sys
import tempfile
from datetime import datetime, timezone

STATE = os.path.join(os.path.expanduser("~"), ".local", "share", "llm-manager")
OUT = os.path.join(STATE, "claude-rate-limits.json")


def record(limits):
    os.makedirs(STATE, exist_ok=True)
    body = {"capturedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "rate_limits": limits}
    fd, tmp = tempfile.mkstemp(dir=STATE, prefix=".claude-rate-limits.")
    with os.fdopen(fd, "w") as f:
        json.dump(body, f)
    os.replace(tmp, OUT)


def main():
    try:
        data = json.load(sys.stdin)
    except ValueError:
        print("Claude")
        return
    limits = data.get("rate_limits")
    if isinstance(limits, dict) and limits:
        try:
            record(limits)
        except OSError:
            pass  # never break the status line over bookkeeping
    parts = [(data.get("model") or {}).get("display_name") or "Claude"]
    for key, label in (("five_hour", "5h"), ("seven_day", "7d")):
        window = (limits or {}).get(key) if isinstance(limits, dict) else None
        if isinstance(window, dict) and isinstance(window.get("used_percentage"), (int, float)):
            parts.append(f"{label} {window['used_percentage']:.0f}%")
    context = (data.get("context_window") or {}).get("used_percentage")
    if isinstance(context, (int, float)):
        parts.append(f"ctx {context:.0f}%")
    print(" · ".join(parts))


if __name__ == "__main__":
    main()
