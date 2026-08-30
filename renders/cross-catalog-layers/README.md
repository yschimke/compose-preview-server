# Cross-catalog layers

Committed evidence for the preview server's **cross-catalog layer diff** —
`/{system}/parallel/{preview}`, reachable from the viewer's provenance row as
_⇄ cross-catalog layers_, and the same document as data at `?format=json`.

It answers the half of a two-runtime comparison that pixels cannot. Two rasterisers drawing one
design system differ mostly in antialiasing; what a reader can act on is stated a layer up — the
font family a text node actually resolved, the value behind a token, the insets of a box. The page
puts those side by side for the cell the `compareWith` + `parallel` pairing resolved, reading both
sides out of the `annotations/index.json` each catalog already publishes over its own baked frame.
Nothing is re-rendered.

The projection is
[`ServeParallelLayers`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeParallelLayers.kt);
the pairing behind it is
[`ServeParallelPairing`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeParallelPairing.kt).

| file | what it is |
| --- | --- |
| `parallel-layers.light.png` | the page for one cell of `Button/Child`: a family that resolved to Inter on one catalog and Roboto on the other, a padding that differs by 4dp, and a text node only one of the two draws at all |
| `parallel-layers.dark.png` | the same page in the dark scheme |

Both are headless-Chromium captures of the committed harness fixture
`preview-harness/fixtures/pages/serve-parallel-layers.html`, which `ServeWebFixtureTest` generates
from the real page function — so a change to the page moves this evidence along with the golden.
