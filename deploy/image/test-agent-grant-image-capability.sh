#!/usr/bin/env bash
# Guard: a grant may carry `images` exactly when this box runs the image lane — never otherwise.
#
# Why this needs a test rather than a comment. The failure mode is asymmetric and both directions
# are silent in a build:
#
#   * offered without the lane — the SERVER REFUSES TO START ("a granted upload would have nowhere
#     to go"). A flat `...,images` default would therefore stop every adopter box that does not
#     upload, at boot, with a message about a capability they never asked for.
#   * withheld with the lane — the box comes up fine and an approver simply cannot pass `images` on,
#     which is exactly the gap #268 was about: a capability that is configured, admitted everywhere
#     else, and absent at the one moment it matters.
#
# So the default is conditional, in two places that have to agree: the compose file adds `images`
# when SERVE_IMAGE_UPLOAD_REPO names a repository, and the entrypoint drops it again for the one
# case compose cannot see — SERVE_ACCEPT_IMAGES=0 with the repository still named, which that file
# documents as "name it and keep the lane shut".
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${COMPOSE_FILE_UNDER_TEST:-${here}/docker-compose.yml}"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"

# ---------------------------------------------------------------------------
# The compose half. `${VAR:-…}` and `${VAR:+…}` mean the same thing to Compose interpolation and to
# bash, so the default expression is evaluated here rather than reimplemented — a test that restated
# the rule could agree with itself while disagreeing with the file.
# ---------------------------------------------------------------------------
default_expr="$(sed -n 's/^ *SERVE_AGENT_GRANT_CAPABILITIES: "\(.*\)"$/\1/p' "${compose}")"
[[ -n "${default_expr}" ]] || {
  echo "FAIL: no SERVE_AGENT_GRANT_CAPABILITIES line in ${compose}" >&2
  exit 1
}

resolve_default() {
  # $1 = SERVE_IMAGE_UPLOAD_REPO ("" for unset). Nothing else is set, so this is the adopter's
  # out-of-the-box answer.
  env -i "SERVE_IMAGE_UPLOAD_REPO=$1" bash -c "printf '%s' \"${default_expr}\""
}

with_repo="$(resolve_default 'yschimke/compose-preview-server')"
without_repo="$(resolve_default '')"

[[ "${with_repo}" == *images* ]] || {
  echo "FAIL: a box that names an upload repo is not offered the images capability: ${with_repo}" >&2
  exit 1
}
[[ "${without_repo}" != *images* ]] || {
  echo "FAIL: a box with no upload repo would be offered images and refuse to start: ${without_repo}" >&2
  exit 1
}
for want in ui-builder-read ui-builder-write ui-builder-export; do
  [[ "${with_repo}" == *"${want}"* && "${without_repo}" == *"${want}"* ]] || {
    echo "FAIL: ${want} is not offered unconditionally" >&2
    exit 1
  }
done
echo "PASS: the compose default offers images exactly when an upload repository is named"

# ---------------------------------------------------------------------------
# The entrypoint half — the guard that catches what compose cannot see. Extracted and run rather
# than read, so a stanza that stopped working would fail here.
# ---------------------------------------------------------------------------
stanza="$(sed -n '/^  # >>> image-capability-guard$/,/^  # <<< image-capability-guard$/p' "${entrypoint}")"
[[ -n "${stanza}" ]] || {
  echo "FAIL: no image-capability-guard stanza in ${entrypoint}" >&2
  exit 1
}

run_guard() {
  # $1 = image_lane_on, $2 = incoming capability list. Echoes what survives.
  image_lane_on="$1" SERVE_AGENT_GRANT_CAPABILITIES="$2" bash -c "
    set -uo pipefail
    ${stanza}
    printf '%s' \"\${SERVE_AGENT_GRANT_CAPABILITIES}\"
  " 2>/dev/null
}

result="$(run_guard 0 'ui-builder-read,images,ui-builder-export')"
[[ "${result}" == "ui-builder-read,ui-builder-export" ]] || {
  echo "FAIL: images was not dropped on a box with no image lane: ${result}" >&2
  exit 1
}
echo "PASS: images is dropped when the lane is off, and the rest of the list survives"

result="$(run_guard 1 'ui-builder-read,images,ui-builder-export')"
[[ "${result}" == "ui-builder-read,images,ui-builder-export" ]] || {
  echo "FAIL: images was dropped on a box that DOES run the image lane: ${result}" >&2
  exit 1
}
echo "PASS: images survives untouched on a box that runs the image lane"

# A list that never mentioned images must come back byte-identical — the guard is not a rewriter.
result="$(run_guard 0 'ui-builder-read,ui-builder-write')"
[[ "${result}" == "ui-builder-read,ui-builder-write" ]] || {
  echo "FAIL: a list without images was modified: ${result}" >&2
  exit 1
}
echo "PASS: a list that never asked for images is left exactly as it was"

# `images` must not be matched as a substring of some other capability name.
result="$(run_guard 0 'ui-builder-images-export')"
[[ "${result}" == "ui-builder-images-export" ]] || {
  echo "FAIL: a capability merely CONTAINING 'images' was stripped: ${result}" >&2
  exit 1
}
echo "PASS: only the whole 'images' name is dropped, not a name containing it"

# Self-test: the checks above must be able to fail. A guard that drops nothing would pass every
# case but the first, so prove that one catches it.
bad_guard='true'
result="$(image_lane_on=0 SERVE_AGENT_GRANT_CAPABILITIES='ui-builder-read,images' bash -c "
  set -uo pipefail
  ${bad_guard}
  printf '%s' \"\${SERVE_AGENT_GRANT_CAPABILITIES}\"
")"
[[ "${result}" == *images* ]] || {
  echo "FAIL: self-test is broken — a no-op guard appeared to drop the capability" >&2
  exit 1
}
echo "PASS: self-test — a guard that drops nothing is caught"

echo "PASS: all agent-grant image-capability checks"
