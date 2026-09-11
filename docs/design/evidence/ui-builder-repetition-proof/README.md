# Repetition and reusable component feasibility proof

This records the independent static-expansion proof at commit `e44c52ae`, before production
integration. The [subsequent integration](../ui-builder-repetition-export/README.md) now accepts the
authored loop in `RemoteDocumentJsonExporter` and verifies browser/MCP edits and exports. The
test-only expansion remains an independent oracle; production emission must match it exactly.

The semantic document defines one `layout/for-each`, three authored row dictionaries and one
reusable `Pair` component. A row's `gap` argument becomes the component's `spacing` parameter,
which controls its Row arrangement. Each placement retains its own padding. The body contains
two clickable boxes that update the screen's shared integer state; a selection below the loop
shows that state. The authored document retains the loop, template and component definition.
Expansion exists only in the test's temporary export representation.

## Evidence

- The unmodified editor canvas renders the authored document and the expanded document identically,
  comparing every pixel at densities 1 and 2.
- The shared production JSON exporter accepts the expanded layouts. The locally staged public
  JSON compiler generates standard RC bytes deterministically, with no new operation or player
  implementation.
- The real CMP Remote Compose player renders those bytes. Its initial frame matches the editor
  canvas pixel for pixel at both densities: 12,000 and 48,000 pixels respectively.
- All six cells are clicked at each density. Every green cell selects the second state; every red
  cell restores the first. This checks each copy's physical hit region, not just the last copy.
- Separate prototype checks reject a missing argument and recursive component placement.

The density-2 captures below are actual renders of the existing editor canvas and the compiled
Remote document, not browser screenshots or mockups. Their PNG bytes are identical:
`646f885c41de79ac2f995ce45835ac2a893e0708bc4df5165ccd492586ea6f7a`.
Density-1 PNG SHA-256 is `5f8784b04cb41432afc66fcde5068056de2a5adea91a5aead8e806e920f1fde9`.

| Existing editor canvas | Compiled Remote document |
| --- | --- |
| ![Authored loop and component](canvas-2.png) | ![Expanded Remote document](rows-2.png) |

Each density includes the authored semantic document, temporary expanded document, authoring JSON,
binary RC document, operation dump and both PNGs. The initial state indicator is the red rectangle
below the three pairs.

## Reproduce

From the repository root, using the same explicit local dependency manifest as the decimal proof:

```sh
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder-export:jvmTest --tests '*RemoteJsonRepetitionProofTest'
./gradlew -p experiments/remote-compose-poc \
  -PlocalJsonCompilerManifest="$PWD/build/local-dependencies/remote-export/local-dependencies.properties" \
  -PlocalRcPlayers=/tmp/ui-builder-local-deps/rc-players \
  -PrepetitionProofDir="$PWD/ui-builder-export/build/remote-json-repetition" \
  jvmTest --tests '*RepetitionJsonProofTest'
VERIFY_REMOTE_REPETITION=true ./gradlew \
  -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder:jvmTest --tests '*RemoteRepetitionCanvasProofTest' --rerun
```

The last check requires the player capture from the preceding step. The opt-in is deliberate:
ordinary editor tests do not depend on another build's generated files. The prototype expansion
lives only in `RemoteJsonRepetitionProofTest`, not in a product API.

## What the next integration must preserve

Static expansion is feasible for authored data; it is not a claim of runtime list support, lazy
recycling, pattern opcodes, independent per-instance mutable state or callable slot parameters.
The prototype's recursive substitution is not a specification of every bindable field. A shared
production pass must validate binding locations and types, preserve wrapper modifier order and
selection references, reject unsupported authored fields, and bound expansion before emitting bytes.

The initial fixture exercised shared-state actions in a reusable body directly through the canvas
and lower-level exporter. The subsequent integration covers the UI/server/MCP authoring gates for
JSON/RC/PNG. Kotlin generation still needs explicit parameter/callback scope for these bodies.

Physical clicks on all copies are proven here. The existing inspection/action wire fields still
name authored nodes; addressing a particular instance over MCP requires its own change and tests.
The record-driven Kotlin exporter also still needs its loop and reusable-call mapping. These
requirements remain in the implementation tracker rather than being dropped after this proof.
