#!/usr/bin/env bash
# Guard: the preview service runs a real init as PID 1, so orphans get reaped.
#
# Why this needs a test. The entrypoint ends in `exec compose-preview-server`, and that launcher
# (Gradle's) ends in `exec "$JAVACMD"` — so with no `init:` the JVM itself is PID 1. A JVM reaps
# only the processes it spawned; it never calls `waitpid(-1)`, so anything reparented to PID 1
# stays defunct for the life of the container.
#
# The failure is invisible in exactly the way a status endpoint is supposed to prevent. Nothing
# errors, no request fails, and the leak is in GRANDCHILDREN nobody writes code for: the catalog
# feed's promisor partial clone spawns background lazy fetches and a detached `gc --auto`, which
# outlive the `git` the server waits on. Measured on preview.coo.ee: 108 `git <defunct>` in 11.5 h,
# against an earlier 2099 `[java] <defunct>` in the daemon lane (see ServeProcessCensus). Both are
# the same missing `wait()` above the JVM, and both are fixed by this one line — which is also why
# it is so easy to drop in a refactor and never notice until a box runs out of PIDs.
#
# Static: no Docker, no network, no compose binary.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
base="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"

[[ -f "${base}" ]] || {
  echo "FAIL: missing ${base}" >&2
  exit 1
}

# The `preview:` service block only — up to the next service at the same indent, so an `init:` that
# belongs to caddy or watchtower can never satisfy this check.
block="$(awk '
  /^  preview:[[:space:]]*$/ { inblock = 1; next }
  inblock && /^  [^[:space:]#]/ { exit }
  inblock { print }
' "${base}")"

[[ -n "${block}" ]] || {
  echo "FAIL: found no 'preview:' service in ${base##*/} — this guard is broken, not passing." >&2
  exit 1
}

grep -qE '^[[:space:]]+init:[[:space:]]*true[[:space:]]*$' <<<"${block}" || {
  echo "FAIL: the 'preview' service does not declare 'init: true' in ${base##*/}." >&2
  echo "      Without it the JVM is PID 1 and never reaps orphans; unreaped 'git'/'java'" >&2
  echo "      grandchildren accumulate as zombies until the container exhausts its PIDs." >&2
  exit 1
}

echo "OK: preview runs an init as PID 1 (orphans get reaped)."
