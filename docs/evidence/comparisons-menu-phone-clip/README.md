# The comparisons menu, clipped out of reach at phone widths

Captures from the `preview-harness` static server over the committed
`fixtures/pages/serve-viewer-rc-parallel.html`, light theme, at a **390×780** viewport — the paired
Remote Compose preview, which is the shape that carries two comparison destinations.

| File | What it shows |
| --- | --- |
| `before-390.png` | `origin/main`. `Full comparisons ▲` is **open** — the caret is up — and all that survives is a ~7px grey sliver under it. `.cp-preview-primary` is the row's scroll container at this width, and the absolutely positioned panel is clipped by it. |
| `after-390.png` | The same click. The panel opens as a bottom sheet — `Wear M3 layers`, `Compare players` — on the same M3 surface it uses on the desktop. |

Measured rather than eyeballed, by hit-testing the centre of each menu item with
`document.elementFromPoint` and asking whether the item is what the browser returns:

| | items | **reachable** | panel `position` | row `overflow-y` |
| --- | ---: | ---: | --- | --- |
| before, 390px | 2 | **0** | `absolute` | `hidden` |
| after, 390px | 2 | **2** | `fixed` | `hidden` |
| after, 1200px | 2 | 2 | `absolute` | `visible` |

The last row is the point of the media-query scope: on the desktop nothing changes, because the row
does not scroll there and the panel was never clipped.
