# syntax=docker/dockerfile:1.29

# Fast, prebuilt-friendly image for the standalone preview server.
#
# This installs the RELEASED standalone compose-preview-server tarball and the
# separately released Android daemon from compose-ai-tools. It serves published catalogs and their live
# bundles module-less: no Gradle project, build-logic, plugin build, or sample
# render is needed in the image. Built once in CI and pushed to GHCR, so hosts
# pull it and never build at all.
# Bumped automatically by release-please on each server release (see release-please-config.json
# extra-files) so a fallback / local `docker build` never ships a stale server. An inline
# `# x-release-please-version` can't go on the ARG line (Docker folds it into the value), so use
# the block-marker form.
# x-release-please-start-version
ARG SERVER_VERSION=3.192.0
# x-release-please-end
# Required build input. The release workflow reads it from gradle/libs.versions.toml so the
# server's runtime dependencies and its separately packaged daemon sidecars cannot drift.
ARG TOOLS_VERSION
# The compose-preview-daemon release whose render-runtime image this builds FROM. That image carries
# the daemon sidecars (the Android daemon, the desktop daemon + renderer and their Skiko native),
# the Android SDK, the prefetched Robolectric runtime and the baked fonts; compose-preview-daemon
# builds and publishes it on each release (its docs/daemon/RENDER_RUNTIME_IMAGE.md is the layout
# contract this file relies on). TOOLS_VERSION names only what still ships from compose-ai-tools
# (`lib-rcjvm/`, `lib-bta/`, `lib-simulator/`, `lib-dex2jar/`). No default: automatic releases read
# `composeai-preview-daemon` from gradle/libs.versions.toml, so the sidecars always match the
# daemon client the server links.
ARG DAEMON_VERSION
# Overridable for a fork that publishes its own base, or for a local build against a tag you built.
ARG RENDER_RUNTIME_IMAGE=ghcr.io/yschimke/compose-preview-render-runtime

# ---------------------------------------------------------------------------
# Stage 0: base — compose-preview-daemon's render runtime.
#
# Temurin 21 with Skiko's native deps and software GL (`LIBGL_ALWAYS_SOFTWARE=1`), the Noto font
# fallbacks, `curl`/`git`/`jq`, a minimal Android SDK at /opt/android-sdk, the Android daemon at
# /opt/lib-daemon-android, the desktop daemon + renderer at /opt/lib-daemon-desktop and
# /opt/lib-renderer, the paired Skiko native at /opt/lib-skiko, Robolectric's instrumented
# android-all under /root/.m2, and the baked downloadable-font cache at /opt/font-cache/fonts. These
# used to be this file's `android-daemon`, `desktop-daemon` and `fonts` stages; they moved to the
# repository that releases the daemons, so the UI-builder image can share them without this server.
# `/opt/compose-preview-render-runtime.json` records the versions it was built with.
#
# `bubblewrap` is installed only in the `playground` target stage below, keeping the main
# `compose-preview-host` image free of user-namespace sandbox tooling while sharing all
# runtime layers between `host` and `playground`.
# ---------------------------------------------------------------------------
FROM ${RENDER_RUNTIME_IMAGE}:${DAEMON_VERSION} AS base
ENV GRADLE_USER_HOME=/root/.gradle
# ---------------------------------------------------------------------------
# Stage 1: install the released standalone server.
# ---------------------------------------------------------------------------
FROM base AS server
ARG SERVER_VERSION
WORKDIR /opt
# Automatic releases stage the exact tag's tarball in the build context and can
# build/deploy before the draft GitHub Release becomes public. Manual and
# human-published-release builds leave the dir empty and retain the public-asset
# fallback (with retries for upload propagation).
COPY release-assets/ /tmp/release-assets/
RUN set -eu; \
    local="/tmp/release-assets/compose-preview-server-${SERVER_VERSION}.tar.gz"; \
    if [ -f "$local" ]; then \
      cp "$local" /tmp/cp.tgz; \
    else \
      curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/cp.tgz \
        "https://github.com/yschimke/compose-preview-server/releases/download/v${SERVER_VERSION}/compose-preview-server-${SERVER_VERSION}.tar.gz"; \
    fi; \
    tar -xzf /tmp/cp.tgz -C /opt; \
    rm /tmp/cp.tgz; \
    ln -s "/opt/compose-preview-server-${SERVER_VERSION}" /opt/compose-preview-server
