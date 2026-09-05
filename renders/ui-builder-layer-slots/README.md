# UI builder — slots in the layers panel, and a drag that lands

Committed evidence for the layers-panel slot lines and the layer drag.

Both images are the left column of `UiBuilderEditorChromePreview`, which renders the editor over the
frozen Jetcaster fixture at revision 108.

| file | what it is |
| --- | --- |
| `layers.before.png` | the panel on `main` — every row at one indent per level, no slot named anywhere |
| `layers.after.png` | the same rows with the slot each group sits in named above it, and what it holds |
| `editor-chrome.after.png` | the whole preview, for the panel in context |

## What the before image shows

`search-bar`, `snackbar-host` and `main-content` are drawn at the same indent under `main-scaffold`,
which reads as three siblings. They are not: they are the only child of `topBar`, of `snackbarHost`
and of `content`. Nothing in the panel said so, and the consequence was not only cosmetic — the
drag it advertised ("Drag vertically to reorder") moved a node one step among the children it
already sat with, and each of these three is the only child of its slot, so there was no step to
take. Every drag on this screen was a no-op, silently.

## What the after image shows

`mainPane 1/1`, `topBar 1/1`, `inputField 1/1`, `placeholder 1/1`, `leadingIcon 1/1` — the slot
name, and how much of its declared capacity is used. An empty slot draws a line too, reading
`empty`, because it is a destination a drop can aim at and no node row names it. A container with
one slot and something in it draws no line: the indentation already says it.

The drag now resolves against the row the pointer is over: the top half of a node row lands the
layer before it, the bottom half after it, *in that row's slot*, and a slot line lands it first in
that slot. A landing that will be refused is drawn in the error colour and named in the panel
heading before the release, rather than evaporating on it.
