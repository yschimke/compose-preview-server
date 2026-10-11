package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A [ServeHost] fronting a trusted design-system catalog with its baked-PNG render plus an opt-in
 * live daemon stream, bridging the two id namespaces so published catalog URLs keep working.
 *
 * A daemon knows previews by descriptor id (`FilledButton_Dark`), while published links use the
 * slug id (`button-filled__ideal__default__dark`). A bare [ServeRenderHost] would 404 every
 * `/p/<id>` link, drop the title and trust badge, and (via `canApplyOverrides`) render every browse
 * through the daemon.
 *
 * So the [baked] [ServeBundleHost] remains the whole snapshot surface (previews, grid, links,
 * thumbnails, badge) and browsing never wakes the daemon. The [live] daemon is reached through
 * [subscribeStream] and overrides, mapping ids via [alias]; ids without an alias stay baked.
 *
 * With [perPreviewResolve], override renders first try a per-preview daemon built from that
 * preview's own bundle, falling back to the monolithic [live] daemon, then to [baked]. The worst
 * case is the plain monolithic host.
 */
class ServeCatalogLiveHost(
  /**
   * Catalog id → daemon preview id. Widened by [liveOnlyPlaygrounds] into the property of the same
   * name.
   */
  alias: Map<String, String>,
  /** The daemon-backed host, keyed by daemon preview ids (the [alias] values). */
  private val live: ServeHost,
  /** The static baked-PNG host, keyed by catalog ids (the browse + snapshot surface). */
  private val baked: ServeHost,
  /**
   * Resolve a per-preview daemon host for a daemon-preview id, or null to fall back to [live].
   * Tried first for alias-mapped override renders; the caller owns and pools the host. Null
   * disables the lane.
   */
  private val perPreviewResolve: ((daemonId: String) -> ServeHost?)? = null,
  /** Availability probe backed by the publication-aware per-preview fetcher. */
  private val executableBundleAvailable: ((daemonId: String) -> Boolean)? = null,
  /** Hydrated per-preview bundle bytes for the viewer's executable download lane. */
  private val executableBundleProvider: ((daemonId: String) -> ByteArray?)? = null,
  /** Live upstream stream count across the pooled per-preview daemons (supplied by the pool). */
  private val perPreviewStreamCount: () -> Int = { 0 },
  /**
   * Render-latency snapshots of pooled per-preview daemons, folded into [renderPerfStats]; this is
   * the default render path, so `/status` must include it.
   */
  private val perPreviewRenderStats: () -> List<RenderPerfSnapshot> = { emptyList() },
  /** Pool occupancy snapshots for `/status.json`, supplied by the pool. */
  private val perPreviewPoolStats: () -> List<DaemonPoolSnapshot> = { emptyList() },
  /**
   * Close per-preview daemons idle for the given window, returning how many; drives the pooled half
   * of [releaseIdleDaemons].
   */
  private val perPreviewReapIdle: (idleMillis: Long) -> Int = { 0 },
  /** Identical monolithic daemon replicas used only for a leased theme-render batch. */
  private val sharedDaemonPool: ServeSharedDaemonPool? = null,
  /**
   * Serve the baked vector immediately and warm the daemon in the background instead of blocking a
   * browse on a cold (possibly minutes-long) first render. Off by default;
   * `-Dcomposeai.serve.warmInBackground=true` enables it.
   */
  private val warmInBackground: Boolean =
    System.getProperty("composeai.serve.warmInBackground")?.toBooleanStrictOrNull() ?: false,
  private val catalogThemeCache: CatalogThemeCache = CatalogThemeCache(),
  /**
   * Whether to eagerly fill [catalogThemeCache] on the idle pass (`previews × declaredThemes`
   * renders). On by default, guarded by [ServeBackgroundWork], the quiet window and the cache's
   * LRU; `-Dcomposeai.serve.themeOptimization=false` disables it.
   */
  private val themeOptimizationEnabled: Boolean =
    System.getProperty("composeai.serve.themeOptimization")?.toBooleanStrictOrNull() ?: true,
  private val serverIdleMillis: () -> Long? = { Long.MAX_VALUE },
  /**
   * Server-wide admission for the idle theme optimizer, shared by every catalog host so passes take
   * turns ([ServeBackgroundWork]).
   */
  private val backgroundWork: ServeBackgroundWork = ServeBackgroundWork(),
  private val themeOptimizationIdleMillis: Long = themeOptimizationIdleMillisDefault(),
  /**
   * How long the idle gate may withhold a turn before one is forced ([grantForcedTurn]);
   * non-positive disables the ceiling.
   */
  private val optimizerGateCeilingMillis: Long = optimizerGateCeilingMillisDefault(),
  /**
   * How long one admitted pass holds its optimizer lane before re-queueing. Trades rotation latency
   * against re-warming: shorter than a cold daemon start (34-68s on Android) wastes the lane
   * warming, while too long starves other catalogs.
   */
  private val optimizerSliceMillis: Long =
    System.getProperty("composeai.serve.themeOptimizerSliceMillis")?.toLongOrNull()
      ?: DEFAULT_OPTIMIZER_SLICE_MILLIS,
  /** Bounds the override render that follows a successful cold-id warm. */
  private val foregroundOverrideTimeoutMillis: Long = FOREGROUND_WARM_AWAIT_MILLIS,
  /** Injectable so admission retry behavior can be covered without a 20-second test. */
  private val optimizerAdmissionWaitMillis: Long = OPTIMIZER_ADMISSION_WAIT_MILLIS,
  /**
   * Route snapshot renders to the shared monolithic daemon rather than the per-preview pool
   * ([renderHostFor]). `-Dcomposeai.serve.sharedDaemonRenders=false` isolates each preview at the
   * cost of a cold start per card.
   */
  private val sharedDaemonRenders: Boolean =
    System.getProperty("composeai.serve.sharedDaemonRenders")?.toBooleanStrictOrNull() ?: true,
  /**
   * Whether [prewarm] warms the daemon when the session opens; off by default because every catalog
   * opens at boot, spawning one JVM each while catalogs are still fetching. A visitor's heartbeat
   * ([keepLiveWarm]) warms on demand instead. `-Dcomposeai.serve.eagerWarmOnOpen=true` restores it.
   */
  private val eagerWarmOnOpen: Boolean =
    System.getProperty("composeai.serve.eagerWarmOnOpen")?.toBooleanStrictOrNull() ?: false,
  /**
   * Box-wide daemon residency budget ([LiveSeatLimiter]), shared by every catalog host. A resident
   * daemon is what holds memory (~1.2 GB a seat), so every way a daemon starts (warm, optimizer
   * resume, publish) must charge it, not only interactive streams. Null leaves residency uncharged
   * (tests).
   */
  private val liveSeats: LiveSeatLimiter? = null,
  /**
   * This catalog's residency cost (desktop 1, Android 2), from the session state's
   * `liveSeatWeight`; a function because the state is built alongside this host.
   */
  private val residencySeatWeight: () -> Int = { 1 },
  /**
   * Whether the bundle a daemon-preview id renders from carries `rc-player-compose`
   * ([ServeRcPlayerIds.carriesCmpAndroidPlayer]). The daemon always registers `cmp-android`, but
   * without the library the render fails with a linkage error. Defaults to false.
   */
  private val cmpAndroidPlayerFor: (daemonId: String) -> Boolean = { false },
  private val clock: () -> Long = System::currentTimeMillis,
) : ServeHost {
  /**
   * The live bundle's A2UI playground ([ServeWeb.a2uiDocumentPreview]) when the catalog doesn't
   * list it (it has no sticker to publish). Exposed under its daemon id as a live-only preview and
   * kept off the landing grid ([playgroundPreviewIds]). No other unlisted preview is exposed.
   */
  private val liveOnlyPlaygrounds: List<ServePreview> =
    ServeWeb.a2uiDocumentPreview(
        live.previews.filter { preview ->
          preview.id !in alias.values && baked.previews.none { it.id == preview.id }
        }
      )
      ?.takeIf { ServeWeb.a2uiDocumentPreview(baked.previews) == null }
      ?.let(::listOf)
      .orEmpty()

  private val alias: Map<String, String> = alias + liveOnlyPlaygrounds.associate { it.id to it.id }

  /**
   * Ids [liveOnlyPlaygrounds] added to [previews]: visible to routes and `/api/previews`, but not
   * to the landing grid.
   */
  val playgroundPreviewIds: Set<String> = liveOnlyPlaygrounds.mapTo(HashSet()) { it.id }

  override fun canDownloadExecutableBundle(previewId: String): Boolean =
    alias[previewId]?.let { daemonId ->
      executableBundleProvider != null && executableBundleAvailable?.invoke(daemonId) == true
    } == true

  override fun executableBundle(previewId: String): ByteArray? =
    alias[previewId]?.let { executableBundleProvider?.invoke(it) }

  /**
   * The browse surface is the baked catalog, but author-declared knobs live on the daemon previews
   * (their `.overrides.json` sidecars), so each mapped preview's knobs are grafted from its daemon
   * twin via [alias]. Unmapped previews keep the baked entry.
   */
  override val previews: List<ServePreview> =
    mergeDeclaredKnobs(baked.previews, live.previews) + liveOnlyPlaygrounds

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    baked.designReferencesFor(previewId)

  // Captures are delivery-branch artifacts, so they come from the baked host; `previews` lists them
  // from there, so the bytes must be forwarded too.
  override fun motionRead(motionId: String, extension: String): BranchFetch =
    baked.motionRead(motionId, extension)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    baked.designReferenceRaster(referenceId)

  // Backdrops ride the baked staging dir, so a live-lane catalog shows the same screens — with its
  // overlay renders coming from the live daemon rather than the baked PNGs.
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

  // Known differences are catalog data, not render output, so they come from the baked staging dir.
  override fun knownDifferences(): ServeKnownDifferences.Document? = baked.knownDifferences()

  override fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    baked.knownDifferenceArtifact(relativePath)

  // The catalog's published player comparison rides the baked staging dir, so it stays reachable
  // when a live daemon fronts this session.
  override fun rcCompare(): RcCompareManifest? = baked.rcCompare()

  override fun rcCompareImage(name: String): ByteArray? = baked.rcCompareImage(name)

  override fun rcComparePending(): Boolean = baked.rcComparePending()

  /**
   * The baked host's live-only (deferred) ids: always routed to the daemon (nothing to replay) and
   * badged in `/api/previews`.
   */
  override val liveOnlyPreviewIds: Set<String> =
    baked.liveOnlyPreviewIds + liveOnlyPlaygrounds.map { it.id }

  // The sticker is the baked host's, so it answers which mode it was drawn in. See
  // [ServeBakedTheme].
  override fun bakedTheme(previewId: String): UiMode? = baked.bakedTheme(previewId)

  /** Delegated to the baked surface for the same reason [bakedTheme] is. */
  override fun bakedRcPlayer(previewId: String): RemoteComposePlayerKind? =
    baked.bakedRcPlayer(previewId)

  // Non-blocking cold start: the no-override SVG lane prefers the daemon's per-variant vector, but
  // a cold Android daemon can take minutes. With [warmInBackground], a not-yet-warm daemon serves
  // the baked vector and warms in the background; [prewarm] closes the window off the request path.
  // Whether the optimizer holds its turn: set once the quiet window is met, cleared when a request
  // arrives, so it isn't re-earned per render.
  private val optimizerHasTurn = AtomicBoolean(false)
  // When the optimizer last checked for activity. Any activity newer than this happened while it
  // was rendering, and must cost it the turn even if the server looks quiet again by now.
  private val optimizerSampledAt = java.util.concurrent.atomic.AtomicLong(0)
  /**
   * When the gate started withholding a turn ([Long.MIN_VALUE] while it isn't), for the ceiling in
   * [grantForcedTurn]; reset whenever a turn is granted.
   */
  private val optimizerGateBlockedSince = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
  /** Set while the pass is running on a turn the ceiling forced rather than the box granting. */
  private val optimizerTurnForced = AtomicBoolean(false)
  /**
   * Next preview position for a new optimizer slice; prevents an evicted prefix monopolising it.
   */
  private val optimizerPreviewCursor = AtomicInteger()
  private val warmDaemonIds = ConcurrentHashMap.newKeySet<String>()
  private val warmingInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Completed renders are retained in [catalogThemeCache] for this generation, since the
   * per-preview pool may evict daemons between selections. The cache survives suspension; a refresh
   * starts a new generation and cache.
   */
  private val themeRendersInFlight = ConcurrentHashMap.newKeySet<String>()
  private val optimizationStarted = AtomicBoolean()
  /**
   * Persisted renders are checked against this renderer once per host — see
   * [verifyPersistedRenders].
   */
  private val persistenceVerified = AtomicBoolean()
  /**
   * True only while the pass holds an optimizer lane; read by [backgroundWorkActive] and
   * [ServeSessionRegistry.suspendIdle] as "stay resident". Scoped to the lane, not the worker's
   * lifetime, because otherwise unfinished optimization alone kept expensive daemons resident and
   * the memory gate then stalled the optimizer itself. Progress lives in [catalogThemeCache] across
   * suspension, and [ServeSessionRegistry.resumeIdleOptimizers] brings the host back when a lane
   * frees.
   */
  private val optimizationActive = AtomicBoolean()
  private val warmExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-warm").apply { isDaemon = true }
    }
  }
  private val foregroundRenderExecutorDelegate = lazy {
    Executors.newCachedThreadPool { r ->
      Thread(r, "serve-catalog-foreground-render").apply { isDaemon = true }
    }
  }
  private val foregroundRenderExecutor by foregroundRenderExecutorDelegate
  private val optimizationExecutorDelegate = lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-theme-optimize").apply { isDaemon = true }
    }
  }
  private val optimizationExecutor by optimizationExecutorDelegate

  /**
   * Workers for one prefetch batch: daemon threads so a shutdown never hangs; lazy so a
   * never-optimizing catalog never creates it.
   */
  private val optimizerBatchExecutorDelegate = lazy {
    Executors.newFixedThreadPool(MAX_OPTIMIZER_BATCH) { r ->
      Thread(r, "serve-catalog-theme-batch").apply { isDaemon = true }
    }
  }
  private val optimizerBatchExecutor by optimizerBatchExecutorDelegate

  /**
   * True when [daemonId] is warm. Otherwise, with [warmInBackground], schedule a one-shot
   * background warm and return false so the caller falls back to baked. Always true with
   * [warmInBackground] off.
   */
  private fun daemonWarmOrScheduling(daemonId: String): Boolean {
    if (!warmInBackground || warmDaemonIds.contains(daemonId)) return true
    // A request finding the id cold also makes the daemon resident, so it charges residency too;
    // [LiveSeatLimiter.acquireBackground] keeps stream headroom free. A refusal returns false, so
    // the caller serves baked pixels instead of spawning a daemon that would exhaust memory.
    if (!chargeResidency()) return false
    if (warmingInFlight.add(daemonId)) {
      warmExecutor.execute {
        try {
          if (renderDaemon(daemonId, PreviewOverrides()) is RenderOutcome.Ok) {
            warmDaemonIds.add(daemonId)
          }
        } catch (_: Throwable) {
          // Best-effort: a failed warm just leaves the id cold; the next request retries.
        } finally {
          warmingInFlight.remove(daemonId)
        }
      }
    }
    return false
  }

  /**
   * Briefly wait for the warm [daemonWarmOrScheduling] just scheduled, so a cold-id override
   * request can render: baked pixels can't satisfy an override and would 503 (see #4149). Bounded
   * by [FOREGROUND_WARM_AWAIT_MILLIS] since the caller holds a render slot; past it the caller
   * still gets Busy.
   */
  private fun awaitForegroundWarm(daemonId: String): Boolean {
    if (!warmInBackground) return false
    val deadline = clock() + FOREGROUND_WARM_AWAIT_MILLIS
    while (clock() < deadline) {
      if (warmDaemonIds.contains(daemonId)) return true
      // The warm finished without succeeding (a genuinely failing preview): stop waiting and let
      // the caller take its normal path rather than burning the whole budget on a lost cause.
      if (!warmingInFlight.contains(daemonId)) return false
      try {
        Thread.sleep(WARM_POLL_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    return warmDaemonIds.contains(daemonId)
  }

  /**
   * The daemon's residency permit, held while this catalog keeps a daemon warm. Taken as background
   * work without the per-preview slice, since warming always has a baked fallback. Released in
   * [close].
   */
  private val residencyTicket = AtomicReference<LiveSeatLimiter.Ticket?>(null)

  /**
   * Charge this catalog's daemon residency, returning false when the box can't afford another
   * resident daemon. Idempotent: one permit per catalog, since the budget models the JVM. A refusal
   * just leaves the catalog cold. Null [liveSeats] always admits.
   */
  private fun chargeResidency(): Boolean {
    val limiter = liveSeats ?: return true
    if (residencyTicket.get() != null) return true
    val ticket =
      limiter.acquireBackground(residencySeatWeight(), dedicatedSlice = false) ?: return false
    // Another thread may have charged between the read and the acquire. Keep one permit and hand
    // the loser's straight back rather than leaking it for the life of the host.
    if (!residencyTicket.compareAndSet(null, ticket)) ticket.close()
    return true
  }

  private fun scheduleWarm(daemonId: String, host: ServeHost = live) {
    if (!warmInBackground || warmDaemonIds.contains(daemonId)) return
    // Charge before the JVM starts; the budget exists to bound resident daemons.
    if (!chargeResidency()) return
    if (warmingInFlight.add(daemonId)) {
      warmExecutor.execute {
        try {
          if (host.render(daemonId, PreviewOverrides()) is RenderOutcome.Ok) {
            warmDaemonIds.add(daemonId)
          }
        } catch (_: Throwable) {
          // Best-effort: a failed warm just leaves the id cold; the next request retries.
        } finally {
          warmingInFlight.remove(daemonId)
        }
      }
    }
  }

  /**
   * Warm the live daemon off the request path so the first browse gets the per-variant SVG lane.
   * Best-effort and async; per-preview catalogs skip eager warming so startup doesn't fan out.
   * No-op without [warmInBackground].
   */
  fun prewarm() {
    startThemeOptimization()
    if (!warmInBackground || !eagerWarmOnOpen) return
    // Per-preview catalogs skip eager warming, except when snapshots share the monolithic daemon:
    // its cold start (~68s on Android) would outlast the page's retries on the first theme
    // selection.
    if (perPreviewResolve != null && !sharedDaemonRenders) return
    alias.values.firstOrNull()?.let { scheduleWarm(it, live) }
  }

  /**
   * A visitor is on this catalog's pages: warm the shared daemon (as [prewarm] does) so their first
   * theme selection is warm. Safe per heartbeat since `scheduleWarm` returns immediately when warm
   * or in flight.
   *
   * Also re-enters the theme-optimization pass, so targets a previous pass couldn't fill (daemon
   * warming, seat budget, contention) are retried rather than abandoned. Self-limiting:
   * `startThemeOptimization` returns when converged or in flight, and [awaitOptimizerTurn] still
   * waits for quiet.
   */
  override fun keepLiveWarm() {
    // Ahead of the `warmInBackground` guard, exactly as in [prewarm]: the two are independent
    // switches, and a box that has disabled background warming still wants its prefetch to finish.
    startThemeOptimization()
    if (!warmInBackground) return
    if (perPreviewResolve != null && !sharedDaemonRenders) return
    alias.values.firstOrNull()?.let { scheduleWarm(it, live) }
  }

  override val label: String = baked.label

  /**
   * App `@ThemeCatalog` themes come from the daemon lane; forwarded so the viewer's theme selector
   * appears and re-renders via the daemon.
   */
  override val declaredThemes: List<ServeTheme> = live.declaredThemes

  override fun themeOptimizationSnapshot(): ThemeOptimizationSnapshot? =
    catalogThemeCache.snapshot().takeIf { it.total > 0 }

  override fun catalogRenderCacheSnapshot(): CatalogRenderCacheSnapshot =
    catalogThemeCache.renderCacheSnapshot()

  override val backgroundWorkActive: Boolean
    get() = optimizationActive.get()

  /**
   * Whether the pass worker is alive, as opposed to [backgroundWorkActive] (holding a lane). Tests
   * waiting for the pass to settle need this one.
   */
  internal val optimizationPassRunning: Boolean
    get() = optimizationStarted.get()

  private data class ThemeOptimizationJob(
    val previewId: String,
    val overrides: PreviewOverrides,
    val cacheKey: String,
  )

  /** Fill every catalog-preview × declared-theme cache entry while the whole server is idle. */
  private fun startThemeOptimization() {
    val catalogIds =
      previews.asSequence().map { it.id }.filter(alias::containsKey).sorted().toList()
    val jobs = catalogIds.flatMap { previewId ->
      declaredThemes.map { theme ->
        val overrides = PreviewOverrides(themeProvider = theme.providerFqn)
        ThemeOptimizationJob(
          previewId = previewId,
          overrides = overrides,
          cacheKey = ServeOverrides.cacheKey(previewId, overrides),
        )
      }
    }
    // The finite declared-theme set is declared to the disk tier FIRST and unconditionally, so that
    // a deployment with the eager pass switched off still persists the renders visitors ask for.
    catalogThemeCache.configurePersistable(jobs.map { it.cacheKey })
    // With the pass off, no targets are configured (so `/status` shows no row), but renders adopted
    // from disk must still be verified.
    if (!themeOptimizationEnabled) {
      verifyAdoptedRendersOnly(jobs)
      return
    }
    catalogThemeCache.configureTargets(jobs.map { it.cacheKey })
    if (jobs.isEmpty()) return
    // Not an early return until persisted renders are verified: a generation adopted whole from
    // disk looks finished on the first heartbeat. `converged`, not `fullyOptimized`, so dirty
    // entries from another build still get re-rendered.
    if (catalogThemeCache.snapshot().converged && persistenceVerified.get()) return
    // Never start a pass into an open render breaker, where every render fails (see #3448). Targets
    // stay configured for `/status`, and heartbeats resume the pass once it closes.
    if (renderBreakerStopsBackgroundWork()) return
    if (!optimizationStarted.compareAndSet(false, true)) return
    optimizationExecutor.execute {
      try {
        // Verification doesn't run before admission: it renders, and startup renders are what the
        // load gate and lane cap hold back.
        // No stagger before the door: the cap admits two and the rest park cheaply, ordered by who
        // has waited longest.
        // One pass slot for the whole slice, so a catalog doesn't pay a cold warm and then lose the
        // slot. The loop provides rotation: a slice returns the lane on a preview boundary and
        // re-queues behind waiting catalogs.
        while (true) {
          // Wait out the gate before taking a lane: waiting inside one lets two passes hold both
          // lanes indefinitely on a box that never goes quiet, starving every other catalog.
          if (!awaitOptimizerTurn()) {
            catalogThemeCache.markPaused()
            if (!awaitOptimizerResume()) return@execute
            continue
          }
          val outcome =
            backgroundWork.withOptimizerSlot(label, optimizerAdmissionWaitMillis) {
              // Residency is tied to the lane (see [optimizationActive]), so the catalog is
              // suspendable as soon as it re-queues.
              optimizationActive.set(true)
              try {
                // Holding the turn, so sample renders are admitted like any background render. Once
                // per host; no-op when nothing was adopted.
                if (!persistenceVerified.get()) verifyPersistedRenders(jobs)
                // `converged`, not `fullyOptimized`: dirty entries are cached too, so the latter
                // would skip the dirty queue after adopting a previous build's generation.
                if (catalogThemeCache.snapshot().converged) PassOutcome.FINISHED
                else runOptimizerPass(jobs, sliceUntil = clock() + optimizerSliceMillis)
              } finally {
                optimizationActive.set(false)
              }
            }
          if (outcome == null) {
            // Park and stay queued rather than exit: a global pause's expiry emits no heartbeat, so
            // exiting would strand the host until someone opened its page.
            catalogThemeCache.markPaused()
            if (!awaitOptimizerResume()) return@execute
            continue
          }
          // A spent slice or a gated pass re-queues (the next stop is the quiet gate, so it can't
          // spin); any other outcome ends the worker for now.
          if (outcome != PassOutcome.SLICE_SPENT && outcome != PassOutcome.GATED) return@execute
        }
      } finally {
        optimizationActive.set(false)
        optimizationStarted.set(false)
      }
    }
  }

  /**
   * Check a few renders adopted from disk against what this daemon produces now, once per host. The
   * fingerprint covers only known inputs; an unknown one (an unreleased base-image bump) changes
   * pixels without changing the name, and stale previews get handed to agents as ground truth.
   * Rendered through [live], not [renderPrefetch], which would return the cached bytes being
   * verified. Only a `DAEMON` render is evidence; a daemon that can't answer yet is no evidence,
   * not a mismatch.
   */
  private fun verifyPersistedRenders(jobs: List<ThemeOptimizationJob>) {
    if (persistenceVerified.get()) return
    val byKey = jobs.associateBy { it.cacheKey }
    val outcome = catalogThemeCache.verifySample { key ->
      val job = byKey[key] ?: return@verifySample null
      val daemonId = alias[job.previewId] ?: return@verifySample null
      val outcome = runCatching { live.render(daemonId, job.overrides) }.getOrNull()
      (outcome as? RenderOutcome.Ok)
        ?.takeIf { it.generation == RenderOutcome.Generation.DAEMON }
        ?.png
    }
    // Latched only once answered; `NO_EVIDENCE` stays unlatched so the next pass asks again.
    if (outcome.settled) persistenceVerified.set(true)
    if (outcome == CatalogThemeCache.VerifyOutcome.MISMATCH) {
      persistenceVerified.set(true)
      System.err.println(
        "serve: catalog $label — persisted theme renders no longer match this renderer; " +
          "dropped the generation and re-warming from scratch"
      )
    }
    // Not latched: the generation couldn't be discarded, so the next pass must retry rather than
    // leave its entries quarantined forever.
    if (outcome == CatalogThemeCache.VerifyOutcome.MISMATCH_UNDISCARDED) {
      System.err.println(
        "serve: catalog $label — persisted theme renders no longer match this renderer, and the " +
          "generation could not be discarded (write lock held); withholding them and retrying"
      )
    }
  }

  /**
   * Verify adopted renders for a catalog whose eager pass is off. Same admission as the pass (lane,
   * then idle gate), on the optimizer executor so the caller never blocks.
   */
  private fun verifyAdoptedRendersOnly(jobs: List<ThemeOptimizationJob>) {
    if (persistenceVerified.get() || jobs.isEmpty()) return
    if (!optimizationStarted.compareAndSet(false, true)) return
    optimizationExecutor.execute {
      try {
        backgroundWork.withOptimizerSlot(label, OPTIMIZER_ADMISSION_WAIT_MILLIS) {
          // Resident for the lane, like the pass proper — this one renders too, and a suspension
          // landing mid-sample would close the daemon under it.
          optimizationActive.set(true)
          try {
            if (awaitOptimizerTurn()) verifyPersistedRenders(jobs)
          } finally {
            optimizationActive.set(false)
          }
          true
        }
      } finally {
        optimizationStarted.set(false)
      }
    }
  }

  /** Why an optimizer pass returned; [SLICE_SPENT] and [GATED] ask for another lane. */
  private enum class PassOutcome {
    /** Every target this pass could see is cached — nothing left to re-queue for. */
    FINISHED,
    /** The render breaker or a shutdown interrupt stopped the pass. */
    STOPPED,
    /**
     * The gate took the turn back and didn't return within [OPTIMIZER_RESUME_WAIT_MILLIS], so the
     * pass gave up its lane. Distinct from [STOPPED]: a gated pass must re-queue, a breakered one
     * must not.
     */
    GATED,
    /** The lane slice ran out with work remaining. */
    SLICE_SPENT,
  }

  /**
   * One lane slice of the pass, ending at [sliceUntil], when work runs out, or when the gate, a
   * pause or the breaker stops it. Doesn't clear `optimizationActive` / `optimizationStarted`;
   * those belong to the looping executor task.
   */
  private fun runOptimizerPass(jobs: List<ThemeOptimizationJob>, sliceUntil: Long): PassOutcome {
    // Render in batches through the replica pool (the same pool a visitor's theme pick uses) rather
    // than serially through the monolithic daemon. One catalog bursts at a time, since the
    // background permit wraps the whole batch. Batched by preview, the unit a daemon warms for, so
    // one warm covers all its themes.
    // `contains`, not `get`: planning needs only presence, and reading would pull every persisted
    // PNG off disk each slice.
    val gaps = jobs.filterNot { catalogThemeCache.contains(it.cacheKey) }
    // Whether this slice works the dirty queue (only a daemon render counts) rather than filling
    // gaps (any tier). The queues never mix: dirty work starts only once gaps are gone.
    val regenerating = gaps.isEmpty()
    val byPreview =
      gaps
        .ifEmpty {
          // Gaps first, then dirty entries: a generation adopted across a release is warm but holds
          // another build's pixels, so it is re-rendered as the lowest-value work.
          val dirty = catalogThemeCache.dirtyTargets().toSet()
          jobs.filter { it.cacheKey in dirty }
        }
        .groupBy { it.previewId }
    if (byPreview.isEmpty()) return PassOutcome.FINISHED
    val allPreviewIds = jobs.map { it.previewId }.distinct()
    val start = Math.floorMod(optimizerPreviewCursor.get(), allPreviewIds.size)
    val previewOrder =
      (allPreviewIds.drop(start) + allPreviewIds.take(start)).filter(byPreview::containsKey)
    var previewsDone = 0
    for (previewId in previewOrder) {
      val previewJobs = byPreview.getValue(previewId)
      // The slice deadline is checked only on preview boundaries, so a just-paid warm (34-68s on
      // Android) is never abandoned. Every slice yields at least one preview, or a very short slice
      // would re-queue forever without rendering.
      if (previewsDone > 0 && clock() >= sliceUntil) {
        catalogThemeCache.markPaused()
        return PassOutcome.SLICE_SPENT
      }
      previewsDone++
      optimizerPreviewCursor.set((allPreviewIds.indexOf(previewId) + 1) % allPreviewIds.size)
      // Re-checked per preview: a breaker can trip mid-pass.
      if (renderBreakerStopsBackgroundWork()) return PassOutcome.STOPPED
      // Gate before the warm too: a cold daemon start is the most expensive thing this pass can do
      // to a busy or loading box.
      if (!awaitOptimizerTurn()) return gateStopOutcome()
      val previewDaemonId = alias[previewId]
      // Await a cold warm once per preview, as a precondition of the batch. With the shared pool
      // the catalog primary stays cold: background renders use reapable replicas so RAM returns
      // between slices. Without the pool, keep the one-time warm.
      if (
        (sharedDaemonPool == null || !sharedDaemonRenders) &&
          previewDaemonId != null &&
          !warmDaemonIds.contains(previewDaemonId)
      ) {
        daemonWarmOrScheduling(previewDaemonId)
        // A cold warm is real render work; counting it keeps the reported rate honest.
        val warmFrom = clock()
        if (warmingInFlight.contains(previewDaemonId) && !awaitWarmCompletion(previewDaemonId)) {
          catalogThemeCache.recordWarm(clock() - warmFrom)
          return PassOutcome.STOPPED
        }
        catalogThemeCache.recordWarm(clock() - warmFrom)
      }
      var index = 0
      while (index < previewJobs.size) {
        // Checked per batch, and a batch is bounded by one render, so a visitor waits at most one
        // render.
        if (!awaitOptimizerTurn()) return gateStopOutcome()
        val batch =
          previewJobs.subList(index, minOf(index + optimizerBatchWidth(), previewJobs.size))
        index += batch.size
        catalogThemeCache.markRunning(clock())
        // Time the render inside the permit; queue time for the permit is its own bucket, distinct
        // from the gate withholding the turn.
        val permitWaitFrom = clock()
        val outcomes =
          backgroundWork.withRenderPermit {
            val renderFrom = clock()
            catalogThemeCache.recordPermitWait(renderFrom - permitWaitFrom)
            // Clear the pool's high-water marks so the reads below belong to THIS batch.
            sharedDaemonPool?.takePeakInFlight()
            sharedDaemonPool?.takeColdStartMillis()
            renderOptimizerBatch(batch, regenerating).also {
              val elapsed = clock() - renderFrom
              // A replica starts on its first render, so attribute overlapping replica cold starts
              // to warm time, not per-entry render time. Capped at this interval, since a
              // foreground borrow may have started one earlier.
              val cold = (sharedDaemonPool?.takeColdStartMillis() ?: 0L).coerceIn(0L, elapsed)
              catalogThemeCache.recordWarm(cold)
              // Width is the peak number of concurrently running daemons, not the job count:
              // without a replica, N jobs may take turns on one daemon.
              catalogThemeCache.recordBatch(
                sharedDaemonPool?.takePeakInFlight() ?: 1,
                elapsed - cold,
              )
            }
          } ?: return PassOutcome.STOPPED
        // Only a fresh daemon render counts as produced; a target filled meanwhile by a foreground
        // request comes back stamped CATALOG_CACHE.
        catalogThemeCache.recordProduced(
          outcomes.count {
            it is RenderOutcome.Ok && it.generation == RenderOutcome.Generation.DAEMON
          }
        )
        for ((job, outcome) in batch.zip(outcomes)) {
          // A success needs no bookkeeping (`put` cleared failure and busy counts). Checked first
          // because the `when` below ends in an `else` that marks failure.
          if (outcome is RenderOutcome.Ok) continue
          // A warm key means a foreground render filled the gap, so nothing to record; except while
          // regenerating, where every dirty key is warm by definition.
          if (!regenerating && catalogThemeCache.get(job.cacheKey) != null) continue
          // Busy is "ask again", not a failure: the warm above may still be settling. Leave it
          // unmarked so a later pass retries instead of spending the `failed` count on it.
          when (outcome) {
            // Busy means ask again (a warm may be settling), so it isn't a failure, but it is
            // counted: after `BUSY_LATCH` consecutive passes the key latches with a reason so
            // `/status` names it. A success clears the count.
            RenderOutcome.Busy -> catalogThemeCache.recordBackgroundBusy(job.cacheKey)
            // Count the failure without latching on the first: a latched key answers foreground
            // requests with 409, so one flaky render would make a thumbnail permanently
            // unavailable. `recordRenderFailure` latches only after a run.
            is RenderOutcome.Failed ->
              catalogThemeCache.recordRenderFailure(job.cacheKey, outcome.reason)
            else -> catalogThemeCache.markFailed(job.cacheKey)
          }
        }
      }
      // A forced turn buys exactly one preview; hand the lane back so the gate still gates.
      if (optimizerTurnForced.compareAndSet(true, false)) {
        optimizerHasTurn.set(false)
        catalogThemeCache.markPaused()
        return PassOutcome.SLICE_SPENT
      }
    }
    catalogThemeCache.markPassFinished(clock())
    return PassOutcome.FINISHED
  }

  /**
   * True when the live lane's breaker is open, so background prefetch stands down. Marks the cache
   * paused so `/status` doesn't read abandoned targets as done.
   */
  private fun renderBreakerStopsBackgroundWork(): Boolean {
    if (renderBreaker()?.open != true) return false
    catalogThemeCache.markPaused()
    return true
  }

  /**
   * Prefetch renders to run at once: the replica pool's capacity, else 1. The pool narrows the
   * batch rather than spawning a JVM the box can't afford.
   */
  private fun optimizerBatchWidth(): Int =
    // Only the shared replica pool makes a per-preview batch parallel: under per-preview routing
    // every theme of one preview hits the same daemon and would come back Busy.
    if (sharedDaemonRenders && sharedDaemonPool != null) {
      sharedDaemonPool.backgroundCapacity().coerceIn(1, MAX_OPTIMIZER_BATCH)
    } else {
      1
    }

  /**
   * Render one batch concurrently via `renderLeased` (the visitor theme-burst entry point, so the
   * same replicas), preserving input order.
   */
  private fun renderOptimizerBatch(
    batch: List<ThemeOptimizationJob>,
    regenerating: Boolean,
  ): List<RenderOutcome> {
    if (batch.size == 1) {
      val job = batch.single()
      return listOf(renderPrefetch(job.previewId, job.overrides, regenerating))
    }
    return batch
      .map { job ->
        optimizerBatchExecutor.submit<RenderOutcome> {
          runCatching { renderPrefetch(job.previewId, job.overrides, regenerating) }
            .getOrElse { RenderOutcome.Failed("prefetch render threw: ${it.message}") }
        }
      }
      .map { future ->
        runCatching { future.get() }
          .getOrElse { RenderOutcome.Failed("prefetch batch: ${it.message}") }
      }
  }

  /**
   * Gate one background render. The pass enters on the full [themeOptimizationIdleMillis] quiet
   * window, then keeps its turn while the server stays quiet and yields as soon as a request
   * arrives ([OPTIMIZER_YIELD_MILLIS]). A visitor never waits behind more than the in-flight
   * render, and an idle box fills the cache at render speed.
   */
  private fun awaitOptimizerTurn(): Boolean {
    // An operator pause applies to the pass in flight too; checked here since every warm and batch
    // passes through.
    if (!awaitOptimizerResume()) {
      optimizerHasTurn.set(false)
      catalogThemeCache.markPaused()
      return false
    }
    if (!optimizerHasTurn.get()) {
      // The wait is charged inside [awaitQuiet], per poll. Unbounded: no lane is held, so parking
      // here costs only a sleeping thread.
      val granted = awaitServerIdle()
      if (!granted) return false
      catalogThemeCache.recordTurnGranted()
      optimizerHasTurn.set(true)
      optimizerSampledAt.set(clock())
      return true
    }
    // A forced turn isn't re-examined against traffic (the box never looks quiet, which is why it
    // was forced); it lasts one preview.
    if (optimizerTurnForced.get()) return true
    // Ask whether anything happened since the last sample, not whether the server is idle right
    // now: a render can outlast [OPTIMIZER_YIELD_MILLIS], so an instantaneous sample misses
    // requests that came and went during it. `now - idle` is when the last activity happened.
    val now = clock()
    val idleMillis = serverIdleMillis()
    val lastActivityAt = idleMillis?.let { now - it }
    val quiet = lastActivityAt != null && lastActivityAt <= optimizerSampledAt.get()
    optimizerSampledAt.set(now)
    if (quiet) return true
    optimizerHasTurn.set(false)
    catalogThemeCache.recordTurnYielded()
    catalogThemeCache.markPaused()
    // Re-enter on the short window: the box has already proved it goes quiet, so only the
    // interrupting visitor needs to finish. Bounded by [OPTIMIZER_RESUME_WAIT_MILLIS] because this
    // waits with a lane in hand; past it the lane is returned and the pass re-parks at the cold
    // gate.
    return awaitQuiet(OPTIMIZER_RESUME_MILLIS, maxWaitMillis = OPTIMIZER_RESUME_WAIT_MILLIS).also {
      if (it) {
        // A resume IS a grant. Counting only cold entries made yields exceed grants after any
        // interrupted pass, which reads as the gate losing turns it never handed out.
        catalogThemeCache.recordTurnGranted()
        optimizerHasTurn.set(true)
        optimizerSampledAt.set(clock())
      }
    }
  }

  /**
   * Why a mid-pass [awaitOptimizerTurn] returned false: a shutdown interrupt
   * ([PassOutcome.STOPPED], ends the worker) or the gate not reopening ([PassOutcome.GATED],
   * re-queue without a lane).
   */
  private fun gateStopOutcome(): PassOutcome =
    if (Thread.currentThread().isInterrupted) PassOutcome.STOPPED else PassOutcome.GATED

  private fun awaitServerIdle(): Boolean = awaitQuiet(themeOptimizationIdleMillis)

  /**
   * Block until the server has been untouched for [quietMillis], polling at a fraction of the
   * window. The gate wait is charged per poll, not on return, so a gate that never opens shows up
   * in `/status` while it is still closed.
   */
  private fun awaitQuiet(quietMillis: Long, maxWaitMillis: Long = Long.MAX_VALUE): Boolean {
    val pollMillis = quietMillis.coerceAtMost(1_000L).coerceAtLeast(50L) / 2
    val startedAt = clock()
    optimizerGateBlockedSince.compareAndSet(Long.MIN_VALUE, startedAt)
    while (true) {
      if (!awaitOptimizerResume()) return false
      val idleMillis = serverIdleMillis()
      if (idleMillis != null && idleMillis >= quietMillis) {
        optimizerGateBlockedSince.set(Long.MIN_VALUE)
        return true
      }
      if (grantForcedTurn()) return true
      if (clock() - startedAt >= maxWaitMillis) return false
      catalogThemeCache.markPaused()
      val polledFrom = clock()
      val slept = pauseOptimization(pollMillis)
      catalogThemeCache.recordGateWait(clock() - polledFrom)
      if (!slept) return false
    }
  }

  /**
   * The ceiling: after [optimizerGateCeilingMillis] of continuous withholding, grant one turn
   * anyway. A gate that can close permanently is indistinguishable from the feature being off, and
   * session-lease clocks have jammed it before (see #4312); `turnsForced` climbing on a
   * visitor-less box signals it again.
   *
   * The grant is one preview, then back to the gate ([optimizerTurnForced]). It never overrides a
   * pause (already handled by [awaitOptimizerResume]) or catalog loading, where a slow daemon start
   * would degrade that catalog to baked PNGs for the process lifetime.
   */
  private fun grantForcedTurn(): Boolean {
    if (optimizerGateCeilingMillis <= 0 || backgroundWork.catalogsLoading) return false
    val blockedSince = optimizerGateBlockedSince.get()
    if (blockedSince == Long.MIN_VALUE || clock() - blockedSince < optimizerGateCeilingMillis) {
      return false
    }
    optimizerGateBlockedSince.set(Long.MIN_VALUE)
    optimizerTurnForced.set(true)
    catalogThemeCache.recordTurnForced()
    return true
  }

  /** Park an unfinished optimizer through a timed/manual pause, stopping only for shutdown. */
  private fun awaitOptimizerResume(): Boolean {
    while (backgroundWork.optimizersPaused()) {
      catalogThemeCache.markPaused()
      if (!pauseOptimization(OPTIMIZER_PAUSE_POLL_MILLIS)) return false
    }
    return !Thread.currentThread().isInterrupted
  }

  private fun awaitWarmCompletion(daemonId: String): Boolean {
    while (warmingInFlight.contains(daemonId)) {
      if (Thread.currentThread().isInterrupted || !pauseOptimization(1_000)) return false
    }
    return true
  }

  private fun pauseOptimization(millis: Long): Boolean =
    try {
      Thread.sleep(millis)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant and shows published pixels; the
   * daemon is opt-in via [hasLiveStream].
   */
  override val canApplyOverrides: Boolean = false

  /**
   * The carried daemon can re-render on demand, so override-bearing `/render` and `/render.svg`
   * return fresh pixels while ordinary browsing still never wakes it.
   */
  override val canRenderOverrides: Boolean = true

  /**
   * Only a preview with a daemon twin ([alias]) can apply overrides; unaliased variants replay
   * baked pixels, so the viewer must not offer controls (like the theme selector) that change
   * nothing.
   */
  override fun canRenderOverridesFor(previewId: String): Boolean = previewId in alias

  /** A leased burst is safe only when requests can borrow independent daemon processes. */
  /**
   * Shared mode borrows identical monolithic replicas (one warm classpath per process) so a leased
   * batch renders five cards at once; per-preview mode is independently parallel. Without either
   * pool, renders are serial.
   */
  override val themeRenderBurstCapacity: Int =
    when {
      sharedDaemonRenders -> sharedDaemonPool?.capacity ?: 1
      perPreviewResolve != null -> ThemeRenderLeaseManager.MAX_CONCURRENCY
      else -> 1
    }

  /** The gesture override is honoured by the daemon lane, if that daemon is Android-backed. */
  // The capability flags below are lazy because reading them opens the daemon session, which
  // [ServeRenderHost] defers to first use.
  /**
   * Whether this catalog carries any daemon: the monolithic one or a pooled per-preview one
   * (streams and exports can start the latter alone). Otherwise `/status` would hide running
   * processes.
   */
  /**
   * The primary shared daemon, its replicas and the per-preview pool's residents, delegated to
   * [live.daemonProcessCount] so a pooled-only catalog doesn't report a phantom monolith.
   */
  override val daemonProcessCount: Int
    get() =
      live.daemonProcessCount +
        (sharedDaemonPool?.replicaProcessCount() ?: 0) +
        perPreviewPoolStats().sumOf { it.open }

  override val daemonStarted: Boolean
    get() =
      live.daemonStarted ||
        (sharedDaemonPool?.replicaProcessCount() ?: 0) > 0 ||
        perPreviewStreamCount() > 0 ||
        perPreviewPoolStats().any { it.open > 0 }

  override val gesturesRenderable: Boolean by lazy { live.gesturesRenderable }

  /**
   * SVG is exportable when either lane can produce it (baked `figma/<slug>.svg` or the daemon's
   * `compose/figma-svg`).
   */
  override val hasSvgExport: Boolean by lazy { baked.hasSvgExport || live.hasSvgExport }

  override val hasScrollExport: Boolean by lazy { live.hasScrollExport }

  /**
   * Accessibility inspection is an explicit live render; forwarded so the control appears on
   * catalog pages.
   */
  override val hasA11yOverlay: Boolean by lazy { live.hasA11yOverlay }

  override fun hasA11yOverlayFor(previewId: String): Boolean =
    previewId in alias && live.hasA11yOverlayFor(alias.getValue(previewId))

  /**
   * From the live lane, not [canApplyOverrides] (which the default reads and which is false here
   * because browsing is baked).
   */
  override val hasDesignAnnotations: Boolean by lazy { live.hasDesignAnnotations }

  override fun hasDesignAnnotationsFor(previewId: String): Boolean =
    previewId in alias && live.hasDesignAnnotations

  /** The baked half: a catalog that published typography can inspect an unmapped variant too. */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    baked.hasPublishedTypographyFor(previewId)

  override fun hasScrollExportFor(previewId: String): Boolean =
    previewId in alias && live.hasScrollExportFor(alias.getValue(previewId))

  /**
   * Per-preview SVG availability: a daemon-twinned id when the daemon can export SVG, otherwise
   * only if the baked catalog has its slug's vector. Mirrors [renderSvg]'s routing.
   */
  override fun hasSvgExportFor(previewId: String): Boolean =
    (previewId in alias && live.hasSvgExport) || baked.hasSvgExportFor(previewId)

  /**
   * [hasSvgExportFor] for the landing page, which must never start a daemon: [live.hasSvgExport]
   * boots one to answer, which made a plain grid GET wait on a ~1 GB Android daemon. The live lane
   * is consulted only once its daemon is already up.
   */
  fun hasSvgExportWithoutWaking(previewId: String): Boolean =
    baked.hasSvgExportFor(previewId) || (live.daemonStarted && hasSvgExportFor(previewId))

  /**
   * The live stream toggle is offered until this catalog's render breaker opens; then `/status`
   * stops advertising `live` (see #3448). Read off [renderBreaker] so non-daemon stand-ins behave
   * as before.
   */
  override val hasLiveStream: Boolean
    get() = renderBreaker()?.open != true

  /**
   * The live lane's open breaker, from the monolithic daemon only: a broken classpath breaks pooled
   * residents identically, and reading the pool would wake hosts.
   */
  override fun renderBreaker(): RenderBreakerSnapshot? = live.renderBreaker()

  /**
   * The baked catalog's degradations plus the live lane's broken-render degradation when its
   * breaker is open.
   */
  override val degradations: List<ServeDegradation>
    get() = baked.degradations + live.degradations

  /**
   * The underlying baked host, so the HTTP layer can read its title, subtitle and trust verdict
   * (see `ServeHttpServer.catalogBundleHost`).
   */
  internal val bakedHost: ServeHost = baked

  /**
   * Ordinary browsing serves the baked PNG and never wakes the daemon. Any override that would
   * change those pixels (a knob, font scale, device, locale, …; see [overridesAffectRender]) is
   * routed to the [live] daemon. Only daemon-twinned ids can re-render.
   */
  /**
   * Answerable without admission only when the request wouldn't reach a daemon, using the same
   * [daemonIdForOverrideRender] predicate as `render`, so the fast path never serves baked pixels
   * for something that should re-render.
   */
  // Straight through to the published host: warming is about the delivery branch's bytes, and the
  // live lane has nothing to contribute to that question.
  override fun warmBakedRender(previewId: String) = baked.warmBakedRender(previewId)

  override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? {
    cachedRender(previewId, overrides)?.let {
      return it
    }
    if (daemonIdForOverrideRender(previewId, overrides) != null) return null
    return baked.bakedRender(previewId, overrides)
  }

  // Unconditional, unlike [bakedRender] above: this measures the *published* pixels, and an unfurl
  // card always points at the override-free render regardless of what the live lane could produce.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? =
    baked.bakedRenderSize(previewId)

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = false)

  /**
   * A broken live lane latches every render that would have reached it, checked before taking a
   * render slot so the 409 names the real error.
   *
   * Scoped by [daemonIdForOverrideRender], not membership in [alias]: unmapped variants and
   * override-free browses replay baked pixels and must keep working. Latching those turned one
   * broken daemon into a catalog-wide blackout of lazily-fetched images (see
   * compose-ai-tools#4220). Requests that genuinely need the daemon still get the 409.
   */
  override fun renderFailureLatch(previewId: String, overrides: PreviewOverrides): String? =
    (if (daemonIdForOverrideRender(previewId, overrides) != null)
      live.renderBreaker()?.takeIf { it.open }?.reason
    else null) ?: themeCacheKey(previewId, overrides)?.let(catalogThemeCache::failureReason)

  /**
   * Shed pooled daemons (burst replicas, per-preview residents) while keeping the monolithic [live]
   * daemon, this catalog's warm browse lane. Both pools reopen on demand.
   */
  override fun releaseIdleDaemons(idleMillis: Long): Int =
    (sharedDaemonPool?.reapIdle(idleMillis) ?: 0) + perPreviewReapIdle(idleMillis)

  override fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    renderInternal(previewId, overrides, leased = true)

  /**
   * A leased render from the idle theme optimizer: same routing as [renderLeased], but replicas it
   * opens are charged to the background seat remainder so prefetching never holds the stream
   * reserve ([ServeSharedDaemonPool.render]).
   */
  private fun renderPrefetch(
    previewId: String,
    overrides: PreviewOverrides,
    regenerating: Boolean = false,
  ): RenderOutcome =
    renderInternal(
      previewId,
      overrides,
      leased = true,
      background = true,
      bypassCache = regenerating,
    )

  private fun renderInternal(
    previewId: String,
    overrides: PreviewOverrides,
    leased: Boolean,
    background: Boolean = false,
    /**
     * Skip the cache read and go to the daemon. Set only by the dirty queue: dirty entries are
     * still servable, so an ordinary read would return them and regeneration would never render
     * anything.
     */
    bypassCache: Boolean = false,
  ): RenderOutcome {
    val catalogCacheKey = catalogCacheKey(previewId, overrides)
    val themeCacheKey = themeCacheKey(previewId, overrides)
    if (!bypassCache) {
      cachedRender(previewId, overrides)?.let {
        return it
      }
    }
    // A render already latched as failing is answered from the latch, keeping broken cards from
    // occupying the render lock.
    if (themeCacheKey != null) {
      catalogThemeCache.failureReason(themeCacheKey)?.let {
        return RenderOutcome.Failed(it)
      }
    }
    if (themeCacheKey != null && !themeRendersInFlight.add(themeCacheKey)) {
      return RenderOutcome.Busy
    }
    try {
      val daemonId =
        daemonIdForOverrideRender(previewId, overrides) ?: return baked.render(previewId, overrides)
      // A live-only preview has no baked fallback: await the daemon even cold and return its
      // outcome as-is.
      if (previewId in liveOnlyPreviewIds) {
        return cacheCatalogRender(
          catalogCacheKey,
          renderDaemon(daemonId, overrides, leased, background),
        )
      }
      // Await the daemon only when warm: blocking a browse (and its render slot) on a cold Android
      // start can saturate the server. Leased batches may cold-start replicas on the request path,
      // otherwise the pool never grows.
      // Foreground overrides can't fall back to baked pixels (the HTTP layer refuses them), so a
      // cold id waits, bounded, for the warm it just scheduled (see #4149).
      var liveNotFound = false
      // Residency is charged on every route that can start this daemon, including leased renders
      // that skip `daemonWarmOrScheduling`. Asymmetric refusal: background work declines and
      // returns Busy (which the optimizer reads as "ask again"); a visitor's leased render proceeds
      // anyway, since it is not deferrable.
      val residency = chargeResidency()
      val mayOpenDaemon = residency || !background
      if (
        mayOpenDaemon &&
          (leased || daemonWarmOrScheduling(daemonId) || awaitForegroundWarm(daemonId))
      ) {
        val live = renderForegroundBounded(daemonId, overrides, leased, background)
        liveNotFound = live is RenderOutcome.NotFound
        // Count a real failure against this theme key ([CatalogThemeCache.recordRenderFailure]);
        // Busy and NotFound are not render failures and must not latch.
        if (themeCacheKey != null && live is RenderOutcome.Failed) {
          catalogThemeCache.recordRenderFailure(themeCacheKey, live.reason)
        }
        // NotFound falls through like Busy: no daemon carries this id, but the baked PNG is still
        // better than a broken image.
        if (live !is RenderOutcome.Busy && live !is RenderOutcome.NotFound)
          return cacheCatalogRender(catalogCacheKey, live)
      }
      if (themeCacheKey != null) return RenderOutcome.Busy
      // A routed override can't be satisfied by baked pixels, so a missed cold warm is a retryable
      // Busy, never a misleading baked response.
      if (overrides != PreviewOverrides() && !liveNotFound) return RenderOutcome.Busy
      return baked.render(previewId, overrides)
    } finally {
      if (themeCacheKey != null) themeRendersInFlight.remove(themeCacheKey)
    }
  }

  private fun renderForegroundBounded(
    daemonId: String,
    overrides: PreviewOverrides,
    leased: Boolean,
    background: Boolean,
  ): RenderOutcome {
    if (!warmInBackground || leased || background || foregroundOverrideTimeoutMillis <= 0)
      return renderDaemon(daemonId, overrides, leased, background)
    val task =
      foregroundRenderExecutor.submit<RenderOutcome> {
        renderDaemon(daemonId, overrides, leased, background)
      }
    return try {
      task.get(foregroundOverrideTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      task.cancel(true)
      RenderOutcome.Busy
    } catch (_: InterruptedException) {
      task.cancel(true)
      Thread.currentThread().interrupt()
      RenderOutcome.Busy
    } catch (e: ExecutionException) {
      task.cancel(true)
      RenderOutcome.Failed(e.cause?.message ?: "foreground render failed")
    }
  }

  override fun cachedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
    catalogCacheKey(previewId, overrides)?.let(catalogThemeCache::get)?.let { bytes ->
      RenderOutcome.Ok(bytes, RenderOutcome.Generation.CATALOG_CACHE)
    }

  /** Cache every successful live catalog render, never a baked fallback or a failure. */
  private fun cacheCatalogRender(key: String?, outcome: RenderOutcome): RenderOutcome {
    if (
      key != null &&
        outcome is RenderOutcome.Ok &&
        outcome.generation != RenderOutcome.Generation.BAKED
    ) {
      catalogThemeCache.put(key, outcome.png)
    }
    return outcome
  }

  /**
   * Cache key for requests that actually route to the daemon (baked browsing needs none). The
   * enclosing [ServeSessionState] owns the map: it survives idle suspension and is replaced
   * atomically on refresh.
   */
  private fun catalogCacheKey(previewId: String, overrides: PreviewOverrides): String? {
    if (daemonIdForOverrideRender(previewId, overrides) == null) return null
    return ServeOverrides.cacheKey(previewId, overrides)
  }

  private fun themeCacheKey(previewId: String, overrides: PreviewOverrides): String? {
    val provider = overrides.themeProvider ?: return null
    if (previewId !in alias || declaredThemes.none { it.providerFqn == provider }) return null
    if (overrides != PreviewOverrides(themeProvider = provider)) return null
    return ServeOverrides.cacheKey(previewId, overrides)
  }

  /**
   * The `.rc` document is a baked-bundle sidecar, so delegate to [baked]; the in-browser player
   * applies knob edits client-side.
   */
  override fun remoteComposeDoc(previewId: String): ByteArray? = baked.remoteComposeDoc(previewId)

  /** The cmp-jvm render spec (baked size + density) comes from the baked bundle, like the doc. */
  override fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? =
    baked.remoteComposeRenderSpec(previewId)

  /** The daemon lane honours the RC player override when the carried daemon is Android-backed. */
  override val remoteComposePlayerSelectable: Boolean by lazy { live.remoteComposePlayerSelectable }

  /**
   * The RC player selector unions both lanes: [RcPlayerBackend.CAMAELON_JS] when the baked bundle
   * carries the `.rc`; the AndroidX view/embedded players when there is a daemon twin
   * ([canRenderOverridesFor]) on a backend honouring the override
   * ([remoteComposePlayerSelectable]); [RcPlayerBackend.CMP_ANDROID] additionally needs the player
   * classes ([cmpAndroidPlayerFor]); [RcPlayerBackend.CMP_JVM] when the desktop player is installed
   * and the bundle can size a render ([supportsCmpJvm]). No `.rc` doc means no selector.
   */
  override fun enabledRcPlayersFor(previewId: String): List<RcPlayerBackend> {
    if (!hasRemoteComposeDoc(previewId)) return emptyList()
    return buildList {
      add(RcPlayerBackend.CAMAELON_JS)
      if (canRenderOverridesFor(previewId) && remoteComposePlayerSelectable) {
        add(RcPlayerBackend.ANDROIDX_VIEW)
        add(RcPlayerBackend.ANDROIDX_EMBEDDED)
        if (alias[previewId]?.let(cmpAndroidPlayerFor) == true) add(RcPlayerBackend.CMP_ANDROID)
      }
      if (supportsCmpJvm(previewId)) add(RcPlayerBackend.CMP_JVM)
      // Players the parity run staged are offerable without a daemon, which keeps the embedded
      // default available when the daemon is down.
      addAll(stagedRcPlayers(previewId).filterNot { it in this })
      // …and the lane the baked artifact already is, which neither the daemon's selectable pair
      // nor the staged columns necessarily cover. See [ServeHost.bakedRcPlayerBackend].
      bakedRcPlayerBackend(previewId)?.let { if (it !in this) add(it) }
    }
      .sortedBy { RcPlayerBackend.UNIVERSE.indexOf(it) }
  }

  /**
   * The daemon host for [daemonId]: the per-preview daemon if [perPreviewResolve] yields one, else
   * [live]. Both take the daemon id.
   */
  private fun liveHostFor(daemonId: String): ServeHost = perPreviewResolve?.invoke(daemonId) ?: live

  /**
   * Which daemon answers a snapshot render (grid cards, themed thumbnails): the shared monolithic
   * daemon by default, because a grid is a batch that amortises one cold start (68s cold vs 356ms
   * warm on Android) and the pool's LRU cap would evict mid-page. Streams keep the per-preview
   * lane, where isolation is the point. Ids the shared daemon doesn't know fall back to the pool.
   */
  /**
   * Render on the shared daemon, falling back to the per-preview daemon only on
   * [RenderOutcome.NotFound] (an id the monolithic descriptor never listed). Busy or Failed is the
   * real answer.
   */
  private fun renderDaemon(
    daemonId: String,
    overrides: PreviewOverrides,
    leased: Boolean = false,
    background: Boolean = false,
  ): RenderOutcome {
    val outcome =
      if (sharedDaemonRenders && leased && sharedDaemonPool != null) {
        sharedDaemonPool.render(daemonId, overrides, background = background)
      } else {
        renderHostFor(daemonId).render(daemonId, overrides)
      }
    if (outcome != RenderOutcome.NotFound || !sharedDaemonRenders) return outcome
    val perPreview = perPreviewResolve?.invoke(daemonId) ?: return outcome
    return perPreview.render(daemonId, overrides)
  }

  private fun renderHostFor(daemonId: String): ServeHost =
    if (sharedDaemonRenders) live else liveHostFor(daemonId)

  /**
   * SVG export mirrors [render]'s routing, plus a fallback to the daemon for a mapped id whose
   * baked vector is missing, so an advertised link doesn't 404. An explicit export may wake the
   * daemon.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    daemonIdForOverrideRender(previewId, overrides)?.let {
      return liveHostFor(it).renderSvg(it, overrides)
    }
    // No override: prefer the warm daemon's per-variant SVG, which is more faithful than an older
    // catalog's light-only `figma/<slug>.svg`.
    alias[previewId]?.let { daemonId ->
      // Only when warm; otherwise serve the baked vector and warm in the background. A failing warm
      // daemon also falls through to baked.
      if (daemonWarmOrScheduling(daemonId)) {
        val live = liveHostFor(daemonId).renderSvg(daemonId, overrides)
        if (live is SvgOutcome.Ok) return live
      }
    }
    return baked.renderSvg(previewId, overrides)
  }

  /**
   * Web mode prefers the baked lane, whose crops have a public branch home to link
   * (`ServeBundleHost.renderSvgForWeb`). Override renders stay live; unservable previews fall back
   * to [renderSvg].
   */
  override fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    daemonIdForOverrideRender(previewId, overrides)?.let {
      return liveHostFor(it).renderSvg(it, overrides)
    }
    val linked = baked.renderSvgForWeb(previewId, overrides)
    if (linked is SvgOutcome.Ok) return linked
    return renderSvg(previewId, overrides)
  }

  /** Full-page raster capture is daemon-produced; route every mapped catalog preview live. */
  override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = alias[previewId] ?: return RenderOutcome.NotFound
    return liveHostFor(daemonId).renderScrollPng(daemonId, overrides)
  }

  /** Full-page vector capture follows the same explicit live route as its raster counterpart. */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val daemonId = alias[previewId] ?: return SvgOutcome.NotFound
    return liveHostFor(daemonId).renderScrollSvg(daemonId, overrides)
  }

  /**
   * Accessibility data from the live daemon, with the viewer's overrides retained so the inspected
   * composition matches what was rendered.
   */
  override fun renderA11y(previewId: String, overrides: PreviewOverrides): A11yOutcome {
    val daemonId = alias[previewId] ?: return A11yOutcome.NotFound
    return liveHostFor(daemonId).renderA11y(daemonId, overrides)
  }

  /**
   * Inspect layers, routed by which layers the caller named. The daemon produces all three
   * (typography, theme, layout); a published bundle only typography ([AnnotationKind.PUBLISHABLE]).
   * An unscoped request means every layer and goes live; a typography-only request is served from
   * published data, avoiding a 16-22s daemon cold start on a suspended catalog. Layout/theme
   * requests, render-moving overrides, or missing published typography still reach the daemon.
   */
  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ): AnnotationsOutcome {
    if (AnnotationKind.publishedLayersSuffice(layers)) {
      val published = bakedAnnotations(previewId, overrides, layers)
      if (published !is AnnotationsOutcome.NotFound) return published
    }
    val daemonId = alias[previewId] ?: return bakedAnnotations(previewId, overrides, layers)
    val live = liveHostFor(daemonId).renderAnnotations(daemonId, overrides, layers)
    // A daemon without semantics answers NotFound; fall back to published typography. A `Failed` is
    // a real error and passes through.
    return if (live is AnnotationsOutcome.NotFound) bakedAnnotations(previewId, overrides, layers)
    else live
  }

  /**
   * The catalog's published annotations, only where they describe the pixels on screen: they were
   * measured over the baked frame, so they hold exactly when
   * [CatalogLiveRouting.overridesAffectRender] says the baked frame is served.
   */
  private fun bakedAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ): AnnotationsOutcome =
    if (
      CatalogLiveRouting.overridesAffectRender(
        previewId,
        overrides,
        bakedTheme(previewId),
        bakedRcPlayer(previewId),
      )
    )
      AnnotationsOutcome.NotFound
    else baked.renderAnnotations(previewId, overrides, layers)

  /**
   * Daemon preview id to route a render to, or null to stay baked. Delegates to
   * [CatalogLiveRouting], shared with [ServePerPreviewLiveHost].
   */
  private fun daemonIdForOverrideRender(previewId: String, overrides: PreviewOverrides): String? =
    CatalogLiveRouting.daemonIdForRender(
      previewId,
      overrides,
      alias,
      liveOnlyPreviewIds,
      bakedTheme(previewId),
      bakedRcPlayer(previewId),
    )

  /** Live streaming is available only for aliased ids; others have no stream (snapshot only). */
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
          // An unmapped (Android-only) variant has no daemon twin, so no live lane — report it so
          // the viewer explains the snapshot fallback rather than a bare "input requires a stream".
          onUnavailable?.invoke("no live daemon twin for '$previewId' (baked snapshot only)")
          return null
        }
    return liveHostFor(daemonId)
      .subscribeStream(
        daemonId,
        overrides,
        codec,
        maxFps,
        onUnavailable = onUnavailable,
        onFrame = onFrame,
      )
  }

  override fun activeStreamCount(): Int = live.activeStreamCount() + perPreviewStreamCount()

  /**
   * Live-lane render stats: the monolithic daemon's counters plus the pooled daemons'
   * ([perPreviewRenderStats]), since the pool does most real renders.
   */
  override fun renderPerfStats(): RenderPerfSnapshot? {
    val pool = perPreviewRenderStats() + (sharedDaemonPool?.renderPerfStats() ?: emptyList())
    val monolithic = live.renderPerfStats()
    // Aggregating a single snapshot would null its percentiles (windows don't merge), so keep the
    // monolithic view verbatim until the pool actually has daemons to fold in.
    if (pool.isEmpty()) return monolithic
    return RenderPerfSnapshot.aggregate(listOfNotNull(monolithic) + pool)
  }

  override fun daemonPoolStats(): List<DaemonPoolSnapshot> =
    listOfNotNull(sharedDaemonPool?.takeIf { sharedDaemonRenders }?.snapshot()) +
      perPreviewPoolStats()

  /**
   * Graft daemon previews' per-preview metadata (knobs, `uiMode`, focus/gesture flags) onto the
   * baked browse surface, keyed through [alias], so `/api/previews` advertises editable controls
   * while keeping the baked Day/Night default. Unmapped previews are unchanged.
   */
  private fun mergeDeclaredKnobs(
    bakedPreviews: List<ServePreview>,
    livePreviews: List<ServePreview>,
  ): List<ServePreview> {
    val twinByDaemonId = livePreviews.associateBy { it.id }
    return bakedPreviews.map { p ->
      val twin = alias[p.id]?.let { twinByDaemonId[it] } ?: return@map p
      p.copy(
        overrides = twin.overrides,
        remoteComposeKnobs = twin.remoteComposeKnobs,
        supportsFocus = twin.supportsFocus,
        supportsGestures = twin.supportsGestures,
        // OR rather than overwrite, so an older daemon bundle without `fixedTheme` can't clear what
        // the baked catalog knows.
        fixedTheme = p.fixedTheme || twin.fixedTheme,
        uiMode = twin.uiMode,
        // Backdrop values too: a published catalog stages no root `previews.json`, so the daemon
        // twin is the only source on this path.
        showBackground = twin.showBackground,
        backgroundColor = twin.backgroundColor,
        // The device frame likewise, preferring the twin and falling back to what the baked card
        // had.
        deviceFrame = twin.deviceFrame ?: p.deviceFrame,
      )
    }
  }

  override fun close() {
    themeRendersInFlight.clear()
    if (optimizationExecutorDelegate.isInitialized()) {
      try {
        optimizationExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread optimization pool
      }
    }
    if (optimizerBatchExecutorDelegate.isInitialized()) {
      try {
        optimizerBatchExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread prefetch batch pool
      }
    }
    if (warmInBackground) {
      try {
        warmExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread warm pool
      }
    }
    if (foregroundRenderExecutorDelegate.isInitialized()) {
      try {
        foregroundRenderExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of bounded foreground render workers
      }
    }
    try {
      sharedDaemonPool?.close()
      live.close()
    } finally {
      // Release the residency permit even if `baked.close()` throws; a leaked permit shrinks the
      // budget for the process lifetime.
      residencyTicket.getAndSet(null)?.close()
      baked.close()
    }
  }

  companion object {
    /**
     * How long a pure-theme request waits for its scheduled warm: between a warm render
     * (sub-second) and a cold Android start (34-68s), so an almost-done warm is caught without
     * holding a render slot for a minute.
     */
    internal const val FOREGROUND_WARM_AWAIT_MILLIS = 15_000L

    /**
     * How recently a request must have touched the server for the optimizer to give up its turn;
     * short, so it steps aside within one render.
     */
    /**
     * How long a parked catalog waits at the admission door before giving up this pass. Shorter
     * than the idle window so waiting doesn't become missing the turn; `keepLiveWarm` re-enters on
     * the next heartbeat.
     */
    internal const val OPTIMIZER_ADMISSION_WAIT_MILLIS = 20_000L

    /**
     * Whole-server quiet required before a cold pass starts. Owned by [ServeBackgroundWork], which
     * publishes it on `/status.json`; aliased here for the constructor default and tests.
     */
    internal fun themeOptimizationIdleMillisDefault(): Long =
      ServeBackgroundWork.themeOptimizationIdleMillisDefault()

    /** Default lane slice — see the `optimizerSliceMillis` constructor parameter for the trade. */
    internal const val DEFAULT_OPTIMIZER_SLICE_MILLIS = 5 * 60_000L

    internal const val OPTIMIZER_YIELD_MILLIS = 1_500L

    private const val OPTIMIZER_PAUSE_POLL_MILLIS = 100L

    /**
     * Quiet window to resume after yielding; short, but it must exceed [OPTIMIZER_YIELD_MILLIS] or
     * a resume could re-detect the activity it just waited out.
     */
    internal const val OPTIMIZER_RESUME_MILLIS = 2_000L

    /**
     * How long a yielded pass waits, holding its lane, for quiet before giving the lane back. Long
     * enough to ride out an ordinary browse without re-warming; bounded so a busy box rotates lanes
     * instead of freezing them.
     */
    internal const val OPTIMIZER_RESUME_WAIT_MILLIS = 30_000L

    /**
     * Default ceiling on how long the gate may withhold a turn ([grantForcedTurn]). The cost is
     * bounded by the grant (one preview), not by this number.
     */
    internal fun optimizerGateCeilingMillisDefault(): Long =
      System.getProperty("composeai.serve.themeOptimizationGateCeilingMillis")?.toLongOrNull()
        ?: (10 * 60_000L)

    /**
     * Ceiling on prefetch batch width, matching the replica pool's capacity; the seat budget
     * narrows it further.
     */
    internal const val MAX_OPTIMIZER_BATCH = ServeSharedDaemonPool.DEFAULT_CAPACITY

    private const val WARM_POLL_MILLIS = 50L
  }
}
