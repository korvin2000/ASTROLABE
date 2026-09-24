#!/usr/bin/env bash
# SessionStart hook: in the Linux cloud sandbox only, start the JDK-25 scratch-copy bootstrap in the
# background (scripts/sandbox-gradle.sh --setup). Prints at most three lines; never blocks the session.
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

REPO="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
STATE="${ASTROLABE_SCRATCH:-${TMPDIR:-/tmp}/astrolabe-jdk25}.state"

if [ -e "$STATE/ready" ]; then
  echo "Sandbox Gradle ready: use scripts/sandbox-gradle.sh <args> (JDK 25 scratch copy)."
elif pgrep -f 'sandbox-gradle.sh --setup' >/dev/null; then
  echo "Sandbox Gradle bootstrap already running; scripts/sandbox-gradle.sh waits for it."
else
  mkdir -p "$STATE"
  setsid nohup "$REPO/scripts/sandbox-gradle.sh" --setup >/dev/null 2>&1 < /dev/null &
  echo "Sandbox Gradle bootstrap started in background (JDK 25 + scratch copy + dependency warm-up)."
  echo "Use scripts/sandbox-gradle.sh <gradle args>; it waits for the bootstrap. Log: $STATE/setup.log"
fi
exit 0
