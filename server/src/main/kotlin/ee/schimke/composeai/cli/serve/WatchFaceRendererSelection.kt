package ee.schimke.composeai.cli.serve

internal object WatchFaceRendererSelection {
  /**
   * Ordered watch-face renderer descriptors for [preview] on [host], listing `dwf-apk` before
   * `dwf-rc` and marking `dwf-apk` as `isDefault = true` whenever it is available (falling back to
   * `dwf-rc` when `dwf-apk` is unavailable).
   */
  fun renderersFor(host: ServeHost, preview: ServePreview): List<WatchFaceRendererInfo> {
    val raw = WatchFaceRendererRegistry.renderersFor(host, preview)
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
   */
  fun resolveFor(
    host: ServeHost,
    preview: ServePreview?,
    wfRendererId: String?,
  ): WatchFaceRendererBackend? {
    val normalized = wfRendererId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalized != null) {
      return WatchFaceRendererRegistry.resolve(normalized)
    }
    if (preview != null && preview.isWatchFace) {
      val apk = WatchFaceRendererRegistry.resolve(WatchFaceRendererIds.DWF_APK)
      if (apk != null && apk.infoFor(host, preview).available) {
        return apk
      }
      val available =
        WatchFaceRendererRegistry.all().firstOrNull { it.infoFor(host, preview).available }
      if (available != null) {
        return available
      }
    }
    return WatchFaceRendererRegistry.resolve(null)
  }
}
