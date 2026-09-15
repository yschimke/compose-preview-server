# Spec lane: the eyedropper, as a loupe

Before/after for [#830](https://github.com/yschimke/compose-preview-server/issues/830) on
`/remote-m3/p/appcard__ideal__default__compact?mode=spec` — the picker that named one pixel on each
side now magnifies the neighbourhood around it, on both sides at once, and can read the render
inside its own matched layout box rather than at the reference's coordinate.

| | |
| --- | --- |
| `before.png` | the pointer on the render panel, the lane header carrying `146,122 · Figma #494451 · Render #332e3c · Δ 22`. Two colours and a number, about one pixel. |
| `after.png` | the same hover with the loupe on: `FIGMA` and `RENDER` at 8×, the sampled cell ringed in the diff map's magenta, and the same reading still in the header. |

The patch in `after.png` is the answer the reading could not give. Under the crosshair Figma has the
inside edge of a stroke and the render has the *middle* of a different glyph — the card's body text
sits at a different place on the two sides, and `Δ 22` was one arbitrary sample of that. A magnifier
shows it as what it is in one look.

Both shots are of the committed artwork through the committed bundles: nothing in `shoot.mjs` draws
a panel or a patch. The magnified pixels are `drawImage`d out of the very canvases the three panels
were painted from, so the ink in the loupe is the picture's, which is why this is shot rather than
described.

## The dial settings, and why

An **odd** window — 15 normalised pixels across — so the patch has a centre *cell* rather than a
centre line, and the crosshair can mark the pixel the readout names instead of the corner between
four of them. At 120px a tile that is an 8× cell: large enough to count a one-pixel border or see a
half-covered state layer, small enough that a 3px shift reads as a fraction of the patch rather than
as a scroll of it. Nearest-neighbour, for the same reason `.cp-spec-canvas--upscaled` exists — a
smoothed magnification is an average of the pixels rather than the pixels.

The window is **not clamped** to the frame. A point near an edge magnifies a square that hangs off
it and `drawImage` draws the intersection, which is the truth about that point; sliding the window
inward would show a neighbourhood the cursor is not on, centred on a pixel it is not over.

The patch **flips** to the other side of the cursor rather than being clamped when it would leave
the viewport. Clamping alone slides it under the pointer, and near the bottom-right — which on a
preview page is where the stage's own corner is — the loupe would cover the pixels it was opened to
show.

## Align: comparing content, not coordinates

`normaliseImageUrls` registers the pair by **content box**: both frames are cropped to their ink and
drawn onto one origin. That is the right registration for a delta map and it says nothing about
where any individual element ended up, which is the question in the issue — *how do we avoid a 3px
shift making everything different?*

With **Align** pressed, a point is located in the smallest matched **layout** box that contains it,
and the render is read at that box's own position instead. The boxes come from the annotations the
typography overlay already uses, paired by `annotate/match.ts`; `spec/align.ts` maps both sides into
the pair's normalised space and answers one question — which box, and how far did it move.

Measured end to end in `test/specCompare.test.ts`, on a pair whose label is three rows lower in the
render:

```
off   4,2 · Spec #ffffff · Render #000000 · Δ 255
on    4,2 · Spec #ffffff · Render #ffffff · aligned +0,+3 · identical
```

`Δ 255` is true about those two pixels and useless as an answer about the label. `aligned +0,+3` is
both the honest comparison *and* the measurement of the shift, which is usually the next question.

Four decisions in that, each pinned by `test/specAlign.test.ts`:

- **the innermost containing box wins.** Layout boxes nest — a card holds a column holds a label —
  and the outer ones have usually not moved at all, because normalisation already aligned the frame
  by its content box. Aligning by one of those is the same as not aligning;
- **centres, not origins.** An element that both moved and resized has no single "shift"; its centre
  is the point both sides agree is the same point;
- **layout boxes only.** Typography boxes are a sparse overlay of the text runs rather than a tiling
  of the frame, so "the smallest box containing this point" means nothing over them;
- **no matched box is said, not guessed.** Falling back to a zero offset would be indistinguishable
  from a matched box that turned out not to have moved — the one distinction the option exists to
  draw. The patch says `no matched box here`, and the reading carries no `aligned` term at all.

It is **off by default**, and that is the trade the issue asks about. Unaligned, the lane reports
what is at a point, which is what a pixel diff means and what every other number on the page is
measured the same way. Aligned, it reports what the same element *is*, which is what a design review
is asking. The first is the lane's contract, so it stays the default and the second is one press
away.

## Reproducing

`../spec-lane-eyedropper/fixture/` is the real page, not a mock: `/remote-m3/p/appcard__ideal__default__compact`
as preview.coo.ee served it, beside the PNGs it points at.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` presses the new **Loupe** toggle off rather than checking out the old assets, and that is
precisely the old lane: a reading and no patch. The toggles themselves are visible in both shots on
purpose — they are part of what changed, and hiding them in one would make the pair differ in more
than the one thing it is about.

The alignment case is not shot here. This fixture is a captured page, so the render side's
annotations endpoint is not among the files it carries, and authoring a payload to stand in for it
would make the picture a statement of this script's rather than of the server's. The readings above,
out of `test/specCompare.test.ts`, are the evidence for that half.
