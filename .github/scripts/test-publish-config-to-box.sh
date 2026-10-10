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

echo "an editor pin is PUT, and --prune clears one the file no longer declares"
check "clears an undeclared pin under --prune" "DELETE /admin/editor" \
  "$(run "${BOX_LISTING}" "${STATUS_WITH_REGISTRY}")"
check_absent "leaves the pin alone without --prune" "/admin/editor" "$(BASE_URL=https://example.invalid \
  ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/config" bash "${SCRIPT}" --dry-run 2>&1 || true)"
cat > "${work}/config/catalogs.json" <<'JSON'
{ "editor": { "version": "3.48.0", "sha256": "0000000000000000000000000000000000000000000000000000000000000000" },
  "catalogs": [ { "system": "compose-m3", "repo": "yschimke/compose-ai-tools" } ], "sites": [] }
JSON
out="$(run "${BOX_LISTING}" "${STATUS_WITH_REGISTRY}")"
check "puts the declared pin" 'PUT /admin/editor {"version":"3.48.0"' "${out}"
check_absent "does not clear a declared pin" "DELETE /admin/editor" "${out}"

echo "UI-builder settings are PUT, and --prune clears a block the file no longer declares"
check_absent "has no block to put yet" 'PUT /admin/ui-builder/config' "${out}"
check "clears an undeclared block under --prune" "DELETE /admin/ui-builder/config" "${out}"
check_absent "leaves the block alone without --prune" "/admin/ui-builder/config" "$(BASE_URL=https://example.invalid \
  ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/config" bash "${SCRIPT}" --dry-run 2>&1 || true)"
cat > "${work}/config/catalogs.json" <<'JSON'
{ "uiBuilder": { "catalogs": { "remote-widgets": { "serve": true } } },
  "catalogs": [ { "system": "compose-m3", "repo": "yschimke/compose-ai-tools" } ], "sites": [] }
JSON
out="$(run "${BOX_LISTING}" "${STATUS_WITH_REGISTRY}")"
check "puts the declared block" 'PUT /admin/ui-builder/config {"catalogs":{"remote-widgets":{"serve":true}}}' "${out}"
check_absent "does not clear a declared block" "DELETE /admin/ui-builder/config" "${out}"

echo "settings.json is PUT whole, and --prune clears settings the deployment no longer declares"
check "clears undeclared settings under --prune" "DELETE /admin/settings" "${out}"
check_absent "leaves the box's settings alone without --prune" "/admin/settings" "$(BASE_URL=https://example.invalid \
  ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/config" bash "${SCRIPT}" --dry-run 2>&1 || true)"
cat > "${work}/config/settings.json" <<'JSON'
{ "uiBuilder": { "guidelines": { "model": "deepseek/deepseek-v4.1-flash", "users": ["yschimke"] } } }
JSON
out="$(run "${BOX_LISTING}" "${STATUS_WITH_REGISTRY}")"
check "puts the declared settings" \
  'PUT /admin/settings {"uiBuilder":{"guidelines":{"model":"deepseek/deepseek-v4.1-flash","users":["yschimke"]}}}' "${out}"
check_absent "does not clear declared settings" "DELETE /admin/settings" "${out}"

echo "--prune revokes branch trust producers.json no longer declares, and nothing else"
cat > "${work}/config/producers.json" <<'JSON'
{ "branches": [
    { "repo": "yschimke/compose-ai-tools", "branch": "design-artifacts/*" },
    { "repo": "yschimke/compose-preview-imports-out", "branch": "design-artifacts/*" } ] }
JSON
BOX_TRUST='{"branches":[{"repo":"yschimke/compose-ai-tools","branch":"design-artifacts/*"},{"repo":"yschimke/compose-preview-imports-out","branch":"design-artifacts/*"},{"repo":"yschimke/compose-samples","branch":"design-artifacts/*"}],"keys":[{"keyId":"k1"}],"oidc":["repo:x/y:*"]}'
run_trust() {
  BASE_URL=https://example.invalid ADMIN_TOKEN=unused \
    DEPLOY_CONFIG_DIR="${work}/config" \
    PRUNE_BOX_CATALOGS_JSON="${BOX_LISTING}" PRUNE_STATUS_JSON="${STATUS_WITH_REGISTRY}" \
    PRUNE_BOX_TRUST_JSON="$1" \
    bash "${SCRIPT}" --dry-run "${@:2}" 2>&1 || true
}
out="$(run_trust "${BOX_TRUST}" --prune)"
check "revokes the undeclared branch, query-encoded" \
  "DELETE /admin/trust?kind=branch&repo=yschimke%2Fcompose-samples&branch=design-artifacts%2F%2A" "${out}"
