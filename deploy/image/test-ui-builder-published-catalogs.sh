#!/usr/bin/env bash
# Guard two decisions the image makes about UI-builder catalogs, both of which are invisible in a
# running container until somebody notices the shelf changed.
#
# 1. The served allowlist is `m3-catalog,remote-m3,wear-m3`, and it is an ALLOWLIST: publishing
#    another preview catalog does not expose a builder for it. `wear-m3` spent a period off this
#    list on cost grounds — a Wear/Android catalog needs Robolectric previews and an Android SDK
#    for its native lane, and nobody was authoring against it — so both directions are asserted
#    here: the default carries it, and an operator can still take it out with
#    SERVE_UI_BUILDER_CATALOGS.
# 2. `--ui-builder-published-catalogs` defaults to `none`, so no catalog takes its definition from
#    a published `ui-builder.json` until somebody names it. The reasons are per catalog and they
#    move — this file used to say m3-catalog scored 25 differences with zero declared components
#    and that remote-m3 published no file at all, and none of that is true any more — so the
#    measurements live in the entrypoint beside the lever. What this asserts is only the shape:
#    the default names no catalog, and an operator naming one is the opt-in.
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
expect "the default serves m3-catalog, remote-m3 and wear-m3" "m3-catalog,remote-m3,wear-m3" \
  "${default}"
expect "the default withholds the published path from every catalog" \
  $'--ui-builder-published-catalogs\nnone' "${default}"

# The symmetric guard, and the one the default no longer covers: a box that does not want to pay
# for the Wear/Android lane can drop it, and dropping it must not disturb the other two.
narrowed="$(run_case "m3-catalog,remote-m3")"
refute "an operator can take wear-m3 out" "wear-m3" "${narrowed}"
expect "taking wear-m3 out leaves the other two" "m3-catalog,remote-m3" "${narrowed}"

all="$(run_case "" "all")"
expect "an operator can opt every catalog back in" $'--ui-builder-published-catalogs\nall' "${all}"

named="$(run_case "m3-catalog,remote-m3" "m3-catalog")"
expect "a named subset is forwarded verbatim" $'--ui-builder-published-catalogs\nm3-catalog' \
  "${named}"

exit "${status}"
