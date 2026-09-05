# UI builder — five components the catalog was not advertising

`m3/center-aligned-top-app-bar`, `m3/list-item`, `m3/primary-tab-row`, `m3/tab` and
`shape/colour-dot` each had a renderer branch, a Compose emitter and an entry in the exporter's field
table. No capability catalog declared any of them, so the palette could not offer one, the inspector
had nothing to show for one, and a document that used one was `UNKNOWN_COMPONENT` to the validator —
including the checked-in Confetti design, which pins this very catalog and uses all five.

`advertised.after.png` is `CatalogUnadvertisedComponentsPreview`: four `InsertComponent` events —
the same event the palette dispatches — into a column, with no hand-editing after them. Top to
bottom: the top app bar with its seeded `Title`, the tab row with three tabs and the first selected,
the list item with its headline over supporting text, and the colour dot. `m3/tab` has no cell of its
own because it is not dropped on its own: it arrives inside the tab row, which is what its starter
content is for.

## There is no before image

Before this change none of these could be inserted at all, so the before frame is an empty canvas.
What the change is worth is better read from the three values the export was dropping, which are
asserted in `UnadvertisedComponentTest` rather than shown here: a top app bar's container colours, a
list item's leading accent bar, and a tab row's selected index — each drawn on the canvas and
discarded on the way to Kotlin, on components nobody could insert, so nobody found out.
