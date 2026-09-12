# Compose Preview Server

The server behind `compose-preview serve`: catalog hosting, live render sessions, the playground,
and the browser viewer surfaces. Its history was extracted from
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools); the CLI remains there and
launches this repository's distribution.

## What this repository ships

**Archives on each GitHub release, and nothing on Maven Central.**

| Asset | What it is |
| --- | --- |
| `compose-preview-server-<v>.tar.gz` | the server: catalog hosting, the HTTP routes, the playground, the viewer surfaces. What `compose-preview serve`, `browse` and `ui-builder` launch |
| `compose-preview-mcp-<v>.tar.gz` | the MCP server, behind `compose-preview mcp serve` |
| `compose-preview-ui-builder-web-<v>.zip` | the Compose/Wasm frontend, for serving yourself or pointing an existing `serve` at with `--ui-builder-dir`. The server archive already carries a copy |

Nothing here is a Maven coordinate. Six modules used to publish — `compose-preview-serve`,
`compose-preview-mcp`, and four more that existed on Central only because the first one's POM named
them — and the arrangement cost more than it bought. A project dependency reaches a published POM as
a coordinate, so adding one to `:server` meant publishing the dependency too; getting that wrong
shipped `compose-preview-server:ui-builder-export-jvm:unspecified` in 3.1.0 and an unresolvable
`compose-preview-serve` from 3.3.0 through 3.8.0. Six releases nobody could resolve, for transitives
nobody wanted.

