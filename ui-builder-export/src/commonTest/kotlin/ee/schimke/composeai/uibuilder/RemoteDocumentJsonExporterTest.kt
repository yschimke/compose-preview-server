package ee.schimke.composeai.uibuilder

import kotlin.test.*
import kotlinx.serialization.json.*

internal fun remoteJsonSelectionFixture(
  values: List<JsonPrimitive> = listOf(JsonPrimitive(10), JsonPrimitive(20)),
  density: Int = 1,
): UiBuilderDocument {
  val kind = if (values.first().booleanOrNull != null) "bool" else "int"
  val ids = values.indices.map { "case$it" }
  val selection =
    StateSelection(
      buildJsonObject {
        put("type", "state")
        put("variable", "page")
      },
      ids.zip(values).toMap(),
      "fallback",
    )
  val click = buildJsonArray {
    if (kind == "bool") {
      add(
        buildJsonObject {
          put("type", "toggle")
          put("variable", "page")
        }
      )
    } else {
      add(
        buildJsonObject {
          put("type", "set")
          put("variable", "page")
          put("value", values.first())
        }
      )
      add(
        buildJsonObject {
          put("type", "set")
          put("variable", "page")
          put("value", values.last())
        }
      )
    }
  }
  val root =
    UiBuilderNode(
      "switch",
      "layout/box",
      properties = buildJsonObject { put(SHOW_BY_STATE, selection.encode()) },
      modifiers =
        buildJsonArray {
          add(buildJsonObject { put("type", "fillMaxSize") })
          add(
            buildJsonObject {
              put("type", "padding")
              listOf("startDp", "topDp", "endDp", "bottomDp").forEach { put(it, 8) }
            }
          )
        },
      slots = mapOf("children" to ids + "fallback"),
      eventBindings = buildJsonObject { put("click", click) },
    )
  val children =
    (ids + "fallback").mapIndexed { index, id ->
      val color =
        if (index == 0) "#FFFF0000" else if (id == "fallback") "#FF0000FF" else "#FF00FF00"
      UiBuilderNode(
        id,
        "layout/box",
        modifiers =
          buildJsonArray {
            add(buildJsonObject { put("type", "fillMaxSize") })
            add(
              buildJsonObject {
                put("type", "background")
                putJsonObject("color") {
                  put("type", "color")
                  put("value", color)
                }
              }
            )
          },
      )
    }
  return UiBuilderDocument(
    "ui-builder-design-v1",
    "selection",
    "Selection",
    1,
    JsonObject(emptyMap()),
    buildJsonObject {
      put("widthDp", 100)
      put("heightDp", 100)
      put("density", density)
    },
    buildJsonObject {
      putJsonObject("page") {
        put("valueType", kind)
        put("initialValue", values.first())
      }
    },
    listOf("switch"),
    (listOf(root) + children).associateBy { it.id },
  )
}

class RemoteDocumentJsonExporterTest {
  @Test
  fun `selection keeps the authored box and ordered actions`() {
    val document = remoteJsonSelectionFixture()
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(document)
      )
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(
      RemoteDocumentJsonExporter.INTEGER_PROFILE,
      json["compilerProfile"]!!.jsonPrimitive.content,
    )
    val root = json["root"]!!.jsonArray.last().jsonObject
    assertEquals("box", root["type"]!!.jsonPrimitive.content)
    val children = root["children"]!!.jsonArray
    assertEquals("integerExpression", children.first().jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("stateLayout", children.last().jsonObject["type"]!!.jsonPrimitive.content)
    val actions = root["modifiers"]!!.jsonArray.last().jsonObject["onClick"]!!.jsonArray
    assertEquals(listOf(10, 20), actions.map { it.jsonObject["value"]!!.jsonPrimitive.int })
    assertEquals(exported, RemoteDocumentJsonExporter.export(document))
  }

