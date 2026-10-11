package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.pseudolocale.LocaleDirection
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.computeGutterCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
import ee.schimke.composeai.imagecrop.pngAlphaBounds
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.web.WebEscaping
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A [ServeHost] backed by a portable bundle on disk (`previews/<id>.png` beside an `index.html`),
 * not a daemon: the shared/public mode where a pre-rendered bundle is served read-only. Overrides
 * are ignored and there is no live stream lane. Cheap and stateless, so the registry pins it
 * resident.
 */
class ServeBundleHost(
  private val bundleDir: File,
  override val label: String,
  /**
   * Producer-trust verdict attached at ingestion ([ServeBundleStore]) for badging. Defaults to
   * `Unverified` for unchecked bundles (e.g. a `--bundles` directory).
   */
  val trust: BundleVerifier.Verdict = BundleVerifier.Verdict.Unverified("not checked"),
  /**
   * Whether this host serves a design-system catalog rather than a plain bundle. The type can't
   * tell: it also backs `--bundles` directories and uploaded bundles. Set only where a catalog is
   * built; not inferred from [title] / [provenance] / [catalogSource], which are all optional on a
   * real catalog.
   */
  val isCatalog: Boolean = false,
  /**
   * Display title from `catalog.json`'s `title`; null for a plain bundle. Shown on the home index
   * cards.
   */
  val title: String? = null,
  /** One-line card descriptor from `catalog.json`'s `library`; null when undeclared. */
  val subtitle: String? = null,
  /** Declared stage surface (`"light"`/`"dark"`); null ⇒ the server's name-based default. */
  val stageSurface: String? = null,
  /**
   * The catalog's declared `display.role`, kept as the raw string so an unknown role from a newer
   * producer stays visible. Parsed by [ServeWeb.PageRole].
   */
  val catalogRole: String? = null,
  /**
   * The catalog's palette as serve-chrome CSS custom properties ([ServeThemeCss]); null keeps the
   * built-in chrome.
   */
  val webThemeCss: String? = null,
  /**
   * Declared hero (`display.hero`), a `componentId` or preview id resolved by
   * [declaredHeroPreviewId]; null ⇒ the server picks one.
   */
  val declaredHero: String? = null,
  /**
   * Local dir of the catalog's `figma/<slug>.svg` exports and crops, filled by [ServeCatalogStore];
   * null ⇒ the `.svg` lane 404s.
   */
  private val figmaDir: File? = null,
  /**
   * Provenance of a served catalog (source `repo@branch`, generation time, producing versions) for
   * the landing's provenance strip; null for a plain bundle.
   */
  val provenance: ServeWeb.CatalogProvenance? = null,
  /**
   * The catalog's Kotlin source (repo/ref/module), distinct from [provenance] (the delivery
   * branch); used for per-preview GitHub source links.
   */
  val catalogSource: ServeWeb.CatalogSource? = null,
  /**
   * The sibling system this catalog is a parallel rendition of; with [parallelByComponentId] it
   * offers the counterpart's render as a comparison source. Null when undeclared.
   */
  val compareWithSystem: String? = null,
  /**
   * `componentId` → counterpart `componentId` in [compareWithSystem]. Empty when undeclared or
   * published before `parallel` existed.
   */
  val parallelByComponentId: Map<String, String> = emptyMap(),
  /**
   * `componentId` → other catalogs publishing this component ([ServeRelatedCatalogs.declaredFor]).
   * Unlike [parallelByComponentId] (one parity counterpart), these are any number of directed
   * "about" links. Declared, not resolved: [ServeRelatedCatalogs.resolve] matches them against what
   * is registered per request.
   */
  val relatedByComponentId: Map<String, List<ServeRelatedCatalogs.Declared>> = emptyMap(),
  /**
   * Why this session is snapshot-only, set by [ServeCatalogStore] for a terminally registered baked
   * host; empty for plain bundles and for a baked host fronting a daemon. See [ServeDegradation].
   */
  override val degradations: List<ServeDegradation> = emptyList(),
  /**
   * Live-only (deferred) previews with no baked PNG, supplied only for a baked host fronting a live
   * daemon (so each has a daemon twin). They join [previews] with their variant metadata and are
   * re-exposed as [liveOnlyPreviewIds]; [render] still returns [RenderOutcome.NotFound] for them.
   */
  liveOnly: List<String> = emptyList(),
  /**
   * Whether a background lane will stage this session's player comparison, so [rcComparePending]
   * means "not landed yet" rather than "no manifest". Only catalogs schedule it; without this,
   * plain bundles would be permanently pending and lose edge caching.
   */
  private val stagesRcCompare: Boolean = false,
  /**
   * Ids this catalog publishes a baked PNG for, whether or not the pixels are local yet. Catalogs
   * pass their declared set (images are fetched lazily via [fetchBakedPng]); plain bundles pass
   * none and are exactly their `previews/` PNGs.
   */
  declaredBaked: List<String> = emptyList(),
  /**
   * Fetch one declared preview's PNG from the delivery branch, or null. Supplied by
   * [ServeCatalogStore], which owns the SSRF gate, size cap and test seam; null for a plain bundle.
   */
  private val fetchBakedPng: ((String) -> ByteArray?)? = null,
  /**
   * Ids this catalog publishes an animated capture for. Separate from [declaredBaked]: a capture
   * owns no card and is reachable only from its still.
   */
  declaredMotion: List<String> = emptyList(),
  /**
   * Fetch one declared capture from the delivery branch, reporting why a failure failed: a throttle
   * is a retryable 503, an absence a 404. Same store-owned seam as [fetchBakedPng].
   */
  private val fetchMotion: ((String) -> BranchFetch)? = null,
  /** Each declared capture's branch path, so a pinned (`?at=<sha>`) request can resolve one. */
  private val motionBranchPaths: Map<String, String> = emptyMap(),
  /**
   * Each declared preview's delivery-branch path, which pinned requests resolve against at an older
   * commit. Empty for plain bundles.
   */
  private val bakedBranchPaths: Map<String, String> = emptyMap(),
  /**
   * The delivery branch's revisions, newest first ([ServeCatalogStore.fetchRevisions]); the head is
   * served, the rest are pinnable. Empty when unavailable.
   */
  val revisions: List<ServeCatalogRevision.Revision> = emptyList(),
  /** Preview inventories precomputed by the publisher, keyed by historic delivery commit. */
  private val revisionPreviewIds: Map<String, Set<String>>? = null,
  /**
   * Per-image history precomputed by the publisher: only commits that changed each PNG, so it fills
   * rows the branch-wide Atom window lost to unrelated commits. Null for older publishers and plain
   * bundles.
   */
  private val indexedPreviewHistory: PreviewHistoryManifest.Manifest? = null,
  /**
   * Publishes in which one render's bytes changed, by branch path; null for a host with no delivery
   * branch. A null result means "could not ask", kept distinct from empty by [renderChangeCommits].
   */
  private val fetchRenderChanges: ((path: String) -> Set<String>?)? = null,
  /**
   * Each design reference's delivery-branch path; the served manifest rewrites rasters to
   * `references/<id>.png`, so this is the only way to address one at an older commit.
   */
  private val referenceBranchPaths: Map<String, String> = emptyMap(),
  /**
   * Fetch a published asset at a given commit, or null; store-owned like [fetchBakedPng]. Null ⇒ no
   * pinned revisions ([supportsPinnedRevisions]).
   */
  private val fetchPinnedAsset: ((commit: String, path: String) -> ByteArray?)? = null,
  /**
   * [fetchPinnedAsset], reporting why a read failed; preferred when supplied. This is what makes
   * [pinnedMisses] safe to keep forever.
   */
  private val fetchPinnedAssetOutcome: ((commit: String, path: String) -> BranchFetch)? = null,
  /**
   * Resolves ids to branch paths as of a given commit ([ServePinnedManifest]); takes precedence
   * over the tip's maps, which remain the fallback.
   */
  private val pinnedManifest: ServePinnedManifest? = null,
  private val fileSystem: FileSystem = SystemFileSystem,
) : ServeHost {

  // A catalog bundle that carried baked `figma/<slug>.svg` vectors can serve an SVG per preview; a
  // plain uploaded bundle (no figmaDir) 404s the `.svg` lane, so it offers no SVG download link.
  override val hasSvgExport: Boolean = figmaDir != null

  private val previewsDir = File(bundleDir, PREVIEWS_SUBDIR)
  private val previewsRoot = previewsDir.canonicalFile.toPath()
  private val designReferences = ServeDesignReferenceStore.load(bundleDir, fileSystem)

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    designReferences.forPreview(previewId)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    designReferences.raster(referenceId)

  internal fun uidReference(referenceId: String): Pair<DesignReference, ByteArray>? {
    val reference =
      designReferences.all.firstOrNull { it.id == referenceId && ServeUidReference.isUid(it) }
        ?: return null
    val file = File(bundleDir, reference.artifact!!.path!!).canonicalFile
    if (!file.toPath().startsWith(bundleDir.canonicalFile.toPath())) return null
    val bytes =
      runCatching {
        fileSystem.read(file.toOkioPath()) {
          readByteArray(
            (ServeUidReference.MAX_BYTES + 1)
              .toLong()
              .coerceAtMost(fileSystem.metadata(file.toOkioPath()).size ?: 0)
          )
        }
      }
        .getOrNull() ?: return null
    return if (ServeUidReference.valid(reference, bytes)) reference to bytes else null
  }

  // Whole-screen backdrops, read once at load like the reference manifest above. A bundle that
  // carries none yields an empty store and the viewer never offers the surface.
  private val designPages = ServeDesignPageStore.load(bundleDir, fileSystem)

  override fun designPages(): ServeDesignPageStore = designPages

  /**
   * One shared backplate's bytes, or null, resolved only through [ServeDesignPageStore.asset],
   * which has validated declaration, containment, signature and size. Joining [bundleDir] to a
   * manifest `uri` directly would be an arbitrary file read.
   */
  fun designPageAssetBytes(id: String): ByteArray? {
    val asset = designPages.asset(id) ?: return null
    val file = File(File(bundleDir, ServeDesignPageStore.DIRECTORY), asset.uri)
    return runCatching { file.readBytes() }.getOrNull()
  }

  // Resolved lazily: lane PNGs land on the catalog's background fetch lane after this host is
  // built.
  private val rcCompare = ServeRcCompareStore.load(bundleDir, fileSystem)

  override fun rcCompare(): RcCompareManifest? = rcCompare.manifest()

  override fun rcCompareImage(name: String): ByteArray? = rcCompare.image(name)

  override fun rcComparePending(): Boolean = stagesRcCompare && rcCompare.pending()

  // Read once at load, like the reference manifest: the feed is a published snapshot, so re-reading
  // it per request would buy nothing (a refresh reloads the whole catalog and rebuilds this host).
  private val parityActivity = ServeParityActivityStore.load(bundleDir, fileSystem)

  override fun parityActivity(): ParityActivity? = parityActivity

  private val parityIssues = ServeParityIssuesStore.load(bundleDir, fileSystem)

  override fun parityIssues(): ParityIssues? = parityIssues

  /**
   * The bundle's `guidelines.json` results, read lazily since a hosted catalog may lift the file
   * out of its live bundle after the host exists. Fail-soft.
   */
  private val guidelineResults by lazy {
    BundleGuidelineResults(ServeGuidelineResultsStore.load(bundleDir, fileSystem))
  }

  override fun guidelineResultFor(
    previewId: String
  ): ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1? =
    guidelineResults.forPreview(previewId)

  // Read once: a published verdict describes the catalog this host was built from.
  private val parityFindings = ServeParityFindingStore.load(bundleDir, fileSystem)

  override fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    parityFindings.forComparison(previewId, referenceId)

  // Read once at load, not per call: a refresh swaps the directory before registering the rebuilt
  // host, and a per-call read would pair a new document with the old inventory, producing false
  // `orphaned-target` findings. A refresh rebuilds this host anyway.
  private val knownDifferences = ServeKnownDifferences.document(bundleDir, fileSystem)

  override fun knownDifferences(): ServeKnownDifferences.Document? = knownDifferences

  override fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    ServeKnownDifferences.artifact(bundleDir, relativePath, fileSystem)

  private val annotations = ServeAnnotationStore.load(bundleDir, fileSystem)

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    annotations.forPreview(previewId)

  /**
   * Published annotation kinds the inspection layers draw; `layout` belongs to the compare page and
   * has no layer in `<cp-inspect-layers>`.
   */
  private fun drawableAnnotations(previewId: String): List<DesignAnnotation> =
    annotationsForPreview(previewId).filter {
      it.kind == AnnotationKind.TYPOGRAPHY || it.kind == AnnotationKind.THEME
    }

  /**
   * Whether the catalog published typography over this preview's own baked frame. See
   * [renderAnnotations].
   */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    previewId in previewIds &&
      annotationsForPreview(previewId).any { it.kind == AnnotationKind.TYPOGRAPHY }

  /**
   * Replay the catalog's published annotations (`annotations/index.json`) as the `.annotations`
   * product. Exact rather than approximate: this host never re-renders, so overrides can't move the
   * pixels. Typography only in practice, since the theme layer is derived live.
   */
  /** No daemon here, so annotations are the published ones over the baked frame. */
  override val annotationsFollowBakedFrame: Boolean = true

  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ): AnnotationsOutcome {
    if (previewId !in previewIds) return AnnotationsOutcome.NotFound
    // Narrowed to the requested layers so the content ETag varies correctly with `layers=`.
    val published =
      drawableAnnotations(previewId).let { all ->
        if (layers == null) all else all.filter { it.kind in layers }
      }
    if (published.isEmpty()) return AnnotationsOutcome.NotFound
    return AnnotationsOutcome.Ok(
      ServeAnnotationsPayload.encode(previewId, published, tagIndexForPreview(previewId))
    )
  }

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    annotations.forReference(referenceId)

  private val tagIndex = ServeTagIndexStore.load(bundleDir, fileSystem)

  override fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    tagIndex.forPreview(previewId)

  /**
   * Per-preview state/theme from `previews/variants.json`; empty for a plain bundle. Malformed
   * manifests degrade to empty.
   */
  private val variantMeta: Map<String, ServeCatalogStore.VariantMeta> = readVariantMeta()

  /**
   * Per-preview `id → module-relative sourceFile`, from `previews/variants.json` (catalogs) then a
   * root `previews.json` (uploaded bundles). Feeds [ServePreview.sourceFile].
   */
  private val sourceFilesById: Map<String, String> = readSourceFiles()

  /**
   * Per-preview discovery params from the root `previews.json`, sizing Remote Compose replays and
   * preserving explicit `uiMode`. Empty without a manifest.
   */
  private val previewParamsById:
    Map<String, ee.schimke.composeai.previewdata.PreviewParams> by lazy {
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (!fileSystem.exists(previewsJson)) return@lazy emptyMap()
    try {
      val text = fileSystem.read(previewsJson) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
          text,
        )
        .previews
        .associate { it.id to it.params }
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * Per-preview body-line anchors for [ServePreview.bodyLine], read like [sourceFilesById]:
   * catalogs key previews by route ids and stage no root manifest, so `variants.json` must come
   * first.
   */
  private val bodyLinesById: Map<String, Int> by lazy {
    val out = LinkedHashMap<String, Int>()
    for ((id, meta) in variantMeta) {
      meta.bodyLine?.takeIf { it > 0 }?.let { out[id] = it }
    }
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (fileSystem.exists(previewsJson)) {
      try {
        val text = fileSystem.read(previewsJson) { readUtf8() }
        val manifest =
          OVERRIDES_JSON.decodeFromString(
            ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
            text,
          )
        for (p in manifest.previews) {
          if (p.id !in out) p.bodyLine?.takeIf { it > 0 }?.let { out[p.id] = it }
        }
      } catch (e: Exception) {
        // Leave whatever the variants map already contributed.
      }
    }
    out
  }

  /** Live-only ids this host lists, minus any that also have baked pixels (baked wins). */
  /** The declared baked set; an id here is never live-only even while its file is missing. */
  /**
   * Declared capture ids. Declared here because the preview list built during construction reads
   * it.
   */
  private val declaredMotionIds: Set<String> = declaredMotion.toSet()

  private val declaredBakedIds: Set<String> =
    declaredBaked.filterTo(LinkedHashSet()) { previewFile(it, PNG_SUFFIX) != null }

  override val liveOnlyPreviewIds: Set<String> =
    liveOnly.filterTo(LinkedHashSet()) {
      val png = previewFile(it, PNG_SUFFIX)
      png != null && it !in declaredBakedIds && !png.isFile
    }

  override val previews: List<ServePreview> =
    // Three sources, deduped: PNGs on disk, the declared baked set (possibly remote), and live-only
    // ids. Walked recursively because ids may contain `/` (nested `previews/<id>.png`).
    (previewsDir
        .walkTopDown()
        .filter {
          it.isFile &&
            it.name.endsWith(PNG_SUFFIX) &&
            it.parentFile.relativeTo(previewsDir).invariantSeparatorsPath.split('/').none { segment
              ->
              segment.endsWith(SPATIAL_SUFFIX)
            }
        }
        .map { it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(PNG_SUFFIX) }
        .toList() +
        previewsDir
          .walkTopDown()
          .filter { it.isFile && it.name.endsWith(RENDER_ERROR_SUFFIX) }
          .map {
            it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(RENDER_ERROR_SUFFIX)
          }
          .toList() +
        previewsDir
          .walkTopDown()
          .filter {
            it.isFile &&
              it.name == SPATIAL_SCENE_FILE &&
              it.parentFile.name.endsWith(SPATIAL_SUFFIX)
          }
          .map {
            it.parentFile
              .relativeTo(previewsDir)
              .invariantSeparatorsPath
              .removeSuffix(SPATIAL_SUFFIX)
          }
          .toList() +
        declaredBakedIds +
        liveOnlyPreviewIds)
      .distinct()
      .sorted()
      .map { id ->
        val meta = variantMeta[id]
        val previewParams = previewParamsById[id]?.asPreviewParamsMeta() ?: meta?.previewParams
        ServePreview(
          id = id,
          label = id,
          spatial = spatialFile(id, SPATIAL_SCENE_FILE)?.isFile == true,
          componentId = meta?.componentId,
          // Only captures this host can actually fetch; otherwise the control would 404.
          motion =
            if (fetchMotion == null) emptyList()
            else
              meta
                ?.motion
                .orEmpty()
                .filter { it.id in declaredMotionIds && it.extension in MOTION_EXTENSIONS }
                .map {
                  ServeMotion(
                    id = it.id,
                    kind = it.kind,
                    caption = it.caption,
                    extension = it.extension,
                  )
                },
          renderFailure = meta?.renderFailure ?: readRenderFailure(id),
          // A packed sidecar is authoritative for uploaded bundles; catalogs also carry
          // declarations inline so controls show before a per-preview daemon opens.
          overrides = readOverrides(id).ifEmpty { meta?.overrides.orEmpty() },
          remoteComposeKnobs =
            readRemoteComposeKnobs(id).ifEmpty { meta?.remoteComposeKnobs.orEmpty() },
          supportsFocus = meta?.supportsFocus == true,
          supportsGestures = meta?.supportsGestures == true,
          fixedTheme = meta?.fixedTheme == true,
          secondary = meta?.secondary == true,
          // `state` comes only from a catalog's `variants.json`. A raw `@OverrideVariant` id
          // (`Foo_VARIANT_off`) is not folded here: `ServeWeb`'s state grouping keys on the catalog
          // `__<state>__` form, so it would vanish without a switcher link.
          state = meta?.state,
          theme = meta?.theme,
          props = meta?.props,
          // Like `state`, only catalogs name breakpoints; plain bundle renders keep their own
          // cards.
          size = meta?.size,
          section = meta?.section,
          group = meta?.group,
          caption = meta?.caption,
          componentParameters = meta?.componentParameters.orEmpty(),
          catalogOrder = meta?.order,
          sourceFile = sourceFilesById[id],
          sourceModule = meta?.sourceModule,
          bodyLine = bodyLinesById[id],
          // Ground and device frame from whichever source exists: an uploaded bundle's root
          // `previews.json`, or a catalog's `variants.json` record. The bundle wins where both
          // exist, being this render's own manifest.
          uiMode = previewParams?.uiMode ?: 0,
          showBackground = previewParams?.showBackground == true,
          backgroundColor = previewParams?.backgroundColor ?: 0L,
          deviceFrame =
            previewParams?.let { ServeDeviceFrame.from(it.device, it.widthDp, it.heightDp) },
        )
      }
      .toList()

  /** Every id this session lists, for the containment checks below. */
  private val publishedIds: Set<String> = previews.mapTo(HashSet()) { it.id }

  /**
   * The baked surface of a published catalog answers which mode a sticker was drawn in. See
   * [ServeBakedTheme].
   */
  override fun bakedTheme(previewId: String): UiMode? =
    ServeBakedTheme.resolve(previewId, variantMeta[previewId]?.theme) { it in publishedIds }

  /**
   * Also answers which Remote Compose player drew the pixels, since only this host holds the
   * manifests. With neither manifest it answers null rather than guessing; see
   * [ServeHost.bakedRcPlayer].
   */
  override fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? {
    if (!hasRemoteComposeDoc(previewId)) return null
    // A root `previews.json` entry speaks for the preview: its `wrapperClassName` is the pin, or
    // null for none. Presence of the entry is the test, not of the field.
    previewParamsById[previewId]?.let {
      return if (it.wrapperClassName == REMOTE_VIEW_PREVIEW_WRAPPER) RemoteComposePlayerKind.VIEW
      else RemoteComposePlayerKind.EMBEDDED
    }
    // A published catalog must record the player explicitly
    // ([ServeCatalogStore.PreviewParamsMeta.capturePlayer]) or it is unknown; inferring the
    // embedded default could serve a view-pinned capture. Legacy `cmp-android` / `java` values are
    // mapped by [ServeRcPlayerIds.fromCaptureRecord].
    return ServeRcPlayerIds.playerKindOf(
      ServeRcPlayerIds.fromCaptureRecord(variantMeta[previewId]?.previewParams?.capturePlayer)
    )
  }

  /**
   * The declared hero ([declaredHero]) resolved to a preview id, or null. Accepts a full preview
   * id, or a `componentId` / function name matched against a preview's slug head with the
   * exporter's normalisation.
   */
  val declaredHeroPreviewId: String? by lazy {
    val hero = declaredHero?.takeIf { it.isNotBlank() } ?: return@lazy null
    val usable = previews.filter { it.renderFailure == null }
    val exact = usable.firstOrNull { it.id == hero }
    if (exact != null) return@lazy exact.id
    val wanted = heroSlug(hero)
    usable.firstOrNull { heroSlug(it.id.substringBefore(SLUG_SEPARATOR)) == wanted }?.id
  }

  /** Resolve one per-preview file without allowing an untrusted catalog id to escape previews/. */
  private fun previewFile(id: String, suffix: String): File? {
    if (
      id.isBlank() ||
        id.startsWith('/') ||
        '\\' in id ||
        id.split('/').any { it == "." || it == ".." }
    ) {
      return null
    }
    val file = File(previewsDir, id + suffix)
    return file.takeIf { it.canonicalFile.toPath().startsWith(previewsRoot) }
  }

  private fun spatialFile(id: String, relativePath: String): File? {
    val base = previewFile(id, SPATIAL_SUFFIX) ?: return null
    if (
      relativePath.isBlank() ||
        relativePath.startsWith('/') ||
        '\\' in relativePath ||
        relativePath.split('/').any { it == "." || it == ".." }
    ) {
      return null
    }
    val root = base.canonicalFile.toPath()
    return File(base, relativePath).takeIf { it.canonicalFile.toPath().startsWith(root) }
  }

  override fun spatialAsset(previewId: String, relativePath: String): ServeSpatialAsset? {
    val contentType =
      when {
        relativePath == SPATIAL_SCENE_FILE -> "application/json"
        relativePath.endsWith(".png", ignoreCase = true) -> "image/png"
        relativePath.endsWith(".jpg", ignoreCase = true) ||
          relativePath.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        relativePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> return null
      }
    val file = spatialFile(previewId, relativePath)?.takeIf { it.isFile } ?: return null
    return ServeSpatialAsset(file.readBytes(), contentType)
  }

  /** Renderer error sidecar for an error-only uploaded/URL bundle preview. */
  private fun readRenderFailure(id: String): CatalogRenderFailure? {
    val sidecar = previewFile(id, RENDER_ERROR_SUFFIX)?.toOkioPath() ?: return null
    if (!fileSystem.exists(sidecar)) return null
    return try {
      val error =
        OVERRIDES_JSON.decodeFromString(
          BundleRenderError.serializer(),
          fileSystem.read(sidecar) { readUtf8() },
        )
      if (error.schema != RENDER_ERROR_SCHEMA) return null
      CatalogRenderFailure(
        id = id,
        preview = id,
        errorClass = error.exception,
        message = error.message,
        stackTrace = error.stackTrace,
        topAppFrame = error.topAppFrame,
      )
    } catch (_: Exception) {
      null
    }
  }

  /** Best-effort read of `previews/variants.json`; absent or unparseable → empty. */
  private fun readVariantMeta(): Map<String, ServeCatalogStore.VariantMeta> {
    val manifest = File(previewsDir, ServeCatalogStore.VARIANTS_FILE).toOkioPath()
    if (!fileSystem.exists(manifest)) return emptyMap()
    return try {
      val text = fileSystem.read(manifest) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
        MapSerializer(String.serializer(), ServeCatalogStore.VariantMeta.serializer()),
        text,
      )
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * Best-effort `id → sourceFile`: `variants.json` entries first, then a root `previews.json`.
   * Entries without a `sourceFile` are dropped.
   */
  private fun readSourceFiles(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((id, meta) in variantMeta) {
      meta.sourceFile?.takeIf { it.isNotBlank() }?.let { out[id] = it }
    }
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (fileSystem.exists(previewsJson)) {
      try {
        val text = fileSystem.read(previewsJson) { readUtf8() }
        val manifest =
          OVERRIDES_JSON.decodeFromString(
            ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
            text,
          )
        for (p in manifest.previews) {
          if (p.id !in out) p.sourceFile?.takeIf { it.isNotBlank() }?.let { out[p.id] = it }
        }
      } catch (e: Exception) {
        // Leave whatever the variants map already contributed.
      }
    }
    return out
  }

  /**
   * App-declared `@ThemeCatalog` themes from `previews.json`. A static bundle can't apply a
   * `themeProvider`, so the viewer shows them as a disabled, informational list. Empty without a
   * manifest.
   */
  override val declaredThemes: List<ServeTheme> = run {
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (!fileSystem.exists(previewsJson)) return@run emptyList()
    try {
      val text = fileSystem.read(previewsJson) { readUtf8() }
      val manifest =
        OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
          text,
        )
      declaredThemesFromPreviews(manifest.previews)
    } catch (e: Exception) {
      emptyList()
    }
  }

  /**
   * Editable knobs from `previews/<id>.overrides.json`; absent → none. Shown as disabled controls
   * since this host can't re-render.
   */
  private fun readOverrides(
    id: String
  ): List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> {
    val sidecar = File(previewsDir, "$id$OVERRIDES_SUFFIX").toOkioPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val json = fileSystem.read(sidecar) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(PreviewOverridesPayload.serializer(), json).declarations
    } catch (e: Exception) {
      emptyList()
    }
  }

  /**
   * Remote Compose named-value knobs from `previews/<id>.remotecompose.json`; the RC counterpart of
   * [readOverrides].
   */
  private fun readRemoteComposeKnobs(
    id: String
  ): List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration> {
    val sidecar = File(previewsDir, "$id$REMOTECOMPOSE_SUFFIX").toOkioPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val json = fileSystem.read(sidecar) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload.serializer(),
          json,
        )
        .declarations
    } catch (e: Exception) {
      emptyList()
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // The captured Remote Compose documents ride in the bundle's `ir/<id>.rc` sidecars (a sibling
  // of `previews/`), the browser player's replayable input.
  private val irDir = File(bundleDir, IR_SUBDIR)

  /**
   * The local file holding [previewId]'s baked PNG, fetching it from the delivery branch first if
   * needed; the single point every pixel reader goes through. Null for unknown, live-only, or
   * failed ids; failures aren't remembered, so transient blips self-heal. Fetches are serialised
   * per id, with a double-check inside the lock.
   */
  private fun bakedPngFile(previewId: String): okio.Path? {
    val path = previewFile(previewId, PNG_SUFFIX)?.toOkioPath() ?: return null
    if (fileSystem.exists(path)) return path
    val fetch = fetchBakedPng ?: return null
    if (previewId !in declaredBakedIds) return null
    synchronized(fillLocks.computeIfAbsent(previewId) { Any() }) {
      if (fileSystem.exists(path)) return path
      val bytes = runCatching { fetch(previewId) }.getOrNull() ?: return null
      // Written to a sibling and moved atomically: the existence check is outside the lock, so
      // readers must never see a half-written file.
      return runCatching {
        path.parent?.let(fileSystem::createDirectories)
        // Named per destination and per host instance. Per destination because different ids hold
        // different locks. Per instance because [fillLocks] is per host while the path is shared by
        // every host over the generation dir, and the registry can briefly run an old and a new
        // host together; each writes its own temp and both atomically publish identical bytes.
        val partial = path.parent!!.resolve("${path.name}.$instanceTag$PARTIAL_SUFFIX")
        fileSystem.write(partial) { write(bytes) }
        fileSystem.atomicMove(partial, path)
        path
      }
        .getOrNull()
    }
  }

  /**
   * Whether this host can answer a `?at=<sha>` pin (it has a delivery branch); false for a plain
   * bundle.
   */
  val supportsPinnedRevisions: Boolean
    get() = fetchPinnedAsset != null || fetchPinnedAssetOutcome != null

  /**
   * [previewId]'s baked render as published at [commit], or null. Never falls back to current
   * bytes: a permalink silently answering with today's render is exactly the bug this exists to
   * prevent.
   */
  fun pinnedRender(commit: String, previewId: String): PinnedOutcome =
    pinnedAsset(
      commit,
      branchPath(commit, previewId, bakedBranchPaths, { it.catalogRead }, { it.renders }),
    )

  /**
   * [previewId]'s render at [commit], addressed by the path the generated history records for it
   * rather than today's layout, so a preview whose path moved still resolves. The path comes from
   * the fetched manifest, never the caller; falls back to [pinnedRender].
   */
  fun pinnedIndexedRender(commit: String, previewId: String): PinnedOutcome {
    val path =
      indexedPreviewHistory?.previews?.get(previewId)?.path
        ?: return pinnedRender(commit, previewId)
    return pinnedAsset(commit, path)
  }

  /**
   * A preview this catalog published at [commit] but no longer lists, as a pageable record, so a
   * permalink made before a rename opens the page and not only the image. Null when that revision
   * didn't publish it or its catalog can't be read. Deliberately minimal: other lanes describe the
   * current catalog and are off on pinned pages.
   */
  fun pinnedPreview(commit: String, previewId: String): ServePreview? {
    val paths = pinnedManifest?.forCommit(commit) ?: return null
    if (!paths.catalogRead || previewId !in paths.renders) return null
    return ServePreview(
      id = previewId,
      label = previewId,
      componentId = paths.labels[previewId],
      caption = paths.captions[previewId],
      theme = paths.themes[previewId],
    )
  }

  /**
   * Whether [commit]'s own catalog could be read, so "no such preview then" (404) is
   * distinguishable from "could not ask" (fall back to tip).
   */
  fun pinnedCatalogIsAuthoritative(commit: String): Boolean =
    pinnedManifest?.forCommit(commit)?.catalogRead == true

  /**
   * Whether one indexed revision carried [previewId]. Image history is authoritative when it names
   * the commit; null means the branch predates both indexes (menus fail open).
   */
  fun revisionContainsPreview(commit: String, previewId: String): Boolean? {
    val normalized =
      ServeCatalogRevision.normalize(commit) ?: return revisionPreviewIds?.let { false }
    if (normalized in indexedPreviewRevisions(previewId).asSequence().map { it.commit }) return true
    return revisionPreviewIds?.let { it[normalized]?.contains(previewId) ?: false }
  }

  /**
   * The publisher's timeline for [previewId], or null without generated history. Already pinned to
   * the served tree; exposed so remote consumers needn't fetch the whole (~1 MB) manifest for one
   * preview.
   */
  fun indexedTimeline(previewId: String): PreviewHistoryManifest.PreviewTimeline? =
    indexedPreviewHistory?.previews?.get(previewId)

  /** Distinct image versions the generated history can recover beyond the branch feed's window. */
  fun indexedPreviewRevisions(previewId: String): List<ServeCatalogRevision.Revision> =
    indexedPreviewHistory?.previews?.get(previewId)?.versions.orEmpty().mapNotNull { version ->
      val commit = ServeCatalogRevision.normalize(version.commit) ?: return@mapNotNull null
      ServeCatalogRevision.Revision(
        commit = commit,
        date = version.date,
        sourceSha = ServeCatalogRevision.normalize(version.sourceSha),
      )
    }

  /**
   * Boundaries for indexed image versions, aligned to displayable rows:
   * [PreviewHistoryManifest.ManifestVersion.introducedBy] when visible, else the displayed commit
   * so the version still gets a marker.
   */
  private fun indexedRenderChanges(previewId: String, visibleRevisions: Set<String>): Set<String> =
    indexedPreviewHistory
      ?.previews
      ?.get(previewId)
      ?.versions
      .orEmpty()
      .mapNotNull { version ->
        val introduced = ServeCatalogRevision.normalize(version.introducedBy)
        val displayed = ServeCatalogRevision.normalize(version.commit)
        introduced?.takeIf { it in visibleRevisions } ?: displayed
      }
      .toSet()

  /**
   * Delivery-branch publishes in which [previewId]'s render changed, or null when neither the
   * generated index nor the branch can answer. Empty means no change in the window (identical
   * pixels); null means draw no markers.
   *
   * Cached per preview for the host's life, matching the load's single pinned commit. Shares
   * [pinnedPermits] with the pinned-asset lane, since both bound anonymous branch reads, and misses
   * are serialised per preview on [fillLocks] so concurrent menu opens don't take every permit for
   * duplicate requests. Blocking; called on [Dispatchers.IO][kotlinx.coroutines.Dispatchers.IO].
   *
   * Known limitation: the feed is read only for the tip path ([bakedBranchPaths]), so changes under
   * a former path are missed and the count is understated. Resolving per revision would roughly
   * triple the cost of a cold menu open; if paths do move in practice, resolve the path at the
   * window's oldest revision and union both feeds.
   */
  fun renderChangeCommits(
    previewId: String,
    visibleRevisions: Set<String> = emptySet(),
  ): Set<String>? {
    val indexed = indexedRenderChanges(previewId, visibleRevisions)
    val fetch = fetchRenderChanges ?: return indexed.takeIf { it.isNotEmpty() }
    // The warm read stays OUTSIDE the lock, exactly as [bakedPngFile]'s does: an answer this host
    // already has must never queue behind someone else's cold fetch.
    renderChangeCache[previewId]?.let {
      return it
    }
    val path = bakedBranchPaths[previewId] ?: return indexed.takeIf { it.isNotEmpty() }
    synchronized(fillLocks.computeIfAbsent("$RENDER_CHANGE_LOCK_PREFIX$previewId") { Any() }) {
      // Re-checked under the lock: whoever we queued behind was fetching exactly this answer.
      renderChangeCache[previewId]?.let {
        return it
      }
      if (
        !pinnedPermits.tryAcquire(PINNED_FETCH_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      )
        return indexed.takeIf { it.isNotEmpty() }
      val fetched =
        try {
          runCatching { fetch(path) }.getOrNull()?.map(String::lowercase)?.toSet()
        } finally {
          pinnedPermits.release()
        }
      // Union the live path feed with history.json when the feed answers; otherwise the generated
      // boundaries alone are authoritative.
      val changes = fetched?.plus(indexed) ?: indexed.takeIf { it.isNotEmpty() }
      // Only a real answer is remembered. A failed read says nothing about the branch, and caching
      // it would strand the markers off for the life of the host over one blip.
      if (changes != null) {
        synchronized(renderChangeCache) {
          if (renderChangeCache.size >= MAX_RENDER_CHANGE_ENTRIES) {
            renderChangeCache.keys.firstOrNull()?.let(renderChangeCache::remove)
          }
          renderChangeCache[previewId] = changes
        }
      }
      return changes
    }
  }

  private val renderChangeCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

  /** [referenceId]'s canonical reference raster as published at [commit]. See [pinnedRender]. */
  fun pinnedReference(commit: String, referenceId: String): PinnedOutcome =
    pinnedAsset(
      commit,
      branchPath(
        commit,
        referenceId,
        referenceBranchPaths,
        { it.referencesRead },
        { it.references },
      ),
    )

  /**
   * Outcome of a pinned read. [Missing] (permanent, 404) and [Busy] (temporary, 503) stay distinct
   * so a good permalink is never reported dead.
   */
  sealed interface PinnedOutcome {
    data class Ok(val bytes: ByteArray) : PinnedOutcome {
      // Arrays get identity equals/hashCode, which a data class would silently inherit. Nothing
      // compares these today; defining them keeps that from becoming a surprise if anything does.
      override fun equals(other: Any?): Boolean =
        this === other || (other is Ok && bytes.contentEquals(other.bytes))

      override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Missing : PinnedOutcome

    data object Busy : PinnedOutcome
  }

  /**
   * Where [id]'s asset lived at [commit], preferring that commit's manifest over the tip's map:
   * render ids derive from paths (a moved file is a different id), and reference paths move
   * independently of ids, so the tip gives wrong answers for history.
   *
   * The tip map is the fallback only for an absent manifest. A readable manifest that doesn't list
   * the id means that revision didn't publish it.
   */
  private fun branchPath(
    commit: String,
    id: String,
    tip: Map<String, String>,
    wasRead: (ServePinnedManifest.Paths) -> Boolean,
    select: (ServePinnedManifest.Paths) -> Map<String, String>,
  ): String? {
    val paths = pinnedManifest?.forCommit(commit)
    if (paths != null && wasRead(paths)) return select(paths)[id]
    return tip[id]
  }

  /**
   * One published asset at one commit, memoised. `(commit, path)` is immutable, so hits never need
   * revalidation; at capacity an arbitrary entry is dropped, since the value is in repeated opens
   * of the same link, not a working set.
   */
  private fun pinnedAsset(commit: String, path: String?): PinnedOutcome {
    // Either seam is enough to have a pinned lane; the outcome-reporting one is preferred
    // wherever it is wired, and the plain one remains for callers that cannot distinguish.
    if (fetchPinnedAssetOutcome == null && fetchPinnedAsset == null) return PinnedOutcome.Missing
    val safePath = ServeCatalogRevision.normalizePath(path) ?: return PinnedOutcome.Missing
    val pin = ServeCatalogRevision.normalize(commit) ?: return PinnedOutcome.Missing
    val key = "$pin/$safePath"
    pinnedCache[key]?.let {
      return PinnedOutcome.Ok(it)
    }
    // Refuse known misses from memory instead of re-asking the branch per image.
    if (key in pinnedMisses) return PinnedOutcome.Missing
    // Admission: `?at=<sha>` lets a request choose the fetch, so a bounded permit stops an
    // anonymous caller opening unlimited branch reads. Timing out answers Busy, not Missing.
    if (!pinnedPermits.tryAcquire(PINNED_FETCH_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
      return PinnedOutcome.Busy
    val outcome =
      try {
        // Re-checked under the permit: while this caller waited, the fetch it is queued behind may
        // have been for exactly this URL — the common case on a page whose images share a commit.
        pinnedCache[key]?.let { BranchFetch.Ok(it) }
          ?: runCatching {
            fetchPinnedAssetOutcome?.invoke(pin, safePath)
              ?: fetchPinnedAsset?.invoke(pin, safePath)?.let { BranchFetch.Ok(it) }
              ?: BranchFetch.NotFound
          }
            .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }
      } finally {
        pinnedPermits.release()
      }
    val bytes = outcome.bytesOrNull
    if (bytes == null) {
      // Only a real absence is remembered; a throttle says nothing about the revision.
      if (outcome == BranchFetch.NotFound) remember(pinnedMisses, key, MAX_PINNED_MISS_ENTRIES)
      return PinnedOutcome.Missing
    }
    synchronized(pinnedCache) {
      if (pinnedCache.size >= MAX_PINNED_CACHE_ENTRIES) {
        pinnedCache.keys.firstOrNull()?.let(pinnedCache::remove)
      }
      pinnedCache[key] = bytes
    }
    return PinnedOutcome.Ok(bytes)
  }

  private fun remember(set: MutableSet<String>, key: String, max: Int) {
    synchronized(set) {
      if (set.size >= max) set.firstOrNull()?.let(set::remove)
      set.add(key)
    }
  }

  private val pinnedCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

  /**
   * URLs this branch answered nothing for, keyed like [pinnedCache]. Not time-bounded: `(commit,
   * path)` is immutable. The set is small and drops entries under pressure.
   */
  private val pinnedMisses: MutableSet<String> =
    java.util.Collections.synchronizedSet(LinkedHashSet())

  private val pinnedPermits = java.util.concurrent.Semaphore(MAX_CONCURRENT_PINNED_FETCHES)

  /** [previewId]'s baked PNG only if it is already local — never fetches. */
  private fun localBakedPng(previewId: String): okio.Path? =
    previewFile(previewId, PNG_SUFFIX)?.toOkioPath()?.takeIf(fileSystem::exists)

  /**
   * Distinguishes this host's staging files from other hosts over the same generation dir (see
   * [bakedPngFile]). Not `System.identityHashCode`, which can collide; a monotonic counter plus a
   * random salt is unique within and across processes.
   */
  private val instanceTag: String = nextInstanceTag()

  private val fillLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

  /**
   * The staged file for one capture, fetched on first request. A near-copy of [bakedPngFile] rather
   * than a shared generic, so a capture can never be written under a still's name.
   */
  private fun motionFile(motionId: String, extension: String): BranchFetch {
    if (motionId !in declaredMotionIds) return BranchFetch.NotFound
    if (extension !in MOTION_EXTENSIONS) return BranchFetch.NotFound
    // The suffix must be the one this capture was published as, not just an allowed format;
    // otherwise a request could type an APNG as GIF.
    if (motionBranchPaths[motionId]?.endsWith(extension) != true) return BranchFetch.NotFound
    val path = previewFile(motionId, extension)?.toOkioPath() ?: return BranchFetch.NotFound
    if (fileSystem.exists(path)) return readStagedMotion(path)
    val fetch = fetchMotion ?: return BranchFetch.NotFound
    // Keyed distinctly from the baked lane: a capture and its sibling still share an id, so one
    // lock namespace would have a cold capture fetch block a warm sticker read on the same card.
    synchronized(fillLocks.computeIfAbsent("$MOTION_LOCK_PREFIX$motionId") { Any() }) {
      if (fileSystem.exists(path)) return readStagedMotion(path)
      val outcome = runCatching {
        fetch(motionId)
      }
        .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }
      // A failure is returned AS ITSELF rather than flattened: a throttle here is what makes the
      // route answer 503 instead of telling the reader the capture was never published.
      val bytes = outcome.bytesOrNull ?: return outcome
      // Staging failing is not the branch's fault and not a missing asset either — the bytes are in
      // hand, so serve them and let the next request try the disk again.
      runCatching {
        path.parent?.let(fileSystem::createDirectories)
        // Per host instance, for the reason the baked-PNG fill states at length.
        val partial = path.parent!!.resolve("${path.name}.$instanceTag$PARTIAL_SUFFIX")
        fileSystem.write(partial) { write(bytes) }
        fileSystem.atomicMove(partial, path)
      }
      return BranchFetch.Ok(bytes)
    }
  }

  /** A staged capture read back off disk; an unreadable stage is transient, not a missing asset. */
  private fun readStagedMotion(path: okio.Path): BranchFetch = runCatching {
    BranchFetch.Ok(fileSystem.read(path) { readByteArray() })
  }
    .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }

  /**
   * The bytes of one published capture. Id and extension are both checked against what the catalog
   * declared, so a request can't invent either.
   */
  override fun motionRead(motionId: String, extension: String): BranchFetch =
    motionFile(motionId, extension)

  /** The branch path of a declared capture, for a pinned request. */
  fun motionBranchPath(motionId: String): String? = motionBranchPaths[motionId]

  /**
   * Local-pixels fast path via [localBakedPng], not [bakedPngFile]: fetching belongs behind
   * admission, so a missing PNG returns null and goes down the ordinary [render] path.
   */
  /**
   * The fetching counterpart of [bakedRender] for [ServeThumbWarmer]: runs off the request thread
   * so [bakedPngFile] may fill the file; the bytes are discarded.
   */
  override fun warmBakedRender(previewId: String) {
    if (previewId !in previewIds) return
    bakedPngFile(previewId)
  }

  override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? {
    if (previewId !in previewIds) return null
    val png = localBakedPng(previewId) ?: return null
    return RenderOutcome.Ok(
      fileSystem.read(png) { readByteArray() },
      RenderOutcome.Generation.BAKED,
    )
  }

  // [localBakedPng] for the same reason as [bakedRender]: measuring must never trigger a fetch, so
  // a non-local preview reports no size.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? {
    if (previewId !in previewIds) return null
    return readPngSize(localBakedPng(previewId) ?: return null)
  }

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    if (previewId !in previewIds) return RenderOutcome.NotFound
    val png = bakedPngFile(previewId) ?: return RenderOutcome.NotFound
    return RenderOutcome.Ok(
      fileSystem.read(png) { readByteArray() },
      RenderOutcome.Generation.BAKED,
    )
  }

  override fun remoteComposeDoc(previewId: String): ByteArray? {
    if (previewId !in previewIds) return null
    val doc = File(irDir, "$previewId$RC_SUFFIX").toOkioPath()
    if (!fileSystem.exists(doc)) return null
    return try {
      fileSystem.read(doc) { readByteArray() }
    } catch (e: Exception) {
      null
    }
  }

  // Cheap existence check (no read) so the per-preview page render can gate the client-side canvas
  // lane without pulling the whole document — the browser fetches the bytes over `/render/<id>.rc`.
  override fun hasRemoteComposeDoc(previewId: String): Boolean {
    if (previewId !in previewIds) return false
    return fileSystem.exists(File(irDir, "$previewId$RC_SUFFIX").toOkioPath())
  }

  // The cmp-jvm render is sized to the baked PNG's pixels at the capture density; null without a
  // doc or baked PNG.
  override fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? {
    if (!hasRemoteComposeDoc(previewId)) return null
    // Sized against the baked PNG, so a declared-but-not-yet-local preview fills first.
    val (widthPx, heightPx) = readPngSize(bakedPngFile(previewId) ?: return null) ?: return null
    // Device density before the renderer default: 2.625 is a phone number and every Wear device is
    // 2.0, so using the default would scale a watch replay against its baked PNG. Same resolution
    // as the viewer's dp→px, so the two can't drift.
    val density = renderDensityFor(previewId) ?: DEFAULT_RENDER_DENSITY
    return RcJvmRenderSpec(widthPx, heightPx, density)
  }

  /** Read a PNG's pixel dimensions from its IHDR without decoding the image; null if unreadable. */
  private fun readPngSize(path: okio.Path): Pair<Int, Int>? {
    return try {
      val header = fileSystem.read(path) { readByteArray(24) }
      // 8-byte PNG signature, 4-byte IHDR length, 4-byte "IHDR", then width + height, big-endian.
      fun be(off: Int): Int =
        ((header[off].toInt() and 0xff) shl 24) or
          ((header[off + 1].toInt() and 0xff) shl 16) or
          ((header[off + 2].toInt() and 0xff) shl 8) or
          (header[off + 3].toInt() and 0xff)
      val w = be(16)
      val h = be(20)
      if (w > 0 && h > 0) w to h else null
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Serve the baked `compose/figma-svg` export for [previewId] from [figmaDir] with its raster
   * crops inlined. [SvgOutcome.NotFound] for a plain bundle, unknown id, or a component without
   * one. Overrides don't apply.
   */
  // Per-preview SVG availability: gate the viewer's SVG control on the actual file, since a slug
  // without `figma/<slug>.svg` 404s (see #2352).
  override fun hasSvgExportFor(previewId: String): Boolean = figmaSvgFileFor(previewId) != null

  /**
   * The baked figma-svg serving [previewId], or null: the per-variant `figma/<slug>/<variant>.svg`
   * first (the slug vector is light-only), then the per-component `figma/<slug>.svg` for older
   * catalogs.
   */
  private fun figmaSvgFileFor(previewId: String): okio.Path? {
    val figma = figmaDir ?: return null
    if (previewId !in previewIds) return null
    val slug = previewId.substringBefore(SLUG_SEPARATOR)
    val variant = previewId.substringAfter(SLUG_SEPARATOR, missingDelimiterValue = "")
    if (variant.isNotEmpty()) {
      val perVariant = File(File(figma, slug), "$variant$SVG_SUFFIX").toOkioPath()
      if (fileSystem.exists(perVariant)) return perVariant
    }
    return File(figma, "$slug$SVG_SUFFIX").toOkioPath().takeIf { fileSystem.exists(it) }
  }

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val svgFile = figmaSvgFileFor(previewId) ?: return SvgOutcome.NotFound
    val svg = fileSystem.read(svgFile) { readUtf8() }
    // Crops resolve relative to the SVG's own dir: `<slug>.figma-raster/` next to the slug vector,
    // `<variant>.figma-raster/` next to a per-variant one.
    val dir = svgFile.parent ?: return SvgOutcome.NotFound
    return SvgOutcome.Ok(
      inlineFigmaRasters(fileSystem, dir, svg).encodeToByteArray(),
      RenderOutcome.Generation.BAKED,
    )
  }

  /**
   * Web variant of [renderSvg]: link raster crops to their published home on the delivery branch
   * (from [provenance]) instead of embedding them, keeping the SVG small. Falls back to the
   * embedded default without provenance.
   */
  override fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val prov = provenance ?: return renderSvg(previewId, overrides)
    val svgFile = figmaSvgFileFor(previewId) ?: return SvgOutcome.NotFound
    val svg = fileSystem.read(svgFile) { readUtf8() }
    // The crops' branch URL mirrors the SVG's on-disk dir relative to the catalog root (figmaDir's
    // parent): `figma` for the slug vector, `figma/<slug>` for a per-variant one.
    val catalogRoot = figmaDir?.parentFile ?: return renderSvg(previewId, overrides)
    val relDir =
      svgFile.parent?.toFile()?.relativeToOrNull(catalogRoot)?.invariantSeparatorsPath
        ?: return renderSvg(previewId, overrides)
    val base = "https://raw.githubusercontent.com/${prov.repo}/${prov.branch}/$relDir"
    return SvgOutcome.Ok(
      linkFigmaRasters(svg, base).encodeToByteArray(),
      RenderOutcome.Generation.BAKED,
    )
  }

  /**
   * The crop framing [previewId]'s thumbnail to the component box, or null for the raw render (see
   * [computeThumbCrop]). Read from the baked SVG's `viewBox` and the PNG's IHDR, then memoised for
   * the host's life.
   */
  fun contentCrop(previewId: String): ContentCrop? {
    cropCache[previewId]?.let {
      return it.orElse(null)
    }
    // Either the PNG or the vector may still be in flight; answer null without memoising so the
    // card crops once they land.
    if (localBakedPng(previewId) == null && previewId in declaredBakedIds) return null
    // A declared capture gutter needs no vector, so it applies even before (or without) the figma
    // pass.
    val gutter = declaredCaptureGutter(previewId)
    val svgOutstanding = figmaDir != null && figmaSvgFileFor(previewId) == null
    // While a vector may still land, a gutter crop is provisional: served but not memoised.
    if (svgOutstanding) return if (gutter == null) null else sharedContentCrop(previewId, gutter)
    val computed = java.util.Optional.ofNullable(sharedContentCrop(previewId, gutter))
    cropCache[previewId] = computed
    return computed.orElse(null)
  }

  /**
   * The density this preview's renders are produced at, or null when nothing here says. Resolved
   * like the render lane (`PreviewManifestRouter.ResolvedRenderParams`), minus the final 2.0
   * default, which the caller applies so it can tell "renders at 2.0" from "unknown". Uploaded
   * bundles carry `density` in `previews.json`; published catalogs only carry the `@Preview(device
   * = …)` string, which resolves through the device catalog (most phone devices are not 2.0).
   */
  fun renderDensityFor(previewId: String): Float? =
    previewParamsById[previewId]?.declaredDensity()
      ?: deviceDensity(variantMeta[previewId]?.previewParams?.device)

  /**
   * The declared `@CaptureGutter` in render pixels, from `previews.json` (dp, resolved against its
   * density) or `variants.json` (already pixels); null when none.
   */
  private fun declaredCaptureGutter(previewId: String): ServeCatalogStore.CaptureGutterPx? =
    (previewParamsById[previewId]?.asPreviewParamsMeta() ?: variantMeta[previewId]?.previewParams)
      ?.captureGutter
      ?.takeUnless { it.isEmpty() }

  private val cropCache =
    java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ContentCrop>>()

  /**
   * [computeContentCrop], memoised across every host over the same files. [cropCache] dies with the
   * host, which the registry rebuilds on resume, so decoding every PNG again made cold landings
   * slow. Keyed by file identity (path, size, mtime) and gutter.
   */
  private fun sharedContentCrop(
    previewId: String,
    gutter: ServeCatalogStore.CaptureGutterPx?,
  ): ContentCrop? {
    val png = localBakedPng(previewId) ?: return null
    val svg = figmaSvgFileFor(previewId)
    val key =
      runCatching {
        val pngMeta = fileSystem.metadata(png)
        val svgMeta = svg?.let(fileSystem::metadata)
        SharedCropKey(
          png.toString(),
          pngMeta.size,
          pngMeta.lastModifiedAtMillis,
          svg?.toString(),
          svgMeta?.size,
          svgMeta?.lastModifiedAtMillis,
          gutter,
        )
      }
        .getOrNull() ?: return computeContentCrop(previewId, gutter)
    sharedCrops[key]?.let {
      return it.orElse(null)
    }
    val computed = java.util.Optional.ofNullable(computeContentCrop(previewId, gutter))
    // Bounded crudely: a crop is a few longs, so the cap is about memory only in the pathological
    // case, and a wholesale clear costs one recompute per card — what every rebuild paid before.
    if (sharedCrops.size >= MAX_SHARED_CROPS) sharedCrops.clear()
    sharedCrops[key] = computed
    return computed.orElse(null)
  }

  private fun computeContentCrop(
    previewId: String,
    gutter: ServeCatalogStore.CaptureGutterPx?,
  ): ContentCrop? {
    // Same per-variant-first resolution as `renderSvg` — a variant vector's viewBox reflects the
    // exact render this preview's PNG shows.
    val svgFile = figmaSvgFileFor(previewId)
    // The already-local file, not `bakedPngFile`: filling here would serially download a cold
    // catalog on the landing request. Cold cards render uncropped until their PNG lands.
    val png = localBakedPng(previewId) ?: return null
    return try {
      val bytes = fileSystem.read(png) { readByteArray() }
      val (rw, rh) = WebEscaping.pngDimensions(bytes.copyOf(PNG_HEADER_BYTES.toInt()))
      // Union the render's actual non-transparent extent into the crop box so a focus ring or
      // disabled outline drawn outside the layout-derived figma box is never clipped.
      val fromSvg =
        svgFile
          ?.let {
            // Union the drawn extent so focus rings outside the figma box aren't clipped, except on
            // a guttered render, where those pixels are the shadow and would make the component
            // look smaller.
            val bounds = if (gutter == null) pngAlphaBounds(bytes) else null
            computeThumbCrop(fileSystem.read(it) { readUtf8() }, rw, rh, bounds)
          }
          // On a guttered render the crop must not clip the shadow the gutter reserves.
          ?.let { if (gutter == null) it else it.copy(clip = false) }
      // The vector wins where it applies (it frames the component inside its canvas); otherwise
      // crop to the gutter.
      fromSvg ?: gutter?.let { computeGutterCrop(it.left, it.top, it.right, it.bottom, rw, rh) }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * A bundle has no daemon, so no live lane — callers fall back to the snapshot ([render]) lane.
   */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    onUnavailable?.invoke("this session serves baked snapshots only (no live daemon)")
    return null
  }

  override fun activeStreamCount(): Int = 0

  override fun close() {
    // Nothing to release — a bundle host owns no daemon or sockets.
  }

  companion object {
    /** Identity of the files a [ContentCrop] was computed from. See [sharedContentCrop]. */
    private data class SharedCropKey(
      val png: String,
      val pngSize: Long?,
      val pngModified: Long?,
      val svg: String?,
      val svgSize: Long?,
      val svgModified: Long?,
      val gutter: ServeCatalogStore.CaptureGutterPx?,
    )

    private const val MAX_SHARED_CROPS = 100_000

    private val sharedCrops =
      java.util.concurrent.ConcurrentHashMap<SharedCropKey, java.util.Optional<ContentCrop>>()

    private const val PREVIEWS_SUBDIR = "previews"
    private const val PNG_SUFFIX = ".png"
    private const val SPATIAL_SUFFIX = ".spatial"
    private const val SPATIAL_SCENE_FILE = "scene.json"

    /**
     * Extensions a published capture may be served under — closed, and checked on every request.
     */
    val MOTION_EXTENSIONS = listOf(".apng", ".gif")

    /** Namespaces the motion fill locks apart from the baked ones, which share an id space. */
    private const val MOTION_LOCK_PREFIX = "motion:"

    /** Namespaces the render-change locks in the shared [fillLocks] map. */
    private const val RENDER_CHANGE_LOCK_PREFIX = "render-changes:"
    private const val RENDER_ERROR_SUFFIX = ".error.json"
    private const val RENDER_ERROR_SCHEMA = "compose-preview-error/v1"
    /** Suffix of the sibling a lazy fill writes before moving it into place atomically. */
    private const val PARTIAL_SUFFIX = ".partial"

    private val instanceCounter = java.util.concurrent.atomic.AtomicLong()

    /** Per-process, so two servers over one generation directory cannot collide on the counter. */
    private val processSalt: String =
      java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())

    /** A staging tag no other live host can hold. See [instanceTag]. Visible for tests. */
    internal fun nextInstanceTag(): String = "$processSalt-${instanceCounter.getAndIncrement()}"

    /** Pinned assets kept per host: a de-duplicator for re-opened permalinks, not a working set. */
    private const val MAX_PINNED_CACHE_ENTRIES = 32

    /** Remembered branch misses; larger than the hit cache since a miss costs only a key. */
    private const val MAX_PINNED_MISS_ENTRIES = 256

    /**
     * Render-change sets kept resident; entries are a few shas and readers open menu after menu.
     */
    private const val MAX_RENDER_CHANGE_ENTRIES = 512

    /**
     * Concurrent pinned-lane branch reads; bounds what an anonymous caller can make this server do.
     */
    private const val MAX_CONCURRENT_PINNED_FETCHES = 4

    /**
     * How long a pinned read waits for a permit before answering busy: ordinary pages queue
     * through, floods are shed.
     */
    private const val PINNED_FETCH_WAIT_SECONDS = 5L

    /** Bytes of a PNG needed to read its IHDR width/height (8 sig + 4 len + 4 tag + 4 + 4). */
    private const val PNG_HEADER_BYTES = 24L
    private const val SVG_SUFFIX = ".svg"
    /** A preview id folds the component slug and variant as `<slug>__<variant>`. */
    private const val SLUG_SEPARATOR = "__"

    /**
     * Normalise a declared hero to the slug the exporter bakes into ids; mirrors `@design-parity`'s
     * `slug()`.
     */
    private fun heroSlug(value: String): String =
      value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase().ifBlank { "x" }

    private const val OVERRIDES_SUFFIX = ".overrides.json"
    private const val REMOTECOMPOSE_SUFFIX = ".remotecompose.json"
    /** Sibling of `previews/` holding the captured Remote Compose docs (`ir/<id>.rc`). */
    private const val IR_SUBDIR = "ir"
    private const val RC_SUFFIX = ".rc"
    private const val PREVIEWS_JSON = "previews.json"

    /**
     * FQN of the `PreviewWrapperProvider` pinning a preview to the view-backed player
     * ([RemoteComposePlayerKind.VIEW]); matched by name since it lives on the app's classpath.
     */
    private const val REMOTE_VIEW_PREVIEW_WRAPPER =
      "ee.schimke.composeai.daemon.RemoteViewPreviewWrapper"
    private val OVERRIDES_JSON = Json { ignoreUnknownKeys = true }

    /** True when [dir] contains at least one baked preview or structured render failure. */
    fun looksLikeBundle(dir: File): Boolean {
      val previews = File(dir, PREVIEWS_SUBDIR)
      return previews.isDirectory &&
        previews.walkTopDown().any {
          it.isFile && (it.name.endsWith(PNG_SUFFIX) || it.name.endsWith(RENDER_ERROR_SUFFIX))
        }
    }
  }
}

