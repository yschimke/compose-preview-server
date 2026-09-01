#!/usr/bin/env bash
# Guard the production image defaults that make browser and MCP clients share one durable,
# authenticated UI-builder service. The static Wasm bytes may remain public; design data never is.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

expected_capabilities='ui-builder-read,ui-builder-write,ui-builder-export'

grep -Fq \
  "SERVE_AGENT_GRANT_CAPABILITIES: \"\${SERVE_AGENT_GRANT_CAPABILITIES:-${expected_capabilities}}\"" \
  "${compose}" || {
  echo "FAIL: compose does not offer the three UI-builder grant capabilities by default" >&2
  exit 1
}

grep -Fq \
  'SERVE_UI_BUILDER_STATE_DIR: "${SERVE_UI_BUILDER_STATE_DIR:-/config/ui-builder-state}"' \
  "${compose}" || {
  echo "FAIL: compose does not persist UI-builder state on /config by default" >&2
  exit 1
}

grep -Fq \
  'args+=(--ui-builder-state-dir "${SERVE_UI_BUILDER_STATE_DIR:-/config/ui-builder-state}")' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not pass the persistent UI-builder state directory" >&2
  exit 1
}

echo "PASS: preview image defaults to durable UI-builder state and scoped agent capabilities"
