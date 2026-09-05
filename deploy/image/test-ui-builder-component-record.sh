#!/usr/bin/env bash
# Guard: a packaged box passes the component record, so its builder can actually export.
#
# Why this needs a test. `--ui-builder-components` was read by `ComponentRecordSource` and written
# by nothing that shipped: this image enabled the builder's catalogs, passed no record, and every
# Compose export on every deployed box refused with `NO_COMPONENT_RECORD`. Nothing was red. The
# builder came up, the catalogs listed, designs saved — and the one action the whole export path
# exists for was withdrawn, because `composeCode` is computed from whether a record is configured.
#
# The failure is silent in both directions and neither shows in a build:
#
#   * record not passed — the export action disappears from a working builder, which reads as a
#     feature nobody finished rather than as a flag nobody set;
#   * record passed for a catalog that has none — the capability is advertised and every export of
#     that catalog refuses by name, which is the worse failure the runner's own comment describes.
#
# So this asserts the shape rather than the string: the flag is emitted for `m3-catalog` when the
# packaged record is present, and not emitted at all when it is absent — a box built from an older
# tarball must not claim an export it cannot perform.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

gate="$(awk '/^  UI_BUILDER_RECORD=/,/^  fi$/' "${entrypoint}")"
[[ -n "${gate}" ]] || {
  echo "FAIL: could not find the component-record gate in ${entrypoint}" >&2
  exit 1
}

run_gate() {
  local override="$1"
  SERVE_UI_BUILDER_COMPONENTS="${override}" bash -c \
    "set -euo pipefail; args=(); ${gate}; printf '%s' \"\${args[*]-}\""
}

present="$(mktemp)"
trap 'rm -f "${present}"' EXIT
echo '{}' >"${present}"

got="$(run_gate "${present}")"
[[ "${got}" == "--ui-builder-components m3-catalog=${present}" ]] || {
  echo "FAIL: a present record should be passed for m3-catalog, got '${got}'" >&2
  exit 1
}

got="$(run_gate "/nonexistent/components.json")"
[[ -z "${got}" ]] || {
  echo "FAIL: an absent record must pass no flag at all, got '${got}'" >&2
  exit 1
}

# The default path, not an override: an image that ships the record somewhere the entrypoint does
# not look is the same outage with a different cause.
default_path="$(sed -n 's#^ *UI_BUILDER_RECORD="\${SERVE_UI_BUILDER_COMPONENTS:-\(.*\)}"$#\1#p' "${entrypoint}")"
[[ "${default_path}" == /opt/compose-preview-server/ui-builder-components/*.json ]] || {
  echo "FAIL: the default record path is not inside the installed distribution: '${default_path}'" >&2
  exit 1
}

# And that the distribution is actually told to carry it. The two live in different files and only
# agreeing matters; either alone is an outage.
staged="$(sed -n 's/^ *include("\(m3-catalog-components-v1.json\)")$/\1/p' "${here}/../../server/build.gradle.kts")"
[[ "${staged}" == "m3-catalog-components-v1.json" ]] || {
  echo "FAIL: the server distribution does not stage the component record" >&2
  exit 1
}
[[ "$(basename "${default_path}")" == "${staged}" ]] || {
  echo "FAIL: the entrypoint reads '${default_path}' and the distribution stages '${staged}'" >&2
  exit 1
}

echo "PASS: the packaged builder is given the component record its export needs"
