#!/usr/bin/env bash
# Guard: every JVM in the image trims its native heap periodically.
#
# A desktop render daemon frees its Skia buffers after each render, but glibc keeps the memory in
# its malloc arenas: on preview.coo.ee m3-catalog's daemon held 20.2 GB resident around a 160 MB
# Java heap until a manual `jcmd <pid> System.trim_native_heap` returned 19.6 GB. The flag has to
# be in JAVA_TOOL_OPTIONS, which the daemons inherit, not in the server-only options.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"

tool_opts="$(grep -E '^\s*JAVA_TOOL_OPTIONS=' "${dockerfile}" || true)"
[[ -n "${tool_opts}" ]] || {
  echo "FAIL: no JAVA_TOOL_OPTIONS line found in ${dockerfile} — the detector is broken." >&2
  exit 1
}
[[ "${tool_opts}" =~ -XX:TrimNativeHeapInterval=([0-9]+) ]] || {
  echo "FAIL: JAVA_TOOL_OPTIONS does not set -XX:TrimNativeHeapInterval; render daemons keep" >&2
  echo "      freed native memory resident (20 GB on preview.coo.ee)." >&2
  exit 1
}
(( BASH_REMATCH[1] > 0 )) || {
  echo "FAIL: -XX:TrimNativeHeapInterval=0 turns trimming off" >&2
  exit 1
}
echo "PASS: every JVM trims its native heap every ${BASH_REMATCH[1]} ms"
