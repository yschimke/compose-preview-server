#!/usr/bin/env bash
# One-off `.env` migrations for an already-deployed box, sourced by setup.sh.
# Kept in its own file (rather than inline in setup.sh) so the rewrite rules can
# be exercised by test-env-migrations.sh without running the real installer —
# these edit an operator's live config, so "only touches what it claims to" has
# to be a test, not a comment.

# The public server briefly pinned only the first three compose-samples apps in
# SERVE_CATALOGS, back when that variable WAS the catalog set. It is now only an
# addition to the operator's catalogs.json (/config/catalogs.json), so the stale
# pin no longer shadows anything — but it does re-add three entries the config
# file already declares, which reads as a mystery on the front page. Dropping it
# leaves the config file as the single source of truth.
LEGACY_COMPOSE_SAMPLES_CATALOGS='jetnews@yschimke/compose-samples,jetchat@yschimke/compose-samples,jetlagged@yschimke/compose-samples'

# True when the line is a SERVE_CATALOGS assignment whose value is exactly the
# legacy list. Tolerates the shapes a hand-edited .env actually shows up in —
# `export `, surrounding quotes, leading/trailing whitespace, CRLF — while still
# comparing the *value*, so an operator's own list is never matched.
_env_line_is_legacy_catalogs() {
  local line="${1%$'\r'}" value
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line#export }"
  line="${line#"${line%%[![:space:]]*}"}"
  [[ "${line}" == SERVE_CATALOGS=* ]] || return 1
  value="${line#SERVE_CATALOGS=}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ ${#value} -ge 2 && ( "${value}" == \"*\" || "${value}" == \'*\' ) ]]; then
    value="${value:1:${#value}-2}"
  fi
  [[ "${value}" == "${LEGACY_COMPOSE_SAMPLES_CATALOGS}" ]]
}

# Drop only the legacy three-app SERVE_CATALOGS assignment from $1, so the next
# `compose up` serves exactly what catalogs.json declares. Any other SERVE_CATALOGS
# value — an operator's own list, or a later override in the same file — is left
# alone. Returns 0 when something was removed (so callers can log), 1 otherwise.
migrate_legacy_serve_catalogs() {
  local env_file="${1:?env file required}"
  [[ -f "${env_file}" ]] || return 1

  local line removed=0 out=""
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if _env_line_is_legacy_catalogs "${line}"; then
      removed=1
      continue
    fi
    out+="${line}"$'\n'
  done < "${env_file}"

  (( removed )) || return 1
  # Truncate-in-place rather than sed -i: keeps the file's 0600 mode, owner and
  # inode, which a temp-file rename would quietly reset on a live box.
  printf '%s' "${out}" > "${env_file}"
}

# The import staging repository's generated output moved: its delivery branches
# and its catalog registry document now live in yschimke/compose-preview-imports-out,
# and the source repository's own copy of the document is frozen and then removed.
# A registry may only serve its own branches, so the nomination has to follow the
# document — a box still nominating the source repository goes on serving frozen
# branches, and then nothing once that copy is deleted.
LEGACY_IMPORTS_REGISTRY='yschimke/compose-preview-imports'
IMPORTS_OUT_REGISTRY='yschimke/compose-preview-imports-out'

# Rewrite one SERVE_CATALOG_REGISTRY line, replacing the legacy nomination when it
# is one of the comma-separated items EXACTLY (no `@ref`: a pinned ref names a
# branch of the source repository, which is an operator's deliberate choice and
# not ours to guess at). Keeps an `export ` prefix and the line's quote style, and
# leaves every other item — and any other key — untouched. Prints the line, and
# returns 0 only when it changed.
_env_rewrite_imports_registry_line() {
  local line="${1%$'\r'}" cr="" lead body prefix="" value quote="" item out="" changed=1
  [[ "$1" == *$'\r' ]] && cr=$'\r'
  lead="${line%%[![:space:]]*}"
  body="${line#"${lead}"}"
  if [[ "${body}" == export\ * ]]; then
    prefix="export "
    body="${body#export }"
  fi
  if [[ "${body}" != SERVE_CATALOG_REGISTRY=* ]]; then
    printf '%s' "$1"
    return 1
  fi
  value="${body#SERVE_CATALOG_REGISTRY=}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ ${#value} -ge 2 && ( "${value}" == \"*\" || "${value}" == \'*\' ) ]]; then
    quote="${value:0:1}"
    value="${value:1:${#value}-2}"
  fi
  local -a items
  IFS=',' read -r -a items <<< "${value}"
  for item in "${items[@]}"; do
    local trimmed="${item#"${item%%[![:space:]]*}"}"
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
    if [[ "${trimmed}" == "${LEGACY_IMPORTS_REGISTRY}" ]]; then
      item="${item/${LEGACY_IMPORTS_REGISTRY}/${IMPORTS_OUT_REGISTRY}}"
      changed=0
    fi
    out+="${out:+,}${item}"
  done
  if (( changed )); then
    printf '%s' "$1"
    return 1
  fi
  printf '%s' "${lead}${prefix}SERVE_CATALOG_REGISTRY=${quote}${out}${quote}${cr}"
}

# Re-point a SERVE_CATALOG_REGISTRY that nominates the import staging repository
# at its output repository. Every assignment is rewritten, not only the last, so
# no line Compose might read is left behind. Returns 0 when something changed (so
# callers can log), 1 otherwise.
migrate_imports_catalog_registry() {
  local env_file="${1:?env file required}"
  [[ -f "${env_file}" ]] || return 1

  local line rewritten changed=0 out=""
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if rewritten="$(_env_rewrite_imports_registry_line "${line}")"; then
      changed=1
    fi
    out+="${rewritten}"$'\n'
  done < "${env_file}"

  (( changed )) || return 1
  # Truncate-in-place, for the same 0600/owner/inode reason as above.
  printf '%s' "${out}" > "${env_file}"
}
