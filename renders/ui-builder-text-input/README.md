# UI builder — text field and radio button

`text-input.after.png` is `CatalogTextInputPreview`, built by the reducer: a filled text field as
inserted, the same component with `variant` set to `outlined`, and a radio button selected and not.

Both text fields are **one component**. `TextField` and `OutlinedTextField` differ in nothing a
design authors, so `variant` picks between them — the choice `m3/card` already makes for its three.

Both arrive with a label and a placeholder, and the radio arrives selected: an empty text field is a
rounded rectangle that says nothing about what it is for, and an unselected radio is a circle.

There is no before image; neither component existed in the catalog.

## The part the picture cannot show

Where the typing goes. A text field's `value` binds to a **declared state variable**, so the canvas
reads it live and the export writes back to it — `value = searchQuery`,
`onValueChange = { searchQuery = it }`. An unbound field emits an empty handler rather than a local
`remember`, because a generated screen whose field silently kept its own text would look like it
worked and would not be the design anybody drew. Both are asserted in `TextInputTest`.
