package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.LovelaceConfigExporter
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

/** This host selects the JSON lane; the builder owns its content and validation. */
class LovelaceJsonExportExecutorTest {
  private val catalog =
    CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog"))
      .listCatalogs()
      .single()
      .newBuilder()
      .also { it.statusSemantics = buildJsonObject { put("platform", "lovelace") } }
      .build()
  private val document =
    LovelaceConfigExporter.import(
        Json.parseToJsonElement(
            """{"views":[{"type":"sections","sections":[{"cards":[{"type":"custom:test","future":{"x":1}}]}]}]}"""
          )
          .jsonObject,
        "dashboard",
        buildJsonObject {
          put("systemId", "lovelace")
          put("catalogRevision", catalog.benchmark.catalogRevision)
          put("capabilityDigest", "candidate")
          put("nativeRuntimeId", catalog.benchmark.nativeRuntimeId)
        },
      )
      .toDesignDocumentV1()
  private val delegated =
    ExportArtifactV1(ExportFormatV1.JSON, "text/plain", ExportEncodingV1.UTF8, "delegate", "")

  private fun request(
    format: ExportFormatV1 = ExportFormatV1.JSON,
    catalog: CatalogCapabilityV1 = this.catalog,
  ) =
    RevisionPinnedUiBuilderExport(
      AuthenticatedUiBuilderActor("operator"),
      document.id,
      document.revision,
      "pinned-hash",
      document,
      catalog,
      format,
    )

  @Test
  fun `Lovelace JSON delegates lowering to the shared exporter with revision evidence`() {
    val expected =
      assertIs<LovelaceConfigExporter.Result.Emitted>(LovelaceConfigExporter.export(document))
    val artifact = LovelaceJsonExportExecutor {
      fail("Lovelace JSON must use the dedicated lane")
    }
      .export(request())
    assertEquals(expected.source, artifact.content)
    assertEquals(document.revision.toString(), artifact.servedRevision())
    assertEquals("application/json; charset=utf-8", artifact.mediaType)
    assertEquals(ExportEncodingV1.UTF8, artifact.encoding)
    assertEquals(
      MessageDigest.getInstance("SHA-256").digest(artifact.content.toByteArray()).joinToString("") {
        "%02x".format(it)
      },
      artifact.contentDigest,
    )
    assertTrue(artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR })
    assertTrue(artifact.diagnostics.single().message.contains("pinned-hash"))
  }

  @Test
  fun `unrelated catalogs and formats retain the original executor`() {
    val executor = LovelaceJsonExportExecutor { delegated }
    assertSame(delegated, executor.export(request(ExportFormatV1.COMPOSE)))
    val m3 = catalog.newBuilder().also { it.statusSemantics = JsonObject(emptyMap()) }.build()
    assertSame(delegated, executor.export(request(catalog = m3)))
  }

  @Test
  fun `unsupported input reports shared refusal without producing partial content`() {
    val invalid = document.copy(roots = emptyList())
    val expected =
      assertIs<LovelaceConfigExporter.Result.Refused>(LovelaceConfigExporter.export(invalid))
    val artifact = LovelaceJsonExportExecutor {
      fail("must not delegate")
    }
      .export(request().copy(document = invalid))
    assertEquals("", artifact.content)
    assertEquals(
      expected.reasons,
      artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }.map { it.message },
    )
  }
}
