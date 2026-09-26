package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An A2UI catalog's JSON export is its A2UI messages, in every build; nothing else changes. */
class A2uiJsonExportExecutorTest {
  private val a2ui =
    CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID)
      )
      .listCatalogs()
      .single()

  private val seed =
    UiBuilderNewDesignSeed.document(
        designId = "a2ui-seed",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
        templateId = UiBuilderNewDesignSeed.A2UI_TEMPLATE,
        catalogRevision = a2ui.benchmark.catalogRevision,
        nativeRuntimeId = a2ui.benchmark.nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(
              File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
                .readText()
            )
            .jsonObject,
      )
      .toDesignDocumentV1()

  private val passedThrough =
    ExportArtifactV1(ExportFormatV1.JSON, "text/plain", ExportEncodingV1.UTF8, "delegate", "")

  private fun request(format: ExportFormatV1, catalog: CatalogCapabilityV1 = a2ui) =
    RevisionPinnedUiBuilderExport(
      AuthenticatedUiBuilderActor("operator"),
      seed.id,
      seed.revision,
      "digest",
      seed,
      catalog,
      format,
    )

  @Test
  fun `an A2UI design's JSON is its createSurface and updateComponents messages`() {
    val artifact = A2uiJsonExportExecutor {
      fail("an A2UI JSON export must not reach the delegate")
    }
      .export(request(ExportFormatV1.JSON))

    assertEquals(ExportFormatV1.JSON, artifact.format)
    assertEquals(ExportEncodingV1.UTF8, artifact.encoding)
    assertTrue(artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR })
    val lines = artifact.content.trimEnd().lines().map { Json.parseToJsonElement(it) as JsonObject }
    assertEquals(
      listOf("createSurface", "updateComponents"),
      lines.map { line -> line.keys.single { it != "version" } },
    )
    assertEquals(
      "Column",
      lines
        .last()
        .getValue("updateComponents")
        .jsonObject
        .getValue("components")
        .jsonArray
        .first()
        .jsonObject
        .getValue("component")
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `other formats and other catalogs pass through untouched`() {
    val executor = A2uiJsonExportExecutor { passedThrough }
    assertSame(passedThrough, executor.export(request(ExportFormatV1.COMPOSE)))
    val m3 =
      CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog"))
        .listCatalogs()
        .single()
    assertSame(passedThrough, executor.export(request(ExportFormatV1.JSON, m3)))
  }
}
