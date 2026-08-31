#!/usr/bin/env bash
# Guard the preview-host image's built-in Compose/Wasm UI registration. A released image supplies
# the shared `/wasm/<catalog>/` fallback without an operator-created directory or env setting.
# Explicit per-catalog apps remain additive and override the fallback in the server.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

block="$(
  awk '
    /^# The release tarball carries the matching Compose\/Wasm catalog browser\./ { capture = 1 }
    capture && /^# Trusted server-side re-render/ { exit }
    capture { print }
  ' "${entrypoint}"
)"
[[ -n "${block}" ]] || {
  echo "FAIL: could not find the built-in wasm-ui registration in ${entrypoint}" >&2
  exit 1
}

# Exercise the shipped block while redirecting its fixed image path into an isolated fixture.
install="${tmp}/compose-preview-server"
block="${block//\/opt\/compose-preview-server/${install}}"

run_case() {
  local env_value="${1:-}"
  env -i PATH="${PATH}" SERVE_WASM_DIR="${env_value}" bash -c \
    "set -euo pipefail; args=(); ${block}; printf '%s\n' \"\${args[@]}\""
}

expect() {
  local wanted="$1" description="$2" env_value="${3:-}" actual
  actual="$(run_case "${env_value}")"
  if [[ "${actual}" != "${wanted}" ]]; then
    echo "FAIL: ${description}" >&2
    echo "  wanted: ${wanted@Q}" >&2
    echo "  actual: ${actual@Q}" >&2
    exit 1
  fi
  echo "  ok: ${description}"
}

echo "==> Built-in Wasm UI registration"
expect "" "an older distribution without wasm-ui adds no implicit mapping"
expect $'--wasm-dir\nextra=/srv/extra' "an explicit app still works without the built-in" \
  "extra=/srv/extra"

mkdir -p "${install}/wasm-ui"
touch "${install}/wasm-ui/index.html"
built_in="${install}/wasm-ui"
expect $'--wasm-ui-dir\n'"${built_in}" "the packaged UI is enabled as a fallback"
expect $'--wasm-ui-dir\n'"${built_in}"$'\n--wasm-dir\nextra=/srv/extra' \
  "operator apps are appended to the fallback" "extra=/srv/extra"

echo "PASS: the packaged Wasm UI is catalog-scoped and SERVE_WASM_DIR remains an override"
