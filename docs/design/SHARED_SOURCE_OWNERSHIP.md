# Shared source ownership

**Status: normative.** The extractions left three surfaces represented in more than one repository.
This table records the one owner, consumer mechanism, and update procedure for each.

| Surface | Owner | Consumer mechanism | Update procedure |
| --- | --- | --- | --- |
| Slot preview runtime | `compose-preview-daemon` | This build consumes `ee.schimke.composeai:slot-preview-runtime` at `composeai-preview-daemon`; its Gradle metadata supplies both JVM and Wasm variants. | Change and test it in daemon, release daemon, then bump this build's daemon pin. Verify `:native-catalog-m3:compileKotlinWasmJs`, `:wasm-ui:wasmJsTest`, and the daemon runtime's JVM tests. Do not restore `:slot-preview-runtime` here. |
| Usage-source PSI parser | `compose-ai-tools` | The server vendors the tools source at the immutable commit in `usage-source-psi/upstream.json`, compiles the parser into its isolated sidecar, and loads it beside `lib-bta`; it is never on `:server`'s runtime classpath. Vendoring is used because tools does not publish this private parser artifact. | Review an upstream tools commit, run `python3 .github/scripts/check-usage-source-psi-vendor.py --refresh <40-char-sha>`, review the source/manifest diff, then run the vendor check plus `:usage-source-psi:check` and the server parser/cleaner tests. |
| Compose/Wasm preview viewer | This repository's `:wasm-ui` | `compose-ai-tools:cli/serve-wasm` is a commit-pinned source fork checked by tools' `.github/ci/check_serve_wasm_fork.py`. Each copy is compiled against its repository's native catalog. | Port shared source changes to tools and bump its pinned upstream SHA using that gate's `--update`. Build and exercise both Wasm applications. Build scripts and the documented font-path paragraph remain repository-specific. |

## Why the Wasm viewer remains a fork

A single prebuilt server viewer cannot replace the tools viewer: `:wasm-ui` links
`:native-catalog-m3`, while tools links its own `:samples:design-catalog-m3-shared`. That catalog is
compiled into the Wasm application, not supplied through a runtime registration seam. Migrating to
one artifact would first require a catalog registration API exercised in both builds; issue #687
does not invent that larger runtime seam. The existing full-inventory, commit-pinned tools gate is
therefore the supported arrangement and prevents shared sources from evolving independently.

## Parser isolation proof

`usage-source-psi` keeps `kotlin-compiler-embeddable` as `compileOnly`. `:server` resolves the parser
only through `composePreviewUsagePsi`, and passes those jars to tests and the distribution as the
`lib-usage-psi` sidecar. `checkServeModuleBoundary` and `checkServerJvmFloor` continue to inspect the
server runtime; parser tests must also assert that the reflective parser actually loaded so a
missing sidecar cannot turn into a silent text-parser fallback.
