# Zoom, the inspector's default, and the layer menu

Committed evidence for
[`UiBuilderEditor`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt),
captured from the hosted editor at `?mode=interactive-editor` by
[`capture-evidence.spec.mjs`](../../preview-harness/capture-evidence.spec.mjs) at 1440x900.

| file | what it is |
| --- | --- |
| `wide.before.png` / `wide.after.png` | a 1920x1200 window, which is the case the framing is for: the design stopped at 1:1 with a third of the workspace empty, and now fills it at 127% |
| `canvas.before.png` / `canvas.after.png` | the editor as it opens at 1440x900, where the design was already being shrunk to fit — the change here is the zoom pill in the corner and the toolbar's verbs moving into the overflow |
| `inspector.before.png` / `inspector.after.png` | the properties of one text layer: every declaration the catalog allows, versus the four the node carries plus a search and an add |
| `layer-menu.before.png` / `layer-menu.after.png` | a right-click on a layer row: nothing, versus the verbs that act on it — which is why the row of icons above the canvas is now one overflow |

Regenerate with `EVIDENCE_SUFFIX=after npx playwright test -c capture-evidence.config.mjs` from
`preview-harness/`, against a `:ui-builder:wasmFrontendDist` build of the branch.
