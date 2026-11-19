package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides

internal object WatchFaceRendererSelection {
  /**
   * Why a catalog `dwf-apk` render is refused while [ServeFeatureFlags.catalogNativeRender] is off.
   * Its own switch, not the Native pane's ([ServeFeatureFlags.catalogNativeRender]): a catalog
   * render on the Android runtime still takes the render daemon down (watchface-tools#72), so a box
   * that runs the Native pane keeps refusing this until that is fixed.
   */
  val CATALOG_NATIVE_RENDER_DISABLED_REASON: String =
    "${ServeFeatureFlags.CATALOG_NATIVE_RENDER_DISABLED}: wfRenderer " +
      "'${WatchFaceRendererIds.DWF_APK}' renders a catalog's watch face on the Android " +
      "watch-face runtime, which this server has switched off for catalog renders " +
      "(features.catalogNativeRender / ${ServeFeatureFlags.CATALOG_NATIVE_RENDER_ENV}). Use " +
      "wfRenderer '${WatchFaceRendererIds.DWF_RC}' (the Remote Compose renderer) instead."

  /**
   * Ordered watch-face renderer descriptors for [preview] on [host], listing `dwf-apk` before
   * `dwf-rc` and marking `dwf-apk` as `isDefault = true` whenever it is available (falling back to
   * `dwf-rc` when `dwf-apk` is unavailable). With [catalogNativeRender] off, `dwf-apk` is listed as
   * unavailable with [CATALOG_NATIVE_RENDER_DISABLED_REASON].
   */
  fun renderersFor(
    host: ServeHost,
    preview: ServePreview,
    catalogNativeRender: Boolean,
  ): List<WatchFaceRendererInfo> {
    if (!preview.isWatchFace) return emptyList()
    val raw =
      WatchFaceRendererRegistry.all().map { gate(it, catalogNativeRender).infoFor(host, preview) }
    if (raw.isEmpty()) return emptyList()
    val ordered = raw.sortedBy { info ->
      when (info.id) {
        WatchFaceRendererIds.DWF_APK -> 0
        WatchFaceRendererIds.DWF_RC -> 1
        else -> 2
      }
    }
    val defaultId =
      ordered.firstOrNull { it.id == WatchFaceRendererIds.DWF_APK && it.available }?.id
        ?: ordered.firstOrNull { it.available }?.id
        ?: ordered.firstOrNull { it.id == WatchFaceRendererIds.DWF_RC }?.id
        ?: ordered.first().id
    return ordered.map { it.copy(isDefault = it.id == defaultId) }
  }

  /**
   * Resolve [wfRendererId] for [preview] on [host]. When [wfRendererId] is null or blank, prefers
   * `dwf-apk` whenever available for [preview] on [host], falling back to the first available
   * backend (`dwf-rc`). Returns null when an explicit [wfRendererId] is unknown.
   *
   * With [catalogNativeRender] off, an explicit `dwf-apk` resolves to a backend that reports itself
   * unavailable and refuses every call with [CATALOG_NATIVE_RENDER_DISABLED_REASON], and the
   * default selection never picks it.
   */
  fun resolveFor(
    host: ServeHost,
    preview: ServePreview?,
    wfRendererId: String?,
    catalogNativeRender: Boolean,
  ): WatchFaceRendererBackend? {
    val normalized = wfRendererId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalized != null) {
      return WatchFaceRendererRegistry.resolve(normalized)?.let { gate(it, catalogNativeRender) }
    }
    if (preview != null && preview.isWatchFace) {
      val apk =
        WatchFaceRendererRegistry.resolve(WatchFaceRendererIds.DWF_APK)?.let {
          gate(it, catalogNativeRender)
        }
      if (apk != null && apk.infoFor(host, preview).available) {
        return apk
      }
      val available =
        WatchFaceRendererRegistry.all()
          .map { gate(it, catalogNativeRender) }
          .firstOrNull { it.infoFor(host, preview).available }
      if (available != null) {
        return available
      }
    }
    return WatchFaceRendererRegistry.resolve(null)?.let { gate(it, catalogNativeRender) }
  }

  private fun gate(backend: WatchFaceRendererBackend, catalogNativeRender: Boolean) =
    if (catalogNativeRender || backend.id != WatchFaceRendererIds.DWF_APK) backend
    else CatalogNativeRenderDisabledBackend(backend)

  /** `dwf-apk` on a box with [ServeFeatureFlags.catalogNativeRender] off: listed, never started. */
  private class CatalogNativeRenderDisabledBackend(private val delegate: WatchFaceRendererBackend) :
    WatchFaceRendererBackend {
    override val id: String = delegate.id
    override val label: String = delegate.label
    override val badgeLabel: String = delegate.badgeLabel

    override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo =
      WatchFaceRendererInfo(
        id = id,
        label = label,
        badgeLabel = badgeLabel,
        available = false,
        unavailableReason = CATALOG_NATIVE_RENDER_DISABLED_REASON,
      )

    override fun render(
      host: ServeHost,
      preview: ServePreview,
      overrides: PreviewOverrides,
    ): RenderOutcome = RenderOutcome.Failed(CATALOG_NATIVE_RENDER_DISABLED_REASON)

    override fun complications(
      host: ServeHost,
      preview: ServePreview,
      overrides: PreviewOverrides,
    ): WatchFaceComplicationsOutcome =
      WatchFaceComplicationsOutcome.Unavailable(CATALOG_NATIVE_RENDER_DISABLED_REASON)
  }
}
