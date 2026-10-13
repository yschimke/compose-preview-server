package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An A2UI catalog's JSON export is its A2UI messages, in every build; nothing else changes. */
class A2uiJsonExportExecutorTest {
  // Captured from a2ui-catalog's published files, as served after the Kotlin catalog was deleted.
  private fun publishedFile(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/catalog-cutover/a2ui-catalog/$name")).use {
      GZIPInputStream(it).bufferedReader().readText()
    }

  private val a2ui by lazy {
    val record = Json.decodeFromString<ComponentRecordFile>(publishedFile("components.json.gz"))
    val exports =
      ExportCapabilitiesV1.Builder()
        .also {
          it.composeCode = true
          it.svg = false
          it.png = false
        }
        .build()
    val result =
      PublishedUiBuilderCatalog.compose(publishedFile("ui-builder.json.gz"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the published A2UI catalog must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    val published = (result as PublishedUiBuilderCatalog.Result.Composed).catalog
    CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID),
        published = mapOf(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID to published),
      )
      .listCatalogs()
      .single()
  }

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
