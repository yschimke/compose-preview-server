# Spec lane: a triptych frame fills its column

Before/after for the triptych's frame sizing, on `/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only?mode=spec` at 1280×683.

| | |
| --- | --- |
| `before.png` | the frame at its intrinsic **104×66**, adrift in a 387px column. The picture is 19% of the stage it appears to occupy; a pointer sweep across the triptych reads nothing for **73%** of its travel. |
| `after.png` | the frame fills its column at **386.7×245.4**. The sweep reads nothing for **4%** — the 16px gaps between columns. |
| `letterbox.png` | the same page at 1280×400, where `max-height: 52vh` binds before the column does. The frame draws **327.8×208** inside a 386.7×208 box, with bars either side. `327.8 / 208 = 1.576 = 104 / 66`: letterboxed, not squashed. |

## What was wrong

The triptych stretches its three columns — `flex: 1 1 0` — but the base panel rule sizes a frame with `max-width` / `max-height` against `auto` dimensions, and **`max-*` only ever shrinks a replaced element**. A raster smaller than its column therefore sat at its intrinsic size with the column's remaining width as inert background: nothing to look at, and no pixel for the eyedropper to name.

On the Wear catalogs that is the normal case rather than an edge one. `button-compact` is 104×66; a Wear screen preview is 192×192; the column at 1280 is 387px.

This is what was left of issue #464 after the two fixes that preceded it. Chasing the picker's "flicker" was the wrong instinct — the readout blanks because there genuinely is no pixel under the cursor, and 6 blank/refill flips across three separate pictures is the minimum a sweep can produce. The picker was right; the pictures were small.

## `object-fit: contain` is the load-bearing half

Once the frame fills its column, `width` is definite. A raster taller than it is wide then squashes to `max-height` — and a distorted spec comparison is worse than a small one, in a lane whose whole job is to say whether two pictures differ. Every `__192dp` Wear screen is exactly that case.

`contain` letterboxes instead, which costs a mapping: the element's box and the drawn frame stop being the same rectangle, and reading through the box slides every reading toward the centre by half the bar — silently, and further the squarer the frame. `SpecCompare.drawnRect` does `contain`'s own arithmetic (one `Math.min`) and the eyedropper maps through that; a point on a bar reads nothing, exactly as a point off a panel does. `test/specCompare.test.ts` pins both.

## Nearest-neighbour, but only upward

`image-rendering: pixelated` is set by a class the component puts on after each paint and on resize, never by the rule itself. Enlarged, it is the honest rendering for a pixel comparison — the row says `#f6edff` and the picture shows the pixel that colour belongs to, not a smoothed average of it and its neighbours. Downscaled it is the opposite: nearest-neighbour drops rows of a 1000px app screen on the way to 387px and invents aliasing the render does not have.

## Reproducing

The fixture is the one `spec-picker-reflow` recorded — the page the issue was reported from, replayed offline — so this directory carries only the script and the shots.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before    # CP_BEFORE_REF=<ref> to pick the base commit
node shoot.mjs letterbox.png --short  # 1280x400, where the height budget binds first
```

`--before` serves both halves of the change — `serve.css` and `viewer-components.js` — as `git show` hands them back from the base commit, so the left-hand shot is the code that shipped rather than a re-enactment. Each run prints the geometry and the swept dead-space fraction it measured, which is where the numbers above come from.
