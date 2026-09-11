# Remote Compose implementation progress

The scope and operation census are in
[the completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md).
The runnable proof is in [experiments/remote-compose-poc](../../experiments/remote-compose-poc/README.md).
This document tracks production implementation; it does not redefine completion around the proof.

## Authoring state and actions

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
selection shape; production direct JSON and binary export still require the work below.

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

This is the verified compiler seam for design-to-JSON and binary export. The existing builder and
MCP still need to generate those documents and use them for live preview; this compiler PR alone
does not complete that integration or expand the claimed catalog coverage.

## Remaining production work

- Extend the shared record-driven generator to nullable state, comparisons and parameter-aware
  callbacks, preserving the same meaning in the code pane, server preview and export.
- Extend Remote state selection to String and nullable selectors without changing authored
  semantics. Transitions and inactive-branch retention can follow.
- Bring the proven direct JSON assembly and real player path into production preview, JSON and
  `.rc` exports, with revision and target-profile checks.
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
