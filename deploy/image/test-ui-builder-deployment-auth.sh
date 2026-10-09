#!/usr/bin/env bash
# Guard the production image defaults that make browser and MCP clients share one durable,
# authenticated UI-builder service. The static Wasm bytes may remain public; design data never is.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
example="${ENV_EXAMPLE_FILE:-${here}/.env.example}"

# The three UI-builder capabilities are unconditional — the image always packages that lane. The
# `images` half is conditional on the upload repo being named, because the server refuses to start
# when the capability is offered without the lane; `test-agent-grant-image-capability.sh` owns that
# half. Written as one literal because that is how the entrypoint reads it.
expected_capabilities='ui-builder-read,ui-builder-write,ui-builder-export${SERVE_IMAGE_UPLOAD_REPO:+,images}'

grep -Fq \
  ": \"\${SERVE_AGENT_GRANT_CAPABILITIES:=${expected_capabilities}}\"" \
  "${entrypoint}" || {
  echo "FAIL: the entrypoint does not offer the three UI-builder grant capabilities by default" >&2
  exit 1
}

grep -Fq \
  'SERVE_UI_BUILDER_STATE_DIR: "${SERVE_UI_BUILDER_STATE_DIR:-/config/ui-builder-state}"' \
  "${compose}" || {
  echo "FAIL: compose does not persist UI-builder state on /config by default" >&2
  exit 1
}

grep -Fq \
  'SERVE_UI_BUILDER_CATALOGS: "${SERVE_UI_BUILDER_CATALOGS:-m3-catalog}"' \
  "${compose}" || {
  echo "FAIL: compose does not selectively enable the reviewed UI-builder catalogs" >&2
  exit 1
}

grep -Fq \
  'args+=(--ui-builder-catalogs "${ui_builder_catalogs}")' \
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

# Compose passes it through EMPTY, so the entrypoint's default below — or settings.json's
# `uiBuilder.adminActors` — decides; a compose default would beat both.
grep -Fq \
  'SERVE_UI_BUILDER_ADMIN_ACTORS: "${SERVE_UI_BUILDER_ADMIN_ACTORS:-}"' \
  "${compose}" || {
  echo "FAIL: compose gives the UI-builder administrators a default of its own" >&2
  exit 1
}

grep -Fq \
  ': "${SERVE_UI_BUILDER_ADMIN_ACTORS:=github:yschimke}"' \
  "${entrypoint}" || {
  echo "FAIL: the image entrypoint does not repair an empty legacy compose pass-through" >&2
  exit 1
}

grep -Fq \
  '[[ "${SERVE_UI_BUILDER_ADMIN_ACTORS}" != "none" ]]' \
  "${entrypoint}" || {
  echo "FAIL: the image entrypoint does not preserve a UI-builder administrator opt-out" >&2
  exit 1
}

grep -Fq \
  'args+=(--ui-builder-admin-actors "${SERVE_UI_BUILDER_ADMIN_ACTORS}")' \
  "${entrypoint}" || {
  echo "FAIL: entrypoint does not pass the UI-builder administrator actor allowlist" >&2
  exit 1
}

# The example names it only as an override of settings.json (`uiBuilder.adminActors`); the
# entrypoint's default above is what makes github:yschimke the administrator.
grep -Fxq '# SERVE_UI_BUILDER_ADMIN_ACTORS=github:yschimke' "${example}" || {
  echo "FAIL: the preview host example does not show github:yschimke as the UI-builder admin" >&2
  exit 1
}

echo "PASS: preview image defaults to selective catalogs, durable state, scoped capabilities, and the configured UI-builder administrator"
