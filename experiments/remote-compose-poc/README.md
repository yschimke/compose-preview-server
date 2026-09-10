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
unpublished player change is needed. It substitutes only the named player modules. That option
has not been exercised: this proof succeeded with released dependencies. No contracts extension
was needed. If the next experiment needs one, add an explicit local contracts path here rather
than requiring a publish or modifying the production build's defaults.

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