  @Test
  fun `dimensions and padding use the captured density`() {
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(remoteJsonSelectionFixture(density = 2))
      )
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(200, json["header"]!!.jsonObject["width"]!!.jsonPrimitive.int)
    val padding =
      json["root"]!!
        .jsonArray
        .last()
        .jsonObject["modifiers"]!!
        .jsonArray[1]
        .jsonObject["padding"]!!
        .jsonObject
    assertEquals(16.0, padding["start"]!!.jsonPrimitive.double)
    val original = remoteJsonSelectionFixture()
    val fractional =
      original.copy(
        environment =
          buildJsonObject {
            put("widthDp", 101)
            put("heightDp", 100)
            put("density", 1.5)
          }
      )
    val rounded =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(fractional)
      )
    assertEquals(
      152,
      Json.parseToJsonElement(rounded.source)
        .jsonObject["header"]!!
        .jsonObject["width"]!!
        .jsonPrimitive
        .int,
    )
  }

  @Test
  fun `boolean export identifies its integer host representation`() {
    val exported =
      assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
        RemoteDocumentJsonExporter.export(
          remoteJsonSelectionFixture(listOf(JsonPrimitive(false), JsonPrimitive(true)))
        )
      )
    assertEquals(mapOf("page" to "bool"), exported.stateKinds)
    val json = Json.parseToJsonElement(exported.source).jsonObject
    assertEquals(
      0,
      json["root"]!!
        .jsonArray[0]
        .jsonObject["integers"]!!
        .jsonObject["page"]!!
        .jsonObject["value"]!!
        .jsonPrimitive
        .int,
    )
  }

  @Test
  fun `unsupported catalog calls and modifier fields are located`() {
    val original = remoteJsonSelectionFixture()
    val unsupported = original.nodes.getValue("case0").copy(componentId = "remote-m3/button")
    val result =
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
        RemoteDocumentJsonExporter.export(
          original.copy(nodes = original.nodes + ("case0" to unsupported))
        )
      )
    assertTrue(result.reasons.any { "nodes.case0.componentId" in it && "recipe" in it })
    val rounded =
      original.nodes
        .getValue("case0")
        .copy(
          modifiers =
            buildJsonArray {
              add(
                buildJsonObject {
                  put("type", "background")
                  put("color", "#FF000000")
                  put("shape", "circle")
                }
              )
            }
        )
    assertTrue(
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
          RemoteDocumentJsonExporter.export(
            original.copy(nodes = original.nodes + ("case0" to rounded))
          )
        )
        .reasons
        .any { "modifiers[0].shape" in it }
    )
  }

  @Test
  fun `malformed selection and cycles return diagnostics rather than partial source`() {
    val original = remoteJsonSelectionFixture()
    val root = original.nodes.getValue("switch")
    val malformed = root.copy(properties = buildJsonObject { put(SHOW_BY_STATE, "bad") })
    assertIs<RemoteDocumentJsonExporter.Result.Refused>(
      RemoteDocumentJsonExporter.export(
        original.copy(nodes = original.nodes + ("switch" to malformed))
      )
    )
    val cyclic =
      root.copy(properties = JsonObject(emptyMap()), slots = mapOf("children" to listOf("switch")))
    assertIs<RemoteDocumentJsonExporter.Result.Refused>(
      RemoteDocumentJsonExporter.export(
        original.copy(nodes = original.nodes + ("switch" to cyclic))
      )
    )
  }

  @Test
  fun `mutable strings require a compiler mapping that preserves identity`() {
    val original = remoteJsonSelectionFixture()
    val declarations =
      JsonObject(
        original.stateVariables +
          ("label" to
            buildJsonObject {
              put("valueType", "string")
              put("initialValue", "Ready")
            })
      )
    assertTrue(
      assertIs<RemoteDocumentJsonExporter.Result.Refused>(
          RemoteDocumentJsonExporter.export(original.copy(stateVariables = declarations))
        )
        .reasons
        .any { "stateVariables.label" in it && "text IDs" in it }
    )
  }
}
