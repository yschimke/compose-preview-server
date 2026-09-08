# The viewer's toolbar: one renderer control, and the sibling as a peer

Captures from the `preview-harness` pages run over the committed
`fixtures/pages/serve-viewer-rc-parallel.html`, light theme. That fixture's catalog declares a
`compareWith` sibling, so it is the one page that shows both changes at once.

| File | What it shows |
| --- | --- |
| `toolbar-before.png` | `○ AndroidX Embedded ▸ Live` and a separate `Switch renderer…` combo, then a lone `Figma` pill with the sibling nowhere on the bar. |
| `toolbar-after.png` | The renderer as one segmented pill with a caret, and `COMPARE  (Figma) (Wear M3)` — both sources as peers under one label. |
| `toolbar-live-on.png` | The same pill LIT. `[aria-pressed="true"]` swaps the chip's outline for a filled green field, so the caret segment takes the field too and a divider stands in for the edge — otherwise a borderless green half sits against an outlined box and the control comes apart at exactly the moment the page claims to be live. |

The sibling was always reachable, but only *through* the `Figma` chip: the source picker inside the
lane ships `hidden` until that chip is pressed. A reader who never pressed it had no way to learn
this catalog has a counterpart.
