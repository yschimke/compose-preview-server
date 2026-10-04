# A compare strip with nothing to compare against

Real captures, not mocks: Playwright over the committed
`preview-harness/fixtures/pages/serve-viewer-strip-no-baseline.html` golden that
`ServeWebFixtureTest` writes, served by `preview-harness/_server.mjs` so the page loads the shipped
`serve.css`. "Before" is the same fixture generated from `main`'s `ServeWeb.kt` and `serve.css`. The
pictures inside the frames are the harness's broken-image placeholder — the fixture names render
URLs nothing serves; the point of the shot is the strip's columns, not its pixels.

The fixture is an imported catalog's component (tunjid-heron's message list): three variants, no
design reference on any of them, no paired catalog.

| File | |
| --- | --- |
| `before.png` | Every row carries an empty dashed **Design reference** frame and `not scored`, under "…, against Design reference", with a link to a reference wall that has no rows. |
| `after.png` | The variants alone — render and name, the one on the stage still marked — in a two-track grid. |
| `before-narrow.png`, `after-narrow.png` | The same at 390px. |

Regenerate by serving `preview-harness/fixtures/pages` with `_server.mjs`, opening
`serve-viewer-strip-no-baseline.html`, and screenshotting `#cp-compare-strip`.
