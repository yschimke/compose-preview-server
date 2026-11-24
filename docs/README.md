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
- The OpenAI MCP Extensions probe (#1236) moved to
  [yschimke/compose-preview-mcp](https://github.com/yschimke/compose-preview-mcp) with the MCP
  server and its probe page.

## Wire contracts

- [serve/SESSION-VIEWER-PROTOCOL.md](serve/SESSION-VIEWER-PROTOCOL.md) — **normative**: the
  `composeai-stream/1` wire contract between `compose-preview serve` and a session-viewer client.
  Cited from `compose-ai-tools`, so it is a cross-repository contract rather than an internal note.

## The UI builder

The UI builder and its design documents live in
[yschimke/compose-ui-builder `docs/design/`](https://github.com/yschimke/compose-ui-builder/tree/main/docs/design).
This repository keeps only the boundary, contract, and server-side integration docs referenced here:

- [design/UI_BUILDER_PROJECT_BOUNDARY.md](design/UI_BUILDER_PROJECT_BOUNDARY.md) — **normative**:
  which module owns the service port, persistent state, catalog validation and export
  orchestration, and what it deliberately does not own. Read this before changing a module boundary.
- [design/UI_BUILDER_CATALOG_CONTRACT.md](design/UI_BUILDER_CATALOG_CONTRACT.md) — what a catalog
  repository authors so the builder can serve it without being written into the server.
- [design/UI_BUILDER_WEAR_SCREEN.md](design/UI_BUILDER_WEAR_SCREEN.md) — the Wear screen: one
  frame spec shared by the canvas and the native lane, and why a borrowed component may not be faked.
- [design/UI_BUILDER_WATCH_FACE_ANDROID_PREVIEW.md](design/UI_BUILDER_WATCH_FACE_ANDROID_PREVIEW.md) —
  the `android-dwf` native surface: a Watch Face Format design drawn by Google's runtime in its own
  tab, the session lifecycle, the control → daemon mapping, and what compose-ai-tools must export.
- [design/UI_BUILDER_HOSTED_PROJECTS.md](design/UI_BUILDER_HOSTED_PROJECTS.md) — hosted projects on
  the preview server.

## The server's own surfaces

- [design/AGENT_ACCESS_GRANTS.md](design/AGENT_ACCESS_GRANTS.md) — **implemented**: how an agent
  with no credential gets temporary, scoped, revocable access to a `serve` host.
- [design/CATALOG_MCP.md](design/CATALOG_MCP.md) — **implemented**: the remote catalog MCP surface,
  and why the UI-builder tools share its endpoint rather than taking one of their own.
- [design/CHAT_SURFACES.md](design/CHAT_SURFACES.md) — **implemented, live checks pending**: Slack
  and other chat tools as text and image fallbacks, pick-by-reply contact sheets, Slack thread
  links, rate-limited notifications and unfurls.
- [design/COMPARE_NAVIGATION.md](design/COMPARE_NAVIGATION.md) — **proposal + phase 1**: one
  vocabulary, one shape and one filter for the seven places a difference can be looked at.
- [design/COMPONENT_PARITY_WORKFLOW.md](design/COMPONENT_PARITY_WORKFLOW.md) — parity issues and
  scoped acceptance.
- [design/MULTIPLAYER_WORKFLOW.md](design/MULTIPLAYER_WORKFLOW.md) — **proposal**: what a
  multiplayer design/engineering/product workflow would look like over what exists today.

## Repository boundary

- [design/SERVER_UNBUNDLING.md](design/SERVER_UNBUNDLING.md) — **accepted, in progress**: the UI
  builder and the hosted MCP ship their own containers on their own release trains. Covers the
  target image ownership, the contracts that replace in-process seams, and the order of the moves.

## History

- [design/PREVIEW_SERVER_SPLIT.md](design/PREVIEW_SERVER_SPLIT.md) — the extraction that produced
  this repository, kept for the one lesson two build files cite it for.

## Not indexed here

`docs/evidence/`, `docs/design/evidence/`, `renders/` and `serve-web/renders/` hold per-PR capture:
one directory per pull request, embedded in that PR's body and commit-pinned.
`preview-harness/performance-results/` holds dated acceptance runs, which are reference measurements
rather than baselines and are kept as they were taken.
