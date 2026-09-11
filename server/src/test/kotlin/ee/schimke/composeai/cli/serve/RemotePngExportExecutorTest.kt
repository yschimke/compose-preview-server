package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue

class RemotePngExportExecutorTest {
  private val document =
    Json.decodeFromString<DesignDocumentV1>(
      Files.readString(
        Path.of("../docs/design/evidence/ui-builder-remote-native-preview/document.json")
      )
    )
  private val remote =
    CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("remote-m3")).listCatalogs().single()

  private fun request(doc: DesignDocumentV1 = document) =
    RevisionPinnedUiBuilderExport(
      AuthenticatedUiBuilderActor("operator"),
      doc.id,
      doc.revision,
      "original-digest",
      doc,
      remote,
      ExportFormatV1.PNG,
    )

  @Test
  fun `render projection keeps environment and provenance while embedding exact document bytes`() {
    val doc =
      document.copy(
        environment =
          document.environment.copy(
            widthDp = 359,
            heightDp = 241,
            density = 1.5,
            locale = "fr-FR",
            fontScale = 1.2,
          )
      )
    val result = RemotePngExportExecutor.renderRequest(request(doc), byteArrayOf(1, 2, 3))
    assertEquals(539, result.widthPx)
    assertEquals(362, result.heightPx)
    assertEquals(1.5f, result.density)
    assertEquals("fr-FR", result.localeTag)
    assertEquals(1.2f, result.fontScale)
    assertEquals("original-digest", result.documentHash)
    assertTrue("AQID" in result.encodedDocument)
    assertTrue("remote-compose/document" in result.encodedDocument)
    assertFalse("showByState" in result.encodedDocument)
    assertEquals(4, doc.nodes.size, "Rendering must not mutate the authored hierarchy")
  }

  @Test
  fun `lowering errors remain PNG diagnostics and never invoke a fallback image renderer`() {
    assumeTrue(
      RemoteDocumentExportSupport.documentFormat != null,
      "Requires staged Remote export contracts",
    )
    val format = requireNotNull(RemoteDocumentExportSupport.documentFormat)
    val errors =
      listOf(
        ExportDiagnosticV1(
          DiagnosticSeverityV1.ERROR,
          "REMOTE_EXPORT_UNSUPPORTED",
          "nodes.choice.properties: unsupported value",
        )
      )
    val delegate = UiBuilderExportExecutor {
      assertEquals(format, it.format)
      ExportArtifactV1(format, "application/octet-stream", ExportEncodingV1.BASE64, "", "", errors)
    }
    val artifact = RemotePngExportExecutor(delegate, null).export(request())
    assertEquals(ExportFormatV1.PNG, artifact.format)
    assertEquals("image/png", artifact.mediaType)
    assertEquals("", artifact.content)
    assertEquals(errors, artifact.diagnostics)
  }

  @Test
  fun `regular Compose and imported Remote documents retain their existing picture exporter`() {
    val sentinel =
      ExportArtifactV1(
        ExportFormatV1.PNG,
        "image/png",
        ExportEncodingV1.BASE64,
        "existing",
        "digest",
      )
    val executor =
      RemotePngExportExecutor(
        UiBuilderExportExecutor {
          assertEquals(ExportFormatV1.PNG, it.format)
          sentinel
        },
        null,
      )
    val mobile = CurrentM3UiBuilderCatalogExecutor().listCatalogs().single()
    assertSame(sentinel, executor.export(request().copy(catalog = mobile)))
    val embedded = DesignNodeV1("imported", "remote-compose/document")
    assertSame(
      sentinel,
      executor.export(
        request(document.copy(roots = listOf("imported"), nodes = mapOf("imported" to embedded)))
      ),
    )
  }
}
