# Spec lane: reading the pixel under the cursor

Before/after for the eyedropper on
`/remote-m3/p/appcard__ideal__default__compact?mode=spec` — a pointer over any of the lane's
panels now names that pixel on **both** sides of the comparison and the difference between them.

| | |
| --- | --- |
| `before.png` | the pointer sits on the render panel and the lane header says only what it always said: the score. The lane can state that the two pictures differ and by how much overall; asked what either pixel *is*, it has no answer. |
| `after.png` | the same hover, and the header carries `146,122 · Figma #494451 · Render #332e3c · Δ 22` — the point, both frames' colours at it, and the channel distance. Read out of the very canvases beside it. |

The question this exists for is a state layer. Material's focus treatment is a 10% white overlay, so
a focused container is `#7661ad` where its resting one is `#6750a4` — 17/255 on one channel. That is
a real difference and it is at the edge of what an eye reports reliably, so "is the overlay drawn,
and does the reference agree" was a question the lane could not answer about the pixels it was
already showing. `Δ 22` above is that question answered in one number.

The registration the reading needs was already done, which is why the whole picker is index
arithmetic. `normaliseImageUrls` hands the lane its pair as two canvases of **one** size, cropped to
their content boxes and drawn onto a shared origin — the same normalisation that makes the delta map
meaningful. A point in that space therefore names the same feature in both frames by construction:
no per-side scale, no root-`translate` subtraction, no letterbox offset. `src/spec/pick.ts` is that
arithmetic and nothing else, and `test/specPick.test.ts` covers it directly.

Two rules in the readout are deliberate and are the tests' subject as much as the shots':

- **Alpha counts toward the delta**, matching `deltaMap`. Ignoring it would call an opaque pixel and
  a transparent one of the same RGB identical — exactly what a reference missing a layer looks like,
  and the one case a picker must not report as a match.
- **A transparent pixel reads `transparent`, not its RGB.** An unpainted buffer hands back whatever
  happens to sit there; printing that as a colour states a fact about the picture that is not true.

The readout sits on a **row of its own, claimed when the lane opens** rather than when the first
reading arrives. The lane header is `flex-wrap: wrap`, so a readout appearing among the controls
added a line to it and pushed the stage down 26px — under the cursor, mid-hover. The reading on
screen then described a pixel the pointer had already left: at one fixed screen point, `146,122 ·
Δ 22` before the shift and `146,94 · identical` after it. Reserving the row moves the stage once,
on entering the lane, while nothing is being read; the same measurement now reads identically
before and after the readout appears.

Every panel is a reading surface, the wipe canvas included — `drawWipe` sizes it to the pair and
draws both sides at the origin, so a point on it names what the other three do. It has to be one,
too: the Slider view hides all three panels, so without it the eyedropper is absent from a quarter
of the lane. A press there is a *seek*, though, so it is the one surface a reading cannot be frozen
from — freezing would hijack the slider's own gesture.

The picker goes quiet from the moment a different pair is asked for until its frames arrive. A
source switch re-labels the lane at once and re-normalises asynchronously, so in between the
canvases still hold the previous source: a reading taken then is the old pixels under the new
source's name. Measured mid-switch, with the paired catalog's raster held back 2.5s — before, the
readout said `153,163 · M3 Wear OS Apps Design Kit #332e3c · …` over Figma's pixels; now it says
nothing until the pair lands, then answers under the label it belongs to.

Leaving the lane takes the reading away with it — text, announcement, frozen latch and both pixel
readbacks. `cp-spec-lane` carries the source buttons, so it outlives the lane; a reading left in it
would go on naming two colours beside a picture neither came from, and the latch would still be
holding when the lane was next opened.

The readout is not itself an `aria-live` region — it updates on every pointer move, and a live region
that changed at pointer rate would talk over everything else on the page. A separate off-screen
region announces the settled reading instead.

## Reproducing

`fixture/` is the real page, not a mock: `/remote-m3/p/appcard__ideal__default__compact` as
preview.coo.ee served it, beside the PNGs it points at. Nothing in the script draws a panel — every
canvas in both shots is painted by the committed `viewer.js` / `viewer-components.js` from that
committed artwork, and the reading in `after.png` is read back out of those same pixels. That is why
this is shot rather than described: the hex in the readout is the picture's, not the shot's.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` removes the two readout elements at init rather than checking out the old assets. The
change is exactly that the lane now carries `#cp-spec-pick` and reads the pair's pixels into it;
with the elements gone `setPick` finds nothing and writes nowhere, which is precisely how the lane
behaved when neither the elements nor the handlers existed.
