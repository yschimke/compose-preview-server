# Remote Compose feasibility proof

Disposable experiment for the [completeness review](../../docs/design/UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md).
This does not add features to the production UI builder. Review the evidence before committing to
production implementation. No upstream commits or releases are needed to run it.

## Run

From this directory, using the repository's Gradle wrapper and a Java 21 installation:

```sh
python3 generate.py
../../gradlew jvmTest compileDocument wasmJsBrowserDistribution writeRuntimeClasspath
python3 serve.py
```

In another terminal, `python3 verify_api.py` checks the browser API and local MCP tool against the
same model, including byte-identical artifacts and matching validation failures.

Open <http://127.0.0.1:8765>. On macOS the host locates Java 21 with `java_home`; elsewhere set
`POC_JAVA_HOME` to a Java 21 installation. The default `JAVA_HOME` may still be Java 17 for the
production repository's build.

## What this proves

`model.json` is a deliberately small semantic fixture, not a proposed public schema. `generate.py`
lowers the same model to AndroidX authoring JSON and self-contained regular Compose Kotlin. The
exact generated Kotlin is compiled into the proof and exercised by interaction tests.

- A StateLayout links to a named integer value, with two explicit branches and container modifiers.
- Clicking runs an integer expression change followed by a named host callback. The same bytes
  switch Off → On → Off without a compiler call between clicks.
- A real canvas LoopOperation reads an index expression to draw four circles.
- Two reusable Badge instances become calls to a generated `@Composable` function in Kotlin;
  their bodies are expanded into JSON from the shared definition.
- AndroidX creation-core compiles JSON directly, with no Compose compiler or Android runtime in
  the document-assembly step.
- The actual `rc-player-compose` plays those bytes on JVM and CMP/Wasm.
- The local website accepts edited models and downloads authoring JSON, binary `.rc`, and Kotlin.
  It uses exactly the same `/compile-model` API available to an automation client.
- The model validator rejects unsupported inputs with a field location, for example
  `repeat.count: expected an integer in 1..12`.

The browser's Compose pane is the compiled **initial fixture**; it is labeled accordingly. Model
edits update the remote preview and both source exports without rebuilding the Wasm application.
Updating the already compiled Compose pane would require an interpreter or a compiler lane and is
not claimed here. The same compiler is also exposed as `compile_interface` at the local `/mcp` endpoint. The
bounded input schema is returned by `tools/list`; `initialize` and `tools/call` were smoke-tested.
This is an isolated stateless MCP proof, not integration with the production UI-builder MCP.

## Versions and local dependencies

- Kotlin 2.4.10; CMP 1.11.1; `rc-player-compose` 1.60.1.
- AndroidX `remote-creation-core` / `remote-core` 1.0.0-alpha19.
- Document API 7, profile mask 513 (AndroidX + experimental).
- Generation uses explicit captured pixel dimensions. The browser supplies
  `X-Preview-Density`; automation defaults to density 1. Save/export for the target display density.
  Tests exercise both density 1 and 2. Font scale is 1 in this proof.

The isolated settings file supports `-PlocalRcPlayers=/absolute/path/to/rc-players` when an
unpublished player change is needed. It substitutes only the named player modules. The original
fixture succeeded with released dependencies; the empty-Box selection proof below verifies this
local player option. No contracts extension was needed for this experiment.

## Evidence

`jvmTest` verifies deterministic assembly, player support reporting, empty-input rejection, remote
state switching plus host callbacks, generated Compose switching, and a density-2 render/click.
It writes PNGs under `build/evidence/`; the review copy is under
`docs/design/evidence/remote-compose-completeness/`.

The browser was tested separately because a density-1 desktop screenshot is insufficient proof
of Wasm behavior on a Retina display. The API measurement is roughly 200 ms for this tiny fixture,
including a fresh local JVM process; it is not a production latency benchmark.

## Findings retained by the proof

`pattern-probe.remote.json` retains the first pattern-based fixture. With the pinned released
assembler/player it produces a container mismatch and implicit box-alignment support issues.
The final fixture uses explicit alignment and expands reusable component bodies. This does not
establish whether the remaining pattern defect belongs in the assembler, decoder or linker;
triage it before counting native pattern support.

