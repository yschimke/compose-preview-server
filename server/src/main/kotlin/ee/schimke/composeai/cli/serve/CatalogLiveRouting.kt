package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * Shared override-routing logic for the two trusted-catalog live hosts:
 * - [ServeCatalogLiveHost] — one monolithic `liveBundle` daemon serving every preview by id;
 * - [ServePerPreviewLiveHost] — a daemon per per-preview bundle
 *   (`bundle/previews/<daemon-id>.png`).
 *
 * Both front a baked catalog and re-render only what the baked PNG cannot satisfy.
 */
internal object CatalogLiveRouting {

  /**
   * The daemon-preview id to route an override [render][ServeHost.render] to, or null to stay
   * baked. Non-null only when [previewId] is a mapped (daemon-renderable) id in [alias] AND the
   * request carries an override the baked PNG can't satisfy ([overridesAffectRender]).
   */
  fun daemonIdForOverrideRender(
    previewId: String,
    overrides: PreviewOverrides,
    alias: Map<String, String>,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): String? {
    // No daemon twin (an Android-only variant) ⇒ always baked; it has no live lane.
    val daemonId = alias[previewId] ?: return null
    return if (overridesAffectRender(previewId, overrides, bakedTheme, bakedRcPlayer)) daemonId
    else null
  }

  /**
   * [daemonIdForOverrideRender], extended with the session's **live-only** ids
   * ([ServeHost.liveOnlyPreviewIds] — the catalog's deferred previews, published with no baked
   * PNG). Those have nothing to replay, so for them even an override-free browse must go to the
   * daemon; every other id keeps the baked-unless-the-override-demands-otherwise routing that makes
   * browsing instant.
   */
  fun daemonIdForRender(
    previewId: String,
    overrides: PreviewOverrides,
    alias: Map<String, String>,
    liveOnly: Set<String>,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): String? =
    if (previewId in liveOnly) alias[previewId]
    else daemonIdForOverrideRender(previewId, overrides, alias, bakedTheme, bakedRcPlayer)

  /**
   * Whether [o] would change pixels vs the baked PNG, so the render must go to the daemon.
   * Overrides that restate what the baked variant shows ([withoutBakedNoOps]) stay baked. Compares
   * against a defaults instance so a newly added override field is covered automatically.
   */
  fun overridesAffectRender(
    previewId: String,
    o: PreviewOverrides,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): Boolean = withoutBakedNoOps(previewId, o, bakedTheme, bakedRcPlayer) != PreviewOverrides()

  /**
   * The overrides in [o] that the **baked** PNG for [previewId] does not reflect, named as the
   * caller spelled them in the query string (`fontScale`, `knob.label`, `rc.stopColor`, …). Empty
   * exactly when [overridesAffectRender] is false — i.e. when the baked pixels are a truthful
   * answer to the request.
   *
   * Makes a baked fallback legible: the HTTP layer refuses a non-empty list (or names it in
   * response headers when the caller opted into the snapshot), since the snapshot's pixels cannot
   * show that an override was ignored. A dropped field not named below reports as `overrides`.
   */
  fun droppedOverrideNames(
    previewId: String,
    o: PreviewOverrides,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): List<String> {
    // Name from the no-op-free copy, not the raw request: an override that merely restates the
    // baked pixels was honoured, so naming it would refuse a request the snapshot answers truly.
    val dropped = withoutBakedNoOps(previewId, o, bakedTheme, bakedRcPlayer)
    if (dropped == PreviewOverrides()) return emptyList()
    val names = mutableListOf<String>()
    fun add(name: String, value: Any?) {
      if (value != null) names += name
    }
    add("widthPx", dropped.widthPx)
    add("heightPx", dropped.heightPx)
    add("minWidthPx", dropped.minWidthPx)
    add("minHeightPx", dropped.minHeightPx)
    add("maxWidthPx", dropped.maxWidthPx)
    add("maxHeightPx", dropped.maxHeightPx)
    add("density", dropped.density)
    add("localeTag", dropped.localeTag)
    add("fontScale", dropped.fontScale)
    add("uiMode", dropped.uiMode)
    add("orientation", dropped.orientation)
    add("device", dropped.device)
    add("inspectionMode", dropped.inspectionMode)
    add("slotMode", dropped.slotMode)
    add("placeholderActive", dropped.placeholderActive)
    add("talkBack", dropped.talkBack)
    add("touchOverlay", dropped.touchOverlay)
    add("themeProvider", dropped.themeProvider)
    add("focus", dropped.focus)
    add("gestures", dropped.gestures)
    add("clearBackground", dropped.clearBackground)
    dropped.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    dropped.remoteCompose?.let { rc ->
      add("rcProfile", rc.profile)
      // `?rcPlayer=` reaches the override as the built-in `player` or, for any other player, as
      // `playerId`; the caller spelled both the same way, so they share one name here.
      add("rcPlayer", rc.player ?: rc.playerId)
      add("rcDocument", rc.documentBase64)
      rc.namedValues.keys.sorted().forEach { names += "${ServeOverrides.RC_NAMED_PREFIX}$it" }
    }
    return names.ifEmpty { listOf("overrides") }
  }

