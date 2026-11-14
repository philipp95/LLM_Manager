#!/usr/bin/env bash
# Turns the failures in a Gradle log into GitHub annotations, so they show on the pull
# request's checks without opening the job log (which needs a sign-in on public repositories).
# Usage: annotate-failures.sh build.log [job status]
log="${1:-build.log}"
status="${2:-failure}"
[ -f "$log" ] || { echo "::error::No build log at $log"; exit 0; }

# A build that never reached its result (cancelled, timed out, stuck): show where it stopped.
if [ "$status" = "cancelled" ] || ! grep -qE '^BUILD (SUCCESSFUL|FAILED)' "$log"; then
  tail -n 15 "$log" | sed -e 's/%/%25/g' | awk '{ printf "%s%%0A", $0 }' | sed -e 's/^/::error title=Last output before the job stopped::/'
  echo
fi

# Each failed test, with the exception Gradle prints on the next line.
awk '/ FAILED$/ {
  test = $0
  getline detail
  sub(/^[ \t]+/, "", detail)
  gsub(/%/, "%25", test); gsub(/%/, "%25", detail)
  print "::error::" test " -> " detail
}' "$log" | head -n 20

# Compiler, detekt and lint findings.
grep -E '^e: |\.kts?:[0-9]+:[0-9]+: .*\[[A-Za-z]+\]$|: Error: ' "$log" | head -n 20 | sed -e 's/%/%25/g' -e 's/^/::error::/'

# The build's own summary of what went wrong.
awk '/^\* What went wrong:/ { on = 1; next }
  on && /^\* Try:/ { exit }
  on { gsub(/%/, "%25"); message = message $0 "%0A" }
  END { if (message != "") print "::error title=Build failure::" message }' "$log"
