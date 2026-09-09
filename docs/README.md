# Documentation index

The repository's own instructions are [`AGENTS.md`](../AGENTS.md) (CI-enforced invariants, PR
workflow) and [`README.md`](../README.md) (what this is, the module table, build commands). This
page indexes everything under `docs/`.

Nothing here was reachable from a link before: seven of these files were not mentioned by any
tracked file in the repository, which is not the same as unused — `UI_BUILDER_COLLABORATION_SOAK.md`
describes a test that runs on every build, and `UI_BUILDER_PROTOCOL_CLIENT.md` describes a module
boundary that is compiled twice. An index is the cheap way to stop that recurring.

## Guides

- [UI_BUILDER_GETTING_STARTED.md](UI_BUILDER_GETTING_STARTED.md) — how to run the UI builder and
  what each surface does.
- [VERSIONING.md](VERSIONING.md) — what a version number here promises.

## Wire contracts

- [serve/SESSION-VIEWER-PROTOCOL.md](serve/SESSION-VIEWER-PROTOCOL.md) — **normative**: the
  `composeai-stream/1` wire contract between `compose-preview serve` and a session-viewer client.
  Cited from `compose-ai-tools`, so it is a cross-repository contract rather than an internal note.

## The UI builder

The product spec and its RFC come first; everything after them is one surface or one seam.

- [design/UI_BUILDER_PRODUCT_SPEC.md](design/UI_BUILDER_PRODUCT_SPEC.md) — **spec**: the product
  surface, its benchmarks and its release gate.
- [design/UI_BUILDER_WAVE0.md](design/UI_BUILDER_WAVE0.md) — **RFC**: benchmark decomposition and
  the v1 contract. Remaining release gates are listed in its §10.
- [design/UI_BUILDER_PROJECT_BOUNDARY.md](design/UI_BUILDER_PROJECT_BOUNDARY.md) — **normative**:
  which module owns the service port, persistent state, catalog validation and export
  orchestration, and what it deliberately does not own. Read this before changing a module boundary.
- [design/UI_BUILDER_CATALOG_CONTRACT.md](design/UI_BUILDER_CATALOG_CONTRACT.md) — what a catalog
  repository authors so the builder can serve it without being written into the server.
- [design/UI_BUILDER_COMPONENT_PACKS.md](design/UI_BUILDER_COMPONENT_PACKS.md) — other catalogs'
  components in one design.
- [design/UI_BUILDER_CATALOG_AUDIT.md](design/UI_BUILDER_CATALOG_AUDIT.md) — **audit**: what an
  application screen needs that the three authoring catalogs do not offer — drawing, layout, the
  Material 3 remainder, modifiers — and which repository owns each gap.
- [design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md](design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md) —
  the builder over a derived component record.
- [design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md](design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md) — the
  canvas, the frame and the variants.
- [design/UI_BUILDER_WEAR_SCREEN.md](design/UI_BUILDER_WEAR_SCREEN.md) — the Wear screen: one
  frame spec shared by the canvas and the native lane, and why a borrowed component may not be faked.
- [design/UI_BUILDER_REMOTE_COMPOSE.md](design/UI_BUILDER_REMOTE_COMPOSE.md) — Remote Compose
  composition inside a design.
- [design/UI_BUILDER_EDITOR_OPERATIONS.md](design/UI_BUILDER_EDITOR_OPERATIONS.md) — editor
  operation parity.
- [design/UI_BUILDER_VALUE_SEMANTICS.md](design/UI_BUILDER_VALUE_SEMANTICS.md) — a committed
  document is one the canvas can draw.
- [design/UI_BUILDER_STATE_STORAGE.md](design/UI_BUILDER_STATE_STORAGE.md) — a store that does not
  rewrite every design on every keystroke.
- [design/UI_BUILDER_LOCAL_STORAGE.md](design/UI_BUILDER_LOCAL_STORAGE.md) — designs kept in the
  browser.
- [design/UI_BUILDER_PROJECT_DESIGNS.md](design/UI_BUILDER_PROJECT_DESIGNS.md) — a project's
  designs.
