# The viewer's compare strip

Real captures, not mocks: `preview-harness/pages-snapshot.spec.mjs` rendering the committed
`fixtures/pages/serve-viewer-spec-default-theme.html` golden that `ServeWebFixtureTest` writes, so
these are the shipped markup and the shipped stylesheet. The pictures inside the frames are the
harness's deterministic placeholder, which is why every render looks alike — the point of the shot
is the page, not the pixels it is comparing.

| File | |
| --- | --- |
| `before.png` | The viewer at `ae7ee69`. The bar's `Transparent` sits among the comparison views and `Fit width` has wrapped to a line of its own; below the stage there is nothing about the component's other variants. |
| `after.png` | The same page with the compare strip, and with `SVG` / `3D` / `Transparent` / `Fit width` gathered under one `VIEW` label so the bar no longer rearranges when the design-spec lane is entered. |

Regenerate with:

```
cd preview-harness && npx playwright test -c playwright.config.mjs \
  pages-snapshot.spec.mjs --grep "spec-default-theme"
```