ENV PATH="/opt/compose-preview-server/bin:${PATH}"

# ---------------------------------------------------------------------------
# Stage 1b: compose-ai-tools' sidecars, which the render runtime deliberately does not carry
# (compose-preview-daemon may not reach compose-ai-tools).
#
# Only `lib-rcjvm/` (the `cmp-jvm` Remote Compose player `RcJvmServerRenderer` spawns, classpath
# `lib-rcjvm/*` + `lib-daemon-desktop/*`), `lib-bta/` (the Kotlin Build Tools jars
# `PlaygroundBtaCompiler.fromInstall` compiles playground snippets with), and `lib-simulator/` +
# `lib-dex2jar/` (the `dwf-apk` watch-face renderer, `DwfApkDaemonBridge`) are lifted from the CLI
# tarball; the rest of it (the `compose-preview` launcher, its own `lib/`, the Wasm players) is not
# this image's job, since the server is the entrypoint. Each is located the way the daemon sidecars
# are, `-Dcomposeai.cli.<name>Dir`, else `$APP_HOME/<name>`. A missing one disables a lane silently
# (a 503 on `?rcPlayer=cmp-jvm`, a playground admitted and then unavailable), hence the assertions.
#
# The `lib-bta/` assertions are two, not one, because `PlaygroundBtaCompiler.fromInstall` splits
# that directory BY FILENAME: the Compose plugin jar rides the per-compile plugin classpath and
# everything else loads into BTA's isolated classloader. A tarball carrying only one of those halves
# satisfies a plain `ls *.jar` and still makes `fromInstall` return null.
#
# Local-first / public-fallback, like the server stage: an automatic release stages the tag's
# tarball into the build context and can build before the draft GitHub Release is public.
# ---------------------------------------------------------------------------
FROM base AS tools-sidecars
ARG TOOLS_VERSION
COPY release-assets/ /tmp/release-assets/
RUN set -eu; \
    local="/tmp/release-assets/compose-preview-${TOOLS_VERSION}.tar.gz"; \
    if [ -f "$local" ]; then \
      cp "$local" /tmp/cli.tgz; \
    else \
      curl -fsSL --retry 20 --retry-delay 30 --retry-all-errors -o /tmp/cli.tgz \
        "https://github.com/yschimke/compose-ai-tools/releases/download/v${TOOLS_VERSION}/compose-preview-${TOOLS_VERSION}.tar.gz"; \
    fi; \
    tar -xzf /tmp/cli.tgz -C /opt --strip-components=1 --wildcards \
      "*/lib-rcjvm" "*/lib-bta" "*/lib-simulator" "*/lib-dex2jar"; \
    rm /tmp/cli.tgz; \
    test -d /opt/lib-rcjvm; \
    test -d /opt/lib-bta; \
    test -d /opt/lib-simulator; \
    test -d /opt/lib-dex2jar; \
    ls /opt/lib-rcjvm/*.jar >/dev/null; \
    ls /opt/lib-bta/*.jar >/dev/null; \
    ls /opt/lib-simulator/*.jar >/dev/null; \
    ls /opt/lib-dex2jar/*.jar >/dev/null; \
    ls /opt/lib-bta/kotlin-compose-compiler-plugin-embeddable*.jar >/dev/null; \
    test "$(ls /opt/lib-bta/*.jar | grep -cv kotlin-compose-compiler-plugin-embeddable)" -gt 0

# `lib-rcjvm/`'s own Skiko native, paired by version with the `skiko-awt` it carries.
#
# TWO Skiko versions meet on the cmp-jvm classpath: `lib-daemon-desktop/` (compose-preview-daemon,
# whose native the base already stages in /opt/lib-skiko) and `lib-rcjvm/` (compose-ai-tools), built
# by different releases — on 3.125.0 they were 0.144.6 and 0.150.1. The cmp-jvm classpath is
# `lib-rcjvm/*` FIRST, so it binds lib-rcjvm's `skiko-awt`; with only the older native, every call
# into a newer JNI symbol (`ParagraphKt._nGetUnresolvedCodepointsCount`, i.e. any text) threw
# `UnsatisfiedLinkError`. So lib-rcjvm/ gets its own native, read off its jar rather than pinned,
# and `check-skiko-pairing.sh` fails the build if any classpath under /opt pairs otherwise. A
# lib-rcjvm with no `skiko-awt` gets no native; the check decides whether that is fine. Fetching it
# is a hard failure: there is no runtime fallback, and an image without it is silently broken.
COPY check-skiko-pairing.sh /usr/local/bin/check-skiko-pairing.sh
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) platform=linux-x64 ;; \
      arm64) platform=linux-arm64 ;; \
      *) echo "no Skiko native for $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    stage_native() { \
      awt="$(ls "$1"/skiko-awt-*.jar 2>/dev/null | grep -v '/skiko-awt-runtime-' | head -n 1)"; \
      version="$(basename "$awt" .jar | sed 's/^skiko-awt-//')"; \
      test -n "$version" || return 0; \
      nativeJar="skiko-awt-runtime-${platform}-${version}.jar"; \
      mkdir -p "$2"; \
      curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o "$2/${nativeJar}" \
        "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-${platform}/${version}/${nativeJar}"; \
      jar tf "$2/${nativeJar}" | grep -qx "libskiko-${platform}.so"; \
    }; \
    stage_native /opt/lib-rcjvm /opt/lib-rcjvm; \
    chmod +x /usr/local/bin/check-skiko-pairing.sh; \
    check-skiko-pairing.sh /opt "$platform"

# ---------------------------------------------------------------------------
# Stage 2: runtime.
# ---------------------------------------------------------------------------
FROM base AS runtime
ARG SERVER_VERSION
COPY --from=server "/opt/compose-preview-server-${SERVER_VERSION}" "/opt/compose-preview-server-${SERVER_VERSION}"
RUN ln -s "/opt/compose-preview-server-${SERVER_VERSION}" /opt/compose-preview-server
# The Android daemon + Android SDK and the desktop daemon + renderer come from the base (the render
# runtime): /opt/lib-daemon-android, /opt/android-sdk, /opt/lib-daemon-desktop, /opt/lib-renderer
# and /opt/lib-skiko. Inert until a served catalog resolves a liveBundle for that backend.
# …plus the one jar the daemon's renderer tarball does not carry and the server's own `lib-renderer/`
# does: `window-core-desktop`. `-Dcomposeai.cli.libRendererDir` points the lookup at THIS directory,
# so the copy `server/build.gradle.kts` adds to the server distribution for #812 never reached the
# daemon, and every UI-builder PNG export that drew a constrained frame died on
# `NoClassDefFoundError: androidx/window/core/layout/WindowSizeClass` — a LinkageError, so the
# breaker then disabled the lane for every design. Taken from the server tarball rather than
# fetched, so its version is the one `checkServerDesktopSidecarPackaging` pins; a glob with no match
# fails the build, which is the assertion. `test-renderer-window-core.sh` keeps the lines here.
# From window-core 1.5 `window-core-desktop` is an empty redirect and Google's `window-core-jvm`
# holds `WindowSizeClass`, so both jars are copied, each by its own glob so each must match.
COPY --from=server "/opt/compose-preview-server-${SERVER_VERSION}/lib-renderer/window-core-desktop-*.jar" /opt/lib-renderer/
COPY --from=server "/opt/compose-preview-server-${SERVER_VERSION}/lib-renderer/window-core-jvm-*.jar" /opt/lib-renderer/
# …and the embedded JVM Remote Compose player the `cmp-jvm` render lane spawns
# (`RcJvmServerRenderer`), whose subprocess classpath is `lib-rcjvm/*` + `lib-daemon-desktop/*`.
# Only half of that pair was here, so every `?rcPlayer=cmp-jvm` on the deployed site answered 503
# with "needs lib-rcjvm and lib-daemon-desktop on the CLI install" — measured against
# a production host's /remote-m3 — and the compare wall could never show that player.
COPY --from=tools-sidecars /opt/lib-rcjvm /opt/lib-rcjvm
# (The Skiko native `lib-daemon-desktop/` links against is the base's /opt/lib-skiko; lib-rcjvm's
# own is inside the directory just copied — see the tools-sidecars stage.)
# …and the native for the desktop player daemon's own bindings. The server tarball ships
# `lib-rc-player-desktop/`: the CMP Remote Compose player and the Compose Multiplatform line it needs,
# laid ahead of `lib-daemon-desktop/` + `lib-renderer/` so `ServeRcPlayerDaemon.desktopSubprocess` can
# hold a live, input-taking `cmp-jvm` session for a document-only preview. That line moves Skiko past
# the daemon's, so — as for `lib-rcjvm/` above — the directory carries the native for its own
# `skiko-awt`, and the pairing check gains that lane. Without it the server declines to start the
# daemon (it checks the same pairing) and cmp-jvm keeps its one-shot lane; a server without the
# directory skips this. Read off the staged jar, never pinned, for the same reason as above.
COPY check-skiko-pairing.sh /usr/local/bin/check-skiko-pairing.sh
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) platform=linux-x64 ;; \
      arm64) platform=linux-arm64 ;; \
      *) echo "no Skiko native for $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    player=/opt/compose-preview-server/lib-rc-player-desktop; \
    awt="$(ls "$player"/skiko-awt-*.jar 2>/dev/null | grep -v '/skiko-awt-runtime-' | head -n 1 || true)"; \
    if [ -n "$awt" ]; then \
      version="$(basename "$awt" .jar | sed 's/^skiko-awt-//')"; \
      nativeJar="skiko-awt-runtime-${platform}-${version}.jar"; \
      curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o "$player/${nativeJar}" \
        "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-${platform}/${version}/${nativeJar}"; \
      jar tf "$player/${nativeJar}" | grep -qx "libskiko-${platform}.so"; \
    fi; \
    chmod +x /usr/local/bin/check-skiko-pairing.sh; \
    check-skiko-pairing.sh /opt "$platform"
# …and the Kotlin Build Tools API jars the PLAYGROUND compiles snippets with. Unlike the
# sidecars above it backs no render lane: without it `--playground` is admitted and then
# disabled at the last step, which is how a production host ran with a playground nobody could
# open. See the tools-sidecars stage for why it is lifted here.
COPY --from=tools-sidecars /opt/lib-bta /opt/lib-bta
# …and the simulator host + dex2jar sidecars (`LaunchMain`) that back the `dwf-apk` watch-face
# renderer daemon (`DwfApkDaemonBridge`).
COPY --from=tools-sidecars /opt/lib-simulator /opt/lib-simulator
COPY --from=tools-sidecars /opt/lib-dex2jar /opt/lib-dex2jar
# JAVA_TOOL_OPTIONS (inherited by the spawned daemon JVM too) points the CLI at the
# unpacked Android AND desktop daemon sidecars, warms catalog daemons in the background so the
# HTTP listener can come up before every live catalog finishes, and lifts BOTH cold-start budgets so a cold
# Robolectric daemon doesn't abort before it can serve live:
#   * sandboxBootTimeoutMs — the Robolectric sandbox boot (the one-time
#     `android-all-instrumented` ~150 MB fetch + instrumentation, in RobolectricHost.start,
#     default 600s) — the dominant cold cost; raised so a first boot completes.
#   * renderTimeoutSeconds — serve's per-render *cold* budget once booted.
#   * frameRenderTimeoutSeconds — serve's per-render budget for a *warm* frame. The 10s default
#     suits a warm Skiko desktop frame but starves a warm Robolectric (Wear) frame and any
#     override-bearing re-render (a `?knob.…=` edit forces a real recompose) on this shared box,
#     which surfaced as `/render?knob.…` returning `500 timed out after 10s`. Lift it in lockstep
#     with the cold budget so warm overrides/Wear frames have room (ServeRenderHost also gives the
#     first override render the cold budget, so this only bounds steady-state frames).
# Baked catalog pixels remain available while a requested live daemon warms. See
# RobolectricHost / ServeCatalogLiveHost / ServeRenderHost.
#
# sandboxCount — the Android/Robolectric daemon defaults to a **5**-sandbox pool (DaemonMain,
# warmSpare). Five instrumented sandboxes (each ~150 MB+ of instrumented android-all in its own
# classloader) is heavy for this 8 GB box; the memory pressure is what makes one sandbox
# intermittently die with `Unable to load Robolectric native runtime library` mid-boot. Pin the
# pooled Android daemons to **3** (interactive needs ≥2; 3 keeps an interactive slot + two render
# slots) to cut that footprint ~40%. RobolectricHost also now retries a transient per-sandbox boot
# failure, so the two changes together keep the Wear live lane up instead of collapsing it to the
# baked-PNG snapshot. Desktop (CMP) daemons ignore this knob.
#
# backgroundSandboxBoot — RobolectricHost's fast cold path: `initialize` returns once ONE sandbox
# is ready (~12 s warm-cache) instead of all `sandboxCount` (~35 s at 3), the remaining sandboxes
# boot on a background thread with a boot-time warm render each, and dispatch routes across the
# ready slots meanwhile. Serve fronts the daemon with baked PNGs while it warms, so nothing here
# needs the strict all-ready contract. `ServeBundleDaemon` already defaults bundle-spawned Android
# daemons in; carrying it in JAVA_TOOL_OPTIONS extends the same behaviour to the Gradle
# source-build catalog path (its daemon JVMs inherit this env). Desktop daemons ignore the knob.
#
# composeai.serve.spareSandboxes — the server keeps this many Android sandbox workers booted and
# warm ahead of demand (ServeSpareSandboxes / daemon-client's SandboxSparePool), keyed by daemon
# classpath, and every Android catalog daemon it spawns adopts them instead of booting its own:
# `initialize` answers in well under a second instead of 4-9 s, and a reaped daemon hands its
# workers back warm for the next one. Two spares is one daemon's worth at sandboxCount=3 (its own
# in-process sandbox fills the third slot), ~1 GB resident held in reserve.
#
# lazyInProcessSandbox — a daemon that adopted spares defers its own in-process sandbox until a path
# only it can serve asks (a held interactive session, @PreviewParameter rows, or a render with no
# worker left), instead of booting it in the background on every open: a boot and ~220 MB per
# catalog daemon that a serve box mostly never needs. Off by default because the Gradle / VS Code
# path wants that sandbox for the panel's live toggle; here it boots on first use.
ENV PATH="/opt/compose-preview-server/bin:${PATH}" \
    SERVE_IDLE_EXIT=0 \
    SERVE_TIMEOUT=1800 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dcomposeai.cli.libDaemonAndroidDir=/opt/lib-daemon-android -Dcomposeai.cli.libDaemonDesktopDir=/opt/lib-daemon-desktop -Dcomposeai.cli.libRendererDir=/opt/lib-renderer -Dcomposeai.cli.libRcjvmDir=/opt/lib-rcjvm -Dcomposeai.cli.libSimulatorDir=/opt/lib-simulator -Dcomposeai.cli.libDex2jarDir=/opt/lib-dex2jar -Dcomposeai.cli.skikoDir=/opt/lib-skiko -Dcomposeai.cli.libBtaDir=/opt/lib-bta -Dcomposeai.serve.warmInBackground=true -Dcomposeai.serve.renderTimeoutSeconds=900 -Dcomposeai.serve.frameRenderTimeoutSeconds=120 -Dcomposeai.daemon.sandboxCount=3 -Dcomposeai.daemon.sandboxBootTimeoutMs=1800000 -Dcomposeai.daemon.backgroundSandboxBoot=true -Dcomposeai.daemon.lazyInProcessSandbox=true -Dcomposeai.serve.spareSandboxes=2"

WORKDIR /project
# `/project` intentionally stays empty: entrypoint.sh starts module-less serve and hosts
# published catalogs/bundles. The only thing in Maven's local cache is the base's pre-fetched
# Robolectric `android-all` runtime, so the Android daemon's first render skips the ~200 MB
# download. Released compose-preview dependencies continue to resolve from their configured remote
# repositories; this image must not force the auto-injected Gradle build to trust arbitrary Maven
# Local state.

# Bake the branch-trust store so a public deploy badges the published
# design-artifacts catalogs as Trusted(Branch) out of the box (SERVE_TRUST_STORE
# defaults to /trust/producers.json). Override by mounting your own there.
COPY trust/ /trust/

# The published catalog set is CONFIG, not image content: this is only the SEED the
# entrypoint copies to /config/catalogs.json on first boot when that (mounted) file
# doesn't exist yet. After that the operator's copy wins — edit it, or POST to
# /admin/catalogs — and an image pull never overwrites it. Mount /config on a volume
# so it outlives the container.
COPY catalogs.json /etc/compose-preview/catalogs.default.json

# The settings.json schema (generated from ServeSettings.kt). The entrypoint reads each setting's
# SERVE_* variable from it, which is why `jq` is in the base image: /config/settings.json is applied
# as environment variables before the entrypoint derives anything from them.
COPY settings.schema.json /etc/compose-preview/settings.schema.json
# …and the one-off migration that drafts a settings.json from a box's .env (see its header), so a
# host without jq can run it from here.
COPY env-to-settings.sh /usr/local/bin/env-to-settings.sh

# The baked downloadable-font cache is the base's /opt/font-cache/fonts. Deliberately NOT straight in
# `~/.cache/composeai/fonts`: that path is a named volume (docker-compose.yml `preview_cache`), and
# a volume only inherits image content when it is FIRST created — an existing box that has been
# running since before this change would never see the baked faces. entrypoint.sh copies the
# missing ones across on every boot instead, which covers both fresh and long-lived volumes.

# The same site-hostname scanner the caddy image carries. Here it answers one question at boot:
# does this deployment serve any top-level site? — which decides whether the GitHub auth cookies
# need widening to the parent domain. Reading it from catalogs.json means a site that arrives
# through the config file (or /admin/sites) counts, not only one spelled out in SERVE_SITES.
COPY site-domains.sh /usr/local/bin/site-domains.sh
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# ---------------------------------------------------------------------------
# Stage 3a: playground — adds `bubblewrap` for `--playground-sandbox bwrap`
# and defaults `SERVE_ROLE=playground`. Shares all `runtime` layers with `host`.
# Published as `ghcr.io/<owner>/compose-preview-playground`.
# ---------------------------------------------------------------------------
FROM runtime AS playground
RUN apt-get update && apt-get install -y --no-install-recommends bubblewrap && \
    rm -rf /var/lib/apt/lists/*
ENV SERVE_ROLE=playground

# ---------------------------------------------------------------------------
# Stage 3b: ui-builder — dedicated UI Builder host (`ui.coo.ee`) with
# `ENV SERVE_ROLE=ui-builder`. Shares all `runtime` layers with `host` and
# `playground`. Published as `ghcr.io/<owner>/compose-preview-ui-builder`.
# ---------------------------------------------------------------------------
FROM runtime AS ui-builder
ENV SERVE_ROLE=ui-builder

# ---------------------------------------------------------------------------
# Stage 3c: host (default final target) — main preview server without
# `bubblewrap`. Published as `ghcr.io/<owner>/compose-preview-host`.
# ---------------------------------------------------------------------------
FROM runtime AS host
