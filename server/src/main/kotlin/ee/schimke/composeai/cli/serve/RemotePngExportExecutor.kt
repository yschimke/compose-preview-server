package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.RecordFreeExport
import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

/** PNGs of authored Remote layouts play the same compiled bytes as document export. */
internal class RemotePngExportExecutor(
  private val delegate: UiBuilderExportExecutor,
  private val renderer: UiBuilderRenderPort?,
) : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (
      request.format != ExportFormatV1.PNG ||
        UiBuilderCatalogPlatform.from(request.catalog.statusSemantics) !=
          UiBuilderCatalogPlatform.REMOTE_COMPOSE ||
        RecordFreeExport.applies(request.document) ||
        request.document.nodes.values.any { it.componentId in EMBEDDED_CONTENT }
    )
      return delegate.export(request)

    val format =
      RemoteDocumentExportSupport.documentFormat
        ?: return failure(
          "REMOTE_COMPILER_UNAVAILABLE",
          "This host cannot export Remote documents.",
        )
    val compiled = delegate.export(request.copy(format = format))
    val errors = compiled.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
    if (errors.isNotEmpty()) return artifact(byteArrayOf(), compiled.diagnostics)
    val player =
      renderer
        ?: return failure("REMOTE_RENDER_UNAVAILABLE", "This host has no Remote PNG renderer.")
    require(compiled.encoding == ExportEncodingV1.BASE64) {
      "Remote document export must return binary bytes"
    }
    val bytes = Base64.getDecoder().decode(compiled.content)
    require(digest(bytes) == compiled.contentDigest) {
      "Remote document export digest does not match its bytes"
    }
    val image = player.renderPng(renderRequest(request, bytes))
    return artifact(
      image,
      compiled.diagnostics +
        ExportDiagnosticV1(
          DiagnosticSeverityV1.INFO,
          "REMOTE_DOCUMENT_PNG",
          "Rendered the compiled Remote document ${compiled.contentDigest} for design ${request.designId} revision ${request.revision} (${request.documentHash}).",
        ),
    )
  }

  private fun failure(code: String, message: String): ExportArtifactV1 =
    artifact(byteArrayOf(), listOf(ExportDiagnosticV1(DiagnosticSeverityV1.ERROR, code, message)))

  private fun artifact(bytes: ByteArray, diagnostics: List<ExportDiagnosticV1>): ExportArtifactV1 =
    ExportArtifactV1(
      ExportFormatV1.PNG,
      "image/png",
      ExportEncodingV1.BASE64,
      Base64.getEncoder().encodeToString(bytes),
      digest(bytes),
      diagnostics,
    )

  companion object {
    // Imported documents and custom content already play through the packaged renderer's
    // composition lane. Keep that existing behavior until their combined JSON lowering exists.
    private val EMBEDDED_CONTENT =
      setOf("remote-compose/document", "remote-compose/inline", "remote-compose/custom")

    internal fun renderRequest(
      request: RevisionPinnedUiBuilderExport,
      bytes: ByteArray,
    ): UiBuilderRenderRequest {
      val node =
        DesignNodeV1(
          id = "remote-output",
          componentId = "remote-compose/document",
          properties =
            mapOf("documentBase64" to StringValueV1(Base64.getEncoder().encodeToString(bytes))),
          modifiers = listOf(FillMaxSizeModifierV1),
        )
      // This is a render projection only. The authored tree, revision and saved document are
      // untouched.
      val projection =
        request.document.copy(
          roots = listOf(node.id),
          nodes = mapOf(node.id to node),
          stateVariables = emptyMap(),
          assets = emptyMap(),
          tokenBindings = emptyMap(),
          components = emptyMap(),
        )
      val environment = request.document.environment
      return UiBuilderRenderRequest(
        request.designId,
        request.revision,
        request.documentHash,
        (environment.widthDp * environment.density).roundToInt(),
        (environment.heightDp * environment.density).roundToInt(),
        environment.density.toFloat(),
        environment.locale,
        environment.fontScale.toFloat(),
        projectRendererDocument(projection),
      )
    }

    private fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
