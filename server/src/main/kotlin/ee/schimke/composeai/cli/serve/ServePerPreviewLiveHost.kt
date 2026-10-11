package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * A [ServeHost] fronting a trusted catalog's baked PNGs with live daemons that re-render each
 * preview from its own per-preview bundle (`bundle/previews/<daemon-id>.png`), rather than one
 * monolithic `liveBundle` ([ServeCatalogLiveHost]).
 *
 * Browsing is baked, as in [ServeCatalogLiveHost]: only an override the baked PNG can't satisfy
 * ([CatalogLiveRouting.overridesAffectRender]) routes to [resolveLive], which yields a pooled
 * single-preview daemon owned by the caller (null falls back to baked). Calls use the mapped daemon
 * id.
 *
 * Per-preview bundles carry only their preview's closure over a Maven-coordinate classpath, so the
 * server holds daemons only for previews being edited and the pool reaps idle ones.
 */
class ServePerPreviewLiveHost(
  /** Catalog id → daemon preview id. Unmapped ids (Android-only variants) always replay baked. */
  private val alias: Map<String, String>,
  /** The static baked-PNG host — the browse + snapshot surface, keyed by catalog ids. */
  private val baked: ServeHost,
  /**
   * Resolve a pooled daemon host for a daemon-preview id from its own bundle, or null. Only called
   * for mapped ids with a pixel-changing override; the caller owns the host.
   */
  private val resolveLive: (daemonId: String) -> ServeHost?,
  /**
   * The whole preview set: baked previews with knobs and feature flags grafted from the bundles'
   * `overrides.json` sidecars, assembled by the caller.
   */
  override val previews: List<ServePreview>,
  /**
   * App-declared `@ThemeCatalog` themes (module-global); forwarded to the viewer's Theme selector.
   */
  override val declaredThemes: List<ServeTheme> = emptyList(),
  /**
   * Whether the per-preview daemons honour the one-handed gesture override (Android-backed only).
   */
  override val gesturesRenderable: Boolean = false,
  /**
   * SVG is exportable when either lane can (baked `figma/<slug>.svg` or a daemon's
   * `compose/figma-svg`); defaults to the baked host's capability.
   */
  override val hasSvgExport: Boolean = baked.hasSvgExport,
  /** Live upstream stream count across the pooled per-preview daemons (supplied by the pool). */
  private val streamCount: () -> Int = { 0 },
) : ServeHost {

  /**
   * The underlying baked host, so `catalogBundleHost()` can read its title, subtitle and trust
   * verdict. Mirrors [ServeCatalogLiveHost.bakedHost].
   */
  internal val bakedHost: ServeHost = baked

  override val label: String = baked.label

  // Straight through to the published host, as [bakedRenderSize] is: warming is about the delivery
  // branch's bytes, which the per-preview live lane has nothing to say about.
  override fun warmBakedRender(previewId: String) = baked.warmBakedRender(previewId)

  // The published pixels' size, which is what an unfurl card advertises — the live lane never
  // changes it, and asking the baked host costs a PNG header read.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? =
    baked.bakedRenderSize(previewId)

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    baked.designReferencesFor(previewId)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    baked.designReferenceRaster(referenceId)

  override fun designPages(): ServeDesignPageStore = baked.designPages()

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    baked.annotationsForPreview(previewId)

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    baked.annotationsForReference(referenceId)

  override fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    baked.tagIndexForPreview(previewId)

  override fun parityActivity(): ParityActivity? = baked.parityActivity()

  override fun parityIssues(): ParityIssues? = baked.parityIssues()

  // A design-guidelines result is published catalog data, like the parity issues: the baked
  // bundle carries it, and a live lane has nothing different to say about it.
  override fun guidelineResultFor(
    previewId: String
  ): ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1? =
    // The published id first, then the bundle id it aliases: the report is keyed by the ids the
    // bundle was built with, which a catalog's published ids need not be.
    baked.guidelineResultFor(previewId) ?: alias[previewId]?.let { baked.guidelineResultFor(it) }

  override fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    baked.parityFindingsFor(previewId, referenceId)

  // Known differences are catalog data from the baked staging dir, not render output.
  override fun knownDifferences(): ServeKnownDifferences.Document? = baked.knownDifferences()

  override fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    baked.knownDifferenceArtifact(relativePath)

  // The catalog's published player comparison rides the baked staging dir, so it stays reachable
  // when a live daemon fronts this session.
  override fun rcCompare(): RcCompareManifest? = baked.rcCompare()

  override fun rcCompareImage(name: String): ByteArray? = baked.rcCompareImage(name)

  override fun rcComparePending(): Boolean = baked.rcComparePending()

  /**
   * The baked host's live-only (deferred) ids, always routed to a daemon. Mirrors
   * [ServeCatalogLiveHost.liveOnlyPreviewIds].
   */
  override val liveOnlyPreviewIds: Set<String> = baked.liveOnlyPreviewIds

  // The sticker is the baked host's, so it answers which mode it was drawn in. See
  // [ServeBakedTheme].
  override fun bakedTheme(previewId: String): UiMode? = baked.bakedTheme(previewId)

  /** Delegated to the baked surface for the same reason [bakedTheme] is. */
  override fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? =
    baked.bakedRcPlayer(previewId)

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant — the live lane is opt-in per edit.
   */
  override val canApplyOverrides: Boolean = false

  /**
   * A per-preview daemon CAN re-render an override on demand, so the viewer's knobs render live.
   */
  override val canRenderOverrides: Boolean = true

  /** The "Live (stream)" toggle is offered (unlike a plain static catalog). */
  override val hasLiveStream: Boolean = true

  /** Only a preview with a per-preview live bundle ([alias]) can re-render; others replay baked. */
  override fun canRenderOverridesFor(previewId: String): Boolean = previewId in alias

  /**
   * Per-preview SVG availability (#2352): a daemon-twinned id via its daemon, an unmapped id only
   * when the baked slug vector exists; never broader than [hasSvgExport]. Mirrors [renderSvg].
   */
  override fun hasSvgExportFor(previewId: String): Boolean =
    hasSvgExport && (previewId in alias || baked.hasSvgExportFor(previewId))

  override val hasScrollExport: Boolean = previews.any {
    it.id in alias && ServeRenderHost.SCROLL_LONG_KIND in it.dataProductKinds
  }

  override fun hasScrollExportFor(previewId: String): Boolean =
    previewId in alias &&
      previews
        .firstOrNull { it.id == previewId }
        ?.dataProductKinds
        ?.contains(ServeRenderHost.SCROLL_LONG_KIND) == true

  /**
   * Inspection layers advertised from [alias] membership, like [canRenderOverridesFor]: asking a
   * daemon would mean materialising one per page render. An id that doesn't resolve answers
   * `NotFound`.
   */
  override val hasA11yOverlay: Boolean = alias.isNotEmpty()

  override fun hasA11yOverlayFor(previewId: String): Boolean = previewId in alias

  override val hasDesignAnnotations: Boolean = alias.isNotEmpty()

  override fun hasDesignAnnotationsFor(previewId: String): Boolean = previewId in alias

  /** The baked half: a published catalog can inspect typography with no daemon at all. */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    baked.hasPublishedTypographyFor(previewId)

  /**
   * Accessibility inspection on this preview's own daemon with the viewer's overrides; no baked
   * fallback, since a sticker has no semantics tree.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    val daemonId = alias[previewId] ?: return A11yOutcome.NotFound
    val live = resolveLive(daemonId) ?: return A11yOutcome.NotFound
    return live.renderA11y(daemonId, overrides)
  }

  /**
   * Typography and theme inspection follow [renderA11y] to the daemon, falling back to published
   * annotations when the live lane can't answer, but only when the request would be served baked
   * pixels ([CatalogLiveRouting.overridesAffectRender]), since published bounds describe the baked
   * frame.
   */
  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ): AnnotationsOutcome {
    // Published-first only when the named layers suffice and overrides leave the baked frame; see
    // [ServeCatalogLiveHost.renderAnnotations].
    if (
      AnnotationKind.publishedLayersSuffice(layers) &&
        !CatalogLiveRouting.overridesAffectRender(
          previewId,
          overrides,
          bakedTheme(previewId),
          bakedRcPlayer(previewId),
        )
    ) {
      val published = baked.renderAnnotations(previewId, overrides, layers)
      if (published !is AnnotationsOutcome.NotFound) return published
    }
    val live =
      alias[previewId]?.let { daemonId ->
        resolveLive(daemonId)?.renderAnnotations(daemonId, overrides, layers)
      }
    if (live != null && live !is AnnotationsOutcome.NotFound) return live
    if (
      CatalogLiveRouting.overridesAffectRender(
        previewId,
        overrides,
        bakedTheme(previewId),
        bakedRcPlayer(previewId),
      )
    )
      return AnnotationsOutcome.NotFound
    return baked.renderAnnotations(previewId, overrides, layers)
  }

  /**
   * Browsing serves baked PNGs; an override they can't represent routes to the preview's daemon.
   * Unmapped or unresolvable ids fall back to baked.
   */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId =
      CatalogLiveRouting.daemonIdForRender(
        previewId,
        overrides,
        alias,
        liveOnlyPreviewIds,
        bakedTheme(previewId),
        bakedRcPlayer(previewId),
      ) ?: return baked.render(previewId, overrides)
    val live =
      resolveLive(daemonId)
        // A live-only (deferred) preview has no baked PNG to fall back to, so an unresolvable
        // daemon is a genuine miss rather than a quiet downgrade to the baked sticker.
        ?: return if (previewId in liveOnlyPreviewIds) RenderOutcome.NotFound
        else baked.render(previewId, overrides)
    return live.render(daemonId, overrides)
  }

  /** Full-page raster capture is not baked; route a mapped preview to its own daemon. */
  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = alias[previewId] ?: return RenderOutcome.NotFound
    val live = resolveLive(daemonId) ?: return RenderOutcome.NotFound
    return live.renderScrollPng(daemonId, overrides)
  }

  /** Full-page vector capture follows the same per-preview daemon route. */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val daemonId = alias[previewId] ?: return SvgOutcome.NotFound
    val live = resolveLive(daemonId) ?: return SvgOutcome.NotFound
    return live.renderScrollSvg(daemonId, overrides)
  }

  /**
   * SVG mirrors [render]'s routing with [ServeCatalogLiveHost]'s fallback: override-bearing mapped
   * ids render on their daemon; otherwise the baked slug vector, and only without one does a mapped
   * id fall back to its daemon.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    CatalogLiveRouting.daemonIdForRender(
        previewId,
        overrides,
        alias,
        liveOnlyPreviewIds,
        bakedTheme(previewId),
        bakedRcPlayer(previewId),
      )
      ?.let { daemonId ->
        resolveLive(daemonId)?.let {
          return it.renderSvg(daemonId, overrides)
        }
      }
    // No override: prefer the daemon's per-variant SVG, since the baked slug vector is light-only
    // and would be wrong for a `…__dark` id. Baked stays the fallback.
    alias[previewId]?.let { daemonId ->
      resolveLive(daemonId)?.renderSvg(daemonId, overrides)?.let { live ->
        if (live !is SvgOutcome.NotFound) return live
      }
    }
    return baked.renderSvg(previewId, overrides)
  }

  /** Live streaming is available only for aliased ids that resolve a per-preview daemon. */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    val daemonId =
      alias[previewId]
        ?: run {
          onUnavailable?.invoke("no live daemon twin for '$previewId' (baked snapshot only)")
          return null
        }
    val live =
      resolveLive(daemonId)
        ?: run {
          onUnavailable?.invoke("live daemon for '$previewId' could not be resolved")
          return null
        }
    return live.subscribeStream(
      daemonId,
      overrides,
      codec,
      maxFps,
      onUnavailable = onUnavailable,
      onFrame = onFrame,
    )
  }

  override fun activeStreamCount(): Int = streamCount()

  /** The pool owns the per-preview daemons; only the baked surface is closed here. */
  override fun close() {
    baked.close()
  }
}
