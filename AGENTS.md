# AGENTS.md

This repository owns the Compose Preview server implementation, its `serve-web` frontend, Wasm UI,
and visual harness. Read `README.md` and `docs/design/PREVIEW_SERVER_SPLIT.md` before changing the
repository boundary.

## Enforced rules

- Git history attributes work only to the human committer. Never add an AI `Co-authored-by:` trailer
  or use an agent identity as author/committer. Scrub PR titles and bodies too.
- Branch names use `agent/...`.
- Commit subjects and PR titles use Conventional Commits. A `!` in one does **not** bump the major:
  every release here is a minor, and why is in [`docs/VERSIONING.md`](docs/VERSIONING.md).
- Run `./gradlew ktfmtFormat` before committing Kotlin changes and
  `npm --prefix serve-web run format` before committing serve-web changes.
- `:ui-builder-runtime` compiles under `explicitApi()` and its public API is pinned by the committed
  dump `ui-builder-runtime/api/ui-builder-runtime.api`, which `checkKotlinAbi` verifies as part of
  `check`. When that module's API changes, run `./gradlew :ui-builder-runtime:updateKotlinAbi` and
  commit the dump with the change. `:server` stays off the gate on purpose; its build file says why.
- Regenerate the committed goldens with `scripts/regenerate-goldens.sh`, and read the diff. On a
  Renovate branch the `Regenerate goldens` workflow does it for you when CI goes red; on any pull
  request `/regenerate-goldens` asks for the same thing.
- Immediately before every push, fetch `origin main` and confirm the branch or PR has not merged.
- Open or update a PR automatically after a completed coding change. Never auto-merge.
- In a PR body write `![alt](url)` plainly, and **leave any backticks that appear around the URL
  alone**. They are injected between an agent and GitHub rather than authored, and the
  [`PR Body Syntax`](.github/workflows/pr-body-syntax.yml) workflow strips them in place within a
  minute of the edit. Hand-fixing or re-posting to "correct" them does not work — the next write is
  mangled the same way.

## Boundary rules

- The build resolves released coordinates from Maven Central. Do not add `mavenLocal()`, a composite
  include of `compose-ai-tools`, project substitution, or a shared catalog outside this repository.
