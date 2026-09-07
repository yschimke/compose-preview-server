# The UI-builder deviceless canvas

A design is a screen until it holds a second top-level item. From the second on it is a **canvas**:
several items side by side, drawn and exported as one spaced column, on no device in particular.

This document is the *why*. The rules it states are load-bearing; the code cites it rather than
restating them.

## The mode is the shape of the document, not a flag on it

A `UiBuilderDocument` has always carried `roots: List<String>`. Until this feature it was refused
the moment it held more than one — by `CollaborationReducer.requireValidTopology` on the client, by
`PersistentUiBuilderService.validateTopology` on the server, and by both export gates.

That refusal was right for exactly as long as a second root meant *nothing*. Two disjoint trees
satisfy every placement rule the reducers check — each node reachable once, no cycles — so nothing
would have noticed; but export required exactly one root, so a design could be created, persisted,
loaded, edited and shared, and only refuse when somebody asked for Kotlin out of it. A dead end that
only a delete-everything could leave ([#429](https://github.com/yschimke/compose-preview-server/issues/429)).

**The reason to refuse was the dead end, not the count.** So the exports learned what several roots
mean, and the refusal went away.

There is deliberately **no stored `canvasMode` field**, and it is not an omission:

- `DesignEnvironmentV1` and its `EnvironmentFieldV1` / `EnvironmentChangeV1` algebra are published
  from `compose-preview-contracts` and closed. A new field is a contracts release, not a change this
  repository can make (the same wall the [reference overlay](UI_BUILDER_REFERENCE_OVERLAY.md) hits
  in its point 2).
- Every alternative is a lie told to a field that means something else, and every consumer — the
  surface, the two Kotlin exporters, the screen projection, the next reader — has to be told it
  separately.
- The shape already answers the question, honestly, and round-trips through every existing seam
  untouched: the wire, the hash, the delta, the snapshot, the stored state, the undo log.

One item is a screen. Several are a canvas. Deleting back down to one makes it a screen again, which
is what makes the mode reversible without anything having to remember it.

## What a canvas *is*, written down once

[`DevicelessCanvas`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/DevicelessCanvas.kt)
is the whole definition: the roots in a `layout/column`, spaced `CANVAS_SPACING_DP` (24 dp) apart
and centred across the frame. `withCanvasRoot()` applies it; a one-root screen passes through
unchanged.

Four consumers, one definition, so they cannot disagree about the picture:

| Consumer | What it does with a canvas |
| --- | --- |
| `UiBuilderSurface` (the editor's renderer) | Draws that column. Several roots in the old `Box` drew on top of each other. |
| `CapabilityComposeCodeExporter` | Emits `Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally)`. |
| `ScreenDocumentProjection` | Projects the synthetic column, so `ScreenGenerator` writes the same thing. |
| `validateDocumentForExport` | Refuses zero roots (`ROOT_CARDINALITY`) and nothing else about the count. |

The column is **synthetic**: it is never inserted into a stored document and no operation can
address it. Its id is `deviceless-canvas`, suffixed if the design already holds a node by that name
— `deviceless-canvas` is a name a person could reasonably have typed, and colliding would replace a
real node in the document the exporter then writes.

24 dp rather than a tighter number because the gap is what says *these are separate things*. A
canvas holding a card and a dialog 8 dp apart reads as one screen laid out badly.

### Why the projection may now choose a layout it once refused

`ScreenDocumentProjection` used to say, correctly, that choosing `Column` over `Box` for two
disjoint trees was a layout decision the document had not made and the projection must not invent.
The document makes it now: several roots *is* the canvas, and the canvas *is* that column. Emitting
it is reporting the design rather than inventing one — the distinction the projection's honesty
rests on is intact.

## How someone gets there

One route, and it is deliberate: **Add to canvas**, a switch in the insert panel under the line that
says where the next Add lands.

Off — the default, and every design's behaviour before this — an Add fills the selected layer's
first accepting slot. On, an Add places a new top-level item and the panel says so.

It is not a fallback inside the ordinary insert. An Add with no compatible slot is *refused*, and
turning that refusal into "then it becomes a second screen" would make a full scaffold silently grow
a neighbour every time somebody added a chip it had no room for. Someone asks for a canvas.

The switch is a **tool mode**, not a property of the design: it is `UiBuilderEditorState.addToCanvas`,
nothing about it is stored, shared with a collaborator or undone, and reopening the design opens it
off. What it produces — the second root — is in the document for everyone to see. A mode rather than
a second button on ~200 palette rows, in a panel 280 dp wide whose name column already gives way to
a badge.

## What the canvas is not

- **Not free positioning.** An item's place on the canvas is its order in the root list. Free x/y
  would need an offset per root, which is a wire field this repository cannot add — the same wall
  as the mode flag, and the reason the arrangement is a column rather than a board.
- **Not a second frame.** The environment's width, density, theme, locale and layout direction still
  apply: they are what the items are drawn *in*. What the Screen inspector hides on a canvas is the
  two device pickers, because "which phone is this?" is a question a board of several items has no
  answer to, and offering a preset would quietly restate one of them as the design's frame.
- **Not shown above the fold.** The canvas draws no companion frame pane. That pane answers "what
  does the device show first?", and a board of several items is not shown on a device at all.
