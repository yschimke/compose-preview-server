# UI builder — the rectangular widget preview in the Code pane

`WearWidgetCodeExporter` used to emit one `@Preview` per generated widget, driven by the shipped
**squircle** provider for the container size the design was authored at. The **rectangular** render
is the one recommended as the image for the widget picker editor
([#587](https://github.com/yschimke/compose-preview-server/issues/587)), so a designer who wanted it
had to hand-write a second `@Preview` — and the one they do not write is the one that ships.

The generated source is a **rendered surface in this repository**, not only a downstream artifact:
`WearWidgetCodePanePreview` (`:ui-builder`, `WearWidgetSamplePreview.kt`) draws the editor with the
Code pane open on the Hello widget, and the pane's content is this exporter's output. So this change
is UI-affecting and these are its before/after.

| File | Shows |
| --- | --- |
| `before-code-pane.png` | `origin/main`. The pane's import block ends `…SquircleSmallWidgetP…`. |
| `after-code-pane.png` | This change. `…RectangularSmallWidg…` is imported above it, and every line below shifts down one. |

Both are `WearWidgetCodePanePreview` at its own 1600×900dp canvas, rendered by `:ui-builder`'s
preview lane (`./gradlew :ui-builder:composePreviewRender --rerun
-PcomposePreview.filter=WearWidgetCodePanePreview`); the before side was rendered from a worktree at
`origin/main`.

## What the pane does not show, and where to look instead

The two `@Preview` functions themselves sit below the pane's viewport — the file is long and the
pane does not scroll in a static capture — so the visible delta here is the import. The previews
being *drawn* is a different question, and this repository cannot answer it: `androidx.glance.wear`
is an Android AAR and the frames come from the Robolectric lane in
[`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools), where the generated
file is checked in as `samples/wear-widget/…/ActivitySummaryWidget.kt` precisely so that compiling
and rendering it is covered somewhere. The two container shapes rendered side by side live in that
repository's `samples/wear-widget/renders/`.