check_absent "leaves a declared branch alone" "repo=yschimke%2Fcompose-ai-tools&" "${out}"
check_absent "leaves the registry's declared output repository alone" \
  "repo=yschimke%2Fcompose-preview-imports-out" "${out}"
check_absent "never touches keys or oidc" "kind=key" "${out}"
check_absent "never touches oidc" "kind=oidc" "${out}"
check_absent "revokes nothing without --prune" "DELETE /admin/trust" "$(run_trust "${BOX_TRUST}")"
out="$(run_trust 'not json' --prune)"
check "an unreadable listing revokes nothing, loudly" "could not read /admin/trust" "${out}"
check_absent "and issues no delete" "DELETE /admin/trust" "${out}"
cat > "${work}/config/producers.json" <<'JSON'
{ "producers": [] }
JSON
out="$(run_trust "${BOX_TRUST}" --prune)"
check "refuses to revoke everything from a file declaring no branches" \
  "refusing to revoke every trusted producer" "${out}"
check_absent "and issues no delete" "DELETE /admin/trust" "${out}"

# ---- applying config that waits for a restart ------------------------------------------------
mkdir -p "${work}/restart"
cat > "${work}/restart/catalogs.json" <<'JSON'
{ "catalogs": [], "sites": [],
  "uiBuilder": { "catalogs": { "remote-m3": { "owned": true } } } }
JSON
cat > "${work}/restart/producers.json" <<'JSON'
{ "producers": [] }
JSON
run_restart() {
  BASE_URL=https://example.invalid ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/restart" \
    bash "${SCRIPT}" --dry-run "$@" 2>&1 || true
}

echo "a block that can owe a restart is followed by the restart hook, after every PUT"
out="$(run_restart)"
check "names the restart hook" "POST /__hooks/restart" "${out}"
put_line=$(printf '%s\n' "${out}" | grep -n 'PUT /admin/ui-builder/config' | cut -d: -f1)
hook_line=$(printf '%s\n' "${out}" | grep -n 'POST /__hooks/restart' | cut -d: -f1)
check "restarts only after the config is written" "after" \
  "$([[ -n "${put_line}" && -n "${hook_line}" && "${hook_line}" -gt "${put_line}" ]] && echo after || echo before)"
out="$(run_restart --no-restart)"
check "--no-restart leaves the restart to the operator" "--no-restart given" "${out}"
check_absent "and calls no hook" "POST /__hooks/restart" "${out}"
out="$(NO_RESTART=1 run_restart)"
check_absent "NO_RESTART=1 does the same" "POST /__hooks/restart" "${out}"
cat > "${work}/restart/nothing.json" <<'JSON'
{ "catalogs": [], "sites": [] }
JSON
out="$(BASE_URL=https://example.invalid ADMIN_TOKEN=unused DEPLOY_CONFIG_DIR="${work}/restart" \
  CATALOGS_FILE="${work}/restart/nothing.json" SETTINGS_FILE=/nonexistent \
  bash "${SCRIPT}" --dry-run 2>&1 || true)"
check_absent "nothing that can owe a restart calls no hook" "__hooks/restart" "${out}"

# The live path, against a stub `curl` on PATH: the box answers the PUT with restartRequired, the
# hook first reports a rollout in progress (409) and then accepts, and the box keeps owing the
# restart for two reads before the new replica is the only one answering.
mkdir -p "${work}/bin" "${work}/state"
cat > "${work}/bin/curl" <<'STUB'
#!/usr/bin/env bash
method=GET; wfmt=""; out=""; url=""; auth=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -X) method="$2"; shift 2 ;;
    -w) wfmt="$2"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -d|-m) shift 2 ;;
    -H) [[ "$2" == Authorization:* ]] && auth="$2"; shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
