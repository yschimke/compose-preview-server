package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.RemoteDocumentJsonExporter
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest
import java.util.Base64

/** Routes document exports through the shared lowering and the offline compiler publication. */
internal class RemoteDocumentExportExecutor(
  private val delegate: UiBuilderExportExecutor,
  private val compiler: ((String) -> ByteArray)? =
    if (compilerAvailable) RemoteComposeJson::compile else null,
) : UiBuilderExportExecutor {
  val supportsBinary: Boolean
    get() = compiler != null

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (request.format !in RemoteDocumentExportSupport.formats) return delegate.export(request)
    require(
      request.document.id == request.designId && request.document.revision == request.revision
    )
    val binary = request.format == RemoteDocumentExportSupport.documentFormat
    val diagnostics = mutableListOf<ExportDiagnosticV1>()
    val bytes =
      when (val lowered = RemoteDocumentJsonExporter.export(request.document)) {
        is RemoteDocumentJsonExporter.Result.Refused -> {
          diagnostics +=
            lowered.reasons.map {
              ExportDiagnosticV1(DiagnosticSeverityV1.ERROR, "REMOTE_EXPORT_UNSUPPORTED", it)
            }
          byteArrayOf()
        }
        is RemoteDocumentJsonExporter.Result.Emitted -> {
          if (!binary) lowered.source.toByteArray(Charsets.UTF_8)
          else if (compiler == null) {
            diagnostics +=
              ExportDiagnosticV1(
                DiagnosticSeverityV1.ERROR,
                "REMOTE_COMPILER_UNAVAILABLE",
                "This host needs the compiler supporting ${RemoteDocumentJsonExporter.INTEGER_PROFILE}.",
              )
            byteArrayOf()
          } else {
            try {
              compiler.invoke(lowered.source)
            } catch (failure: RemoteComposeJsonException) {
              diagnostics +=
                ExportDiagnosticV1(
                  DiagnosticSeverityV1.ERROR,
                  "REMOTE_COMPILE_FAILED",
                  failure.message ?: "The Remote Compose compiler refused this document.",
                )
              byteArrayOf()
            }
          }
        }
      }
    diagnostics +=
      ExportDiagnosticV1(
        DiagnosticSeverityV1.INFO,
        "REVISION_PINNED_REMOTE_EXPORT",
        "Exported design ${request.designId} revision ${request.revision} (${request.documentHash}).",
      )
    return ExportArtifactV1(
      format = request.format,
      mediaType = if (binary) "application/octet-stream" else "application/json; charset=utf-8",
      encoding = if (binary) ExportEncodingV1.BASE64 else ExportEncodingV1.UTF8,
      content =
        if (binary) Base64.getEncoder().encodeToString(bytes) else bytes.toString(Charsets.UTF_8),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics = diagnostics,
    )
  }

  companion object {
    // Probe the actual compiler API, avoiding a release-number guess or reflection. The released
    // floor rejects integerExpression; the staged profile emits ordinary document operations.
    val compilerAvailable: Boolean by lazy {
      runCatching {
        RemoteComposeJson.compile(
          """{"compilerProfile":"${RemoteDocumentJsonExporter.INTEGER_PROFILE}","root":{"box":{"children":[{"integerExpression":{"name":"probe","value":"1+1"}}]}}}"""
        )
      }
        .isSuccess
    }
  }
}
