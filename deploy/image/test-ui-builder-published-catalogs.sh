#!/usr/bin/env bash
# Guard two decisions the image makes about UI-builder catalogs, both of which are invisible in a
# running container until somebody notices the shelf changed.
#
# 1. `wear-m3` is NOT served by default. It is a Wear/Android catalog — Robolectric previews, an
#    Android SDK for its native lane — that nobody is authoring against. A deployment that wants it
#    back sets SERVE_UI_BUILDER_CATALOGS; nothing about the catalog itself changed.
# 2. `--ui-builder-published-catalogs` defaults to `none`, so no catalog takes its definition from
#    a published `ui-builder.json` until somebody names it. m3-catalog publishes one the
#    equivalence gate scores 25 differences against, with zero declared components and zero
#    builtins; remote-m3 publishes none at all. An operator naming a catalog is the opt-in.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

block="$(
  awk '
    /^  args\+=\(--ui-builder-catalogs/ { capture = 1 }
    capture && /^  args\+=\(--ui-builder-state-dir/ { exit }
    capture { print }
  ' "${entrypoint}"
)"
[[ -n "${block}" ]] || {
  echo "FAIL: could not find the UI-builder catalog block in ${entrypoint}" >&2
  exit 1
}

# The block is written to a file and sourced rather than interpolated into `bash -c`: it contains
# `${SERVE_UI_BUILDER_CATALOGS:-...}`, which the OUTER shell would expand, so the case under test
# would never see the variable the case is about.
script="$(mktemp)"
trap 'rm -f "${script}"' EXIT
{
  echo 'set -euo pipefail'
  echo 'args=()'
  printf '%s\n' "${block}"
  echo 'printf "%s\n" "${args[@]:-}"'
} > "${script}"

run_case() {
  env -i PATH="${PATH}" \
    SERVE_UI_BUILDER_CATALOGS="${1:-}" \
    SERVE_UI_BUILDER_PUBLISHED_CATALOGS="${2:-}" \
    bash "${script}"
}

status=0
expect() {
  local label="$1" expected="$2" actual="$3"
  if [[ "${actual}" == *"${expected}"* ]]; then
    echo "ok   ${label}"
  else
    echo "FAIL ${label}: expected to contain '${expected}', got: ${actual//$'\n'/ }" >&2
    status=1
  fi
}
refute() {
  local label="$1" unexpected="$2" actual="$3"
  if [[ "${actual}" == *"${unexpected}"* ]]; then
    echo "FAIL ${label}: expected NOT to contain '${unexpected}', got: ${actual//$'\n'/ }" >&2
    status=1
  else
    echo "ok   ${label}"
  fi
}

default="$(run_case)"
# Guard the guard: every refute below would pass on empty output, so prove the block ran at all
# before believing anything it did not print.
[[ -n "${default}" ]] || {
  echo "FAIL: the extracted block produced no arguments; the test is not exercising it" >&2
  exit 1
}
expect "the default serves m3-catalog and remote-m3" "m3-catalog,remote-m3" "${default}"
refute "the default does not serve wear-m3" "wear-m3" "${default}"
expect "the default withholds the published path from every catalog" \
  $'--ui-builder-published-catalogs\nnone' "${default}"

overridden="$(run_case "m3-catalog,remote-m3,wear-m3")"
expect "an operator can put wear-m3 back" "wear-m3" "${overridden}"

all="$(run_case "" "all")"
expect "an operator can opt every catalog back in" $'--ui-builder-published-catalogs\nall' "${all}"

named="$(run_case "m3-catalog,remote-m3" "m3-catalog")"
expect "a named subset is forwarded verbatim" $'--ui-builder-published-catalogs\nm3-catalog' \
  "${named}"

exit "${status}"
