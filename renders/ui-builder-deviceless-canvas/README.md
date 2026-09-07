# The deviceless canvas, before and after

Committed evidence for the deviceless canvas — several top-level items in one design, drawn and
exported as one spaced column. The why is
[`docs/design/UI_BUILDER_DEVICELESS_CANVAS.md`](../../docs/design/UI_BUILDER_DEVICELESS_CANVAS.md).

| file | what it is |
| --- | --- |
| `deviceless-canvas.before.png` | the same document through the old root loop: three items stacked in one `Box`, so only the last one is visible, top-left, on no ground |
| `deviceless-canvas.after.png` | the canvas: the items spaced 24 dp apart, centred across the frame, on the theme's ground |
| `deviceless-canvas-editor.after.png` | the editor around it — the **Add to canvas** switch in the insert panel, the three items as layers, the selection on the third |

All three are `composePreviewRender` output for the previews in
[`DevicelessCanvasPreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/DevicelessCanvasPreview.kt),
so `./gradlew :ui-builder:composePreviewRender -PcomposePreview.filter=DevicelessCanvas`
reproduces them and the visual-diff bot compares the next change to the arrangement.

Two notes on what "before" means here.

The **before** picture was produced by disabling the canvas branch in `UiBuilderSurface` and
rendering the same fixture — it is what the old root loop did with several roots, not a state a user
could reach: before this change the collaboration reducer, the service and both export gates all
refused a second root ([#429](https://github.com/yschimke/compose-preview-server/issues/429)). That
refusal is exactly why the stacking was never seen.

For the same reason the **editor has no before**. The document
([`deviceless-canvas-v1.json`](../../docs/design/fixtures/ui-builder/deviceless-canvas-v1.json))
could not be created, and the switch that produces one is part of this change.
