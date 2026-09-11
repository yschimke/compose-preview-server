# Remote Compose implementation progress

The scope and operation census are in
[the completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md).
The runnable proof is in [experiments/remote-compose-poc](../../experiments/remote-compose-poc/README.md).
This document tracks production implementation; it does not redefine completion around the proof.

## Authoring boundary

The visual editor focuses on layouts. Its tree remains a semantic hierarchy of components, layouts,
state and actions; it is not the flat Remote Compose operation stream. Richer authoring can live in
MCP without requiring a visual control for every operation. Precise lowering belongs in the export
layer. Common concepts keep their Compose mappings, including selection as `when`.

## JSON and binary export delivery

The existing service, WASM Export menu and MCP now route Remote document exports through the shared
`RemoteDocumentJsonExporter`. The JSON source is UTF-8; `.rc` is compiled by the existing offline
`remotecompose-json` publication and travels as base64 in the protocol. Both are revision-pinned and
content-digested. HTTP downloads return diagnostics as 422 responses, rather than offering a partial
or empty document as a successful file.

[Contracts PR #63](https://github.com/yschimke/compose-preview-contracts/pull/63) declares `json` / `rc`
and independently advertised `remoteJson` / `remoteDocument` flags. The host advertises them only for
catalogs declaring the Remote Compose platform. The binary flag also requires a successful compiler
profile probe. The temporary strict-serialization bridge keeps builds on released contracts usable;
without local contracts the new formats are unadvertised. Remove that bridge when the release is
pinned. Local contracts and the compiler can be staged into one manifest:

```shell
python3 scripts/stage-local-dependency.py --checkout /path/to/contracts --module :ui-builder-protocol --output build/local-dependencies/remote-export
python3 scripts/stage-local-dependency.py --checkout /path/to/compose-ai-tools --module :remotecompose-json --output build/local-dependencies/remote-export
VERIFY_REMOTE_DOCUMENT_EXPORTS=true ./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties :server:test --tests '*RemoteDocumentExportExecutorTest' --tests '*ServeUiBuilderRoutesTest' :mcp:test --tests '*UiBuilderMcpAdapterTest' :ui-builder:jvmTest --tests '*EditorExportMenuTest' :ui-builder:wasmFrontendDist :server:installDist
```

The hosted `ui_builder_export` accepts these formats. Standalone MCP adds `export_design`, with an
explicit revision and a format enum derived from its installed contracts; its existing source and
image tools remain available. Catalog capabilities determine which declared formats a host supports.

The staged checks pass 32 targeted tests, including a real persistent-service HTTP proof. The existing
WASM app's actual menu downloads a 757-byte document; the revision-pinned HTTP endpoint and hosted MCP
return identical bytes and SHA-256. The browser and hosted MCP also agree on the 1,659-byte JSON
source. [Browser evidence and measurements](evidence/ui-builder-document-exports/README.md)
record the sample and the distinction between delivery and preview fidelity. The released-floor
runtime suite, ABI, 46 shared-export tests, targeted HTTP/MCP/menu checks and Wasm compilation also
pass; new-format-only tests skip explicitly without staged contracts. Golden regeneration changes
no committed fixtures.

This completes the delivery path for the current lowering subset, not Remote Compose completeness.
Unsaved/local document compilation, broader component recipes and modifier mappings, independent
String state, nullable/computed values, callbacks, loop/reusable fidelity, and the operation coverage
remaining in the review are still required.

## Live document preview

For saved designs whose host advertises RC export, the existing Preview button now loads the exact
revision's compiled document into the existing CMP/WASM player. Design mode keeps the semantic
authoring canvas. The host waits for the expected design to match its authoritative snapshot;
revision/generation changes cancel and refresh the preview. Compilation and player-support errors
are shown in place, without substituting the semantic renderer for a refused export.

[Actual browser playback and MCP evidence](evidence/ui-builder-live-document-preview/README.md)
shows clicks advancing through both cases and the fallback, then a live MCP edit updating the open
Preview to revision 1. Browser network bytes and hosted MCP exports agree at both revisions. The
full staged editor suite passes 943 tests and the WASM build passes. The six new tests cover
playback/actions at densities 1 and 2, pending saves, wrong revisions, late responses, and the
additional interactive pane. The released dependency floor compiles; its known empty-Box player
limitation skips the two density variants of that fixture until the local player fix is selected,
and the four lifecycle/routing tests pass. Staged validation can require playback with
`VERIFY_REMOTE_DOCUMENT_PREVIEW=true`.

## Authoring state and actions

The editing canvas now measures natural content height and then lays out that same composition
within a finite extent. This fixes fill-only selected branches collapsing under unbounded measurement,
while preserving long content and remeasuring on document or preview-state changes. The saved export
sample now draws its 312 × 312 dp selected child inside 24 dp padding in the actual WASM app.
[Before/after browser evidence](evidence/ui-builder-canvas-fill/README.md) includes measured bounds;
four new rendering/interaction tests and the full 937-test UI-builder JVM suite pass.

The browser's Screen panel can add, edit and remove state declarations. The Layer inspector can
add, edit, reorder and remove actions on existing controls. Both send the released
`setStateVariable`, `removeStateVariable` and `setEventBinding` mutations that MCP uses. Local
sessions translate those same mutations, including undo and redo. Removal or narrowing of state
still in use is rejected. Editing a declaration refreshes that variable in the preview while
preserving unrelated interaction state.

Actions execute in order, with each action observing prior writes. Remote Kotlin export uses
AndroidX `combinedAction` to preserve lists rather than refusing all multi-action handlers.
The existing restrictions on nullable Remote values remain explicit.

Evidence from actual Compose controls, before and after wiring a flag to a button:

- [Before wiring](evidence/ui-builder-behavior-authoring/before-wiring.png)
- [After wiring](evidence/ui-builder-behavior-authoring/after-wiring.png)

These are interaction-test captures of the production inspectors, not screenshots of a released
site. `BehaviorInspectorTest` drives the controls through the editor reducer. The full builder JVM
suite and `ServeUiBuilderMcpIntegrationTest` cover browser/local collaboration and actual MCP
requests respectively; the browser Wasm target also compiles.

## Layout click export

The shared Compose projection maps clicks on Box, Row and Column to `Modifier.clickable`, after
these layouts' authored modifier chains. Controls keep their declared event parameters. The
nested callback uses the shared generator's `ScreenValue.ActionLambda`, with the same validation
and ordered state writes as ordinary handlers. Local generator publications make this path
available before a release; the released floor reports a located refusal for the missing shape.

The exact emitted Kotlin is compiled by `GeneratedLayoutClicksTest`, which clicks both selected
branches and the fallback at densities 1 and 2 and checks the authored padding. The production
gate and hosted MCP tests compare their output with that same source. See
[compiled interaction evidence](evidence/ui-builder-layout-clicks/README.md). The combined run passes 945 editor tests,
46 shared-export tests, 26 targeted server/MCP tests, runtime ABI checks and the WASM build.
The actual existing browser Code pane and live MCP export are also verified; the released floor
passes compilation and its explicit-refusal/compiled-fixture tests. Golden regeneration changes no
existing fixtures.

The combined local profile includes contracts, both generator publications, the JSON compiler and
the player. A real hosted MCP comparison also exposed a separate remaining gap: a plain layout
root in a Remote catalog does not yet route to Remote Kotlin export; the existing dedicated route
requires a Wear widget root. That route must be generalized without changing the semantic tree.

## Shared Compose export

The browser code pane and the server's record-driven export now project scalar declarations,
state reads and ordered click handlers into the shared generator. Progress values bound to state
are read inside the generated callback. Declared decimal state accepts integer JSON spellings
such as `0` and `1` without changing the Kotlin type.

Catalog scalar properties now accept state reads of a matching declared type, using one rule shared
by the browser and service. A text property can bind String state, and a Boolean property can bind
a flag or a comparison. Nullable state still needs a property that admits null; colours, assets and
constrained enums retain their own value requirements. The existing canvas resolves these property
reads while preserving the variable references used by two-way inputs.

`BehaviorScreenExportTest` pins the generated source byte for byte against
[`state-actions.kt.txt`](fixtures/ui-builder/state-actions.kt.txt). The builder compiles that exact
file as test source, and `GeneratedStateActionsTest` clicks both the existing builder canvas and the
resulting Material 3 button using the same saved document. It checks the changed label, enabled
state and progress. This is generated code running against real Compose. The MCP integration test
also authors a declaration, binding and ordered handler, then
exports them through the production service.

Null initial values, state comparisons and callbacks that consume an event value still require
extensions to the shared model. Their refusals remain explicit; they are required follow-up work.

## State selection generator

The shared generator extension is implemented in
[compose-ai-tools#5383](https://github.com/yschimke/compose-ai-tools/pull/5383) and is available through
the local dependency workflow. `ScreenNode.selection` selects ordinary child slots with a typed
`when`, preserving authored values such as `10` and `20` and an optional fallback. Every branch is
validated. Selection introduces no layout or receiver scope, so a containing layout's modifiers
remain usable. It follows ordinary Compose composition behavior; transitions and retention of
inactive branches remain later polish.

The proof discovers real Material 3 components, generates Kotlin, compiles it, and clicks from
the initial branch to a second branch and then the fallback. The
[generated source](https://github.com/yschimke/compose-ai-tools/blob/2b16c83bf2a8596d22a37f22f795afb5c49085d9/docs/evidence/screen-selection/SelectedScreen.kt.txt)
and [render evidence](https://github.com/yschimke/compose-ai-tools/tree/2b16c83bf2a8596d22a37f22f795afb5c49085d9/docs/evidence/screen-selection)
are committed with the extension. Its 42 screen-model tests, 574 discovery tests, four real Compose
functional tests and Wasm compilation pass. The existing builder also passes 915 JVM tests, 31
shared-export tests, 16 targeted behavior/MCP tests and Wasm compilation against the locally staged
generator. Those consumer tests verify compatibility; they do not yet prove selection authoring.

The existing Box inspector now has a **Show by state** section. It binds a declared scalar value,
assigns a matching value to each child, and optionally designates one child as the fallback.
The configuration is one typed `showByState` object property, so browser edits and MCP use the
same atomic `setProperty` mutation. Ordinary Box modifiers and child layout scopes remain intact.
Shared validation checks every case, undeclared selectors, unassigned children, duplicate values
(including Float narrowing), and malformed wrappers. The existing canvas draws only the selected
child; an unmatched value without a fallback draws no child. Undo/redo preserves the configuration,
and subtree copies remap case references to the copied children.

The design projection emits the shared generator's structural selection inside the normal Box.
Until the upstream generator is released, opt in to locally staged `screen-model` and
`preview-discovery` artifacts. The released generator floor explicitly refuses the unsupported
selection shape; it cannot silently turn selection into simultaneous children. Remote StateLayout
lowering now supports non-null integer, decimal and Boolean selectors, including an empty branch
when no fallback is authored. String and nullable selectors still require target support.
This is an implementation chunk, not completion of the Remote Compose scope.

`StateSelectionInspectorTest` operates the actual inspector, changes the bound declaration, and
asserts the canvas's selected content; it also verifies undo/redo and remapped subtree copies.
[Before configuration](evidence/ui-builder-state-selection/before.png) and
[configured selector](evidence/ui-builder-state-selection/configured.png) are captures of those
production composables in the interaction harness. They are not a separate editor or a released
website screenshot. `ServeUiBuilderMcpIntegrationTest` discovers the property through the catalog,
authors it through MCP and proves that removing its selector declaration is rejected.
`StateSelectionExportTest` validates the same shape and checks typed cases, fallback and padding
through the actual shared export gate. Run that test with `VERIFY_LOCAL_STATE_SELECTION=true`
and a manifest overriding only the generator pair to require generated code; without the override
it verifies the released floor's explicit refusal.

Verification for this chunk: 931 builder tests, 35 shared-export tests, 188 runtime tests and
15 targeted server/MCP tests pass with released dependencies. The same 15 server/MCP tests pass
with the local generator override. The existing Wasm target compiles, runtime ABI verification
passes, and golden regeneration adds only the Box property to the two synthesized catalogs.


## Remote StateLayout export

The production Remote emitter retains the authored Box and its modifiers, maps case values to
physical StateLayout child indexes, and records a fallback branch even when it must be empty.
Selectors, scalar property reads and action writes share one mutable declaration. Inline Remote
content now emits those declarations as well as the component body.

The [standalone Android proof](../../experiments/remote-state-selection/README.md) compiles exact
production exports against AndroidX alpha18, captures real `.rc` bytes, and exercises state changes
in the native Android player. Its seven scenarios cover integers, booleans, decimals (including adjacent Float values), an empty
fallback, 13 cases, and signed integer limits. This caught the player's integer equality overflow;
the emitter compares two 16-bit halves so the generated condition retains exact Int semantics.
Expressions are materialized per case to bound operand-mask size. Keeping expressions inside the
authored Box also ensures they update during the player's ordinary paint path.

The proof uses host overrides, not synthetic clicks. Transition appearance and inactive-branch
retention are not asserted here. This adds Remote Kotlin lowering to the existing editor/MCP
selection shape; direct JSON, binary export and saved-document playback are covered by the later integration sections above.

Verification: seven real-player scenarios, 39 shared-export tests, 931 existing builder tests,
189 runtime tests, and 15 targeted server/MCP tests pass. The local generator manifest also
passes the export/runtime/server checks after the rebase. Wasm compilation passes. Golden
regeneration changes only the selection capability notes in the three catalog fixtures.
The preview plugin now registers its desktop tasks after evaluation; a separate build fix preserves
the production-only preview filter, with a test inspecting the actual packaged `previews.json`.

## Direct JSON integration findings

The extended JSON proof now preserves authored integer selectors, including values above Float's
exact range and both Int limits. Five checks in `IntegerSelectionJsonTest` verify real player
updates and make the stock parser's limitation explicit. AndroidX alpha19 cannot declare derived
integer expressions through authoring JSON. A small experimental component-registry adapter proves
that bounded integer expressions work, but it is not a production or stock-parser dialect.
The shared compiler profile below establishes that capability for this compiler. These extended
sources still require the named profile and are not portable to the stock AndroidX parser.

The proof also exposed valid AndroidX empty Boxes without `LayoutComponentContent`, which the CMP
player rejected. [rc-players#92](https://github.com/yschimke/rc-players/pull/92) supplies the owning player's fix: it models Box content as nullable, preserves geometry and
modifiers, and rejects stray child layouts. The local player passes 118 runtime tests, 216 Compose
tests, ABI checks and Wasm compilation. The JSON probe passes with genuinely empty branches against
that local build. The existing builder also passes all 931 JVM tests and Wasm compilation against
the staged player stack. Both the experiment's local checkout option and the production staging
workflow avoid waiting for a release. This dependency work supports the existing builder; it adds no editor.

## Shared authoring JSON compiler profile

[compose-ai-tools#5396](https://github.com/yschimke/compose-ai-tools/pull/5396) adds the explicit
`compose-preview-integer-expressions-v1` profile to the existing plain-JVM `remotecompose-json`
module. The document's top-level `compilerProfile` selects it; ordinary authoring JSON retains the
stock AndroidX parser path. The profile emits standard integer-expression operations and validates
integer references, duplicate names, infix syntax, function arity and the 32-slot wire limit.
Unknown profiles fail compilation. This source profile is distinct from the binary header's API
level and feature mask.

The local dependency workflow stages its normal Maven publication. With
`-PlocalJsonCompilerManifest=…`, the real-player proof calls the shared compiler's public API and
bypasses its experimental Java adapter. All ten proof tests pass, including both Int limits,
adjacent integers above Float's exact range, 13 cases, callbacks and density 2. The owning module
passes 28 JVM tests, ABI and boundary checks. The existing server compiles against the local
publication and passes all 103 HTTP routing tests. Golden regeneration makes no changes.

This is the verified compiler seam for design-to-JSON and binary export. The delivery and saved-preview sections above describe its builder/MCP integration. This compiler
profile does not expand the claimed catalog coverage.

## Shared design-to-JSON lowering

`RemoteDocumentJsonExporter` now lives in the existing multiplatform `ui-builder-export` module.
It lowers authored Box/Row/Column trees, their spacing and cross-axis alignment, basic ordered
modifiers, integer/Boolean selection and numeric/Boolean action writes into authoring JSON. The
original Box carries its modifiers and click handler; bounded expressions precede the StateLayout
inside it. Case order follows the authored child order. Boolean state is encoded as named integer
0/1, with its authored kind retained in the export result for the eventual host bridge.

The shared gate returns located refusals for unmapped catalog components and fields instead of
omitting them. The protocol-document entry point checks predicates, accessibility, asset bindings
and token bindings before converting to the editor's document shape, so that conversion cannot
silently drop unsupported semantics. Material components still need catalog-specific recipes or
their real compilation lane.

`RemoteDocumentJsonPlayerFixturesTest` writes exact output from this exporter.
`ProductionJsonExportTest` compiles and plays five scenarios at two densities: ordinary integers,
adjacent integers above Float's exact range, both Int limits, Boolean selection and 13 cases.
It checks real click dispatch with ordered state writes, host-driven fallback and retained padding.
The player supplies its normal click indication; branch assertions identify the resulting color
channel without treating that indication as a change to the authored fill.

A separate compiler probe proves that equal initial String values share a text ID in the stock
parser. Mutable String export is refused until the owning compiler can preserve independent state
and literal IDs. Float selection, nullable state, dynamic dimensions, scoped child alignment,
catalog typography/assets and reusable instances also remain to be mapped.

Verification: 46 shared-export tests and 189 runtime tests pass, the shared module and existing
editor compile for Wasm, and all 12 proof tests pass, including the ten generated-document scenarios.
The delivery and saved-preview sections above describe its editor, service and MCP integration.

## Remaining production work

- Extend the shared record-driven generator to nullable state, comparisons and parameter-aware
  callbacks, preserving the same meaning in the code pane, server preview and export.
- Extend Remote state selection to String and nullable selectors without changing authored
  semantics. Transitions and inactive-branch retention can follow.
- Extend the saved-document preview/export integration to unsaved and local-storage designs.
- Route ordinary layout roots in Remote catalogs to Remote Kotlin generation, retaining the same
  semantic tree used by JSON and binary export.
- Complete loops, reusable component parameters/callbacks and per-instance addressing through all
  preview and export lanes.
- Extend typed values, expressions, action sequences, host events and modifier bindings using
  shared capability metadata; surface detailed controls only when needed.
- Map catalog-specific components through declared lowering recipes or their real compilation
  lane. Do not substitute lookalikes for unsupported library components.
- Expose the same discoverable operations, located diagnostics, validation and exports over MCP.
- Verify coverage against the pinned operation census, including operands and target limitations;
  rendering one fixture or accepting an opcode is not an 80% implementation claim.

## Local dependency development

[`stage-local-dependency.py`](../../scripts/stage-local-dependency.py) builds selected modules in
their owning checkouts and stages their normal JVM and Wasm publications. An explicit
`-PlocalDependencies=…` manifest selects them for the existing builder and server. Shared generator
publications are staged together, because both `screen-model` and `preview-discovery` carry its
classes. Released builds continue using pinned coordinates by default. Usage is in
[the local build guide](../development/LOCAL_DEPENDENCIES.md).

Verified by compiling local `ui-builder-protocol` JVM/Wasm publications and both shared generator
publications, then running 915 builder JVM tests, 31 shared-export tests and 16 targeted server
behavior/MCP tests against them. Wasm compilation and both resolved-classpath boundary checks
also pass. Gradle dependency reports confirm the selected snapshot versions; omitting the manifest
restores `ui-builder-protocol:2.15.0` and `preview-discovery:2.7.0`.

The first contracts probe used the then-current 2.15.0 floor. Rebasing onto main at `9f1277d5`
brought in contracts 2.16.0 and the declaration/removal mutation integration. Selection verification
now stages only the shared generator pair, retaining the released contracts 2.16.0 dependency;
local overrides must not downgrade contracts to the older probe snapshot.
