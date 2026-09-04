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
# That is exactly how preview.coo.ee ran: the Android pair (`/opt/lib-daemon-android` +
# `-Dcomposeai.cli.libDaemonAndroidDir`) was baked and every `backend: "android"` catalog was live,
# while the desktop pair was in neither the image nor JAVA_TOOL_OPTIONS and every
# `backend: "desktop"` catalog — m3-catalog among them — served snapshots. The server distribution
# carries only `bin/`, `lib/`, `wasm-ui/` and `ui-builder/`; both sidecars ride in compose-ai-tools' `:cli`
# distribution, so `$APP_HOME/lib-daemon-desktop` resolves to nothing and the fallback is silent.
#
# So: for each backend, assert the directory is produced by a build stage, copied into the runtime
# stage, and named by the sysprop that locates it. A stage that fetches a sidecar nobody points at,
# or a sysprop naming a path nothing populates, both fail here.
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
  # server serves no playground — but it is just as invisible to a build: preview.coo.ee logged
  # "playground admitted" and then "playground compiler unavailable" on the next line, for months.
  "playground|lib-bta|composeai.cli.libBtaDir|/playground is admitted and then disabled, so the box serves no playground at all"
)

# The line that puts the directory into the RUNTIME stage. Anchored to `COPY --from=`, so a stage
# that unpacks a sidecar into a builder and never carries it forward does not count as carried.
runtime_copies() {
  grep -qE "^COPY --from=[a-z0-9-]+ /opt/${1}( |$)" "${dockerfile}"
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
    echo "FAIL: backend=${backend} needs /opt/${dir} in the runtime stage, but no COPY --from carries it." >&2
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

echo "PASS: all live-render sidecar checks"
