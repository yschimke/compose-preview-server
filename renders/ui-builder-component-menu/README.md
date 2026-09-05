# The UI builder's component menu, before and after the catalog tree

Committed evidence for the insert panel in
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
— the list a component is dragged or added onto the canvas from.

Before this change the panel was one flat list under three headings taken from the component's
**role**: `Scaffolds`, `Containers`, `Composables`. That is what a slot will *accept*, not what a
person is looking for, so `Containers` held a tab row, a card, a dialog and a plain `Row` — and a
component with variants had none of them, because the catalog's variant property was reachable only
by inserting the component and editing it in the inspector.

After it, the panel is the same tree the published catalog draws: the catalog's own families as
collapsible headings with a count, the components under each, and a component's variants as rows
under it — each with its own Add, and the catalog's first value marked `default`.

| file | what it is |
| --- | --- |
| `menu.before.png` | the panel grouped by role, as it shipped |
| `menu.after.png` | the same panel grouped by the catalog's families, with counts and twisties |
| `menu.variants.after.png` | the panel filtered to `filled` — a variant name, not any component's |

All three are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
(`UiBuilderEditorChromePreview` and `UiBuilderComponentVariantsPreview`), so the next change to the
panel moves the evidence with it rather than leaving it stale.
