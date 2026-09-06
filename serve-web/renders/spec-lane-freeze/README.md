# Spec lane: what the eyedropper freezes when you click it

Before/after for the picker's freeze gesture on
`/remote-m3/p/appcard__ideal__default__compact?mode=spec`, in the Triptych view — issue #464, "the
colour picker is glitchy when it shows and hides".

| | |
| --- | --- |
| `before.png` | one pointer move onto the render panel, then one click at the **same screen point**. The row was reading `146,122 · Figma #494451 · Render #332e3c · Δ 22`; the click replaced it with `145,121 · Figma #332e3c · Render #332e3c · identical`. |
| `after.png` | the identical gesture. The click latches the line that was already there. |

The crosshair in both shots is drawn by `shoot.mjs` at the exact coordinates the events carried —
screenshots do not carry a cursor, and where the pointer was is half of what these shots claim.
Everything else is the page: both readings are read back out of those very canvases by the lane.

## Why the click read a different pixel

The picker's one gesture is *hold what I am looking at*, and it was not holding that. The click
handler took its **own** reading, from the click's coordinates — and a click's coordinates are not
the pointer's. Chromium rounds `MouseEvent.clientX/clientY` to whole CSS pixels, while the
`pointermove` that drew the line carries fractions. The events from the shot above, one held-still
pointer:

```
pointermove  1015.138427734375, 496.87811279296875
pointerdown  1015.138427734375, 496.87811279296875
click        1015,              496
```

A panel is the raster *fitted into its box*, so that whole CSS pixel is more than one pixel of the
normalised space the reading names: here 0.878 CSS px moved the reading a full row. The crosshair
sits on the edge of the `i` in `ipsum`, where the diff panel beside it is solid magenta — so the
frozen line did not merely name a neighbouring pixel, it reported `identical` about a point the lane
was drawing as *changed*, one row from where the visitor was looking.

The fix is to latch the reading already on screen rather than take a new one. The click no longer
samples anything.

## And what the release left behind

The two ways out of a frozen reading disagreed. Escape blanked the row; a second click did not — it
dropped the `--frozen` styling and left the latched line in place, so a line describing where the
pointer *had been* went on standing beside an unfrozen row as though it were live. Both routes now
go through one release, which re-reads at the pointer's last tracked sub-pixel position: the row
comes back to the pixel the cursor is actually on, or empties when the cursor has left the
comparison entirely. The pointer's position is tracked through the freeze for exactly this — the
latch stops the row from moving, not the pointer.

`test/specCompare.test.ts` pins both halves, with the panel laid out at half the pair's height so
the click's rounding lands on a different row of the normalised space, as it does on a real page.

## Reproducing

The fixture is `spec-lane-eyedropper/fixture/` — the real page as preview.coo.ee served it, beside
the PNGs it points at — so this directory carries only the script and the two shots.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before    # CP_BEFORE_REF=<ref> to pick the base commit
```

`--before` serves `viewer-components.js` as `git show` hands it back from the base commit rather
than re-enacting the old behaviour, so the left-hand shot is the code that shipped. Page, artwork,
viewport and pointer path are byte-identical between the two runs; the readout line is the only
thing that differs.
