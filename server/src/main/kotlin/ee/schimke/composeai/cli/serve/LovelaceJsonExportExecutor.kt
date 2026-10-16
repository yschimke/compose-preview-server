package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.LovelaceConfigExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest

/**
 * Host JSON lane for Lovelace. Authentication, pinning and capability checks stay in the service.
 */
internal class LovelaceJsonExportExecutor(private val delegate: UiBuilderExportExecutor) :
  UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (
      request.format != ExportFormatV1.JSON ||
        UiBuilderCatalogPlatform.from(request.catalog.statusSemantics) !=
          UiBuilderCatalogPlatform.LOVELACE
    )
      return delegate.export(request)

    val diagnostics = mutableListOf<ExportDiagnosticV1>()
    val bytes =
      when (
        val lowered = runCatching {
          LovelaceConfigExporter.export(request.document)
        }
          .getOrElse {
            LovelaceConfigExporter.Result.Refused(
              listOf("document: the Lovelace export could not read the design: ${it.message}")
            )
          }
      ) {
        is LovelaceConfigExporter.Result.Emitted -> lowered.source.toByteArray(Charsets.UTF_8)
        is LovelaceConfigExporter.Result.Refused -> {
          diagnostics +=
            lowered.reasons.map {
              ExportDiagnosticV1(DiagnosticSeverityV1.ERROR, "LOVELACE_EXPORT_UNSUPPORTED", it)
            }
          byteArrayOf()
        }
      }
    diagnostics +=
      ExportDiagnosticV1(
        DiagnosticSeverityV1.INFO,
        "REVISION_PINNED_LOVELACE_EXPORT",
        "Exported design ${request.designId} revision ${request.revision} (${request.documentHash}).",
      )
    return ExportArtifactV1(
      format = ExportFormatV1.JSON,
      mediaType = "application/json; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = bytes.toString(Charsets.UTF_8),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics = diagnostics,
    )
  }
}
