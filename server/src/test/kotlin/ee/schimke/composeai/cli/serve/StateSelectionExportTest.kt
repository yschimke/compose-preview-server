package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class StateSelectionExportTest {
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

  private fun document(): DesignDocumentV1 =
    ScreenGeneratorScreenFixture.document()
      .copy(
        id = "state-selection",
        title = "State selection",
        revision = 0,
        catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
        stateVariables =
          mapOf(
            "page" to
              StateVariableV1(
                type = StateVariableTypeV1.VALUE,
                valueType = StateValueTypeV1.INTEGER,
                initialValue = JsonPrimitive(10),
                persistence = StatePersistenceV1.PREVIEW,
              )
          ),
        roots = listOf("choice"),
        nodes =
          mapOf(
            "choice" to
              DesignNodeV1(
                "choice",
                "layout/box",
                properties =
                  mapOf(
                    SHOW_BY_STATE to
                      ObjectValueV1(
                        mapOf(
                          "selector" to StateValueV1("page"),
                          "cases" to
                            ObjectValueV1(
                              mapOf("first" to IntegerValueV1(10), "second" to IntegerValueV1(20))
                            ),
                          "fallback" to StringValueV1("other"),
                        )
                      )
                  ),
                modifiers =
                  listOf(
                    PaddingModifierV1(
                      JsonPrimitive(12),
                      JsonPrimitive(12),
                      JsonPrimitive(12),
                      JsonPrimitive(12),
                    )
                  ),
                slots = mapOf("children" to listOf("first", "second", "other")),
              ),
            "first" to
              DesignNodeV1(
                "first",
                "m3/text",
                properties = mapOf("text" to StringValueV1("First")),
              ),
            "second" to
              DesignNodeV1(
                "second",
                "m3/text",
                properties = mapOf("text" to StringValueV1("Second")),
              ),
            "other" to
              DesignNodeV1(
                "other",
                "m3/text",
                properties = mapOf("text" to StringValueV1("Other")),
              ),
          ),
      )

  @Test
  fun `selection is catalog valid and exported through the shared generator without losing branches`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog"))
    assertNull(catalogs.validate(document(), catalogs.listCatalogs().single()))
    val result = ScreenExportGate.export(document(), record)
    // The opt-in local build proves the new upstream model before a release exists. The released
    // floor must give a located refusal rather than quietly emit all three children.
    if (System.getenv("VERIFY_LOCAL_STATE_SELECTION") == "true") {
      val source = assertIs<ScreenExportGate.Outcome.Emitted>(result, result.toString()).source
      assertTrue("when (page.value)" in source, source)
      assertTrue("10 ->" in source && "20 ->" in source && "else ->" in source, source)
      assertTrue("padding(start = 12.dp" in source, source)
      File("build/behavior-export/StateSelection.kt").apply {
        parentFile.mkdirs()
        writeText(source)
      }
    } else {
      val refusal = assertIs<ScreenExportGate.Outcome.Refused>(result)
      assertTrue(
        refusal.reasons.any { "choice" in it && "state-selection support" in it },
        refusal.toString(),
      )
    }
  }

  @Test
  fun `invalid hidden case is located before export`() {
    val original = document()
    val choice = original.nodes.getValue("choice")
    val properties = choice.properties.getValue(SHOW_BY_STATE) as ObjectValueV1
    val invalid =
      original.copy(
        nodes =
          original.nodes +
            ("choice" to
              choice.copy(
                properties =
                  mapOf(
                    SHOW_BY_STATE to
                      properties.copy(
                        fields =
                          properties.fields +
                            ("cases" to
                              ObjectValueV1(
                                mapOf(
                                  "first" to IntegerValueV1(10),
                                  "second" to StringValueV1("20"),
                                )
                              ))
                      )
                  )
              ))
      )
    val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog"))
    val issue = assertNotNull(catalogs.validate(invalid, catalogs.listCatalogs().single()))
    assertTrue(issue.toString().contains(SHOW_BY_STATE), issue.toString())
    assertIs<ScreenExportGate.Outcome.Refused>(ScreenExportGate.export(invalid, record))
  }
}
