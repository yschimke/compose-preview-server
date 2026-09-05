# UI builder — checkbox and switch

`selection-controls.after.png` is `CatalogSelectionControlsPreview`: four cells from two new
components, built by the reducer. Left to right — a checkbox as inserted, the same checkbox with
`checked` set to false in the inspector, a switch as inserted, and a switch turned off the same way.

Both arrive checked because that is their starter content: Material draws an unchecked box as an
empty square and an off switch as a grey pill, and a palette drop that reads as neither control is
what starter content exists to prevent. Turning one off is one click.

There is no before image: neither component existed in the catalog, so the before frame is an empty
canvas.

## What the two off cells are really testing

Each is one `checked` edit through the inspector, which is the round trip that breaks quietly. A
property the canvas stops reading looks exactly like a property nobody edited.
