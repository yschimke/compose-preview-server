# UI builder — the dialog and the two pickers

Committed evidence for three components the catalog did not have: `m3/dialog`, `m3/date-picker` and
`m3/time-picker`.

`dialog-and-pickers.after.png` is `CatalogDialogAndPickersPreview`, whose document is built by the
reducer rather than authored: each cell is one `InsertComponent` — the same event the palette
dispatches — followed by the property edits an operator would make in the inspector. Left to right,
top to bottom:

| cell | what it is |
| --- | --- |
| dialog | `m3/dialog` as inserted, holding the starter content it seeds: a title, supporting text, and Cancel beside OK as text buttons |
| date picker | `m3/date-picker` with `mode = picker` — Material's calendar |
| date input | the same component with `mode = input`, which is `DisplayMode.Input`, the typed date field |
| time dial | `m3/time-picker` with `mode = dial`, `is24Hour = true` |
| time input | the same component with `mode = input`, `is24Hour = false` — Material's `TimeInput`, with the AM/PM selector |

## There is no before image, and that is the point

Before this change the palette had no dialog and no picker at all. Dropping one was not something
the builder could do — `m3/dialog` was not a component id anywhere, and a design needing a date had
to be drawn out of a card, a column and some text. The "before" frame would be an empty canvas, so
the honest evidence is what the components look like now and what an insert produces without any
hand-editing.

## What this render also checks

Every value in it is pinned by the document. Material's own defaults are the clock: an unpinned
`rememberDatePickerState` opens on the current month and rings today's cell, and an unpinned
`rememberTimePickerState` starts at the current time — so if the pinning ever came out, this render
would start changing by itself and the visual diff would report it on the next unrelated pull
request. `May 16, 2024` and `10:30` are the design's own, not this morning's.

The date input's value and its `mm/dd/yyyy` placeholder overlap in the render. That is Material's own
drawing of an unfocused date input carrying a value, not something this change introduced.
