#!/usr/bin/env bash
#
# Sync (or check) the vendored UI-builder design-guideline rules.
#
# The `guidelines` check of `ui_builder_check_design` and the editor's own guidelines check ask a
# model the same questions, and must ask them from the same rules: compose-ui-builder owns
# `docs/guidelines/android-design-guidelines.json`, and this repository vendors the copy below as a
# server resource. The copy is compared against the release this repository *pins* —
# `composeai-ui-builder` in gradle/libs.versions.toml — not against upstream `main`, so `--check`
# goes red on the pin bump that changed the rules, which is when re-syncing becomes an obligation.
#
# Usage:
#   scripts/sync-ui-builder-guidelines.sh           # rewrite the vendored copy
#   scripts/sync-ui-builder-guidelines.sh --check   # fail on drift, write nothing (CI)

set -euo pipefail

readonly UPSTREAM_REPO="yschimke/compose-ui-builder"
readonly UPSTREAM_PATH="docs/guidelines/android-design-guidelines.json"
readonly VENDORED="server/src/main/resources/ee/schimke/composeai/cli/serve/guidelines/android-design-guidelines.json"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${repo_root}"

check_only=false
case "${1-}" in
  --check) check_only=true ;;
  "") ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

pinned=$(sed -n 's/^composeai-ui-builder = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
if [ -z "${pinned}" ]; then
  echo "could not read the composeai-ui-builder version pin from gradle/libs.versions.toml" >&2
  exit 1
fi
url="https://raw.githubusercontent.com/${UPSTREAM_REPO}/v${pinned}/${UPSTREAM_PATH}"

upstream=$(mktemp)
trap 'rm -f "${upstream}"' EXIT

# Retried, because a GitHub blip is not drift. A 404 is not retried: it is an answer.
status=""
for attempt in 1 2 3; do
  status=$(curl --silent --show-error --location --max-time 30 \
    --output "${upstream}" --write-out '%{http_code}' "${url}" || echo "000")
  case "${status}" in
    200|404) break ;;
    *) if [ "${attempt}" -lt 3 ]; then sleep $((attempt * 2)); fi ;;
  esac
done

if [ "${status}" = "404" ]; then
  # The pinned release predates the rules. Not a failure: the vendored copy cannot follow a file
  # that has not shipped, and failing here would deadlock the pin bump against the release carrying it.
  echo "note: ${UPSTREAM_PATH} is not in ${UPSTREAM_REPO} v${pinned} yet — nothing to sync."
  echo "note: it starts being checked once the composeai-ui-builder pin reaches the release carrying it."
  exit 0
fi

if [ "${status}" != "200" ]; then
  echo "could not fetch ${url} (HTTP ${status})" >&2
  exit 1
fi

if [ "${check_only}" = true ]; then
  if diff --unified "${VENDORED}" "${upstream}" >/dev/null 2>&1; then
    echo "${VENDORED} matches ${UPSTREAM_REPO} v${pinned}."
    exit 0
  fi
  echo "${VENDORED} has drifted from ${UPSTREAM_REPO} v${pinned}:" >&2
  diff --unified --label "vendored" --label "v${pinned}/${UPSTREAM_PATH}" \
    "${VENDORED}" "${upstream}" >&2 || true
  echo >&2
  echo "Run scripts/sync-ui-builder-guidelines.sh and re-run ServeUiBuilderGuidelinesTest." >&2
  exit 1
fi

cp "${upstream}" "${VENDORED}"
echo "synced ${VENDORED} from ${UPSTREAM_REPO} v${pinned}."
