#!/usr/bin/env bash
# Guard two decisions the image makes about UI-builder catalogs, both of which are invisible in a
# running container until somebody notices the shelf changed.
#
# 1. The served allowlist is `m3-catalog,remote-m3,wear-m3`, and it is an ALLOWLIST: publishing
#    another preview catalog does not expose a builder for it. `wear-m3` spent a period off this
#    list on cost grounds — a Wear/Android catalog needs Robolectric previews and an Android SDK
#    for its native lane, and nobody was authoring against it — so both directions are asserted
#    here: the default carries it, and an operator can still take it out with the explicit
#    SERVE_UI_BUILDER_WEAR opt-out.
# 2. `--ui-builder-published-catalogs` now defaults to ALL THREE: every served catalog takes its
#    definition from its own published `ui-builder.json` rather than from the catalog this build
#    writes in Kotlin. The per-catalog reasons live in the entrypoint beside the lever, together
#    with what each one costs. What is asserted here is the shape: the default is DERIVED from the
#    served list rather than written independently of it, an operator can name a subset, and an
#    operator can retreat to `none`. The derivation is the load-bearing part -- see the narrowing
#    cases below for why a literal default cannot work.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

block="$(
  awk '
    /^  ui_builder_catalogs=/ { capture = 1 }
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
    SERVE_UI_BUILDER_WEAR="${3:-1}" \
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
# The FULL value, with the trailing newline, not a prefix of it. `expect` is a substring check, so
# asserting `…published-catalogs\nremote-m3` kept passing when the default became
# `remote-m3,wear-m3` -- it matched the prefix. An assertion that cannot fail when the thing it
# names changes is the failure this file exists to prevent, so it pins both ids and the end of the
# argument. (Command substitution strips the trailing newline, so the assertion pins both ids
# rather than the line end -- which still catches the regression that matters, a default that
# silently loses wear-m3.)
expect "the default serves all three catalogs from their published files" \
  $'--ui-builder-published-catalogs\nm3-catalog,remote-m3,wear-m3' "${default}"

# The reverse direction, and the one that matters most: a box can put every catalog back on the
# catalog this build writes in Kotlin. `none` is the whole-fleet retreat this lever exists for,
# and it was the default until remote-m3's gate came back clean under `--strict`. See #796 before
# assuming the retreat is free for designs already saved against the published catalog.
withheld="$(run_case "" "none")"
expect "an operator can withhold the published path from every catalog" \
  $'--ui-builder-published-catalogs\nnone' "${withheld}"

# The case that made the published default a derived value rather than a literal, and the reason it
# must stay derived now that all three are publishable. An operator may narrow the served allowlist
# — dropping the Wear/Android lane is the documented reason — and `ServeCommandOptions` REFUSES a
# published id the served list does not carry, with a startup failure rather than a warning. So the
# published list has to shrink with the served one on its own; otherwise the box does not boot and
# the operator is told to narrow a second variable they were never asked to think about.
narrowed_out="$(run_case "m3-catalog")"
expect "narrowing to m3-catalog alone publishes exactly it" \
  $'--ui-builder-published-catalogs\nm3-catalog' "${narrowed_out}"
refute "narrowing to m3-catalog alone does not publish remote-m3" "remote-m3" "${narrowed_out}"
refute "narrowing to m3-catalog alone does not publish wear-m3" "wear-m3" "${narrowed_out}"

# The `none` fallback still has to be reachable, and after this change it is no longer reachable by
# narrowing to one of the three — every one of them is publishable. It is reached by serving a
# catalog this image has no published file for, which is what a future catalog looks like on the
# day it is added to the served list and before its fixture and gate exist.
future="$(run_case "some-future-catalog")"
expect "a served catalog with no published file falls back to none" \
  $'--ui-builder-published-catalogs\nnone' "${future}"

# The symmetric guard, and the one the default no longer covers: a box that does not want to pay
# for the Wear/Android lane can drop it explicitly, and dropping it must not disturb the other two.
# The exact two-catalog allowlist is the retired image default and now migrates forward; #911 was the
# production box retaining that stale value while every fresh box got Wear.
narrowed="$(run_case "" "" "0")"
refute "an operator can take wear-m3 out" "wear-m3" "${narrowed}"
expect "taking wear-m3 out leaves the other two published" \
  $'--ui-builder-published-catalogs\nm3-catalog,remote-m3' "${narrowed}"

legacy="$(run_case "m3-catalog,remote-m3")"
expect "the retired two-catalog default migrates forward" \
  $'--ui-builder-catalogs\nm3-catalog,remote-m3,wear-m3' "${legacy}"

# The other half of the derivation, added when wear-m3 joined remote-m3 on the published path: the
# intersection has to work from EITHER side. Dropping remote-m3 must leave wear-m3 published rather
# than falling back to `none`, which is what a literal default or a single-catalog `if` would do.
wear_only="$(run_case "m3-catalog,wear-m3")"
expect "dropping remote-m3 still publishes the rest" \
  $'--ui-builder-published-catalogs\nm3-catalog,wear-m3' "${wear_only}"
refute "dropping remote-m3 does not publish it" "remote-m3" "${wear_only}"

all="$(run_case "" "all")"
expect "an operator can opt every catalog back in" $'--ui-builder-published-catalogs\nall' "${all}"

named="$(run_case "m3-catalog,remote-m3" "m3-catalog")"
expect "a named subset is forwarded verbatim" $'--ui-builder-published-catalogs\nm3-catalog' \
  "${named}"

exit "${status}"
