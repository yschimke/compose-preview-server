#!/usr/bin/env bash
# Guard: the daemon sidecar flags survive a replaced JAVA_TOOL_OPTIONS.
#
# Why this needs a test. `-Dcomposeai.cli.lib*Dir` is the ONLY way the render lanes find their
# sidecars on this image: they sit at /opt/lib-*, which is neither $APP_HOME (the Gradle start
# script never exports it) nor a classpath-relative path. Setting JAVA_TOOL_OPTIONS from the compose
# file replaces the baked string and takes those flags with it, and the resulting failure is silent:
# catalogs still serve and still render, they just stop offering live controls, and the degradation
# blames a daemon that "could not be started".
#
# preview.coo.ee ran exactly that way — a stale JAVA_TOOL_OPTIONS carrying the Android flag alone,
# so every Android catalog was live and every desktop one was snapshots. That reads like a
# desktop-lane bug and is not one, which is what makes it worth a guard rather than a comment.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
entrypoint="${ENTRYPOINT_FILE:-${here}/entrypoint.sh}"
[[ -f "${entrypoint}" ]] || {
  echo "FAIL: missing ${entrypoint}" >&2
  exit 1
}

# Run just the restore stanza rather than the whole entrypoint (which needs a container).
stanza="$(sed -n '/^# >>> sidecar-restore$/,/^# <<< sidecar-restore$/p' "${entrypoint}")"
[[ -n "${stanza}" ]] || {
  echo "FAIL: no sidecar-restore stanza found in ${entrypoint} — the detector is broken." >&2
  exit 1
}

fixtures="$(mktemp -d)"
trap 'rm -rf "${fixtures}"' EXIT
mkdir -p "${fixtures}/lib-daemon-android" "${fixtures}/lib-daemon-desktop" \
  "${fixtures}/lib-renderer" "${fixtures}/lib-bta"

# `present` lets a case declare a sidecar dir that does NOT exist, by pointing at a missing path.
run_stanza() {
  local incoming="$1" android="${2:-${fixtures}/lib-daemon-android}" \
    bta="${3:-${fixtures}/lib-bta}"
  JAVA_TOOL_OPTIONS="${incoming}" \
  LIB_DAEMON_ANDROID_DIR="${android}" \
  LIB_DAEMON_DESKTOP_DIR="${fixtures}/lib-daemon-desktop" \
  LIB_RENDERER_DIR="${fixtures}/lib-renderer" \
  LIB_BTA_DIR="${bta}" \
    bash -c "
      set -euo pipefail
      ${stanza}
      printf '%s' \"\${JAVA_TOOL_OPTIONS}\"
    " 2>/dev/null
}

# 1. The reported failure: an inherited string carrying the Android flag and nothing else. The two
#    desktop flags come back, and the operator's own content is untouched.
result="$(run_stanza '-XX:MaxRAMPercentage=70 -Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android')"
for want in libDaemonDesktopDir libRendererDir libBtaDir; do
  [[ "${result}" == *"${want}="* ]] || {
    echo "FAIL: ${want} was not restored: ${result}" >&2
    exit 1
  }
done
[[ "${result}" == *"-XX:MaxRAMPercentage=70"* ]] || {
  echo "FAIL: the incoming options were not preserved: ${result}" >&2
  exit 1
}
echo "PASS: a JAVA_TOOL_OPTIONS missing the desktop flags gets them back"

# 2. An operator pointing a flag at their OWN sidecar keeps it — the restore adds what is missing,
#    it does not overwrite a deliberate choice.
result="$(run_stanza '-Dcomposeai.cli.libDaemonDesktopDir=/custom/sidecar')"
[[ "${result}" == *"libDaemonDesktopDir=/custom/sidecar"* ]] || {
  echo "FAIL: an explicit operator path was lost: ${result}" >&2
  exit 1
}
[[ "${result}" != *"libDaemonDesktopDir=${fixtures}"* ]] || {
  echo "FAIL: the image path was added on top of the operator's: ${result}" >&2
  exit 1
}
echo "PASS: an explicitly set sidecar path is left alone"

# 3. A sidecar this image does not carry must NOT get a flag. The flag is the first candidate
#    `locateBundleSidecarJars` tries, so pointing it at a missing directory would mask the
#    classpath-relative fallback a source build resolves through — a regression, not a no-op.
result="$(run_stanza '' "${fixtures}/absent-android")"
[[ "${result}" != *"libDaemonAndroidDir="* ]] || {
  echo "FAIL: a flag was added for a sidecar directory that does not exist: ${result}" >&2
  exit 1
}
[[ "${result}" == *"libDaemonDesktopDir="* ]] || {
  echo "FAIL: a missing android sidecar suppressed the desktop one too: ${result}" >&2
  exit 1
}
echo "PASS: no flag is invented for a sidecar the image does not carry"

# 4. The ordinary case — nothing replaced anything, every flag already present — must be a no-op,
#    not a string that lists each flag twice.
baked='-Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android -Dcomposeai.cli.libDaemonDesktopDir=/opt/lib-daemon-desktop -Dcomposeai.cli.libRendererDir=/opt/lib-renderer -Dcomposeai.cli.libBtaDir=/opt/lib-bta'
result="$(run_stanza "${baked}")"
[[ "${result}" == "${baked}" ]] || {
  echo "FAIL: an intact JAVA_TOOL_OPTIONS was modified: ${result}" >&2
  exit 1
}
echo "PASS: an intact JAVA_TOOL_OPTIONS is left exactly as it was"

# 5. The playground's compiler jars are subject to the same two rules as a render sidecar, and are
#    worth their own case because the symptom differs: losing this flag does not degrade a lane to
#    snapshots, it removes `/playground` entirely on a box configured to serve it.
result="$(run_stanza '-XX:MaxRAMPercentage=70')"
[[ "${result}" == *"libBtaDir=${fixtures}/lib-bta"* ]] || {
  echo "FAIL: libBtaDir was not restored: ${result}" >&2
  exit 1
}
result="$(run_stanza '' "${fixtures}/lib-daemon-android" "${fixtures}/absent-bta")"
[[ "${result}" != *"libBtaDir="* ]] || {
  echo "FAIL: libBtaDir was invented for a directory the image does not carry: ${result}" >&2
  exit 1
}
echo "PASS: the playground compiler flag is restored, and only when the image carries it"

# 6. Self-test: the checks above must be able to fail. A stanza that only ever echoes its input
#    would pass cases 2-4 on content it never touched, so prove case 1 catches it.
bad_stanza='true'
result="$(JAVA_TOOL_OPTIONS='-Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android' bash -c "
  set -euo pipefail
  ${bad_stanza}
  printf '%s' \"\${JAVA_TOOL_OPTIONS}\"
")"
[[ "${result}" == *"libDaemonDesktopDir="* ]] && {
  echo "FAIL: self-test — a no-op stanza was not caught by the restore check" >&2
  exit 1
}
echo "PASS: self-test — a stanza that restores nothing is caught"

echo "PASS: all sidecar-flag restore checks"