// Last-resort cmp-jvm density (the desktop renderer's default, a phone value), used only after
// [deviceDensity]. File-level because the params→meta mapping below also uses it.
private const val DEFAULT_RENDER_DENSITY = 2.625f

/**
 * The density a `@Preview(device = …)` renders at (same device catalog the renderer uses), or null
 * for an unknown device rather than throwing.
 */
private fun deviceDensity(device: String?): Float? =
  device
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { DeviceDimensions.resolve(it).density }.getOrNull() }
    ?.takeIf { it > 0f }

/**
 * The entry's stated density, else its device's; null means unstated, and each caller picks its own
 * fallback. Non-positive values fall through.
 */
private fun ee.schimke.composeai.previewdata.PreviewParams.declaredDensity(): Float? =
  density?.takeIf { it > 0f } ?: deviceDensity(device)

/**
 * Whether a `@Preview(locale = …)` render was right-to-left, using the renderer's own rule (bidi
 * pseudolocales, then the language table) rather than a second copy.
 */
private fun rendersRightToLeft(locale: String?): Boolean {
  if (locale.isNullOrBlank()) return false
  Pseudolocale.fromTag(locale)?.let {
    return it.isRtl
  }
  return LocaleDirection.isRtl(locale)
}

/**
 * A bundle manifest's `@Preview` params in the shape a catalog publishes, so "which fields does a
 * ground need?" is answered once. Only fields consulted before opening a daemon cross over.
 */
