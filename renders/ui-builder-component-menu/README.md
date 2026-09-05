# The UI builder's component menu, before and after the catalog tree

Committed evidence for the insert panel in
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
— the list a component is dragged or added onto the canvas from.

Before this change the panel was one flat list under three headings taken from the component's
**role**: `Scaffolds`, `Containers`, `Composables`. That is what a slot will *accept*, not what a
person is looking for, so `Containers` held a tab row, a card, a dialog and a plain `Row` — and a
component with variants had none of them, because the catalog's variant property was reachable only
by inserting the component and editing it in the inspector.

After it, the panel is the tree the published catalog draws beside its grid, and it is drawn the way
that one is: an **All** pill carrying the whole count, sentence-case shelf rows with a solid twisty
and an accent bar down the open branch, a muted count at the end of every row, and an indent rule
running down each shelf's children. Under a shelf are its components, and under a component are its
variants — each with its own Add, and the catalog's first value marked `default`.

One structural difference from the catalog's own tree, and it is the data rather than the design: the
catalog splits `Progress Linear` and `Progress Circular` into two components under a
`Progress indicators` group, where the builder has one `m3/progress-indicator` whose `variant`
property is `linear` or `circular`. So the builder's tree is the same shape one level shallower —
what the catalog spends a group level on, this spends a variant level on.

**Thumbnails**, which the first pass knowingly left out, are here now — and they are not baked. The
catalog's rows carry a prebaked PNG because that page has the pixels on disk; the builder has
something better, the renderer that is about to draw the thing for real. Each row draws the
component inserted into an empty frame by the same `InsertComponent` its Add dispatches, so a
thumbnail cannot disagree with what pressing Add does, nothing has to be regenerated when a default
changes, no PNGs are committed, and a catalog nobody has baked artwork for still gets pictures. The
picture is also the grip: you drag the thing you are placing.

| file | what it is |
| --- | --- |
| `menu.before.png` | the panel grouped by role, as it shipped |
| `menu.after.png` | the same panel as the catalog's tree: All pill, shelves, accent bar, indent rules |
| `menu.variants.after.png` | the panel filtered to `filled` — a variant name, not any component's |
| `menu.thumbnails.before.png` | the shelves with a drag handle per row |
| `menu.thumbnails.after.png` | the same rows, each drawing the component its Add would insert |

All five are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
(`UiBuilderEditorChromePreview` and `UiBuilderComponentVariantsPreview`), so the next change to the
panel moves the evidence with it rather than leaving it stale.
