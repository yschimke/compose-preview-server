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
- Immediately before every push, fetch `origin main` and confirm the branch or PR has not merged.
- Open or update a PR automatically after a completed coding change. Never auto-merge.

## Boundary rules

- The build resolves released coordinates from Maven Central. Do not add `mavenLocal()`, a composite
  include of `compose-ai-tools`, project substitution, or a shared catalog outside this repository.
- Wire shapes belong in `compose-preview-contracts`; server behavior and browser/offline scoring
  implementation belong here. Moving implementation into the contracts repository does not reduce
  traffic coupling.
- Two Kotlin modules, and the direction is `:server` -> `:render-host`, never back. `:render-host`
  holds what renders and reads history — the render host, the bundle daemon, the git-backed preview
  history — and must stay free of a web server; `:server` holds the HTTP layer, the runner, the
  catalog store and the web surfaces. Both publish, and a release must publish both: `:server`'s POM
  names `compose-preview-render-host` at the same version. `checkRenderHostIsServerFree` and
  `checkServeModuleBoundary` enforce the two halves.
- Keep `checkServeModuleBoundary` a resolved-classpath positive allowlist, including transitives.
- The source package stays `ee.schimke.composeai.cli.serve` until a separately reviewed rename.
- UI-affecting PRs include viewable before/after evidence and update the visual harness when needed.
