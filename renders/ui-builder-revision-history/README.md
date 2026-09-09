# The history bar

Committed evidence for the strip of revision thumbnails under the canvas, and the compare pane it
opens — the pictures half of the history the
[History dock](../ui-builder-history/README.md) lists in words. The design, the two timelines it sits
between, and where the pictures come from are in
[`docs/design/UI_BUILDER_REVISION_HISTORY.md`](../../docs/design/UI_BUILDER_REVISION_HISTORY.md).

| file | what it is |
| --- | --- |
| `history-bar.after.png` | the strip and the dock open together over a session that has done three things and undone one |
| `revision-compare.after.png` | two of those revisions compared, side by side, with what moved between them |

There is no `.before.png`: neither surface existed, and a picture of the canvas without them says
nothing a reader could check.

Both are `composePreviewRender` output for `UiBuilderHistoryBarPreview` and
`UiBuilderRevisionComparePreview` in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so the next change to either moves the evidence with it rather than leaving it stale —
`./gradlew :ui-builder:composePreviewRender --rerun -PcomposePreview.filter=UiBuilderHistoryBarPreview`.

What to look at in `history-bar.after.png`, and why each is in the shot:

- **a picture per revision, and they differ.** `r108 Opened`, `r109 Changed the…`, `r110 Set text
  on…`, `r111 Set density…`, `now Took back: S…` — five thumbnails of the same screen at five
  points, each rebuilt from the compensating changes the reducer already recorded and drawn through
  the renderer drawing the canvas. Nothing is stored per revision.
- **the density change is visible in the picture.** `r111` set the screen to 2.0 and its thumbnail is
  the design at that density, which is the kind of change a list of words describes and cannot show.
- **the newest row is marked.** Nothing has been picked, so the row the canvas is showing carries the
  outline — "which one am I on" is answered without opening anything.
- **the panel and the strip arrived together.** One press of History, because they are the same
  history asked two questions.

And in `revision-compare.after.png`:

- **the editing canvas is not composed at all.** A canvas that took a drop at r109 of a design at
  r112 would be editing a picture; the review pane replaces it rather than covering it.
- **both ends are marked on the strip**, in two colours — the revision being looked at and the one it
  is being compared with — and every other row offers `Compare` to become the second end.
- **the differences are read off the two documents**, not off the operations between them:
  `density: 1.0 → 2.0` and `text: Search for a podcast → Search podcasts`. The seeded session's undo
  cancels the change it took back instead of being counted as a second one.

The session is seeded through `initialEdits`, which applies the listed events through the same
reducer a person's edits go through, and the compare preview pins the two revisions it names rather
than reading whatever the seeding happens to end at.
