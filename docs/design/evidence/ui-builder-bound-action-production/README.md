# Production Remote Kotlin callbacks with row values

The production `RemoteContentEmitter` now emits the action-factory form established by the
[compiled prototype](../ui-builder-bound-action-proof/README.md). The semantic document is unchanged:
three rows pass their IDs through two reusable components with differently named arguments.
Each component supplies its own value to a typed factory; the caller retains the mutable state target.
The authored tree remains hierarchical, with one function definition per reusable component.

[The exact production source](RemoteRepeatedContent.kt.txt) includes the small `RemoteRepeatedContent`
entry point used by the Android test. The exporter test writes this file directly; no prototype,
source rewriting or hand-written implementation replaces its body. The existing Code pane and the
revision-pinned server export are separately checked against the same public export seam. The server
check also verifies the design/revision provenance header.

## Compiled evidence

AndroidX creation-compose alpha18 compiles the production file and captures real Remote documents.
The Android player passes all eight out-of-order select/reset clicks at densities 1 and 2: 16 physical
clicks. Each initial frame matches every pixel of the independent JSON/player reference. The captured
RC documents and every checked frame are adjacent, with hashes in [verification.json](verification.json).

Before interaction and after selecting row 2:

![Initial production Remote frame](remote-initial-2.png)
![Production frame after row 2 selects its own ID](remote-0-row-2-select-2.png)

Additional production sources for [Int](intActions.kt.txt), [Float](floatActions.kt.txt),
[Boolean](boolActions.kt.txt) and [String](stringActions.kt.txt) factories compile in the same Android
build. The interaction test covers Int row values; the other scalar variants establish compilation,
not complete player interaction coverage. Exporter tests also cover direct loop handlers, mixed
literal/bound action ordering, missing arguments, incompatible types and out-of-scope reads.

The consumer checks pass all 87 shared export tests, 955 editor tests (one separate opt-in
proof skipped), and 11 service export tests. WASM and server compilation also pass.

## Reproduce

Use the explicit local dependency manifest documented in
[local development](../../../development/LOCAL_DEPENDENCIES.md). Then run:

```sh
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder-export:jvmTest --tests '*RemoteScopedSourceExportTest' --rerun
ANDROID_HOME=/path/to/android-sdk ./gradlew -p experiments/remote-state-selection \
  -PboundActionProductionProof=true testDebugUnitTest --tests '*BoundActionSourceProofTest' --rerun
```

The Android project also needs the original experiment's generated source prerequisites described
in the [prototype reproduction instructions](../ui-builder-bound-action-proof/README.md#reproduce).
The new flag chooses the production-generated sources and the same Android player test. It leaves
the prototype generator and its separate source directory untouched. Without either proof flag,
the additional callback proof sources/tests are excluded. The flag gates the compile proof, not the
production exporter.

## Integration still required

This is the production Remote Kotlin export chunk. It does not enable new binding controls or
establish that the catalog validator accepts this fixture through an MCP mutation. Shared ordinary
Compose typed callbacks, preview action-value substitution, authoritative type/scope validation,
Remote JSON lowering and the existing behavior inspector/MCP authoring metadata remain to be wired.
The compiler proof does not claim full Remote Compose operation coverage or production readiness of
the complete authoring flow.
