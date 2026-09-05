# AGENTS.md

This repository owns the Compose Preview server implementation, its `serve-web` frontend, Wasm UI,
and visual harness. Read `README.md` and `docs/design/PREVIEW_SERVER_SPLIT.md` before changing the
repository boundary.

## Enforced rules

- Git history attributes work only to the human committer. Never add an AI `Co-authored-by:` trailer
  or use an agent identity as author/committer. Scrub PR titles and bodies too.
- Branch names use `agent/...`.
- Commit subjects and PR titles use Conventional Commits.
- Run `./gradlew ktfmtFormat` before committing Kotlin changes and
  `npm --prefix serve-web run format` before committing serve-web changes.
- Regenerate the committed goldens with `scripts/regenerate-goldens.sh`, and read the diff. On a
  Renovate branch the `Regenerate goldens` workflow does it for you when CI goes red; on any pull
  request `/regenerate-goldens` asks for the same thing.
- Immediately before every push, fetch `origin main` and confirm the branch or PR has not merged.
- Open or update a PR automatically after a completed coding change. Never auto-merge.

## Boundary rules

- The build resolves released coordinates from Maven Central. Do not add `mavenLocal()`, a composite
  include of `compose-ai-tools`, project substitution, or a shared catalog outside this repository.
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
- Two published Kotlin modules. `:server` depends on `:ui-builder-runtime`, never back; `:server`
  holds the HTTP layer, the runner, the catalog store and the web surfaces, and
  `:ui-builder-runtime` holds persistent design state, catalog validation and renderer-neutral
  export orchestration. Both publish in lockstep: `:server`'s POM names the runtime at the same
  version. `checkUiBuilderRuntimeBoundary` and `checkServeModuleBoundary` enforce the graph.
- `:render-host` was a third, until #180 moved it to compose-ai-tools, where it publishes as
  `ee.schimke.composeai:render-host`. It is offline behaviour that opens no socket, which the layer
  rule places in that repository, and it had zero project dependencies inside this build. `:server`
  still depends on it; the edge points the same way, it just crosses a repository boundary the
  correct direction now. `checkRenderHostIsServerFree` went with it.
- Keep `checkServeModuleBoundary` a resolved-classpath positive allowlist, including transitives.
- The source package stays `ee.schimke.composeai.cli.serve` until a separately reviewed rename.
- UI-affecting PRs include viewable before/after evidence and update the visual harness when needed.
