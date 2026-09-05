# The editor's chrome, before and after the docks

Committed evidence for
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
— the toolbar, the panels and the space left over for the design.

Before this change the canvas got whatever was left after a 300 dp catalog and a 360 dp inspector,
neither of which could be closed, and the row above it held eighteen text buttons of equal weight:
`Duplicate` and `Cut` beside `Help` and `Reconnect`, most of them greyed for the whole of any
session that never selected anything.

| file | what it is |
| --- | --- |
| `editor.before.png` | the chrome as it shipped: both panels nailed open, every verb in the toolbar |
| `editor.after.png` | the same document with both docks open, now switched from the rails |
| `editor.canvas-forward.after.png` | how the editor now opens — docks closed, the design with the window |
| `chrome-mock.png` | the proposal, authored as a design in the builder before any of it was written |

All four are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
and [`UiBuilderChromeMockPreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderChromeMockPreview.kt),
so the next change to any of these surfaces moves the evidence with it rather than leaving it
stale. `chrome-mock.png` in particular is not a picture of a UI: it is
`docs/design/fixtures/ui-builder/ui-builder-chrome-mock-v1.json` replayed through the reducer and
drawn by `UiBuilderSurface`, the renderer the Wasm canvas and the production export already share.
