package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A deviceless canvas on the surface: three top-level items, spaced and centred.
 *
 * The one picture that says what the mode *is*. Before this change the same document was refused by
 * the reducer, the service and both export gates; the surface, had one reached it, drew all three
 * cards on top of each other in a `Box`. See
 * [`docs/design/UI_BUILDER_DEVICELESS_CANVAS.md`](../../../../../../../../docs/design/UI_BUILDER_DEVICELESS_CANVAS.md).
 *
 * Wired into the preview workflow rather than described, so the next change to the canvas
 * arrangement is diffed without anyone remembering to look. Everything it draws is fixed: a frozen
 * operations fixture replayed to a known revision, no clock, no network, no random source.
 */
@Preview(widthDp = 720, heightDp = 140)
@Composable
fun DevicelessCanvasPreview() {
  UiBuilderSurface(document = devicelessCanvasPreviewDocument, editorOverlay = false)
}

/**
 * The same canvas in the editor, with the insert panel open on the **Add to canvas** switch.
 *
 * The switch is the only route into the mode, so it is the surface a change to this feature is most
 * likely to break, and a canvas drawn without the control that produced it says half the story.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DevicelessCanvasEditorPreview() {
  UiBuilderEditor(
    document = devicelessCanvasPreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "card-outlined",
    initialComponentsOpen = true,
    initialInspectorOpen = true,
  )
}

internal val devicelessCanvasPreviewDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/deviceless-canvas-v1.json")).jsonObject
    )
    .document
}
