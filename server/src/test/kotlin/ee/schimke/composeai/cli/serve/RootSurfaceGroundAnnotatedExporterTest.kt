package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.RootSurfaceGround
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The served export carries [RootSurfaceGround]'s notice, whatever format was asked for, and only
 * when there is something to notice.
 */
class RootSurfaceGroundAnnotatedExporterTest {

  private val provenance =
    ExportDiagnosticV1(
      severity = DiagnosticSeverityV1.INFO,
      code = "REVISION_PINNED_DAEMON_RENDER",
      message = "Rendered design d revision 7 (hash) through the packaged preview.",
    )

  private val delegate = UiBuilderExportExecutor { request ->
    ExportArtifactV1(
      format = request.format,
      mediaType = "image/png",
      encoding = ExportEncodingV1.BASE64,
      content = "",
      contentDigest = "digest",
      diagnostics = listOf(provenance),
    )
  }

  private val exporter = RootSurfaceGroundAnnotatedExporter(delegate)

  @Test
  fun `a coloured root that wraps its content gets the warning, after the provenance`() {
    val artifact = exporter.export(request(wrappingColouredRoot(), ExportFormatV1.PNG))

    assertEquals(2, artifact.diagnostics.size, artifact.diagnostics.toString())
    assertEquals(provenance, artifact.diagnostics.first())
    val warning = artifact.diagnostics.last()
    assertEquals(DiagnosticSeverityV1.WARNING, warning.severity)
    assertEquals(RootSurfaceGround.CODE, warning.code)
    assertEquals("surface", warning.nodeId)
  }

  @Test
  fun `the notice is about the document, so every format carries it`() {
    ExportFormatV1.entries.forEach { format ->
      val artifact = exporter.export(request(wrappingColouredRoot(), format))
      assertEquals(
        listOf(RootSurfaceGround.CODE),
        artifact.diagnostics.map { it.code } - provenance.code,
        format.name,
      )
    }
  }

  @Test
  fun `a root that fills the frame passes the artifact through untouched`() {
    val artifact =
      exporter.export(request(ScreenGeneratorScreenFixture.document(), ExportFormatV1.PNG))
    assertEquals(listOf(provenance), artifact.diagnostics)
  }

  /** The fixture's root, given a `containerColor` and stripped of its `fillMaxSize`. */
  private fun wrappingColouredRoot(): DesignDocumentV1 {
    val document = ScreenGeneratorScreenFixture.document()
    val root = document.nodes.getValue("surface")
    return document.copy(
      nodes =
        document.nodes +
          ("surface" to
            root.copy(
              properties = root.properties + ("containerColor" to ColorValueV1("#313338")),
              modifiers = emptyList(),
            ))
    )
  }

  private fun request(document: DesignDocumentV1, format: ExportFormatV1) =
    RevisionPinnedUiBuilderExport(
      actor = AuthenticatedUiBuilderActor("tester"),
      designId = document.id,
      revision = document.revision,
      documentHash = "hash",
      document = document,
      catalog =
        CatalogCapabilityV1(
          schema = "compose-catalog-capabilities/v1",
          benchmark =
            CatalogBenchmarkV1("test", "source", "test-catalog", "candidate", "candidate"),
          components = emptyList(),
          exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
        ),
      format = format,
    )
}