Empty layout boxes also require an explicit content shape in the tested player path. The spacer
helper gives its box an empty text child. Production support should reconcile the legal empty-box
form with the player rather than carrying this workaround indefinitely.

Density-dependent font expressions did not produce a usable frame in this tested combination.
The successful fixture uses explicit generation density for text, drawing coordinates and layout
dimensions. API 8 density flags alone did not establish cross-density correctness. These are
reasons to test semantic fields as well as opcode support.

## What remains deliberately unproven

Arbitrary Remote Material catalog lowering, Remote Kotlin compilation of the new constructs,
native pattern/macro reuse, data-driven layout repetition and lazy keys, component-local state,
general expression coverage, production MCP integration, actual app host callbacks, assets/fonts
beyond this fixture, accessibility parity, transition/retention polish, and 80% operation coverage.
The existing production Kotlin emitter remains available; this experiment does not replace it.

The schema, generator and HTTP host are disposable proof code. They are not a general-purpose
compiler, a production service, or permission to label a generated interface production-ready.

## Integer selection and empty JSON Boxes

`IntegerSelectionJsonTest` extends the feasibility proof to authored values that are not physical
child indexes. It checks 10/20/fallback, adjacent integers above Float's exact range, both signed
Int limits, and 13 cases. Changes reach the real player through named state without recompilation.

The published AndroidX alpha19 parser accepts only integer variable names for `stateLayout.indexId`.
It has no way to declare a derived integer expression. The local `IntegerExpressions` adapter uses
its component registry to add an experimental `integerExpression` node, materializing bounded
expressions and recording their aliases. This is **not stock alpha19 JSON**: the test requires the
unmodified parser to reject the extension. Production export must expose a supported parser profile
or upstream this support before advertising these documents as portable authoring JSON.

Comparing two quotient/remainder halves preserves all 32 bits using the parser's integer arithmetic
vocabulary. The probe never converts an integer selector to a float. Expressions sit inside the
layout-bearing Box so updates are evaluated by the normal layout/player path.

