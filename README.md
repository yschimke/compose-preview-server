# Compose Preview Server

The server behind `compose-preview serve`: catalog hosting, live render sessions, the playground,
and the browser viewer surfaces. Its history was extracted from
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools); the CLI remains there and
consumes this repository's published library.

The JVM API and hosted builder frontend are published as four lockstep artifacts:

```kotlin
// The server: catalog hosting, the HTTP routes, the playground, the viewer surfaces.
implementation("ee.schimke.composeai:compose-preview-serve:<version>")

// Just the render host, the bundle daemon and the git-backed preview history — no web server.
// What an OFFLINE caller (`compose-preview bundle render`, `compose-preview history manifest`)
// needs. `compose-preview-serve` depends on this, so depending on the server still gets you both.
implementation("ee.schimke.composeai:render-host:<version>")

// Persistent collaborative design service, catalog validation and export orchestration. It has no
// Ktor server, daemon/render-host implementation, MCP SDK or Compose UI dependency.
implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime:<version>")

// Immutable Compose/Wasm application archive used by the standalone server distribution. This is
// a deployment input rather than a JVM runtime dependency.
// ee.schimke.composeai:compose-preview-ui-builder-web:<version>
```

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

The binary has four commands, and `help` lists them:

| Command | What it does |
| --- | --- |
| `serve` | Host previews — fetched bundles, published catalogs, or a local module's `@Preview` functions with `--module` / `--discover`. |
| `ui` | Build this project's previews and open the Compose UI builder against them. |
| `playground` | `serve` with the snippet compile lane admitted. |
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

Releases publish that distribution beside the Maven library, then build the production
`ghcr.io/yschimke/compose-preview-host` image. The canonical Docker and `preview.coo.ee`
configuration lives in [`deploy/image`](deploy/image/) and
[`deploy/preview.coo.ee`](deploy/preview.coo.ee/).

## Repository boundary

`checkServeModuleBoundary` walks the resolved runtime classpath, transitives included. It rejects
project dependencies, renderer/daemon implementations, the Gradle plugin, and any unregistered
`ee.schimke.composeai` coordinate. Update its positive allowlist only when a reviewed dependency
floor change is intentional.

The source package remains `ee.schimke.composeai.cli.serve` for binary/source continuity. A package
rename is independent of repository ownership and is not part of the extraction.
