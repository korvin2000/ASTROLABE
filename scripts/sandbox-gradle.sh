#!/usr/bin/env bash
# Linux cloud sandbox Gradle wrapper (see CLAUDE.md § Commands). JDK 26 cannot be provisioned there, so
# Gradle runs in a scratch copy of the working tree retargeted to JDK 25 — never committed (TODO §1).
#
#   scripts/sandbox-gradle.sh <gradle args>   wait for the scratch copy, sync, retarget, run quietly
#   scripts/sandbox-gradle.sh --setup         bootstrap: JDK 25 + scratch copy + dependency warm-up
#
# Output: one OK line with test totals, or the failure lines and the log tail. Full log: $LOG.
# After a task named *updateKotlinAbi, the */api/*.api dumps are copied back to the working tree.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRATCH="${ASTROLABE_SCRATCH:-${TMPDIR:-/tmp}/astrolabe-jdk25}"
STATE="$SCRATCH.state"                       # outside the copy so a sync never touches it
READY="$STATE/ready" FAILED="$STATE/failed" SETUP_LOG="$STATE/setup.log" LOG="$STATE/last.log"
JDK="${ASTROLABE_JDK25:-/usr/lib/jvm/java-25-openjdk-amd64}"
export JAVA_HOME="$JDK" GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.welcome=never"
mkdir -p "$STATE"

# Mirror tracked + untracked-unignored files into the copy and delete files the tree no longer has;
# build outputs and Gradle state in the copy survive, so incremental compilation keeps working.
sync_tree() {
  local want="$STATE/files"
  mkdir -p "$SCRATCH"
  (cd "$REPO" && git ls-files -co --exclude-standard | while IFS= read -r f; do [ -e "$f" ] && printf '%s\n' "$f"; done \
    | LC_ALL=C sort) >"$want" || return 1
  (cd "$REPO" && tar -T "$want" -cf -) | tar -C "$SCRATCH" -xf - || return 1
  comm -23 \
    <(cd "$SCRATCH" && find . \( -name build -o -name .gradle -o -name .kotlin \) -prune -o -type f -print \
        | sed 's|^\./||' | LC_ALL=C sort) \
    "$want" | (cd "$SCRATCH" && tr '\n' '\0' | xargs -0 -r rm -f)
}

# JDK 26 → 25 in the convention plugin, no daemon-JVM toolchain file, local JDK only. Idempotent because
# sync_tree restores the pristine files first.
retarget() {
  local conv="$SCRATCH/build-logic/src/main/kotlin/astrolabe.kotlin-library.gradle.kts"
  sed -i -e 's/JavaLanguageVersion\.of(26)/JavaLanguageVersion.of(25)/' -e 's/jvmToolchain(26)/jvmToolchain(25)/' \
         -e 's/JvmTarget\.JVM_26/JvmTarget.JVM_25/' -e 's/-Xjdk-release=26/-Xjdk-release=25/' \
         -e 's/release\.set(26)/release.set(25)/' "$conv" || return 1
  rm -f "$SCRATCH/gradle/gradle-daemon-jvm.properties"
  printf '\norg.gradle.java.installations.paths=%s\norg.gradle.java.installations.auto-download=false\n' "$JDK" \
    >> "$SCRATCH/gradle.properties"
}

# Run Gradle in the copy; retry Maven Central 429s (and other transient fetch errors) with backoff.
gradle_retry() {
  local log="$1"; shift
  local delay=15 attempt
  for attempt in 1 2 3 4 5; do
    (cd "$SCRATCH" && ./gradlew --console=plain "$@") >"$log" 2>&1 && return 0
    grep -qE '(\b429\b|Too Many Requests|Could not (GET|HEAD|resolve)|Connection reset|Read timed out)' "$log" || return 1
    [ "$attempt" = 5 ] && return 1
    sleep "$delay"; delay=$((delay * 2))
  done
}

setup() {
  rm -f "$READY" "$FAILED"
  (
    echo "setup start $(date -Is)"
    if [ ! -x "$JDK/bin/javac" ]; then
      for i in 1 2 3; do
        apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-25-jdk-headless && break
        sleep $((i * 10))
      done
    fi
    [ -x "$JDK/bin/javac" ] || { echo "JDK 25 not installed at $JDK"; exit 1; }
    { sync_tree && retarget; } || { echo "scratch copy failed"; exit 1; }
    gradle_retry "$STATE/warmup.log" testClasses || { echo "warm-up failed:"; tail -n 20 "$STATE/warmup.log"; exit 1; }
    echo "setup done $(date -Is)"
  ) >"$SETUP_LOG" 2>&1 && touch "$READY" || { touch "$FAILED"; return 1; }
}

wait_ready() {
  local waited=0
  if [ ! -e "$READY" ] && [ ! -e "$FAILED" ] && ! pgrep -f 'sandbox-gradle.sh --setup' >/dev/null; then
    echo "sandbox: no bootstrap found; running setup now" >&2; setup
  fi
  while [ ! -e "$READY" ] && [ ! -e "$FAILED" ]; do
    [ "$waited" = 0 ] && echo "sandbox: waiting for bootstrap (log $SETUP_LOG)" >&2
    sleep 5; waited=$((waited + 5))
    [ "$waited" -ge 1800 ] && { echo "sandbox: bootstrap timed out" >&2; return 1; }
  done
  [ -e "$READY" ] || { echo "sandbox: bootstrap failed:" >&2; tail -n 20 "$SETUP_LOG" >&2; return 1; }
}

# Totals from the JUnit XML written by this run.
test_totals() {
  local files; files=$(find "$SCRATCH" -path '*/build/test-results/*' -name 'TEST-*.xml' -newer "$1" 2>/dev/null)
  [ -n "$files" ] || return 0
  # shellcheck disable=SC2086
  cat $files | grep -o '<testsuite [^>]*>' | awk '
    { for (i = 1; i <= NF; i++) { split($i, kv, "="); gsub(/"/, "", kv[2]); v[kv[1]] += kv[2] } }
    END { printf "tests %d, failures %d, errors %d, skipped %d\n", v["tests"], v["failures"], v["errors"], v["skipped"] }'
}

case "${1:-}" in
  --setup) setup; exit $? ;;
  "") echo "usage: $0 <gradle args> | --setup" >&2; exit 2 ;;
esac

wait_ready || exit 1
{ sync_tree && retarget; } || { echo "sandbox: sync failed" >&2; exit 1; }
start="$STATE/start"; touch "$start"; t0=$SECONDS
if gradle_retry "$LOG" "$@"; then
  echo "OK ($((SECONDS - t0))s): $*  $(test_totals "$start")"
  status=0
else
  status=1
  echo "FAILED ($((SECONDS - t0))s): $*  — full log: $LOG"
  grep -nE '^(e: |w: .*error)|FAILED$|> Task .* FAILED|What went wrong|^\* What|Exception|expected:|AssertionFailedError' "$LOG" \
    | grep -v '^\s*at ' | head -n 40
  echo "--- tail ---"; tail -n 25 "$LOG"
  totals=$(test_totals "$start"); [ -n "$totals" ] && echo "$totals"
fi

if [ "$status" = 0 ] && printf '%s\n' "$@" | grep -q 'updateKotlinAbi'; then
  for f in "$SCRATCH"/*/api/*.api; do
    rel="${f#"$SCRATCH"/}"; cmp -s "$f" "$REPO/$rel" || { cp "$f" "$REPO/$rel"; echo "ABI dump updated: $rel"; }
  done
fi
exit "$status"
