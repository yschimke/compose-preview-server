#!/usr/bin/env bash
# Guard: every lane this image claims to serve must have its sidecar baked in AND pointed at, not
# one of the two. Mostly live-render backends; the playground's compiler jars are here too, because
# they are found by the same lookup and were missing in the same way.
#
# Why this needs a test rather than a comment. A missing sidecar does not fail the build, refuse the
# catalog, or show up in the logs an operator reads: `ServeBundleDaemon.materialize` calls
# `locateBundleSidecarJars`, gets an empty list, logs one line to stderr and returns null, and the
# catalog is published as baked PNGs with `livebundle-unavailable`. The site still works. Everything
# renders. Only the device/theme/knob controls quietly stop re-rendering.
#
# That is exactly how a production host ran: the Android pair (`/opt/lib-daemon-android` +
# `-Dcomposeai.cli.libDaemonAndroidDir`) was baked and every `backend: "android"` catalog was live,
# while the desktop pair was in neither the image nor JAVA_TOOL_OPTIONS and every
# `backend: "desktop"` catalog — m3-catalog among them — served snapshots. The server distribution
# carries only `bin/`, `lib/`, `wasm-ui/` and `ui-builder/`; both sidecars ride in compose-ai-tools' `:cli`
# distribution, so `$APP_HOME/lib-daemon-desktop` resolves to nothing and the fallback is silent.
#
# So: for each backend, assert the directory reaches the runtime stage and is named by the sysprop
# that locates it. A directory reaches it either by a `COPY --from` into the runtime stage, or, for
# the render runtime's contract directories (compose-preview-daemon's
# docs/daemon/RENDER_RUNTIME_IMAGE.md), by the runtime stage being built FROM that image. A stage
# that fetches a sidecar nobody points at, or a sysprop naming a path nothing populates, both fail
# here.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dockerfile="${DOCKERFILE:-${here}/Dockerfile}"
[[ -f "${dockerfile}" ]] || {
  echo "FAIL: missing ${dockerfile}" >&2
  exit 1
}

# backend | sidecar dir under /opt | the sysprop `locateBundleSidecarJars` reads for it
# (`BundleDaemonSupport.bundleSidecarSysprop`). Both desktop directories are required: the CMP
# daemon links against the Skiko renderer, and either one missing returns null from
# `desktopBundleDaemonLaunch`.
sidecars=(
  "android|lib-daemon-android|composeai.cli.libDaemonAndroidDir|its catalogs publish as baked PNGs with livebundle-unavailable"
  "desktop|lib-daemon-desktop|composeai.cli.libDaemonDesktopDir|its catalogs publish as baked PNGs with livebundle-unavailable"
  "desktop|lib-renderer|composeai.cli.libRendererDir|its catalogs publish as baked PNGs with livebundle-unavailable"
  # The cmp-jvm RENDER lane, not a liveBundle daemon, and it failed the same way for the same
  # reason: `RcJvmServerRenderer` spawns a subprocess whose classpath is `lib-rcjvm/*` +
  # `lib-daemon-desktop/*`, only the second half was baked, and the miss surfaced as a 503 on
  # `?rcPlayer=cmp-jvm` rather than as anything a build or a log would show. Same pairing rule —
  # baked, carried into the runtime stage, named by the sysprop that locates it — different
  # consequence, which is why the message is per row rather than one line for all four.
  "cmp-jvm|lib-rcjvm|composeai.cli.libRcjvmDir|?rcPlayer=cmp-jvm answers 503 and the compare wall can never show that player"
  # Not a render backend at all, and here because it fails by the identical mechanism: the
  # PLAYGROUND's Kotlin Build Tools jars are located by the same `locateBundleSidecarJars` call,
  # from the same CLI tarball, and were the one sidecar this image deliberately did not lift. The
  # consequence is louder than a degraded lane — `openPlaygroundService` returns null and the
  # server serves no playground — but it is just as invisible to a build: a production host logged
  # "playground admitted" and then "playground compiler unavailable" on the next line, for months.
  # The Skiko NATIVE, which is not code the CLI stages at all: `cli/build.gradle.kts` filters
  # every `skiko-awt-runtime-*` out of the sidecars ("SkikoNativeProvision downloads only the
  # current host's jar") and that provisioner is a CLI class this image never installs. So the
  # directory has to be built here, and `-Dcomposeai.cli.skikoDir` is what makes
  # `locateBundleSidecarJars("lib-daemon-desktop")` prepend it (`configuredSkikoJars`). Same
  # pairing rule as the rows above, and a fourth distinct symptom: not a missing jar but a
  # missing `.so`, reported as `ExceptionInInitializerError: null` with the cause stripped.
  "skiko|lib-skiko|composeai.cli.skikoDir|every Skiko-backed lane dies in a static initialiser and ?rcPlayer=cmp-jvm answers 500 for every document"
  "playground|lib-bta|composeai.cli.libBtaDir|/playground is admitted and then disabled, so the box serves no playground at all"
  "dwf-apk|lib-simulator|composeai.cli.libSimulatorDir|DwfApkDaemonBridge cannot find LaunchMain and watch-face catalogs fall back off dwf-apk"
  "dwf-apk|lib-dex2jar|composeai.cli.libDex2jarDir|DwfApkDaemonBridge cannot translate APK DEX classes and watch-face catalogs fail to boot dwf-apk"
)

