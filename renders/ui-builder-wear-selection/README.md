# UI builder — Wear selection rows

`wear-selection-rows.after.png` is `WearSelectionRowsPreview`: a Wear screen holding
`wear-m3/checkbox-button`, `wear-m3/switch-button` and `wear-m3/radio-button`, drawn by the builder's
canvas.

**This picture is the argument for the components existing.** Upstream's `CheckboxButton`,
`SwitchButton` and `RadioButton` are *rows*: a filled, full-width container with a label, an optional
secondary label and the control at its end. That is why they are list items on a watch, and why
borrowing `m3/checkbox` was never on the table — it would have put a 20dp square on the screen and
called it the same component.

What is borrowed here is the drawing, not the identity. The canvas cannot link
`androidx.wear.compose:compose-material3` (an Android AAR), so each row is assembled out of Material
3 pieces in Wear's shape — 52dp, fully rounded, `surfaceContainer`, label column at the start — while
the generated Kotlin names `CheckboxButton`, `SwitchButton` and `RadioButton` with the callbacks
upstream gives them (`onCheckedChange` for the two toggles, `onSelect` for the radio).

There is no before image: none of the three existed, on either catalog.

## One thing the generated code does not claim

Inside a `TransformingLazyColumn`, each row gets `Modifier.transformedHeight(this, spec)` and **not**
`transformation = SurfaceTransformation(spec)`. `ListHeader` and `TitleCard` take that argument and
upstream's own list samples pass it to them; there is no sample here — and no compiled Wear
dependency in this repository — showing a selection row accepts it, and an argument that may not
exist is source that does not compile. The row still measures against the list's scroll; what it
misses is the scale and fade toward the bezel. `WearSelectionRowExportTest` pins that, so adding the
argument later is a deliberate edit.
