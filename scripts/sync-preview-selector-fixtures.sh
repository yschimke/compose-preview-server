#!/usr/bin/env bash
#
# Sync (or check) the vendored preview-selector fixture table.
#
# `--id`, `--filter` and `--preview` are answered by two implementations in two repositories:
# `previewIdMatchesStandaloneRequest` here, and `previewIdMatchesRequest` in compose-ai-tools.
# Since `compose-preview serve` became a launcher (#180 step 4) the CLI no longer hands its rule
# in, so nothing pinned the two against each other (compose-ai-tools#5185). The table does:
# compose-ai-tools owns it, this repository vendors the copy below, and `PreviewSelectorFixturesTest`
# on each side runs every case through its own rule.
#
# The copy is compared against the release this repository *pins* — `composeai-tools` in
# gradle/libs.versions.toml — not against upstream `main`. A change over there is not adoptable
# until it ships, so `--check` goes red on the pin bump rather than the day the change lands, which
# is the moment the obligation is real.
#
# Usage:
#   scripts/sync-preview-selector-fixtures.sh           # rewrite the vendored copy
#   scripts/sync-preview-selector-fixtures.sh --check   # fail on drift, write nothing (CI)

set -euo pipefail

readonly UPSTREAM_REPO="yschimke/compose-ai-tools"
readonly FIXTURES="docs/serve/preview-selector-fixtures.json"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${repo_root}"

check_only=false
case "${1-}" in
  --check) check_only=true ;;
  "") ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

pinned=$(sed -n 's/^composeai-tools = "\(.*\)"$/\1/p' gradle/libs.versions.toml)
if [ -z "${pinned}" ]; then
  echo "could not read the composeai-tools version pin from gradle/libs.versions.toml" >&2
  exit 1
fi
url="https://raw.githubusercontent.com/${UPSTREAM_REPO}/v${pinned}/${FIXTURES}"

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
  # The pinned release predates the table. Not a failure: a consumer cannot vendor a file that has
  # not shipped, and failing here would deadlock the pin bump against the release that carries it.
  echo "note: ${FIXTURES} is not in ${UPSTREAM_REPO} v${pinned} yet — nothing to sync."
  echo "note: it starts being checked once the composeai-tools pin reaches the release carrying it."
  exit 0
fi

if [ "${status}" != "200" ]; then
  echo "could not fetch ${url} (HTTP ${status})" >&2
  exit 1
fi

if [ "${check_only}" = true ]; then
  if diff --unified "${FIXTURES}" "${upstream}" >/dev/null 2>&1; then
    echo "${FIXTURES} matches ${UPSTREAM_REPO} v${pinned}."
    exit 0
  fi
  echo "${FIXTURES} has drifted from ${UPSTREAM_REPO} v${pinned}:" >&2
  diff --unified --label "vendored/${FIXTURES}" --label "v${pinned}/${FIXTURES}" \
    "${FIXTURES}" "${upstream}" >&2 || true
  echo >&2
  echo "Run scripts/sync-preview-selector-fixtures.sh and re-run the server test suite." >&2
  exit 1
fi

cp "${upstream}" "${FIXTURES}"
echo "synced ${FIXTURES} from ${UPSTREAM_REPO} v${pinned}."