- [design/UI_BUILDER_DESIGN_PORTABILITY.md](design/UI_BUILDER_DESIGN_PORTABILITY.md) — where a
  design lives, and how it comes back.
- [design/UI_BUILDER_EXPORT_BUNDLE.md](design/UI_BUILDER_EXPORT_BUNDLE.md) — exporting a design as
  a bundle.
- [design/UI_BUILDER_ASSETS.md](design/UI_BUILDER_ASSETS.md) — images in a design.
- [design/UI_BUILDER_LINKS.md](design/UI_BUILDER_LINKS.md) — links between designs.
- [design/UI_BUILDER_COMMENTS.md](design/UI_BUILDER_COMMENTS.md) — comments on a design.
- [design/UI_BUILDER_REVISION_HISTORY.md](design/UI_BUILDER_REVISION_HISTORY.md) — the history
  bar of revision thumbnails, diffing two of them, and how that sits beside the viewer's
  published-render versions.
- [design/UI_BUILDER_LIVE_SESSION.md](design/UI_BUILDER_LIVE_SESSION.md) — the live browser session.
- [design/UI_BUILDER_REFERENCE_OVERLAY.md](design/UI_BUILDER_REFERENCE_OVERLAY.md) — the reference
  overlay.
- [design/UI_BUILDER_SIDECAR_ACCESS.md](design/UI_BUILDER_SIDECAR_ACCESS.md) — sidecar access
  control.
- [design/UI_BUILDER_PROTOCOL_CLIENT.md](design/UI_BUILDER_PROTOCOL_CLIENT.md) — the common Kotlin
  v1 protocol client, compiled for JVM and Wasm without depending on `:server` or Ktor, with
  networking injected through transport interfaces.
- [design/UI_BUILDER_COLLABORATION_SOAK.md](design/UI_BUILDER_COLLABORATION_SOAK.md) — what
  `PersistentCollaborationSoakTest` drives, and why its schedule is seeded rather than timed.

### Benchmarks and fidelity

- [design/UI_BUILDER_JETCASTER_BENCHMARK.md](design/UI_BUILDER_JETCASTER_BENCHMARK.md) — the
  primary fidelity target.
- [design/UI_BUILDER_JETCASTER_FIDELITY.md](design/UI_BUILDER_JETCASTER_FIDELITY.md) — the evidence
  for the content-color, floating-toolbar and compact-layout alignment against it.

## The server's own surfaces

- [design/AGENT_ACCESS_GRANTS.md](design/AGENT_ACCESS_GRANTS.md) — **implemented**: how an agent
  with no credential gets temporary, scoped, revocable access to a `serve` host.
- [design/CATALOG_MCP.md](design/CATALOG_MCP.md) — **implemented**: the remote catalog MCP surface,
  and why the UI-builder tools share its endpoint rather than taking one of their own.
- [design/COMPARE_NAVIGATION.md](design/COMPARE_NAVIGATION.md) — **proposal + phase 1**: one
  vocabulary, one shape and one filter for the seven places a difference can be looked at.
- [design/COMPONENT_PARITY_WORKFLOW.md](design/COMPONENT_PARITY_WORKFLOW.md) — parity issues and
  scoped acceptance.
- [design/MULTIPLAYER_WORKFLOW.md](design/MULTIPLAYER_WORKFLOW.md) — **proposal**: what a
  multiplayer design/engineering/product workflow would look like over what exists today.

## History

- [design/PREVIEW_SERVER_SPLIT.md](design/PREVIEW_SERVER_SPLIT.md) — the extraction that produced
  this repository, kept for the one lesson two build files cite it for.

## Not indexed here

`docs/evidence/`, `docs/design/evidence/`, `renders/` and `serve-web/renders/` hold per-PR capture:
one directory per pull request, embedded in that PR's body and commit-pinned.
`preview-harness/performance-results/` holds dated acceptance runs, which are reference measurements
rather than baselines and are kept as they were taken.
