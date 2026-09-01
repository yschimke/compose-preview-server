# preview-server harness

Playwright specs for `compose-preview serve`. Two kinds live here:

**Page captures** — `pages-snapshot.spec.mjs` renders the committed `fixtures/pages/*.html` (written
by `:cli`'s `ServeWebFixtureTest` / `ExplodedSvgFixtureTest`) and writes `<name>.<theme>.png` into
`out/`. `format-compare-scorer.spec.mjs` runs the served viewer's real
`serve/assets/format-compare.js` in a browser and scores synthetic pairs. Both feed the visual-diff
bot; see below.

**End-to-end lanes** — `serve-lanes`, `playground` and `bundle-upload` each boot a real server
(`*-boot.sh`) and drive it. They need a JVM, xvfb and a built CLI, which is why they have their own
configs and run in `serve-lanes-e2e.yml` rather than on every PR.

```
npm ci
npx playwright install --with-deps chromium

npm run harness:pages           # the page captures the diff bot compares
npm run harness:compare         # the compare scorer, in a real browser
npm run harness:serve-lanes     # needs SERVE_URL from serve-lanes-boot.sh
npm run harness:playground
npm run harness:bundle-upload
```

CI also runs `npm run harness:ui-builder-visual-replay` after assembling the standalone server and
the independent Jetcaster reference. That command deliberately excludes the cross-repository MCP
case below, so operations-to-production-pixels remains a required check without needing an external
compose-ai-tools checkout.

The UI-builder convergence gate additionally needs the standalone server distribution, packaged
UI-builder Wasm, and the real compose-ai-tools MCP executable:

```
./gradlew :server:installDist :ui-builder-reference-jetcaster:wasmFrontendDist
GATE2_MCP_LAUNCHER=/absolute/path/to/compose-preview-mcp npm --prefix preview-harness run harness:ui-builder-gate2
```

The visual-replay case submits the checked-in 100-step Jetcaster fixture through the public HTTP
protocol as one create plus an atomic ordered batch of 99 insert mutations. It opens the committed
revision in operator and independently granted agent browsers, exports through the production
daemon render lane, and pixel-diffs that PNG against the separately compiled Compose/Wasm oracle.
The 4% limit is the visual harness's existing cross-runtime/platform raster bound; the exact ratio,
PNG/SVG digests, converged browser hashes and diff images are retained as test attachments.

The MCP case starts the installed server twice with a fresh persistent state directory and no
external render app-home, then drives two real Chromium pages and the MCP process concurrently.
Together the cases prove authenticated create/open, private-design denial, browser/MCP convergence,
WebSocket gap recovery, restart persistence, deterministic revision-pinned PNG/SVG/Compose exports,
and scripted operations-to-pixels acceptance. Artifacts and process logs are retained under
`test-results/ui-builder-gate2/`.

`HARNESS_CHROMIUM=/path/to/chrome` points Playwright at an existing browser when the matching
download isn't present. `HARNESS_THEME=dark` narrows the captures.

## Why it lives here

It used to sit in [`preview-harness/`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/), which made it look like the VS Code
extension's. It never was: these specs drive the server's web surfaces. The measurement in
[#3824](https://github.com/yschimke/compose-ai-tools/issues/3824) put that misfiling at 28% of the
apparent traffic across the serve boundary, and counted from the other side it was worse — of 72
PRs touching `compose-preview-vscode/`, 60 touched only this harness. `harness:snapshot` ran 205 tests,
**167 of them these**.

Two consequences worth knowing:

- **The captures feed one diff surface, from two producers.** The extension's harness and this one
  writes `<name>.<theme>.png`, and `serve-preview-comment` diffs this directory against
  `serve-preview/main`. It merged two producers until the VS Code extension was split out
  (yschimke/compose-preview-vscode), which now runs its own copy against its own baselines.
  Capture names were unique across the two, so the
  merge is safe and the baselines did not need regenerating when this moved.
- **Both must render on the same Chromium.** They share a baseline set, so a Playwright version skew
  between the two `package.json`s would move pixels for reasons no PR explains. Keep the
  `@playwright/test` range here and in [`package.json`](https://github.com/yschimke/compose-preview-vscode/blob/main/package.json) in step.

`playwright.config.mjs` explains which settings are pixel-load-bearing. Change viewport or the
Chromium raster flags and every capture rebaselines.

## What stayed behind

[`preview-harness/`](https://github.com/yschimke/compose-preview-vscode/blob/main/preview-harness/) keeps the panel's own webview fixtures, `snapshot.spec.mjs`,
`contract.spec.mjs` and their helpers. The only thing this directory borrowed from it was a
four-line `listThemes()`, now copied into `_themes.mjs` — see that file for why it is a copy rather
than an import.
