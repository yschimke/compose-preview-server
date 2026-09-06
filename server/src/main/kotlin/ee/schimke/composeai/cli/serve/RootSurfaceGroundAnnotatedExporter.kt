package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.RootSurfaceGround
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.toUiBuilderDocument

/**
 * The served export, with [RootSurfaceGround]'s notice on every artifact it applies to.
 *
 * A wrapper rather than a line in each executor, because the notice is about the *document* and not
 * about a format: a PNG whose ground is the theme's, an SVG with the same ground, and Kotlin whose
 * root `Surface` wraps its content are three pictures of one fact. Attached here, at the seam every
 * format passes through, it is said once and cannot be said differently.
 *
 * A warning, not a refusal: the artifact is right, it is the author's reading of it that the
 * property led astray (compose-preview-server #485). The editor's problems panel carries the same
 * text, so a person at the canvas and an agent reading the export are told the same thing.
 */
internal class RootSurfaceGroundAnnotatedExporter(private val delegate: UiBuilderExportExecutor) :
  UiBuilderExportExecutor {

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    val artifact = delegate.export(request)
    val notice =
      RootSurfaceGround.diagnose(request.document.toUiBuilderDocument()) ?: return artifact
    return artifact.copy(
      diagnostics =
        artifact.diagnostics +
          ExportDiagnosticV1(
            severity = DiagnosticSeverityV1.WARNING,
            code = RootSurfaceGround.CODE,
            message = notice.message,
            nodeId = notice.nodeId,
          )
    )
  }
}
