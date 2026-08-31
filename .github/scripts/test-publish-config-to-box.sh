#!/usr/bin/env bash
# Self-test for publish-config-to-box.sh's --prune pass, driven through --dry-run so it talks to
# no server. The listing and the status document are injected (PRUNE_BOX_CATALOGS_JSON /
# PRUNE_STATUS_JSON), which is the same seam the stall-probe timeout uses.
#
# What is actually being pinned here is the three-set difference: retire what the box serves minus
# what the file declares MINUS what a nominated registry contributes. The third term is the one
# worth a test — without it every registry catalog is deleted on every publish, and it comes back
# on the next refresh, so the bug looks like flapping rather than a bad diff.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/publish-config-to-box.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
failures=0

check() {
  local label="$1" expected="$2" actual="$3"
  if [[ "${actual}" == *"${expected}"* ]]; then
    echo "  ok: ${label}"
  else
    echo "  FAIL: ${label}"
    echo "    expected to contain: ${expected}"
    echo "    got: ${actual}"
    failures=$((failures + 1))
  fi
}
check_absent() {
  local label="$1" unexpected="$2" actual="$3"
  if [[ "${actual}" != *"${unexpected}"* ]]; then
    echo "  ok: ${label}"
  else
    echo "  FAIL: ${label} — output unexpectedly contained: ${unexpected}"
    failures=$((failures + 1))
  fi
}

mkdir -p "${work}/config"
cat > "${work}/config/catalogs.json" <<'JSON'
{ "catalogs": [ { "system": "compose-m3", "repo": "yschimke/compose-ai-tools" } ], "sites": [] }
JSON
cat > "${work}/config/producers.json" <<'JSON'
{ "producers": [] }
JSON

# The box serves the declared catalog, a registry-contributed one, and one nobody declares.
BOX_LISTING='{"catalogs":[{"system":"compose-m3"},{"system":"joreilly-bikeshare"},{"system":"abandoned-catalog"}]}'
STATUS_WITH_REGISTRY='{"config":{"catalogRegistries":[{"repo":"yschimke/compose-preview-imports","systems":["joreilly-bikeshare"]}]}}'
STATUS_OLD_BOX='{"config":{}}'

run() {
  BASE_URL=https://example.invalid ADMIN_TOKEN=unused \
    DEPLOY_CONFIG_DIR="${work}/config" \
    PRUNE_BOX_CATALOGS_JSON="$1" PRUNE_STATUS_JSON="$2" \
    bash "${SCRIPT}" --dry-run --prune 2>&1 || true
}

echo "prune retires only what is neither declared nor registry-contributed"
out="$(run "${BOX_LISTING}" "${STATUS_WITH_REGISTRY}")"
check "retires the abandoned catalog" "DELETE /admin/catalogs/abandoned-catalog" "${out}"
check_absent "leaves the registry-contributed catalog alone" "/admin/catalogs/joreilly-bikeshare" "${out}"
check_absent "leaves the declared catalog alone" "DELETE /admin/catalogs/compose-m3" "${out}"

echo "prune refuses on a box that cannot report its registries"
out="$(run "${BOX_LISTING}" "${STATUS_OLD_BOX}")"
check "refuses rather than guessing" "needs config.catalogRegistries" "${out}"
check_absent "retires nothing on refusal" "DELETE /admin/catalogs/" "${out}"

echo "without --prune nothing is retired"
out="$(BASE_URL=https://example.invalid ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/config" \
  bash "${SCRIPT}" --dry-run 2>&1 || true)"
check_absent "additive by default" "DELETE /admin/catalogs/abandoned-catalog" "${out}"

echo "an unknown flag is refused"
out="$(BASE_URL=https://example.invalid ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/config" \
  bash "${SCRIPT}" --prunee 2>&1 || true)"
check "typo'd flag does not read as a successful prune" "unknown argument" "${out}"

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} check(s) failed"
  exit 1
fi
echo "All publish-config-to-box prune checks passed."
