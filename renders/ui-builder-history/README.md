# The History dock

Committed evidence for the operation history in
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
— what has been done to the design, and which of it undo would take back.

Before this the editor had an undo button and no way to find out what it would undo. On a design one
person is editing that is uncomfortable; on one two people are editing it misleads, because undo
walks *your own* commands and the change on screen is very often somebody else's.

| file | what it is |
| --- | --- |
| `history-dock.after.png` | the dock open over a session that has done three things and undone one |

There is no `.before.png`: the panel did not exist, and a picture of the rail without it says nothing
a reader could check.

`history-dock.after.png` is `composePreviewRender` output for `UiBuilderHistoryDockPreview` in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so the next change to this surface moves the evidence with it rather than leaving it stale.

What to look at, and why each is in the shot:

- **three shapes of change.** A modifier chain (`layout  padding 16 16 16 16 · was none`), a property
  (`text  Search podcasts · was Search for a podcast`) and the screen itself (`density  2.0 · was
  1.0`) — the last belonging to no node, so its row has nothing to select.
- **both markers at once.** "Undo takes this back" and "Redo puts this back" are only in the panel
  together after an undo, which is why the seeded session ends with one.
- **the row names its node as the canvas names it now.** The layout change at revision 109 reads
  "Changed the layout of Search podcasts" although the node was called "Search for a podcast" when it
  happened: the row is a way back to a node on the canvas, and the line under it carries the old
  value.

The session is seeded through `initialEdits`, which applies the listed events through the same
reducer a person's edits go through. A fresh editor has no history, so without it this preview would
draw the one state that says nothing about the panel.
