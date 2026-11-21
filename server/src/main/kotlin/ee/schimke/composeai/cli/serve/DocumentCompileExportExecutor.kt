package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.BuiltInCatalogDocumentFormats
import ee.schimke.composeai.uibuilder.export.CatalogDocumentFormat
import ee.schimke.composeai.uibuilder.export.CatalogDocumentFormats
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest
import java.util.Base64

/**
 * Compiles one document format's content (as its `CatalogDocumentFormat` writes it) to a Remote
 * Compose document, through the format's own service. Throws, with a sentence for the client, when
 * the service refuses.
 */
internal fun interface DocumentCompiler {
  fun compile(content: String): ByteArray
}

/**
 * The `.rc` of a design whose catalog declares a document format this host compiles: the format's
 * own content (written here, by the format, so it needs neither the renderer nor anything else in
 * [delegate]'s chain), then [compilers]' bytes for it. The builder advertises `remoteDocument` for
 * exactly these catalogs (`CurrentM3UiBuilderCatalogExecutor.Builder.documentCompilers`), so every
 * other request passes through to [delegate] untouched.
 */
internal class DocumentCompileExportExecutor(
  private val delegate: UiBuilderExportExecutor,
  private val compilers: Map<String, DocumentCompiler>,
  private val formats: CatalogDocumentFormats = BuiltInCatalogDocumentFormats.ALL,
) : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (request.format != ExportFormatV1.RC) return delegate.export(request)
    val declared =
      formats.supported(request.catalog.statusSemantics) ?: return delegate.export(request)
    val compiler = compilers[declared.format.id] ?: return delegate.export(request)

    val content =
      when (
        val written =
          declared.format.export(request.document.toUiBuilderDocument(), declared.policy)
      ) {
        is CatalogDocumentFormat.Result.Emitted ->
          when (declared.format.encoding) {
            ExportEncodingV1.UTF8 -> written.content
            ExportEncodingV1.BASE64 ->
              Base64.getDecoder().decode(written.content).toString(Charsets.UTF_8)
          }
        is CatalogDocumentFormat.Result.Refused ->
          throw IllegalArgumentException(written.reasons.joinToString("; "))
      }
    val bytes = compiler.compile(content)
    return ExportArtifactV1(
      format = ExportFormatV1.RC,
      mediaType = "application/octet-stream",
      encoding = ExportEncodingV1.BASE64,
      content = Base64.getEncoder().encodeToString(bytes),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics =
        listOf(
          ExportDiagnosticV1(
            DiagnosticSeverityV1.INFO,
            "REVISION_PINNED_REMOTE_EXPORT",
            "Compiled design ${request.designId} revision ${request.revision} " +
              "(${request.documentHash}) from ${declared.format.label}.",
          ),
        ),
    )
  }
}
