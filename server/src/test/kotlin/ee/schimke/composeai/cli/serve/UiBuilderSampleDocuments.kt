package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Local copies of two sample designs this server's tests build. compose-ui-builder 3.124.0
// (compose-ui-builder#997) moved them out of the published `compose-preview-ui-builder-export`
// into its unpublished `:ui-builder-export-fixtures`, and asked hosts to keep their own. Copied
// from `WearWidgetSamples.kt` and `WearScreenSample.kt` at v3.124.0 with only the parameters these
// tests pass, so the documents, and the exports pinned against them, are unchanged.

/** The Wear widget sample's Hello widget: `RemoteText` centred on a primary background. */
internal fun helloWidgetUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument {
  val size = WearWidgetScaffoldSize.Small
  val scaffoldId = "wear-widget-${size.name.lowercase()}"
  val nodes =
    listOf(
      UiBuilderNode(
        id = scaffoldId,
        componentId = size.componentId,
        properties = JsonObject(mapOf("background" to colorToken("primary"))),
        modifiers = JsonArray(emptyList()),
        slots = mapOf("content" to listOf("hello-content")),
      ),
      UiBuilderNode(
        id = "hello-content",
        componentId = "layout/box",
        properties = JsonObject(emptyMap()),
        modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
        slots = mapOf("children" to listOf("hello-text")),
      ),
      UiBuilderNode(
        id = "hello-text",
        componentId = REMOTE_TEXT_COMPONENT_ID,
        properties =
          JsonObject(
            mapOf(
              "text" to literal("string", JsonPrimitive("Hello, World!")),
              "color" to colorToken("onPrimary"),
              "fontSize" to literal("float", JsonPrimitive(20)),
            )
          ),
        modifiers =
          JsonArray(
            listOf(
              JsonObject(
                mapOf("type" to JsonPrimitive("align"), "alignment" to JsonPrimitive("center"))
              )
            )
          ),
        slots = emptyMap(),
      ),
    )
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Hello widget · ${size.label}",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(scaffoldId),
    nodes = nodes.associateBy(UiBuilderNode::id),
  )
}

/**
 * A Wear list screen: a `ScreenScaffold` over a `TransformingLazyColumn` of title cards under a
 * frozen `10:10` status strip, identical to wear-m3-catalog's `TransformingLazyColumn` component.
 */
internal fun wearScreenUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument {
  val title = "Activity"
  val rows = (1..6).map { "Session $it" to "${it * 4} min" }
  val rowNodes = rows.flatMapIndexed { index, (rowTitle, subtitle) ->
    listOf(
      UiBuilderNode(
        id = "row-$index",
        componentId = "wear-m3/card",
        properties = JsonObject(mapOf("variant" to literal("enum", JsonPrimitive("title")))),
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        slots = mapOf("content" to listOf("row-$index-lines")),
      ),
      UiBuilderNode(
        id = "row-$index-lines",
        componentId = "layout/column",
        properties =
          JsonObject(mapOf("horizontalAlignment" to literal("enum", JsonPrimitive("start")))),
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        slots = mapOf("children" to listOf("row-$index-title", "row-$index-subtitle")),
      ),
      wearScreenText("row-$index-title", rowTitle, "titleMedium", "onSurface", fontSizeSp = 14f),
      wearScreenText(
        "row-$index-subtitle",
        subtitle,
        "bodySmall",
        "onSurfaceVariant",
        fontSizeSp = 13f,
      ),
    )
  }
  val nodes =
    listOf(
      UiBuilderNode(
        id = "wear-screen",
        componentId = "wear-m3/screen-scaffold",
        properties =
          JsonObject(
            mapOf(
              "timeText" to literal("string", JsonPrimitive("10:10")),
              "scrollIndicator" to literal("bool", JsonPrimitive(true)),
            )
          ),
        modifiers = JsonArray(emptyList()),
        slots = mapOf("content" to listOf("wear-list"), "edgeButton" to emptyList()),
      ),
      UiBuilderNode(
        id = "wear-list",
        componentId = "wear-m3/transforming-lazy-column",
        properties = JsonObject(mapOf("verticalSpacingDp" to literal("float", JsonPrimitive(4f)))),
        modifiers = JsonArray(emptyList()),
        slots = mapOf("items" to listOf("list-header") + rows.indices.map { "row-$it" }),
      ),
      UiBuilderNode(
        id = "list-header",
        componentId = "wear-m3/list-header",
        properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive(title)))),
        modifiers = JsonArray(emptyList()),
        slots = emptyMap(),
      ),
    ) + rowNodes
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "$title · Wear screen",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("wear-screen"),
    nodes = nodes.associateBy(UiBuilderNode::id),
  )
}

private fun wearScreenText(
  id: String,
  text: String,
  style: String,
  color: String,
  fontSizeSp: Float,
): UiBuilderNode =
  UiBuilderNode(
    id = id,
    componentId = "wear-m3/text",
    properties =
      JsonObject(
        mapOf(
          "text" to literal("string", JsonPrimitive(text)),
          "style" to literal("typographyToken", JsonPrimitive(style)),
          "color" to colorToken(color),
          "textAlign" to literal("enum", JsonPrimitive("start")),
          "fontSizeSp" to literal("float", JsonPrimitive(fontSizeSp)),
        )
      ),
    modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
    slots = emptyMap(),
  )

private fun literal(type: String, value: JsonPrimitive): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

private fun colorToken(token: String): JsonObject = literal("colorToken", JsonPrimitive(token))

private fun modifier(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))
