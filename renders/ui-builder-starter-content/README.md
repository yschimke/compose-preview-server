# UI builder — what a container arrives holding

Committed evidence for starter content: the default children a container is seeded with when it is
inserted from the palette.

Both images are `StarterContentInsertPreview`, a 1280×260dp desktop preview whose document is not
authored — every child under the row is produced by the same `InsertComponent` event the palette
dispatches, through the same reducer, against the same catalog. Left to right: icon button, button,
filter chip, card, search bar, lazy column, each dropped into its own 190×200dp cell.

| file | what it is |
| --- | --- |
| `insert.before.png` | six inserts on `main` — required slots filled generically, optional slots empty |
| `insert.after.png` | the same six inserts with starter content |

## What the before image shows

Six containers nobody would have drawn:

- the **icon button** holds a clock, and so does the **button** — `iconKey` has no seeded value, so
  it takes the first entry of its enum (`accessTime`), and `m3/button`'s content slot accepts text
  and icons alike with the icon branch tested first, so a button arrived holding an icon rather than
  a label;
- the **filter chip** and the **card** both read `New text`, the generic fill for a text slot;
- the **search bar** is an empty pill: its search field is there, with no placeholder and no
  magnifier, because both of those slots are optional;
- the **lazy column** is nothing at all. Its `items` slot has no minimum, so the component that was
  just inserted draws zero pixels.

## What the after image shows

The same six inserts, each holding what its shape implies: a heart, a `Button` label, a `Filter`
chip, a card with a title over supporting text, a search field reading `Search` behind a magnifier,
and a list of three items. Every seeded node is an ordinary node — selectable, editable, deletable —
so a wrong guess costs one keystroke, where an empty container cost the same subtree retyped on
every insert.

The rules, the table and what is deliberately left unseeded are in
[`docs/design/UI_BUILDER_EDITOR_OPERATIONS.md`](../../docs/design/UI_BUILDER_EDITOR_OPERATIONS.md#starter-content)
and in `StarterContent.kt`'s own documentation.
