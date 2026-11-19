package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides

internal object WatchFaceRendererSelection {
  /**
   * Why `dwf-apk` is refused while [ServeFeatureFlags.nativePreview] is off. It starts the same
   * Android watch-face runtime the Native pane does, so the one switch covers both: a box that has
   * turned the runtime off answers this instead of starting a daemon it was told not to run.
   */
  val NATIVE_PREVIEW_DISABLED_REASON: String =
    "${ServeFeatureFlags.NATIVE_PREVIEW_DISABLED}: wfRenderer '${WatchFaceRendererIds.DWF_APK}' " +
      "runs the Android watch-face runtime, which this server has switched off " +
      "(features.nativePreview / ${ServeFeatureFlags.NATIVE_PREVIEW_ENV}). Use wfRenderer " +
      "'${WatchFaceRendererIds.DWF_RC}' (the Remote Compose renderer) instead."

  /**
   * Ordered watch-face renderer descriptors for [preview] on [host], listing `dwf-apk` before
   * `dwf-rc` and marking `dwf-apk` as `isDefault = true` whenever it is available (falling back to
   * `dwf-rc` when `dwf-apk` is unavailable). With [nativePreview] off, `dwf-apk` is listed as
   * unavailable with [NATIVE_PREVIEW_DISABLED_REASON].
   */
  fun renderersFor(
    host: ServeHost,
    preview: ServePreview,
    nativePreview: Boolean,
  ): List<WatchFaceRendererInfo> {
    if (!preview.isWatchFace) return emptyList()
    val raw = WatchFaceRendererRegistry.all().map { gate(it, nativePreview).infoFor(host, preview) }
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
   * With [nativePreview] off, an explicit `dwf-apk` resolves to a backend that reports itself
   * unavailable and refuses every call with [NATIVE_PREVIEW_DISABLED_REASON], and the default
   * selection never picks it.
   */
  fun resolveFor(
    host: ServeHost,
    preview: ServePreview?,
    wfRendererId: String?,
    nativePreview: Boolean,
  ): WatchFaceRendererBackend? {
    val normalized = wfRendererId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalized != null) {
      return WatchFaceRendererRegistry.resolve(normalized)?.let { gate(it, nativePreview) }
    }
    if (preview != null && preview.isWatchFace) {
      val apk =
        WatchFaceRendererRegistry.resolve(WatchFaceRendererIds.DWF_APK)?.let {
          gate(it, nativePreview)
        }
      if (apk != null && apk.infoFor(host, preview).available) {
        return apk
      }
      val available =
        WatchFaceRendererRegistry.all()
          .map { gate(it, nativePreview) }
          .firstOrNull { it.infoFor(host, preview).available }
      if (available != null) {
        return available
      }
    }
    return WatchFaceRendererRegistry.resolve(null)?.let { gate(it, nativePreview) }
  }

  private fun gate(backend: WatchFaceRendererBackend, nativePreview: Boolean) =
    if (nativePreview || backend.id != WatchFaceRendererIds.DWF_APK) backend
    else NativePreviewDisabledBackend(backend)

  /** `dwf-apk` on a box with [ServeFeatureFlags.nativePreview] off: listed, never started. */
  private class NativePreviewDisabledBackend(private val delegate: WatchFaceRendererBackend) :
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
        unavailableReason = NATIVE_PREVIEW_DISABLED_REASON,
      )

    override fun render(
      host: ServeHost,
      preview: ServePreview,
      overrides: PreviewOverrides,
    ): RenderOutcome = RenderOutcome.Failed(NATIVE_PREVIEW_DISABLED_REASON)

    override fun complications(
      host: ServeHost,
      preview: ServePreview,
      overrides: PreviewOverrides,
    ): WatchFaceComplicationsOutcome =
      WatchFaceComplicationsOutcome.Unavailable(NATIVE_PREVIEW_DISABLED_REASON)
  }
}
