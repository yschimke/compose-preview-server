# Compose Preview Server

The server behind `compose-preview serve`: catalog hosting, live render sessions, the playground,
and the browser viewer surfaces. Its history was extracted from
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools); the CLI remains there and
consumes this repository's published library.

The JVM API is published as two artifacts:

```kotlin
// The server: catalog hosting, the HTTP routes, the playground, the viewer surfaces.
implementation("ee.schimke.composeai:compose-preview-serve:<version>")

// Just the render host, the bundle daemon and the git-backed preview history — no web server.
// What an OFFLINE caller (`compose-preview bundle render`, `compose-preview history manifest`)
// needs. `compose-preview-serve` depends on this, so depending on the server still gets you both.
implementation("ee.schimke.composeai:compose-preview-render-host:<version>")
```

`:render-host` exists because rendering a packed bundle and reading a preview timeline out of git
open no sockets, and a caller doing only that should not link `ktor-server-*`, `jmdns` and
`kotlin-reflect` to do it. `render-host/build.gradle.kts` records the measured before/after and the
transitives it deliberately cannot drop; `checkRenderHostIsServerFree` keeps the claim honest.

The build is intentionally repository-independent. Compose Preview implementation artifacts resolve
from Maven Central at the version in `gradle/libs.versions.toml`; wire contracts resolve separately
from [`compose-preview-contracts`](https://github.com/yschimke/compose-preview-contracts). There is
no composite build, project substitution, shared version catalog, or `mavenLocal()` repository.

## Build

```shell
./gradlew check ktfmtCheckAll
npm --prefix serve-web ci
npm --prefix serve-web run verify
```

The independently installable visual harness lives in `preview-harness/`. The experimental
Compose/Wasm frontend lives in `wasm-ui/`. The UI builder incubates in the dependency-isolated
`ui-builder/` module; it has its own reducer, native Compose renderer, standalone Wasm visual
fixture and code exporter so that it can later move out without pulling server implementation with
it. `ui-builder-reference-jetcaster/` is a separately compiled, provenance-pinned Compose/Wasm
oracle for the primary Jetcaster visual benchmark and has no dependency on the builder module.
The server distribution packages the builder's Jetcaster benchmark preview as a separate app at
`/ui-builder/`; the existing catalog-scoped `/wasm/<system>/` preview application remains a
distinct feature and route. The builder route opens an interactive Wasm editor around the frozen
Jetcaster design; clean benchmark modes remain available to the independent visual harness.

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
./compose-preview-server-*/bin/compose-preview-server --help
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
