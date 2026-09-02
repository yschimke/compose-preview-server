# The UI builder on a derived component record

Status: **plan** (2026-09). The server-side half of
[`compose-ai-tools` → `docs/design/COMPONENT_RECORD.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/COMPONENT_RECORD.md),
which carries the root-cause analysis, the record's shape, the override format
and the conformance ladder. This document covers only what this repository owns:
where the builder's catalog comes from, how a node renders, how an export is
proven, and what has to change in `:ui-builder`, `:ui-builder-runtime` and
`:server`.

## The problem this repository owns

The builder describes one catalog and renders another.

* **One capability catalog, hand-transcribed.**
  `docs/design/fixtures/ui-builder/jetcaster-discover-capabilities-v1.json` is 25
  components transcribed by hand from a single screen of
  `android/compose-samples`. `ui-builder-runtime/build.gradle.kts` copies it into
  a resource named `m3-catalog-v1.json`. It has no relationship to
  `yschimke/m3-catalog` — the 59-component Material 3 reference catalog served on
  the same host under the same name.
* **Restated twice, by hand.** `UiBuilderRenderer.kt` (1,554 lines) re-implements
  each component as a `when (componentId)` branch; `CapabilityComposeCodeExporter.kt`
  (1,232 lines) prints Kotlin from a second `when (componentId)`.
* **The three already disagree**, and nothing detects it:

  | | ids |
  |---|---|
  | renderer implements, catalog does not declare | `m3/center-aligned-top-app-bar`, `m3/list-item`, `m3/primary-tab-row`, `m3/tab`, `shape/colour-dot` |
  | exporter prints, catalog does not declare | the same five |
  | catalog declares, exporter cannot print | `remote-compose/document` |

* **Slots are declared in one place and implemented in another.**
  `SlotCapability` (`name`, `cardinality`, `ordered`, `acceptedRoles`,
  `acceptedTraits`) is validated against JSON; what a slot *does* is whatever its
  renderer branch does. They are never compared, which is why "do slots really
  work" has no answer today. A slot is not derived from a `@Composable` lambda
  parameter of a real function, because there is no real function.
* **Export is unverified and says so.** Every `RevisionPinnedComposeExportExecutor`
  artifact carries `ALMOST_COMPILING_PROJECTION`. Nothing compiles the output —
  while `PlaygroundCompileService`, in this same server, already stages Kotlin,
  compiles it against a catalog's resolved classpath, discovers its previews,
  renders a first frame and mints a live session.

## The change

**1. Generate the capability catalog.** `ComponentCapability` becomes a
projection of `components.json` rather than an authored file:

| capability field | derived from |
|---|---|
| `componentId` | the catalog identity |
| `properties` | non-slot parameters; `jsonType` from the Kotlin type, `allowedValues` from an enum's constants |
| `PropertyEditorControl` | the parameter's type, replacing `CapabilityCatalogParser.EDITOR_OVERRIDES` |
| `slots` | `@Composable` lambda parameters; cardinality from nullability/defaults, acceptance from the receiver scope |
| `code.symbol` / `code.imports` | the symbol FQN |
| `wasm.adapterStatus` / `svg.status` | the conformance tier the component proved |

`CapabilityValidator` keeps its job unchanged — it just validates against a table
nobody typed.

**2. Two render tiers, one input.**

* **Daemon/server tier (authoritative).** A node renders by resolving its
  `ComposableMethod` from the catalog bundle's classpath and invoking it with
  arguments built from the node's properties and `@Composable` lambdas built from
  its slot children. `compose-ai-tools`' `RenderEngine` already invokes
  composables reflectively *and* already passes a `@Composable () -> Unit` as a
  reflective argument (`InvokeWithOptionalWrapper` hands the preview body to a
  wrapper exactly that way), so the mechanism is proven; what is new is the
  argument mapping and the slot-lambda construction.
* **Wasm tier.** The in-browser renderer cannot reflectively invoke an arbitrary
  jar, so it needs a compiled-in set. The fix is not to keep hand-writing it:
  **generate** `UiBuilderRenderer`'s and `CapabilityComposeCodeExporter`'s
  dispatch from the record, check the generated sources in, and fail CI on a
  diff. The three-way drift above then cannot recur.

**3. Export through the oracle.** A Compose export is not returned until it has
been compiled (`PlaygroundCompileService`), rendered, and pixel-compared against
the builder's own render of that revision. Either the round trip passes or the
export returns the failure. `ALMOST_COMPILING_PROJECTION` is deleted rather than
reworded.

**4. Admission is the conformance ladder, not the allowlist.** Today
`--ui-builder-catalogs` admits `m3-catalog` and `remote-m3` by name. It becomes a
consequence: a catalog's components enter the builder only at Tier 3 — every
parameter constructible-or-defaulted from the wire, every slot a `@Composable`
lambda, argument-deterministic, parent-agnostic, and the document → Kotlin →
compile → render → pixel-compare round trip green. A typical app's
`SpeakerItemView(speaker: SpeakerDetails, …)` fails at the first check, which is
the intended answer.

**5. Remote Compose keeps its own lane.** `remote-compose/document` embeds a
nested *player*, not a composable, and its document-authored slot names
(registered as custom-component configs, reached through the inferred
`DynamicSlots` trait — see [UI_BUILDER_REMOTE_COMPOSE.md](UI_BUILDER_REMOTE_COMPOSE.md))
are a second component model wearing the first one's clothes. Do not force it
into the record: keep it an explicitly typed embed node with its `namedValues`
boundary, keep it out of the Compose exporter as it already is, and let
`remote-m3` reach Tier 2 through a Remote Compose *creation-DSL* generator rather
than the Compose one.

## Sequence

Phases 0–3 of the upstream plan (the PSI measurement, `components.json`, the
parameter override format, generation by printing) land in `compose-ai-tools`
first; nothing here can be built on a record that does not exist. Then, here:

1. **Now, independent of everything:** add a `catalog ↔ renderer ↔ exporter`
   component-id diff as a test, and fix the seven ids above. Rename
   `m3-catalog-v1.json` to what it is — a Jetcaster transcription — so two
   unrelated things stop sharing a name on one host.
2. Generate the capability catalog from a published `components.json`; keep the
   hand-written file as a golden until the generated one matches it.
3. Generate the Wasm renderer/exporter dispatch; CI-diff the checked-in output.
4. Reflective invocation on the daemon tier, behind a flag, proven against the
   Wasm tier's pixels.
5. Export through the oracle; drop `ALMOST_COMPILING_PROJECTION`.
6. Tier 3 admission replaces the `--ui-builder-catalogs` allowlist.

## Success criteria

* The renderer, the exporter and the catalog cannot disagree, because two of the
  three are generated and CI diffs them.
* Every Compose export the server hands a user has been compiled and rendered.
* A component's slots are its `@Composable` lambda parameters, so "do slots work"
  is answered by the type system rather than by two tables agreeing.
* Confetti — a typical app — reaches the preview browser and the API panel and
  **no** UI-builder component. If it reaches Tier 3, the gate is too loose.
