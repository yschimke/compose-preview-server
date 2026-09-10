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

## Remaining production work

- Extend the shared record-driven generator to nullable state, comparisons and parameter-aware
  callbacks, preserving the same meaning in the code pane, server preview and export.
- Implement state selection with named cases, normal modifiers and state bindings; lower it to
  Remote StateLayout and Compose `when`. Transitions and inactive-branch retention can follow.
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

Local dependency overrides are authorized for this project. Introduce them when a contract or
shared generator extension requires one, and verify that development works without publishing
another repository first. Released builds should continue using pinned coordinates by default.
