package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The editor can author `toggle` and `set`; the exporter could not emit them.
 *
 * A control built with either exported to `TODO("Unsupported action …")`, which throws the moment
 * anyone presses it. The preview ran the action and the export did not, which is the worst shape
 * for a builder whose whole claim is that what you see is the Compose you get.
 */
class ComposeExportActionTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val fixture =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun literal(type: String, value: JsonPrimitive) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  private fun documentWith(actions: List<JsonObject>): UiBuilderDocument {
    val button =
      UiBuilderNode(
        id = "toggle-button",
        componentId = "m3/button",
        properties = JsonObject(mapOf("style" to literal("enum", JsonPrimitive("filled")))),
        slots = mapOf("content" to listOf("toggle-label")),
        eventBindings = JsonObject(mapOf("click" to JsonArray(actions))),
      )
    val label =
      UiBuilderNode(
        id = "toggle-label",
        componentId = "m3/text",
        properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive("Press")))),
      )
    return fixture.copy(
      roots = listOf(button.id),
      nodes = mapOf(button.id to button, label.id to label),
      stateVariables =
        JsonObject(
          mapOf(
            "expanded" to
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("value"),
                  "valueType" to JsonPrimitive("bool"),
                  "initialValue" to JsonPrimitive(false),
                  "persistence" to JsonPrimitive("preview"),
                )
              )
          )
        ),
    )
  }

  private fun exportOf(actions: List<JsonObject>): String {
    val result = CapabilityComposeCodeExporter.export(documentWith(actions), catalog)
    return assertNotNull(result.source, result.diagnostics.joinToString { it.message })
  }

  @Test
  fun `toggle flips the boolean the document declares`() {
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
          )
        )
      )

    assertTrue(
      source.contains("expanded = !expanded"),
      source.lineSequence().first { "onClick" in it },
    )
    assertFalse(
      source.contains("TODO("),
      "a toggle used to export as a TODO that throws when pressed",
    )
  }

  @Test
  fun `set is an assignment, and its value keeps the declared type`() {
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("expanded"),
              "value" to JsonPrimitive(true),
            )
          )
        )
      )

    // Unquoted: the exporter declares `expanded` from its boolean initial value, so a quoted
    // "true" would not compile against it.
    assertTrue(source.contains("expanded = true"), source)
    assertFalse(source.contains("expanded = \"true\""), source)
  }

  @Test
  fun `every action in a handler is emitted, not just the first`() {
    // `eventBindings` is a list because a handler runs its actions in order and as a unit, which
    // is how the renderer dispatches it. Exporting only the head shipped a button that did less
    // than the preview showed.
    val source =
      exportOf(
        listOf(
          JsonObject(
            mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("expanded"))
          ),
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("set"),
              "variable" to JsonPrimitive("expanded"),
              "value" to JsonPrimitive(false),
            )
          ),
        )
      )

    assertTrue(source.contains("expanded = !expanded; expanded = false"), source)
  }

  @Test
  fun `a toggle against a non-boolean variable refuses rather than emitting broken source`() {
    val document =
      documentWith(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive("caption"))
            )
          )
        )
        .let { base ->
          base.copy(
            stateVariables =
              JsonObject(
                mapOf(
                  "caption" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("text"),
                        "initialValue" to JsonPrimitive("hello"),
                        "persistence" to JsonPrimitive("preview"),
                      )
                    )
                )
              )
          )
        }
    val source = assertNotNull(CapabilityComposeCodeExporter.export(document, catalog).source)

    // `!caption` would not compile. A refusal is the honest output.
    assertTrue(source.contains("TODO(\"toggle needs a boolean state variable\")"), source)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
