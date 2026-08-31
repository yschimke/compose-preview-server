package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.uibuilder.service.PackagedUiBuilderRenderBundle
import ee.schimke.composeai.uibuilder.service.UiBuilderRenderPort
import ee.schimke.composeai.uibuilder.service.UiBuilderRenderRequest
import java.nio.file.Path

/** Server-side adapter from the UI-builder runtime's narrow port to the existing render host. */
internal class ServeUiBuilderRenderPort private constructor(private val host: ServeRenderHost) :
  UiBuilderRenderPort {
  override val supportsSvg: Boolean = host.hasSvgExport

  override fun renderPng(request: UiBuilderRenderRequest): ByteArray =
    when (
      val outcome = host.render(PackagedUiBuilderRenderBundle.PREVIEW_ID, request.overrides())
    ) {
      is RenderOutcome.Ok -> outcome.png
      RenderOutcome.NotFound -> error("packaged UI-builder preview is missing")
      RenderOutcome.Busy -> error("UI-builder renderer is busy")
      is RenderOutcome.Failed -> error(outcome.reason)
    }

  override fun renderSvg(request: UiBuilderRenderRequest): ByteArray =
    when (
      val outcome = host.renderSvg(PackagedUiBuilderRenderBundle.PREVIEW_ID, request.overrides())
    ) {
      is SvgOutcome.Ok -> outcome.svg
      SvgOutcome.NotFound -> error("packaged UI-builder SVG producer is unavailable")
      is SvgOutcome.Failed -> error(outcome.reason)
    }

  override fun close() = host.close()

  private fun UiBuilderRenderRequest.overrides(): PreviewOverrides =
    PreviewOverrides(
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      localeTag = localeTag,
      fontScale = fontScale,
      namedOverrides =
        mapOf(
          PackagedUiBuilderRenderBundle.DOCUMENT_OVERRIDE_KEY to
            PreviewOverrideValue.StringValue(encodedDocument)
        ),
    )

  companion object {
    fun open(
      root: Path,
      onLog: (String) -> Unit = { System.err.println("[ui-builder renderer] $it") },
    ): ServeUiBuilderRenderPort {
      val bundle = PackagedUiBuilderRenderBundle.copyTo(root)
      val generation = bundle.parent
      val state =
        ServeBundleDaemon.materialize(
          bundleFile = bundle.toFile(),
          destDir = generation.resolve("runtime").toFile(),
          system = "ui-builder-renderer",
          onLog = onLog,
        ) ?: error("could not materialize packaged UI-builder renderer bundle")
      require(state.previews.any { it.id == PackagedUiBuilderRenderBundle.PREVIEW_ID }) {
        "packaged UI-builder preview id is missing"
      }
      return ServeUiBuilderRenderPort(
        ServeRenderHost.open(
          descriptorPath = state.descriptor,
          workspaceRoot = state.workspaceRoot,
          workspaceName = state.workspaceName,
          previews = state.previews,
          label = "UI builder renderer",
          onLog = onLog,
        )
      )
    }

    internal fun forHost(host: ServeRenderHost): ServeUiBuilderRenderPort =
      ServeUiBuilderRenderPort(host)
  }
}