The one library consumer was compose-ai-tools' `:cli`, which compiled two wire-drift tests against
`compose-preview-serve`. Those tests launch the distribution now
([compose-ai-tools#5436](https://github.com/yschimke/compose-ai-tools/pull/5436)) — the artifact
`serve` runs anyway, so they check the wire that actually ships. Released coordinates up to and
including 3.24.0 stay resolvable on Central; there simply will not be new ones.

`render-host` exists because rendering a packed bundle and reading a preview timeline out of git
open no sockets, and a caller doing only that should not link `ktor-server-*`, `jmdns` and
`kotlin-reflect` to do it. It is **published from
[compose-ai-tools](https://github.com/yschimke/compose-ai-tools)**, not from here: that is where
everything it depends on lives, and offline behaviour belongs in that layer
([#180](https://github.com/yschimke/compose-preview-server/issues/180)). It used to be this
repository's `:render-host` module, publishing as `compose-preview-render-host`; that coordinate
stays resolvable at its final 2.x for anyone pinned to it, and the new one is on compose-ai-tools'
version line. The measured before/after and the transitives it deliberately cannot drop are recorded
in its build file there, and `checkRenderHostIsServerFree` moved with it.

`:ui-builder-runtime` owns authoritative persistent design state, exact catalog validation and
revision-pinned export orchestration. `:server` supplies HTTP/authentication and adapts its narrow
render request onto the render host. The runtime therefore has no Ktor, daemon/render-host, MCP or
Compose UI dependency, while the offline render host has no UI-builder protocol or service edge.

The build is intentionally repository-independent. Compose Preview implementation artifacts resolve
from Maven Central at the version in `gradle/libs.versions.toml`; wire contracts resolve separately
from [`compose-preview-contracts`](https://github.com/yschimke/compose-preview-contracts). There is
no composite build, project substitution, shared version catalog, or `mavenLocal()` repository.
For unreleased contract or generator changes, an explicit
[local dependency manifest](docs/development/LOCAL_DEPENDENCIES.md) selects artifacts compiled from
local checkouts. Leaving that option unset retains the released dependency graph.

## Java

**Run the distribution on Java 17 or newer. The UI builder's PNG/SVG export needs Java 21.**

The two numbers are one difference, and it is worth stating plainly rather than leaving to be
inferred from build files. The server itself, `mcp serve`, and every artifact this repository
publishes target Java 17, because `compose-ai-tools`' CLI compiles against them on a 17 toolchain
and its `serve` / `mcp serve` commands launch this distribution's start script as a separate
process, resolving `java` from `JAVA_HOME`/`PATH`. Only the UI builder's renderer is above that
floor: the design it rasterizes is drawn by a Compose preview compiled for Java 21, packaged into
the render bundle, and unpacked into a daemon that runs on the server's own JVM.

On an older JVM the server still starts and everything else works; the UI builder loses PNG and SVG
export and says so at startup, naming the version it found and the one it needs. Point `JAVA_HOME`
at a Java 21 JDK to get it back, or pass `--ui-builder-state-dir none` to run without the UI builder
at all. The published container image
([`deploy/image/Dockerfile`](deploy/image/Dockerfile)) is built on Temurin 21 and clears both.

Both floors are declared once, as `java-server` and `java-ui-builder` in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml), which is also where the reasoning lives.
`:server:checkServerJvmFloor` fails the build if anything above `java-server` reaches the
distribution's classpath, and the render bundle carries `java-ui-builder` as data so the startup
message cannot drift from the bytes it describes.

## Build

Building needs **both** JDKs on the machine: Gradle runs on 17, and `:ui-builder` /
`:ui-builder-artwork` compile through a 21 toolchain. Gradle finds a 21 installed anywhere it
already scans (`/usr/lib/jvm`, SDKMAN, asdf, jabba); if it cannot, the failure is
`No matching toolchains found for requested specification: {languageVersion=21}` and the fix is to
install one, not to lower the target.

```shell
./gradlew check ktfmtCheckAll
npm --prefix serve-web ci
npm --prefix serve-web run verify
```

The independently installable visual harness lives in `preview-harness/`. The experimental
Compose/Wasm frontend lives in `wasm-ui/`. The UI builder frontend incubates in the
dependency-isolated `ui-builder/` module; its native Compose renderer and standalone Wasm visual
fixture remain separate from the published JVM runtime. `ui-builder-reference-jetcaster/` is a
separately compiled, provenance-pinned Compose/Wasm
oracle for the primary Jetcaster visual benchmark and has no dependency on the builder module.
The server distribution packages the builder's Jetcaster benchmark preview as a separate app at
`/ui-builder/`; the existing catalog-scoped `/wasm/<system>/` preview application remains a
distinct feature and route. The builder route opens an interactive Wasm editor around the frozen
Jetcaster design; clean benchmark modes remain available to the independent visual harness.
`:ui-builder-renderer` builds a separate renderer-only CMP/Wasm runtime directory and ZIP. The
editor can mount an exact retained runtime under `/ui-builder/runtime/<runtimeId>/` in a sandboxed
iframe and receive measured node/slot geometry without placing editor overlays in the Compose tree.
The distribution consumes the frontend through the immutable `:ui-builder-web` archive variant;
it no longer reaches into the frontend project's tasks or output directory.

The Remote Compose authoring extension is behind the default-off compile-time option
`-PuiBuilderRemoteCompose=true`. Build the server and WASM frontend together with that option;
see [feature scope and verification](docs/development/UI_BUILDER_FEATURE_FLAGS.md).

## Remote catalog MCP

The server can expose every hosted catalog through one aggregate Streamable HTTP MCP endpoint at
`/mcp`. Enable it with `--agent-grants --catalog-mcp`; published resources require a
short-lived `preview` grant, while made-to-order renders and structured data products require a
`live` grant. The endpoint is separate from the stateful UI-builder authoring MCP surface, but both
use the same authenticated user approval and revocation flow. See
[the catalog MCP design and setup guide](docs/design/CATALOG_MCP.md).

## Spatial and WebXR previews

A portable bundle can publish an XR preview as a version-one `SpatialScene` document and its panel
textures:

```text
previews/<preview-id>.spatial/scene.json
previews/<preview-id>.spatial/<panel>.png
```

The viewer opens these scenes in an orbitable Three.js/WebGL stage and offers **Enter VR** when the
browser exposes an `immersive-vr` WebXR session. WebXR requires a secure context, so a headset must
reach the server over HTTPS (localhost remains suitable for desktop WebGL development). Uploaded
bundles may carry JSON scene documents and PNG/JPEG/WebP textures only; all assets are served from
the scene's same-origin `/spatial/` route.

Build and run the standalone distribution with:

```shell
./gradlew :server:distTar
tar -xzf server/build/distributions/compose-preview-server-*.tar.gz
./compose-preview-server-*/bin/compose-preview-server help
```

The binary has five commands, and `help` lists them:

| Command | What it does |
| --- | --- |
| `serve` | Host previews — fetched bundles, published catalogs, or a local module's `@Preview` functions with `--module` / `--discover`. |
| `ui` | Build this project's previews and open the Compose UI builder against them. |
| `playground` | `serve` with the snippet compile lane admitted. |
| `design` | Render, export or read a UI-builder design from a server that is already up. The one command that does not serve. |
| `help [command]` | The command list, or one command's options. |

Flags may still be passed with no command in front of them: `compose-preview-server --module app`
is exactly `compose-preview-server serve --module app`, and stays supported.

`ui` needs a build host — the `compose-preview` binary — because discovering and building a local
Gradle project is work this server asks for over a pipe rather than doing itself. The builder's
palette is a packaged design-system catalog; what `ui` adds is the project, by pointing
`--ui-builder-components` at the module's discovered `components.json` so the Compose export
generates call sites for your composables. From a checkout with the CLI installed:

```shell
compose-preview-server ui --module app
```

`design` is the client half of that lane, and the one command here that starts no server: it talks
to one that is already up — a local `serve`, or a deployment — and writes a design's pixels or its
generated source to a file, which is what a session otherwise re-invents as a `curl` into `/mcp`, a
`jq` to unwrap the envelope and a `base64 -d`
([#529](https://github.com/yschimke/compose-preview-server/issues/529)):

```shell
export COMPOSE_PREVIEW_TOKEN=...          # or let the command ask a human to approve a grant
compose-preview-server design list --server https://preview.coo.ee
compose-preview-server design render spotify-wear-widget -o cover.png
compose-preview-server design export spotify-wear-widget -o Widget.kt
compose-preview-server design get spotify-wear-widget > design.json
```

The credential is read from `$COMPOSE_PREVIEW_TOKEN` (or the older
`$COMPOSE_PREVIEW_UI_BUILDER_TOKEN` that `scripts/ui-builder/design-sync.mjs` reads), never from a
flag. With neither set — or after a restart has dropped the grant — the command runs the server's
own device-code flow: it prints an approval link and a code, waits for a human, and carries on.
A refused export prints the generator's own diagnostics to stderr and exits non-zero, writing
nothing, so it composes in CI.

`--local` is the half of that command which needs no server at all: it runs the same generator,
compiler and render daemon a server would, **here**, against a catalog bundle on disk — and says
why a frame is missing rather than only that it is, which the wire reply cannot
([#551](https://github.com/yschimke/compose-preview-server/issues/551)). That is what makes a
broken host debuggable: `design get` captures its document (a read, not the render lane under
suspicion) and the file replays anywhere.

```shell
compose-preview-server design get spotify-wear-widget --server https://preview.coo.ee > doc.json
compose-preview-server design render --document doc.json --local \
  --catalog wear-m3.bundle --assets ./assets -o replay.png
compose-preview-server design export --document doc.json --local   # the generated Kotlin, no server
```

A local render prints the classpath it resolved, the daemon opener it built and the compiler's own
diagnostics; `--components <catalog>=<components.json>` names the record a record-driven catalog's
call sites are proven against, exactly as `serve --ui-builder-components` does.

A served catalog's own composables can also be offered *inside* the builder's catalogs as a
component pack (`--ui-builder-packs confetti-mobile=mobile,confetti-wear=wear`), switched on by an
author from the editor's settings; see
[`docs/design/UI_BUILDER_COMPONENT_PACKS.md`](docs/design/UI_BUILDER_COMPONENT_PACKS.md).

Releases attach that distribution to the GitHub release, then build the production
`ghcr.io/yschimke/compose-preview-host` image. The canonical Docker and `preview.coo.ee`
configuration lives in [`deploy/image`](deploy/image/) and
[`deploy/preview.coo.ee`](deploy/preview.coo.ee/).

## Releasing

One lane in [`release.yml`](.github/workflows/release.yml): build
`compose-preview-server-<v>.tar.gz`, `compose-preview-mcp-<v>.tar.gz` and
`compose-preview-ui-builder-web-<v>.zip`, attach them to the GitHub release, then build the
`compose-preview-host` image.

The GitHub release stays a **draft** until that lane has succeeded, so a failed build never leaves a
tag whose assets do not exist.

There used to be a second, skippable Maven lane, with a `release:no-maven` label and a
`publish_maven` workflow input to turn it off. Both are gone with the publication — every release is
now what that label used to ask for.



## Repository boundary

`checkServeModuleBoundary` walks the resolved runtime classpath, transitives included. It rejects
project dependencies, renderer/daemon implementations, the Gradle plugin, and any unregistered
`ee.schimke.composeai` coordinate. Update its positive allowlist only when a reviewed dependency
floor change is intentional.

The source package remains `ee.schimke.composeai.cli.serve` for binary/source continuity. A package
rename is independent of repository ownership and is not part of the extraction.
