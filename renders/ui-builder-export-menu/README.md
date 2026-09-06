# The UI builder's Export menu, and the toolbar before it had one

Committed evidence for the Export menu in
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)
and the rows it lists, defined in
[`UiBuilderExportActions.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderExportActions.kt).

Before this change the server exported a design as PNG and Figma-compatible SVG, and the editor
offered neither: the only way to hold the picture was a protocol request or an MCP call. After it,
the top bar carries **Export** beside **Code**, with the catalog viewer's own three verbs per
format — Copy, Copy link, Download — and every link is *live*: the address renders the current
committed revision on each request.

| file | what it is |
| --- | --- |
| `toolbar.before.png` | the editor as it opened: Code, then the overflow, nothing to get the picture out |
| `toolbar.after.png` | the same document with the Export button between Code and the overflow |
| `export-menu.after.png` | the menu's rows, drawn flat: Copy SVG / PNG, Copy SVG / PNG link, Download SVG / PNG |

All three are `composePreviewRender` output —
`UiBuilderCanvasForwardPreview` in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt)
and `UiBuilderExportMenuPreview` in
[`UiBuilderExportPreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderExportPreview.kt)
— so the next change to either surface moves the evidence with it. The rows are previewed flat
because a `DropdownMenu` is a popup a static render does not capture.
