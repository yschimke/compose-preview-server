package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleClasspathHydration
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.designpages.DesignPagesJson
import ee.schimke.composeai.designpages.DesignPagesManifest
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeArtifactV1
import ee.schimke.composeai.uibuilder.protocol.validateContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class PerPreviewBundleAccess(
  val available: (daemonId: String) -> Boolean,
  val fetch: (daemonId: String) -> File?,
)

/**
 * Serves our published design systems on a public preview server: fetches a
 * `design-artifacts/<system>` catalog (`catalog.json` + `images/`) from GitHub and registers it as
 * a read-only [ServeBundleHost] session.
 *
 * Trust is by origin: a catalog from a `repo@branch` listed in the [TrustStore]'s `branches` is
 * [BundleVerifier.Verdict.Trusted]; otherwise it is served `Unverified` (images execute no code, so
 * they are shown either way, just badged).
 *
 * The fetch base derives only from operator-supplied `--catalogs` / `--catalog-repo`, never client
 * input, so there is no SSRF lever here. [fetch] is injected so tests can stub the network.
 */
class ServeCatalogStore(
  private val root: File,
  private val register: (name: String, host: ServeBundleHost) -> Unit,
  /**
   * Read per fetch rather than captured, so trust changes apply to the next load and refresh
   * without a restart.
   */
  private val trust: () -> TrustStore,
  private val repo: String = DEFAULT_REPO,
  private val branchPrefix: String = DEFAULT_BRANCH_PREFIX,
  private val fetch: ((String) -> ByteArray?)? = null,
  /**
   * Runs post-publish vector fills ([scheduleFigmaSvgFetch]). Single-threaded so background work
   * never outweighs the request path; daemon so it never blocks shutdown.
   */
  private val figmaExecutor: java.util.concurrent.Executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-figma").apply { isDaemon = true }
    },
  /**
   * The branch transport. Outcome-shaped so every lane, including pins, goes through this one
   * injected seam and can see why a read failed.
   */
  private val networkFetch: (url: String, maxBytes: Long) -> BranchFetch = ::httpFetchOutcome,
  /**
   * Existence probe (`HEAD`), outcome-shaped like [networkFetch] so "absent" and "refused" stay
   * distinct and throttling is counted.
   */
  private val networkProbe: (url: String) -> BranchFetch = ::httpProbeOutcome,
  private val maxImages: Int = DEFAULT_MAX_IMAGES,
  /**
   * Called on every publish with the directory of the catalog's in-browser Wasm app, or null when
   * this generation has none, so `/wasm/<system>/` rides the trusted branch. Null withdraws the
   * registration; otherwise the previous generation's app would keep serving beside the new
   * catalog's pages.
   */
  private val registerWasm: (system: String, dir: File?) -> Unit = { _, _ -> },
  /**
   * Invoked when a post-publish lane (vector fills, rc-compare pull) hit a transient failure after
   * [Result.Ok.incomplete] was already returned. Wired to `ServeCatalogRefresher.forgetHeads` so
   * the next tick re-reads the revision.
   */
  private val onPostPublishIncomplete: (system: String) -> Unit = {},
  private val serverSideRenderEnabled: Boolean = false,
  /**
   * Recorded reason [system]'s live-lane launch failed ([LiveLaneLaunchLog]), appended to the
   * generic degradation so `/status.json` names the actual cause. Null leaves the generic sentence.
   */
  private val liveLaneFailure: (system: String) -> String? = { null },
  /**
   * Trusted server-side re-render from a carried executable bundle (opt-in,
   * `--allow-render-trusted`). For a Trusted catalog declaring a `liveBundle`, the fetched bundle
   * stands up a daemon-backed session that fronts the baked catalog ([ServeCatalogLiveHost]) via
   * the catalog→daemon `alias` and a `bakedFallback`. Tried before [buildTrustedSource]; returns
   * true when it registered a session. The callback owns the flag gate; this store only calls it
   * for a Trusted catalog whose whole bundle fetched cleanly. `externalResourcesDir` is the
   * rehydrated resource pool ([rehydrateExternalResources]), null for self-contained bundles.
   *
   * `fetchPerPreviewBundle` resolves a daemon-preview id to its own split bundle (fail-closed, null
   * when absent); split bundles share the monolithic bundle's resource pool, and a null resolve
   * falls back to the monolithic daemon.
   */
  private val buildTrustedBundle:
    (
      system: String,
      bundleFile: File,
      externalResourcesDir: File?,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
      perPreviewBundle: PerPreviewBundleAccess,
    ) -> Boolean =
    { _, _, _, _, _, _ ->
      false
    },
  /**
   * Multi-module counterpart of [buildTrustedBundle]. Each entry owns one classpath and alias set.
   */
  private val buildTrustedBundles:
    (
      system: String,
      bundles: List<TrustedModuleBundle>,
      bakedFallback: () -> ServeHost,
    ) -> Boolean =
    { _, _, _ ->
      false
    },
  /** Publish verified carried bundles to non-daemon consumers such as the playground compiler. */
  private val recordTrustedBundles: (system: String, bundles: List<VerifiedModuleBundle>) -> Unit =
    { _, _ ->
    },
  /** Clear compile targets when a successful refresh no longer carries a usable trusted bundle. */
  private val clearTrustedBundles: (system: String) -> Unit = {},
  /**
   * Trusted server-side re-render from a declared `source` (opt-in, `--allow-render-trusted`):
   * builds a daemon-backed session that fronts the baked catalog like [buildTrustedBundle]. Returns
   * true when it registered one; the default never does. The callback owns the ref-allowlist and
   * build gates.
   */
  private val buildTrustedSource:
    (
      system: String,
      source: CatalogSource,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
    ) -> Boolean =
    { _, _, _, _ ->
      false
    },
  /**
   * Durable, content-addressed home for heavy fetched bytes (live bundles, splits, resource pool);
   * see [CatalogBlobPool]. Defaults to a process-scoped pool under [root]; `--catalog-cache-dir`
   * supplies a durable one.
   */
  private val blobs: CatalogBlobPool = CatalogBlobPool(File(root, BLOB_CACHE_DIR)),
) {

  /** A catalog's buildable source — where to check out + build to re-render it live. */
  data class CatalogSource(val repo: String, val ref: String, val module: String)

  data class TrustedModuleBundle(
    val module: String,
    val file: File,
    val externalResourcesDir: File?,
    val alias: Map<String, String>,
    val perPreviewBundle: PerPreviewBundleAccess,
    /** Catalog-wide daemon identity to the id actually carried by this module bundle. */
    val localPreviewIds: Map<String, String> = emptyMap(),
  )

  /** Minimal verified carried-bundle identity for compile consumers that do not run its daemon. */
  data class VerifiedModuleBundle(val module: String, val file: File)

  /**
   * Build the catalog-id → daemon-preview-id alias: each image's route-safe id mapped to its
   * recorded `previewId`. Images without one have no live lane; first mapping wins. Live-only
   * [Catalog.deferred] records are aliased too, and this alias is the only way they can be served.
   */
  /**
   * [cause] with the recorded launch explanation appended. The generic half stays first and
   * unchanged because consumers and tests match on it.
   */
  private fun withLaunchReason(cause: String, system: String): String =
    liveLaneFailure(system)?.trim()?.takeIf { it.isNotEmpty() }?.let { "$cause: $it" } ?: cause

  private fun previewAliasFor(catalog: Catalog): Map<String, String> {
    val alias = LinkedHashMap<String, String>()
    for (component in catalog.components) {
      for (image in component.images) {
        val daemonId = image.previewId?.takeIf { it.isNotBlank() } ?: continue
        alias.putIfAbsent(previewIdFor(image.path), daemonId)
      }
    }
    for (record in catalog.deferred) {
      val id = deferredPreviewIdOf(record) ?: continue
      alias.putIfAbsent(id, record.daemonId ?: continue)
    }
    return alias
  }

  /**
   * Route-safe id for a [Deferred] record, or null when it can't be served: no `path`, a path
   * outside `images/` or attempting traversal, or no daemon twin.
   */
  private fun deferredPreviewIdOf(record: Deferred): String? {
    val path = record.path?.takeIf { it.isNotBlank() } ?: return null
    if (!path.startsWith("$IMAGES_DIR/") || !path.endsWith(".png")) return null
    if (".." in path.split("/")) return null
    if (record.daemonId == null) return null
    return previewIdFor(path)
  }

  /**
   * Route-safe id a published capture is served under, or null. Same containment as baked images;
   * the extension is checked against a closed list because these bytes go to a browser, and a
   * fetched suffix must not choose the content type.
   */
  private fun motionPreviewIdOf(path: String): String? {
    if (path.isBlank() || !path.startsWith("$MOTION_DIR/")) return null
    val extension = MOTION_EXTENSIONS.firstOrNull { path.endsWith(it) } ?: return null
    if (".." in path.split("/")) return null
    // Flattened like a still's path, so a capture named from its sibling sticker shares its id;
    // they are served from separate routes, so they never collide.
    return path.removePrefix("$MOTION_DIR/").removeSuffix(extension).replace("/", "__")
  }

  /** The extension [path] carries, or null when it is not a servable capture. */
  private fun motionExtensionOf(path: String): String? = MOTION_EXTENSIONS.firstOrNull {
    path.endsWith(it)
  }

  /**
   * One declared baked image resolved before any fetch: its route-safe [id], staged [target] (null
   * when the path escaped the previews dir, still counted as declared), and variant context.
   * Planning apart from fetching lets fetches run concurrently while consumption stays ordered.
   */
  private data class PlannedImage(
    val path: String,
    val id: String,
    val target: File?,
    val image: Image,
    val componentId: String?,
    val section: String?,
    val group: String?,
    val componentSourceFile: String?,
    val componentSourceModule: String?,
    val componentBodyLine: Int?,
    /** The owning component's authored one-line description, tagged onto each of its renders. */
    val componentCaption: String?,
    /** The production composable API published with the owning component. */
    val componentParameters: List<ComponentParameter>,
    val componentMotion: List<Motion>,
  )

  /**
   * The declared hero resolved against [bakedIds], or null. Tried in order:
   * 1. an exact preview id;
   * 2. a `componentId`, matched against the slug head with the exporter's normalisation;
   * 3. a `@Preview` function name, via each image's daemon [Image.previewId] ([heroForFunction]).
   *    The first two mirror [ServeBundleHost.declaredHeroPreviewId]; the resolved id is handed to
   *    the host as its declared hero so both agree.
   */
  private fun heroPreviewIdFor(catalog: Catalog, bakedIds: Set<String>): String? {
    val hero = catalog.display?.hero?.takeIf { it.isNotBlank() } ?: return null
    if (hero in bakedIds) return hero
    val wanted = heroSlugOf(hero)
    return bakedIds.firstOrNull { heroSlugOf(it.substringBefore(SLUG_SEPARATOR)) == wanted }
      ?: heroForFunction(catalog, hero, bakedIds)
  }

  /**
   * The baked preview a function-name [hero] names, matched through each image's daemon preview id
   * (a function publishes under its component's slug, not its own name). The simple name must equal
   * the function or the function plus a `_…` suffix; exact beats suffixed, and the default render
   * (no state or variant props) wins among matches. Null when nothing matches.
   */
  private fun heroForFunction(catalog: Catalog, hero: String, bakedIds: Set<String>): String? {
    val matches =
      catalog.components.flatMap { component ->
        component.images.mapNotNull { image ->
          val name =
            image.previewId?.substringAfterLast(':')?.substringAfterLast('.')
              ?: return@mapNotNull null
          val exact =
            when {
              name == hero -> true
              name.startsWith("${hero}_") -> false
              else -> return@mapNotNull null
            }
          val id = previewIdFor(image.path).takeIf { it in bakedIds } ?: return@mapNotNull null
          val isDefault =
            (image.state == null || image.state == "default") && image.props.isNullOrEmpty()
          Triple(id, exact, isDefault)
        }
      }
    return matches.sortedWith(compareBy({ !it.second }, { !it.third })).firstOrNull()?.first
  }

  sealed interface Result {
    data class Ok(
      val system: String,
      val previewCount: Int,
      val trust: String,
      val failedRenderCount: Int = 0,
      /**
       * Some optional artifact could not be fetched right now (throttled, branch host unwell). The
       * catalog is still served, but the caller must not record this revision as settled, since
       * retrying could answer differently. See `ServeCatalogRefresher.checkOne` and
       * `seedInitialHeads`.
       */
      val incomplete: Boolean = false,
    ) : Result

    data class Failed(val system: String, val reason: String) : Result
  }

  /**
   * Fetch the `<branchPrefix><system>` catalog, lay its images out as previews, and register it.
   *
   * [sourceRepo] / [sourceBranchPrefix] override the store defaults for this one system, so one
   * server can serve catalogs from different repos. The branch-trust verdict uses whichever repo
   * served it.
   */
  fun load(system: String, sourceRepo: String? = null, sourceBranchPrefix: String? = null): Result =
    inFetchScope {
      load(system, sourceRepo, sourceBranchPrefix, it)
    }

  private fun load(
    system: String,
    sourceRepo: String?,
    sourceBranchPrefix: String?,
    scope: FetchScope,
  ): Result {
    val safe = ServeBundleStore.sanitizeName(system) ?: return Result.Failed(system, "invalid name")
    // Bumped per load so a background pass started for an earlier generation of this catalog stops
    // instead of writing its vectors into the refreshed one.
    val generation = generations.merge(system, 1, Int::plus)!!
    val repo = sourceRepo?.takeIf { it.isNotBlank() } ?: this.repo
    val branchPrefix = sourceBranchPrefix?.takeIf { it.isNotBlank() } ?: this.branchPrefix
    val branch = "$branchPrefix$system"

    // Publish history is read first: its head decides what the rest of this load reads.
    // Best-effort; empty just drops the permalink affordance.
    val revisions = fetchRevisions(repo, branch)
    val deliveryCommit = revisions.firstOrNull()?.commit

    // A load reads one commit, not a branch: pinning the base to the resolved sha keeps a
    // multi-minute load from straddling a publish, and makes the served catalog and a pin to the
    // same revision read identical URLs. Falls back to the branch when the feed is unreadable.
    val base =
      deliveryCommit?.let { "https://raw.githubusercontent.com/$repo/$it/" }
        ?: "https://raw.githubusercontent.com/$repo/$branch/"
    // Only an immutable (pinned) base may populate the URL-keyed [CatalogBlobPool]; a branch ref is
    // a moving target.
    val pinned = deliveryCommit != null

    val catalogBytes =
      try {
        fetchCatalogAsset(base + CATALOG_FILE)
      } catch (e: Exception) {
        return Result.Failed(system, "could not fetch catalog.json: ${e.message}")
      } ?: return Result.Failed(system, "could not fetch $base$CATALOG_FILE")

    val catalog =
      try {
        json.decodeFromString(Catalog.serializer(), catalogBytes.toString(Charsets.UTF_8))
      } catch (e: Exception) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
        return Result.Failed(system, "could not parse catalog.json: $detail")
      }
    rememberPublishedRuntimeHistory(repo, revisions.drop(1).map { it.commit }.distinct())

    // One compact file answers preview availability for the whole revision menu. Older branches
    // have no index and deliberately fail open; the pinned catalog remains authoritative on click.
    val revisionPreviewIds = runCatching {
      ServeRevisionPreviewIndex.parse(fetchCatalogAsset(base + ServeRevisionPreviewIndex.FILE_NAME))
        ?.previewsByCommit(deliveryCommit.orEmpty())
    }
      .getOrNull()

    // GitHub caps the branch Atom feed, so metadata-only commit bursts can push every catalog
    // generation out of it. history.json, read from the same tree, restores those image revisions.
    // Older publishers lack it.
    val indexedPreviewHistory = runCatching {
      fetchCatalogAsset(base + PreviewHistoryManifest.FILE_NAME)
        ?.toString(Charsets.UTF_8)
        ?.let(PreviewHistoryManifest::decode)
        ?.takeIf { it.formatVersion == PreviewHistoryManifest.FORMAT_VERSION }
    }
      .getOrNull()

    // Images are fetched into a `.staging` dir and swapped in only once the catalog is usable, so a
    // failed re-load never turns a healthy catalog into 404s. Later steps (wasm, figma, liveBundle)
    // are fail-soft.
    //
    // The live directory is generation-scoped (`<root>/<system>/g<n>`) and a load never writes into
    // the registered one, so a host and the bytes it reads lazily switch together at registration.
    // Old generations are retired by the next load's sweep, giving in-flight reads a full refresh
    // interval ([retireStaleGenerations]).
    val systemRoot = File(root, safe)
    val dir = File(systemRoot, "$GENERATION_DIR_PREFIX$generation")
    val staging = File(systemRoot, STAGING_DIR)
    retireStaleGenerations(safe, systemRoot, keep = setOf(dir.name, staging.name))
    staging.deleteRecursively()
    // Only a leftover from a previous process (the counter climbs within one); the rename below
    // needs the path free.
    dir.deleteRecursively()
    val previewsDir = File(staging, "previews")
    val previewsRoot = previewsDir.canonicalFile.toPath()
    var count = 0
    // How many baked images the catalog DECLARES (before any fetch) — see the check after the
    // loops.
    var declaredImages = 0
    // The component slugs whose baked figma-svg to fetch (a slug is the preview id up to `__`).
    val slugs = LinkedHashSet<String>()
    // Exact vectors mirror each successfully fetched images/<slug>/<variant>.png. Carrying these
    // preserves the baked theme/locale/size axis; the flat slug vector is only a legacy fallback.
    val variantSvgPaths = LinkedHashSet<String>()
    // Per-preview state/theme, carried via `previews/variants.json` so the grid can fold states
    // into one card and the viewer can offer a switcher. Only previews with a state or theme are
    // recorded.
    val variants = LinkedHashMap<String, VariantMeta>()
    // Every declared baked image, flattened into catalog order with its component context, without
    // fetching. An image whose destination escapes the previews dir is planned with a null target
    // so it still counts toward `declaredImages`.
    // Captions are per component, so live-only records and failures borrow their component's
    // caption. A wholly deferred component carries its caption on the deferred record, so both are
    // read, components first.
    val captionByComponentId: Map<String?, String?> = buildMap {
      catalog.deferred.forEach { d ->
        val id = d.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
        val caption = d.caption?.takeIf { it.isNotBlank() } ?: return@forEach
        putIfAbsent(id, caption)
      }
      catalog.components.forEach { c ->
        val id = c.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
        val caption = c.caption?.takeIf { it.isNotBlank() } ?: return@forEach
        put(id, caption)
      }
    }
    val parametersByComponentId: Map<String, List<ComponentParameter>> = buildMap {
      catalog.components.forEach { component ->
        val id = component.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
        component.parameters.takeIf { it.isNotEmpty() }?.let { put(id, it) }
      }
    }
    val plannedImages =
      catalog.components.flatMap { component ->
        // The component's section/group tag every one of its previews (a component maps to one
        // section + group), so the tabbed landing can bucket + sub-head + order them.
        val section = component.section?.takeIf { it.isNotBlank() }
        val group = component.group?.takeIf { it.isNotBlank() }
        val componentId = component.componentId?.takeIf { it.isNotBlank() }
        val componentSourceFile = component.sourceFile?.takeIf { it.isNotBlank() }
        val componentSourceModule = component.sourceModule?.takeIf { it.isNotBlank() }
        val componentBodyLine = component.bodyLine?.takeIf { it > 0 }
        val componentCaption = component.caption?.takeIf { it.isNotBlank() }
        val componentParameters = component.parameters
        component.images.mapNotNull { image ->
          val path = image.path
          // Only image-directory PNGs; reject traversal. The path is from a trusted branch, but a
          // containment check costs nothing and guards a compromised/garbled catalog.
          val segments = path.split("/")
          if (!path.startsWith("$IMAGES_DIR/") || !path.endsWith(".png") || ".." in segments)
            return@mapNotNull null
          val id = previewIdFor(path)
          val target =
            File(previewsDir, "$id.png").takeIf {
              it.canonicalFile.toPath().startsWith(previewsRoot)
            }
          PlannedImage(
            path,
            id,
            target,
            image,
            componentId,
            section,
            group,
            componentSourceFile,
            componentSourceModule,
            componentBodyLine,
            componentCaption,
            componentParameters,
            component.motion,
          )
        }
      }

    // Walk the plan for metadata only: `catalog.json` names every card, so the grid publishes
    // without waiting for pixels. Each id is handed to the host, which fetches its PNG on first use
    // ([ServeBundleHost.fetchBakedPng]).
    val bakedPathById = LinkedHashMap<String, String>()
    // Branch path per capture route id, fetched by the host on demand. Captures are not staged:
    // they are much heavier than stickers and rarely opened.
    val motionPathById = LinkedHashMap<String, String>()
    var imageLimitExceeded = false
    for (planned in plannedImages) {
      if (count >= maxImages) {
        imageLimitExceeded = true
        break
      }
      // Counted as declared so the completeness check can tell an all-deferred catalog (legal) from
      // one whose images the branch can't serve (an outage).
      declaredImages++
      if (planned.target == null) continue
      run {
        val id = planned.id
        val image = planned.image
        bakedPathById[id] = planned.path
        slugs.add(id.substringBefore(SLUG_SEPARATOR))
        planned.path
          .removePrefix("$IMAGES_DIR/")
          .removeSuffix(".png")
          .takeIf { it.count { char -> char == '/' } == 1 }
          ?.let { variantSvgPaths.add("$it.svg") }
        // Record variant metadata for any preview with state/theme or a section/group tag. The
        // authored `order` rides only with section/group tags (it orders the tabbed landing); a
        // catalog with neither stays a flat grid.
        val hasSectionInfo = planned.section != null || planned.group != null
        val props = image.props?.takeIf { it.isNotEmpty() }
        val size = image.size?.takeIf { it.isNotBlank() }
        // Pair captures to this card by the theme the export recorded, not by parsing filenames, so
        // there is only one implementation of the naming rule. An unthemed capture belongs to every
        // card of its component.
        val motion =
          planned.componentMotion.mapNotNull { capture ->
            val theme = capture.theme?.takeIf { it.isNotBlank() }
            if (theme != null && !theme.equals(image.theme, ignoreCase = true))
              return@mapNotNull null
            val motionId = motionPreviewIdOf(capture.path) ?: return@mapNotNull null
            val extension = motionExtensionOf(capture.path) ?: return@mapNotNull null
            motionPathById[motionId] = capture.path
            MotionMeta(
              id = motionId,
              kind = capture.kind.takeIf { it.isNotBlank() },
              caption = capture.caption?.takeIf { it.isNotBlank() },
              extension = extension,
            )
          }
        if (
          motion.isNotEmpty() ||
            image.state != null ||
            image.theme != null ||
            props != null ||
            size != null ||
            planned.componentId != null ||
            hasSectionInfo ||
            planned.componentSourceFile != null ||
            planned.componentSourceModule != null ||
            image.overrides.isNotEmpty() ||
            image.remoteComposeKnobs.isNotEmpty() ||
            image.supportsFocus ||
            image.supportsGestures ||
            image.fixedTheme ||
            image.secondary ||
            planned.componentParameters.isNotEmpty() ||
            // Without this, an image whose only metadata is `previewParams` would be dropped from
            // the manifest and the host would read it back as absent.
            image.previewParams != null
        ) {
          variants[id] =
            VariantMeta(
              state = image.state,
              theme = image.theme,
              props = props,
              size = size,
              componentId = planned.componentId,
              overrides = image.overrides,
              remoteComposeKnobs = image.remoteComposeKnobs,
              supportsFocus = image.supportsFocus,
              supportsGestures = image.supportsGestures,
              fixedTheme = image.fixedTheme,
              secondary = image.secondary,
              motion = motion,
              section = planned.section,
              group = planned.group,
              order = if (hasSectionInfo) count else null,
              sourceFile = planned.componentSourceFile,
              sourceModule = planned.componentSourceModule,
              bodyLine = planned.componentBodyLine,
              caption = planned.componentCaption,
              componentParameters = planned.componentParameters.map(ComponentParameter::toServe),
              // The ground and frame this render was captured with, lifted by the export from the
              // bundle's `previews.json`.
              previewParams = image.previewParams,
            )
        }
        count++
      }
    }
    // Live-only (deferred) previews: their variant metadata joins the same manifest so cards group
    // and order with baked ones, but the ids go to the host separately and only where a live lane
    // can render them. Baked pixels win over a duplicate id.
    val deferredIds = LinkedHashSet<String>()
    for (record in catalog.deferred) {
      val id = deferredPreviewIdOf(record) ?: continue
      // Checked against the declared baked set: nothing is on disk yet with lazy fetching.
      if (variants.containsKey(id) || bakedPathById.containsKey(id)) continue
      if (count + deferredIds.size >= maxImages) {
        imageLimitExceeded = true
        break
      }
      if (!deferredIds.add(id)) continue
      val section = record.section?.takeIf { it.isNotBlank() }
      val group = record.group?.takeIf { it.isNotBlank() }
      variants[id] =
        VariantMeta(
          state = record.state,
          theme = record.theme,
          props = record.props?.takeIf { it.isNotEmpty() },
          size = record.size?.takeIf { it.isNotBlank() },
          componentId = record.componentId?.takeIf { it.isNotBlank() },
          fixedTheme = record.fixedTheme,
          secondary = record.secondary,
          section = section,
          group = group,
          order = if (section != null || group != null) count + deferredIds.size - 1 else null,
          // A live-only record carries no caption of its own, so borrow its component's.
          caption =
            record.caption?.takeIf { it.isNotBlank() }
              ?: captionByComponentId[record.componentId?.takeIf { it.isNotBlank() }],
          componentParameters =
            parametersByComponentId[record.componentId?.takeIf { it.isNotBlank() }]
              .orEmpty()
              .map(ComponentParameter::toServe),
        )
    }

    // Failed renders are listed as cards with diagnostics. Deliberately not live-only ids: a static
    // catalog must never turn a failure into a silent 404.
    val failedIds = LinkedHashSet<String>()
    for ((index, failure) in catalog.failures.withIndex()) {
      if (count + deferredIds.size + failedIds.size >= maxImages) {
        imageLimitExceeded = true
        break
      }
      // Catalog JSON is fetched input. Never reuse its id as a filesystem path: generate a
      // single-segment route id from descriptive fields, then suffix collisions deterministically.
      fun failureSlug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifBlank { "unknown" }
      val base =
        "render-failed--${failureSlug(failure.componentId.orEmpty())}--" +
          failureSlug(failure.preview ?: failure.id.ifBlank { index.toString() })
      var id = base
      var suffix = 2
      while (id in bakedPathById || id in deferredIds || id in failedIds) {
        id = "$base--${suffix++}"
      }
      failedIds.add(id)
      variants[id] =
        VariantMeta(
          state = failure.state,
          theme = failure.mode,
          props = failure.props,
          componentId = failure.componentId,
          section = failure.section,
          group = failure.group,
          order = count + deferredIds.size + failedIds.size - 1,
          sourceFile = failure.sourceFile,
          caption = captionByComponentId[failure.componentId?.takeIf { it.isNotBlank() }],
          componentParameters =
            parametersByComponentId[failure.componentId?.takeIf { it.isNotBlank() }]
              .orEmpty()
              .map(ComponentParameter::toServe),
          renderFailure = failure,
        )
    }

    // A catalog is an inventory: truncating would silently drop components. Reject the staged
    // generation; a refresh keeps the previous one.
    if (imageLimitExceeded) {
      staging.deleteRecursively()
      return Result.Failed(system, "catalog exceeds the $maxImages image limit")
    }

    // Nothing to serve is a failure only when the catalog declared baked images and none fetched
    // (an outage; leave the served dir untouched). A wholly live-only catalog is legal: the live
    // builders register its deferred ids, or the baked host explains it via `deferred-not-served`.
    if (count == 0 && failedIds.isEmpty() && (declaredImages > 0 || deferredIds.isEmpty())) {
      staging.deleteRecursively()
      return Result.Failed(system, "catalog had no usable images")
    }

    // Fetch a small sample, hero first, before publishing: it lands the front-door image and proves
    // the branch serves pixels, so a branch returning 404s can't replace a working catalog. A
    // sample rather than one image so a single missing file doesn't fail the whole catalog.
    // The hero is resolved here and handed to the host, since function-name heroes resolve only
    // through daemon ids the host never sees.
    val heroId = heroPreviewIdFor(catalog, bakedPathById.keys)
    if (bakedPathById.isNotEmpty()) {
      val heroPath = heroId?.let(bakedPathById::get)
      val sample =
        (listOfNotNull(heroPath) + bakedPathById.values).distinct().take(PUBLISH_SAMPLE_IMAGES)
      val landed =
        fetchCatalogAssetsToFiles(
          sample.map { path -> (base + path) to File(previewsDir, "${previewIdFor(path)}.png") }
        )
      if (landed.isEmpty()) {
        staging.deleteRecursively()
        return Result.Failed(system, "catalog images could not be fetched from its branch")
      }
    }

    // Written before the swap so a reader never sees `variants.json` disagree with its images.
    // Absent for a plain catalog.
    if (variants.isNotEmpty()) {
      val manifest =
        json.encodeToString(MapSerializer(String.serializer(), VariantMeta.serializer()), variants)
      // A wholly-deferred catalog wrote no PNG, so the staged previews dir may not exist yet.
      previewsDir.mkdirs()
      File(previewsDir, VARIANTS_FILE).writeText(manifest)
    }

    // Fetch only declared, producer-normalized design-tool PNGs into server-owned paths.
    // `source.uri` and `artifact.path` are never fetched, so a reference cannot turn refresh into
    // code execution or an authenticated request.
    val manifestReferences = fetchDesignReferences(base)
    val referenceBranchPaths =
      writeDesignReferences(manifestReferences + catalog.references, base, staging)
    writeAnnotations(base, staging)
    writeTagIndex(base, staging)
    writeParityActivity(base, staging)
    writeParityIssues(base, staging)
    writeGuidelineResults(base, staging)
    writeParityFindings(base, staging)
    writeDesignPages(base, staging)
    writeKnownDifferences(base, staging)
    writeComponentRecord(base, staging, catalog)
    writeUiBuilderCatalog(base, staging, catalog)
    writeUiBuilderRuntime(base, staging, catalog)?.let { failure ->
      staging.deleteRecursively()
      return Result.Failed(system, failure)
    }

    // Nothing serves from `dir` until [publishGeneration], so this is a rename onto a free path.
    if (!staging.renameTo(dir)) {
      // Cross-device or a racing reader held a handle — copy then drop the staging dir.
      staging.copyRecursively(dir, overwrite = true)
      staging.deleteRecursively()
    }

    // Optional in-browser Wasm tier: fetch a declared `compose-wasm` app into `<dir>/web/wasm/`
    // (best-effort; file list from the trusted catalog, each file contained and capped). Registered
    // at [publishGeneration], since it belongs to this generation.
    val wasmDir = fetchWasmApp(catalog.webRender, base, dir, safe)
    val wasmRegistered = wasmDir != null

    val verdict =
      if (trust().trustsBranch(repo, branch))
        BundleVerifier.Verdict.Trusted(listOf(BundleVerifier.Basis.Branch(repo, branch)))
      else BundleVerifier.Verdict.Unverified("branch $repo@$branch is not trusted")

    // Baked editable vectors (figma/<slug>.svg + crops) are a server-side input nothing requests on
    // demand, so they can't be lazy; instead probe a handful of components synchronously (hero
    // first, distinct slugs, since any one may legitimately lack a vector) and fill the rest in the
    // background. Cards render uncropped until the pass lands.
    val figmaProbeIds =
      (listOfNotNull(heroId) + bakedPathById.keys)
        .distinctBy { it.substringBefore(SLUG_SEPARATOR) }
        .take(FIGMA_PROBE_SLUGS)
    val figmaDir = probeFigmaSvg(figmaProbeIds.flatMap(::heroSvgCandidates), base, dir)
    // Scheduled unconditionally: a sample miss doesn't prove the branch has no vectors.
    scheduleFigmaSvgFetch(system, generation, slugs, variantSvgPaths, base, dir)

    // Sampled integrity audit of cached blobs, pinned loads only (un-pinned caches nothing). On the
    // post-publish lane so it never outweighs the request path.
    if (pinned) {
      val sample = bakedPathById.values.take(AUDIT_SAMPLE_ASSETS).map { base + it }
      if (sample.isNotEmpty()) {
        figmaExecutor.execute { runCatching { auditCachedAssets(sample, safe) } }
      }
    }

    // The catalog's own palette for page chrome ([ServeThemeCss]). Best-effort: any failure leaves
    // the built-in chrome.
    val webThemeCss = fetchWebThemeCss(catalog.tokensFile, base)

    // The static baked-PNG host is always the browse surface; a live builder fronts it with a
    // daemon rather than replacing it, so `/p/<id>` links keep resolving. Built lazily so the
    // registry can rebuild it on resume.
    // [degradations] is empty when this host merely fronts a live daemon and populated only at the
    // terminal registration. [liveOnly] (deferred ids) is passed only by live builders that can
    // render them.
    // [alias] bridges catalog ids to daemon descriptor ids (unmapped ids fall back to baked PNGs).
    // Read first because it also decides whether the published-comparison lane runs
    // ([ServeRcCompare.stagesFor]).
    val alias = previewAliasFor(catalog)

    val bakedFallback: (List<ServeDegradation>, List<String>) -> ServeBundleHost =
      { degradations, liveOnly ->
        ServeBundleHost(
          dir,
          safe,
          verdict,
          // The one construction site with a `catalog.json` behind it. See
          // [ServeBundleHost.isCatalog].
          isCatalog = true,
          title = catalog.title?.takeIf { it.isNotBlank() },
          subtitle =
            catalog.library.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { null },
          // Declared presentation hints, so the front door and grid use the system's own choice.
          stageSurface = catalog.display?.surface?.takeIf { it.isNotBlank() },
          catalogRole = catalog.display?.role?.takeIf { it.isNotBlank() },
          declaredHero = heroId ?: catalog.display?.hero?.takeIf { it.isNotBlank() },
          webThemeCss = webThemeCss,
          figmaDir = figmaDir,
          provenance =
            ServeWeb.CatalogProvenance(
              repo = repo,
              branch = branch,
              // The branch tip this load was pinned to, which permalinks name; null only drops the
              // permalink affordance.
              commit = deliveryCommit,
              generatedAt = catalog.generatedAt?.takeIf { it.isNotBlank() },
              // `renderer` is `compose-preview <version>`; show just the version.
              toolVersion =
                catalog.renderer
                  ?.takeIf { it.isNotBlank() }
                  ?.removePrefix("compose-preview")
                  ?.trim()
                  ?.ifBlank { null },
              designParityVersion = catalog.designParity?.takeIf { it.isNotBlank() },
            ),
          // The catalog's Kotlin source (repo/ref/module), so the viewer can link a preview to its
          // source.
          catalogSource =
            catalog.source
              ?.takeIf { it.repo.isNotBlank() && it.ref.isNotBlank() }
              ?.let { ServeWeb.CatalogSource(it.repo, it.ref, it.module) },
          // Cross-system pairing straight from `catalog.json`; absent means no second comparison
          // source.
          compareWithSystem = catalog.compareWith?.system?.takeIf { it.isNotBlank() },
          // Read from both lists, components last so they win, as in [captionByComponentId]: a
          // wholly deferred component carries its pairing only on the deferred record.
          parallelByComponentId =
            buildMap {
              catalog.deferred.forEach { deferred ->
                val id = deferred.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                val parallel = deferred.parallel?.takeIf { it.isNotBlank() } ?: return@forEach
                // First wins among deferred records, like [previewAliasFor].
                putIfAbsent(id, parallel)
              }
              catalog.components.forEach { component ->
                val id = component.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                val parallel = component.parallel?.takeIf { it.isNotBlank() } ?: return@forEach
                // `put`, not `putIfAbsent`: a component that IS in `components[]` states its own
                // pairing, and a deferred *variant* record sharing its id only inherits one.
                put(id, parallel)
              }
            },
          // Same lists and order as `parallelByComponentId`. Normalised once here since producer
          // slips don't change between reads.
          relatedByComponentId = buildMap {
              catalog.deferred.forEach { deferred ->
                val id = deferred.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                if (deferred.related.isEmpty()) return@forEach
                putIfAbsent(id, deferred.related)
              }
              catalog.components.forEach { component ->
                val id = component.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                if (component.related.isEmpty()) return@forEach
                put(id, component.related)
              }
            }
              .mapValues { (_, entries) ->
                ServeRelatedCatalogs.declaredFor(
                  entries.map {
                    ServeRelatedCatalogs.Declared(it.system, it.componentId, it.label)
                  },
                  selfSystem = system,
                )
              }
              .filterValues { it.isNotEmpty() },
          degradations = degradations,
          liveOnly = liveOnly,
          // Kept in step with [scheduleRcCompareFetch]'s own guard through the shared helper: a
          // host that claims a lane which never runs stays "pending" forever and never caches.
          stagesRcCompare = ServeRcCompare.stagesFor(alias),
          // Every id the catalog bakes, so the grid is complete the moment `catalog.json` lands…
          declaredBaked = bakedPathById.keys.toList() + failedIds,
          // …and the pixels follow on first use. The store keeps ownership of the network here: the
          // host never builds a URL or applies a fetch policy, it just asks for an id it declared.
          fetchBakedPng = { id ->
            bakedPathById[id]?.let { path -> fetchCatalogAsset(base + path) }
          },
          // Motion ids and their lazy fetch; captures are never staged (see [motionPathById]).
          declaredMotion = motionPathById.keys.toList(),
          fetchMotion = { id ->
            motionPathById[id]?.let { path -> fetchCatalogAssetOutcome(base + path) }
              ?: BranchFetch.NotFound
          },
          motionBranchPaths = motionPathById.toMap(),
          // The branch path of every baked render, so a pinned (`?at=<sha>`) request can be
          // answered out of the same tree at an older commit — see [ServeCatalogRevision].
          bakedBranchPaths = bakedPathById.toMap(),
          referenceBranchPaths = referenceBranchPaths,
          revisions = revisions,
          revisionPreviewIds = revisionPreviewIds,
          indexedPreviewHistory = indexedPreviewHistory,
          // Read off the branch, not the resolved head: this asks when these bytes moved, and
          // pinning would hide later publishes.
          fetchRenderChanges = { path -> fetchRenderChanges(repo, branch, path) },
          // Same seam as `fetchBakedPng`: the host names a commit and a published path, the store
          // builds the URL and applies the fetch policy. Null repo ⇒ no pinned lane at all.
          fetchPinnedAsset = { commit, path ->
            ServeCatalogRevision.assetUrl(repo, commit, path)?.let { fetchCatalogAsset(it) }
          },
          // The same read, but reporting WHY it failed — which is the only thing that makes the
          // host's permanent negative cache safe. See [ServeBundleHost.fetchPinnedAssetOutcome].
          fetchPinnedAssetOutcome = { commit, path ->
            ServeCatalogRevision.assetUrl(repo, commit, path)?.let { fetchCatalogAssetOutcome(it) }
              ?: BranchFetch.NotFound
          },
          // Ids are stable across publishes but paths are not, so a pinned request resolves its
          // path from the manifests at that commit, falling back to the tip's maps.
          pinnedManifest =
            ServePinnedManifest(
              fetch = { commit, file ->
                ServeCatalogRevision.manifestUrl(repo, commit, file)?.let { fetchCatalogAsset(it) }
              }
            ),
        )
      }

    // Published Remote Compose player comparison, fetched in the background so the catalog doesn't
    // wait on ~150 small PNGs.
    scheduleRcCompareFetch(system, generation, alias, base, dir)

    // Trusted re-render from a carried executable bundle (opt-in), tried before the Gradle `source`
    // build. Only a Trusted catalog that declares `liveBundle` reaches the builder, and only once
    // the whole bundle fetched cleanly. The builder fronts [bakedFallback] with the daemon.
    // [liveBundleFallback] records why a declared liveBundle didn't yield a live session, for the
    // terminal baked host to explain.
    var liveBundleFallback: ServeDegradation? = null
    var trustedBundlesRecorded = false
    val declaredLiveBundles = catalog.liveBundles
    val multiLiveBundle = declaredLiveBundles.size > 1
    if (verdict is BundleVerifier.Verdict.Trusted && multiLiveBundle) {
      val nonEmptyPrefixes =
        declaredLiveBundles.map { it.previewIdPrefix }.filter { it.isNotEmpty() }
      val descriptorsValid =
        nonEmptyPrefixes.distinct().size == nonEmptyPrefixes.size &&
          declaredLiveBundles.count { it.previewIdPrefix.isEmpty() } == 1
      if (!descriptorsValid) {
        liveBundleFallback =
          ServeDegradation.liveBundleUnavailable("the module bundle identity map is invalid")
      } else {
        val prepared = mutableListOf<TrustedModuleBundle>()
        // The empty-prefix bundle is primary regardless of position: the runner opens the first
        // bundle's daemon unwrapped, and only its local ids equal the catalog's (see #1351).
        for (descriptor in declaredLiveBundles.sortedBy { it.previewIdPrefix.isNotEmpty() }) {
          val moduleAlias = alias.filterValues { daemonId ->
            if (descriptor.previewIdPrefix.isNotEmpty()) {
              daemonId.startsWith(descriptor.previewIdPrefix)
            } else {
              nonEmptyPrefixes.none(daemonId::startsWith)
            }
          }
          val bundleFile = fetchLiveBundle(descriptor, base, dir, safe, pinned)
          if (bundleFile == null) {
            prepared.clear()
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                "the ${descriptor.module.ifBlank { "primary" }} module bundle could not be fetched"
              )
            break
          }
          // The catalog namespace need not appear inside a module's bundle: prefer an exact
          // manifest match (older publishers), else strip only the declared outer prefix.
          val bundleIds = runCatching {
            BundleReader.readMetadata(bundleFile).manifest.previewIds.toSet()
          }
            .getOrDefault(emptySet())
          val localPreviewIds =
            moduleAlias.values.associateWith { id ->
              val local = id.removePrefix(descriptor.previewIdPrefix)
              if (id !in bundleIds && local in bundleIds) local else id
            }
          val localAlias = moduleAlias.mapValues { (_, id) -> localPreviewIds.getValue(id) }
          extractCatalogRcDocs(bundleFile, localAlias, dir)
          extractComponentRecord(bundleFile, dir)
          extractGuidelineResults(bundleFile, dir)
          val resources =
            when (
              val res = rehydrateExternalResources(bundleFile, base, descriptor.path, dir, safe)
            ) {
              is ResRehydrate.Ready -> res.dir
              ResRehydrate.Unavailable -> {
                prepared.clear()
                liveBundleFallback =
                  ServeDegradation.liveBundleUnavailable(
                    "a resource for ${descriptor.module.ifBlank { "the primary module" }} could not be rehydrated"
                  )
                break
              }
            }
          val safeStems = uniquePerPreviewStems(localPreviewIds.values)
          prepared +=
            TrustedModuleBundle(
              module = descriptor.module,
              file = bundleFile,
              externalResourcesDir = resources,
              alias = moduleAlias,
              localPreviewIds = localPreviewIds,
              perPreviewBundle =
                PerPreviewBundleAccess(
                  available = { daemonId ->
                    safeStems[localPreviewIds[daemonId]]?.let { stem ->
                      perPreviewBundleAvailable(stem, descriptor, base, dir, pinned)
                    } ?: false
                  },
                  fetch = { daemonId ->
                    safeStems[localPreviewIds[daemonId]]?.let { stem ->
                      fetchPerPreviewBundle(stem, descriptor, base, dir, safe, resources, pinned)
                    }
                  },
                ),
            )
        }
        val preparedCompletely =
          prepared.size == declaredLiveBundles.size && prepared.all { it.alias.isNotEmpty() }
        if (preparedCompletely) {
          recordTrustedBundles(
            safe,
            prepared.map { VerifiedModuleBundle(module = it.module, file = it.file) },
          )
          trustedBundlesRecorded = true
        }
        if (
          preparedCompletely &&
            buildTrustedBundles(
              safe,
              prepared,
              { bakedFallback(emptyList(), deferredIds.toList()) },
            )
        ) {
          publishGeneration(safe, dir, wasmDir)
          return Result.Ok(
            safe,
            count + deferredIds.size + failedIds.size,
            "${BundleVerifier.summary(verdict)} (${prepared.size} live module bundles)",
            failedIds.size,
            incomplete = scope.sawTransientFailure,
          )
        }
        if (liveBundleFallback == null) {
          liveBundleFallback =
            ServeDegradation.liveBundleUnavailable(
              withLaunchReason("the module bundle daemons could not be started", safe)
            )
        }
      }
    }
    // A multi-module declaration is atomic: never silently start only its primary legacy bundle.
    val liveBundle = if (multiLiveBundle) null else catalog.liveBundle
    if (verdict is BundleVerifier.Verdict.Trusted && liveBundle != null) {
      val bundleFile = fetchLiveBundle(liveBundle, base, dir, safe, pinned)
      if (bundleFile == null) {
        liveBundleFallback =
          ServeDegradation.liveBundleUnavailable(
            "the bundle could not be fetched from the delivery branch"
          )
      } else {
        recordTrustedBundles(
          safe,
          listOf(VerifiedModuleBundle(module = liveBundle.module, file = bundleFile)),
        )
        trustedBundlesRecorded = true
        // Extract captured `.rc` documents re-keyed to catalog ids for the in-browser canvas lane.
        // Done regardless of daemon outcome, since that lane needs no daemon.
        extractCatalogRcDocs(bundleFile, alias, dir)
        extractComponentRecord(bundleFile, dir)
        extractGuidelineResults(bundleFile, dir)
        // IR-backed previews have no class in app.jar; the daemon replays them from `ir/`, so they
        // stay in the alias.
        // Rehydrate externalized resources (fonts) from the branch's content-addressed pool.
        // Fail-closed: a missing resource would make the daemon render without fonts, so skip to
        // the source/static path instead.
        when (val res = rehydrateExternalResources(bundleFile, base, liveBundle.path, dir, safe)) {
          is ResRehydrate.Ready -> {
            // Per-preview live lane: each daemon id maps to its own split bundle, fetched on demand
            // and sharing the monolithic bundle's font pool ([res.dir]).
            // `bundle split` writes colliding sanitised ids as `<base>.png`, `<base>-2.png`, …, and
            // the suffix order is unknowable here, so only ids with a unique stem use this lane;
            // others fall back to the monolithic daemon.
            val safeStems = uniquePerPreviewStems(alias.values)
            val perPreviewBundle =
              PerPreviewBundleAccess(
                available = { daemonId ->
                  safeStems[daemonId]?.let { stem ->
                    perPreviewBundleAvailable(stem, liveBundle, base, dir, pinned)
                  } ?: false
                },
                fetch = { daemonId ->
                  safeStems[daemonId]?.let { stem ->
                    fetchPerPreviewBundle(stem, liveBundle, base, dir, safe, res.dir, pinned)
                  }
                },
              )
            if (
              alias.isNotEmpty() &&
                buildTrustedBundle(
                  safe,
                  bundleFile,
                  res.dir,
                  alias,
                  { bakedFallback(emptyList(), deferredIds.toList()) },
                  perPreviewBundle,
                )
            ) {
              publishGeneration(safe, dir, wasmDir)
              return Result.Ok(
                safe,
                count + deferredIds.size + failedIds.size,
                "${BundleVerifier.summary(verdict)} (live bundle)",
                failedIds.size,
                incomplete = scope.sawTransientFailure,
              )
            }
            // The builder didn't stand a daemon up (usually `--allow-render-trusted` is off or the
            // backend can't run here): fall through to source/static with a reason.
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                if (serverSideRenderEnabled)
                  withLaunchReason("the live bundle daemon could not be started", safe)
                else "server-side re-render is not enabled on this server"
              )
          }
          // Fall through to source/static — a declared resource couldn't be rehydrated.
          ResRehydrate.Unavailable ->
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                "a required font or resource could not be rehydrated"
              )
        }
      }
    }

    // Trusted re-render from source (opt-in): only a Trusted catalog that declares a source reaches
    // the builder, so a spoofed catalog can't trigger a build.
    if (!trustedBundlesRecorded) clearTrustedBundles(safe)
    val src = catalog.source
    if (
      verdict is BundleVerifier.Verdict.Trusted &&
        src != null &&
        src.module.isNotBlank() &&
        buildTrustedSource(
          safe,
          CatalogSource(src.repo, src.ref, src.module),
          alias,
          { bakedFallback(emptyList(), deferredIds.toList()) },
        )
    ) {
      publishGeneration(safe, dir, wasmDir)
      return Result.Ok(
        safe,
        count + deferredIds.size + failedIds.size,
        "${BundleVerifier.summary(verdict)} (live)",
        failedIds.size,
        incomplete = scope.sawTransientFailure,
      )
    }

    // Terminal: no server-side live lane, so register the baked host and record why it is
    // snapshot-only, unless the Wasm tier registered (that is a live lane). Priority: a specific
    // liveBundle failure > an unverified catalog that declared a live lane > no live bundle
    // published.
    // [deferredNote] is recorded whenever live-only coverage is omitted here, even with Wasm, since
    // the in-browser tier renders only baked previews.
    val deferredNote =
      deferredIds.takeIf { it.isNotEmpty() }?.let { ServeDegradation.deferredNotServed(it.size) }
    val degradations =
      if (wasmRegistered) listOfNotNull(deferredNote)
      else
        listOfNotNull(
          liveBundleFallback
            ?: when {
              verdict is BundleVerifier.Verdict.Unverified &&
                ((liveBundle != null || declaredLiveBundles.isNotEmpty()) ||
                  (src != null && src.module.isNotBlank())) ->
                ServeDegradation.unverifiedNoRerender()
              else -> ServeDegradation.catalogBakedOnly()
            },
          deferredNote,
        )
    // Same reasoning as the degradation: a session with no live lane lists no live-only previews.
    val host = bakedFallback(degradations, emptyList())
    publishGeneration(safe, dir, wasmDir)
    register(safe, host)
    return Result.Ok(
      safe,
      host.previews.size,
      BundleVerifier.summary(verdict),
      failedIds.size,
      incomplete = scope.sawTransientFailure,
    )
  }

  /**
   * Fetch a `compose-wasm` [WebRender] app from [base] into `<dir>/web/wasm/`. The file list comes
   * from the trusted [render]; each entry is path-contained and size-capped. Returns the app dir
   * only if the whole app (including `index.html`) fetched, else null, leaving the session
   * snapshot-only.
   */
  private fun fetchWasmApp(render: WebRender?, base: String, dir: File, system: String): File? {
    if (render == null || render.kind != WEB_RENDER_COMPOSE_WASM) return null
    val prefix = render.path.trim('/')
    if (prefix.isEmpty() || render.files.isEmpty()) return null
    val wasmDir = File(dir, WEB_WASM_DIR)
    // Fail closed, all-or-nothing: register only if every declared file fetched and `index.html`
    // exists. A partial app would advertise "Run in browser" and then 404 its module fetches.
    fun fail(reason: String): File? {
      wasmDir.deleteRecursively()
      System.err.println("serve: $system web/wasm/ incomplete ($reason) — in-browser tier disabled")
      return null
    }
    if (render.files.size > MAX_WASM_FILES) return fail("more than $MAX_WASM_FILES files declared")
    val wasmRoot = wasmDir.canonicalFile.toPath()
    for (name in render.files) {
      val rel = name.trim('/')
      if (rel.isEmpty() || ".." in rel.split("/")) return fail("invalid entry '$name'")
      val target = File(wasmDir, rel)
      if (!target.canonicalFile.toPath().startsWith(wasmRoot)) return fail("escaping entry '$name'")
      val bytes =
        runCatching { fetchCatalogAsset("$base$prefix/$rel") }.getOrNull()
          ?: return fail("missing $rel")
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
    if (!File(wasmDir, "index.html").isFile) return fail("no index.html")
    return wasmDir
  }

  /**
   * Fetch a catalog's executable `liveBundle` from `<base><path>/<file>`. Fail-closed like
   * [fetchWasmApp]: any invalid entry or fetch miss returns null, so the caller falls back to the
   * source build or the static host.
   *
   * When [pinned], [base] names one immutable tree, so the ~100 MB download is cached in [blobs]
   * (durable across restarts with `--catalog-cache-dir`). An un-pinned load addresses a moving
   * branch and stages into `<dir>/$LIVE_BUNDLE_DIR/<file>` instead.
   */
  private fun fetchLiveBundle(
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
    pinned: Boolean,
  ): File? {
    val name = liveBundle.file.trim('/')
    if (name.isEmpty() || ".." in name.split("/")) {
      System.err.println("serve: $system liveBundle has an invalid file entry — skipping")
      return null
    }
    val prefix = liveBundle.path.trim('/')
    val url = if (prefix.isEmpty()) "$base$name" else "$base$prefix/$name"

    if (pinned) {
      val blob =
        blobs.keyed(url) { dest ->
          val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
          if (bytes == null) false
          else {
            dest.writeBytes(bytes)
            true
          }
        }
      if (blob == null) {
        System.err.println("serve: $system liveBundle fetch failed ($url) — skipping")
      }
      return blob
    }

    val bundleDir = File(dir, LIVE_BUNDLE_DIR)
    val bundleRoot = bundleDir.canonicalFile.toPath()
    val target = File(bundleDir, name)
    if (!target.canonicalFile.toPath().startsWith(bundleRoot)) {
      System.err.println("serve: $system liveBundle escaping entry '$name' — skipping")
      return null
    }
    val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
    if (bytes == null) {
      System.err.println("serve: $system liveBundle fetch failed ($url) — skipping")
      return null
    }
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
    return target
  }

  /**
   * Stage the declared component record at `<staging>/components.json`. Fail-soft; a live bundle
   * may still supply it via [extractComponentRecord]. Validation is structural only, so a newer
   * producer's schema version isn't reported as malformed.
   */
  private fun writeComponentRecord(base: String, staging: File, catalog: Catalog) {
    val name = catalog.componentsFile?.trim('/')?.takeIf { it.isNotEmpty() } ?: return
    if (".." in name.split("/")) return
    val bytes = runCatching { fetchCatalogAsset("$base$name") }.getOrNull() ?: return
    if (!looksLikeComponentRecord(bytes)) return
    File(staging, COMPONENT_RECORD_FILE).writeBytes(bytes)
  }

  /**
   * Lift `components.json` out of the fetched live [bundleFile] unless the branch already supplied
   * one. The branch copy wins: it is the one addressed to readers, and a multi-module catalog's
   * first bundle holds only one module's record. Best-effort.
   */
  private fun extractComponentRecord(bundleFile: File, dir: File) {
    val target = File(dir, COMPONENT_RECORD_FILE)
    if (target.isFile) return
    val bytes = runCatching { componentRecordEntry(bundleFile) }.getOrNull() ?: return
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
  }

  /** The `components.json` entry of a packed bundle, or null when it carries none. */
  private fun componentRecordEntry(bundleFile: File): ByteArray? {
    val zipBytes = BundleReader.extractZipBytes(bundleFile)
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        if (!entry.isDirectory && entry.name.replace('\\', '/') == COMPONENT_RECORD_FILE) {
          val buf = ByteArrayOutputStream()
          val chunk = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val n = zin.read(chunk)
            if (n < 0) break
            total += n
            check(total <= MAX_COMPONENT_RECORD_BYTES) { "component record exceeds the cap" }
            buf.write(chunk, 0, n)
          }
          val bytes = buf.toByteArray()
          return bytes.takeIf(::looksLikeComponentRecord)
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
    return null
  }

  private fun looksLikeComponentRecord(bytes: ByteArray): Boolean = runCatching {
    val root = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return false
    root["schemaVersion"] is JsonPrimitive && root["components"] is JsonArray
  }
    .getOrDefault(false)

  /**
   * Stage the declared builder catalog at `<staging>/ui-builder.json`. Fail-soft and
   * structural-only, like [writeComponentRecord].
   */
  private fun writeUiBuilderCatalog(base: String, staging: File, catalog: Catalog) {
    val name = catalog.uiBuilderFile?.trim('/')?.takeIf { it.isNotEmpty() } ?: return
    if (".." in name.split("/")) return
    val bytes = runCatching { fetchCatalogAsset("$base$name") }.getOrNull() ?: return
    if (!looksLikeUiBuilderCatalog(bytes)) return
    File(staging, UI_BUILDER_CATALOG_FILE).writeBytes(bytes)
  }

  private fun looksLikeUiBuilderCatalog(bytes: ByteArray): Boolean = runCatching {
    val root = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return false
    root["schema"] is JsonPrimitive && root["statusSemantics"] is JsonObject
  }
    .getOrDefault(false)

  /**
   * Stage and verify the executable renderer this catalog generation declares. Fail-closed, unlike
   * metadata: activating a catalog without its runtime would pair two different generations.
   */
  private fun writeUiBuilderRuntime(base: String, staging: File, catalog: Catalog): String? {
    val descriptor = catalog.uiBuilderRuntime ?: return null
    descriptor
      .validateContract()
      .takeIf { it.isNotEmpty() }
      ?.let { issues ->
        return "catalog UI-builder runtime descriptor is invalid: " +
          issues.joinToString { issue -> "${issue.field}:${issue.code}" }
      }
    val archive =
      runCatching {
        cachedBranchRead(base + descriptor.path, MAX_RUNTIME_ARCHIVE_FETCH_BYTES).bytesOrNull
      }
        .getOrNull() ?: return "catalog UI-builder runtime could not be fetched"
    return runCatching {
      ServeUiBuilderRuntimeAssets.stageArchive(
        descriptor,
        archive,
        File(staging, UI_BUILDER_RUNTIME_DIR),
      )
      rememberHistoricalRuntime(descriptor, base + descriptor.path)
    }
      .exceptionOrNull()
      ?.let { failure ->
        "catalog UI-builder runtime is invalid: " +
          (failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName.orEmpty())
      }
  }

  /**
   * The builder catalog of [system]'s currently served generation, or null where the catalog
   * publishes none.
   */
  fun uiBuilderCatalog(system: String): File? =
    liveDir(system)?.let { File(it, UI_BUILDER_CATALOG_FILE) }?.takeIf { it.isFile }

  /** Catalog-delivered runtime asset from an atomically published generation. */
  internal fun uiBuilderRuntimeAsset(
    runtimeId: String,
    segments: List<String>,
  ): ServeUiBuilderRuntimeAssets.Asset? {
    // Old generations stay on disk until the next refresh so in-flight requests holding their
    // runtime id can finish; runtime lookup must honour that grace period rather than only
    // liveDirs.
    runtimeRoots.removeIf { !it.isDirectory }
    val roots = runtimeRoots.toList()
    val manifestEtags =
      roots
        .mapNotNull { runtimeRoot ->
          ServeUiBuilderRuntimeAssets.assetFromDirectory(
            runtimeRoot,
            runtimeId,
            emptyList(),
          )
        }
        .map { it.etag }
        .distinct()
    // A global runtime id resolving to different manifests is an identity collision, never a
    // choice. Compare the identity first: two colliding runtimes can legitimately share one asset.
    if (manifestEtags.size > 1) return null
    if (manifestEtags.size == 1) {
      return roots
        .mapNotNull { runtimeRoot ->
          ServeUiBuilderRuntimeAssets.assetFromDirectory(runtimeRoot, runtimeId, segments)
        }
        .distinctBy { it.etag }
        .singleOrNull()
    }
    return historicalRuntimeAsset(runtimeId, segments)
  }

  /**
   * Remember how to recover an immutable runtime after its generation is swept. Only a
   * commit-pinned source is durable identity (a branch URL would let an old id resolve to future
   * bytes). One descriptor per id+integrity pair, so a reused id stays an explicit collision.
   */
  private fun rememberHistoricalRuntime(
    artifact: UiBuilderRuntimeArtifactV1,
    sourceUrl: String,
  ) {
    if (!ServeCatalogRevision.isCommitPinned(sourceUrl)) return
    val directory = File(root, UI_BUILDER_RUNTIME_DESCRIPTOR_DIR)
    if (!directory.isDirectory && !directory.mkdirs()) return
    val descriptor =
      HistoricalRuntimeDescriptor(
        sourceUrl = sourceUrl,
        artifact = artifact,
      )
    val target =
      File(
        directory,
        "${artifact.runtimeId}-${artifact.integritySha256}.$RUNTIME_DESCRIPTOR_SUFFIX",
      )
    if (target.isFile) return
    val staging = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
    runCatching {
      staging.writeText(json.encodeToString(HistoricalRuntimeDescriptor.serializer(), descriptor))
      if (!staging.renameTo(target) && !target.isFile) {
        staging.copyTo(target, overwrite = false)
      }
    }
    staging.delete()
  }

  /**
   * Rebuild runtime descriptors from the bounded delivery-feed tail during load, so an unknown
   * runtime id on the request path stays a cheap miss rather than a synchronous GitHub crawl.
   */
  private fun rememberPublishedRuntimeHistory(repo: String, commits: List<String>) {
    val bases = commits.map { commit -> "https://raw.githubusercontent.com/$repo/$commit/" }
    val catalogUrls = bases.map { base -> base + CATALOG_FILE }
    val catalogs = fetchCatalogAssets(catalogUrls)
    for ((base, catalogUrl) in bases.zip(catalogUrls)) {
      val catalog =
        runCatching {
          catalogs[catalogUrl]?.let {
            json.decodeFromString(Catalog.serializer(), it.toString(Charsets.UTF_8))
          }
        }
          .getOrNull() ?: continue
      val artifact = catalog.uiBuilderRuntime ?: continue
      if (artifact.validateContract().isEmpty()) {
        rememberHistoricalRuntime(artifact, base + artifact.path)
      }
    }
  }

  /**
   * Recover a historical runtime into a renewable extracted-tree lease. The archive lives in
   * [CatalogBlobPool]; extracted trees are larger, so they are process-local, expire after
   * inactivity and are count-capped. An evicted iframe transparently reacquires the same bytes.
   */
  @Synchronized
  private fun historicalRuntimeAsset(
    runtimeId: String,
    segments: List<String>,
  ): ServeUiBuilderRuntimeAssets.Asset? {
    val now = System.currentTimeMillis()
    historicalRuntimeLeases.entries.removeIf { (_, lease) ->
      if (lease.expiresAtMillis > now) false
      else {
        lease.root.deleteRecursively()
        true
      }
    }
    historicalRuntimeLeases[runtimeId]?.let { lease ->
      if (lease.root.isDirectory) {
        lease.expiresAtMillis = now + HISTORICAL_RUNTIME_LEASE_MILLIS
        return ServeUiBuilderRuntimeAssets.assetFromDirectory(lease.root, runtimeId, segments)
      }
      historicalRuntimeLeases.remove(runtimeId)
    }

    val descriptors = historicalRuntimeDescriptors(runtimeId)
    val identities =
      descriptors.map { it.artifact.protocolVersion to it.artifact.integritySha256 }.distinct()
    if (identities.size != 1) return null
    val descriptor = descriptors.first()
    val archive =
      runCatching {
        cachedBranchRead(descriptor.sourceUrl, MAX_RUNTIME_ARCHIVE_FETCH_BYTES).bytesOrNull
      }
        .getOrNull() ?: return null
    val leaseRoot =
      Files.createTempDirectory(historicalRuntimeLeaseRoot.toPath(), ".lease-").toFile()
    try {
      ServeUiBuilderRuntimeAssets.stageArchive(descriptor.artifact, archive, leaseRoot)
    } catch (failure: Exception) {
      leaseRoot.deleteRecursively()
      return null
    }
    while (historicalRuntimeLeases.size >= MAX_HISTORICAL_RUNTIME_LEASES) {
      val eldest = historicalRuntimeLeases.entries.firstOrNull() ?: break
      historicalRuntimeLeases.remove(eldest.key)
      eldest.value.root.deleteRecursively()
    }
    historicalRuntimeLeases[runtimeId] =
      HistoricalRuntimeLease(leaseRoot, now + HISTORICAL_RUNTIME_LEASE_MILLIS)
    return ServeUiBuilderRuntimeAssets.assetFromDirectory(leaseRoot, runtimeId, segments)
  }

  private fun historicalRuntimeDescriptors(runtimeId: String): List<HistoricalRuntimeDescriptor> {
    val directory = File(root, UI_BUILDER_RUNTIME_DESCRIPTOR_DIR)
    return directory
      .listFiles { file ->
        file.isFile &&
          file.name.startsWith("$runtimeId-") &&
          file.name.endsWith(".$RUNTIME_DESCRIPTOR_SUFFIX")
      }
      .orEmpty()
      .sortedBy(File::getName)
      .mapNotNull { file ->
        runCatching {
          json.decodeFromString(HistoricalRuntimeDescriptor.serializer(), file.readText())
        }
          .getOrNull()
      }
      .filter { descriptor ->
        descriptor.schema == HISTORICAL_RUNTIME_DESCRIPTOR_SCHEMA &&
          descriptor.artifact.runtimeId == runtimeId &&
          descriptor.artifact.validateContract().isEmpty() &&
          descriptor.sourceUrl.startsWith("https://raw.githubusercontent.com/") &&
          ServeCatalogRevision.isCommitPinned(descriptor.sourceUrl)
      }
  }

  @Serializable
  private data class HistoricalRuntimeDescriptor(
    val schema: String = HISTORICAL_RUNTIME_DESCRIPTOR_SCHEMA,
    val sourceUrl: String,
    val artifact: UiBuilderRuntimeArtifactV1,
  )

  private data class HistoricalRuntimeLease(
    val root: File,
    var expiresAtMillis: Long,
  )

  /**
   * The component record of [system]'s served generation, or null. Read per request, so a refreshed
   * catalog's record applies.
   */
  fun componentRecord(system: String): File? =
    liveDir(system)?.let { File(it, COMPONENT_RECORD_FILE) }?.takeIf { it.isFile }

  /**
   * A builder catalog fetched from the delivery branch at startup, without loading the catalog (see
   * [fetchUiBuilderCatalog]): which catalogs the builder serves is fixed before the first request,
   * while catalogs load in the background. A catalog that declares no `uiBuilderFile` returns null
   * quietly.
   */
  internal data class PublishedUiBuilderCatalogAsset(
    val file: File,
    val runtimeId: String?,
    /**
     * Seed documents named by the policy's `statusSemantics.templates`, in order, from the same
     * commit; null for one that would not fetch so the reader can refuse the set. Fetched only for
     * catalog-owned catalogs.
     */
    val templates: Map<String, String?> = emptyMap(),
    /**
     * The catalog's `ui-builder.guidelines.json` from the same commit, or null.
     * [ServeCatalogGuidelines.accept] decides whether it is valid.
     */
    val guidelines: ByteArray? = null,
    /** Where [guidelines] were read from. */
    val guidelinesUrl: String? = null,
  )

  internal fun fetchUiBuilderCatalog(
    system: String,
    sourceRepo: String? = null,
    sourceBranchPrefix: String? = null,
    templates: Boolean = false,
  ): PublishedUiBuilderCatalogAsset? {
    val safe = ServeBundleStore.sanitizeName(system) ?: return null
    val repo = sourceRepo?.takeIf { it.isNotBlank() } ?: this.repo
    val branchPrefix = sourceBranchPrefix?.takeIf { it.isNotBlank() } ?: this.branchPrefix
    val branch = "$branchPrefix$system"
    val deliveryCommit = fetchRevisions(repo, branch).firstOrNull()?.commit
    val base =
      deliveryCommit?.let { "https://raw.githubusercontent.com/$repo/$it/" }
        ?: "https://raw.githubusercontent.com/$repo/$branch/"
    val catalog =
      runCatching {
        fetchCatalogAsset(base + CATALOG_FILE)?.let {
          json.decodeFromString(Catalog.serializer(), it.toString(Charsets.UTF_8))
        }
      }
        .getOrNull() ?: return null
    val declared = catalog.uiBuilderFile?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
    if (".." in declared.split("/")) return null
    val bytes =
      runCatching { fetchCatalogAsset("$base$declared") }.getOrNull()
        ?: run {
          System.err.println(
            "serve: $system declares uiBuilderFile $declared and it could not be fetched from $branch"
          )
          return null
        }
    if (!looksLikeUiBuilderCatalog(bytes)) {
      System.err.println("serve: $system's $declared is not a builder catalog")
      return null
    }
    val dir = File(File(root, COMPONENT_RECORD_CACHE_DIR), safe)
    dir.mkdirs()
    val target = File(dir, UI_BUILDER_CATALOG_FILE)
    target.writeBytes(bytes)
    // The catalog's own design guidance, published beside its builder catalog. Absent is the
    // ordinary case (a catalog that has written none) and says nothing.
    val guidelinesUrl = base + ServeCatalogGuidelines.siblingOf(declared)
    val guidelines = runCatching { fetchCatalogAsset(guidelinesUrl) }.getOrNull()
    return PublishedUiBuilderCatalogAsset(
      file = target,
      runtimeId = catalog.uiBuilderRuntime?.takeIf { it.validateContract().isEmpty() }?.runtimeId,
      templates = if (templates) fetchUiBuilderTemplates(system, base, bytes) else emptyMap(),
      guidelines = guidelines,
      guidelinesUrl = guidelinesUrl.takeIf { guidelines != null },
    )
  }

  /**
   * The seed documents [policy] names, fetched from one commit in policy order. A path that escapes
   * or won't fetch maps to null (logged once), so `CatalogSeedTemplates.read` refuses the set by
   * name.
   */
  private fun fetchUiBuilderTemplates(
    system: String,
    base: String,
    policy: ByteArray,
  ): Map<String, String?> {
    val declared = runCatching {
      ((json.parseToJsonElement(policy.decodeToString()) as? JsonObject)?.get("statusSemantics")
          as? JsonObject)
        ?.get("templates") as? JsonArray
    }
      .getOrNull()
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim('/') }
      .orEmpty()
    return declared.associateWith { path ->
      path
        .takeIf { ".." !in it.split("/") }
        ?.let { runCatching { fetchCatalogAsset("$base$it") }.getOrNull()?.decodeToString() }
        .also {
          if (it == null)
            System.err.println("serve: $system declares template $path and it could not be fetched")
        }
    }
  }

  /**
   * Fetch [system]'s component record from its branch head now, without loading the catalog. For
   * the UI builder's component packs, a startup fact that must not change under an open design.
   * Same route as [load] (declared `componentsFile`, else the live bundle's entry). Written under
   * the store root, not a generation dir the next load would sweep. Best-effort: null with a stderr
   * line.
   */
  fun fetchComponentRecord(
    system: String,
    sourceRepo: String? = null,
    sourceBranchPrefix: String? = null,
  ): File? {
    val safe = ServeBundleStore.sanitizeName(system) ?: return null
    val repo = sourceRepo?.takeIf { it.isNotBlank() } ?: this.repo
    val branchPrefix = sourceBranchPrefix?.takeIf { it.isNotBlank() } ?: this.branchPrefix
    val branch = "$branchPrefix$system"
    val deliveryCommit = fetchRevisions(repo, branch).firstOrNull()?.commit
    val base =
      deliveryCommit?.let { "https://raw.githubusercontent.com/$repo/$it/" }
        ?: "https://raw.githubusercontent.com/$repo/$branch/"
    fun stop(reason: String): File? {
      System.err.println("serve: no component record for $system from $branch — $reason")
      return null
    }
    val catalog =
      runCatching {
        fetchCatalogAsset(base + CATALOG_FILE)?.let {
          json.decodeFromString(Catalog.serializer(), it.toString(Charsets.UTF_8))
        }
      }
        .getOrNull() ?: return stop("could not read $base$CATALOG_FILE")
    val recordDir = File(File(root, COMPONENT_RECORD_CACHE_DIR), safe)
    val target = File(recordDir, COMPONENT_RECORD_FILE)
    val declared = catalog.componentsFile?.trim('/')?.takeIf { it.isNotEmpty() }
    if (declared != null && ".." !in declared.split("/")) {
      val bytes = runCatching { fetchCatalogAsset("$base$declared") }.getOrNull()
      if (bytes != null && looksLikeComponentRecord(bytes)) {
        recordDir.mkdirs()
        target.writeBytes(bytes)
        return target
      }
    }
    val bundle =
      catalog.liveBundle
        ?: catalog.liveBundles.firstOrNull { it.previewIdPrefix.isEmpty() }
        ?: catalog.liveBundles.firstOrNull()
        ?: return stop("the catalog declares neither a componentsFile nor a live bundle")
    val name = bundle.file.trim('/')
    if (name.isEmpty() || ".." in name.split("/")) return stop("the live bundle entry is invalid")
    val prefix = bundle.path.trim('/')
    val url = if (prefix.isEmpty()) "$base$name" else "$base$prefix/$name"
    val bundleBytes =
      runCatching { fetchExecutableBundle(url) }.getOrNull()
        ?: return stop("the live bundle could not be fetched ($url)")
    recordDir.mkdirs()
    val bundleFile = File(recordDir, "bundle.tmp")
    return try {
      bundleFile.writeBytes(bundleBytes)
      val bytes =
        runCatching { componentRecordEntry(bundleFile) }.getOrNull()
          ?: return stop(
            "the live bundle carries no components.json (packed by ${catalog.renderer ?: "an older renderer"})"
          )
      target.writeBytes(bytes)
      target
    } finally {
      bundleFile.delete()
    }
  }

  /**
   * Extract `ir/<daemon-id>.rc` documents from [bundleFile], re-key them through [alias], and write
   * `<dir>/ir/<catalog-id>.rc` for [ServeBundleHost.remoteComposeDoc]. Ids without an `.rc` are
   * skipped. Best-effort: an unreadable bundle just leaves the lane off.
   */
  private fun extractCatalogRcDocs(bundleFile: File, alias: Map<String, String>, dir: File) {
    // Only the entries the alias actually maps to are worth decoding — a bundle padded with junk
    // `ir/` entries can't make us expand (or retain) anything the catalog will ever serve.
    val wantedDaemonIds = alias.values.toHashSet()
    if (wantedDaemonIds.isEmpty()) return
    // Generous caps (real docs are a few KB) that stop a highly-compressed entry exhausting the
    // heap; the network cap bounds only the compressed bundle.
    val maxDocBytes = 8L * 1024 * 1024
    val maxTotalBytes = 64L * 1024 * 1024
    val docsByDaemonId =
      try {
        val zipBytes = BundleReader.extractZipBytes(bundleFile)
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
          var entry = zin.nextEntry
          while (entry != null) {
            val name = entry.name.replace('\\', '/')
            if (
              !entry.isDirectory &&
                name.startsWith("$IR_DOC_DIR/") &&
                name.endsWith(RC_DOC_SUFFIX) &&
                ".." !in name.split("/")
            ) {
              val daemonId = name.removePrefix("$IR_DOC_DIR/").removeSuffix(RC_DOC_SUFFIX)
              if (daemonId in wantedDaemonIds && daemonId !in out) {
                // Bound the streaming read per doc and in total, abandoning the optional lane as
                // soon as either is exceeded.
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var docBytes = 0L
                while (true) {
                  val n = zin.read(chunk)
                  if (n < 0) break
                  docBytes += n
                  total += n
                  check(docBytes <= maxDocBytes && total <= maxTotalBytes) {
                    "rc docs exceed the decompression cap"
                  }
                  buf.write(chunk, 0, n)
                }
                out[daemonId] = buf.toByteArray()
              }
            }
            zin.closeEntry()
            entry = zin.nextEntry
          }
        }
        out
      } catch (e: Exception) {
        return
      }
    if (docsByDaemonId.isEmpty()) return
    val irDir = File(dir, IR_DOC_DIR)
    val irRoot = irDir.canonicalFile.toPath()
    for ((catalogId, daemonId) in alias) {
      val bytes = docsByDaemonId[daemonId] ?: continue
      val target = File(irDir, "$catalogId$RC_DOC_SUFFIX")
      // Containment: a crafted catalog id must not let the write escape the ir/ dir.
      if (!target.canonicalFile.toPath().startsWith(irRoot)) continue
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
  }

  /**
   * The subset of [daemonIds] whose sanitised per-preview stem is unambiguous, mapped to that stem.
   * `bundle split` suffixes collisions in an order the server can't reconstruct, so colliding (and
   * blank) stems are dropped and served by the monolithic daemon.
   */
  private fun uniquePerPreviewStems(daemonIds: Collection<String>): Map<String, String> {
    val counts = HashMap<String, Int>()
    val stems = LinkedHashMap<String, String>()
    for (id in daemonIds) {
      val stem = sanitizePerPreviewName(id)
      if (stem.isEmpty()) continue
      stems[id] = stem
      counts[stem] = (counts[stem] ?: 0) + 1
    }
    return stems.filterValues { counts[it] == 1 }
  }

  /**
   * Fetch one preview's split bundle (`<liveBundle.path>/previews/<stem>.png`) into
   * `<dir>/$LIVE_BUNDLE_DIR/$PER_PREVIEW_DIR/<stem>.png`. Fail-closed: null makes the caller use
   * the monolithic daemon for that id.
   *
   * The hydrated bundle is what gets cached: hydration resolves entries by sha256, so its output is
   * a deterministic function of the URL and, when [pinned], can be keyed on it in [blobs]. [stem]
   * comes from [uniquePerPreviewStems], so it matches the published filename exactly.
   */
  private fun fetchPerPreviewBundle(
    stem: String,
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
    externalResourcesDir: File?,
    pinned: Boolean,
  ): File? {
    val url = perPreviewBundleUrl(stem, liveBundle, base)
    val prefix = liveBundle.path.trim('/')
    val hydrate = { dest: File ->
      hydratePerPreviewBundle(url, dest, base, prefix, system, stem, externalResourcesDir)
    }

    if (pinned) return blobs.keyed(url) { dest -> hydrate(dest) }

    val previewsDir = File(File(dir, LIVE_BUNDLE_DIR), PER_PREVIEW_DIR)
    val previewsRoot = previewsDir.canonicalFile.toPath()
    val target = File(previewsDir, "$stem.png")
    if (!target.canonicalFile.toPath().startsWith(previewsRoot)) {
      System.err.println("serve: $system per-preview '$stem' escapes — skipping")
      return null
    }
    // Cached on disk from a prior request for the same id (the pool reopens lazily on eviction).
    if (target.isFile && target.length() > 0) {
      if (isCompleteExecutableBundle(target)) return target
      target.delete()
    }
    target.parentFile?.mkdirs()
    return target.takeIf { hydrate(it) }
  }

  /**
   * Download one split bundle and repack it into [dest] with its externalised classpath and
   * resources folded back in; returns whether [dest] is usable. Takes a caller-chosen destination
   * so pinned and un-pinned loads share one hydration path.
   */
  private fun hydratePerPreviewBundle(
    url: String,
    dest: File,
    base: String,
    prefix: String,
    system: String,
    stem: String,
    externalResourcesDir: File?,
  ): Boolean {
    val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
    if (bytes == null) {
      // Expected when the branch ships no per-preview bundle for this id (older catalog, view-only
      // tier); the caller falls back to the monolithic daemon. Quiet — not an error.
      return false
    }
    val thin =
      java.nio.file.Files.createTempFile(dest.parentFile.toPath(), "$stem.", ".shared.png").toFile()
    val hydrated =
      try {
        thin.writeBytes(bytes)
        runCatching {
          BundleClasspathHydration.hydrate(
            source = thin,
            output = dest,
            resolveClasspath = { entry -> fetchExternalClasspathBlob(entry, base, prefix, system) },
            resolveResource = { entry ->
              readMaterializedExternalResource(entry, externalResourcesDir)
            },
          )
        }
          .onFailure {
            System.err.println(
              "serve: $system per-preview '$stem' classpath hydration failed (${it.message})"
            )
          }
          .getOrNull()
      } finally {
        thin.delete()
      }
    if (hydrated == null) return false
    if (!isCompleteExecutableBundle(hydrated)) {
      hydrated.delete()
      return false
    }
    return true
  }

  /** Check publication without downloading and hydrating the potentially 100 MB bundle. */
  private fun perPreviewBundleAvailable(
    stem: String,
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    pinned: Boolean,
  ): Boolean {
    val url = perPreviewBundleUrl(stem, liveBundle, base)
    // Deliberately unverified — see [CatalogBlobPool.holds]. Hashing a multi-megabyte bundle to
    // answer "does this lane exist" would cost more than the network probe it replaces.
    if (pinned && blobs.holds(url)) return true
    val cached = File(File(File(dir, LIVE_BUNDLE_DIR), PER_PREVIEW_DIR), "$stem.png")
    if (cached.isFile && cached.length() > 0 && isCompleteExecutableBundle(cached)) return true
    return runCatching { branchProbe(url) }.getOrDefault(false)
  }

  private fun perPreviewBundleUrl(stem: String, liveBundle: LiveBundle, base: String): String {
    val prefix = liveBundle.path.trim('/')
    val rel = "$PER_PREVIEW_DIR/$stem.png"
    return if (prefix.isEmpty()) "$base$rel" else "$base$prefix/$rel"
  }

  /** A cached download is usable without a sibling pool and was not split as view-only. */
  private fun isCompleteExecutableBundle(bundle: File): Boolean = runCatching {
    BundleReader.readMetadata(bundle).manifest.let { manifest ->
      manifest.resolution != "view-only" &&
        manifest.externalClasspath.isEmpty() &&
        manifest.externalResources.isEmpty()
    }
  }
    .getOrDefault(false)

  /** Read one already-verified external resource from the monolithic bundle's materialized pool. */
  private fun readMaterializedExternalResource(
    entry: BundleReader.ExternalResource,
    externalResourcesDir: File?,
  ): ByteArray? {
    val root = externalResourcesDir?.canonicalFile?.toPath() ?: return null
    if (entry.path.isBlank() || entry.path.startsWith("/") || ".." in entry.path.split("/")) {
      return null
    }
    val file = File(externalResourcesDir, entry.path).canonicalFile
    if (!file.toPath().startsWith(root) || !file.isFile) return null
    return file.readBytes()
  }

  /** Fetch and verify one whole classpath entry into the shared content-addressed cache. */
  private fun fetchExternalClasspathBlob(
    entry: BundleReader.ExternalClasspath,
    base: String,
    bundlePathPrefix: String,
    system: String,
  ): ByteArray? {
    val sha = entry.sha256
    if (
      entry.path != "classes/app.jar" ||
        entry.size <= 0 ||
        entry.size > MAX_LIVE_BUNDLE_FETCH_BYTES ||
        sha.length != 64 ||
        sha.any { it !in '0'..'9' && it !in 'a'..'f' }
    ) {
      System.err.println("serve: $system external classpath declaration is invalid — skipping")
      return null
    }
    val prefix = bundlePathPrefix.trim('/')
    val url = if (prefix.isEmpty()) "$base$RES_POOL_DIR/$sha" else "$base$prefix/$RES_POOL_DIR/$sha"
    // Content-addressed by the manifest digest: a hit is returned only after its bytes hash back,
    // so corrupt entries are refetched. No URL is involved, so this is safe on un-pinned loads too.
    val blob =
      blobs.contentAddressed(sha, entry.size) {
        runCatching { fetchExecutableBundle(url) }
          .getOrNull()
          ?.takeIf { it.size.toLong() == entry.size }
      }
    if (blob == null) {
      System.err.println("serve: $system external classpath could not be fetched ($url)")
      return null
    }
    return runCatching { blob.readBytes() }.getOrNull()
  }

  /** Filesystem/route-safe stem for a per-preview id (mirrors `bundle split`'s sanitiser). */
  private fun sanitizePerPreviewName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }

  /** Outcome of [rehydrateExternalResources]. */
  private sealed interface ResRehydrate {
    /** Ready: [dir] is the materialized classpath dir, or null when nothing was externalized. */
    data class Ready(val dir: File?) : ResRehydrate

    /** A declared resource couldn't be fetched or verified; skip the live bundle. */
    data object Unavailable : ResRehydrate
  }

  /**
   * Rehydrate resources (fonts) that `bundle externalize` lifted out of [bundleFile]'s manifest.
   * Each is fetched once into a content-addressed cache, sha256-verified, then materialized at its
   * classpath [path] under `<dir>/$RES_MATERIALIZED_DIR/` so `/fonts/…` resolves as if inline.
   * Paths come from the trusted manifest and each write is contained.
   *
   * Returns [ResRehydrate.Ready] (null dir when nothing was externalized) or
   * [ResRehydrate.Unavailable] (fail-closed) when any resource is bad or unfetchable.
   */
  private fun rehydrateExternalResources(
    bundleFile: File,
    base: String,
    bundlePathPrefix: String,
    dir: File,
    system: String,
  ): ResRehydrate {
    val resources =
      runCatching { BundleReader.readMetadata(bundleFile).manifest.externalResources }.getOrNull()
        ?: emptyList()
    if (resources.isEmpty()) return ResRehydrate.Ready(null)

    val materialized = File(dir, RES_MATERIALIZED_DIR)
    val matRoot = materialized.canonicalFile.toPath()
    val prefix = bundlePathPrefix.trim('/')

    for (res in resources) {
      val sha = res.sha256
      if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        System.err.println(
          "serve: $system external resource '$sha' is not a sha256 — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      // Keyed by sha256 and trusted only after the bytes hash back, so corrupt entries are
      // refetched. Reused across systems, reloads and (with `--catalog-cache-dir`) restarts.
      val url =
        if (prefix.isEmpty()) "$base$RES_POOL_DIR/$sha" else "$base$prefix/$RES_POOL_DIR/$sha"
      val cached =
        blobs.contentAddressed(sha, res.size) { runCatching { fetchCatalogAsset(url) }.getOrNull() }
      if (cached == null) {
        System.err.println(
          "serve: $system external resource could not be fetched or verified ($url) — " +
            "skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      // Materialize at the recorded classpath path (path-contained — reject traversal/absolute).
      if (res.path.isBlank() || res.path.startsWith("/") || ".." in res.path.split("/")) {
        System.err.println(
          "serve: $system external resource path '${res.path}' is invalid — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      val dest = File(materialized, res.path)
      if (!dest.canonicalFile.toPath().startsWith(matRoot)) {
        System.err.println(
          "serve: $system external resource path '${res.path}' escapes — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      dest.parentFile?.mkdirs()
      cached.copyTo(dest, overwrite = true)
    }
    return ResRehydrate.Ready(materialized)
  }

  /**
   * Fetch baked `figma/<slug>.svg` exports plus each hybrid SVG's `<slug>.figma-raster/<node>.png`
   * crops into `<dir>/figma/`. Best-effort per slug; writes are path-contained. Returns the
   * `figma/` dir when at least one SVG landed, else null.
   */
  private fun fetchFigmaSvgs(
    slugs: Set<String>,
    variantPaths: Set<String>,
    base: String,
    dir: File,
    stillCurrent: () -> Boolean = { true },
  ): File? {
    val figmaDir = File(dir, FIGMA_DIR)
    val figmaRoot = figmaDir.canonicalFile.toPath()
    var wrote = 0
    val candidates = buildList {
      addAll(variantPaths)
      addAll(slugs.map { "$it.svg" })
    }
    // Concurrent prefetch in two waves: hybrid SVGs name their crops in their own markup, and
    // raw.githubusercontent has no directory listing.
    val safeCandidates = candidates.filter { relativePath ->
      val segments = relativePath.split("/")
      relativePath.isNotEmpty() &&
        relativePath.endsWith(".svg") &&
        ".." !in segments &&
        segments.size in 1..2
    }
    // Wave 1: the vectors themselves, written straight to disk by the workers (so a catalog's
    // whole vector set is never resident at once) and path-contained before planning.
    val writtenSvgs =
      fetchCatalogAssetsToFiles(
        stillWanted = stillCurrent,
        plan =
          safeCandidates.mapNotNull { relativePath ->
            val svgFile = File(figmaDir, relativePath)
            if (!svgFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
            "$base$FIGMA_DIR/$relativePath" to svgFile
          },
      )
    // Wave 2: the crops named inside wave 1's SVGs, each re-read from the file just written.
    if (!stillCurrent()) return null
    val cropPlan = safeCandidates.flatMap { relativePath ->
      val svgFile = File(figmaDir, relativePath)
      if ("$base$FIGMA_DIR/$relativePath" !in writtenSvgs) return@flatMap emptyList()
      val svg = runCatching { svgFile.readText() }.getOrNull() ?: return@flatMap emptyList()
      val remoteParent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
      figmaRasterHrefs(svg).mapNotNull { href ->
        if (href.isEmpty() || ".." in href.split("/")) return@mapNotNull null
        val cropFile = File(svgFile.parentFile, href)
        if (!cropFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
        val remoteCrop = if (remoteParent.isEmpty()) href else "$remoteParent/$href"
        "$base$FIGMA_DIR/$remoteCrop" to cropFile
      }
    }
    fetchCatalogAssetsToFiles(cropPlan, stillWanted = stillCurrent)
    wrote = writtenSvgs.size
    return if (wrote > 0) figmaDir else null
  }

  /**
   * Vectors that would serve [heroId], most specific first: per-variant
   * `figma/<slug>/<variant>.svg`, then per-component `figma/<slug>.svg`.
   */
  private fun heroSvgCandidates(heroId: String?): List<String> {
    val id = heroId ?: return emptyList()
    val slug = id.substringBefore(SLUG_SEPARATOR)
    val variant = id.substringAfter(SLUG_SEPARATOR, missingDelimiterValue = "")
    return buildList {
      if (variant.isNotEmpty()) add("$slug/$variant.svg")
      add("$slug.svg")
    }
  }

  /**
   * Fetch the first existing candidate to learn whether this branch carries the figma lane at all;
   * null keeps the catalog from advertising an SVG control that would 404. The rest follows in
   * [scheduleFigmaSvgFetch].
   */
  private fun probeFigmaSvg(candidates: List<String>, base: String, dir: File): File? {
    val figmaDir = File(dir, FIGMA_DIR)
    val figmaRoot = figmaDir.canonicalFile.toPath()
    val plan = candidates.mapNotNull { relativePath ->
      val svgFile = File(figmaDir, relativePath)
      if (!svgFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
      "$base$FIGMA_DIR/$relativePath" to svgFile
    }
    // One concurrent wave, so a catalog that publishes no vectors pays a single round-trip rather
    // than one per candidate.
    return figmaDir.takeIf { fetchCatalogAssetsToFiles(plan).isNotEmpty() }
  }

  /**
   * Fill remaining baked vectors off the publish path (best-effort, fire-and-forget). Guarded by
   * [generation] so a pass for a superseded generation stops instead of writing into the fresh
   * catalog.
   */
  private fun scheduleFigmaSvgFetch(
    system: String,
    generation: Int,
    slugs: Set<String>,
    variantPaths: Set<String>,
    base: String,
    dir: File,
  ) {
    figmaExecutor.execute {
      if (generations[system] != generation) return@execute
      // Its own scope: this lane runs after the result was handed back, so it reports its own
      // incompleteness rather than being counted into anyone else's load.
      val incomplete = inFetchScope { scope ->
        runCatching {
          fetchFigmaSvgs(slugs, variantPaths, base, dir) { generations[system] == generation }
        }
          .onFailure { System.err.println("serve: catalog $system figma vectors: ${it.message}") }
        scope.sawTransientFailure
      }
      // Re-checked: a newer load may have finished meanwhile, and un-settling its revision would
      // force a needless reload.
      if (incomplete && generations[system] == generation) onPostPublishIncomplete(system)
    }
  }

  private fun scheduleRcCompareFetch(
    system: String,
    generation: Int,
    alias: Map<String, String>,
    base: String,
    dir: File,
  ) {
    if (!ServeRcCompare.stagesFor(alias)) return
    figmaExecutor.execute {
      if (generations[system] != generation) return@execute
      // Post-publish and self-reporting, like the vector fills beside it.
      val incomplete = inFetchScope { scope ->
        runCatching { fetchRcCompare(alias, base, dir) { generations[system] == generation } }
          .onFailure { System.err.println("serve: catalog $system rc-compare: ${it.message}") }
        scope.sawTransientFailure
      }
      if (incomplete && generations[system] == generation) onPostPublishIncomplete(system)
    }
  }

  /**
   * Stage the published Remote Compose player comparison (`rc-compare-summary.json` plus lane PNGs)
   * so the compare page needs no in-browser rendering. [ServeRcCompare.plan] re-keys rows through
   * [alias]; a catalog with no `.rc` docs costs one 404.
   *
   * The manifest is written last, since [ServeRcCompareStore] gates on it, so no row is served
   * before its pixels; cells whose image didn't arrive are dropped.
   */
  private fun fetchRcCompare(
    alias: Map<String, String>,
    base: String,
    dir: File,
    stillWanted: () -> Boolean,
  ) {
    // Every terminal outcome writes a manifest, including [ServeRcCompare.NONE], so the page knows
    // the lane has settled (see [ServeRcCompareStore.pending]).
    fun settle(manifest: RcCompareManifest) {
      if (!stillWanted()) return
      val root = File(dir, ServeRcCompare.DIRECTORY)
      root.mkdirs()
      File(root, ServeRcCompare.INDEX_FILE)
        .writeText(json.encodeToString(RcCompareManifest.serializer(), manifest))
    }

    val summaryBytes =
      runCatching { fetchCatalogAsset(base + ServeRcCompare.SUMMARY_FILE) }.getOrNull()
        ?: return settle(ServeRcCompare.NONE)
    val summary = ServeRcCompare.parseSummary(summaryBytes) ?: return settle(ServeRcCompare.NONE)
    val plan = ServeRcCompare.plan(summary, alias) ?: return settle(ServeRcCompare.NONE)
    if (!stillWanted()) return

    val root = File(dir, ServeRcCompare.DIRECTORY)
    val rootPath = root.canonicalFile.toPath()
    // Staged names come from a fixed lane vocabulary and an integer slot, so this is belt and
    // braces — but the write stays contained the same way every other staging lane's does.
    val fetchPlan =
      plan.assets.mapNotNull { (source, staged) ->
        val target = File(root, staged)
        if (!target.canonicalFile.toPath().startsWith(rootPath)) null else (base + source) to target
      }
    val fetched = fetchCatalogAssetsToFiles(fetchPlan, stillWanted)
    if (!stillWanted()) return
    val staged = plan.assets.filterKeys { (base + it) in fetched }.values.toSet()
    settle(ServeRcCompare.retainStaged(plan.manifest, staged) ?: ServeRcCompare.NONE)
  }

  /**
   * Stage the published annotation manifest, if any. Served catalogs are assembled from fetched
   * parts, so anything not copied here is invisible to [ServeBundleHost]. Annotations need no
   * assets. Fail-soft.
   */
  private fun writeAnnotations(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset(
          "$base${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}"
        )
      }
        .getOrNull() ?: return
    val manifest =
      runCatching { json.decodeFromString(AnnotationManifest.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.takeIf { it.schema == AnnotationManifest.SCHEMA } ?: return
    if (manifest.previews.isEmpty() && manifest.references.isEmpty()) return
    val dir = File(staging, ServeAnnotationStore.DIRECTORY)
    dir.mkdirs()
    File(dir, ServeAnnotationStore.INDEX_FILE)
      .writeText(json.encodeToString(AnnotationManifest.serializer(), manifest))
  }

  /**
   * Stage the published tag index, if any, like [writeAnnotations]. Validated before writing so a
   * malformed index never reaches the staging tree.
   */
  private fun writeTagIndex(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset("$base${ServeTagIndexStore.DIRECTORY}/${ServeTagIndexStore.INDEX_FILE}")
      }
        .getOrNull() ?: return
    val manifest =
      runCatching { json.decodeFromString(TagIndexManifest.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.takeIf { it.schema == TagIndexManifest.SCHEMA } ?: return
    if (manifest.previews.isEmpty()) return
    val dir = File(staging, ServeTagIndexStore.DIRECTORY)
    dir.mkdirs()
    File(dir, ServeTagIndexStore.INDEX_FILE)
      .writeText(json.encodeToString(TagIndexManifest.serializer(), manifest))
  }

  /**
   * Stage the catalog's committed known differences: the document, verbatim, plus the artifacts it
   * names. Without this the acceptance surface is silently dead on every published catalog.
   *
   * The document is copied byte for byte, not judged: verdicts belong to the shared engine, and
   * validating here would be a third implementation of the contract. Only artifact paths are
   * parsed, leniently, like the browser adapter's prefetch.
   *
   * Oversized documents and artifacts are staged anyway so the reader can answer `TooLarge` (413)
   * rather than the different verdict `unreadable`. Each artifact path passes
   * [ServeKnownDifferences.isLookupPath] before any fetch or write, so it cannot escape the
   * artifact root. Fail-soft and bounded: a document the reader would refuse whole contributes no
   * paths, and artifacts stream to disk one per worker.
   */
  private fun writeKnownDifferences(base: String, staging: File) {
    val dirName = ServeKnownDifferences.DIRECTORY
    val documentFile = File(staging, "$dirName/${ServeKnownDifferences.DOCUMENT_FILE}")
    val documentOutcome =
      runCatching {
        fetchCatalogAssetOutcome("$base$dirName/${ServeKnownDifferences.DOCUMENT_FILE}")
      }
        .getOrNull() ?: return
    // A fetch refused by size looks like an absent file, so stage a length-only marker past the
    // contract ceiling; the reader answers `TooLarge` from the length without reading it.
    if (documentOutcome is BranchFetch.TooLarge) {
      stageOversizeMarker(documentFile, ServeKnownDifferences.MAX_DOCUMENT_BYTES + 1L)
      return
    }
    val documentBytes = documentOutcome.bytesOrNull ?: return

    val artifactRoot = "$dirName/${ServeKnownDifferences.ARTIFACT_DIRECTORY}"
    // Streamed to disk via [fetchCatalogAssetsToFiles], never accumulated: a wave of 8–25 MiB
    // artifacts in memory could exhaust the heap. Oversized artifacts are still staged (see above),
    // and each write is guarded so one unwriteable artifact doesn't abandon the refresh.
    fetchCatalogAssetsToFiles(
      knownDifferenceArtifactPlan(base, documentBytes).map { path ->
        "$base$artifactRoot/$path" to File(staging, "$artifactRoot/$path")
      },
      // Same oversize-marker treatment as the document, against the artifact ceiling.
      oversizeMarkerBytes = ServeKnownDifferences.MAX_ARTIFACT_BYTES + 1L,
    )

    File(staging, dirName).mkdirs()
    documentFile.writeBytes(documentBytes)
  }

  /**
   * Write a placeholder of exactly [length] bytes for an asset refused by size. Uses `setLength` so
   * the filesystem stores a hole: readers answer `TooLarge` from metadata and never open it.
   * Fail-soft.
   */
  private fun stageOversizeMarker(target: File, length: Long): Boolean = runCatching {
    target.parentFile?.mkdirs()
    java.io.RandomAccessFile(target, "rw").use { it.setLength(length) }
    true
  }
    .getOrDefault(false)

  /**
   * Which artifact files to fetch: the producer's published index
   * ([ServeKnownDifferences.ARTIFACT_INDEX_FILE]) when present, otherwise paths derived from the
   * document.
   *
   * The index is a fetch plan, not an authority: every path still passes
   * [ServeKnownDifferences.isLookupPath]; disagreement with the document is not an error (an
   * omitted file evaluates as `artifact-unreadable`); and it never decides whether the document is
   * readable. Absent means the producer predates the index (fall back); a parsed empty index is
   * honoured as empty.
   */
  private fun knownDifferenceArtifactPlan(base: String, documentBytes: ByteArray): List<String> {
    // The document's length gates everything: past [ServeKnownDifferences.MAX_DOCUMENT_BYTES] the
    // engine refuses it whole, so fetching any artifact would be wasted.
    if (documentBytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return emptyList()
    return oneWritePerFile(
      publishedArtifactIndex(base) ?: knownDifferenceArtifactPaths(documentBytes)
    )
  }

  /**
   * Drop any path whose target file an earlier path already claims (e.g. `mask.png` vs `MASK.PNG`
   * on a case-insensitive filesystem), so concurrent writes can't make the result depend on which
   * fetch finished last. First spelling wins rather than rejecting, because the derivation has no
   * fallback; an index with collisions was already rejected by [publishedArtifactIndex].
   */
  private fun oneWritePerFile(paths: List<String>): List<String> {
    val claimed = HashSet<String>()
    return paths.filter { claimed.add(it.lowercase()) }
  }

  /**
   * The producer's artifact list, or null when none is published. Any malformation returns null so
   * the caller falls back to the derivation, rather than treating it as empty and stripping every
   * record of its artifacts. Bounded by the document ceiling.
   */
  private fun publishedArtifactIndex(base: String): List<String>? {
    val dirName = ServeKnownDifferences.DIRECTORY
    val url = "$base$dirName/${ServeKnownDifferences.ARTIFACT_INDEX_FILE}"
    val bytes = runCatching { fetchCatalogAsset(url) }.getOrNull() ?: return null
    if (bytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return null
    val parsed =
      runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
        ?: return null
    val schema = (parsed["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (schema != ServeKnownDifferences.ARTIFACT_INDEX_SCHEMA) return null
    val artifacts = parsed["artifacts"] as? JsonArray ?: return null
    if (artifacts.size > MAX_INDEXED_ARTIFACTS) return null

    val paths = LinkedHashSet<String>()
    for (entry in artifacts) {
      // A wrongly-typed entry rejects the whole index: skipping it could reduce the list to empty,
      // which would be honoured as "carried nothing".
      val path = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      // A path this host won't look up is dropped individually; the list is a convenience, not a
      // licence.
      if (ServeKnownDifferences.isLookupPath(path)) paths += path
    }

    // Two spellings of one file (case-insensitive filesystems) make the index unsafe to execute
    // concurrently; reject it rather than guess which the producer meant.
    val collision = paths.groupingBy { it.lowercase() }.eachCount().any { it.value > 1 }
    if (collision) return null

    return paths.toList()
  }

  /**
   * The `<id>/<file>` paths a known-difference document names, or none when the engine will reject
   * it whole. The fallback for catalogs published before the artifact index
   * ([knownDifferenceArtifactPlan]); kept conservative because nobody is left to fix those. A
   * whole-document rejection reads no artifacts, so fetching for it would waste up to 256 × 2 × 8
   * MiB per refresh ([rejectsWholeDocument]). Otherwise path extraction is lenient: a record with a
   * non-string artifact contributes nothing and the engine refuses it itself.
   */
  private fun knownDifferenceArtifactPaths(documentBytes: ByteArray): List<String> {
    if (documentBytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return emptyList()
    val parsed =
      runCatching { json.parseToJsonElement(documentBytes.decodeToString()) }.getOrNull()
        as? JsonObject ?: return emptyList()
    val acceptances = parsed["acceptances"] as? JsonArray ?: return emptyList()
    if (rejectsWholeDocument(parsed, acceptances)) return emptyList()

    val paths = LinkedHashSet<String>()
    for (record in acceptances) {
      val fields = record as? JsonObject ?: continue
      val id = recordId(fields) ?: continue
      for (key in listOf("mask", "acceptedCandidate")) {
        val value = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        val path = "$id/$value"
        if (ServeKnownDifferences.isLookupPath(path)) paths += path
      }
    }
    return paths.toList()
  }

  /**
   * Whether the engine refuses this document before reading any artifact; a deliberate mirror of
   * `known-differences.mjs`'s `parseDocument` + `identityFailures`.
   *
   * Wrong in only one direction on purpose: falsely "rejected" would change legal records'
   * verdicts, falsely "not rejected" only wastes a fetch. So only file-level rules are mirrored.
   * Deliberately not mirrored: `documentTextRefusal` (needs a JSON text scanner), `isSafeId` and
   * mask collisions, and `schemaReasons`; per-record rules here would be a third implementation of
   * the contract with no conformance suite.
   */
  private fun rejectsWholeDocument(document: JsonObject, acceptances: JsonArray): Boolean {
    // The schema and the shape. `acceptances` is already known to be an array by the caller.
    val schema = (document["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (schema != ServeKnownDifferences.SCHEMA) return true
    // `additionalProperties: false` at document level: an unknown key is `document-unreadable`.
    if (document.keys.any { it != "schema" && it != "acceptances" }) return true
    if (acceptances.size > ServeKnownDifferences.MAX_ACCEPTANCES) return true

    val seen = HashSet<String>()
    for (record in acceptances) {
      // A record with no usable key can't be reported, so it rejects the whole document.
      val fields = record as? JsonObject ?: return true
      val id = recordId(fields) ?: return true
      // Case-folded, because `foo` and `FOO` are two map keys and one directory on Windows and on a
      // default macOS filesystem: a document carrying both cannot even be checked out intact.
      if (!seen.add(id.lowercase())) return true
    }
    return false
  }

  /**
   * A record's `id` as the engine keys it: a string that is not blank *to JavaScript*, or nothing.
   */
  private fun recordId(fields: JsonObject): String? =
    (fields["id"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { !isEcmaScriptBlank(it) }

  /**
   * Whether ECMAScript `String.prototype.trim()` would empty this string: the engine's test for an
   * unkeyable id. Not `isBlank()`, which treats U+001C..U+001F and U+0085 as whitespace where JS
   * does not, and would wrongly reject a whole document. The set is the spec's `WhiteSpace` ∪
   * `LineTerminator`.
   */
  private fun isEcmaScriptBlank(value: String): Boolean = value.all {
    when (it) {
      '\u0009',
      '\u000B',
      '\u000C',
      '\uFEFF' -> true
      '\u000A',
      '\u000D',
      '\u2028',
      '\u2029' -> true
      else -> Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
    }
  }

  /**
   * Stage the published design-parity activity feed, if any, like [writeAnnotations]; without it
   * `/parity` falls back to coverage-only on published catalogs. Validated before writing;
   * fail-soft.
   */
  private fun writeParityActivity(base: String, staging: File) {
    val bytes =
      runCatching { fetchCatalogAsset("$base${ParityActivity.DIRECTORY}/${ParityActivity.FILE}") }
        .getOrNull() ?: return
    val activity =
      runCatching { json.decodeFromString(ParityActivity.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.let { ServeParityActivityStore.sanitize(it) } ?: return
    val dir = File(staging, ParityActivity.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityActivity.FILE)
      .writeText(json.encodeToString(ParityActivity.serializer(), activity))
  }

  /**
   * Stage the catalog's design-guidelines results (`guidelines.json`) when published, validated and
   * fail-soft like [writeParityIssues].
   */
  private fun writeGuidelineResults(base: String, staging: File) {
    val bytes =
      runCatching { fetchCatalogAsset("$base${ServeGuidelineResultsStore.FILE}") }.getOrNull()
        ?: return
    if (bytes.size > MAX_GUIDELINE_RESULTS_BYTES) return
    if (ServeGuidelineResultsStore.parse(bytes.decodeToString()) == null) return
    File(staging, ServeGuidelineResultsStore.FILE).writeBytes(bytes)
  }

  /**
   * Lift `guidelines.json` out of the live [bundleFile] unless the branch supplied one.
   * Best-effort, like [extractComponentRecord].
   */
  private fun extractGuidelineResults(bundleFile: File, dir: File) =
    liftGuidelineResults(bundleFile, dir, MAX_GUIDELINE_RESULTS_BYTES)

  /** Stage the validated GitHub issue snapshot published beside the parity activity feed. */
  private fun writeParityIssues(base: String, staging: File) {
    val bytes =
      runCatching { fetchCatalogAsset("$base${ParityIssues.DIRECTORY}/${ParityIssues.FILE}") }
        .getOrNull() ?: return
    val issues =
      runCatching { json.decodeFromString(ParityIssues.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.let { ServeParityIssuesStore.sanitize(it) } ?: return
    val dir = File(staging, ParityIssues.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityIssues.FILE).writeText(json.encodeToString(ParityIssues.serializer(), issues))
  }

  /**
   * Stage the catalog's parity verdict (`parity/findings.json`), validated and re-serialized like
   * [writeParityIssues]. Without it the verdict panel is silently dark on published catalogs.
   */
  private fun writeParityFindings(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset("$base${ParityFindings.DIRECTORY}/${ParityFindings.FILE}")
      }
        .getOrNull() ?: return
    val findings =
      runCatching { ServeParityFindingStore.sanitizeDocument(bytes.decodeToString()) }.getOrNull()
        ?: return
    val dir = File(staging, ParityFindings.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityFindings.FILE)
      .writeText(json.encodeToString(ParityFindings.serializer(), findings))
  }

  /**
   * Stage the catalog's design pages: the validated manifest ([ServeDesignPageStore.drawablePages])
   * plus one SVG per page, re-pathed to `pages/<id>.svg` so a manifest cannot dictate where bytes
   * land. A page whose SVG can't be fetched is dropped rather than 404ing on open. Fail-soft.
   */
  private fun writeDesignPages(base: String, staging: File) {
    val dirName = ServeDesignPageStore.DIRECTORY
    val manifestBytes =
      runCatching { fetchCatalogAsset("$base$dirName/${ServeDesignPageStore.INDEX_FILE}") }
        .getOrNull() ?: return
    val manifest =
      runCatching {
        DesignPagesJson.decodeFromString(
          DesignPagesManifest.serializer(),
          manifestBytes.decodeToString(),
        )
      }
        .getOrNull() ?: return
    // Capped before fetching, like `maxImages`, so a branch declaring hundreds of pages can't flood
    // requests or the staging disk.
    val declared = ServeDesignPageStore.drawablePages(manifest)
    val pages = declared.take(MAX_DESIGN_PAGES)
    if (declared.size > pages.size) {
      System.err.println(
        "serve: catalog declares ${declared.size} design pages — staging the first $MAX_DESIGN_PAGES"
      )
    }
    if (pages.isEmpty()) return

    // One bounded wave at a time, since specimen sheets can approach a megabyte each.
    val accepted =
      pages.chunked(ASSET_FETCH_CONCURRENCY).flatMap { wave ->
        val fetched = fetchCatalogAssets(wave.map { "$base$dirName/${it.image.uri}" })
        wave.mapNotNull { page ->
          val bytes = fetched["$base$dirName/${page.image.uri}"] ?: return@mapNotNull null
          val localName = "${page.id}.svg"
          val file = File(staging, "$dirName/$localName")
          file.parentFile?.mkdirs()
          file.writeBytes(bytes)
          // `newBuilder()`, not `copy()`: these wire types keep `copy` internal so upstream field
          // additions don't break released callers.
          page
            .newBuilder()
            .also {
              it.image = page.image.newBuilder().also { image -> image.uri = localName }.build()
            }
            .build()
        }
      }
    if (accepted.isEmpty()) return
    File(staging, dirName).mkdirs()
    File(staging, "$dirName/${ServeDesignPageStore.INDEX_FILE}")
      .writeText(
        DesignPagesJson.encodeToString(
          DesignPagesManifest.serializer(),
          manifest.newBuilder().also { it.pages = accepted }.build(),
        )
      )
  }

  /**
   * Stage the accepted references and return reference id → branch path. Pinned (`?at=<sha>`)
   * requests resolve against this map, since the staged manifest rewrites rasters to server-owned
   * `references/<id>.png` and erases the producer's path.
   */
  private fun writeDesignReferences(
    references: List<DesignReference>,
    base: String,
    staging: File,
  ): Map<String, String> {
    if (references.isEmpty()) return emptyMap()
    val seen = HashSet<String>()
    // References need bytes in hand (dimensions and optional sha256 are checked by
    // [ServeDesignReferenceStore]), so fetch one bounded wave at a time.
    val branchPaths = LinkedHashMap<String, String>()
    val accepted =
      references
        .filter { ServeDesignReferenceStore.isSafeRelativePath(it.raster.path) }
        .chunked(ASSET_FETCH_CONCURRENCY)
        .flatMap { wave ->
          val fetched = fetchCatalogAssets(wave.map { "$base${it.raster.path}" })
          wave.mapNotNull { reference ->
            val bytes = fetched["$base${reference.raster.path}"] ?: return@mapNotNull null
            if (!ServeDesignReferenceStore.isValid(reference, bytes) || !seen.add(reference.id))
              return@mapNotNull null
            val localPath = "${ServeDesignReferenceStore.DIRECTORY}/${reference.id}.png"
            val file = File(staging, localPath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            branchPaths[reference.id] = reference.raster.path
            val artifact =
              if (ServeUidReference.isUid(reference)) {
                val original = reference.artifact!!
                val document = fetchCatalogAsset("$base${original.path}")
                if (document != null && ServeUidReference.valid(reference, document)) {
                  val uidPath = "references/${reference.id}.uid"
                  File(staging, uidPath).writeBytes(document)
                  original.copy(path = uidPath)
                } else null
              } else reference.artifact
            reference.copy(raster = reference.raster.copy(path = localPath), artifact = artifact)
          }
        }
    if (accepted.isEmpty()) return emptyMap()
    val referenceDir = File(staging, ServeDesignReferenceStore.DIRECTORY)
    referenceDir.mkdirs()
    File(referenceDir, ServeDesignReferenceStore.INDEX_FILE)
      .writeText(
        json.encodeToString(
          DesignReferenceManifest.serializer(),
          DesignReferenceManifest(references = accepted),
        )
      )
    return branchPaths
  }

  /**
   * Read the published provider-neutral manifest; [Catalog.references] (inline, older catalogs) is
   * the fallback at the call site.
   */
  private fun fetchDesignReferences(base: String): List<DesignReference> {
    val manifestBytes =
      runCatching {
        fetchCatalogAsset(
          "$base${ServeDesignReferenceStore.DIRECTORY}/${ServeDesignReferenceStore.INDEX_FILE}"
        )
      }
        .getOrNull() ?: return emptyList()
    return runCatching {
      json.decodeFromString(DesignReferenceManifest.serializer(), manifestBytes.decodeToString())
    }
      .getOrNull()
      ?.takeIf { it.schema == DesignReferenceManifest.SCHEMA }
      ?.references
      .orEmpty()
  }

  /**
   * The catalog's design tokens projected onto serve chrome ([ServeThemeCss]), or null on any
   * failure, in which case pages use the built-in chrome.
   */
  private fun fetchWebThemeCss(tokensFile: String?, base: String): String? {
    val path = tokensFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // Branch-relative only. The branch is trusted, but a garbled/hostile `tokensFile` must not be
    // able to point the fetch at another host or walk out of the catalog.
    if (path.startsWith("/") || "://" in path || ".." in path.split("/")) return null
    val bytes = runCatching { fetchCatalogAsset(base + path) }.getOrNull() ?: return null
    return runCatching { ServeThemeCss.fromDtcg(bytes.decodeToString()) }.getOrNull()
  }

  /** Minimal mirror of the `design-parity-catalog/v1` schema: only the fields we serve. */
  @Serializable
  private data class Catalog(
    /** Human display title (e.g. "Compose Material 3"); surfaced on the public home index. */
    val title: String? = null,
    /** Underlying library coordinate(s); shown as the one-line descriptor on a system card. */
    val library: List<String> = emptyList(),
    /** ISO-8601 time the delivery branch was generated; shown on the provenance strip. */
    val generatedAt: String? = null,
    /**
     * The renderer that produced the catalog (`compose-preview <version>`); shown on the provenance
     * strip.
     */
    val renderer: String? = null,
    /**
     * The `@design-parity/catalog-export` version that built the catalog; shown on the provenance
     * strip.
     */
    val designParity: String? = null,
    val components: List<Component> = emptyList(),
    /** Provider-neutral design references, each carrying a canonical PNG for exact comparison. */
    val references: List<DesignReference> = emptyList(),
    /**
     * Presentation hints the system declared (stage surface, hero), read instead of inferring from
     * the system name.
     */
    val display: CatalogDisplay? = null,
    /**
     * The sibling system this catalog is a parallel rendition of; only useful together with
     * [Component.parallel]. Usually absent.
     */
    val compareWith: CatalogCompareWith? = null,
    /**
     * Branch-relative DTCG token file with the system's palette, read by [fetchWebThemeCss]; absent
     * when none is published.
     */
    val tokensFile: String? = null,
    /**
     * Branch-relative component record (`components.json`), staged by [writeComponentRecord] for
     * UI-builder packs and export. May instead come from the live bundle
     * ([extractComponentRecord]); absent from both, the catalog is browsable but not authorable.
     */
    val componentsFile: String? = null,
    /**
     * Branch-relative builder catalog (`ui-builder.json`): platform, shelves, frame, templates and
     * screen strategy. Staged by [writeUiBuilderCatalog], read by [uiBuilderCatalog]; absent until
     * a catalog opts in, so the reader falls back.
     */
    val uiBuilderFile: String? = null,
    /** Verified executable renderer archive paired with this exact catalog generation. */
    val uiBuilderRuntime: UiBuilderRuntimeArtifactV1? = null,
    /** Optional in-browser render descriptor (the CMP-Wasm app carried in the branch). */
    val webRender: WebRender? = null,
    /** Optional buildable source for trusted server-side re-render (`--allow-render-trusted`). */
    val source: Source? = null,
    /**
     * Optional executable preview bundle carried beside the baked PNGs, preferred over [source] for
     * trusted re-render (no Gradle build, no worktree).
     */
    val liveBundle: LiveBundle? = null,
    /**
     * One independently executable bundle per Gradle module; [liveBundle] remains the v1 primary.
     */
    val liveBundles: List<LiveBundle> = emptyList(),
    /**
     * Live-only coverage the spec declared `priority: "deferred"`, which CI did not rasterise; kept
     * out of `components[].images` so no consumer gets an image with no pixels. See [Deferred].
     */
    val deferred: List<Deferred> = emptyList(),
    /** Structured render failures retained even when their components have no images. */
    val failures: List<CatalogRenderFailure> = emptyList(),
  )

  /**
   * One `deferred[]` record: a preview the catalog declares with no baked PNG, rendered on demand
   * by a live host.
   *
   * [path] is the `images/…` path the sticker would have had, so [previewIdFor] yields the same
   * route a baked one would (flipping `required`/`deferred` never changes its URL). [previewId] is
   * the daemon preview that renders it; [previewIds] is a fallback for older catalogs, used only
   * when unambiguous. Axes and placement ride along so the card lands in the right tab and
   * switcher. Records with no path or no daemon id are skipped.
   */
  @Serializable
  private data class Deferred(
    val path: String? = null,
    val previewId: String? = null,
    val previewIds: List<String> = emptyList(),
    val componentId: String? = null,
    val section: String? = null,
    val group: String? = null,
    /**
     * Caption for a wholly deferred component, which never reaches `components[]`; its deferred
     * variant records inherit it.
     */
    val caption: String? = null,
    val state: String? = null,
    val theme: String? = null,
    val props: JsonObject? = null,
    /** The declared breakpoint this live-only record renders at — see [Image.size]. */
    val size: String? = null,
    /** Counterpart in the `compareWith` sibling, here for the same reason as [caption]. */
    val parallel: String? = null,
    /** As [Component.related]; a wholly deferred component carries its links here instead. */
    val related: List<Related> = emptyList(),
    /** Why it was deferred (`entry` / `variant` / `mode`) — carried for diagnostics. */
    val reason: String? = null,
    /**
     * Discovery-time `@FixedTheme` (see [Image.fixedTheme]); the live render is a deferred
     * specimen's only render, so the flag matters even more.
     */
    val fixedTheme: Boolean = false,
    /**
     * Discovery-time `@OverrideVariant(secondary = true)` (see [VariantMeta.secondary]); this
     * record is the only route for the flag on deferred coverage.
     */
    val secondary: Boolean = false,
  ) {
    /** The daemon preview to render this record through, or null when it has no live twin. */
    val daemonId: String?
      get() =
        previewId?.takeIf { it.isNotBlank() }
          // An older catalog carries only the function's id list; take it only when the function
          // produced exactly one preview, since anything else would be a guess between annotations.
          ?: previewIds.singleOrNull()?.takeIf { it.isNotBlank() }
  }

  /**
   * `catalog.json`'s `display`: the presentation the system declared for itself.
   *
   * [role] picks the page shape; only `samples` (call sites rather than components) means anything
   * today ([ServeWeb.PageRole]), and unknown roles read as default. Declared by the catalog, never
   * inferred by name here (`.github/scripts/ui-builder-catalog-literals.sh`).
   */
  @Serializable
  data class CatalogDisplay(
    val surface: String? = null,
    val hero: String? = null,
    val role: String? = null,
  )

  /**
   * `catalog.json`'s `compareWith`: the sibling system this catalog reproduces. [system] is also
   * its path on a server hosting both; [repo] is set only when the sibling lives elsewhere.
   */
  @Serializable data class CatalogCompareWith(val system: String = "", val repo: String? = null)

  /**
   * `catalog.json` live bundle descriptor. Prefix partitions collision-safe daemon ids by module.
   */
  @Serializable
  private data class LiveBundle(
    val path: String = "",
    val file: String = "",
    val module: String = "",
    val previewIdPrefix: String = "",
  )

  /** `catalog.json`'s `source`: the repo/ref/module to build to re-render this catalog live. */
  @Serializable
  private data class Source(val repo: String = "", val ref: String = "", val module: String = "")

  /**
   * One `components[].related` entry: another catalog that publishes this component. Distinct from
   * [Component.parallel] (see [ServeRelatedCatalogs]). Absent `componentId` means the same
   * spelling; absent `label` means no authored wording.
   */
  @Serializable
  private data class Related(
    val system: String = "",
    val componentId: String? = null,
    val label: String? = null,
  )

  @Serializable
  private data class Component(
    val componentId: String? = null,
    val images: List<Image> = emptyList(),
    /** The counterpart's `componentId` in the [Catalog.compareWith] sibling, or null. */
    val parallel: String? = null,
    /**
     * Other catalogs that publish this component. Additive (empty for older catalogs); see
     * [ServeRelatedCatalogs] for how it differs from [parallel].
     */
    val related: List<Related> = emptyList(),
    /** The authored one-line description of what the component is for, or null. */
    val caption: String? = null,
    /**
     * The production composable's ordered Kotlin value parameters, including content slots. Empty
     * for older catalogs.
     */
    val parameters: List<ComponentParameter> = emptyList(),
    /**
     * Top-level section (the tab: `"Themes"`, `"Components"`, …), one level above [group]. Null ⇒
     * untabbed.
     */
    val section: String? = null,
    /** Sub-heading group within a [section] (e.g. `"Buttons"`, `"Contacts"`). */
    val group: String? = null,
    /**
     * Module-relative source path of the `@Preview`, carried into `previews/variants.json` so the
     * viewer can link to it. Null for older catalogs.
     */
    val sourceFile: String? = null,
    /**
     * Gradle project owning [sourceFile], for repository-wide catalogs. Null for single-module
     * catalogs.
     */
    val sourceModule: String? = null,
    /**
     * 1-based line inside the `@Preview` body in [sourceFile], so the playground handoff can open
     * just that declaration. Null when unknown.
     */
    val bodyLine: Int? = null,
    /**
     * Animated captures (`@InteractionPreview` / `@AnimatedPreview`) published under `motion/`. A
     * separate axis from [images] because every [images] consumer assumes a still.
     */
    val motion: List<Motion> = emptyList(),
  )

  @Serializable
  private data class ComponentParameter(
    val name: String = "",
    val type: String = "",
    val hasDefault: Boolean = false,
    val composableSlot: Boolean = false,
  ) {
    fun toServe(): ServeComponentParameter =
      ServeComponentParameter(
        name = name,
        type = type,
        hasDefault = hasDefault,
        composableSlot = composableSlot,
      )
  }

  /**
   * One published animated capture. [path] (`motion/<slug>/<variant>.apng`) is named from the
   * sibling sticker, so it flattens to the same route id ([motionPreviewIdOf]).
   */
  @Serializable
  private data class Motion(
    val path: String = "",
    /** `"interaction"` (a scripted gesture) or `"animation"` (a self-running animation). */
    val kind: String = "",
    /** The capture's declared caption, telling the reader which property they are being shown. */
    val caption: String? = null,
    /** The theme of the sticker this capture accompanies, used to pair it with the right card. */
    val theme: String? = null,
  )

  @Serializable
  private data class Image(
    val path: String,
    /**
     * Daemon preview id that produced this image, bridging the route-safe catalog id to a live
     * host. Null ⇒ no live lane; stays baked.
     */
    val previewId: String? = null,
    /** Baked component state (`"pressed"`, `"disabled"`, …), or `"default"`/null. */
    val state: String? = null,
    /**
     * Baked theme (`"light"`/`"dark"`), or null; scopes the state switcher to same-theme siblings.
     */
    val theme: String? = null,
    /**
     * Variant axis this render varies (`{"locale":"ar-XB"}`, `{"fontScale":"2.0"}`, …), or empty
     * for the default. Lets the grid fold it onto the component's card like [state].
     */
    val props: JsonObject? = null,
    /**
     * Declared breakpoint (`"192dp"`, `"compact"`, …) this render was captured at, folded onto the
     * component's card like [state] and [props]. Absent when the catalog declares none.
     */
    val size: String? = null,
    /** Author-declared plain-Compose knobs, lifted from this preview's bundle sidecar in CI. */
    val overrides: List<PreviewOverrideDeclaration> = emptyList(),
    /** Author-declared Remote Compose named-value knobs, lifted from its bundle sidecar in CI. */
    val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    /** Discovery-time `@FocusedPreview` support, recorded without opening the live bundle. */
    val supportsFocus: Boolean = false,
    /** Discovery-time `@GestureHintPreview` support, recorded without opening the live bundle. */
    val supportsGestures: Boolean = false,
    /**
     * Discovery-time `@FixedTheme` (or a `@ThemeCatalog` sheet): the subject is a theme, so never
     * re-render it under a `themeProvider` override.
     */
    val fixedTheme: Boolean = false,
    /**
     * Second-tier variant cell (`@OverrideVariant(secondary = true)`): renderable and addressable,
     * but kept out of the browse variant tree. See [VariantMeta.secondary].
     */
    val secondary: Boolean = false,
    /**
     * This render's `@Preview` ground and device frame from export time; see [PreviewParamsMeta].
     */
    val previewParams: PreviewParamsMeta? = null,
  )

  /**
   * One `previews/variants.json` entry: baked [state]/[theme], catalog [section]/[group] and
   * authored [order], written by the fetch loop and read by [ServeBundleHost]. Null keys are
   * omitted. Section/group/order drive the tabbed layout; all null for a flat catalog.
   */
  /**
   * One animated capture offered beside a preview. [id] is a route, not a branch path (the store
   * owns URL assembly and the size cap); [extension] matters because an APNG served as GIF stops at
   * its first frame.
   */
  @Serializable
  data class MotionMeta(
    val id: String,
    val kind: String? = null,
    val caption: String? = null,
    val extension: String = ".apng",
  )

  /**
   * `@Preview` ground and device frame a browse surface needs before any daemon opens. Published
   * catalogs stage no root `previews.json`, so without this every preview fell back to annotation
   * defaults. One nested record so ground and frame can't drift apart; every field defaults, so
   * older catalogs read back as null.
   */
  @Serializable
  data class PreviewParamsMeta(
    /** `@Preview(uiMode = …)`, for the viewer's Day/Night default. */
    val uiMode: Int = 0,
    /** `@Preview(showBackground = …)`. */
    val showBackground: Boolean = false,
    /** `@Preview(backgroundColor = …)`; `0` is the annotation's own "unset". */
    val backgroundColor: Long = 0L,
    /** The raw `@Preview(device = …)` string; the shape is resolved from it, never from the dp. */
    val device: String? = null,
    /** `@Preview(widthDp = …)`, when the annotation states one. */
    val widthDp: Int? = null,
    /** `@Preview(heightDp = …)`, when the annotation states one. */
    val heightDp: Int? = null,
    /**
     * `@CaptureGutter` in render pixels: the transparent margin around the component. Browse
     * surfaces subtract it so a gutter doesn't shrink the component relative to its siblings.
     * Pixels because the exporter resolved them at a density the catalog doesn't carry.
     */
    val captureGutter: CaptureGutterPx? = null,
    /**
     * Id of the Remote Compose player the render was captured with; null means not recorded, not
     * default. Read via [ServeRcPlayerIds.fromCaptureRecord] (`cmp-android` here means the embedded
     * player). Without it [ServeHost.bakedRcPlayer] answers unknown rather than guessing and
     * serving wrong pixels.
     */
    val capturePlayer: String? = null,
  )

  /**
   * A published capture gutter per physical edge, in render pixels. The writer resolves
   * leading/trailing against the capture's layout direction.
   */
  @Serializable
  data class CaptureGutterPx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
  ) {
    /** True when no edge carries a gutter — the same "equivalent to no annotation" rule. */
    fun isEmpty(): Boolean = left <= 0 && top <= 0 && right <= 0 && bottom <= 0
  }

  @Serializable
  data class VariantMeta(
    val state: String? = null,
    val theme: String? = null,
    /** The owning component's authored one-line description, or null. */
    val caption: String? = null,
    /** Production composable API shared by every render of this component. */
    val componentParameters: List<ServeComponentParameter> = emptyList(),
    /**
     * Variant axis this render varies (`{"locale":"ar-XB"}`, …), or empty for the default; lets a
     * host fold it onto the component's card and offer a switcher.
     */
    val props: JsonObject? = null,
    /**
     * Declared breakpoint ([Image.size]), folded onto the component's card with a size switcher.
     * Null when none declared.
     */
    val size: String? = null,
    /** Original catalog component id, retained for human-readable display labels. */
    val componentId: String? = null,
    /** Catalog-published controls used before a lazy per-preview daemon has been opened. */
    val overrides: List<PreviewOverrideDeclaration> = emptyList(),
    val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    val supportsFocus: Boolean = false,
    val supportsGestures: Boolean = false,
    /** Discovery-time `@FixedTheme` — see [Image.fixedTheme]. */
    val fixedTheme: Boolean = false,
    /**
     * Second-tier variant cell (`@OverrideVariant(secondary = true)`): rendered, addressable and
     * kit-paired, but omitted from the variant tree, so an exhaustive kit set doesn't produce an
     * unusable menu.
     */
    val secondary: Boolean = false,
    /**
     * Animated captures this preview can offer instead of its still; surfaced only as an opt-in
     * control.
     */
    val motion: List<MotionMeta> = emptyList(),
    val section: String? = null,
    val group: String? = null,
    val order: Int? = null,
    /**
     * Module-relative source path of the `@Preview` function ([Component.sourceFile]), used for the
     * viewer's GitHub link. Null when unrecorded.
     */
    val sourceFile: String? = null,
    /** Per-preview Gradle project path; overrides the catalog-wide source module when present. */
    val sourceModule: String? = null,
    /**
     * 1-based body line within [sourceFile] ([Component.bodyLine]), so the playground handoff seeds
     * one declaration. Null when unrecorded.
     */
    val bodyLine: Int? = null,
    /** Failure shown by the landing card instead of requesting a missing PNG. */
    val renderFailure: CatalogRenderFailure? = null,
    /**
     * `@Preview` ground and device frame, so the read-only catalog path can resolve backdrop and
     * clip without a daemon. See [PreviewParamsMeta].
     */
    val previewParams: PreviewParamsMeta? = null,
  )

  /**
   * `catalog.json`'s `webRender`: an app under [path] (e.g. `web/wasm/`) with its [files] listed.
   */
  @Serializable
  private data class WebRender(
    val kind: String = "",
    val path: String = "",
    val files: List<String> = emptyList(),
  )

  companion object {
    const val DEFAULT_REPO = "yschimke/compose-ai-tools"
    const val DEFAULT_BRANCH_PREFIX = "design-artifacts/"
    const val CATALOG_FILE = "catalog.json"
    /**
     * Re-exported from [CatalogImagePaths] (shared with `history manifest`) to keep existing call
     * sites.
     */
    const val IMAGES_DIR = CatalogImagePaths.IMAGES_DIR

    /** The delivery branch's directory of published animated captures, beside `images/`. */
    const val MOTION_DIR = "motion"

    /**
     * Closed list: these bytes go to a browser, so fetched JSON must not choose the content type.
     */
    val MOTION_EXTENSIONS = listOf(".apng", ".gif")
    const val FIGMA_DIR = "figma"
    /** A preview id folds the component slug + variant as `<slug>__<variant>`. */
    const val SLUG_SEPARATOR = "__"
    const val WEB_WASM_DIR = "web/wasm"
    const val WEB_RENDER_COMPOSE_WASM = "compose-wasm"
    private const val MAX_WASM_FILES = 64
    /** Local subdir a catalog's `liveBundle` file is fetched into (`<dir>/bundle/<file>`). */
    const val LIVE_BUNDLE_DIR = "bundle"

    /**
     * Staged component record at the generation root, from the declared `componentsFile` or the
     * live bundle. Read by [componentRecord].
     */
    const val COMPONENT_RECORD_FILE = "components.json"

    /** Staged builder catalog from the declared `uiBuilderFile`. Read by [uiBuilderCatalog]. */
    const val UI_BUILDER_CATALOG_FILE = "ui-builder.json"

    /** Expanded, verified catalog renderer runtimes under each immutable generation. */
    const val UI_BUILDER_RUNTIME_DIR = "ui-builder/runtime"

    /** Persisted immutable fetch descriptors, separate from generation retirement. */
    internal const val UI_BUILDER_RUNTIME_DESCRIPTOR_DIR = ".ui-builder-runtime-descriptors"

    /** Process-local expanded historical runtime trees, bounded by renewable leases. */
    internal const val UI_BUILDER_RUNTIME_LEASE_DIR = ".ui-builder-runtime-leases"

    private const val HISTORICAL_RUNTIME_DESCRIPTOR_SCHEMA = "compose-ui-builder-runtime-source/v1"
    private const val RUNTIME_DESCRIPTOR_SUFFIX = "json"
    private const val MAX_HISTORICAL_RUNTIME_LEASES = 3
    private val HISTORICAL_RUNTIME_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(10)

    /**
     * Where [fetchComponentRecord] keeps a record read ahead of any load: under the store root,
     * since generation dirs are swept and the UI builder keeps the file for the process lifetime.
     */
    const val COMPONENT_RECORD_CACHE_DIR = "component-records"

    /** A record is a couple of megabytes for a large catalog; this is a decompression guard. */
    private const val MAX_COMPONENT_RECORD_BYTES = 32L * 1024 * 1024
    private const val MAX_GUIDELINE_RESULTS_BYTES = 16L * 1024 * 1024

    /**
     * Sibling of `previews/` holding `ir/<catalog-id>.rc`, written by [extractCatalogRcDocs] and
     * read by [ServeBundleHost.remoteComposeDoc].
     */
    const val IR_DOC_DIR = "ir"
    const val RC_DOC_SUFFIX = ".rc"

    /**
     * Subdir (under `liveBundle.path` / [LIVE_BUNDLE_DIR]) of per-preview split bundles, fetched by
     * [fetchPerPreviewBundle].
     */
    const val PER_PREVIEW_DIR = "previews"

    /**
     * Per-preview variant manifest under `previews/` (`{ "<id>": { "state": …, "theme": … } }`),
     * read by [ServeBundleHost]. Absent for a stateless catalog.
     */
    const val VARIANTS_FILE = "variants.json"

    /**
     * Subdir under `liveBundle.path` of sha256-addressed externalized resources, fetched by
     * [rehydrateExternalResources].
     */
    const val RES_POOL_DIR = "res"

    /**
     * Default [CatalogBlobPool] root under the store root (process-scoped); `--catalog-cache-dir`
     * overrides it.
     */
    const val BLOB_CACHE_DIR = ".blobs"

    /**
     * Per-system subdir the rehydrated resources are materialized into at their classpath paths.
     */
    const val RES_MATERIALIZED_DIR = "bundle-res"

    /**
     * Single-path-segment preview id for a catalog image path, since routes capture one segment:
     * drops `images/` and `.png` and replaces `/` with `__` (e.g.
     * `button-filled__ideal__default__dark`). The design-parity exporter derives `livePreview`
     * links the same way.
     */
    fun previewIdFor(imagePath: String): String = CatalogImagePaths.previewIdFor(imagePath)

    /**
     * Maximum previews per published catalog unless overridden. Bounds registered metadata, not
     * memory (images are lazy); keep above the largest first-party catalog. Exceeding it rejects
     * the generation rather than truncating.
     */
    const val DEFAULT_MAX_IMAGES = 10_000

    /**
     * Design pages one catalog may stage; a disk and request ceiling, since each is a large SVG.
     */
    const val MAX_DESIGN_PAGES = 40
    private const val MAX_FETCH_BYTES = 25L * 1024 * 1024 // 25 MB per catalog asset
    private const val MAX_RUNTIME_ARCHIVE_FETCH_BYTES = 64L * 1024 * 1024

    /**
     * Live dirs are `<root>/<system>/g<generation>`, staged in `<root>/<system>/.staging`. Nested
     * rather than siblings because a system id may contain a dot (`m3.g3` vs `m3`), and it makes
     * the sweep a listing.
     */
    internal const val GENERATION_DIR_PREFIX = "g"

    internal const val STAGING_DIR = ".staging"

    /**
     * Max entries in a published artifact index before it is ignored: two per contract record. A
     * bound on the fetch plan, not a legality rule.
     */
    private const val MAX_INDEXED_ARTIFACTS = ServeKnownDifferences.MAX_ACCEPTANCES * 2

    /** Cap on a delivery branch's commit feed ([fetchRevisions]), which is tens of kilobytes. */
    private const val MAX_FEED_FETCH_BYTES = 1L * 1024 * 1024

    /**
     * Concurrent catalog asset fetches ([fetchCatalogAssets]). Twelve was the measured knee against
     * `raw.githubusercontent.com`; memory is bounded by this times the per-asset cap.
     */
    private const val ASSET_FETCH_CONCURRENCY = 12

    /**
     * Images fetched before a catalog publishes: the hero plus a couple of others, proving the
     * branch serves pixels without one missing file failing the catalog.
     */
    /**
     * Cached assets the post-publish audit re-reads. Small on purpose: it checks for something that
     * should never happen, so it only needs to be running.
     */
    private const val AUDIT_SAMPLE_ASSETS = 3

    private const val PUBLISH_SAMPLE_IMAGES = 3

    /**
     * Components sampled to decide whether a branch carries baked vectors; more than one since any
     * component may lack one.
     */
    private const val FIGMA_PROBE_SLUGS = 5

    /**
     * Slug normalisation shared with [ServeBundleHost]'s hero resolver; mirrors design-parity's
     * `slug()`.
     */
    private fun heroSlugOf(value: String): String =
      value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase().ifBlank { "x" }

    internal const val MAX_LIVE_BUNDLE_FETCH_BYTES = 100L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    /**
     * One branch read, retried while the failure is transient: `raw.githubusercontent.com`
     * rate-limits unauthenticated readers, so two short `Retry-After`-aware retries recover most
     * 429s. [BranchFetch.NotFound] is never retried.
     */
    internal fun httpFetchOutcome(
      url: String,
      maxBytes: Long,
      sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): BranchFetch {
      var last: BranchFetch = httpFetchOnce(url, maxBytes)
      var attempt = 1
      while (true) {
        val delay = BranchFetch.retryDelayMillis(last, attempt) ?: return last
        try {
          sleep(delay)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return last
        }
        last = httpFetchOnce(url, maxBytes)
        if (!last.isTransient) return last
        attempt++
      }
    }

    /**
     * A single attempt. Only [java.io.IOException] becomes [BranchFetch.Transport]. Oversize is an
     * outcome ([BranchFetch.TooLarge], not retried) rather than an exception, because known
     * differences must distinguish too-large from absent.
     */
    private fun httpFetchOnce(url: String, maxBytes: Long): BranchFetch =
      try {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) {
            BranchFetch.ofStatus(
              response.code,
              BranchFetch.parseRetryAfter(response.header("Retry-After")),
            )
          } else {
            val body = response.body
            readCapped(body.byteStream(), maxBytes)?.let { BranchFetch.Ok(it) }
              ?: BranchFetch.TooLarge(maxBytes)
          }
        }
      } catch (e: java.io.IOException) {
        BranchFetch.Transport(e::class.simpleName ?: "IOException")
      }

    private fun httpFetch(url: String, maxBytes: Long): ByteArray? =
      httpFetchOutcome(url, maxBytes).bytesOrNull

    /**
     * A `HEAD` probe as an outcome; [BranchFetch.Ok] carries no bytes. A 429 is a throttle to
     * count, not an absent file.
     */
    private fun httpProbeOutcome(url: String): BranchFetch =
      try {
        httpClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
          if (response.isSuccessful) BranchFetch.Ok(ByteArray(0))
          else
            BranchFetch.ofStatus(
              response.code,
              BranchFetch.parseRetryAfter(response.header("Retry-After")),
            )
        }
      } catch (e: java.io.IOException) {
        BranchFetch.Transport(e::class.simpleName ?: "IOException")
      }

    /**
     * The body, or null once it reads past [max]; abandons the read rather than downloading the
     * rest just to learn its length.
     */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
      }
      return out.toByteArray()
    }
  }

  /** Current generation per system; see [scheduleFigmaSvgFetch]. */
  private val generations = ConcurrentHashMap<String, Int>()

  /**
   * Generation directory each system's registered host reads from. Written only when a host is
   * published, so a failed load leaves the previous entry.
   */
  private val liveDirs = ConcurrentHashMap<String, File>()

  /** Verified runtime roots from published generations that have not yet been retired. */
  private val runtimeRoots = ConcurrentHashMap.newKeySet<File>()

  /**
   * Access-ordered expanded historical runtimes; every mutation is under [historicalRuntimeAsset].
   */
  private val historicalRuntimeLeases =
    LinkedHashMap<String, HistoricalRuntimeLease>(16, 0.75f, true)

  /** Process-local: archives persist in [blobs]; expanded trees are recreated after restart. */
  private val historicalRuntimeLeaseRoot: File by lazy {
    File(root, UI_BUILDER_RUNTIME_LEASE_DIR).apply {
      deleteRecursively()
      check(mkdirs() || isDirectory) { "could not create historical runtime lease directory" }
    }
  }

  /** Where [system]'s registered host reads its bytes from, or null if it has never published. */
  fun liveDir(system: String): File? = ServeBundleStore.sanitizeName(system)?.let { liveDirs[it] }

  /**
   * Record [dir] as [safe]'s live generation when its host is registered. Called at every publish
   * point, so a generation is never marked live without a host (or swept while one still serves
   * it).
   */
  private fun publishGeneration(safe: String, dir: File, wasmDir: File?) {
    liveDirs[safe] = dir
    File(dir, UI_BUILDER_RUNTIME_DIR).takeIf(File::isDirectory)?.let(runtimeRoots::add)
    // Always, including null, so an earlier generation's in-browser app is withdrawn. See
    // [registerWasm].
    registerWasm(safe, wasmDir)
  }

  /**
   * Delete every generation directory of [safe] except the live one and [keep]. Run at the start of
   * a load, giving in-flight reads against the outgoing host until the next refresh. Not
   * correctness-critical: a miss only costs disk. On a fresh start everything on disk is stale.
   */
  private fun retireStaleGenerations(safe: String, systemRoot: File, keep: Set<String>) {
    val live = liveDirs[safe]?.name
    for (child in systemRoot.listFiles().orEmpty()) {
      if (child.name == live || child.name in keep) continue
      runCatching { child.deleteRecursively() }
    }
  }

  /**
   * The delivery branch's revisions, newest first, from its Atom feed
   * ([ServeCatalogRevision.commitsFeedUrl]). The head is what this load reads; the tail is the
   * history visitors can pin. Best-effort: any failure returns empty, costing only the permalink
   * affordance.
   */
  private fun fetchRevisions(repo: String, branch: String): List<ServeCatalogRevision.Revision> =
    runCatching {
      val url = ServeCatalogRevision.commitsFeedUrl(repo, branch)
      val body =
        if (fetch != null) fetch.invoke(url) else branchRead(url, MAX_FEED_FETCH_BYTES).bytesOrNull
      body?.toString(Charsets.UTF_8)?.let { ServeCatalogRevision.parseCommitsFeed(it) }
    }
    .getOrNull()
    .orEmpty()

  /**
   * Delivery-branch shas in which one render's bytes changed, via the path-scoped feed
   * ([ServeCatalogRevision.pathCommitsFeedUrl]).
   *
   * Null, not empty, on failure, and an empty parse counts as failure: a published path always has
   * at least its adding commit, so zero entries means an error page or reshaped feed. Empty would
   * wrongly claim every publish was pixel-identical.
   */
  private fun fetchRenderChanges(repo: String, branch: String, path: String): Set<String>? =
    runCatching {
      val url = ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, path) ?: return null
      val body =
        if (fetch != null) fetch.invoke(url) else branchRead(url, MAX_FEED_FETCH_BYTES).bytesOrNull
      body?.toString(Charsets.UTF_8)?.let { xml ->
        ServeCatalogRevision.parseCommitsFeed(xml, ServeCatalogRevision.MAX_PATH_REVISIONS)
          .map { it.commit }
          .toSet()
          .takeIf { it.isNotEmpty() }
      }
    }
    .getOrNull()

  /**
   * Delivery-branch read counters (`/status.json` → `branchFetch`). Per-store and wrapped around
   * [networkFetch], so they are not shared across test stores and still count with an injected
   * transport.
   */
  val branchFetchStats = BranchFetchStats()

  /** [networkFetch] with its outcome counted. Every network read in this store goes through it. */
  private fun branchRead(url: String, maxBytes: Long): BranchFetch =
    networkFetch(url, maxBytes).also {
      branchFetchStats.record(it)
      // Attributed to whatever operation issued it; a read outside one belongs to nobody.
      if (it.isTransient) activeFetchScope.get()?.recordTransient()
    }

  /** [networkProbe] with its outcome counted; true only when the branch actually has the file. */
  private fun branchProbe(url: String): Boolean =
    networkProbe(url).also(branchFetchStats::record) is BranchFetch.Ok

  /**
   * One operation's tally of transient read failures. Scoped per operation because request-time
   * lazy reads share `fetchCatalogAsset`; a store-wide count would let a retrying client mark
   * complete revisions incomplete, forcing re-reads while the branch host is unhealthy.
   */
  private class FetchScope {
    private val transient = java.util.concurrent.atomic.AtomicLong()

    fun recordTransient() {
      transient.incrementAndGet()
    }

    val sawTransientFailure: Boolean
      get() = transient.get() > 0
  }

  /** The scope reads on this thread belong to; null for a request-time lazy fetch. */
  private val activeFetchScope = ThreadLocal<FetchScope?>()

  /** Run [body] with a fresh scope installed, restoring whatever was there before. */
  private fun <T> inFetchScope(body: (FetchScope) -> T): T {
    val scope = FetchScope()
    val previous = activeFetchScope.get()
    activeFetchScope.set(scope)
    return try {
      body(scope)
    } finally {
      activeFetchScope.set(previous)
    }
  }

  /** Fetch an ordinary catalog asset using the existing tight per-file envelope. */
  private fun fetchCatalogAsset(url: String): ByteArray? = cachedBranchRead(url).bytesOrNull

  /**
   * Every small-asset read, with the [blobs] pool in front of the transport.
   *
   * Admission is by URL ([ServeCatalogRevision.isCommitPinned]), since pinned requests address
   * commits the load never resolved; un-pinned branch URLs bypass the pool. `Ok` stores bytes;
   * `NotFound` is remembered for a day ([CatalogBlobPool.knownMissing]); transient failures are
   * never remembered. The pool sits outside the injected [fetch] seam so stubbed tests exercise the
   * same caching.
   */
  private fun cachedBranchRead(url: String, maxBytes: Long = MAX_FETCH_BYTES): BranchFetch {
    if (!ServeCatalogRevision.isCommitPinned(url)) return directBranchRead(url, maxBytes)
    blobs
      .read(url)
      ?.takeIf { it.size.toLong() <= maxBytes }
      ?.let {
        branchFetchStats.recordCached()
        return BranchFetch.Ok(it)
      }
    if (blobs.knownMissing(url)) {
      branchFetchStats.recordCached()
      return BranchFetch.NotFound
    }
    return directBranchRead(url, maxBytes).also {
      when (it) {
        is BranchFetch.Ok -> blobs.write(url, it.bytes)
        // Remembered for a day, not forever: see [CatalogBlobPool.knownMissing].
        BranchFetch.NotFound -> blobs.recordMissing(url)
        else -> Unit
      }
    }
  }

  /**
   * The transport without the pool. Used by [auditCachedAssets], which must compare against the
   * branch, not the cache.
   */
  private fun directBranchRead(url: String, maxBytes: Long = MAX_FETCH_BYTES): BranchFetch =
    if (fetch != null)
      fetch.invoke(url)?.takeIf { it.size.toLong() <= maxBytes }?.let { BranchFetch.Ok(it) }
        ?: BranchFetch.NotFound
    else branchRead(url, maxBytes)

  /**
   * Re-read a small fixed sample of cached assets from the branch and compare with the pool
   * ([CatalogBlobPool.audit]). Post-publish, never a gate. The sample is the front of the baked
   * paths, so a mis-filing shows up on every load of a revision.
   */
  private fun auditCachedAssets(urls: List<String>, system: String) {
    for (url in urls) {
      val fresh = runCatching { directBranchRead(url) }.getOrNull()?.bytesOrNull ?: continue
      if (blobs.audit(url, fresh) == CatalogBlobPool.AuditResult.MISMATCHED) {
        System.err.println(
          "serve: $system cached bytes for $url did not match the branch — entry dropped. " +
            "This should be impossible; check /status.json catalogCache.mismatched."
        )
      }
    }
  }

  /**
   * [fetchCatalogAsset], keeping the failure reason. The injected [fetch] seam stays `ByteArray?`;
   * a stub's null is reported as [BranchFetch.NotFound].
   */
  private fun fetchCatalogAssetOutcome(url: String): BranchFetch = cachedBranchRead(url)

  /**
   * Fetch [urls] concurrently, returning `url → bytes` for those that arrived; failures are absent
   * (callers skip them). Concurrency matters because per-asset round-trips, not bandwidth,
   * dominate. Callers keep their sequential loop, so ordering is unchanged.
   */
  /**
   * Concurrent fetch that never accumulates: each worker writes straight to its planned
   * destination, so peak memory is the in-flight assets. Returns urls both fetched and written.
   * Destinations are path-contained by the caller.
   */
  private fun fetchCatalogAssetsToFiles(
    plan: List<Pair<String, File>>,
    stillWanted: () -> Boolean = { true },
    /**
     * When set, a size-refused asset is staged as a marker of this many bytes
     * ([stageOversizeMarker]). Only for lanes whose reader distinguishes too-large from absent.
     */
    oversizeMarkerBytes: Long? = null,
  ): Set<String> {
    if (plan.isEmpty()) return emptySet()
    val pool =
      Executors.newFixedThreadPool(minOf(ASSET_FETCH_CONCURRENCY, plan.size)) { r ->
        Thread(r, "serve-catalog-fetch").apply { isDaemon = true }
      }
    return try {
      // The scope these reads belong to is the caller's, and a `ThreadLocal` set here is invisible
      // to the workers — so it is captured at submission and re-established inside each task.
      val submitting = activeFetchScope.get()
      val inFlight = plan.map { (url, target) ->
        url to
          pool.submit<Boolean> {
            val previous = activeFetchScope.get()
            activeFetchScope.set(submitting)
            try {
              val outcome =
                runCatching { fetchCatalogAssetOutcome(url) }.getOrNull() ?: return@submit false
              // Re-checked immediately before each write so a fetch for a superseded generation
              // can't land in the swapped-in directory.
              if (outcome is BranchFetch.TooLarge && oversizeMarkerBytes != null) {
                if (!stillWanted()) return@submit false
                return@submit stageOversizeMarker(target, oversizeMarkerBytes)
              }
              val bytes = outcome.bytesOrNull ?: return@submit false
              if (!stillWanted()) return@submit false
              runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
              }
                .isSuccess
            } finally {
              activeFetchScope.set(previous)
            }
          }
      }
      buildSet {
        for ((url, future) in inFlight) {
          if (runCatching { future.get() }.getOrNull() == true) add(url)
        }
      }
    } finally {
      pool.shutdown()
    }
  }

  private fun fetchCatalogAssets(urls: List<String>): Map<String, ByteArray> {
    val distinct = urls.distinct()
    if (distinct.size <= 1) {
      val only = distinct.firstOrNull() ?: return emptyMap()
      return runCatching { fetchCatalogAsset(only) }.getOrNull()?.let { mapOf(only to it) }
        ?: emptyMap()
    }
    val pool =
      Executors.newFixedThreadPool(minOf(ASSET_FETCH_CONCURRENCY, distinct.size)) { r ->
        Thread(r, "serve-catalog-fetch").apply { isDaemon = true }
      }
    val submitting = activeFetchScope.get()
    return try {
      val inFlight = distinct.map { url ->
        url to
          pool.submit<ByteArray?> {
            val previous = activeFetchScope.get()
            activeFetchScope.set(submitting)
            try {
              runCatching { fetchCatalogAsset(url) }.getOrNull()
            } finally {
              activeFetchScope.set(previous)
            }
          }
      }
      buildMap {
        for ((url, future) in inFlight) {
          runCatching { future.get() }.getOrNull()?.let { put(url, it) }
        }
      }
    } finally {
      pool.shutdown()
    }
  }

  /**
   * Fetch an executable bundle with the 100 MB envelope shared by uploaded and startup bundles. A
   * [fetch] override still intercepts it.
   */
  private fun fetchExecutableBundle(url: String): ByteArray? =
    if (fetch != null) fetch.invoke(url)
    else branchRead(url, MAX_LIVE_BUNDLE_FETCH_BYTES).bytesOrNull
}

/**
 * Lift `guidelines.json` out of [bundleFile] into `<dir>/guidelines.json`, unless [dir] already has
 * one: written only when the entry is at most [maxBytes] and parses as guideline results.
 * Best-effort — a bundle without the entry, or one that will not read, leaves [dir] as it was.
 */
internal fun liftGuidelineResults(bundleFile: File, dir: File, maxBytes: Long) {
  val target = File(dir, ServeGuidelineResultsStore.FILE)
  if (target.isFile) return
  val bytes =
    runCatching { zipEntryBytes(bundleFile, ServeGuidelineResultsStore.FILE, maxBytes) }.getOrNull()
      ?: return
  if (ServeGuidelineResultsStore.parse(bytes.decodeToString()) == null) return
  target.parentFile?.mkdirs()
  target.writeBytes(bytes)
}

/** The bytes of [bundleFile]'s entry named [name], capped at [maxBytes]; null when absent. */
private fun zipEntryBytes(bundleFile: File, name: String, maxBytes: Long): ByteArray? {
  val zipBytes = BundleReader.extractZipBytes(bundleFile)
  java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zin ->
    var entry = zin.nextEntry
    while (entry != null) {
      if (!entry.isDirectory && entry.name.replace('\\', '/') == name) {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = zin.read(chunk)
          if (n < 0) break
          total += n
          check(total <= maxBytes) { "$name exceeds the cap" }
          buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
      }
      zin.closeEntry()
      entry = zin.nextEntry
    }
  }
  return null
}
