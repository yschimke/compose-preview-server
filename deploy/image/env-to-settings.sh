#!/usr/bin/env bash
# Draft a deployment's settings.json from the non-secret lines of a box's .env, for review.
#
#   deploy/image/env-to-settings.sh [path/to/.env] > settings.json.draft
#
# settings.json (deploy/image/SETTINGS.md) is where a deployment's non-secret settings live now:
# checked in, reviewed, published to the box by the publish-preview-config workflow. This is the
# one-off move: it reads the .env the box already runs on and prints the settings.json that says
# the same thing, so the operator can diff it into deploy/<deployment>/settings.json, open a pull
# request, and — once the publish has landed and GET /admin/settings shows each value coming from
# settings.json — delete the .env lines env-redundant.sh then reports as duplicates.
#
# SECRETS NEVER LEAVE THE FILE. Only keys the schema lists are read at all, and the schema is
# generated from ServeSettings.kt, which a test keeps free of anything named like a token, secret or
# key. Every other line — SERVE_TOKEN, SERVE_ADMIN_TOKEN, the OAuth and cookie secrets, the
# OpenRouter key — is skipped without its value being looked at, so the draft is safe to paste into
# a pull request. Box and infrastructure settings (memory, seats, sandbox, hostnames) are not in the
# schema either: they stay in .env on purpose.
#
# Needs bash and jq. A host without jq can run it from the image, which carries both:
#   docker compose run --rm --no-deps -v "$PWD/.env:/tmp/box.env:ro" \
#     --entrypoint /usr/local/bin/env-to-settings.sh preview /tmp/box.env
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_file="${1:-${ENV_FILE:-${here}/.env}}"
schema="${SETTINGS_SCHEMA:-${here}/settings.schema.json}"
[[ -f "${schema}" ]] || schema=/etc/compose-preview/settings.schema.json

[[ -f "${env_file}" ]] || { echo "no .env at ${env_file}" >&2; exit 2; }
[[ -f "${schema}" ]] || { echo "no settings schema (settings.schema.json) found" >&2; exit 2; }
command -v jq > /dev/null || { echo "jq is required (or run this from the image; see the header)" >&2; exit 2; }

# Compose's reading of one .env value: one layer of quotes stripped, and for an unquoted value an
# inline ` #` comment dropped. Same rules as env-redundant.sh, which explains why they matter.
compose_value() {
  local raw="$1"
  case "${raw}" in
    \"*\") printf '%s' "${raw:1:${#raw}-2}"; return 0 ;;
    \'*\') printf '%s' "${raw:1:${#raw}-2}"; return 0 ;;
  esac
  local v="${raw}"
  [[ "${v}" == *" #"* ]] && v="${v%% #*}"
  [[ "${v}" == *$'\t#'* ]] && v="${v%%$'\t'#*}"
  while [[ "${v}" == *[[:space:]] ]]; do v="${v%[[:space:]]}"; done
  printf '%s' "${v}"
}

# env -> {path, type, empty} for every managed setting.
mapping="$(jq -c '[paths(type == "object" and has("x-env")) as $p | getpath($p) as $leaf
  | {key: $leaf."x-env", value: {path: [$p[] | select(. != "properties")], type: $leaf.type,
      empty: ($leaf."x-empty" // "")}}] | from_entries' "${schema}")"

# Settings another committed file already owns, so they are pointed at rather than duplicated.
declare -A elsewhere=(
  [SERVE_SITES]='catalogs.json "sites"'
  [SERVE_UI_BUILDER_CATALOGS]='catalogs.json "uiBuilder".catalogs.<id>.serve'
  [SERVE_UI_BUILDER_PUBLISHED_CATALOGS]='catalogs.json "uiBuilder".catalogs.<id>.published'
  [SERVE_UI_BUILDER_CATALOG_OWNERSHIP]='catalogs.json "uiBuilder".catalogs.<id>.owned'
  [SERVE_UI_BUILDER_NATIVE_CATALOGS]='catalogs.json "uiBuilder".catalogs.<id>.nativeCatalog'
  [SERVE_UI_BUILDER_PACKS]='catalogs.json "uiBuilder".packs'
  [SERVE_UI_BUILDER_WIDGET_PLAYER]='catalogs.json "uiBuilder".widgetPlayer'
)

declare -A value_of
order=()
while IFS= read -r line || [[ -n "${line}" ]]; do
  [[ "${line}" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Z_][A-Z0-9_]*)= ]] || continue
  key="${BASH_REMATCH[2]}"
  # Only a managed key's value is ever read. Anything else — every secret included — stops here.
  if [[ -n "${elsewhere[${key}]+set}" ]]; then
    [[ -n "${value_of[${key}]+set}" ]] || order+=("${key}")
    value_of["${key}"]=""
    continue
  fi
  jq -e --arg k "${key}" 'has($k)' <<< "${mapping}" > /dev/null || continue
  [[ -n "${value_of[${key}]+set}" ]] || order+=("${key}")
  # Last assignment wins, as Compose reads it.
  value_of["${key}"]="$(compose_value "${line#*=}")"
done < "${env_file}"

document='{"$schema": "../image/settings.schema.json"}'
moved=()
for key in "${order[@]}"; do
  if [[ -n "${elsewhere[${key}]+set}" ]]; then
    echo "note: ${key} is managed in ${elsewhere[${key}]}, not settings.json — move it there" >&2
    continue
  fi
  value="${value_of[${key}]}"
  # An empty line is the same as no line: nothing to move.
  [[ -n "${value}" ]] || continue
  spec="$(jq -c --arg k "${key}" '.[$k]' <<< "${mapping}")"
  if ! json="$(jq -nc --arg v "${value}" --argjson s "${spec}" '
      if $s.type == "boolean" then
        (if ($v | ascii_downcase) as $b | ["1", "true"] | index($b) then true
         elif ($v | ascii_downcase) as $b | ["0", "false"] | index($b) then false
         else error("expected 1/0 or true/false") end)
      elif $s.type == "integer" then ($v | tonumber? // error("expected a whole number"))
      elif $s.type == "array" then
        (if $s.empty != "" and $v == $s.empty then []
         else [$v | split(",")[] | gsub("^\\s+|\\s+$"; "") | select(. != "")] end)
      else $v end' 2>&1)"; then
    echo "skipped: ${key} — its value is not one settings.json can hold (${json##*: })" >&2
    continue
  fi
  document="$(jq -c --argjson s "${spec}" --argjson v "${json}" 'setpath($s.path; $v)' <<< "${document}")"
  moved+=("${key}")
done

jq . <<< "${document}"
if ((${#moved[@]})); then
  echo "moved ${#moved[@]} setting(s): ${moved[*]}" >&2
  echo "After the publish lands and GET /admin/settings shows them from settings.json, delete" \
    "those .env lines (env-redundant.sh lists them)." >&2
else
  echo "no .env line holds a setting settings.json manages" >&2
fi
