package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The same gate used by the browser code pane and the server's record-driven export. */
class BehaviorScreenExportTest {
  @org.junit.jupiter.api.BeforeEach
  fun requireExperimentalBuild() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
  }

  private val record = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<ComponentRecordFile>(
      File("../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").readText()
    )

  private fun state(type: StateValueTypeV1, initial: JsonPrimitive) =
    StateVariableV1(
      type = StateVariableTypeV1.VALUE,
      valueType = type,
      initialValue = initial,
      persistence = StatePersistenceV1.PREVIEW,
    )

  private fun document(): DesignDocumentV1 =
    ScreenGeneratorScreenFixture.document()
      .copy(
        id = "state-actions",
        title = "State actions",
        revision = 0,
        catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
        stateVariables =
          linkedMapOf(
            "label" to state(StateValueTypeV1.STRING, JsonPrimitive("Ready")),
            "enabled" to state(StateValueTypeV1.BOOLEAN, JsonPrimitive(true)),
            // An integer JSON spelling is legal for a declared decimal.
            "progress" to state(StateValueTypeV1.DECIMAL, JsonPrimitive(0)),
          ),
        roots = listOf("column"),
        nodes =
          linkedMapOf(
            "column" to
              DesignNodeV1(
                "column",
                "layout/column",
                slots = mapOf("children" to listOf("button", "progress")),
              ),
            "button" to
              DesignNodeV1(
                "button",
                "m3/button",
                properties =
                  mapOf("style" to EnumValueV1("filled"), "enabled" to StateValueV1("enabled")),
                slots = mapOf("content" to listOf("label")),
                eventBindings =
                  mapOf(
                    "click" to
                      listOf(
                        SetValueActionV1("label", JsonPrimitive("Updated")),
                        ToggleActionV1("enabled"),
                        SetValueActionV1("progress", JsonPrimitive(1)),
                      )
                  ),
              ),
            "label" to
              DesignNodeV1("label", "m3/text", properties = mapOf("text" to StateValueV1("label"))),
            "progress" to
              DesignNodeV1(
                "progress",
                "m3/progress-indicator",
                properties =
                  mapOf("variant" to EnumValueV1("linear"), "progress" to StateValueV1("progress")),
              ),
          ),
      )

  @Test
  fun `state and ordered handlers export exactly the compiled interaction fixture`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog"))
    assertNull(catalogs.validate(document(), catalogs.listCatalogs().single()))
    val result = ScreenExportGate.export(document(), record)
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(result, result.toString()).source
    val fixture = File("../docs/design/fixtures/ui-builder/state-actions.kt.txt")
    // A build output is convenient when intentionally regenerating this compiled fixture.
    File("build/behavior-export/StateActions.kt").apply {
      parentFile.mkdirs()
      writeText(source)
    }
    if (System.getenv("UPDATE_UI_BUILDER_BEHAVIOR_FIXTURE") == "true") fixture.writeText(source)
    assertEquals(fixture.readText(), source)
    val designFile = File("../docs/design/fixtures/ui-builder/state-actions.document.json")
    val designJson =
      Json {
          prettyPrint = true
          encodeDefaults = true
        }
        .encodeToString(DesignDocumentV1.serializer(), document()) + "\n"
    if (System.getenv("UPDATE_UI_BUILDER_BEHAVIOR_FIXTURE") == "true")
      designFile.writeText(designJson)
    assertEquals(designFile.readText(), designJson)
  }

  @Test
  fun `a handler with an undeclared write identifies the event and variable`() {
    val doc = document()
    val button =
      doc.nodes
        .getValue("button")
        .copy(eventBindings = mapOf("click" to listOf(ToggleActionV1("missing"))))
    val result =
      assertIs<ScreenExportGate.Outcome.Refused>(
        ScreenExportGate.export(doc.copy(nodes = doc.nodes + ("button" to button)), record)
      )
    assertTrue(
      result.reasons.any { "eventBindings.click" in it && "missing" in it },
      result.reasons.toString(),
    )
  }

  @Test
  fun `a type-invalid toggle is refused by the shared generator`() {
    val doc = document()
    val button =
      doc.nodes
        .getValue("button")
        .copy(eventBindings = mapOf("click" to listOf(ToggleActionV1("label"))))
    val result =
      assertIs<ScreenExportGate.Outcome.Refused>(
        ScreenExportGate.export(doc.copy(nodes = doc.nodes + ("button" to button)), record)
      )
    assertTrue(
      result.reasons.any { "label" in it && "kotlin.Boolean" in it },
      result.reasons.toString(),
    )
  }

  @Test
  fun `normalizing state names cannot silently merge two declarations`() {
    val doc =
      document()
        .copy(
          stateVariables =
            mapOf(
              "a-b" to state(StateValueTypeV1.STRING, JsonPrimitive("first")),
              "a_b" to state(StateValueTypeV1.STRING, JsonPrimitive("second")),
            ),
          roots = listOf("label"),
          nodes =
            mapOf(
              "label" to
                DesignNodeV1(
                  "label",
                  "m3/text",
                  properties = mapOf("text" to StringValueV1("Label")),
                )
            ),
        )
    val result = assertIs<ScreenExportGate.Outcome.Refused>(ScreenExportGate.export(doc, record))
    assertTrue(result.reasons.isNotEmpty())
  }
}