The probe also found that AndroidX emits empty Boxes without `LayoutComponentContent`. The CMP
player previously rejected them. [rc-players#92](https://github.com/yschimke/rc-players/pull/92) fixes
this in the owning player. Use its local checkout to run the
empty-branch proof without waiting for a release:

```shell
./gradlew -p experiments/remote-compose-poc \
  -PlocalRcPlayers=/path/to/rc-players \
  jvmTest --tests '*IntegerSelectionJsonTest'
```

The existing application consumes the same fix using the normal local dependency manifest:

```shell
python3 scripts/stage-local-dependency.py --checkout /path/to/rc-players \
  --module :rc-player-trace --module :rc-player-protocol \
  --module :rc-player-runtime --module :rc-player-compose \
  --output build/local-dependencies/empty-box-player
./gradlew -PlocalDependencies=build/local-dependencies/empty-box-player/local-dependencies.properties \
  :ui-builder:jvmTest :ui-builder:compileKotlinWasmJs
```

The adapter is confined to this experiment. The production builder has not yet adopted a new JSON
dialect, and this proof is not counted as completed JSON export or live document assembly.

## Verify the shared compiler publication

The owning `compose-ai-tools:remotecompose-json` module has an explicit
`compose-preview-integer-expressions-v1` authoring profile in
[compose-ai-tools#5396](https://github.com/yschimke/compose-ai-tools/pull/5396). It adds named integer
expressions through AndroidX's component registry and validates references, expression syntax and
the 32-slot integer operation limit. Documents declare this extension in top-level `compilerProfile`;
it is separate from the binary header's API level and profile mask.

Stage that module and test its publication without a release:

```shell
python3 scripts/stage-local-dependency.py --checkout /path/to/compose-ai-tools \
  --module :remotecompose-json --output build/local-dependencies/json-compiler
./gradlew -p experiments/remote-compose-poc \
  -PlocalRcPlayers=/path/to/rc-players \
  -PlocalJsonCompilerManifest="$PWD/build/local-dependencies/json-compiler/local-dependencies.properties" \
  jvmTest
```

With this manifest, the selection scenarios call the shared compiler's public `compile(String)`
API with the profile declaration. They bypass the experimental Java adapter entirely. All ten
proof tests pass, including exact integer selection at both Int limits and above Float's exact
range. The shared compiler itself has 28 JVM tests and passes its ABI checks. Without the manifest,
the proof retains its original isolated adapter while the production publication is unreleased.

The existing server also consumes this same artifact with its normal `-PlocalDependencies` option.
This establishes the supported compiler path; wiring design export and live previews to it in the
existing editor and MCP is still required.

## Play actual design-to-JSON exports

`RemoteDocumentJsonExporter` in the shared production `ui-builder-export` module now lowers
authored layout documents to JSON. Generate the exact fixtures and run them through the shared
compiler and real player:

```shell
./gradlew :ui-builder-export:jvmTest --tests '*RemoteDocumentJsonPlayerFixturesTest'
./gradlew -p experiments/remote-compose-poc \
  -PlocalRcPlayers=/path/to/rc-players \
  -PlocalJsonCompilerManifest="$PWD/build/local-dependencies/json-compiler/local-dependencies.properties" \
  -PproductionJsonDir="$PWD/ui-builder-export/build/remote-json-selection" \
  jvmTest
```

`ProductionJsonExportTest` exercises nine generated scenarios at densities 1 and 2: ordinary and
adjacent large integers, Int extremes, Boolean selection, 13 cases, ordinary decimals, adjacent
Floats, subnormals and opposite finite Float extremes. It verifies the initial
branch, ordered click actions, host-driven fallback and retained padding. The Boolean bridge uses
named integers 0/1; the export result retains the authored state kinds for its host adapter.
The test explicitly skips when no production fixture directory is supplied.

The initial mapping covers Box/Row/Column, spacing and cross-axis alignment, basic ordered
modifiers, non-null integer/Boolean selection and numeric/Boolean state writes. Catalog-specific
components need declared lowering recipes; typography, assets, scoped child alignment, dynamic
dimensions, String/nullable selection and reusable instances remain to be mapped. Mutable
String declarations use the explicit state profile below; a compiler probe retains the stock
parser's equal-initial-text identity limitation.

### Independent mutable text

`MutableStringJsonTest` proves the owning compiler's `compose-preview-state-v1` profile with the
real CMP player: two `Ready` variables and an equal literal stay independent after a click action
and a host update. The shared exporter's exact output also preserves ordered assignments of empty,
Unicode and reference-like strings. Run the production fixture writer first, then:

```shell
./gradlew -p experiments/remote-compose-poc \
  -PlocalJsonCompilerManifest="$PWD/build/local-dependencies/remote-export/local-dependencies.properties" \
  -PlocalRcPlayers=/path/to/local/rc-players \
  -PproductionStringJsonDir="$PWD/ui-builder-export/build/remote-json-strings" \
  jvmTest --tests '*MutableStringJsonTest'
```

This proof does not add String equality or catalog typography recipes.

### Decimal selection and shared numeric IDs

`FloatSelectionJsonTest` follows creation-compose's exact equality lowering: a Float comparison
produces 0/1, an integer expression reads that result by ID, and StateLayout consumes the resulting
case ordinal. An isolated `floatEquals` parser adapter makes this a feasibility test rather than
a new advertised production JSON construct.

The test exposed a CMP player defect: Float writes populated only the Float namespace, while
AndroidX `RemoteComposeState.updateFloat` also publishes the truncated integer view. That left
integer comparison inputs at zero and selected the fallback even when the decimal case matched.
The local player correction publishes both numeric views for expressions, named values, actions,
constants and measurements, while retaining exact authored integer values.

The four rendering probes cover ordinary decimals, adjacent Floats, subnormal values and opposite
finite extremes. Each selects both cases, falls back, then returns to the first case without
recompilation. A fifth test compares numeric writes directly with the actual AndroidX alpha19
`RemoteComposeState`, including conversion boundaries and non-finite runtime values.

```shell
./gradlew -p experiments/remote-compose-poc \
  -PlocalRcPlayers=/path/to/local/rc-players \
  jvmTest --tests '*FloatSelectionJsonTest'
```

The proof writes JSON, compiled documents and PNGs to `build/evidence/float-selection`.
The shared compiler now supplies the production `floatEquals` declaration under its state profile.
`ProductionJsonExportTest` exercises exact generated documents through that publication; it does
not use this experimental adapter.

The shared exporter is now connected to browser downloads, revision-pinned service export, live
WASM playback and MCP. See the implementation tracker for current evidence and remaining coverage.
