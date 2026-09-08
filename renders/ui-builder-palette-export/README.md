# UI builder — the palette says what the Compose export cannot write

Committed evidence for the insert panel in
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt),
after [#494](https://github.com/yschimke/compose-preview-server/pull/494) closed the components an
ordinary screen needed and left the question of how a person finds out about the rest before
building with them ([#488](https://github.com/yschimke/compose-preview-server/issues/488),
[#477](https://github.com/yschimke/compose-preview-server/issues/477)).

| file | what it is |
| --- | --- |
| `palette.before.png` | `UiBuilderEditorChromePreview` at `main`: every row alike, whether or not the export can write it |
| `palette.after.png` | the same preview: `Supporting pane`, `Search bar`, `Search input field`, `Tab` and `Tab row` faded, each with code-crossed-out beside its Add |
| `palette.pickers.after.png` | `UiBuilderUnexportablePalettePreview`, the panel narrowed to "picker": two uncovered components with their variant rows, which dim with them and carry no mark of their own |

The answer comes from the component record the export reads —
`docs/design/fixtures/ui-builder/m3-catalog-components-v1.json`, embedded into the editor for the
problems panel — and from nothing else, so the palette cannot say a component exports when the
export would refuse it. `PaletteExportStatusTest` holds the two to the same list. A catalog the
record was not authored for (`wear-m3`, `remote-m3`) gets no mark on any row rather than a wrong one.

The Add stays enabled on a faded row. The canvas draws every one of these and the PNG and SVG
exports carry them; only the Kotlin is missing, and the mark says that rather than taking the
component away.

All three are `composePreviewRender` output for previews in
[`UiBuilderEditorChromePreview.kt`](../../ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt),
so the next change to the panel moves the evidence with it.
