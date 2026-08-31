# Spec lane: switching the compare source

Before/after for the `Compare against` picker on
`/remote-m3/p/appcard__ideal__default__compact?mode=spec` — the control that puts the paired
`wear-m3-catalog` beside the render instead of the imported Figma spec.

| | |
| --- | --- |
| `before.png` | `M3 Wear OS Apps Design Kit` is pressed and **nothing on the stage is its**. The left panel is still the Figma reference, still captioned `SPEC`; the diff is still the Figma diff at 26.28%; the readout still says the imported spec is baseline-only. The picker latched and the comparison did not move — yschimke/compose-ai-tools#4895. |
| `after.png` | the same click, and the whole lane follows it: the left panel is wear-m3-catalog's own AppCard render, captioned with that catalog's name, the diff is re-measured against it at 28.71%, and both the readout and the stage hint name whose render is on the stage. |

The bug was one latch. `<cp-spec-compare>` read the reference URL once at install, off the served
`data-reference` — which is the KIT raster and only ever the kit raster. Picking a source re-pointed
the hidden `<img>` behind the canvases, but that `<img>` is what the *plain* `Spec` view shows;
`Diff`, `Triptych` and `Slider` paint canvases from a normalised pair, and that pair never changed.
So the one view where a switch was visible was the one view most people are not in — the lane opens
on `Triptych`.

Fixing the latch means the panel can now hold something that is **not a specification**, and three
surfaces had to stop saying it is: the panel's caption and label name the source, the design-spec
chip keeps its published verdict to itself (that number is the kit comparison), and the kit's
typography annotations are withheld rather than drawn over another catalog's pixels. The pictures
and the live measurement are untouched — two renders is a real pixel comparison, and it is the
number the cross-system parity surfaces already report. Only the claim that it is a *spec* match
goes.

Both shots are off the published baseline (`rcPlayer=cmp-android` is an override), which is why
neither quotes a match score. That is the pre-existing rule, and the `after` line shows the one
change it needed: it now names *whose* render is baseline-only instead of asserting an imported spec
that is nowhere on the stage.

## Reproducing

`fixture/` is the real page, not a mock: `/remote-m3/p/appcard__ideal__default__compact` as
preview.coo.ee served it when the bug was filed, beside the three PNGs it points at — the remote-m3
render, its imported Figma reference, and wear-m3-catalog's render of the paired component. Nothing
is drawn by the script; every panel in both shots is painted by the committed `viewer.js` and
`viewer-components.js` from that committed artwork, which is why this is shot rather than described.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` reproduces the previous behaviour rather than checking out the old assets. The change is
exactly that `viewer.js` names the picked source on `open()` and the element acts on it, so
swallowing that argument on the way through re-latches the reference precisely as it was latched;
the stage hint, decided a few lines away by `specActive()` alone, is restored with it.
