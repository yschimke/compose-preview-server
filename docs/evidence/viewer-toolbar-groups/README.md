# The viewer's toolbar: one renderer control, and the sibling as a peer

Captures from the `preview-harness` pages run over the committed
`fixtures/pages/serve-viewer-rc-parallel.html`, light theme. That fixture's catalog declares a
`compareWith` sibling, so it is the one page that shows both changes at once.

| File | What it shows |
| --- | --- |
| `toolbar-before.png` | `○ AndroidX Embedded ▸ Live` and a separate `Switch renderer…` combo, then a lone `Figma` pill with the sibling nowhere on the bar. |
| `toolbar-after.png` | The renderer as one segmented pill with a caret, and `COMPARE  (Figma) (Wear M3)` — both sources as peers under one label. |

The sibling was always reachable, but only *through* the `Figma` chip: the source picker inside the
lane ships `hidden` until that chip is pressed. A reader who never pressed it had no way to learn
this catalog has a counterpart.
