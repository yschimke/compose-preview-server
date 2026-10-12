#!/usr/bin/env bash
# Guard: every Skiko-backed JVM on the image loads a native built for its own `skiko-awt`.
#
# 3.125.0 shipped `lib-rcjvm/skiko-awt-0.150.1.jar` (compose-ai-tools 2.50.0) beside one native,
# `lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar`, picked off `lib-daemon-desktop/` (compose-
# preview-daemon 3.15.3). The cmp-jvm worker bound the newer classes, loaded the older `libskiko`,
# and every document containing text failed with
#
#     UnsatisfiedLinkError: 'int org.jetbrains.skia.paragraph.ParagraphKt._nGetUnresolvedCodepointsCount(long)'
#
# The image build passed: each half was present, nothing compared them. `check-skiko-pairing.sh`
# is that comparison and the desktop-daemon stage runs it; this proves it rejects the 3.125.0
# layout, accepts the fixed one, and is still wired into the Dockerfile.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="${here}/check-skiko-pairing.sh"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# layout <name> <relative jar path>... — empty files suffice: the check reads names, not contents
# (the Dockerfile asserts the `.so` is inside each native it fetches).
layout() {
  local dir="${tmp}/$1" jar
  shift
  mkdir -p "${dir}"
  for jar in "$@"; do
    mkdir -p "${dir}/$(dirname "${jar}")"
    : >"${dir}/${jar}"
  done
  echo "${dir}"
}

expect() {
  local want="$1" name="$2" root="$3" out status=0
  out="$(bash "${check}" "${root}" linux-x64 2>&1)" || status=$?
  if [[ "${want}" == pass && "${status}" -ne 0 ]]; then
    echo "FAIL: ${name}: expected the pairing check to pass, it said:" >&2
    echo "${out}" >&2
    exit 1
  fi
  if [[ "${want}" == fail && "${status}" -eq 0 ]]; then
    echo "FAIL: ${name}: expected the pairing check to fail, it said:" >&2
    echo "${out}" >&2
    exit 1
  fi
  echo "PASS: ${name} (check ${want}ed)"
  printf '%s\n' "${out}"
}

desktop=(
  lib-daemon-desktop/desktop-daemon.jar
  lib-daemon-desktop/skiko-awt-0.144.6.jar
  lib-renderer/renderer.jar
  lib-renderer/skiko-awt-0.144.6.jar
)

# The 3.125.0 image, exactly.
out="$(expect fail "3.125.0 layout: lib-rcjvm binds 0.150.1, only the 0.144.6 native is staged" \
  "$(layout shipped "${desktop[@]}" \
    lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar \
    lib-rcjvm/rc-render-jvm.jar lib-rcjvm/skiko-awt-0.150.1.jar)")"
echo "${out}"
[[ "${out}" == *"cmp-jvm"*"binds Skiko 0.150.1"*"0.144.6 native"* ]] || {
  echo "FAIL: the 3.125.0 layout failed, but not on the cmp-jvm pair it actually broke." >&2
  exit 1
}

# The fix: lib-rcjvm carries the native for its own bindings, ahead of lib-skiko's.
expect pass "fixed layout: each classpath's first native matches its first skiko-awt" \
  "$(layout fixed "${desktop[@]}" \
    lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar \
    lib-rcjvm/rc-render-jvm.jar lib-rcjvm/skiko-awt-0.150.1.jar \
    lib-rcjvm/skiko-awt-runtime-linux-x64-0.150.1.jar)"

# Releases that agree need nothing extra in lib-rcjvm: lib-skiko's native pairs for both.
expect pass "agreeing releases: one native in lib-skiko serves both lanes" \
  "$(layout agree "${desktop[@]}" \
    lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar \
    lib-rcjvm/rc-render-jvm.jar lib-rcjvm/skiko-awt-0.144.6.jar)"

# A lib-rcjvm that stops shipping bindings borrows the desktop pair.
expect pass "lib-rcjvm without skiko-awt binds lib-daemon-desktop's pair" \
  "$(layout borrow "${desktop[@]}" \
    lib-skiko/skiko-awt-runtime-linux-x64-0.144.6.jar lib-rcjvm/rc-render-jvm.jar)"

# The desktop daemon is held to the same rule.
expect fail "desktop daemon native one release behind its bindings" \
  "$(layout desktop-skew "${desktop[@]}" lib-skiko/skiko-awt-runtime-linux-x64-0.143.0.jar)" >/dev/null

expect fail "no native at all" "$(layout none "${desktop[@]}")" >/dev/null

# Another host's native is not this host's: a linux-arm64 jar pairs nothing on linux-x64.
expect fail "only another platform's native is staged" \
  "$(layout wrong-host "${desktop[@]}" lib-skiko/skiko-awt-runtime-linux-arm64-0.144.6.jar)" >/dev/null

# Wiring: the Dockerfile must stage lib-rcjvm's own native and run the check in the same stage.
[[ -f "${dockerfile}" ]] || {
  echo "FAIL: missing ${dockerfile}" >&2
  exit 1
}
grep -qE '^COPY check-skiko-pairing\.sh ' "${dockerfile}" || {
  echo "FAIL: the Dockerfile no longer copies check-skiko-pairing.sh into the build." >&2
  exit 1
}
grep -qE 'check-skiko-pairing\.sh /opt "\$platform"' "${dockerfile}" || {
  echo "FAIL: the Dockerfile no longer runs check-skiko-pairing.sh against /opt, so a mismatched" >&2
  echo "      Skiko pair builds and ships, as 3.125.0 did." >&2
  exit 1
}
grep -qE 'stage_native /opt/lib-rcjvm /opt/lib-rcjvm' "${dockerfile}" || {
  echo "FAIL: the Dockerfile no longer stages lib-rcjvm's own Skiko native, so cmp-jvm loads" >&2
  echo "      lib-skiko's, which pairs with lib-daemon-desktop's skiko-awt instead." >&2
  exit 1
}
echo "PASS: the Dockerfile stages lib-rcjvm's native and runs the pairing check"

echo "PASS: all Skiko pairing checks"
