package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.Json

class LayoutClickExportTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val record =
    json.decodeFromString<ComponentRecordFile>(
      File("../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").readText()
    )

  private fun document() =
    json
      .decodeFromString<DesignDocumentV1>(
        File("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json")
          .readText()
      )
      .copy(
        title = "Clickable state layout",
        catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      )

  @Test
  fun `layout clicks and state selection emit the compiled interaction fixture`() {
    val result = ScreenExportGate.export(document(), record)
    if (System.getenv("VERIFY_LOCAL_LAYOUT_CLICKS") != "true") {
      val refused = assertIs<ScreenExportGate.Outcome.Refused>(result)
      assertTrue(refused.reasons.any { "action-lambda support" in it }, refused.toString())
      return
    }
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(result, result.toString()).source
    val fixture = File("../docs/design/fixtures/ui-builder/clickable-state-layout.kt.txt")
    if (System.getenv("UPDATE_UI_BUILDER_BEHAVIOR_FIXTURE") == "true") fixture.writeText(source)
    assertEquals(fixture.readText(), source)
    assertTrue("when (page.value)" in source, source)
    assertTrue(".clickable(onClick = { page.value = 20 })" in source, source)
    assertTrue(".padding(start = 24.dp" in source, source)
    assertFalse("Box(onClick" in source, source)
  }

  @Test
  fun `row and column clicks use the same modifier and retain authored modifier order`() {
    for (component in listOf("layout/row", "layout/column")) {
      val doc =
        document().let { original ->
          original.copy(
            nodes =
              original.nodes.mapValues { (id, node) ->
                if (id == "choice") node else node.copy(componentId = component)
              }
          )
        }
      val result = ScreenExportGate.export(doc, record)
      if (System.getenv("VERIFY_LOCAL_LAYOUT_CLICKS") == "true") {
        val source = assertIs<ScreenExportGate.Outcome.Emitted>(result, result.toString()).source
        assertTrue(".background(color = Color(" in source, source)
        assertTrue(source.indexOf(".background(") < source.indexOf(".clickable("), source)
        assertEquals(3, Regex("\\.clickable\\(").findAll(source).count())
      } else {
        assertIs<ScreenExportGate.Outcome.Refused>(result)
      }
    }
  }
}
