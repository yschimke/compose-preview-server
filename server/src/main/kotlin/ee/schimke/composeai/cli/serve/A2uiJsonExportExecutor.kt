package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.A2uiDocumentExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest

/**
 * The JSON export of a design pinned to an A2UI catalog: the A2UI v0.9 messages an agent sends to
 * draw it, as JSON Lines (compose-ui-builder's `A2uiDocumentExporter`).
 *
 * Not behind the Remote Compose authoring flag, which gates the *Remote Compose* JSON the same
 * format carries for a `remote-compose` catalog. The contract has one JSON format and one
 * capability flag (`remoteJson`); the catalog's platform decides which JSON it means, and every
 * other design falls through to [delegate] unchanged.
 */
internal class A2uiJsonExportExecutor(private val delegate: UiBuilderExportExecutor) :
  UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (
      request.format != ExportFormatV1.JSON ||
        UiBuilderCatalogPlatform.from(request.catalog.statusSemantics) !=
          UiBuilderCatalogPlatform.A2UI
    )
      return delegate.export(request)

    val diagnostics = mutableListOf<ExportDiagnosticV1>()
    val bytes =
      when (
        val lowered = runCatching {
          A2uiDocumentExporter.export(request.document.toUiBuilderDocument())
        }
          .getOrElse {
            A2uiDocumentExporter.Result.Refused(
              listOf("document: the A2UI export could not read the design: ${it.message}")
            )
          }
      ) {
        is A2uiDocumentExporter.Result.Emitted -> lowered.source.toByteArray(Charsets.UTF_8)
        is A2uiDocumentExporter.Result.Refused -> {
          diagnostics +=
            lowered.reasons.map {
              ExportDiagnosticV1(DiagnosticSeverityV1.ERROR, "A2UI_EXPORT_UNSUPPORTED", it)
            }
          byteArrayOf()
        }
      }
    diagnostics +=
      ExportDiagnosticV1(
        DiagnosticSeverityV1.INFO,
        "REVISION_PINNED_A2UI_EXPORT",
        "Exported design ${request.designId} revision ${request.revision} (${request.documentHash}).",
      )
    return ExportArtifactV1(
      format = ExportFormatV1.JSON,
      // JSON Lines: one A2UI message per line, the framing A2UI streams over a transport.
      mediaType = "application/jsonl; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = bytes.toString(Charsets.UTF_8),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics = diagnostics,
    )
  }
}
