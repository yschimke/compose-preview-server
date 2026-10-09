#!/usr/bin/env bash
# Guard: the entrypoint applies /config/settings.json as environment variables, below .env.
#
# settings.json is how a deployment's non-secret settings reach the box (deploy/image/SETTINGS.md),
# and the entrypoint is where they turn into the SERVE_* variables everything below it derives
# from. This exercises the real function against the committed schema, so a schema change that the
# merge cannot read — or a merge that lets settings.json beat an .env line — fails here.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
schema="${here}/settings.schema.json"

command -v jq > /dev/null || { echo "SKIP: jq not installed"; exit 0; }

eval "$(sed -n '/^apply_settings_file() {$/,/^}$/p' "${entrypoint}")"
declare -F apply_settings_file > /dev/null || {
  echo "FAIL: apply_settings_file not found in ${entrypoint} — the extractor is broken." >&2
  exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
cat > "${work}/settings.json" <<'JSON'
{
  "$schema": "../image/settings.schema.json",
  "uiBuilder": {
    "adminActors": [],
    "guidelines": {"model": "deepseek/deepseek-v4.1-flash", "users": ["yschimke", "octocat"]}
  },
  "catalogs": {"mcp": true},
  "uploads": {"acceptImages": false, "imageRepository": "yschimke/compose-ai-tools"},
  "auth": {"github": {"clientId": "Iv1.public", "openUiBuilder": true}}
}
JSON

fail() { echo "FAIL: $*" >&2; exit 1; }

# The preview role: everything applies, except what the environment already set.
(
  # Compose passes an unset .env line through as "" — that is unset, not an override.
  export SERVE_UI_BUILDER_GUIDELINES_MODEL=""
  # A real .env line: it wins over settings.json.
  export SERVE_CATALOG_MCP=0
  unset SERVE_UI_BUILDER_GUIDELINES_USERS SERVE_ACCEPT_IMAGES SERVE_UI_BUILDER_ADMIN_ACTORS \
    SERVE_GITHUB_AUTH_OPEN_UI_BUILDER SERVE_SETTINGS_SOURCES
  apply_settings_file "${work}/settings.json" "${schema}" preview 2> /dev/null

  [[ "${SERVE_UI_BUILDER_GUIDELINES_MODEL}" == "deepseek/deepseek-v4.1-flash" ]] ||
    fail "the model was not applied: '${SERVE_UI_BUILDER_GUIDELINES_MODEL}'"
  [[ "${SERVE_UI_BUILDER_GUIDELINES_USERS}" == "yschimke,octocat" ]] ||
    fail "a list was not comma-joined: '${SERVE_UI_BUILDER_GUIDELINES_USERS}'"
  [[ "${SERVE_ACCEPT_IMAGES}" == "0" ]] || fail "false did not become 0: '${SERVE_ACCEPT_IMAGES}'"
  [[ "${SERVE_GITHUB_AUTH_OPEN_UI_BUILDER}" == "1" ]] || fail "true did not become 1"
  [[ "${SERVE_UI_BUILDER_ADMIN_ACTORS}" == "none" ]] ||
    fail "an empty admin list must be 'none', or the image default comes back: '${SERVE_UI_BUILDER_ADMIN_ACTORS}'"
  [[ "${SERVE_CATALOG_MCP}" == "0" ]] || fail "settings.json beat an .env line"
  [[ ",${SERVE_SETTINGS_SOURCES}," == *",SERVE_CATALOG_MCP=environment,"* ]] ||
    fail "the override was not recorded: ${SERVE_SETTINGS_SOURCES}"
  [[ ",${SERVE_SETTINGS_SOURCES}," == *",SERVE_UI_BUILDER_GUIDELINES_MODEL=settings.json,"* ]] ||
    fail "the applied value was not recorded: ${SERVE_SETTINGS_SOURCES}"
  echo "PASS: preview role — settings.json below .env, values spelled as the variables read them"
)

# The playground shares /config, and must take only the settings its role reads.
(
  unset SERVE_CATALOG_MCP SERVE_GITHUB_AUTH_CLIENT_ID SERVE_UI_BUILDER_GUIDELINES_MODEL \
    SERVE_SETTINGS_SOURCES
  apply_settings_file "${work}/settings.json" "${schema}" playground 2> /dev/null
  [[ -z "${SERVE_CATALOG_MCP:-}" ]] || fail "the playground took a preview-only setting"
  [[ -z "${SERVE_UI_BUILDER_GUIDELINES_MODEL:-}" ]] || fail "the playground took the guidelines model"
  [[ "${SERVE_GITHUB_AUTH_CLIENT_ID:-}" == "Iv1.public" ]] ||
    fail "the playground must sign in with the same client id as preview"
  echo "PASS: playground role — only the settings both containers must agree on"
)

# A broken file is reported and skipped; the box still comes up.
(
  printf '{"uiBuilder": ' > "${work}/broken.json"
  unset SERVE_UI_BUILDER_GUIDELINES_MODEL
  out="$(apply_settings_file "${work}/broken.json" "${schema}" preview 2>&1)" ||
    fail "a broken settings.json must not stop the entrypoint"
  [[ "${out}" == *"not applied"* ]] || fail "a broken settings.json was not reported: ${out}"
  [[ -z "${SERVE_UI_BUILDER_GUIDELINES_MODEL:-}" ]] || fail "a broken file applied something"
  echo "PASS: a broken settings.json is reported and skipped"
)

# No file, and `none`: nothing happens.
(
  apply_settings_file "${work}/absent.json" "${schema}" preview
  apply_settings_file none "${schema}" preview
  [[ -z "${SERVE_SETTINGS_SOURCES:-}" ]] || fail "an absent file recorded sources"
  echo "PASS: no settings.json is a no-op"
)

# Compose must pass every managed variable through EMPTY by default: a non-empty compose default is
# "set" by the time the entrypoint runs, so settings.json could never fill it. That is how the
# admin actors, the Umami switch and the grant capabilities first shipped.
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
# The `preview` service only: the playground deliberately pins a few (SERVE_ACCEPT_DOCS) for itself,
# and its role does not read those settings from settings.json anyway.
preview_env="$(awk '/^  preview:$/ { on = 1; next } on && /^  [a-z][a-z-]*:$/ { exit } on' "${compose}")"
[[ -n "${preview_env}" ]] || fail "no preview service in ${compose} — the slicer is broken"
while IFS= read -r env; do
  line="$(grep -E "^\s+${env}: " <<< "${preview_env}" || true)"
  [[ -z "${line}" || "${line}" =~ ^[[:space:]]+${env}:\ \"\$\{${env}:-\}\"$ ]] ||
    fail "compose gives ${env} a default of its own, so settings.json cannot set it: ${line}"
done < <(jq -r '[paths(type == "object" and has("x-env")) as $p | getpath($p) | ."x-env"] | .[]' "${schema}")
echo "PASS: compose leaves every managed setting for settings.json to fill"

# The image carries what the merge needs.
grep -q '^COPY settings.schema.json /etc/compose-preview/settings.schema.json$' "${here}/Dockerfile" ||
  fail "the Dockerfile does not bake the schema the entrypoint reads"
grep -qE '^\s+curl ca-certificates git bubblewrap jq$' "${here}/Dockerfile" ||
  fail "the Dockerfile does not install jq, which the merge needs"
echo "PASS: the image bakes the schema and jq"
