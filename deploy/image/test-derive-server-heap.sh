#!/usr/bin/env bash
# Guard: the server JVM's heap ceiling is derived from the container's memory, bounded on both
# sides, and applied to the server alone.
#
# Without it the server took JAVA_TOOL_OPTIONS' MaxRAMPercentage=70, a default for a JVM that owns
# its container. This one shares it with its render daemons: on preview.coo.ee (32 GB) the ceiling
# was 22 GB, and G1 held 7-10 GB around a 1.7 GB live heap while the daemons were pushed to swap.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

eval "$(sed -n '/^SERVER_HEAP_PCT=/,/^}$/p' "${entrypoint}")"
declare -F derive_server_heap_mb >/dev/null || {
  echo "FAIL: derive_server_heap_mb not found in ${entrypoint} — the extractor is broken." >&2
  exit 1
}

check() { # eff_mb expected why
  local got; got="$(derive_server_heap_mb "$1")"
  [[ "${got}" == "$2" ]] || {
    echo "FAIL: ${3} — ${1}MB gave ${got}, expected ${2}" >&2
    exit 1
  }
  echo "PASS: ${3} (${1}MB -> ${got}MB)"
}

check 32768 4096 "preview.coo.ee's 32 GB container is held to the ceiling, not 22 GB"
check 12288 3072 "a mid-sized box takes a quarter"
check 4096 2048 "the 4 GB ui-builder container keeps the floor, half for its renderer"
check 2048 1228 "a box smaller than twice the floor is held to 60%, never all of it"
check 0 0 "unknown memory leaves the JVM default"

# Applied through the start script's own variable, never JAVA_TOOL_OPTIONS (the daemons'), and
# skipped for 0, which means "leave the JVM default".
grep -q 'export COMPOSE_PREVIEW_SERVER_OPTS="${COMPOSE_PREVIEW_SERVER_OPTS:-} -Xmx${SERVE_SERVER_HEAP_MB}m"' \
  "${entrypoint}" || {
  echo "FAIL: the heap cap is not appended to COMPOSE_PREVIEW_SERVER_OPTS" >&2
  exit 1
}
if grep -E 'JAVA_TOOL_OPTIONS=.*-Xmx\$\{SERVE_SERVER_HEAP_MB' "${entrypoint}"; then
  echo "FAIL: the server heap cap leaks into JAVA_TOOL_OPTIONS, which every daemon inherits" >&2
  exit 1
fi
grep -q 'SERVE_SERVER_HEAP_MB}" =~ ^\[1-9\]\[0-9\]\*\$' "${entrypoint}" || {
  echo "FAIL: 0 (or a non-number) must leave the JVM default rather than pass -Xmx0m" >&2
  exit 1
}
echo "PASS: applied to the server alone"
