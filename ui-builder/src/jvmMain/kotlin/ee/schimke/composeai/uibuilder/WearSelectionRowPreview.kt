package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wear's three selection rows on a Wear screen, as the canvas draws them.
 *
 * The picture is the argument for the components existing. Upstream's `CheckboxButton`,
 * `SwitchButton` and `RadioButton` are **rows** — a filled, full-width container with a label, an
 * optional secondary label and the control at the end — so borrowing `m3/checkbox` would have put a
 * 20dp square on a watch and called it the same component. What is borrowed here is the drawing,
 * not the identity: the canvas has no Wear Compose to link, so the row is assembled out of Material
 * 3 pieces in Wear's shape, and the generated Kotlin names the Wear composable.
 */
@Preview(widthDp = 200, heightDp = 100)
@Composable
fun WearSelectionRowsPreview() {
  UiBuilderSurface(document = wearSelectionRowPreviewDocument, editorOverlay = false)
}

private val wearSelectionRowPreviewDocument: UiBuilderDocument by lazy {
  fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = "wear-m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to
              JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
          )
        ),
    )

  fun flag(name: String, value: Boolean) =
    JsonObject(
      mapOf(
        name to JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(value)))
      )
    )

  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "wear-selection-rows-preview",
    title = "Wear selection rows",
    revision = 0,
    catalogPin =
      JsonObject(
        mapOf(
          "systemId" to JsonPrimitive("wear-m3"),
          "catalogRevision" to JsonPrimitive("wear-screen-scaffold-v1"),
          "capabilityDigest" to JsonPrimitive("candidate"),
          "nativeRuntimeId" to JsonPrimitive("candidate"),
        )
      ),
    environment =
      JsonObject(
        mapOf(
          "widthDp" to JsonPrimitive(192),
          "heightDp" to JsonPrimitive(100),
          "density" to JsonPrimitive(1.0),
          "theme" to JsonPrimitive("dark"),
          "dynamicColor" to JsonPrimitive(false),
          "locale" to JsonPrimitive("en-US"),
          "fontScale" to JsonPrimitive(1.0),
          "layoutDirection" to JsonPrimitive("ltr"),
          "windowPosture" to JsonPrimitive("flat"),
          "browserZoomPercent" to JsonPrimitive(100),
          "fixedTime" to JsonPrimitive("2024-05-16T12:00:00Z"),
          "animations" to JsonPrimitive("settled"),
          "networkAccess" to JsonPrimitive(false),
        )
      ),
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("screen"),
    nodes =
      mapOf(
        "screen" to
          UiBuilderNode(
            id = "screen",
            componentId = "wear-m3/screen-scaffold",
            properties =
              JsonObject(
                mapOf(
                  "timeText" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("string"),
                        "value" to JsonPrimitive("10:30"),
                      )
                    )
                )
              ),
            slots = mapOf("content" to listOf("list")),
          ),
        "list" to
          UiBuilderNode(
            id = "list",
            componentId = "wear-m3/transforming-lazy-column",
            slots = mapOf("items" to listOf("notifications", "vibrate", "loud")),
          ),
        "notifications" to
          UiBuilderNode(
            id = "notifications",
            componentId = "wear-m3/checkbox-button",
            properties = flag("checked", true),
            slots =
              mapOf(
                "label" to listOf("notifications-label"),
                "secondaryLabel" to listOf("notifications-secondary"),
              ),
          ),
        "notifications-label" to text("notifications-label", "Alerts"),
        "notifications-secondary" to text("notifications-secondary", "On"),
        "vibrate" to
          UiBuilderNode(
            id = "vibrate",
            componentId = "wear-m3/switch-button",
            properties = flag("checked", false),
            slots = mapOf("label" to listOf("vibrate-label")),
          ),
        "vibrate-label" to text("vibrate-label", "Vibrate"),
        "loud" to
          UiBuilderNode(
            id = "loud",
            componentId = "wear-m3/radio-button",
            properties = flag("selected", true),
            slots = mapOf("label" to listOf("loud-label")),
          ),
        "loud-label" to text("loud-label", "Loud"),
      ),
  )
}