path="/${url#*://*/}"
echo "${method} ${path} ${auth}" >> "${STUB_STATE}/calls"
count() { local n; n=$(( $(cat "${STUB_STATE}/$1" 2>/dev/null || echo 0) + 1 )); echo "$n" > "${STUB_STATE}/$1"; echo "$n"; }
body='{}'; code=200
case "${method} ${path}" in
  "PUT /admin/ui-builder/config") body='{"restartRequired":true,"next":{"catalogs":["remote-m3"]}}' ;;
  "POST /__hooks/restart") [[ "$(count hook)" == 1 ]] && code=409 || code=202; body="" ;;
  "GET /admin/ui-builder/config") [[ "$(count owed)" -le 2 ]] && body='{"restartRequired":true}' || body='{"restartRequired":false}' ;;
  "GET /admin/catalogs") body='{"catalogs":[]}' ;;
esac
if [[ -n "${out}" ]]; then printf '%s' "${body}" > "${out}"; fi
if [[ -n "${wfmt}" ]]; then
  [[ -z "${out}" ]] && printf '%s' "${body}"
  printf '%s' "${wfmt//%\{http_code\}/${code}}"
else
  printf '%s' "${body}"
fi
STUB
chmod +x "${work}/bin/curl"

echo "a PUT that owes a restart restarts the box through the hook and waits until it serves"
out="$(PATH="${work}/bin:${PATH}" STUB_STATE="${work}/state" \
  BASE_URL=https://example.invalid ADMIN_TOKEN=admin DEPLOY_HOOK_TOKEN=hook \
  DEPLOY_CONFIG_DIR="${work}/restart" SETTINGS_FILE=/nonexistent \
  RESTART_POLL_SECONDS=0 RESTART_RETRY_SECONDS=0 RESTART_CONSECUTIVE=2 RESTART_WAIT_SECONDS=60 \
  bash "${SCRIPT}" 2>&1 || true)"
calls="$(cat "${work}/state/calls")"
check "posts the restart hook with the hook token" "POST /__hooks/restart Authorization: Bearer hook" "${calls}"
check "retries a restart refused while a rollout runs" "2" "$(cat "${work}/state/hook")"
check "waits until the box no longer owes the restart" "the box serves the published config" "${out}"
check_absent "and does not report an error" "::error::" "${out}"

echo "a hook token that is unset leaves the config to the next start, loudly"
rm -f "${work}/state/"*
out="$(PATH="${work}/bin:${PATH}" STUB_STATE="${work}/state" \
  BASE_URL=https://example.invalid ADMIN_TOKEN=admin \
  DEPLOY_CONFIG_DIR="${work}/restart" SETTINGS_FILE=/nonexistent \
  bash "${SCRIPT}" 2>&1 || true)"
check "says the restart is owed" "DEPLOY_HOOK_TOKEN is unset" "${out}"
check_absent "and calls no hook" "__hooks/restart" "$(cat "${work}/state/calls")"

echo "a restart that never takes effect fails the publish"
rm -f "${work}/state/"*
sed -i 's/\[\[ "$(count owed)" -le 2 \]\]/[[ "$(count owed)" -le 1000 ]]/' "${work}/bin/curl"
out="$(PATH="${work}/bin:${PATH}" STUB_STATE="${work}/state" \
  BASE_URL=https://example.invalid ADMIN_TOKEN=admin DEPLOY_HOOK_TOKEN=hook \
  DEPLOY_CONFIG_DIR="${work}/restart" SETTINGS_FILE=/nonexistent \
  RESTART_POLL_SECONDS=1 RESTART_RETRY_SECONDS=0 RESTART_WAIT_SECONDS=2 \
  bash "${SCRIPT}" 2>&1; echo "exit=$?")"
check "names why" "still owes a restart" "${out}"
check "and exits non-zero" "exit=1" "${out}"

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} check(s) failed"
  exit 1
fi
echo "All publish-config-to-box checks passed."
