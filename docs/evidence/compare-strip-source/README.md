# The compare strip follows the lane's source picker

Real captures, not mocks: Playwright over the committed
`preview-harness/fixtures/pages/serve-viewer-rc-parallel.html` golden that `ServeWebFixtureTest`
writes, served by `preview-harness/_server.mjs` so the page loads the shipped `serve.css`. The
pictures inside the frames are the harness's deterministic placeholder — the point of the shot is
the strip, not the pixels it is comparing.

The two shots are the same page with the lane's source picker on each of its two sources. Before
this change only the left one existed: pressing the picker switched the pair on the stage and left
every row below still comparing against the design reference.

| File | |
| --- | --- |
| `before.png` | The `kit` source. The rows stand opposite the design reference and carry the match the delivery branch published for those exact pixels. Byte-for-byte the strip an unpaired catalog still gets. |
| `after.png` | The `parallel` source. The same rows against the paired catalog's own render, the heading and column renamed to it, and every score `not scored` — nothing measured that pair, and a design number must not stand in for one. The unpaired variant keeps its empty frame rather than borrowing the sibling's default. |

Regenerate by serving `preview-harness/fixtures/pages` with `_server.mjs`, opening
`serve-viewer-rc-parallel.html`, and screenshotting `#cp-compare-strip` with
`data-cp-strip-source` set to each source in turn.
