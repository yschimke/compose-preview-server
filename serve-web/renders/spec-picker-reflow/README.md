# Spec lane: the readout may not move the page

Before/after for issue #464 — "the colour picker is glitchy when it shows and hides" — on the page
it was reported from, `/wear-m3-catalog/p/button-compact__ideal__filled-variant-icon-only?mode=spec`,
at the reporter's own 1280×683 viewport.

Each shot is **two frames of the same page rectangle**: pointer away, then pointer on the render
panel. The dashed rule is `position: fixed` at one viewport y, drawn into the page before either
frame is taken, so both carry it at the same page coordinate.

| | |
| --- | --- |
| `before.png` | the reading arrives and the **SVG** and **3D** toggles drop from the end of the Triptych row onto a line of their own. The stage goes with them: `getBoundingClientRect().y` 392.1 → **398.1**. Move the pointer off again and everything jumps back. |
| `after.png` | the toggles stay where they are and the stage does not move: 392.1 → **392.1**. |

## Why the row moved the page

The row was already *reserved* — `serve-web/renders/spec-lane-eyedropper` is the change that did
that, after a readout appearing among the controls pushed the stage down 26px mid-hover. Reserving
it fixed the row's **height**. This is the same fault one axis over, and reserving the height could
never have caught it, because the shift came from the row's **width**.

`.cp-spec-lane` is `display: inline-flex`, so it is shrink-to-fit: its width is whatever its
contents need. `.cp-spec-pick` is `flex-basis: 100%`, which contributes nothing to that measurement
while the row is empty and takes the whole available width the moment it holds text. Measured here:

```
                lane width   .cp-preview-primary   stage y
pointer away        1020px          146px           392.1
a reading in it     1224px          152px           398.1
```

The lane's parent `.cp-preview-primary` is itself `flex-wrap: wrap`, and at 1280 the 1020px lane
left exactly enough room for the two format toggles beside it. Widening it to the full 1224px put
them on a new line, which made the header a row taller, which moved everything below it.

That last part is why the bug is viewport- and catalog-dependent, and why it did not show up in the
existing eyedropper fixture: on a page whose lane already spans the full width, there is nothing
beside it to displace. The reporter's 1280×683 is a width where there is.

The fix is two declarations in `serve.css`:

```css
.cp-spec-pick { flex-basis: 100%; width: 0; min-width: 100%; … }
```

`width: 0` is the value the lane measures itself against, so no reading can move it. `min-width`
resolves against the settled lane afterwards, so the row still spans it, and the `overflow-x: auto`
that was already there scrolls a reading too long to fit rather than widening anything.

`ServeWebFixtureTest.the eyedropper's readout cannot resize the spec lane` pins it. The assertion is
on the sheet rather than on a screenshot deliberately: the whole fault is a used width, and both
frames of `before.png` show a readout on a row of its own — 6px apart.

## Reproducing

`fixture/` is the reported page as preview.coo.ee served it, recorded with every response it made —
bodies at the paths that served them, `index.json` mapping each full URL to its file and content
type — so a run is offline and deterministic. Only `serve.css` and the script bundles come from the working
tree, by basename, so the shots follow the sheet being edited.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before    # CP_BEFORE_REF=<ref> to pick the base commit
node shoot.mjs --record               # refresh fixture/ from the live server
```

`--before` serves `serve.css` as `git show` hands it back from the base commit, so the left-hand
shot is the sheet that shipped rather than a re-enactment. Viewport, page, artwork and pointer path
are identical between the two runs.
