#!/usr/bin/env bash
# Guard the production image defaults that make browser and MCP clients share one durable,
# authenticated UI-builder service. The static Wasm bytes may remain public; design data never is.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# The three UI-builder capabilities are unconditional — the image always packages that lane. The
# `images` half is conditional on the upload repo being named, because the server refuses to start
# when the capability is offered without the lane; `test-agent-grant-image-capability.sh` owns that
# half. Written as one literal because that is how the compose file reads it.
expected_capabilities='ui-builder-read,ui-builder-write,ui-builder-export${SERVE_IMAGE_UPLOAD_REPO:+,images}'

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
  'SERVE_UI_BUILDER_CATALOGS: "${SERVE_UI_BUILDER_CATALOGS:-m3-catalog,remote-m3}"' \
  "${compose}" || {
  echo "FAIL: compose does not selectively enable the reviewed UI-builder catalogs" >&2
  exit 1
}

grep -Fq \
  'args+=(--ui-builder-catalogs "${SERVE_UI_BUILDER_CATALOGS:-m3-catalog,remote-m3}")' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not pass the selective UI-builder catalog allowlist" >&2
  exit 1
}

grep -Fq \
  'args+=(--ui-builder-state-dir "${SERVE_UI_BUILDER_STATE_DIR:-/config/ui-builder-state}")' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not pass the persistent UI-builder state directory" >&2
  exit 1
}

echo "PASS: preview image defaults to selective catalogs, durable state, and scoped capabilities"
