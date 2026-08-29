# Compose Preview Server

The server behind `compose-preview serve`: catalog hosting, live render sessions, the playground,
and the browser viewer surfaces. Its history was extracted from
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools); the CLI remains there and
consumes this repository's published library.

The JVM API is published as:

```kotlin
implementation("ee.schimke.composeai:compose-preview-serve:<version>")
```

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
Compose/Wasm frontend lives in `wasm-ui/`.

## Repository boundary

`checkServeModuleBoundary` walks the resolved runtime classpath, transitives included. It rejects
project dependencies, renderer/daemon implementations, the Gradle plugin, and any unregistered
`ee.schimke.composeai` coordinate. Update its positive allowlist only when a reviewed dependency
floor change is intentional.

The source package remains `ee.schimke.composeai.cli.serve` for binary/source continuity. A package
rename is independent of repository ownership and is not part of the extraction.
