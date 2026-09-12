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
# 2. `--ui-builder-published-catalogs` defaults to `remote-m3`: that catalog takes its
#    definition from its own published `ui-builder.json` and the other two do not. The
#    per-catalog reasons move and live in the entrypoint beside the lever, together with what
#    serving remote-m3 that way costs. What is asserted here is the shape: the default names
#    remote-m3 and nothing else, an operator can name more, and an operator can retreat to
#    `none`.
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
# The FULL value, with the trailing newline, not a prefix of it. `expect` is a substring check, so
# asserting `…published-catalogs\nremote-m3` kept passing when the default became
# `remote-m3,wear-m3` -- it matched the prefix. An assertion that cannot fail when the thing it
# names changes is the failure this file exists to prevent, so it pins both ids and the end of the
# argument. (Command substitution strips the trailing newline, so the assertion pins both ids
# rather than the line end -- which still catches the regression that matters, a default that
# silently loses wear-m3.)
expect "the default serves remote-m3 and wear-m3 from their published files" \
  $'--ui-builder-published-catalogs\nremote-m3,wear-m3' "${default}"

# The reverse direction, and the one that matters most: a box can put every catalog back on the
# catalog this build writes in Kotlin. `none` is the whole-fleet retreat this lever exists for,
# and it was the default until remote-m3's gate came back clean under `--strict`. See #796 before
# assuming the retreat is free for designs already saved against the published catalog.
withheld="$(run_case "" "none")"
expect "an operator can withhold the published path from every catalog" \
  $'--ui-builder-published-catalogs\nnone' "${withheld}"

# The case that made the published default a derived value rather than a literal. An operator may
# narrow the served allowlist — dropping the Wear/Android lane is the documented reason — and
# `ServeCommandOptions` REFUSES a published id the served list does not carry, with a startup
# failure. So a narrowed allowlist that no longer serves remote-m3 must fall back to `none` on its
# own; otherwise the box does not boot and the operator is told to narrow a second variable they
# were never asked to think about.
narrowed_out="$(run_case "m3-catalog")"
expect "narrowing the allowlist past remote-m3 withholds the published path" \
  $'--ui-builder-published-catalogs\nnone' "${narrowed_out}"
refute "narrowing the allowlist past remote-m3 publishes nothing" "remote-m3" "${narrowed_out}"

# The symmetric guard, and the one the default no longer covers: a box that does not want to pay
# for the Wear/Android lane can drop it, and dropping it must not disturb the other two.
narrowed="$(run_case "m3-catalog,remote-m3")"
refute "an operator can take wear-m3 out" "wear-m3" "${narrowed}"
expect "taking wear-m3 out leaves the other two" "m3-catalog,remote-m3" "${narrowed}"

# The other half of the derivation, added when wear-m3 joined remote-m3 on the published path: the
# intersection has to work from EITHER side. Dropping remote-m3 must leave wear-m3 published rather
# than falling back to `none`, which is what a literal default or a single-catalog `if` would do.
wear_only="$(run_case "m3-catalog,wear-m3")"
expect "dropping remote-m3 still publishes wear-m3" \
  $'--ui-builder-published-catalogs\nwear-m3' "${wear_only}"
refute "dropping remote-m3 does not publish it" "remote-m3" "${wear_only}"

all="$(run_case "" "all")"
expect "an operator can opt every catalog back in" $'--ui-builder-published-catalogs\nall' "${all}"

named="$(run_case "m3-catalog,remote-m3" "m3-catalog")"
expect "a named subset is forwarded verbatim" $'--ui-builder-published-catalogs\nm3-catalog' \
  "${named}"

exit "${status}"