private fun ee.schimke.composeai.previewdata.PreviewParams.asPreviewParamsMeta():
  ServeCatalogStore.PreviewParamsMeta =
  ServeCatalogStore.PreviewParamsMeta(
    uiMode = uiMode,
    showBackground = showBackground,
    backgroundColor = backgroundColor,
    device = device,
    widthDp = widthDp,
    heightDp = heightDp,
    // Gutter dp is converted to render pixels per edge with this manifest's density, as the
    // exporter does for catalogs.
    captureGutter =
      captureGutter?.let { gutter ->
        val scale = declaredDensity() ?: DEFAULT_RENDER_DENSITY
        fun px(dp: Int) = (dp.coerceAtLeast(0) * scale).roundToInt()
        // Leading/trailing → left/right against the direction this render was composed in — the
        // same resolution the renderer performed when it placed the component inset.
        val rtl = rendersRightToLeft(locale)
        ServeCatalogStore.CaptureGutterPx(
            left = px(if (rtl) gutter.end else gutter.start),
            top = px(gutter.top),
            right = px(if (rtl) gutter.start else gutter.end),
            bottom = px(gutter.bottom),
          )
          .takeUnless { it.isEmpty() }
      },
  )

@Serializable
private data class BundleRenderError(
  val schema: String = "",
  val exception: String = "RenderError",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  val stackTrace: String? = null,
)

/**
 * A bundle's guideline results by bundle id. Bundles rename ids to path-safe form
 * (`[^A-Za-z0-9._-]` → `_`), and older reports carry raw ids, so an exact miss falls back to
 * safe-mapped ids; ambiguous ones are left out.
 */
internal class BundleGuidelineResults(private val results: ServeGuidelineResults?) {
  private val bySafeId:
    Map<String, ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1> by lazy {
    // An ambiguous safe id answers nothing rather than possibly another preview's result.
    val grouped = results?.records?.entries.orEmpty().groupBy { bundleSafeId(it.key) }
    grouped.filterValues { it.size == 1 }.mapValues { it.value.single().value }
  }

  fun forPreview(previewId: String): ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1? =
    results?.forPreview(previewId) ?: bySafeId[bundleSafeId(previewId)]
}

/** A preview id as a bundle stores it: anything outside `[A-Za-z0-9._-]` becomes `_`. */
internal fun bundleSafeId(id: String): String = id.replace(BUNDLE_UNSAFE_ID_CHARS, "_")

private val BUNDLE_UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9._-]")