- **The UI builder is a second project inside this repository, on the same release line.** Which
  modules are in it, the four published seams the server may reach it through, and the rule that it
  never depends on the server, are written once in
  [`docs/design/UI_BUILDER_PROJECT_BOUNDARY.md`](docs/design/UI_BUILDER_PROJECT_BOUNDARY.md) and
  enforced by [`.github/scripts/ui-builder-project-boundary.sh`](.github/scripts/ui-builder-project-boundary.sh)
  on every pull request. Moving a module between the two projects, or adding a seam, is a change to
  that document in the same pull request as the code
  ([#346](https://github.com/yschimke/compose-preview-server/issues/346)).
- Which repository a module belongs in is decided by the layer rule, written once in
  [`docs/design/REPOSITORY_LAYERS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REPOSITORY_LAYERS.md):
  contracts is shape, compose-ai-tools is offline behaviour, this repository is HTTP and the
  surfaces reachable over it, and a dependency may only point down. Wire shapes therefore belong in
  `compose-preview-contracts`; server behavior and browser/offline scoring implementation belong
  here. Moving implementation into the contracts repository does not reduce traffic coupling.
- The Gradle Tooling API stays off this repository's floor. A server that needs a local Gradle build
  asks compose-ai-tools for one across a process boundary, over a contract in
  `compose-preview-contracts`; it does not link a Gradle driver
  ([#9](https://github.com/yschimke/compose-preview-server/issues/9),
  [#180](https://github.com/yschimke/compose-preview-server/issues/180)).
- Three published Kotlin modules. `:server` depends on `:ui-builder-runtime`, never back; `:server`
  holds the HTTP layer, the runner, the catalog store and the web surfaces, and
  `:ui-builder-runtime` holds persistent design state, catalog validation and renderer-neutral
  export orchestration. Both publish in lockstep: `:server`'s POM names the runtime at the same
  version. `checkUiBuilderRuntimeBoundary` and `checkServeModuleBoundary` enforce the graph.
- `:mcp` is the third, and it depends on neither of the other two. It is the Model Context Protocol
  server — `compose-preview mcp serve` — moved here from compose-ai-tools because the layer rule
  places a module that needs an HTTP server in this repository (compose-ai-tools#5176), and it
  reaches what it needs from layer 1 (`daemon-core`, `daemon-client`, `render-session-api`,
  `render-matrix`) as published coordinates. It publishes as `compose-preview-mcp` and ships a
  standalone tarball on the GitHub release, which is what the CLI's `mcp` command launches. Keep
  `checkMcpToolingApiBoundary` passing: driving a Gradle build is layer-1 behaviour and stays off
  this repository's floor, MCP included.
- `:render-host` was a third, until #180 moved it to compose-ai-tools, where it publishes as
  `ee.schimke.composeai:render-host`. It is offline behaviour that opens no socket, which the layer
  rule places in that repository, and it had zero project dependencies inside this build. `:server`
  still depends on it; the edge points the same way, it just crosses a repository boundary the
  correct direction now. `checkRenderHostIsServerFree` went with it.
- Keep `checkServeModuleBoundary` a resolved-classpath positive allowlist, including transitives.
- The preview-selector rule (`previewIdMatchesStandaloneRequest`) is stated in this repository and
  again in compose-ai-tools, because `serve` is a launcher and the CLI no longer passes its own rule
  in. `docs/serve/preview-selector-fixtures.json` is the shared golden table that pins them; it is
  owned upstream, vendored by `scripts/sync-preview-selector-fixtures.sh`, and run by
  `PreviewSelectorFixturesTest`. Change the rule, change the table upstream in the same change.
- Two JVM floors, `java-server` (17) and `java-ui-builder` (21), declared once in
  `gradle/libs.versions.toml` with the reasoning beside them. Everything a consumer compiles or
  resolves against is 17, because compose-ai-tools' `:cli` compiles against published
  `compose-preview-serve` on a 17 toolchain; only `:ui-builder` and `:ui-builder-artwork`, whose
  class files leave the build solely inside `:ui-builder-render-bundle`'s polyglot, sit above it.
  Building needs both JDKs. Never raise a module's toolchain by editing the module —
  `:server:checkServerJvmFloor` scans the resolved distribution classpath and will say so.
- **Never fabricate a component in the Wasm canvas to stand in for a library the canvas cannot
  link.** The editor's canvas is Compose Multiplatform for Wasm; `androidx.wear.compose` is an
  Android AAR it can never link, and hand-assembling a lookalike out of Material 3 pieces produces an
  impression nothing in this build can check. A catalog whose components the canvas cannot draw gets
  its fidelity from the streaming preview lane, which compiles the generated Kotlin against a real
  classpath — not from a replica maintained here. Stated once, with the decision and what it costs,
  in [`docs/design/UI_BUILDER_WEAR_SCREEN.md`](docs/design/UI_BUILDER_WEAR_SCREEN.md#the-line-a-component-is-never-faked-so-it-can-run-in-wasm)
  ([#395](https://github.com/yschimke/compose-preview-server/pull/395) is the change it closed).
- The source package stays `ee.schimke.composeai.cli.serve` until a separately reviewed rename.
- UI-affecting PRs include viewable before/after evidence and update the visual harness when needed.

## The hosted catalog MCP server

[`.mcp.json`](.mcp.json) registers `compose-preview-catalog`, the deployment of this repository's
own `:mcp` module at `https://preview.coo.ee/mcp`. It is how an agent reads the hosted catalogs and
drives a UI-builder design — `ui_builder_get_design`, `ui_builder_apply`, `ui_builder_export`,
`ui_builder_render_native` — against the running server rather than a local one.

The credential is `$COMPOSE_PREVIEW_TOKEN`, sent as `X-Compose-Preview-Token`. With the variable
unset the header is empty and every call answers `authorization_required`; that is the normal cold
start, not a misconfiguration. Recover it through the server's own device-code flow: call
`request_access` with the scope and capabilities the task needs (`ui-builder-read`,
`ui-builder-write`, `ui-builder-export` are separate from the `preview -> live -> playground`
compute ladder), show the human the `approveUrl` and `userCode` it returns, then `poll_access` until
it answers `approved`. A server restart drops every grant, so a token that stopped working is asked
for again the same way. Designs are private to their owner and collaborators, so a grant reads only
what its actor has been given an ACL for.
