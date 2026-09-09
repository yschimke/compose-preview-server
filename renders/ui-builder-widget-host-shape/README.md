# UI builder — the widget host container shape on the canvas

[#587](https://github.com/yschimke/compose-preview-server/issues/587) asked for the rectangular
widget preview, and [#597](https://github.com/yschimke/compose-preview-server/pull/597) gave it to
the **generated file**: an exported widget now carries a `@Preview` per host container shape. The
editor still drew one frame, so a designer could read that the rectangular container exists and
could not see their own design inside it without exporting the file and opening it elsewhere.

`canvas-both-shapes.png` is `WearWidgetHostShapesPreview` (`:ui-builder`,
`WearWidgetSamplePreview.kt`): the same Weather widget document, drawn by the editor's own canvas
renderer, in each host container the platform ships.

| | Left | Right |
| --- | --- | --- |
| Shape | Squircle | Rectangular |
| Frame | 216×124dp | 232×144dp |
| Content | 200×108dp | 168×112dp |
| Padding (h/v) | 8 / 8dp | 32 / 16dp |
| Radius | 26dp | 0dp |

The two frames differ in more than their corners, which is the whole reason the view is worth
having: the rectangular container lays the design out in a **narrower, taller** content box, so a
widget that just fits the squircle can clip in the other frame. Both footprints come from
[`hostSpec`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearWidgetHostShape.kt),
which is also what the native render lane builds its `WearWidgetParams` from — one table, so the two
panes cannot disagree about what the host reserves.

## Why there is no "before" frame here

Nothing about the squircle changed: the left frame is what the canvas has always drawn, and the
`WeatherWearWidgetSamplePreview` beside it in the same file is unchanged and still renders it. The
right frame is the new capability, and no earlier revision of this canvas could draw it at all.

## What this render cannot show

The **Native render pane** half of the same change — the shape now rides on the render request, so
that pane draws the container the canvas is drawing. Its frames come from the Android/Robolectric
daemon against a served `remote-m3` bundle (`androidx.glance.wear` is an Android AAR), which no
renderer in this repository can produce; a human or deployment verifies it by opening a `remote-m3`
design on a host that maps one and switching the container shape. The lane's own output is asserted
instead, in `ServeUiBuilderWearNativePreviewTest`: the submitted `WearWidgetParams` and the
`@Preview` canvas both move to the rectangular spec when the shape is asked for.