  /**
   * The overrides an **IR replay** of [previewId] cannot honour, named as the caller spelled them —
   * the daemon-lane counterpart of [droppedOverrideNames].
   *
   * An IR-backed preview is redrawn by replaying a captured document, so an axis whose only route
   * to the pixels is a fresh composition renders byte-identical to the baked snapshot while
   * answering `200`.
   *
   * Deliberately a narrow allow-list: an axis is listed only when the document has no
   * representation of it at all, because too wide a list turns working renders into refusals.
   * - `themeProvider` and `knob.` overrides are seeded into a composition that does not exist.
   * - `localeTag`: strings were resolved at capture, and `RemoteContext` has no locale variable.
   * - **string** `rc.` named values do not land in the alpha player (float ones do); remove this
   *   entry when it does, and `IrReplayDroppedOverridesTest` will notice.
   *
   * `fontScale` and `uiMode` are deliberately absent: a document can defer both to the host
   * `Configuration` at paint time (a `-PcomposePreview.rcDensity=host` capture does), so refusing
   * them would be a false refusal. Size, density and device reach the player through the capture's
   * `displayMetrics`.
   *
   * Runs [withoutBakedNoOps] first so both predicates share a baseline.
   */
  fun irReplayDroppedOverrideNames(
    previewId: String,
    o: PreviewOverrides,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): List<String> {
    val dropped = withoutBakedNoOps(previewId, o, bakedTheme, bakedRcPlayer)
    val names = mutableListOf<String>()
    if (dropped.localeTag != null) names += "localeTag"
    if (dropped.themeProvider != null) names += "themeProvider"
    dropped.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    dropped.remoteCompose
      ?.namedValues
      ?.filterValues { it is RemoteNamedValue.StringValue }
      ?.keys
      ?.sorted()
      ?.forEach { names += "${ServeOverrides.RC_NAMED_PREFIX}$it" }
    return names
  }

  /**
   * [o] with the fields the baked PNG already satisfies cleared:
   * - a `uiMode` equal to [bakedTheme] ([ServeHost.bakedTheme] / [ServeBakedTheme]);
   * - `clearBackground = false`, which preserves the authored background the baked render drew;
   * - an `rcPlayer` equal to [bakedRcPlayer] ([ServeHost.bakedRcPlayer]), the capture's player.
   *
   * A null [bakedTheme] or [bakedRcPlayer] satisfies nothing, so those overrides route to a render.
   */
  private fun withoutBakedNoOps(
    previewId: String,
    o: PreviewOverrides,
    bakedTheme: UiMode? = ServeBakedTheme.token(previewId),
    bakedRcPlayer: RemoteComposePlayerKind? = null,
  ): PreviewOverrides =
    o.copy(
      uiMode = o.uiMode?.takeIf { it != bakedTheme },
      clearBackground = o.clearBackground?.takeIf { it },
      remoteCompose =
        o.remoteCompose
          ?.let { rc ->
            if (rc.player == bakedRcPlayer) rc.newBuilder().also { it.player = null }.build()
            else rc
          }
          // An emptied facet must become null for the `!= PreviewOverrides()` comparison. A
          // `playerId` is never a no-op: the capture records only the built-in player. Nor is a
          // carried document (`documentBase64`): it replaces the preview's content outright, so
          // the baked pixels never answer it, whichever player draws it.
          ?.takeIf {
            it.profile != null ||
              it.player != null ||
              it.playerId != null ||
              it.namedValues.isNotEmpty() ||
              it.documentBase64 != null
          },
    )
}
