package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * Translate catalog-wide module identities at the executable host boundary. Keep the outer ids
 * distinct even when two module bundles carry the same local preview id.
 */
internal class ServeModuleLiveHost(
  private val host: ServeHost,
  private val localIds: Map<String, String>,
) : ServeHost by host {
  private fun local(id: String): String = localIds[id] ?: id

  override val previews: List<ServePreview>
    get() =
      if (localIds.isEmpty()) host.previews
      else
        localIds.flatMap { (outer, inner) ->
          host.previews.filter { it.id == inner }.map { it.copy(id = outer) }
        }

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    host.render(local(previewId), overrides)

  override fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    host.renderLeased(local(previewId), overrides)

  override fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
    host.cachedRender(local(previewId), overrides)

  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    host.renderFailureLatch(local(previewId), overrides)

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    host.renderSvg(local(previewId), overrides)

  override fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    host.renderSvgForWeb(local(previewId), overrides)

  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    host.renderScrollPng(local(previewId), overrides)

  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    host.renderScrollSvg(local(previewId), overrides)

  override fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome =
    host.renderSlots(local(previewId), overrides)

  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome =
    host.renderA11y(local(previewId), overrides)

  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ): AnnotationsOutcome = host.renderAnnotations(local(previewId), overrides, layers)

  override fun hasRemoteComposeDoc(previewId: String): Boolean =
    host.hasRemoteComposeDoc(local(previewId))

  override fun remoteComposeDoc(previewId: String): ByteArray? =
    host.remoteComposeDoc(local(previewId))

  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? =
    host.subscribeStream(local(previewId), overrides, codec, maxFps, onUnavailable, onFrame)
}