# What compose-preview-daemon's render runtime carries at these paths (its layout contract).
base_dirs=" lib-daemon-android lib-daemon-desktop lib-renderer lib-skiko "

# Whether the runtime stage is built FROM the render runtime: the `base` stage is that image and the
# runtime stage is `FROM base`. Both lines, so a `base` renamed or rebased onto something else, with
# the contract directories no longer under it, is not mistaken for one.
from_render_runtime() {
  grep -qE '^FROM \$\{RENDER_RUNTIME_IMAGE\}:\$\{DAEMON_VERSION\} AS base$' "${1}" &&
    grep -qE '^FROM base AS runtime$' "${1}"
}

# The line that puts the directory into the RUNTIME stage. Anchored to `COPY --from=`, so a stage
# that unpacks a sidecar into a builder and never carries it forward does not count as carried.
runtime_copies() {
  grep -qE "^COPY --from=[a-z0-9-]+ /opt/${1}( |$)" "${2:-${dockerfile}}" && return 0
  [[ "${base_dirs}" == *" ${1} "* ]] && from_render_runtime "${2:-${dockerfile}}"
}

# The sysprop must name the SAME path the COPY lands on. A `-D…Dir=/opt/lib-daemon-desktop` beside a
# `COPY … /opt/desktop-daemon` is the failure this pairing check exists for: both halves are present
# and the lookup still finds nothing.
sysprop_points_at() {
  grep -qE -- "-D${2}=/opt/${1}( |\"|$)" "${dockerfile}"
}

for entry in "${sidecars[@]}"; do
  IFS='|' read -r backend dir sysprop consequence <<<"${entry}"
  runtime_copies "${dir}" || {
    echo "FAIL: backend=${backend} needs /opt/${dir} in the runtime stage, but no COPY --from carries it" >&2
    echo "      and it is not a render-runtime directory under a runtime stage built FROM that image." >&2
    echo "      Then ${consequence}." >&2
    exit 1
  }
  sysprop_points_at "${dir}" "${sysprop}" || {
    echo "FAIL: nothing sets -D${sysprop}=/opt/${dir}, so the ${backend} daemon lookup falls back to" >&2
    echo "      \$APP_HOME/${dir} — which the server distribution does not carry." >&2
    exit 1
  }
  echo "PASS: backend=${backend} sidecar /opt/${dir} is baked and located by ${sysprop}"
done

# Self-test: prove the pairing check can actually fail, rather than trusting two greps that both
# happen to pass on today's file. A Dockerfile that copies the sidecar but points the sysprop
# somewhere else is the silent-fallback shape, and must be caught.
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
cat >"${tmp}" <<'BAD'
FROM base AS runtime
COPY --from=desktop-daemon /opt/lib-daemon-desktop /opt/lib-daemon-desktop
ENV JAVA_TOOL_OPTIONS="-Dcomposeai.cli.libDaemonDesktopDir=/opt/somewhere-else"
BAD
if DOCKERFILE="${tmp}" grep -qE -- "-Dcomposeai.cli.libDaemonDesktopDir=/opt/lib-daemon-desktop( |\"|$)" "${tmp}"; then
  echo "FAIL: self-test — a sysprop pointing at the wrong path was not caught." >&2
  exit 1
fi
echo "PASS: self-test — a sidecar copied but pointed elsewhere is caught"

# Self-test: a contract directory is only "from the base" when the runtime stage really is built on
# the render runtime. A plain temurin base that claims nothing must be caught.
cat >"${tmp}" <<'BAD'
FROM eclipse-temurin:21-jdk AS base
FROM base AS runtime
ENV JAVA_TOOL_OPTIONS="-Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android"
BAD
if runtime_copies lib-daemon-android "${tmp}"; then
  echo "FAIL: self-test — a contract directory was assumed present without the render-runtime base." >&2
  exit 1
fi
# …and a compose-ai-tools sidecar is never assumed to come from the base.
cat >"${tmp}" <<'BAD'
FROM ${RENDER_RUNTIME_IMAGE}:${DAEMON_VERSION} AS base
FROM base AS runtime
BAD
if runtime_copies lib-rcjvm "${tmp}"; then
  echo "FAIL: self-test — lib-rcjvm was assumed to come from the render runtime, which never carries it." >&2
  exit 1
fi
runtime_copies lib-daemon-android "${tmp}" || {
  echo "FAIL: self-test — a contract directory under the render-runtime base was not accepted." >&2
  exit 1
}
echo "PASS: self-test — base-provided directories are accepted only under the render-runtime base"

echo "PASS: all live-render sidecar checks"
