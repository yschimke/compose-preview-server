#!/usr/bin/env bash
# Assert that every Skiko-backed JVM this image spawns loads a `libskiko` built for the SAME Skiko
# version as the `skiko-awt` bindings it loads. Run by the Dockerfile's desktop-daemon stage
# against the staged /opt tree, and by `test-skiko-pairing.sh` against fixtures.
#
#   check-skiko-pairing.sh <opt-root> <platform>     e.g. /opt linux-x64
#
# Why a check and not a comment. Skiko loads the FIRST `skiko-awt` classes and the FIRST
# `libskiko-<platform>.so` resource on the classpath, and nothing compares the two. A mismatched
# pair boots fine and then fails per call, on whichever JNI symbol the newer bindings added — on
# 3.125.0 that was `ParagraphKt._nGetUnresolvedCodepointsCount`, so `?rcPlayer=cmp-jvm` answered
# 500 for every document containing text and drew every document without it. The two halves come
# from different releases (bindings from compose-ai-tools' `lib-rcjvm/` or compose-preview-daemon's
# `lib-daemon-desktop/`, the native from Maven Central at a version this image picks), so the pair
# drifts whenever either release moves Compose.
#
# The classpaths below mirror how they are assembled at runtime; change them together:
#   desktop daemon  `locateBundleSidecarJars("lib-daemon-desktop")` — which PREPENDS the
#                   `skiko-awt-runtime-*` jars in `-Dcomposeai.cli.skikoDir` (`configuredSkikoJars`)
#                   — then `locateBundleSidecarJars("lib-renderer")` (`desktopBundleDaemonLaunch`,
#                   `PlaygroundDaemonOpeners.desktop`).
#   cmp-jvm         `locateBundleSidecarJars("lib-rcjvm")` + `locateBundleSidecarJars(
#                   "lib-daemon-desktop")` (compose-ai-tools' `RcJvmServerRenderer.classpath`).
# Each directory's jars are taken sorted by name, as `locateBundleSidecarJars` does.
set -euo pipefail
export LC_ALL=C

root="${1:?usage: check-skiko-pairing.sh <opt-root> <platform>}"
platform="${2:?usage: check-skiko-pairing.sh <opt-root> <platform>}"

# Sorted jars of one directory (nothing when it is absent), optionally only the host natives.
jars() {
  local dir="${root}/$1" filter="${2:-}"
  [[ -d "${dir}" ]] || return 0
  local f
  for f in "${dir}"/*.jar; do
    [[ -f "${f}" ]] || continue
    if [[ -n "${filter}" && "$(basename "${f}")" != skiko-awt-runtime-*.jar ]]; then continue; fi
    echo "${f}"
  done
}

# The version of the first binding jar (`kind=awt`) or first host native (`kind=native`) on an
# ordered classpath read from stdin, plus the jar it came from.
first() {
  local kind="$1" f b v
  while IFS= read -r f; do
    b="$(basename "${f}" .jar)"
    case "${kind}:${b}" in
      awt:skiko-awt-runtime-*) ;;
      awt:skiko-awt-*) v="${b#skiko-awt-}" ;;
      native:skiko-awt-runtime-"${platform}"-*) v="${b#skiko-awt-runtime-"${platform}"-}" ;;
      *) ;;
    esac
    if [[ -n "${v:-}" ]]; then
      echo "${v} ${f}"
      return 0
    fi
  done
}

failed=0
check() {
  local lane="$1" fix="$2" cp="$3" awt native
  awt="$(first awt <<<"${cp}")"
  native="$(first native <<<"${cp}")"
  if [[ -z "${awt}" ]]; then
    echo "FAIL: ${lane}: no skiko-awt-<version>.jar on its classpath under ${root}" >&2
    failed=1
  elif [[ -z "${native}" ]]; then
    echo "FAIL: ${lane}: binds Skiko ${awt%% *} (${awt#* }) but no skiko-awt-runtime-${platform}-*.jar" >&2
    echo "      is on its classpath — ${fix}" >&2
    failed=1
  elif [[ "${awt%% *}" != "${native%% *}" ]]; then
    echo "FAIL: ${lane}: binds Skiko ${awt%% *} (${awt#* })" >&2
    echo "      but loads the ${native%% *} native (${native#* })." >&2
    echo "      Every JNI symbol newer than ${native%% *} throws UnsatisfiedLinkError — ${fix}" >&2
    failed=1
  else
    echo "PASS: ${lane}: skiko-awt ${awt%% *} pairs with ${native#* }"
  fi
}

desktop_cp="$(jars lib-skiko natives; jars lib-daemon-desktop; jars lib-renderer)"
rcjvm_cp="$(jars lib-rcjvm; jars lib-skiko natives; jars lib-daemon-desktop)"

check "desktop daemon (lib-skiko + lib-daemon-desktop + lib-renderer)" \
  "stage the native matching lib-daemon-desktop's skiko-awt into lib-skiko/" "${desktop_cp}"
# lib-rcjvm is optional to the image's other lanes, so only check it when it carries jars.
if [[ -n "$(jars lib-rcjvm)" ]]; then
  check "cmp-jvm (lib-rcjvm + lib-skiko + lib-daemon-desktop)" \
    "stage the native matching lib-rcjvm's skiko-awt into lib-rcjvm/" "${rcjvm_cp}"
fi

exit "${failed}"
