#!/usr/bin/env bash
# Guard: the image's server JVM exits on OutOfMemoryError, and only the server JVM.
#
# Why this needs a test. A heap OutOfMemoryError on a coroutine worker kills Ktor CIO's accept loop
# and leaves the JVM running, so the container stays up with nothing listening and `restart: always`
# never fires (ui.coo.ee, 2026-11-25: five hours of 502s that a `docker restart` fixed). The flag
# that turns that into a restart is one word in a long ENV block, and moving it into
# JAVA_TOOL_OPTIONS would look like a tidy-up while making every daemon that runs out of heap take
# the whole server down with it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"
[[ -f "${dockerfile}" ]] || {
  echo "FAIL: missing ${dockerfile}" >&2
  exit 1
}

server_opts="$(grep -E '^\s*COMPOSE_PREVIEW_SERVER_OPTS=' "${dockerfile}" || true)"
[[ "${server_opts}" == *"-XX:+ExitOnOutOfMemoryError"* ]] || {
  echo "FAIL: ${dockerfile} does not set COMPOSE_PREVIEW_SERVER_OPTS with -XX:+ExitOnOutOfMemoryError;" >&2
  echo "      a heap OOM would leave the server running with a dead listener." >&2
  exit 1
}

tool_opts="$(grep -E '^\s*JAVA_TOOL_OPTIONS=' "${dockerfile}" || true)"
[[ -n "${tool_opts}" ]] || {
  echo "FAIL: no JAVA_TOOL_OPTIONS line found in ${dockerfile} — the detector is broken." >&2
  exit 1
}
[[ "${tool_opts}" != *"OnOutOfMemoryError"* ]] || {
  echo "FAIL: JAVA_TOOL_OPTIONS carries an OnOutOfMemoryError flag; spawned daemon JVMs inherit it." >&2
  echo "      Keep it in COMPOSE_PREVIEW_SERVER_OPTS, which only the server's start script reads." >&2
  exit 1
}

echo "PASS: the server JVM exits on OutOfMemoryError; daemon JVMs are left to the host"
