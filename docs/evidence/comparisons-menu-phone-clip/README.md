# The comparisons menu, clipped out of reach at phone widths

Captures from the `preview-harness` static server over the committed
`fixtures/pages/serve-viewer-rc-parallel.html`, light theme, at a **390×780** viewport — the paired
Remote Compose preview, which is the shape that carries two comparison destinations.

| File | What it shows |
| --- | --- |
| `before-390.png` | `origin/main`. `Full comparisons ▲` is **open** — the caret is up — and all that survives is a ~7px grey sliver under it. `.cp-preview-primary` is the row's scroll container at this width, and the absolutely positioned panel is clipped by it. |
| `after-390.png` | The same click. The panel opens as a bottom sheet — `Wear M3 layers`, `Compare players` — on the same M3 surface it uses on the desktop. |

Measured rather than eyeballed, by hit-testing **21 points across each row** with
`document.elementFromPoint` and asking whether the item is what the browser returns:

| | `Wear M3 layers` | `Compare players` | panel `position` | row `overflow-y` |
| --- | ---: | ---: | --- | --- |
| before, 390px | **21/21 blocked** | **21/21 blocked** | `absolute` | `hidden` |
| after, `z-index: 50` | 0/21 | **3/21 blocked** by `.cp-fab` | `fixed` | `hidden` |
| after, `z-index: 70` | 0/21 | 0/21 | `fixed` | `hidden` |
| after, 1200px | 0/21 | 0/21 | `absolute` | `visible` |

The middle row is why the sheet is raised above the report launcher rather than left on the panel's
desktop `z-index: 50`: `.cp-fab` is `position: fixed; right: 16px; bottom: 16px; z-index: 60`, so it
covered the right edge of the last row — three of twenty-one sample points, invisible to a test that
only probes each row's centre.

The last row is the point of the media-query scope: on the desktop nothing changes, because the row
does not scroll there and the panel was never clipped.

The sheet is also closed when a viewer drawer opens (`ViewerDrawers.setOpen`). It is `fixed` above
the FAB, so it paints over a drawer (`z-index: 40`) and its scrim (`35`) — and the summary that
would dismiss it sits *behind* that scrim, so leaving it open stranded the reader with a panel they
could not close covering the drawer they had just asked for.
