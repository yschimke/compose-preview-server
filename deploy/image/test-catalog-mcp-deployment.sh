#!/usr/bin/env bash
# Guard the opt-in catalog MCP image wiring. Authentication policy is enforced by the server; this
# test keeps the compose variable and entrypoint flag from silently drifting apart.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

grep -Fq 'SERVE_CATALOG_MCP: "${SERVE_CATALOG_MCP:-}"' "${compose}" || {
  echo "FAIL: compose does not pass SERVE_CATALOG_MCP into the image" >&2
  exit 1
}

gate="$(awk '/^# Remote, per-catalog MCP\./,/^fi$/' "${entrypoint}")"
[[ -n "${gate}" ]] || {
  echo "FAIL: could not find the catalog MCP gate in ${entrypoint}" >&2
  exit 1
}

expect_args() {
  local value="$1" want="$2" got
  got="$(
    SERVE_CATALOG_MCP="${value}" bash -c \
      "set -euo pipefail; args=(); ${gate}; printf '%s' \"\${args[*]-}\""
  )"
  [[ "${got}" == "${want}" ]] || {
    echo "FAIL: SERVE_CATALOG_MCP=${value}: expected '${want}', got '${got}'" >&2
    exit 1
  }
}

expect_args "" ""
expect_args "0" ""
expect_args "1" "--catalog-mcp"
expect_args "true" "--catalog-mcp"

echo "PASS: catalog MCP remains opt-in and maps to --catalog-mcp"
