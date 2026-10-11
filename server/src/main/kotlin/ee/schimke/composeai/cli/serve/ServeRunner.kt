package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import ee.schimke.composeai.uibuilder.export.CatalogComposeSourceExportAdapters
import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CatalogCutoverShadow
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.PackagedUiBuilderRenderBundle
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.ProductionUiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderDesignStateStore
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath

/**
 * The delivery-system id a Builder catalog's published policy and component record load from.
 * Usually the catalog itself; Wear is not: designs name `wear-m3` while the `wear-m3-catalog`
 * branch owns the policy and Android bundle.
 */
internal fun uiBuilderPublishedSourceSystem(
  builderSystem: String,
  nativeCatalogs: Map<String, String>,
): String = nativeCatalogs[builderSystem] ?: builderSystem

/**
 * The component record a Builder catalog composes and exports against: its delivery system's live
 * record ([served], keyed by delivery system), else the one fetched at startup ([startup], keyed by
 * Builder id). Never keyed by Builder id against [served]: a separate served catalog named
 * `wear-m3` (a small harness) would otherwise hand the owned Builder catalog the wrong record.
 */
internal fun uiBuilderCatalogRecord(
  builderSystem: String,
  nativeCatalogs: Map<String, String>,
  served: (deliverySystem: String) -> File?,
  startup: (builderSystem: String) -> File?,
): File? =
  served(uiBuilderPublishedSourceSystem(builderSystem, nativeCatalogs)) ?: startup(builderSystem)

/**
 * `compose-preview serve`, from the first port bind to the last shutdown hook. Configuration comes
 * through [ServeOptions] and builds through [ServeBuildHost]; it never sees argv, help text or a
 * Gradle type, so the server is startable without the CLI.
 */
public class ServeRunner(
  private val options: ServeOptions,
  private val build: ServeBuildHost,
  /**
   * Called once with the discovery this run serves, before anything is hosted. Exists for the `ui`
   * command, whose `--ui-builder-components` path is only known after the build. Not a general
   * event hook: it fires once on the discovery path, and a throw fails the run.
   */
  private val onDiscovered: (ServeDiscovery) -> Unit = {},
) : ServeOptions by options, ServeBuildHost by build {

  private val catalogBlobPool: CatalogBlobPool by lazy {
    val requested = catalogCacheDirFlag
    val maxBytes = catalogCacheMaxBytesFlag ?: CatalogBlobPool.DEFAULT_MAX_BYTES
    val preferred = requested?.takeIf { it != "none" }?.let(::File)
    if (
      preferred != null && (preferred.isDirectory || preferred.mkdirs()) && preferred.canWrite()
    ) {
      System.err.println(
        "serve: catalog blob cache at $preferred (cap ${maxBytes / (1024 * 1024)} MB) — " +
          "it survives only if that path outlives the process; in a container that means a " +
          "mounted volume, since the writable layer goes with the container"
      )
      CatalogBlobPool(preferred, maxBytes = maxBytes, persistenceConfigured = true)
    } else {
      if (preferred != null) {
        System.err.println("serve: catalog blob cache $preferred is not writable; using a temp dir")
      }
      val temp =
        java.nio.file.Files.createTempDirectory("serve-catalog-blobs").toFile().also {
          it.deleteOnExit()
        }
      System.err.println(
        "serve: catalog blob cache is a temp dir — it will not survive a restart. " +
          "Set --catalog-cache-dir (SERVE_CATALOG_CACHE_DIR) to a mounted volume to keep it."
      )
      // Flagged as not configured so `/status.json` can tell a temp pool from a working durable
      // cache.
      CatalogBlobPool(temp, maxBytes = maxBytes, persistenceConfigured = false)
    }
  }

  private val themeCacheStore: ThemeCacheStore? by lazy {
    val requested = themeCacheDirFlag
    // `none` disables persistence. A sentinel is needed because unset derives a default beside
    // `--catalogs-file`, which on the prebuilt image is the config volume.
    if (requested == "none") return@lazy null
    val explicit = requested?.let(::File)
    val preferred =
      explicit
        ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("theme-cache")
        ?: return@lazy null
    if (!(preferred.isDirectory || preferred.mkdirs()) || !preferred.canWrite()) {
      System.err.println("serve: theme cache disabled — $preferred is not writable")
      return@lazy null
    }
    val maxBytes = themeCacheMaxBytesFlag ?: ThemeCacheStore.DEFAULT_MAX_BYTES
    System.err.println("serve: theme cache at $preferred (cap ${maxBytes / (1024 * 1024)} MB)")
    ThemeCacheStore(preferred, maxBytes = maxBytes).also { store ->
      // Before any generation opens, so eviction never races a live write. For when the operator
      // already knows the renderer changed (see [ThemeCacheFingerprint]).
      if (themeCacheEvictRequested) {
        val evicted = store.evictAll()
        System.err.println("serve: theme cache evicted on request — $evicted generation(s) removed")
      }
    }
  }

  private val bundleSpecs: List<ServeStartupBundles.Spec> by lazy {
    ServeStartupBundles.parse(bundleFlags)
  }

  private val catalogsFile: ServeCatalogsConfigFile? = catalogsFilePath?.let {
    ServeCatalogsConfigFile(it.toPath())
  }

  private val agentGrantMaxTtlSeconds: Long =
    agentGrantMaxTtlFlag
      ?.let {
        // A typo must fail loudly, not fall back to the default: this bounds how long a minted
        // credential lives.
        AgentGrantProtocol.parseDurationSeconds(it)
          ?: throw IllegalArgumentException(
            "--agent-grant-max-ttl '$it' is not a duration — try 90m, 2h, or a number of seconds"
          )
      }
      ?.coerceIn(60L, ServeDefaults.AGENT_GRANT_HARD_MAX_TTL_SECONDS)
      ?: ServeDefaults.AGENT_GRANT_MAX_TTL_SECONDS

  private val agentGrantMaxScope: AgentGrantScope =
    agentGrantScopesFlag?.let {
      // A typo must fail: the fallback default (`preview,live`) would widen grants when the
      // operator meant to narrow them.
      AgentGrantScope.parseHighest(it)
        ?: throw IllegalArgumentException(
          "--agent-grant-scopes '$it' is not a scope list — use preview, live, or playground"
        )
    } ?: AgentGrantScope.DEFAULT_MAX

  // Raw flag strings parsed server-side, keeping these types off `:cli`'s classpath; errors still
  // fire at startup.
  private val agentGrantCapabilities: Set<AgentGrantCapability> =
    agentGrantCapabilitiesFlag?.let {
      // Throws on an unknown name, like `--agent-grant-scopes`, rather than silently withholding a
      // capability.
      AgentGrantCapability.parseAll(it)
    } ?: emptySet()

  /**
   * The one live-seat budget, shared by the HTTP stream lane and every catalog daemon pool. Built
   * here because pools are constructed before [ServeHttpServer] exists; two limiters would each
   * think they owned the box.
   */
  private val liveSeatLimiter: LiveSeatLimiter = LiveSeatLimiter(liveSeats)

  /**
   * Warm sandbox workers ([ServeSpareSandboxes]) every Android catalog daemon adopts from; built
   * here beside the seat budget for the same reason.
   */
  private val spareSandboxPool: ServeSpareSandboxes? =
    ServeSpareSandboxes.forBudget(options.spareSandboxes)

  /**
   * How every daemon-backed host forks its daemon (spare pool if any), so resumed sessions match
   * startup ones.
   */
  private val renderSessions: RenderSessionFactory =
    spareSandboxPool?.sessions ?: SubprocessRenderSessions

  /**
   * Why each catalog's live-lane launch failed, written by the bundle builders and read by
   * [ServeCatalogStore] so the degradation names the cause. See [LiveLaneLaunchLog].
   */
  private val liveLaneLaunchLog: LiveLaneLaunchLog = LiveLaneLaunchLog()

  /**
   * Where each `--catalogs` system's verified `liveBundle` landed, updated by [registerCatalogs] on
   * load and refresh. Lets `--playground-bundle <system>` use a served catalog's bundle. Written by
   * load threads, read by request threads.
   */
  private val catalogLiveBundles =
    java.util.concurrent.ConcurrentHashMap<String, List<CatalogLiveBundle>>()

  /**
   * Which daemon-preview ids of a live catalog can run the `cmp-android` player, keyed by session
   * descriptor (the only key [openHost] has). Filled from the bundle manifest
   * ([ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer]); keyed by descriptor so a resumed session
   * keeps its answer.
   */
  private val cmpAndroidPlayerByDescriptor =
    java.util.concurrent.ConcurrentHashMap<String, (String) -> Boolean>()

  /** The descriptor key each system last PUBLISHED with, so a refresh retires its predecessor. */
  private val cmpAndroidPlayerKeyBySystem = java.util.concurrent.ConcurrentHashMap<String, String>()

  /** Record before [openHost] reads it; [commitCmpAndroidPlayer] once the generation is live. */
  private fun recordCmpAndroidPlayer(
    descriptor: File,
    carriesPlayer: (daemonId: String) -> Boolean,
  ) {
    cmpAndroidPlayerByDescriptor[descriptor.absolutePath] = carriesPlayer
  }

  /**
   * Retire the previous generation's entry only after this one published, since the old session may
   * still suspend and reopen.
   */
  private fun commitCmpAndroidPlayer(system: String, descriptor: File) {
    val key = descriptor.absolutePath
    cmpAndroidPlayerKeyBySystem
      .put(system, key)
      ?.takeIf { it != key }
      ?.let { cmpAndroidPlayerByDescriptor.remove(it) }
  }

  private fun forgetCmpAndroidPlayer(system: String) {
    cmpAndroidPlayerKeyBySystem.remove(system)?.let { cmpAndroidPlayerByDescriptor.remove(it) }
  }

  /**
   * A served catalog's verified liveBundle as the playground sees it. [backend] is read once at
   * load so the runtime selector knows which modes a catalog offers before resolving a classpath;
   * null means unreadable metadata, and the catalog is not offered.
   */
  private data class CatalogLiveBundle(
    val id: String,
    val module: String,
    val file: java.io.File,
    val backend: String?,
  )

  private fun catalogTargetId(system: String, module: String, primary: Boolean): String =
    if (primary) system else "$system@$module"

  /**
   * Parsed sandbox policy, or a startup failure: a typo must never silently yield an unsandboxed
   * playground.
   */
  private val playgroundSandbox: Result<PlaygroundSandbox> =
    PlaygroundSandbox.parseProfile(playgroundSandboxSpec).mapCatching { parsed ->
      PlaygroundSandbox.validate(
          parsed.copy(
            memoryMb = playgroundSandboxMemoryMb,
            cpus = playgroundSandboxCpus,
            pids = playgroundSandboxPids,
            ttlSeconds = playgroundSandboxTtlSeconds,
            extraReadOnlyPaths = playgroundSandboxReadOnlyPaths,
          )
        )
        .getOrThrow()
    }

  /**
   * The live producer-trust store, shared by uploads, catalogs and the trust admin; consumers read
   * through it on every verification so edits apply without a restart.
   */
  private val trustStore: MutableTrustStore by lazy {
    MutableTrustStore(loadTrustStore(), source = trustStoreFile)
  }

  /**
   * The running branch poller, held so a trust revocation can invalidate retired catalogs' branch
   * heads ([retireNewlyUntrusted]).
   */
  @Volatile private var activeRefresher: ServeCatalogRefresher? = null

  /** The producers.json document backing [trustStore], when `--trust-store` names one. */
  private val trustStoreFile: ServeTrustStoreFile? by lazy {
    trustStorePath?.let { ServeTrustStoreFile(File(it).absolutePath.toPath()) }
  }

  /**
   * Whether the image lane will actually come up (opted in and given a gating repo), so a lane
   * [openImageLane] will refuse doesn't keep an empty server alive.
   */
  private val imageLaneConfigured: Boolean
    get() = acceptImages && !imageUploadRepository.isNullOrBlank()

  /**
   * Whether the UI builder is a lane in its own right: it hosts no session, so without this `ui
   * --no-project` would exit as empty. Requires both assets and a state dir; otherwise it serves
   * nothing usable.
   */
  private val uiBuilderLaneConfigured: Boolean
    // The bundled check only: resolving a pin fetches, and this is asked before startup has
    // decided anything. A pin is a lane too, even on a distribution that packages no editor.
    get() =
      (usableBundledUiBuilderDir() != null || catalogsConfig.editor != null) &&
        uiBuilderStateDirFlag != "none"

  /**
   * Whether [openUiBuilderService] actually returned a lane, set by [bringUpServer]. The static
   * shell is served either way, but a broken builder must not be the page `--open-browser` sends
   * the user to.
   */
  @Volatile private var uiBuilderLaneOpen: Boolean = false

  /**
   * The UI-builder lane's catalog refresh, reached through this because the refresher is built
   * first.
   */
  @Volatile private var uiBuilderPublishedRefresh: ((String) -> Unit)? = null

  /**
   * Each shadowed builder catalog's latest report ([ServeOptions.uiBuilderShadowCatalogs]), read by
   * `/admin/ui-builder/config`.
   */
  private val uiBuilderShadowReports = ConcurrentHashMap<String, ServeUiBuilderShadowReportDto>()

  /**
   * Problems with each catalog-owned builder catalog ([UiBuilderOwnedCatalogHealth]); read by
   * `/status`, the designs page and admin config.
   */
  @Volatile private var uiBuilderCatalogHealth: UiBuilderOwnedCatalogHealth? = null

  private fun uiBuilderCatalogProblems(): Map<String, String> =
    uiBuilderCatalogHealth?.problems().orEmpty()

  /**
   * Whether `/` has anything to show (catalogs or a default session), set with [uiBuilderLaneOpen].
   * A docs/images/admin-only host has neither, so `/` would 404.
   */
  @Volatile private var landingServesSomething: Boolean = false

  /** Whether [path] is the builder, in either of its two spellings. */
  private fun isUiBuilderPath(path: String): Boolean =
    path == "/ui-builder" || path.startsWith("/ui-builder/")

  /**
   * The page to open and print: [openBrowserPath], unless it names an absent builder (then `/` or
   * `/status`). Normalised to the trailing slash so the printed link doesn't depend on the
   * redirect.
   */
  private val effectiveOpenPath: String
    get() =
      when {
        !isUiBuilderPath(openBrowserPath) -> openBrowserPath
        uiBuilderLaneOpen ->
          if (openBrowserPath == "/ui-builder") "/ui-builder/" else openBrowserPath
        // `/status` leases no session, so a sessionless host can always answer it.
        landingServesSomething -> "/"
        else -> "/status"
      }

  /**
   * Whether nothing but the builder could keep this server alive (the configured half).
   * [bringUpServer] re-asks once the builder has tried to open, so a builder that fails is fatal
   * only when it was the sole surface. Registered sessions are the other half, checked there from
   * the registry.
   */
  private val uiBuilderIsOnlyConfiguredSurface: Boolean
    get() =
      catalogRefs.isEmpty() &&
        !acceptBundles &&
        !acceptDocs &&
        !imageLaneConfigured &&
        adminToken == null

  /**
   * The parsed `--catalogs-file`, or empty. A malformed file is reported and treated as empty so
   * the box still comes up on its flag-supplied catalogs.
   */
  private val catalogsConfig: ServeCatalogsConfig by lazy {
    val file = catalogsFile ?: return@lazy ServeCatalogsConfig.EMPTY
    val parsed = runCatching {
      file.load()
    }
      .getOrElse {
        System.err.println("serve: could not read ${file.displayPath}: ${it.message}")
        ServeCatalogsConfig.EMPTY
      }
    parsed.problems().forEach { System.err.println("serve: catalogs config: $it") }
    parsed
  }

  /**
   * Listed catalog systems that registered, shown as landing-page nav links. Unlisted catalogs
   * never land here.
   */
  private val registeredCatalogs = mutableListOf<String>()

  /** Unlisted app catalogs that registered, shown under the front page's "Apps" section. */
  private val registeredUnlistedCatalogs = mutableListOf<String>()

  /**
   * Recent daemon startup failures recorded by [openHost], surfaced on `/status` instead of only
   * stderr.
   */
  private val daemonLog = DaemonStartupLog()

  /**
   * Per-catalog per-preview daemon pools from [buildTrustedCatalogBundle], keyed by system. Owned
   * here because they outlive suspend/resume; closed at shutdown
   * ([catalogPerPreviewPoolsCloseable]), and a refresher re-load closes the previous pool for its
   * system.
   */
  private val catalogPerPreviewPools =
    java.util.concurrent.ConcurrentHashMap<String, AutoCloseable>()

  /** Closes every live per-preview pool at shutdown; a live view of [catalogPerPreviewPools]. */
  private val catalogPerPreviewPoolsCloseable = AutoCloseable {
    catalogPerPreviewPools.values.forEach { runCatching { it.close() } }
  }

  /**
   * Serializes catalog session publication and retirement, so admin trust/catalog routes can't
   * interleave with a load that has computed trust but not yet registered.
   */
  private val catalogRegistrationLock = Any()

  /**
   * Whether the box is under memory pressure, using the same hysteretic gate `/status.json`
   * publishes (`themeOptimizer.pressure`) so the registry sheds on the already-tuned threshold
   * without flapping.
   */
  private fun underMemoryPressure(): Boolean = runCatching {
    backgroundWork.optimizerAdmissionSnapshot().pressure?.constrained == true
  }
    .getOrDefault(false)

  private val backgroundWork by lazy {
    val pressureSampler = LinuxHostResourceSampler()
    // One number for the lane count, the render permits and the cross-replica coordinator. A pass
    // holds one permit for its whole batch, so admitted passes bound concurrent renders; mismatched
    // values left permits unreachable (making `--background-renders` useless) or had passes waiting
    // for permits while holding warm daemons and seats.
    val renderLane = backgroundRenders ?: ServeBackgroundWork.renderLaneFor(liveSeatLimiter)
    ServeBackgroundWork(
      maxConcurrentRenders = renderLane,
      maxConcurrentOptimizers = renderLane,
      hostCoordinator =
        optimizerCoordinationDirectory?.let {
          FileOptimizerHostCoordinator(directory = it, lanes = renderLane)
        } ?: OptimizerHostCoordinator.NONE,
      pressureGate =
        OptimizerPressureGate(
          sample = pressureSampler::sample,
          thresholds = OptimizerPressureThresholds.fromSystemProperties(),
        ),
    )
  }

  /**
   * Periodic enforcement of the blob cache ceiling. Request-path reads (lazy PNGs, `?at=<sha>`)
   * admit blobs with no publication nearby, so publication-time sweeps alone would let an idle box
   * fill its volume. A ticker rather than a write-path check, since enforcement is a directory
   * census; [sweepCatalogBlobs]'s rate limit makes redundant ticks free.
   */
  private fun startCatalogBlobSweeper(): AutoCloseable {
    val exec =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "serve-catalog-blob-sweep").apply { isDaemon = true }
      }
    exec.scheduleWithFixedDelay(
      { runCatching { sweepCatalogBlobs() } },
      ServeDefaults.CATALOG_BLOB_SWEEP_INTERVAL_MILLIS,
      ServeDefaults.CATALOG_BLOB_SWEEP_INTERVAL_MILLIS,
      java.util.concurrent.TimeUnit.MILLISECONDS,
    )
    return AutoCloseable { exec.shutdownNow() }
  }

  /** Rate limiter for [sweepCatalogBlobs]. */
  private val lastCatalogBlobSweep = java.util.concurrent.atomic.AtomicLong()

  /**
   * Reclaim pooled blobs no longer worth their disk. Eviction is always safe (the cost is a
   * refetch), so this needs no live-set knowledge, and runs after every publication, not just
   * startup. Rate-limited here so callers need not coordinate; [force] is for the end of the
   * startup pass, which reports what boot found.
   */
  private fun sweepCatalogBlobs(force: Boolean = false) {
    val now = System.currentTimeMillis()
    val previous = lastCatalogBlobSweep.get()
    if (!force && now - previous < ServeDefaults.CATALOG_BLOB_SWEEP_INTERVAL_MILLIS) return
    if (!lastCatalogBlobSweep.compareAndSet(previous, now)) return
    val snapshot = runCatching { catalogBlobPool.sweep() }.getOrNull() ?: return
    // Otherwise silent: a periodic line saying nothing happened is noise, and the counters belong
    // on /status rather than in the log.
    if (force || snapshot.evicted > 0) {
      System.err.println(
        "serve: catalog blob cache — ${snapshot.blobs} blob(s), ${snapshot.bytes / (1024 * 1024)} MB, " +
          "${snapshot.hits} hit(s), ${snapshot.misses} miss(es), ${snapshot.evicted} reclaimed"
      )
    }
  }

  /**
   * Reclaim theme-cache generations nothing can read. Runs once the catalog pass finishes, the only
   * moment the live set is known; earlier would delete a generation a later catalog was about to
   * adopt.
   */
  private fun sweepThemeCache() {
    val store = themeCacheStore ?: return
    val live =
      liveThemeGenerations.entries
        .map { (system, fingerprint) -> ThemeCacheStore.GenerationId(system, fingerprint) }
        .toSet()
    // Three populations: loaded now (sweep, reclaiming the superseded fingerprint); configured but
    // not loaded this pass (skip, so a transient failure doesn't cost a day of re-warming); no
    // longer configured (sweep, so removing a catalog frees its disk).
    val configuredButUnloaded = themeCacheConfiguredSystems().orEmpty() - liveThemeGenerations.keys
    val sweepable = runCatching { store.systems() }.getOrNull().orEmpty() - configuredButUnloaded
    val result =
      runCatching { store.sweep(live, onlySystems = sweepable + liveThemeGenerations.keys) }
        .getOrNull() ?: return
    if (result.deletedGenerations > 0) {
      System.err.println(
        "serve: theme cache swept ${result.deletedGenerations} stale generation(s), " +
          "${result.reclaimedBytes / (1024 * 1024)} MB reclaimed"
      )
    }
    if (result.overCap) {
      System.err.println(
        "serve: theme cache is ${result.bytes / (1024 * 1024)} MB, over its cap — every generation " +
          "still in use, so nothing was evicted. Raise --theme-cache-max-bytes or serve fewer catalogs."
      )
    }
  }

  /**
   * Systems this server is configured to serve; null until the tracker exists, which keeps the
   * sweep conservative.
   */
  @Volatile private var themeCacheConfiguredSystems: () -> Set<String>? = { null }

  /**
   * Generation in use per system, so a sweep knows what to keep. A map, not an append-only set, so
   * superseded fingerprints become reclaimable.
   */
  private val liveThemeGenerations = java.util.concurrent.ConcurrentHashMap<String, String>()

  /**
   * Build the disk tier for one catalog generation, or null when it has no durable identity. The
   * fingerprint comes from the daemon's own launch descriptor, so it tracks what the renderer
   * actually loads.
   */
  private fun themeCacheFor(
    system: String,
    alias: Map<String, String>,
    vararg descriptors: File,
  ): CatalogThemeCache {
    // Every bailout names itself: a catalog silently falling back to memory-only is
    // indistinguishable from a server with no cache dir.
    val store = themeCacheStore ?: return CatalogThemeCache()
    val launches = descriptors.map {
      ServeBundleDaemon.readLaunchDescriptor(it)
        ?: return CatalogThemeCache(persistenceOffReason = "launch descriptor unreadable")
    }
    if (launches.isEmpty())
      return CatalogThemeCache(persistenceOffReason = "catalog has no launch descriptor")
    // The JVM is part of what produced the pixels; system properties are not hashed because they
    // contain per-load staging paths.
    val renderConfig =
      launches.joinToString(" | ") {
        ThemeCacheFingerprint.renderConfig(it.systemProperties, it.jvmArgs)
      }
    val routing = ThemeCacheFingerprint.routingDigest(alias)
    val variant = launches.map { it.variant }.distinct().sorted().joinToString("+")
    // A multi-module catalog renders from several bundles at once and its generation is all of them
    // together — any one changing changes what a visitor sees.
    val fingerprint =
      ThemeCacheFingerprint.combine(
        launches.map { launch ->
          ThemeCacheFingerprint.of(
            classpath =
              ThemeCacheFingerprint.renderedClasspath(
                launch.classpath,
                launch.systemProperties,
                // Same preview manifest, reachable by a second route on descriptors that name it
                // here rather than in a system property.
                extraPayloads = listOfNotNull(launch.manifestPath),
              ),
            variant = launch.variant,
            renderConfig =
              ThemeCacheFingerprint.renderConfig(launch.systemProperties, launch.jvmArgs),
            routing = routing,
          )
            ?: return CatalogThemeCache(
              persistenceOffReason = "fingerprint unavailable (a classpath entry could not be read)"
            )
        }
      ) ?: return CatalogThemeCache(persistenceOffReason = "fingerprint unavailable")
    val inputs =
      GenerationInputs(
        system = system,
        fingerprint = fingerprint,
        toolVersion = SERVE_VERSION,
        variant = variant,
        renderConfig = "$renderConfig routing=$routing",
      )
    val generation =
      store.open(system, fingerprint, inputs)
        ?: return CatalogThemeCache(
          persistenceOffReason = "generation directory could not be opened"
        )
    // The sweep is not run here: the replacement host is still being staged and may fail, leaving
    // the previous generation in use. Retirement waits for a successful publication
    // ([sweepThemeCache]'s callers).
    liveThemeGenerations[system] = fingerprint
    if (generation.loadedEntries > 0) {
      System.err.println(
        "serve: catalog $system → ${generation.loadedEntries} theme renders adopted from disk"
      )
    }
    return CatalogThemeCache(persistence = generation)
  }

  /**
   * A parsed `--catalogs` / `--catalogs-unlisted` entry: [system], the [repo] of its branch
   * ([catalogRepo] unless overridden with `@<owner>/<repo>`), and whether it is [listed] on the
   * nav.
   */
  private data class CatalogRef(
    val system: String,
    val repo: String,
    val listed: Boolean,
    /**
     * Front-page section and the repos allowed to claim it; only `--catalogs-file` entries carry
     * one.
     */
    val group: ServeWeb.HomeGroup? = null,
    /**
     * Upstream project this catalog was rendered from; see
     * [ServeCatalogsConfig.Entry.importedFrom].
     */
    val importedFrom: String? = null,
    /**
     * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]); flag entries
     * keep their naming order.
     */
    val loadPriority: Int = 0,
    /**
     * In [ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP]: fetched first and required before the server
     * reports ready. Only `--catalogs-file` entries can claim it.
     */
    val designSystem: Boolean = false,
  )

  /**
   * Parse one comma-separated flag value into [CatalogRef]s; `<system>@<owner>/<repo>` per entry.
   */
  private fun parseCatalogRefs(raw: String?, listed: Boolean): List<CatalogRef> =
    raw
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.map { entry ->
        val at = entry.indexOf('@')
        if (at < 0) {
          CatalogRef(entry, catalogRepo, listed)
        } else {
          val system = entry.substring(0, at).trim()
          val repo = entry.substring(at + 1).trim().ifEmpty { catalogRepo }
          CatalogRef(system, repo, listed)
        }
      } ?: emptyList()

  /** The `--catalogs-file` entries as refs, skipping any the config itself reports as malformed. */
  private fun configCatalogRefs(): List<CatalogRef> =
    catalogsConfig.catalogs
      .filter { ServeCatalogsConfig.validateEntry(it) == null }
      .map { entry ->
        val repo = entry.repo?.takeIf { it.isNotBlank() } ?: catalogRepo
        CatalogRef(
          system = entry.system,
          repo = repo,
          listed = entry.listed,
          group = ServeCatalogAdmin.homeGroup(entry, repo, catalogsConfig.groups),
          importedFrom = entry.importedFrom,
          loadPriority = entry.loadPriority,
          designSystem = entry.group == ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP,
        )
      }

  /** Validated `--catalog-registry` nominations; empty ⇒ off. */
  private val catalogRegistryRepos: List<ServeCatalogRegistry.Nomination> by lazy {
    ServeCatalogRegistry.parseRepos(catalogRegistryRaw) { System.err.println("serve: $it") }
  }

  /**
   * Each registry project's document, read once at startup on the boot thread so its catalogs load
   * through the ordinary startup path (order, readiness, `/status`) rather than as a late runtime
   * publish. [ServeCatalogRegistrySync] handles later changes. Best-effort per registry.
   */
  private val catalogRegistryBoot: List<RegistryBoot> by lazy {
    catalogRegistryRepos.map { nomination ->
      // Captured as well as printed so `/status` shows why a catalog is missing
      // ([CatalogRegistryStatus]).
      var problem: String? = null
      val contribution =
        ServeCatalogRegistry.fetch(nomination, ::fetchRegistryDocument) {
            problem = it
            System.err.println("serve: $it")
          }
          // Log each registry's contribution, so a working registry is distinguishable from an
          // absent flag.
          ?.also { contribution ->
            System.err.println(
              "serve: catalog registry ${nomination}: ${contribution.entries.size} catalog(s) — " +
                contribution.entries.joinToString(", ") { it.system }
            )
          }
      RegistryBoot(nomination, contribution, problem)
    }
  }

  /** One nomination's boot-time result: what it gave us, or why it gave us nothing. */
  private data class RegistryBoot(
    val nomination: ServeCatalogRegistry.Nomination,
    val contribution: ServeCatalogRegistry.Contribution?,
    val problem: String?,
  )

  private val catalogRegistryContributions: List<ServeCatalogRegistry.Contribution>
    get() = catalogRegistryBoot.mapNotNull { it.contribution }

  /**
   * The nominations as `/status` reports them: the boot read until [sync] has read a nomination,
   * then the sync's latest. See [catalogRegistryStatus].
   */
  private fun catalogRegistryStatuses(
    sync: ServeCatalogRegistrySync?
  ): List<CatalogRegistryStatus> = catalogRegistryBoot.map { boot ->
    catalogRegistryStatus(
      nomination = boot.nomination,
      bootContribution = boot.contribution,
      bootProblem = boot.problem,
      live = sync?.lastRead(boot.nomination),
    )
  }

  /** Read one registry document; shared by the sync and the boot fold-in. */
  private fun fetchRegistryDocument(url: String, maxBytes: Long): ByteArray? =
    ServeCatalogStore.httpFetchOutcome(url, maxBytes).bytesOrNull

  /**
   * Where to look for published designs: every configured catalog, available or not, so the browse
   * list doesn't flicker with load state. `lastAttemptEpochMillis` is the generation marker, moving
   * when the refresher re-fetches.
   */
  /**
   * The app's own checkouts from `--ui-builder-designs`, ahead of any catalog's published set: a
   * local design of the same name is the one being worked on.
   */
  private fun uiBuilderDesignDirectories(): List<ServeUiBuilderDesignLibrary.Coordinate> =
    uiBuilderDesigns.map { (system, dir) ->
      ServeUiBuilderDesignLibrary.Coordinate(
        system = system,
        source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
      )
    }

  private fun uiBuilderDesignCatalogCoordinates(
    catalogLoads: CatalogLoadTracker?
  ): List<ServeUiBuilderDesignLibrary.Coordinate> =
    catalogLoads?.snapshot().orEmpty().map { state ->
      ServeUiBuilderDesignLibrary.Coordinate(
        system = state.config.system,
        source =
          ServeUiBuilderDesignLibrary.Source.Branch(
            repo = state.config.repo,
            branch = state.config.branch,
          ),
        generation = state.lastAttemptEpochMillis?.toString(),
      )
    }

  /** The registry-contributed entries as refs, in registry order. */
  private fun registryCatalogRefs(): List<CatalogRef> =
    catalogRegistryContributions.flatMap { contribution ->
      contribution.entries.map { entry ->
        CatalogRef(
          system = entry.system,
          repo = contribution.repo,
          listed = entry.listed,
          group = contribution.homeGroup(entry),
          importedFrom = entry.importedFrom,
          loadPriority = entry.loadPriority,
        )
      }
    }

  /**
   * Whether the catalog machinery is needed even with no configured catalogs: an `--admin-token`
   * server publishes its first catalog at runtime and needs the store and tracker ready.
   */
  private val needsCatalogMachinery: Boolean
    get() = catalogRefs.isNotEmpty() || adminToken != null

  /**
   * All catalog refs: config file first (it carries grouping), then flag entries, de-duplicated by
   * system with first winning, so a flag can add a catalog but never re-attribute one.
   */
  private val catalogRefs: List<CatalogRef> by lazy {
    (operatorCatalogRefs() +
        // Last, so first-wins de-duplication means a registry can add catalogs the operator hasn't
        // named but can never re-attribute one they have. See [ServeCatalogRegistry].
        registryCatalogRefs())
      .distinctBy { it.system }
  }

  /** The operator's own catalog refs — the config file, then the flags — before any registry. */
  private fun operatorCatalogRefs(): List<CatalogRef> =
    configCatalogRefs() +
      parseCatalogRefs(catalogsRaw, listed = true) +
      parseCatalogRefs(catalogsUnlistedRaw, listed = false)

  public fun run() {
    // Before any cmp-jvm worker spawns: workers read their fonts dir at spawn and keep the fallback
    // face otherwise. See [ServeRcJvmFonts].
    ServeRcJvmFonts.installPackaged()

    // Gradle is opt-in: without an explicit request this is a pure preview server over fetched
    // sources, even inside a Gradle checkout (a stray `serve` must not start a possibly-hanging
    // build). The opt-in signals are `--module` / `--discover` plus the modes that structurally
    // need Gradle: `--export`, `--catalog-source-root` and `--revisions`, each implying discovery.
    val needsGradle =
      explicitModule != null ||
        discover ||
        exportPath != null ||
        catalogSourceRoot != null ||
        revisions
    if (!needsGradle) {
      // Selectors apply to a discovered module's manifest, which this path never has; warn instead
      // of silently publishing everything (see #3744).
      val selectors =
        listOfNotNull(
          exactId?.let { "--id" },
          filter?.let { "--filter" },
          previewRef?.let { "--preview" },
        )
      if (selectors.isNotEmpty()) {
        System.err.println(
          "serve: ${selectors.joinToString(" / ")} select previews from a discovered module, but " +
            "this server is bundle-backed — it hosts what --bundle / --bundles / --catalogs and " +
            "uploads provide, and no manifest to select against exists."
        )
        System.err.println(
          "  Drop the selector, or pass --module <path> / --discover to serve from a module."
        )
        exitProcess(64)
      }
      runBundleServer()
      return
    }

    // Discover and build; `--module` is passed to the build host so the render task itself is
    // narrowed.
    val discovered = discoverAndBuild(silenceStdout = false)
    // Applied again for build hosts that predate the spawn argument and report every module.
    val outcome =
      explicitModule?.let { requested ->
        selectRequestedModule(discovered, requested)
          ?: run {
            System.err.println(
              "serve: --module $requested matched no discovered preview module" +
                if (discovered.manifests.isEmpty()) "."
                else
                  ": " +
                    discovered.manifests.joinToString(", ") { (module, _) -> module.gradlePath } +
                    "."
            )
            exitProcess(3)
          }
      } ?: discovered
    if (!outcome.buildOk) {
      System.err.println("serve: render build failed.")
      exitProcess(2)
    }
    if (outcome.manifests.isEmpty()) {
      System.err.println("serve: no previews discovered.")
      exitProcess(3)
    }
    onDiscovered(outcome)
    // Expand `@PreviewParameter` fan-out before counting modules: selection kept modules whose rows
    // might match, and one that yields nothing servable must not abort a request with exactly one
    // real answer.
    val servable = modulesWithMatchingPreviews(outcome.manifests)
    if (servable.isEmpty()) {
      System.err.println("serve: no previews matched (--id/--filter excluded them all).")
      exitProcess(3)
    }
    if (browseProject && exportPath == null) {
      runProjectBrowser(servable, outcome.manifests)
      return
    }
    if (servable.size > 1) {
      System.err.println(
        "serve: ${servable.size} modules discovered; a server hosts one module. " +
          "Narrow with --module <path>:"
      )
      servable.forEach { (m, _) -> System.err.println("  ${m.gradlePath}") }
      exitProcess(1)
    }

    val (module, previews) = servable.single()
    val manifest = outcome.manifests.first { (m, _) -> m.gradlePath == module.gradlePath }.second
    // The module's declared @ThemeCatalog themes — the Theme selector renders them so a preview can
    // be re-rendered under Brand Dark etc. Module-global, so unaffected by the --id/--filter above.
    val declaredThemes = declaredThemesFromPreviews(manifest.previews)

    if (!runDaemonStart(module)) {
      System.err.println("serve: composePreviewDaemonStart failed for ${module.gradlePath}.")
      exitProcess(2)
    }

    val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptor.isFile) {
      System.err.println("serve: missing daemon-launch.json at ${descriptor.path}")
      exitProcess(2)
    }

    val renderHost =
      try {
        ServeRenderHost.open(
          descriptorPath = descriptor,
          workspaceRoot = module.projectDir,
          workspaceName = module.projectDir.name,
          previews = previews,
          label = module.gradlePath,
          declaredThemes = declaredThemes,
          onLog = { System.err.println("[daemon serve] $it") },
          factory = renderSessions,
        )
      } catch (e: RenderSessionException) {
        System.err.println("serve: failed to open render session (${e.message})")
        exitProcess(2)
      }

    // `--export` reuses the render session to write a portable bundle and exits, so the live link
    // and the offline bundle are the same render output.
    val exportTo = exportPath
    if (exportTo != null) {
      exportBundle(renderHost, module.gradlePath, exportTo)
      renderHost.close()
      return
    }

    val token = tokenOverride ?: ServeUrls.generateToken()
    // One server fronts a session registry: the current checkout is the default session, and idle
    // daemons are suspended and resumed from saved state.
    val openHost: (ServeSessionState) -> ServeHost? = ::openHost
    // Project mode forks a session per git revision via the registry's factory, with worktrees
    // rooted at the served module's project. Off by default.
    val worktrees: GitWorktrees? = if (revisions) openWorktrees(module) else null
    // Worktrees for the trusted-catalog builder: rooted at `--catalog-source-root` when set, else
    // the served project (reusing [worktrees]). Kept separate so `?session=<rev>` stays rooted at
    // the served project. Both are gated by `--revisions-allow`.
    val catalogWorktrees: GitWorktrees? =
      when {
        !allowRenderTrusted -> null
        catalogSourceRoot != null -> openWorktrees(module, rootOverride = catalogSourceRoot)
        else -> worktrees ?: openWorktrees(module)
      }
    // The `?session=<rev>` factory is gated on `--revisions` only, not on worktrees existing;
    // otherwise `--allow-render-trusted` would let clients trigger builds of arbitrary allowlisted
    // revisions.
    val factory =
      if (revisions && worktrees != null) revisionFactory(module, worktrees)
      else ServeSessionFactory { null }
    val registry =
      ServeSessionRegistry(
        open = openHost,
        factory = factory,
        underMemoryPressure = ::underMemoryPressure,
      )
    val defaultState =
      ServeSessionState(
        descriptor = descriptor,
        workspaceRoot = module.projectDir,
        workspaceName = module.projectDir.name,
        previews = previews,
        label = module.gradlePath,
        // Declared themes live on the session state so the theme selector survives suspend/resume.
        declaredThemes = declaredThemes,
      )
    registry.register(module.gradlePath, defaultState, host = renderHost)
    // Shared mode: pre-rendered bundles under `--bundles <dir>` as read-only, pinned sessions
    // (cheap, nothing to reclaim).
    registerBundles().forEach { (id, bundleHost) ->
      registry.register(id, host = bundleHost, pinned = true)
    }
    // Serve any operator-supplied `--bundle <url|path>` fetched bundles alongside the module — live
    // from a daemon when Trusted + --allow-render-trusted, else read-only baked PNGs.
    registerStartupBundles(registry)
    // Serve published design systems from their trusted branches; a catalog's `web/wasm/` app rides
    // the same branch.
    val catalogReg =
      if (needsCatalogMachinery) registerCatalogs(registry, catalogWorktrees, openHost) else null
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart.
    val catalogRefresher = catalogReg?.let { buildCatalogRefresher(it.store, it.loads) }
    // Make manual refresh + trust-revocation invalidation available as soon as any catalog page
    // can be served. The background cadence is still seeded and started after the loader finishes.
    activeRefresher = catalogRefresher
    // Runtime ingestion (--accept-bundles): clients POST a bundle (or a ?url= to one) and it's
    // registered as a pinned session. Unpacked under a temp dir for this server's lifetime.
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null
    val wasmCatalogs = mergedWasmCatalogs(catalogReg)
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("serve: in-browser Wasm tier for: ${wasmCatalogs.keys.joinToString(", ")}")
    }
    bringUpServer(
      registry = registry,
      token = token,
      defaultSessionId = module.gradlePath,
      bundleStore = bundleStore,
      wasmCatalogs = wasmCatalogs,
      bannerLabel = module.gradlePath,
      bannerPreviewCount = previews.size,
      mdnsModuleLabel = module.gradlePath,
      mdnsPreviewIds = previews.map { it.id },
      closeables =
        listOf(
          spareSandboxPool,
          catalogReg?.loader,
          catalogRefresher,
          worktrees,
          catalogWorktrees.takeIf { it !== worktrees },
          catalogPerPreviewPoolsCloseable,
          catalogReg?.let { startCatalogBlobSweeper() },
        ),
      catalogLoads = catalogReg?.loads,
      catalogStore = catalogReg?.store,
      catalogRefresh =
        catalogRefresher?.let { refresher ->
          { system: String, force: Boolean -> refresher.refresh(system, force) }
        },
      localSourceRoots =
        if (componentBrowser) mapOf(module.gradlePath to module.projectDir) else emptyMap(),
      // Project mode computes the history strip from local git instead of a published history.json.
      projectHistory =
        historyBranch?.let { ServeProjectHistory(repoRoot = projectRepoRoot(module), branch = it) },
      onStarted = {
        catalogReg?.loader?.start { loaded ->
          catalogRefresher?.let {
            it.seedInitialHeads(loaded)
            // The poller is what the interval switches off; the manual route stays either way.
            if (catalogRefreshSeconds > 0) it.start()
          }
        }
      },
    )
  }

  /**
   * Browse every preview-bearing module behind one component-browser front door; a failed daemon
   * removes only its module. Kept out of the full `serve` path, whose options describe one module.
   */
  private fun runProjectBrowser(
    servable: List<Pair<PreviewModule, List<ServePreview>>>,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
  ) {
    val registry =
      ServeSessionRegistry(open = ::openHost, underMemoryPressure = ::underMemoryPressure)
    val opened = mutableListOf<Pair<PreviewModule, List<ServePreview>>>()

    servable.forEach { (module, previews) ->
      if (!runDaemonStart(module)) {
        System.err.println(
          "browse: ${module.gradlePath} could not start its preview daemon — skipping it."
        )
        return@forEach
      }
      val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
      if (!descriptor.isFile) {
        System.err.println(
          "browse: ${module.gradlePath} produced no daemon-launch.json — skipping it."
        )
        return@forEach
      }
      val manifest =
        manifests.first { (candidate, _) -> candidate.gradlePath == module.gradlePath }.second
      val declaredThemes = declaredThemesFromPreviews(manifest.previews)
      val host =
        try {
          ServeRenderHost.open(
            descriptorPath = descriptor,
            workspaceRoot = module.projectDir,
            workspaceName = module.projectDir.name,
            previews = previews,
            label = module.gradlePath,
            declaredThemes = declaredThemes,
            onLog = { System.err.println("[daemon browse ${module.gradlePath}] $it") },
            factory = renderSessions,
          )
        } catch (e: RenderSessionException) {
          System.err.println(
            "browse: ${module.gradlePath} failed to open its render session (${e.message}) — skipping it."
          )
          return@forEach
        }
      registry.register(
        module.gradlePath,
        ServeSessionState(
          descriptor = descriptor,
          workspaceRoot = module.projectDir,
          workspaceName = module.projectDir.name,
          previews = previews,
          label = module.gradlePath,
          declaredThemes = declaredThemes,
        ),
        host = host,
      )
      opened += module to previews
    }

    if (opened.isEmpty()) {
      System.err.println("browse: no preview module could start a render session.")
      exitProcess(2)
    }

    val wasmCatalogs = mergedWasmCatalogs(null)
    val privateWasmCatalogs = mutableSetOf<String>()
    automaticWasmCatalogs(opened.map { it.first }).forEach { (module, dir) ->
      // An advanced explicit --wasm-dir remains an escape hatch, and wins when supplied.
      if (wasmCatalogs.putIfAbsent(module, dir) == null) privateWasmCatalogs += module
    }
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("browse: in-browser CMP Wasm for: ${wasmCatalogs.keys.joinToString(", ")}")
    }

    val first = opened.first()
    bringUpServer(
      registry = registry,
      token = tokenOverride ?: ServeUrls.generateToken(),
      defaultSessionId = first.first.gradlePath,
      bundleStore = null,
      wasmCatalogs = wasmCatalogs,
      privateWasmCatalogs = privateWasmCatalogs,
      bannerLabel = "${opened.size} component modules",
      bannerPreviewCount = opened.sumOf { it.second.size },
      mdnsModuleLabel = null,
      mdnsPreviewIds = null,
      closeables = listOf(spareSandboxPool),
      catalogLoads = null,
      localCatalogSessions = opened.map { it.first.gradlePath },
      localSourceRoots = opened.associate { it.first.gradlePath to it.first.projectDir },
    )
  }

  /**
   * Module-less mode: a pure preview server with no local project or Gradle build, hosting
   * `--bundle` / `--bundles` / `--catalogs` / `--accept-bundles` sources. A Trusted bundle can
   * still be live-rendered from a daemon. Source builds are unavailable, so a catalog without a
   * usable `liveBundle` falls back to baked PNGs.
   */
  private fun runBundleServer() {
    val token = tokenOverride ?: ServeUrls.generateToken()
    val registry =
      ServeSessionRegistry(open = ::openHost, underMemoryPressure = ::underMemoryPressure)

    registerBundles().forEach { (id, bundleHost) ->
      registry.register(id, host = bundleHost, pinned = true)
    }
    val registeredStartup = registerStartupBundles(registry)
    // No worktrees in module-less mode — catalogs live-render only from their carried `liveBundle`.
    val catalogReg =
      if (needsCatalogMachinery) registerCatalogs(registry, worktrees = null, ::openHost) else null
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart — the
    // public preview server (preview.coo.ee) runs this module-less path.
    val catalogRefresher = catalogReg?.let { buildCatalogRefresher(it.store, it.loads) }
    // A catalog registered early in the asynchronous startup load can already show Refresh; wire
    // its immediate check now instead of waiting for every configured catalog to finish loading.
    activeRefresher = catalogRefresher
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null

    val wasmCatalogs = mergedWasmCatalogs(catalogReg)
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("serve: in-browser Wasm tier for: ${wasmCatalogs.keys.joinToString(", ")}")
    }

    // Pick a landing session so `/` resolves: the first configured catalog, else the first bundle.
    val defaultSessionId =
      catalogRefs.firstOrNull { it.listed }?.system
        ?: registeredStartup.firstOrNull()
        ?: registry.anySessionId()
    // Upload, document, image and admin hosts legitimately start with no sessions; bail only when
    // there is nothing to serve and no way to add any.
    if (
      defaultSessionId == null &&
        catalogRefs.isEmpty() &&
        !acceptBundles &&
        !acceptDocs &&
        !imageLaneConfigured &&
        !uiBuilderLaneConfigured &&
        adminToken == null
    ) {
      // Name the missing `--image-upload-repo` first; the generic line below would otherwise read
      // as if the flag was never set.
      if (acceptImages) System.err.println(ServeDefaults.IMAGE_LANE_NO_REPO)
      System.err.println(
        "serve: nothing to serve — no --bundle / --bundles / --catalogs registered a session, and " +
          "none of --accept-bundles / --accept-docs / --accept-images / --ui-builder-dir / " +
          "--admin-token is set."
      )
      // Guide the common "ran serve in my project expecting a build" case: Gradle discovery is now
      // opt-in, so point at --discover / --module rather than leaving them staring at a bare error.
      if (gradleProjectRoot() != null) {
        System.err.println(
          "  This looks like a Gradle project. Local preview discovery/build is opt-in: pass " +
            "--discover to build all modules, or --module <path> to scope to one."
        )
      }
      exitProcess(3)
    }

    bringUpServer(
      registry = registry,
      token = token,
      // Upload-only server: no landing session yet (routes 404 until the first upload lands).
      defaultSessionId = defaultSessionId ?: "",
      bundleStore = bundleStore,
      wasmCatalogs = wasmCatalogs,
      bannerLabel = "(no module — hosting fetched bundles/catalogs)",
      bannerPreviewCount = registry.activeCount(),
      // No module previews to advertise; discovery is a module-session nicety, so skip it here.
      mdnsModuleLabel = null,
      mdnsPreviewIds = null,
      closeables =
        listOfNotNull(
          spareSandboxPool,
          catalogReg?.loader,
          catalogRefresher,
          catalogPerPreviewPoolsCloseable,
          catalogReg?.let { startCatalogBlobSweeper() },
        ),
      catalogLoads = catalogReg?.loads,
      catalogStore = catalogReg?.store,
      catalogRefresh =
        catalogRefresher?.let { refresher ->
          { system: String, force: Boolean -> refresher.refresh(system, force) }
        },
      onStarted = {
        catalogReg?.loader?.start { loaded ->
          catalogRefresher?.let {
            it.seedInitialHeads(loaded)
            // The poller is what the interval switches off; the manual route stays either way.
            if (catalogRefreshSeconds > 0) it.start()
          }
        }
      },
    )
  }

  /**
   * Reopen a session's host from its [ServeSessionState]: the registry's `open` callback for every
   * mode. Trusted-catalog and live-bundle sessions carry a baked fallback and alias, so the daemon
   * is fronted by [ServeCatalogLiveHost]; other sessions get the bare daemon. Rebuilt on every
   * resume.
   */
  private fun openHost(state: ServeSessionState): ServeHost? = runCatching {
    fun openDaemon(systemPropertyOverrides: Map<String, String> = emptyMap()): ServeRenderHost =
      ServeRenderHost.open(
        descriptorPath = state.descriptor,
        workspaceRoot = state.workspaceRoot,
        workspaceName = state.workspaceName,
        previews = state.previews,
        label = state.label,
        declaredThemes = state.declaredThemes,
        systemPropertyOverrides = systemPropertyOverrides,
        onLog = { System.err.println("[daemon serve] $it") },
        factory = renderSessions,
      )
    val daemon = openDaemon()
    val fallback = state.bakedFallback
    if (fallback != null)
      ServeCatalogLiveHost(
          alias = state.previewAliases,
          live = daemon,
          baked = fallback(),
          perPreviewResolve = state.perPreviewResolve,
          executableBundleAvailable = state.executableBundleAvailable,
          executableBundleProvider = state.executableBundleProvider,
          perPreviewStreamCount = state.perPreviewStreamCount,
          perPreviewRenderStats = state.perPreviewRenderStats,
          perPreviewPoolStats = state.perPreviewPoolStats,
          perPreviewReapIdle = state.perPreviewReapIdle,
          sharedDaemonPool =
            ServeSharedDaemonPool(
              primary = daemon,
              liveSeats = liveSeatLimiter,
              seatWeight = { state.liveSeatWeight },
            ) {
              // Replicas need separate output roots: every daemon writes `<outputBaseName>.png`
              // there, and overlapping themes could overwrite each other before the host reads the
              // file.
              openIsolatedSharedDaemonReplica(state.descriptor, ::openDaemon)
            },
          catalogThemeCache = state.catalogThemeCache ?: CatalogThemeCache(),
          serverIdleMillis = state.serverIdleMillis,
          backgroundWork = state.backgroundWork,
          // The catalog's own resident daemon (~1.2 GB) also charges the live-seat budget.
          liveSeats = liveSeatLimiter,
          residencySeatWeight = { state.liveSeatWeight },
          cmpAndroidPlayerFor =
            cmpAndroidPlayerByDescriptor[state.descriptor.absolutePath] ?: { false },
        )
        // Prewarm off the request path so a slow-starting Android daemon is ready for the first
        // browse.
        .also { it.prewarm() }
    else daemon
  }
    // Record the failure for `/status`; the host still degrades to null.
    .onFailure { daemonLog.record(state.label, it.message ?: it.toString()) }
    .getOrNull()

  /**
   * The `--accept-docs` store, or null. In-memory and TTL-bounded; nothing to register or clean up.
   */
  private fun openDocStore(): ServeDocStore? {
    if (!acceptDocs) return null
    if (acceptDocsFrom.isEmpty()) {
      System.err.println(
        "serve: --accept-docs accepts uploads only; no ?url= host is allowed (SSRF fail closed). " +
          "Pass --accept-docs-from <host>[,<host>…] to permit URL fetches."
      )
    }
    System.err.println(
      "serve: document uploads enabled (/docs) — ${ServeDocFormats.knownSummary()}; " +
        "links expire after ${docTtlSeconds}s"
    )
    return ServeDocStore(
      ttlSeconds = docTtlSeconds,
      allowedHosts = acceptDocsFrom,
      mintId =
        if (playgroundRole) ({ ServeDocStore.playgroundId() }) else ({ ServeDocStore.randomId() }),
    )
  }

  /**
   * The `--accept-images` lane (store plus identity gate), or null. Fails closed without a
   * repository, since the gate is "GitHub says this account can access that repo". Returned as a
   * pair so [ServeHttpServer] gets both or neither.
   */
  private fun openImageLane(): ImageLane? {
    if (!acceptImages) return null
    val repository = imageUploadRepository
    if (repository.isNullOrBlank()) {
      System.err.println(ServeDefaults.IMAGE_LANE_NO_REPO)
      return null
    }
    // Only the id and secret are needed to recognise this app's tokens, not the rest of sign-in.
    val oauthApp =
      if (!githubAuthClientId.isNullOrBlank() && !githubAuthClientSecret.isNullOrBlank()) {
        GitHubOAuthApp(githubAuthClientId!!, githubAuthClientSecret!!)
      } else null
    val tokens =
      ImageUploadTokenPolicy.parse(imageUploadTokensFlag, appConfigured = oauthApp != null)
    System.err.println(
      "serve: image uploads enabled (POST /images) — ${ServeImageFormats.knownSummary()}; " +
        "links expire after ${imageTtlSeconds}s; uploaders must have access to $repository; " +
        "tokens accepted: ${tokens.describe(appConfigured = oauthApp != null)}"
    )
    if (imageRateLimit <= 0) {
      System.err.println(
        "serve: WARNING image uploads are UNMETERED (--image-rate-limit 0). The store's size caps " +
          "still bound memory, but one account can churn every held image out of it."
      )
    }
    return ImageLane(
      store = ServeImageStore(ttlSeconds = imageTtlSeconds),
      auth =
        GithubTokenUploadAuth(
          repository = repository,
          allowedUsers = githubAuthUsers,
          allowedOrgs = githubAuthOrgs,
          tokens = tokens,
          app = oauthApp,
        ),
      limiter =
        if (imageRateLimit > 0) {
          ServeRateLimiter(
            permitsPerWindow = imageRateLimit,
            windowSeconds = 60,
            // Serialising per account is free (memory writes) and keeps one agent's batch from
            // crowding others.
            maxConcurrent = ServeDefaults.IMAGE_CALLER_CONCURRENCY,
          )
        } else null,
    )
  }

  /** The image lane's three pieces, built and disabled together. */
  private class ImageLane(
    val store: ServeImageStore,
    val auth: ServeImageUploadAuth,
    val limiter: ServeRateLimiter?,
  )

  /** Runtime catalog selection, under `--playground`, `--compile-engine` or the playground role. */
  private val engineRuntimeSelection: Boolean
    get() = playgroundRuntimeSelection || compileEngine || playgroundRole

  /**
   * Whether the public playground surface is mounted: always with `--playground`, and with a bare
   * bundle pin unless `--compile-engine` restricts the engine to the UI builder.
   */
  private val publicPlayground: Boolean
    get() =
      playgroundRuntimeSelection ||
        playgroundRole ||
        ((playgroundBundlePath != null || playgroundAndroidBundlePath != null) && !compileEngine)

  /**
   * Build the `--playground-bundle` compile service, or null. Resolves the CMP classpath from the
   * liveBundle once at startup and wires the in-process BTA compiler from `lib-bta/`.
   *
   * Under `--public` the lane needs one admission posture ([PlaygroundPublicGate]): a verified
   * per-session sandbox (the startup probe must show egress blocked and filesystem/process
   * contained) or [repoAccessGated]. Anonymous and uncontained is refused. Any other missing piece
   * logs and disables the lane.
   *
   * @param repoAccessGated GitHub auth is configured, so the routes' repo-access check actually
   *   rejects callers.
   */
  private fun openPlaygroundService(
    docStore: ServeDocStore?,
    registry: ServeSessionRegistry,
    repoAccessGated: Boolean,
  ): PlaygroundLane? {
    val cmpBundle = playgroundBundlePath
    val androidBundle = playgroundAndroidBundlePath
    if (cmpBundle == null && androidBundle == null && !engineRuntimeSelection) return null
    // With nothing served there is nothing to select; refuse at startup rather than serve a
    // permanently empty selector.
    if (cmpBundle == null && androidBundle == null && catalogRefs.isEmpty()) {
      System.err.println(
        "serve: --playground / --compile-engine select a catalog at runtime but no --catalogs " +
          "are configured, and no --playground-bundle is pinned; there is nothing to compile " +
          "against. Playground disabled."
      )
      return null
    }

    val configuredSandbox = playgroundSandbox.getOrElse { e ->
      System.err.println("serve: ${e.message}. Playground disabled.")
      return null
    }
    val workRoot = java.nio.file.Files.createTempDirectory("compose-playground").toFile()

    // Under `--public` the playground serves only behind a sandbox that has demonstrated
    // containment: the preflight runs a throwaway JVM in the jail and checks network, filesystem
    // and process isolation. Run for any active sandbox, since whether the jail can launch at all
    // matters on token-gated hosts too.
    val probe =
      if (configuredSandbox.isActive) {
        System.err.println("serve: playground sandbox preflight (${configuredSandbox.describe()})…")
        PlaygroundSandboxProbe.run(
            sandbox = configuredSandbox,
            javaHome = java.io.File(System.getProperty("java.home")),
            classpath =
              System.getProperty("java.class.path")
                .orEmpty()
                .split(java.io.File.pathSeparator)
                .filter { it.isNotBlank() },
            workRoot = workRoot,
          )
          .also { System.err.println("serve: ${it.summary()}") }
      } else null
    // Kept so `/status.json` reports which posture admitted the lane.
    val admittedBy: String
    when (
      val decision = PlaygroundPublicGate.decide(public, repoAccessGated, configuredSandbox, probe)
    ) {
      is PlaygroundPublicGate.Decision.Refuse -> {
        System.err.println("serve: ${decision.reason}")
        workRoot.deleteRecursively()
        return null
      }
      is PlaygroundPublicGate.Decision.Allow -> {
        System.err.println("serve: playground admitted — ${decision.detail}")
        admittedBy = decision.detail
      }
    }
    // A configured jail that can't launch here would silently break every snippet; drop the jail
    // and keep the caps (`-Xmx`, CPU cap, temp-dir confinement, TTL), see
    // PlaygroundSandbox.droppingJail. Unreachable for the contained posture, which is refused above
    // without a probe.
    // Not for `systemd`/`strict`, whose resource caps live in the dropped `systemd-run` prefix:
    // refuse rather than run without the caps the operator asked for.
    if (probe != null && !probe.ran && configuredSandbox.profile.declaresResourceCaps) {
      System.err.println(
        "serve: playground sandbox '${configuredSandbox.profile.id}' could not launch on this " +
          "host (${probe.detail}), and its CPU/memory/pid caps are enforced BY that command — " +
          "dropping it would leave the snippet effectively uncapped, so the playground is " +
          "disabled instead. Fix the jail (a container has no systemd to build a transient scope " +
          "against), or pick a profile whose caps are JVM-level (bwrap, unshare)."
      )
      workRoot.deleteRecursively()
      return null
    }
    val sandbox =
      if (probe != null && !probe.ran) {
        System.err.println(
          "serve: WARNING playground sandbox '${configuredSandbox.profile.id}' could not launch on this " +
            "host (${probe.detail}) — dropping the jail and keeping the JVM caps. Snippets run " +
            "capped but UNCONTAINED; the lane is admitted by ${if (public) "repo-access gating" else "the access token"}, not by containment." +
            (if (configuredSandbox.profile == PlaygroundSandbox.Profile.CUSTOM)
              " Any caps that custom argv supplied are gone with it — only the JVM-level ones remain."
            else "")
        )
        configuredSandbox.droppingJail()
      } else configuredSandbox
    // A repo-access-gated lane ignores the probe, so warn when the defence-in-depth jail is broken;
    // the lane still serves.
    if (repoAccessGated && probe != null && (!probe.ran || probe.failedChecks().isNotEmpty())) {
      System.err.println(
        "serve: WARNING playground sandbox '${sandbox.profile.id}' is configured but did not " +
          "contain the preflight (" +
          (if (!probe.ran) probe.detail else probe.failedChecks().joinToString("; ")) +
          "). The lane serves because it is repo-access-gated, not because it is contained."
      )
    }

    // Classpaths resolve on first use: a served-catalog bundle loads in the background after
    // startup, so resolving now would disable the mode forever (#3212). Local paths are deferred
    // the same way.
    val cmpSupplier = cmpBundle?.let { playgroundClasspathSupplier(it, workRoot, "cmp") }
    val androidSupplier = androidBundle?.let {
      playgroundClasspathSupplier(it, workRoot, "android")
    }
    if (cmpSupplier == null && androidSupplier == null && !engineRuntimeSelection) {
      // Both configured sources were rejected outright (an unknown system id) — the specific reason
      // is already on stderr from the supplier factory.
      System.err.println("serve: playground has no usable bundle source; playground disabled.")
      return null
    }

    val inProcessCompiler =
      PlaygroundBtaCompiler.fromInstall(java.io.File(workRoot, "bta-ic").toPath())
    // With a sandbox configured the compile also runs jailed, so a pathological snippet burns a
    // disposable child's budget. Falls back to in-process (loudly) when it can't be jailed.
    val compiler = inProcessCompiler?.let {
      val (implJars, pluginJars) =
        PlaygroundBtaCompiler.installJars()
          ?: (emptyList<java.io.File>() to emptyList<java.io.File>())
      PlaygroundJailedCompiler.wrap(
        sandbox = sandbox,
        inProcess = it,
        btaImplJars = implJars,
        compilerPluginJars = pluginJars,
        slots = playgroundCompileSlots,
      )
    }
    if (compiler == null) {
      System.err.println(
        "serve: playground compiler unavailable — no lib-bta/ in the CLI install (run from an " +
          "installed distribution). Playground disabled."
      )
      return null
    }

    // The shared Android daemon opener backs both the ANDROID first-frame render and REMOTE_COMPOSE
    // capture (which also needs the `/d/` store); without the sidecar both are unavailable and CMP
    // is unaffected. Built for the runtime selector too, so it can omit Android catalogs this host
    // can't render.
    val androidDaemonOpener =
      if (androidSupplier != null || engineRuntimeSelection)
        buildPlaygroundAndroidDaemonOpener(sandbox)
      else null
    val androidRender = androidDaemonOpener?.let { opener ->
      buildPlaygroundAndroidRenderService(workRoot, opener)
    }
    val rcCapture = androidDaemonOpener?.let { opener ->
      buildPlaygroundRcCaptureService(workRoot, docStore, opener)
    }

    // CMP's still first frame renders on the desktop (Skiko) daemon; without that sidecar CMP has
    // no still, but `/pg/` still renders live.
    val cmpDaemonOpener =
      if (cmpSupplier != null || engineRuntimeSelection) buildPlaygroundDesktopDaemonOpener(sandbox)
      else null
    val cmpRender = cmpDaemonOpener?.let { opener ->
      buildPlaygroundAndroidRenderService(workRoot, opener)
    }

    // UI-builder node-bounds capture per backend: a second short session reading
    // `compose/semantics` so the native pane can outline nodes. Absent with its backend, leaving
    // the picture without an overlay.
    val androidNodeBounds = androidDaemonOpener?.let {
      buildPlaygroundNodeBoundsService(workRoot, it)
    }
    val cmpNodeBounds = cmpDaemonOpener?.let { buildPlaygroundNodeBoundsService(workRoot, it) }

    // The runtime selector: a catalog is offerable once it publishes a verified liveBundle whose
    // backend this host can render; picking a catalog picks the whole compile target.
    val catalogTargets =
      if (!engineRuntimeSelection) null
      else
        PlaygroundCatalogTargets(
          available = {
            catalogLiveBundles.flatMap { (system, bundles) ->
              bundles.mapNotNull { live ->
                live.backend?.let { PlaygroundCatalogAvailable(live.id, system, live.module, it) }
              }
            }
          },
          modesForBackend = { backend ->
            PlaygroundCatalogTargets.naturalModes(backend).filter { mode ->
              when (mode) {
                // CMP compiles and streams without the desktop sidecar (it only adds the still
                // first frame), so a desktop catalog is always offerable.
                PlaygroundMode.CMP -> true
                PlaygroundMode.ANDROID -> androidRender != null
                PlaygroundMode.REMOTE_COMPOSE -> rcCapture != null
              }
            }
          },
          newSupplier = { id ->
            val system = id.substringBefore('@')
            PlaygroundClasspathSupplier(
              source = PlaygroundBundleSource.ServedCatalog(system),
              locateServedBundle = {
                catalogLiveBundles[system]?.firstOrNull { live -> live.id == id }?.file
              },
              resolve = { bundleFile -> resolvePlaygroundClasspath(bundleFile, workRoot, id) },
              onLog = { System.err.println("serve: playground catalog $id: $it") },
            )
          },
          limit = playgroundCatalogLimit,
          onLog = { System.err.println("serve: playground: $it") },
        )

    // Reports which modes are wired, not resolved; a mode whose bundle never materializes says so
    // per request.
    System.err.println(
      (if (publicPlayground) "serve: playground enabled (POST /api/1/compiler/run) — "
      else "serve: compile engine enabled for the UI builder (public playground off) — ") +
        listOfNotNull(
            cmpSupplier?.let { "cmp✓" },
            cmpRender?.let { "cmp-render✓" },
            androidSupplier?.let { "android✓" },
            androidRender?.let { "android-render✓" },
            rcCapture?.let { "remote-compose✓" },
            catalogTargets?.let { "catalog-selector✓(≤$playgroundCatalogLimit)" },
          )
          .joinToString(" ")
    )

    val snippetCounter = java.util.concurrent.atomic.AtomicLong()
    // Mint and redeem share one token store, so dropping a token deletes its work dir and releases
    // its live session. onRemove needs the redeem service, so it goes through a holder.
    val redeemRef = java.util.concurrent.atomic.AtomicReference<PlaygroundRedeemService?>()
    val tokenStore =
      PlaygroundTokenStore(onRemove = { token -> redeemRef.get()?.release(token.id) })
    // Keyed off the response's token, the caller's only handle on the compiled snippet. An expired
    // token or a backend without bounds capture yields an empty map.
    val captureNodeBounds: (PlaygroundRunResponse) -> Map<String, AnnotationBounds> = { response ->
      val snippet = response.previewToken?.let { tokenStore.get(it) }?.snippet
      val capture =
        when (snippet?.mode) {
          PlaygroundMode.ANDROID -> androidNodeBounds
          PlaygroundMode.CMP -> cmpNodeBounds
          else -> null
        }
      if (snippet == null || capture == null) emptyMap() else capture.capture(snippet)
    }
    val service =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          // A named catalog never falls back to the pinned default, which would report success
          // against the wrong design system.
          if (catalog != null) catalogTargets?.classpath(catalog, mode)
          else
            when (mode) {
              PlaygroundMode.CMP -> cmpSupplier?.classpath()
              // Advertise Android modes only when their daemon came up; otherwise the host would
              // compile and then mint a dead token.
              PlaygroundMode.ANDROID ->
                androidSupplier?.classpath()?.takeIf { androidRender != null }
              PlaygroundMode.REMOTE_COMPOSE ->
                androidSupplier?.classpath()?.takeIf { rcCapture != null }
            }
        },
        // Null, not an empty lambda, when the selector is off — the editor tells "no selector here"
        // from "selector configured, nothing loaded yet" by exactly this.
        catalogTargets = catalogTargets?.let { targets -> { targets.targets() } },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = tokenStore,
        newWorkDir = {
          java.io
            .File(workRoot, "snippet-${snippetCounter.incrementAndGet()}")
            .absolutePath
            .toPath()
        },
        // CMP renders on desktop, Android on Robolectric; REMOTE_COMPOSE never reaches here. A null
        // just omits the still.
        renderFirstFrameWithReason = { snippet ->
          when (snippet.mode) {
            PlaygroundMode.CMP -> cmpRender?.renderFrame(snippet)
            PlaygroundMode.ANDROID -> androidRender?.renderFrame(snippet)
            PlaygroundMode.REMOTE_COMPOSE -> null
          } ?: PlaygroundFirstFrame(null)
        },
        // Which served catalog each pinned mode compiles against, so browsing surfaces get a true
        // answer on a pin-only host. A local-file pin has no system id and answers null.
        pinnedCatalogSystem = { mode ->
          val supplier =
            when (mode) {
              PlaygroundMode.CMP -> cmpSupplier
              PlaygroundMode.ANDROID,
              PlaygroundMode.REMOTE_COMPOSE -> androidSupplier
            }
          supplier?.servedCatalogSystem
        },
        captureRemoteDocument = { snippet -> rcCapture?.capture(snippet) },
        publishRemoteDocument = { name, bytes, checked ->
          (docStore?.add(name, bytes, isSecurityChecked = checked) as? ServeDocStore.Result.Ok)
            ?.doc
            ?.path
        },
        // The lease is a playground editor feature; an engine-only host has no editor to hold one.
        editLeasesEnabled = playgroundEditing && repoAccessGated && publicPlayground,
        editLeaseTtlMillis = playgroundEditLeaseTtlSeconds * 1000,
      )
    if (playgroundEditing && !repoAccessGated) {
      System.err.println(
        "serve: --playground-editing requested without GitHub auth; the authenticated editing " +
          "lease is disabled."
      )
    } else if (service.editLeasesEnabled) {
      System.err.println(
        "serve: playground live editing trial enabled — one authenticated lease, " +
          "${playgroundEditLeaseTtlSeconds}s idle TTL"
      )
    }
    // No mode is wired (e.g. Android-only with no sidecar): disable the lane rather than offer an
    // empty selector. Checks wiring, not `availableModes`, which would force resolution before
    // catalogs load.
    val wiredModes =
      listOfNotNull(
        cmpSupplier?.let { PlaygroundMode.CMP },
        androidSupplier?.takeIf { androidRender != null }?.let { PlaygroundMode.ANDROID },
        androidSupplier?.takeIf { rcCapture != null }?.let { PlaygroundMode.REMOTE_COMPOSE },
      )
    // Only for the pinned configuration; a runtime-selector host has no modes until catalogs load
    // and reports that on the page.
    if (wiredModes.isEmpty() && catalogTargets == null) {
      System.err.println(
        "serve: playground resolved no runnable mode (a bundle source is configured but its " +
          "render backend is unavailable); playground disabled."
      )
      return null
    }
    // Stage-2 redemption stands compiled classes up as a live registry session.
    // materializePlaygroundSnippet self-gates on the backend, so this is always safe to enable.
    val redeem =
      PlaygroundRedeemService(
        tokenStore = tokenStore,
        registry = registry,
        materialize = { ServeBundleDaemon.materializePlaygroundSnippet(it, sandbox) },
      )
    redeemRef.set(redeem)

    // A redeemed `/pg` session never touches the token store, so its lazy purge would never fire;
    // sweep expired tokens on a timer so sessions die near their deadline.
    val purgePeriod = tokenStore.ttlSeconds.coerceIn(15L, 60L)
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "playground-token-purge").apply { isDaemon = true }
      }
      .scheduleWithFixedDelay(
        { runCatching { tokenStore.purgeExpired() } },
        purgePeriod,
        purgePeriod,
        java.util.concurrent.TimeUnit.SECONDS,
      )

    // What `/status.json` needs to diagnose a half-up playground: admission posture, jail
    // containment and each mode's resolution state. A lambda so rows are fresh; `isResolved` never
    // forces a resolve.
    val health = {
      PlaygroundHealth(
        admittedBy = admittedBy,
        sandboxProfile = sandbox.profile.id,
        sandboxActive = sandbox.isActive,
        jailDropped = sandbox.jailDropped,
        sandboxMemoryMb = sandbox.memoryMb,
        sandboxCpus = sandbox.cpus,
        sandboxTtlSeconds = sandbox.ttlSeconds,
        probe = probe,
        compilerJailed = compiler !== inProcessCompiler && !sandbox.jailDropped,
        compileSlots = playgroundCompileSlots,
        publicSurface = publicPlayground,
        modes = {
          listOfNotNull(
            cmpSupplier?.let {
              PlaygroundHealth.Mode(PlaygroundMode.CMP.name, it.describeSource(), it.isResolved)
            },
            androidSupplier
              ?.takeIf { androidRender != null }
              ?.let {
                PlaygroundHealth.Mode(
                  PlaygroundMode.ANDROID.name,
                  it.describeSource(),
                  it.isResolved,
                )
              },
            androidSupplier
              ?.takeIf { rcCapture != null }
              ?.let {
                PlaygroundHealth.Mode(
                  PlaygroundMode.REMOTE_COMPOSE.name,
                  it.describeSource(),
                  it.isResolved,
                )
              },
          )
        },
        catalogSelector =
          catalogTargets?.let { targets ->
            {
              PlaygroundHealth.CatalogSelector(
                offered = targets.targets().map { it.system },
                resolved = targets.resolvedCount(),
                limit = playgroundCatalogLimit,
              )
            }
          },
        editing = { service.editLeaseHealth() },
      )
    }
    return PlaygroundLane(
      compile = service,
      redeem = redeem,
      health = health,
      captureNodeBounds = captureNodeBounds,
      catalogBackend = { id -> catalogTargets?.targets()?.firstOrNull { it.id == id }?.backend },
      remoteComposeCatalog = {
        catalogTargets?.targets()?.firstOrNull { PlaygroundMode.REMOTE_COMPOSE in it.modes }?.id
      },
    )
  }

  /**
   * Per-caller compile budget, or null when disabled. Only compiles are metered: redemption needs a
   * freshly minted token and is already bounded by seats, the token cap and TTL.
   */
  private fun buildPlaygroundRateLimiter(): ServeRateLimiter? {
    if (playgroundRateLimit <= 0) {
      System.err.println(
        "serve: WARNING playground compile lane is UNMETERED (--playground-rate-limit 0). Its " +
          "remaining bounds are all whole-host ones, so one caller can hold every compile slot."
      )
      return null
    }
    System.err.println(
      "serve: playground compile budget — $playgroundRateLimit/min per caller, " +
        "$playgroundCallerConcurrency concurrent" +
        (if (trustForwardedFor) ", keyed by the last X-Forwarded-For entry when anonymous" else "")
    )
    return ServeRateLimiter(
      permitsPerWindow = playgroundRateLimit,
      windowSeconds = 60,
      maxConcurrent = playgroundCallerConcurrency,
    )
  }

  /**
   * The agent-grant store, or null when off or unsafe. Approval needs an identifiable operator (a
   * GitHub sign-in or the `--token` holder); a `--public` box without GitHub auth has neither, so
   * it is refused loudly rather than letting anyone approve credentials.
   */
  private fun buildAgentGrantStore(githubAuth: ServeGithubAuth?): ServeAgentGrantStore? {
    if (!agentGrants) return null
    if (public && githubAuth == null) {
      System.err.println(
        "serve: --agent-grants refused — a --public server with no GitHub auth has no way to tell " +
          "an operator from a visitor, so nobody could be said to have approved a grant. Add " +
          "--github-auth-* (any signed-in user then approves), or drop --public (the --token " +
          "holder then approves)."
      )
      throw IllegalArgumentException("--agent-grants needs an approver identity")
    }
    if (agentGrantMaxScope == AgentGrantScope.PLAYGROUND) {
      System.err.println(
        "serve: WARNING --agent-grant-scopes allows 'playground' — an approved agent can compile " +
          "and run Kotlin on this host."
      )
    }
    // An `images` capability without a configured image lane ([imageLaneConfigured], not just the
    // flag) would be approved and then 404; refuse at startup.
    if (AgentGrantCapability.IMAGES in agentGrantCapabilities && !imageLaneConfigured) {
      System.err.println(
        "serve: --agent-grant-capabilities images refused — this server does not run the image " +
          "lane, so a granted upload would have nowhere to go. Add --accept-images AND a " +
          "repository to gate it on (--image-upload-repo, or --github-auth-repo), or drop the " +
          "capability."
      )
      throw IllegalArgumentException("--agent-grant-capabilities images needs the image lane")
    }
    // The approver must hold what they pass on: for `images` that is access to the image lane's
    // repository, computed at sign-in alongside the OAuth repo bit
    // ([ServeGithubAuthConfig.imageRepository], `Approver.github`), so the two repositories may
    // differ.
    if (agentGrantCapabilities.isNotEmpty()) {
      System.err.println(
        "serve: agent grants may carry " +
          AgentGrantCapability.wireNames(agentGrantCapabilities).joinToString(", ") +
          " when a human ticks it — an approved agent can then upload without a GitHub credential."
      )
    }
    return ServeAgentGrantStore(
      maxGrantTtlSeconds = agentGrantMaxTtlSeconds,
      maxScope = agentGrantMaxScope,
      maxCapabilities = agentGrantCapabilities,
      maxActiveGrants = agentGrantMaxActive,
      // Audit trail for minted credentials; only token fingerprints are logged.
      audit = { line -> System.err.println("serve: $line") },
    )
  }

  private fun buildAgentGrantRateLimiter(): ServeRateLimiter? {
    if (agentGrantRateLimit <= 0) {
      System.err.println(
        "serve: WARNING agent-grant routes are UNMETERED (--agent-grant-rate-limit 0). " +
          "/agent-access/request is reachable without any credential."
      )
      return null
    }
    return ServeRateLimiter(
      permitsPerWindow = agentGrantRateLimit,
      windowSeconds = 60,
      // An agent polls while its human reads the page; the rate bucket bounds the lane, concurrency
      // only stops thread pinning.
      maxConcurrent = ServeDefaults.AGENT_GRANT_CALLER_CONCURRENCY,
    )
  }

  /** The playground's Stage-1 compile lane + Stage-2 redeem lane, sharing one token store. */
  private class PlaygroundLane(
    val compile: PlaygroundCompileService,
    val redeem: PlaygroundRedeemService,
    /** Read by `/status.json` to report why the lane is (or isn't) fully up. */
    val health: () -> PlaygroundHealth,
    /**
     * Where each tagged node drew on a compiled snippet's frame, for the UI-builder's native pane.
     * Empty on a backend with no semantics producer.
     */
    val captureNodeBounds: (PlaygroundRunResponse) -> Map<String, AnnotationBounds> = {
      emptyMap()
    },
    /**
     * A served catalog's bundle backend (`desktop`/`android`), or null. Read per call because
     * catalogs load after the lane is wired.
     */
    val catalogBackend: (String) -> String? = { null },
    /**
     * A served catalog this host can compile a `@RemoteComposable` body against (any selector
     * target offering `remote-compose`), or null. Any works, since inline bodies use the Remote
     * Compose vocabulary. Null on pin-only hosts, because a capture must name a catalog and the
     * lane never falls back to a pin.
     */
    val remoteComposeCatalog: () -> String? = { null },
  )

  /**
   * Lazy classpath supplier for one mode's bundle flag, or null (logged) when it can't name a
   * bundle. Only an unknown served-catalog id fails at startup; everything else is deferred to
   * [PlaygroundClasspathSupplier] because catalogs aren't fetched yet (#3212).
   */
  private fun playgroundClasspathSupplier(
    raw: String,
    workRoot: java.io.File,
    mode: String,
  ): PlaygroundClasspathSupplier? {
    val source = PlaygroundBundleSource.parse(raw)
    if (source is PlaygroundBundleSource.ServedCatalog) {
      // Compared against the CONFIGURED catalog set, not the loaded one: nothing has loaded yet.
      val configured = catalogRefs.map { it.system }
      if (source.system !in configured) {
        System.err.println(
          "serve: playground $mode bundle '${source.system}' is neither a readable file nor a " +
            "catalog this server is configured to serve" +
            (if (configured.isEmpty()) " (no --catalogs configured)"
            else " (configured: ${configured.sorted().joinToString(", ")})") +
            ". That mode is disabled — pass a .bundle path, or a served system id."
        )
        return null
      }
    }
    return PlaygroundClasspathSupplier(
      source = source,
      locateServedBundle = { catalogLiveBundles[it]?.firstOrNull()?.file },
      resolve = { bundleFile -> resolvePlaygroundClasspath(bundleFile, workRoot, mode) },
      onLog = { System.err.println("serve: playground $mode — $it") },
    )
  }

  /**
   * Unpack [bundleFile] into `<workRoot>/catalog-<label>` and resolve its compile classpath,
   * logging the outcome. Shared by pinned and per-catalog suppliers. Honours `--extra-maven-repos`,
   * since the resolver fails closed on unresolved coordinates.
   */
  private fun resolvePlaygroundClasspath(
    bundleFile: java.io.File,
    workRoot: java.io.File,
    label: String,
  ): PlaygroundCompileService.Classpath? =
    PlaygroundCatalogClasspath.resolve(
        bundleFile = bundleFile,
        destDir = java.io.File(workRoot, "catalog-$label"),
        system = "playground-$label",
        extraMavenRepos = extraMavenRepos,
        onLog = { System.err.println("serve playground: $it") },
      )
      .also {
        if (it == null) {
          System.err.println(
            "serve: playground could not resolve a $label classpath from " +
              "${bundleFile.absolutePath}; that target is unavailable."
          )
        } else {
          System.err.println(
            "serve: playground $label classpath resolved from ${bundleFile.absolutePath}"
          )
        }
      }

  /**
   * The Android/Robolectric daemon opener ([PlaygroundDaemonOpeners.android]), or null (logged)
   * when the sidecar or `android.jar` is missing.
   */
  private fun buildPlaygroundAndroidDaemonOpener(
    sandbox: PlaygroundSandbox
  ): PlaygroundAndroidSessionOpener? =
    PlaygroundDaemonOpeners.android(sandbox) {
      System.err.println("serve: playground Android modes disabled — $it")
    }

  /**
   * The desktop daemon opener ([PlaygroundDaemonOpeners.desktop]) for CMP's first frame, or null
   * (logged) when its jars are absent.
   */
  private fun buildPlaygroundDesktopDaemonOpener(
    sandbox: PlaygroundSandbox
  ): PlaygroundAndroidSessionOpener? =
    PlaygroundDaemonOpeners.desktop(sandbox) {
      System.err.println("serve: playground CMP first-frame unavailable — $it")
    }

  /**
   * First-frame render backend: renders a compiled snippet on [opener] (desktop or Robolectric) and
   * returns the still PNG.
   */
  private fun buildPlaygroundAndroidRenderService(
    workRoot: java.io.File,
    opener: PlaygroundAndroidSessionOpener,
  ): PlaygroundAndroidRenderService {
    val renderCounter = java.util.concurrent.atomic.AtomicLong()
    return PlaygroundAndroidRenderService(
      openSession = opener,
      newWorkDir = { java.io.File(workRoot, "android-render-${renderCounter.incrementAndGet()}") },
    )
  }

  /**
   * Node-bounds capture: renders a snippet with `compose/semantics` and projects `testTag → box in
   * render pixels`.
   */
  private fun buildPlaygroundNodeBoundsService(
    workRoot: java.io.File,
    opener: PlaygroundAndroidSessionOpener,
  ): PlaygroundNodeBoundsService {
    val boundsCounter = java.util.concurrent.atomic.AtomicLong()
    return PlaygroundNodeBoundsService(
      openSession = opener,
      newWorkDir = { java.io.File(workRoot, "node-bounds-${boundsCounter.incrementAndGet()}") },
    )
  }

  /**
   * REMOTE_COMPOSE capture: renders a snippet on [opener] and captures its `.rc`. Null (logged)
   * without the `/d/` store.
   */
  private fun buildPlaygroundRcCaptureService(
    workRoot: java.io.File,
    docStore: ServeDocStore?,
    opener: PlaygroundAndroidSessionOpener,
  ): PlaygroundRcCaptureService? {
    if (docStore == null) {
      System.err.println(
        "serve: playground remote-compose mode needs the /d/ document store — enable it with " +
          "--accept-docs. Remote-compose mode disabled."
      )
      return null
    }
    val captureCounter = java.util.concurrent.atomic.AtomicLong()
    return PlaygroundRcCaptureService(
      openSession = opener,
      newWorkDir = { java.io.File(workRoot, "rc-capture-${captureCounter.incrementAndGet()}") },
    )
  }

  /** Build the `--accept-bundles` upload store (temp-dir backed), wired to [registry]. */
  private fun openUploadStore(registry: ServeSessionRegistry): ServeBundleStore {
    val uploads =
      java.nio.file.Files.createTempDirectory("serve-uploads").toFile().also { it.deleteOnExit() }
    if (acceptBundlesFrom.isEmpty()) {
      System.err.println(
        "serve: --accept-bundles accepts uploads only; no ?url= host is allowed (SSRF fail " +
          "closed). Pass --accept-bundles-from <host>[,<host>…] to permit URL fetches."
      )
    }
    return ServeBundleStore(
      root = uploads,
      register = { id, bundleHost -> registry.register(id, host = bundleHost, pinned = true) },
      allowedHosts = acceptBundlesFrom,
      trust = { trustStore.get() },
    )
  }

  /**
   * In-browser Wasm apps: those carried by served catalogs plus `--wasm-dir` overrides (which win).
   * Returns the live map, so runtime catalog publishes and retirements apply immediately.
   */
  private fun mergedWasmCatalogs(reg: CatalogRegistration?): MutableMap<String, File> {
    val live = reg?.wasm ?: java.util.concurrent.ConcurrentHashMap()
    live.putAll(localWasm)
    return live
  }

  /**
   * `--wasm-dir` overrides resolved once; they win over catalogs and need no re-check on refresh.
   */
  private val localWasm: Map<String, File> by lazy { filterLocalWasm() }

  /**
   * Keep only `--wasm-dir` entries whose directory actually holds the assembled app (index.html).
   */
  private fun filterLocalWasm(): Map<String, File> = wasmDirs.filter { (system, dir) ->
    val ok = File(dir, "index.html").isFile
    if (!ok) {
      System.err.println(
        "serve: --wasm-dir $system=${dir.path} has no index.html — skipping (build it with " +
          ":samples:cmp-wasm-catalog:wasmCatalogDist)."
      )
    }
    ok
  }

  /** Validate the one packaged browser once rather than turning every missing asset into noise. */
  private fun usableWasmUiDir(): File? {
    val dir = wasmUiDir ?: return null
    if (File(dir, "index.html").isFile) return dir
    System.err.println("serve: --wasm-ui-dir ${dir.path} has no index.html — skipping")
    return null
  }

  /** Validate the independently packaged builder once; it is not a catalog Wasm fallback. */
  private fun usableBundledUiBuilderDir(): File? {
    val dir = uiBuilderDir ?: return null
    if (File(dir, "index.html").isFile) return dir
    System.err.println("serve: --ui-builder-dir ${dir.path} has no index.html — skipping")
    return null
  }

  /**
   * Where pinned editors are cached: beside `catalogs.json` (the durable volume on the image), so
   * restarts don't re-download. Null without a catalogs file.
   */
  private val uiBuilderEditorStore: ServeUiBuilderEditorStore? by lazy {
    catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("ui-builder-editors")?.let {
      ServeUiBuilderEditorStore(it)
    }
  }

  /** Which editor this process is serving; filled by [usableUiBuilderDir]. */
  private var uiBuilderEditorState: ServeUiBuilderEditorState =
    ServeUiBuilderEditorState(bundledVersion = null, servingPin = null, servingVersion = null)

  /**
   * The editor directory: the `catalogs.json` pin when it fetches and verifies, else the bundled
   * one. A failed pin is logged and never takes the builder down.
   */
  private fun usableUiBuilderDir(): File? {
    val bundled = usableBundledUiBuilderDir()
    val bundledVersion = bundled?.let { ServeUiBuilderEditor.readManifest(it)?.version }
    uiBuilderEditorState = ServeUiBuilderEditorState(bundledVersion, null, bundledVersion)
    val pin = catalogsConfig.editor ?: return bundled
    val store = uiBuilderEditorStore ?: return bundled
    if (ServeCatalogsConfig.validateEditor(pin) != null) return bundled // reported by problems()
    return when (val resolved = store.resolve(pin)) {
      is ServeUiBuilderEditorStore.Result.Ready -> {
        resolved.warning?.let { System.err.println("serve: $it") }
        System.err.println(
          "serve: UI-builder editor ${pin.version} (pinned in catalogs.json)" +
            (bundledVersion?.let { "; bundled is $it" } ?: "")
        )
        uiBuilderEditorState = ServeUiBuilderEditorState(bundledVersion, pin, pin.version)
        runCatching { store.prune(keep = resolved.dir) }
        resolved.dir
      }
      is ServeUiBuilderEditorStore.Result.Failed -> {
        System.err.println(
          "serve: pinned UI-builder editor ${pin.version} unavailable (${resolved.reason}) — " +
            "serving the bundled editor" +
            (bundledVersion?.let { " $it" } ?: "")
        )
        bundled
      }
    }
  }

  /**
   * The authoritative design service, opened only alongside the packaged builder app;
   * [ServeHttpServer] is the transport. An unwritable explicit directory is a startup error, not an
   * in-memory downgrade, since clients rely on persistence.
   */
  private data class UiBuilderLane(
    val service: PersistentUiBuilderService,
    val renderer: AutoCloseable?,
    /**
     * Reference overlays, beside (not inside) the state file, which is rewritten on every operation
     * while references are megabytes.
     */
    val references: ServeUiBuilderReferenceStore?,
    /**
     * Comment threads, in their own directory so replies and state writes never rewrite each other.
     */
    val comments: ServeUiBuilderCommentStore?,
    /**
     * Design back-links, beside the state: recording one must not advance the revision open clients
     * hold.
     */
    val links: ServeUiBuilderLinksStore?,
    /** Review verdicts and implementing pull requests, beside the state for the links' reasons. */
    val reviews: ServeUiBuilderReviewStore?,
    /** Each design's latest guidelines result, beside the state for the same reasons. */
    val guidelineRecords: ServeUiBuilderGuidelineStore? = null,
    /** Each builder catalog's own `ui-builder.guidelines.json`, read with its published catalog. */
    val catalogGuidelines: ServeCatalogGuidelines = ServeCatalogGuidelines(),
    /** Shared file-manager folders, stored beside design state without changing revisions. */
    val folders: ServeUiBuilderFolderStore?,
    val projects: ServeUiBuilderProjectStore? = null,
    /**
     * Components this host's editors publish; a project's own `ui-builder/components/` belongs to
     * its repository.
     */
    val components: ServeUiBuilderComponentStore? = null,
    /** The design listing's card pictures, drawn ahead of the reader and kept across restarts. */
    val thumbnails: ServeUiBuilderThumbnails?,
    /**
     * Recompose every builder catalog a delivery system supplies and swap the service onto the
     * result; called when that branch moves.
     */
    val refreshPublished: (sourceSystem: String) -> Unit,
    /** What a new design starts as: built in, or the published templates of a catalog-owned one. */
    val seeds: UiBuilderCatalogSeeds = UiBuilderCatalogSeeds.BUILT_IN,
    /**
     * The Compose exporter, kept so the native lane can call it with node tagging (the service's
     * exporter may wrap several formats).
     */
    val compose: ScreenGeneratorComposeExportExecutor,
    /**
     * Which daemon each catalog's designs render on, from the catalog's own declaration
     * ([UiBuilderPreviewSurfaces]), so it is known before any bundle loads.
     */
    val nativeBackends: Map<String, String>,
    /**
     * `ui_builder_validate`: a scratch service over [service]'s catalogs and exporter, answering
     * with the real refusal and writing nothing.
     */
    val validator: UiBuilderDraftValidator,
    /** The state directory itself, for the stores that live beside the builder's (Web Push). */
    val stateDirectory: File? = null,
  ) : AutoCloseable {
    override fun close() {
      thumbnails?.close()
      renderer?.close()
    }
  }

  /**
   * The UI-builder lane, or null when it could not be opened. Nothing about the builder may stop
   * `serve` binding its port, except a bad `--ui-builder-migrate-state` argument. Every later
   * failure disables the lane and prints [uiBuilderDisabledWarning].
   */
  /** For [ServeGoogleFonts]' fetches on the renderer's behalf: the font route's own timeouts. */
  private val googleFontsHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  }

  private fun openUiBuilderService(
    appDirectory: File?,
    /** The served catalogs' store, for a pack's record and a served catalog's export record. */
    catalogStore: ServeCatalogStore? = null,
    /** Which repository each served catalog is fetched from, for the same reason. */
    catalogLoads: CatalogLoadTracker? = null,
  ): UiBuilderLane? {
    if (uiBuilderMigrateState && (appDirectory == null || uiBuilderStateDirFlag == "none")) {
      throw IllegalStateException(
        "--ui-builder-migrate-state requires an enabled UI-builder app and durable state"
      )
    }
    if (appDirectory == null || uiBuilderStateDirFlag == "none") return null
    val directory =
      uiBuilderStateDirFlag?.let(::File)
        ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("ui-builder-state")
        ?: File(System.getProperty("user.home"), ".compose-preview/ui-builder-state")
    // Anything the lane opens before it fails is closed here: a renderer left running would hold a
    // daemon and a directory for the life of a process that is no longer using either.
    val opened = AtomicReference<AutoCloseable?>(null)
    return try {
      openUiBuilderLane(
        directory,
        catalogStore,
        catalogLoads,
        opened::set,
        // The editor's Google Fonts cache, which the renderer draws a design's typefaces from.
        fonts = ServeGoogleFonts.overHttp(appDirectory, googleFontsHttpClient),
        appDirectory = appDirectory,
      )
    } catch (failure: Exception) {
      runCatching { opened.get()?.close() }
      System.err.println(uiBuilderDisabledWarning(directory, failure))
      null
    }
  }

  /**
   * Opens the lane, throwing on any failure; [openUiBuilderService] is what makes that survivable.
   */
  private fun openUiBuilderLane(
    directory: File,
    catalogStore: ServeCatalogStore?,
    catalogLoads: CatalogLoadTracker?,
    registerCloseable: (AutoCloseable?) -> Unit,
    fonts: ServeGoogleFonts? = null,
    /** The builder distribution, for the new-design fixture a shadow report seeds from. */
    appDirectory: File? = null,
  ): UiBuilderLane {
    if (!(directory.isDirectory || directory.mkdirs()) || !directory.canWrite()) {
      throw IllegalStateException("UI-builder state directory is not writable: $directory")
    }
    // Owner-only, so the designs, comments and access lists under it are not readable by other
    // accounts on the host; see [ServeOwnerOnlyFiles].
    ServeOwnerOnlyFiles.restrictDirectory(directory.toPath())
    System.err.println("serve: UI-builder design API persisting to ${directory.absolutePath}")
    // Whether Compose export survives a renderer failure is the same question the capability below
    // answers, so it is asked once here. Missing catalogs are named, not counted, because
    // `composeCode` is all-or-none. Record-free catalogs (`remote-m3`, `wear-m3`, the packaged A2UI
    // catalog) generate without a record and are neither reported nor gated.
    val catalogsWithoutRecords = uiBuilderCatalogs.filterNot {
      it in uiBuilderComponents.keys ||
        it in RecordFreeExport.CATALOG_SYSTEM_IDS ||
        it == CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID
    }
    val composeExportConfigured = catalogsWithoutRecords.isEmpty()
    val renderer = runCatching {
      ServeUiBuilderRenderPort.open(directory.resolve("renderer").toPath(), fonts)
    }
      .onFailure { failure ->
        System.err.println(
          "serve: UI-builder PNG/SVG renderer unavailable (${failure.message}); " +
            if (composeExportConfigured) "Compose export remains enabled"
            else
              "and ${catalogsWithoutRecords.sorted().joinToString(", ")} " +
                (if (catalogsWithoutRecords.size == 1) "has" else "have") +
                " no component record, so this host offers no export at all " +
                "(pass --ui-builder-components <catalog>=<components.json>)"
        )
      }
      .getOrNull()
    registerCloseable(renderer)
    // The Compose exporter is constructed here, not inside the runtime, because
    // `checkUiBuilderRuntimeBoundary` keeps `preview-discovery` off that module's classpath;
    // `:server` may hold both.
    // [startupRecords]: served catalogs' records fetched at startup for packs (the catalogs
    // themselves load later), kept as the export fallback until a generation is published.
    val startupRecords = ConcurrentHashMap<String, File>()
    // The catalogs whose published file composed against their own delivery-branch record; that
    // record, not a configured one, is then the catalog's (see `composePublished`).
    val publishedRecordCatalogs = ConcurrentHashMap.newKeySet<String>()
    val records =
      ComponentRecordSource(
        uiBuilderComponents,
        preferServed = { it in publishedRecordCatalogs },
      ) { system ->
        uiBuilderCatalogRecord(
          system,
          uiBuilderNativeCatalogs,
          served = { catalogStore?.componentRecord(it) },
          startup = { startupRecords[it] },
        )
      }
    if (catalogStore != null) {
      uiBuilderPacks.keys
        .filterNot { it in uiBuilderComponents }
        .forEach { packId ->
          val config = catalogLoads?.stateFor(packId)?.config
          val fetched =
            catalogStore.fetchComponentRecord(
              system = packId,
              sourceRepo = config?.repo,
              sourceBranchPrefix = config?.branch?.removeSuffix(packId),
            )
          if (fetched != null) startupRecords[packId] = fetched
        }
    }
    // A pack is projected once at startup, since every open design validates against it and the
    // shelf must not change underneath. A named but unreadable record is a startup failure
    // (operator typo); a served catalog with no record is a warning and an absent shelf.
    val packs = uiBuilderPacks.mapNotNull { (packId, platform) ->
      val record =
        when (val lookup = records.record(packId)) {
          is ComponentRecordSource.Lookup.Found -> lookup.record
          ComponentRecordSource.Lookup.Unconfigured -> {
            System.err.println(
              "serve: UI-builder pack $packId is not offered — no component record: the served " +
                "catalog publishes none (or is not served here), and none was passed as " +
                "`--ui-builder-components $packId=<components.json>`"
            )
            return@mapNotNull null
          }
          is ComponentRecordSource.Lookup.Unusable ->
            if (records.isConfigured(packId)) {
              throw IllegalArgumentException(
                "--ui-builder-packs admits `$packId`, and its component record could not be " +
                  "loaded: ${lookup.reason}"
              )
            } else {
              System.err.println(
                "serve: UI-builder pack $packId is not offered — the served catalog's component " +
                  "record could not be loaded: ${lookup.reason}"
              )
              return@mapNotNull null
            }
        }
      val derived =
        ComponentRecordPacks.derive(
          packId = packId,
          platform =
            requireNotNull(UiBuilderCatalogPlatform.fromWord(platform)) {
              "--ui-builder-packs names an unknown platform `$platform` for `$packId`"
            },
          record = record,
        )
      System.err.println(
        "serve: UI-builder pack $packId offers ${derived.source.components.size} " +
          "components to $platform designs" +
          if (derived.skipped.isEmpty()) ""
          else " (${derived.skipped.size} left out: ${derived.skipped.joinToString("; ")})"
      )
      derived.source
    }
    // Uploaded asset bytes, content-addressed, beside (not inside) the state file. The export
    // executor reads the same store so daemon renders match the canvas and Wear widgets can inline
    // background pictures.
    val assetStore = runCatching {
      FileUiBuilderAssetStore(directory.resolve("assets").toPath())
    }
      .onFailure {
        System.err.println(
          "serve: UI-builder asset store unavailable (${it.message}); " +
            "the builder works, and a design cannot hold an uploaded image"
        )
      }
      .getOrNull()
    // What each published catalog composed, for the record-free emitters. An accessor because the
    // executor must be built before composition (its capabilities feed it).
    val publishedRecords = ConcurrentHashMap<String, Map<String, ComponentRecord>>()
    val catalogPlatforms = ConcurrentHashMap<String, UiBuilderCatalogPlatform>()
    val compose =
      ScreenGeneratorComposeExportExecutor(
        records::record,
        packs = packs.map { it.id }.toSet(),
        assetStore = assetStore,
        publishedComponents = { systemId -> publishedRecords[systemId].orEmpty() },
        catalogPlatform = { systemId ->
          catalogPlatforms[systemId] ?: UiBuilderCatalogPlatform.DEFAULT
        },
      )
    val pictureExporter =
      renderer?.let { ProductionUiBuilderExportExecutor(it, compose, assets = assetStore) }
        ?: compose
    // Enabled catalogs that publish `ui-builder.json` are served from it. Read at startup from the
    // delivery branch; per catalog and reversible, so anything that won't compose keeps the
    // synthesised catalog and logs why.
    // One export-capability value, shared by published catalogs and the executor, so published
    // catalogs advertise exactly what the renderer supports.
    val documentExporter =
      if (UiBuilderBuildFeatures.remoteCompose) RemoteDocumentExportExecutor(pictureExporter)
      else null
    // An A2UI catalog's JSON (its A2UI messages) is written whatever the Remote Compose flag says;
    // every other design passes through to the Remote Compose / picture chain below it.
    val exporter =
      A2uiJsonExportExecutor(
        documentExporter?.let { RemotePngExportExecutor(it, renderer, fonts) } ?: pictureExporter
      )
    val pictureExports =
      ((pictureExporter as? ProductionUiBuilderExportExecutor)?.capabilities
          ?: ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = true
              it.svg = false
              it.png = false
            }
            .build())
        .newBuilder()
        .also { it.composeCode = composeExportConfigured }
        .build()
    // `remoteJson`: the executor writes JSON in every build; the runtime narrows it per catalog
    // (A2UI always, Remote Compose only with `-PuiBuilderRemoteCompose=true`).
    val uiBuilderExports =
      RemoteDocumentExportSupport.capabilities(
          pictureExports,
          json = true,
          document = documentExporter?.supportsBinary == true,
        )
        .newBuilder()
        .also { it.remoteJson = true }
        .build()
    val publishedCatalogs = ConcurrentHashMap<String, CatalogCapabilityV1>()
    val publishedRuntimeIds = ConcurrentHashMap<String, String>()
    // The catalog-owned cutover (compose-ui-builder's UI_BUILDER_CATALOG_CUTOVER.md); every read
    // below is a no-op at `none`.
    val ownership = options.uiBuilderCatalogOwnership
    if (!ownership.isNone) {
      System.err.println(
        "serve: UI-builder catalogs owned by their repositories " +
          "(--ui-builder-catalog-ownership ${ownership.wireValue}): ${
            uiBuilderCatalogs.filter(ownership::owns).sorted().joinToString()
          } seed from their published templates and export by their own declaration"
      )
    }
    val publishedTemplates = ConcurrentHashMap<String, CatalogSeedTemplates>()
    // Null allows every enabled catalog, empty turns the path off, a named set opts in one at a
    // time.
    val publishedAllowed = options.uiBuilderPublishedCatalogs
    if (publishedAllowed != null) {
      // Logged once, since "no published file" and "told not to read it" are otherwise
      // indistinguishable.
      val withheld = uiBuilderCatalogs.filterNot(publishedAllowed::contains).sorted()
      System.err.println(
        if (publishedAllowed.isEmpty())
          "serve: no UI-builder catalog reads its published ui-builder.json " +
            "(--ui-builder-published-catalogs none); every catalog keeps its built-in definition"
        else
          "serve: only ${publishedAllowed.sorted().joinToString()} may read a published " +
            "ui-builder.json; ${withheld.joinToString()} keep their built-in definition"
      )
    }
    // Shadow step before ownership: served unchanged, but each composition reports what ownership
    // would refuse or change.
    val shadowed =
      options.uiBuilderShadowCatalogs.filterTo(mutableSetOf()) {
        it in uiBuilderCatalogs && !ownership.owns(it)
      }
    // Every template reads its environment from the same fixture a new design does.
    val shadowFixture by lazy {
      appDirectory
        ?.resolve(ServeUiBuilderCreate.NEW_DESIGN_FIXTURE)
        ?.takeIf { it.isFile }
        ?.let { file -> runCatching { Json.parseToJsonElement(file.readText()).jsonObject } }
        ?.getOrNull()
    }
    fun reportShadow(
      systemId: String,
      composed: PublishedUiBuilderCatalog.Result.Composed,
      templates: Map<String, String?>,
      runtimeId: String?,
    ) {
      val fixture = shadowFixture
      if (fixture == null) {
        System.err.println(
          "serve: UI-builder catalog $systemId shadow: no ${ServeUiBuilderCreate.NEW_DESIGN_FIXTURE} " +
            "in the builder distribution, or unreadable, to seed its templates from; not reported"
        )
        return
      }
      // A report that cannot be made must not take the catalog down with it: it is served either
      // way, and the shadow is advice.
      val report = runCatching {
        val read = CatalogSeedTemplates.read(systemId, templates.keys.toList()) { templates[it] }
        CatalogCutoverShadow.report(
          catalogId = systemId,
          published = composed.catalog,
          templates = (read as? CatalogSeedTemplates.Result.Read)?.templates,
          fixture = fixture,
          packComponents = composed.records,
          exportRecord = compose.exportRecord(systemId),
          nativeRuntimeId = runtimeId,
        )
      }
        .getOrElse {
          System.err.println("serve: UI-builder catalog $systemId shadow failed: ${it.message}")
          uiBuilderShadowReports.remove(systemId)
          return
        }
      uiBuilderShadowReports[systemId] =
        ServeUiBuilderShadowReportDto(
          report.ready,
          report.findings,
          report.differences,
          report.losses,
        )
      System.err.println(
        "serve: UI-builder catalog $systemId shadow: " +
          (if (report.ready) "ready to own"
          else
            "${report.findings.size} finding(s) and " +
              "${report.losses.orEmpty().size} loss(es) before owning") +
          ", " +
          (report.differences?.let { "${it.size} difference(s) from its Kotlin catalog" }
            ?: "no Kotlin catalog to compare")
      )
      // A loss is marked so a log read alone says what blocks owning, not just what changes.
      val losses = report.losses.orEmpty().toSet()
      report.findings.forEach { System.err.println("serve:   $it") }
      report.differences.orEmpty().forEach {
        System.err.println("serve:   ${if (it in losses) "[loss] " else ""}$it")
      }
    }
    // Compose one catalog's published definition into the maps below; a function because the
    // refresher calls it again when the branch moves. True when it composed.
    val catalogGuidelines = ServeCatalogGuidelines()
    // An owned catalog that cannot be composed is withheld, never quietly replaced.
    val health = UiBuilderOwnedCatalogHealth(ownership::owns).also { uiBuilderCatalogHealth = it }
    fun composePublished(systemId: String, refresh: Boolean = false): Boolean {
      if (catalogStore == null || publishedAllowed?.contains(systemId) == false) {
        health.withhold(
          systemId,
          if (catalogStore == null) "this box has no catalog store to read its published file from"
          else "--ui-builder-published-catalogs does not let it read its published file",
        )
        return false
      }
      // A catalog served under another name (`wear-m3` from `wear-m3-catalog`) keeps its record
      // under the builder id, where the delivery branch's reload never replaces it: fetch it again.
      if (refresh) startupRecords.remove(systemId)
      // Compose under the delivery system that supplies the Builder catalog (they differ for Wear);
      // see [uiBuilderPublishedSourceSystem].
      val sourceSystem = uiBuilderPublishedSourceSystem(systemId, uiBuilderNativeCatalogs)
      val config = catalogLoads?.stateFor(sourceSystem)?.config
      val published =
        catalogStore.fetchUiBuilderCatalog(
          system = sourceSystem,
          sourceRepo = config?.repo,
          sourceBranchPrefix = config?.branch?.removeSuffix(sourceSystem),
          templates = ownership.owns(systemId) || systemId in shadowed,
        )
          ?: run {
            health.withhold(
              systemId,
              "no published ui-builder.json could be read from $sourceSystem's delivery branch",
            )
            return false
          }
      // The catalog's own design guidance, published beside it. A republish that drops the file
      // drops the catalog's guidelines too, and the check falls back to the bundled rules.
      val guidelinesBytes = published.guidelines
      val guidelinesUrl = published.guidelinesUrl
      if (guidelinesBytes != null && guidelinesUrl != null) {
        if (catalogGuidelines.accept(systemId, guidelinesBytes, guidelinesUrl)) {
          System.err.println("serve: UI-builder catalog $systemId publishes its own guidelines")
        }
      } else {
        catalogGuidelines.remove(systemId)
      }
      // Fetch the catalog's own record now if it has never loaded; otherwise a cold start composes
      // the policy against nothing and quietly serves a near-empty shelf. Fetched even when a
      // record is configured: the published one is tried first and wins only if it composes. Keyed
      // by delivery system ([uiBuilderCatalogRecord]).
      if (catalogStore.componentRecord(sourceSystem) == null && startupRecords[systemId] == null) {
        catalogStore
          .fetchComponentRecord(
            system = sourceSystem,
            sourceRepo = config?.repo,
            sourceBranchPrefix = config?.branch?.removeSuffix(sourceSystem),
          )
          ?.let { startupRecords[systemId] = it }
      }
      // The same record the export reads, through the same source, so the shelf the builder
      // offers and the code the export writes cannot disagree about what a component is.
      publishedRecordCatalogs += systemId
      val record = (records.record(systemId) as? ComponentRecordSource.Lookup.Found)?.record
      when (
        val composed =
          PublishedUiBuilderCatalog.compose(published.file.readText(), record, uiBuilderExports)
      ) {
        is PublishedUiBuilderCatalog.Result.Composed -> {
          publishedCatalogs[systemId] = composed.catalog
          // The runtime is the executable half of the document pin; never substitute a host
          // default. A republish that drops its runtime returns to the built-in renderer.
          published.runtimeId?.let { publishedRuntimeIds[systemId] = it }
            ?: publishedRuntimeIds.remove(systemId)
          // The published file maps builder ids to record components; without it the Remote
          // emitter's record fallback is unreachable.
          publishedRecords[systemId] = composed.records
          if (systemId in shadowed) {
            reportShadow(systemId, composed, published.templates, published.runtimeId)
          }
          if (ownership.owns(systemId)) {
            when (
              val read =
                CatalogSeedTemplates.read(systemId, published.templates.keys.toList()) {
                  published.templates[it]
                }
            ) {
              is CatalogSeedTemplates.Result.Read -> {
                publishedTemplates[systemId] = read.templates
                health.templatesRead(systemId)
              }
              is CatalogSeedTemplates.Result.Unusable -> {
                // Owned, so there is no built-in seed to fall back to: the catalog is offered the
                // generic blank until it republishes, and reported as degraded.
                publishedTemplates.remove(systemId)
                health.degrade(systemId, read.reason)
              }
            }
          }
          if (health.isWithheld(systemId)) health.healthy(systemId)
          System.err.println("serve: UI-builder catalog ${composed.note}")
        }
        is PublishedUiBuilderCatalog.Result.Unusable -> {
          if (ownership.owns(systemId)) {
            health.withhold(systemId, composed.reason)
          } else {
            System.err.println(
              "serve: UI-builder catalog $systemId keeps its built-in definition — " +
                composed.reason
            )
          }
          // The served record did not compose this file, so a configured one stays the catalog's.
          publishedRecordCatalogs -= systemId
          // On a refresh this is a fall back, not a no-op: the policy an earlier publish composed
          // must not outlive a publish that replaced it with something unusable.
          publishedCatalogs.remove(systemId)
          publishedRuntimeIds.remove(systemId)
          publishedRecords.remove(systemId)
          // The seeds belonged to the publication that just stopped composing.
          publishedTemplates.remove(systemId)
          uiBuilderShadowReports.remove(systemId)
          return false
        }
      }
      return true
    }
    uiBuilderCatalogs.forEach { composePublished(it) }
    fun buildCatalogExecutor(): UiBuilderCatalogExecutor =
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .also {
          // An owned catalog that did not compose is left out rather than allowed to fail the
          // whole builder: compose-ui-builder refuses an owned catalog with no published file.
          it.catalogSystemIds = health.served(uiBuilderCatalogs)
          it.published = publishedCatalogs
          it.nativeRuntimeIds = publishedRuntimeIds
          it.catalogOwnership = ownership
          // `composeCode` answers a configuration question (is a record configured for this
          // catalog?), not a filesystem one: the value is baked in once, so a disk read would block
          // hot reload of a repaired record. Asked per catalog so a record-less catalog doesn't
          // withdraw export everywhere. A broken configured record is still advertised and each
          // export refuses with a precise reason.
          it.exportCapabilities = uiBuilderExports
          // A record, or a catalog whose designs are written by a record-free emitter (e.g. Wear
          // widgets via `WearWidgetCodeExporter`). `composeCode` now means "exports Kotlin source";
          // a new `ExportFormatV1` member would be a cross-repo wire change.
          it.composeExportFor = { systemId ->
            val owned = publishedCatalogs[systemId]?.takeIf { ownership.owns(systemId) }
            // A catalog that owns its export states it, and is answered by that declaration
            // alone: no record, id or platform test below speaks for it.
            if (owned != null)
              CatalogExportRouting.exportsCompose(CatalogExportRouting.route(owned, ownership)) {
                false
              }
            else
              systemId in uiBuilderComponents.keys ||
                publishedCatalogs[systemId]?.let { catalog ->
                  CatalogComposeSourceExportAdapters.resolve(catalog) is
                    CatalogComposeSourceExportAdapters.Resolution.Supported
                } == true ||
                systemId in RecordFreeExport.CATALOG_SYSTEM_IDS ||
                // An A2UI catalog, packaged or published: `RecordFreeExport` routes its designs to
                // `A2uiComposeExporter` by platform, so every one of them has Kotlin.
                systemId == CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID ||
                publishedCatalogs[systemId]?.statusSemantics?.let {
                  UiBuilderCatalogPlatform.from(it) == UiBuilderCatalogPlatform.A2UI
                } == true ||
                (UiBuilderBuildFeatures.remoteCompose &&
                  publishedCatalogs[systemId]?.statusSemantics?.let {
                    UiBuilderCatalogPlatform.from(it) == UiBuilderCatalogPlatform.REMOTE_COMPOSE
                  } == true)
          }
          it.packs = packs
        }
        .build()
    val catalogs = SwappableUiBuilderCatalogExecutor(buildCatalogExecutor())
    // Called when a delivery branch moves: recompose every builder catalog that system supplies and
    // swap them in one step. Native backends are kept current too, since a policy can change a
    // catalog's platform.
    val nativeBackends = ConcurrentHashMap<String, String>()
    fun deriveRouting() {
      catalogs.listCatalogs().forEach { catalog ->
        // An owned catalog reaches the record-free emitter its `composeSourceExport` names,
        // whatever platform word it states; every other catalog is routed by that word.
        catalogPlatforms[catalog.benchmark.catalogSystemId] =
          CatalogExportRouting.recordFreePlatform(CatalogExportRouting.route(catalog, ownership))
            ?: UiBuilderCatalogPlatform.from(catalog.statusSemantics)
        nativeBackends[catalog.benchmark.catalogSystemId] =
          UiBuilderPreviewSurfaces.from(catalog.statusSemantics).native.backend
      }
    }
    deriveRouting()
    // Set once the service exists, below; the refresher cannot fire before this lane is returned.
    var recovery: ServeUiBuilderCatalogRecovery? = null
    val refreshPublished: (String) -> Unit = { sourceSystem ->
      val affected = uiBuilderCatalogs.filter {
        uiBuilderPublishedSourceSystem(it, uiBuilderNativeCatalogs) == sourceSystem
      }
      val before = affected.associateWith {
        Triple(publishedRuntimeIds[it], publishedCatalogs[it], health.stateOf(it))
      }
      affected.forEach { composePublished(it, refresh = true) }
      // Becoming unavailable, or available again, changes the served set as surely as a new
      // policy does.
      val changed = affected.filter {
        Triple(publishedRuntimeIds[it], publishedCatalogs[it], health.stateOf(it)) != before[it]
      }
      if (changed.isNotEmpty()) {
        catalogs.swap(buildCatalogExecutor())
        deriveRouting()
        System.err.println(
          "serve: UI-builder catalog " +
            changed.joinToString() +
            " refreshed from " +
            sourceSystem +
            "; runtime " +
            changed.joinToString { publishedRuntimeIds[it] ?: "built-in" }
        )
        // Designs pinned to the runtime just replaced no longer open; move the ones that can move.
        recovery?.let {
          runCatching { runBlocking { it.recoverStranded() } }
            .onFailure { e ->
              System.err.println("serve: UI-builder catalog recovery failed: ${e.message}")
            }
        }
      }
    }
    val annotatedExporter = RootSurfaceGroundAnnotatedExporter(exporter)
    val service =
      PersistentUiBuilderService(
        designStore = UiBuilderDesignStateStore.open(directory.toPath()),
        catalogs = catalogs,
        exporter = annotatedExporter,
        assets = assetStore,
      )
    // A deploy can change a catalog's runtime too, so the same pass runs once at startup.
    recovery = ServeUiBuilderCatalogRecovery(service, service, catalogs)
    runCatching { runBlocking { recovery?.recoverStranded() } }
      .onFailure { System.err.println("serve: UI-builder catalog recovery failed: ${it.message}") }
    // Unusable designs are otherwise invisible at startup; name each id and reason so the operator
    // can repair or retire it.
    val unreadable = service.adminUnreadableDesigns()
    service.adminUnusableDesigns().forEach { (designId, reason) ->
      // Two remedies: a design the catalog outgrew can be exported and re-imported; one whose files
      // won't decode has no document to take out.
      val remedy =
        if (designId in unreadable) {
          "the stored files are what failed, so there is nothing to download or repair — restore " +
            "this design's directory from a backup, or retire it through /admin/ui-builder"
        } else {
          "repair the catalog it pins and restart, or take the design through /admin/ui-builder: " +
            "download it, edit it to satisfy the rule, put it back, or retire it"
        }
      System.err.println(
        "serve: WARNING UI-builder design $designId cannot be served: $reason — $remedy"
      )
    }
    service.adminDegradedDesigns().forEach { (designId, reason) ->
      System.err.println(
        "serve: WARNING UI-builder design $designId is degraded: $reason — open its Issues " +
          "panel to drop each property or map it to a catalog replacement"
      )
    }
    // Warn about a state file near its size ceiling in the deploy output. Never allowed to fail
    // startup.
    runCatching { service.diagnostics() }
      .getOrNull()
      ?.let { uiBuilderStorageWarning(it.storageBytes, it.storageMaximumBytes) }
      ?.let(System.err::println)
    if (uiBuilderMigrateState) {
      // Not caught here: [openUiBuilderService]'s guard closes the renderer and disables the lane.
      val migration = service.migratePersistenceToLatest()
      System.err.println(
        "serve: UI-builder persistence ${migration.fromFormat} -> ${migration.toFormat} " +
          if (migration.migrated) "completed (${migration.persistedBytes} bytes)"
          else "already current"
      )
    }
    return UiBuilderLane(
      service = service,
      renderer = renderer,
      refreshPublished = refreshPublished,
      seeds = UiBuilderCatalogSeeds(ownership) { publishedTemplates[it] },
      references =
        runCatching { ServeUiBuilderReferenceStore(directory.resolve("references").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder reference overlays unavailable (${it.message}); " +
                "the builder works, and a reference cannot be attached"
            )
          }
          .getOrNull(),
      comments =
        runCatching { ServeUiBuilderCommentStore(directory.resolve("comments").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder comments unavailable (${it.message}); " +
                "the builder works, and a design cannot be discussed on it"
            )
          }
          .getOrNull(),
      links =
        runCatching { ServeUiBuilderLinksStore(directory.resolve("links").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder links unavailable (${it.message}); " +
                "the builder works, and a design cannot say what it is for"
            )
          }
          .getOrNull(),
      reviews =
        runCatching { ServeUiBuilderReviewStore(directory.resolve("reviews").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder reviews unavailable (${it.message}); " +
                "the builder works, and a design cannot be approved on it"
            )
          }
          .getOrNull(),
      catalogGuidelines = catalogGuidelines,
      guidelineRecords =
        runCatching { ServeUiBuilderGuidelineStore(directory.resolve("guidelines").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder guidelines results unavailable (${it.message}); " +
                "the builder works, and a guidelines check is not kept"
            )
          }
          .getOrNull(),
      thumbnails = runCatching {
          ServeUiBuilderThumbnails(
            directory.resolve("thumbnails").toPath(),
            // The widget player is part of what draws a widget's picture, so switching it
            // redraws the cards rather than keeping the other player's pictures current.
            ServeUiBuilderThumbnails.generationOf(SERVE_VERSION) {
              PackagedUiBuilderRenderBundle.digest()
            } + "+widget-" + options.uiBuilderWidgetPlayer.flagValue,
          )
        }
          .onFailure {
            System.err.println(
              "serve: UI-builder thumbnails unavailable (${it.message}); " +
                "the design list draws each card from the live export"
            )
          }
          .getOrNull(),
      folders =
        runCatching { ServeUiBuilderFolderStore(directory.resolve("folders").toPath()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder folders unavailable (${it.message}); " +
                "the builder works, and designs remain unfiled"
            )
          }
          .getOrNull(),
      projects =
        runCatching { ServeUiBuilderProjectStore(directory.resolve("projects").toPath()) }
          .onFailure {
            System.err.println("serve: UI-builder projects unavailable (${it.message})")
          }
          .getOrNull(),
      components = runCatching {
          ServeUiBuilderComponentStore(
            directory.resolve(ServeUiBuilderComponentStore.DIRECTORY).toPath()
          )
        }
          .onFailure {
            System.err.println(
              "serve: UI-builder component publishing unavailable (${it.message}); " +
                "the project library is still read, and nothing can be published to it"
            )
          }
          .getOrNull(),
      compose = compose,
      nativeBackends = nativeBackends,
      validator = ScratchUiBuilderDraftValidator(catalogs, annotatedExporter),
      stateDirectory = directory,
    )
  }

  /**
   * Find conventional executable CMP/Wasm browser projects and associate them with the preview
   * modules they depend on (the `:shared:ui` + `:webApp` split, or a module owning its own Wasm
   * app). Missing distributions are built; failure is non-fatal since snapshots remain a complete
   * fallback.
   */
  private fun automaticWasmCatalogs(modules: List<PreviewModule>): Map<String, File> {
    val root = gradleProjectRoot() ?: return emptyMap()
    val gradleProjects = gradleProjects()
    val projects = discoverWasmProjects(root, gradleProjects)
    if (projects.isEmpty()) return emptyMap()

    val assignments = modules.mapNotNull { module ->
      val directMatches = projects.filter { it.supports(module) }
      // Convention plugins can hide the dependency declaration; one preview module and one Wasm app
      // is still unambiguous.
      val matches =
        if (directMatches.isEmpty() && modules.size == 1 && projects.size == 1) projects
        else directMatches
      val selected =
        matches
          .sortedWith(
            compareByDescending<AutomaticWasmProject> { it.distribution() != null }
              .thenBy { it.gradlePath }
          )
          .firstOrNull() ?: return@mapNotNull null
      if (matches.size > 1) {
        System.err.println(
          "browse: several Wasm apps depend on ${module.gradlePath}; using ${selected.gradlePath}."
        )
      }
      module.gradlePath to selected
    }

    assignments
      .map { it.second }
      .distinctBy { it.gradlePath }
      .filter { it.distribution() == null }
      .forEach { project ->
        System.err.println("browse: building CMP Wasm app ${project.gradlePath}…")
        val ok =
          runGradleTasks(
            ":${project.gradlePath}:wasmJsBrowserDistribution",
            arguments = gradleBuildArgs(),
          )
        if (!ok) {
          System.err.println(
            "browse: ${project.gradlePath} has no usable Wasm browser distribution; using snapshots."
          )
        }
      }

    return assignments
      .mapNotNull { (module, project) -> project.distribution()?.let { module to it } }
      .toMap()
  }

  private fun bringUpServer(
    registry: ServeSessionRegistry,
    token: String,
    defaultSessionId: String,
    bundleStore: ServeBundleStore?,
    /** Live (see [mergedWasmCatalogs]) so a runtime catalog's Wasm app is added/removed with it. */
    wasmCatalogs: MutableMap<String, File>,
    /** Auto-discovered local apps whose compiled project assets stay behind the session token. */
    privateWasmCatalogs: Set<String> = emptySet(),
    bannerLabel: String,
    bannerPreviewCount: Int,
    mdnsModuleLabel: String?,
    mdnsPreviewIds: List<String>?,
    closeables: List<AutoCloseable?>,
    catalogLoads: CatalogLoadTracker?,
    /** Local project sessions to list on the component-browser front door. */
    localCatalogSessions: List<String> = emptyList(),
    /** Module roots used to serve source for local component-browser sessions. */
    localSourceRoots: Map<String, File> = emptyMap(),
    /** The catalog store an admin registration fetches through; null ⇒ no runtime admin. */
    catalogStore: ServeCatalogStore? = null,
    /** Immediate branch-head check used by the Refresh control on catalog landing pages. */
    catalogRefresh: ((system: String, force: Boolean) -> CatalogRefreshResult)? = null,
    /** Project mode's local-git render history; null on a box with no checkout to read. */
    projectHistory: ServeProjectHistory? = null,
    /** Called immediately after the HTTP listener binds, before the long blocking wait. */
    onStarted: () -> Unit = {},
  ) {
    val configuredCatalogs =
      localCatalogSessions +
        (catalogLoads?.snapshot()?.filter { it.config.listed }?.map { it.config.system }
          ?: registeredCatalogs.toList())
    val configuredApps =
      catalogLoads?.snapshot()?.filter { !it.config.listed }?.map { it.config.system }
        ?: registeredUnlistedCatalogs.toList()
    // Top-level sites: `catalogs.json`'s `sites` first, then `--sites` entries for unclaimed hosts.
    // A site naming an unserved system is dropped with a warning. A [ServeSiteRegistry] because
    // `/admin/sites` publishes onto the running server.
    val sites =
      ServeSiteRegistry(
        ServeSites.of(
          catalogsConfig.sites.map { it.host to it.system } +
            ServeSites.parse(sitesRaw, onProblem = { System.err.println("serve: $it") }).let {
              flagSites ->
              flagSites.hosts.map { it to flagSites.systemFor(it)!! }
            },
          knownSystems = (configuredCatalogs + configuredApps).toSet(),
          onProblem = { System.err.println("serve: $it") },
        )
      )
    // Runtime catalog administration needs both an admin token and a catalog store; a plain `serve`
    // has no admin surface.
    val catalogAdmin =
      if (adminToken != null && catalogStore != null && catalogLoads != null) {
        buildCatalogAdmin(registry, catalogStore, catalogLoads, wasmCatalogs, sites)
      } else {
        null
      }
    // Keep the catalog set in step with nominated registry projects ([ServeCatalogRegistrySync]) so
    // later listings import without a restart. Independent of the admin token.
    val catalogRegistrySync =
      if (catalogStore != null && catalogLoads != null) {
        buildCatalogRegistrySync(registry, catalogStore, catalogLoads, wasmCatalogs, sites)
      } else {
        null
      }
    // Project onboarding exists exactly when the administrator does: everything it does is a
    // `catalogAdmin.register` read off the repository's refs.
    val onboarding = catalogAdmin?.let {
      ServeOnboarding(admin = it, branchPrefix = catalogBranchPrefix)
    }
    // Onboarding a project that has published nothing: needs only the admin token, no store or
    // administrator.
    val sourceOnboarding = if (adminToken != null) buildSourceOnboarding() else null
    // Runtime site administration: needs only the admin token and the live map. Reads the current
    // served set through the tracker, so a site may name a catalog published moments earlier.
    val siteAdmin =
      if (adminToken != null) {
        ServeSiteAdmin(
          registry = sites,
          servedSystems = {
            catalogLoads?.snapshot()?.map { it.config.system }?.toSet()
              ?: (configuredCatalogs + configuredApps).toSet()
          },
          configFile = catalogsFile,
        )
      } else {
        null
      }
    // Runtime editor-pin administration: needs the admin token, an archive cache, and a file to
    // persist the pin to.
    val editorAdmin =
      if (adminToken != null && uiBuilderEditorStore != null) {
        ServeUiBuilderEditorAdmin(
          store = uiBuilderEditorStore!!,
          configFile = catalogsFile,
          servingState = { uiBuilderEditorState },
        )
      } else {
        null
      }
    // UI builder catalog settings maintained in catalogs.json, resolved against the environment's
    // values so a PUT describes what the next start serves.
    val uiBuilderSettingsAdmin =
      if (adminToken != null) {
        ServeUiBuilderSettingsAdmin(
          configFile = catalogsFile,
          environment =
            ServeUiBuilderSettings.Effective.of(ServeUiBuilderSettings.environmentOf(options)),
          serving = ServeUiBuilderSettings.Effective.of(options),
          shadowReports = { uiBuilderShadowReports.toMap() },
          unavailable = { uiBuilderCatalogProblems() },
        )
      } else {
        null
      }
    // Producer-trust administration needs only the admin token, so a box with no trust store can
    // add its first producer without a rebuild.
    val trustAdmin =
      if (adminToken != null) {
        ServeTrustAdmin(
          store = trustStore,
          file = trustStoreFile,
          // Revoking trust retires what it bought: affected sessions are dropped and rows marked
          // failed, so the refresher re-fetches them as `unverified`.
          onRevoke = { updated -> retireNewlyUntrusted(updated, catalogLoads, registry) },
          // Granting trust re-verifies affected catalogs; otherwise the refresher's unchanged-SHA
          // short-circuit keeps them `unverified`.
          onGrant = { before, updated -> reverifyNewlyTrusted(before, updated, catalogLoads) },
        )
      } else {
        null
      }
    // Resolved once so the playground's remote-compose lane publishes into the SAME store the `/d/`
    // route serves from — otherwise a minted `/d/<id>` link wouldn't resolve.
    val docStore = openDocStore()
    val imageLane = openImageLane()
    // Built before the playground: configured GitHub auth is one of the two bases of the `--public`
    // admission gate.
    val githubAuth = buildGithubAuth()
    val uiBuilderGuidelines = buildUiBuilderGuidelines()
    val guidelinesPictureBudget =
      java.util.concurrent.atomic.AtomicLong(uiBuilderGuidelinesPictureBudgetSeconds)
    val settingsAdmin = buildSettingsAdmin(uiBuilderGuidelines, guidelinesPictureBudget)
    settingsAdmin.describe().forEach(System.err::println)
    val agentGrantStore = buildAgentGrantStore(githubAuth)
    if (catalogMcp && agentGrantStore == null) {
      System.err.println(
        "serve: --catalog-mcp refused — remote MCP requires --agent-grants so its bearer can be " +
          "short-lived, scoped, approved, and revoked."
      )
      throw IllegalArgumentException("--catalog-mcp requires --agent-grants")
    }
    val machineAuthorization =
      ServeMachineAuthorization(
        token,
        githubAuth,
        agentGrantStore,
        siteHosts = { sites.hosts },
        isPublic = public,
      )
    val playgroundLane =
      openPlaygroundService(docStore, registry, repoAccessGated = githubAuth != null)
    if (playgroundExternal && (playgroundLane == null || publicPlayground)) {
      System.err.println(
        if (publicPlayground)
          "serve: --playground-external ignored — this host mounts the playground itself."
        else
          "serve: --playground-external has no compile engine to decide which catalogs compile " +
            "(add --compile-engine); no playground links are offered."
      )
    }
    val catalogFeed =
      if (catalogLoads != null && catalogFeedIdleSeconds > 0) {
        ServeCatalogChangeFeed(
          entries = { catalogLoads.snapshot().map { it.config } },
          cacheRoot = catalogFeedCacheDir,
          idleTimeoutMillis = catalogFeedIdleSeconds * 1000,
          // While a feed is subscribed it follows the catalog refresh cadence, or ten minutes when
          // refresh is disabled.
          pollIntervalMillis =
            (catalogRefreshSeconds.takeIf { it > 0 }
              ?: ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS) * 1000,
        )
      } else {
        null
      }
    val uiBuilderAppDir = usableUiBuilderDir()
    val uiBuilderLane = openUiBuilderService(uiBuilderAppDir, catalogStore, catalogLoads)
    // A local module's own guidelines, where compose-ai-tools' discovery writes them beside its
    // `ui-builder.json`; a hosted catalog's arrive with its published catalog instead.
    localSourceRoots.values.forEach { root ->
      uiBuilderLane
        ?.catalogGuidelines
        ?.loadLocal(
          File(
            root,
            "build/compose-previews/${ee.schimke.composeai.uibuilder.guidelines.CatalogGuidelines.FILE_NAME}",
          )
        )
    }
    uiBuilderLaneOpen = uiBuilderLane != null
    uiBuilderPublishedRefresh = uiBuilderLane?.refreshPublished
    // Logged at startup because a capability cap otherwise surfaces three steps downstream, as a
    // refused tool call.
    if (agentGrantStore != null && uiBuilderLane != null) {
      val builderCapabilities =
        listOf(
          AgentGrantCapability.UI_BUILDER_READ,
          AgentGrantCapability.UI_BUILDER_WRITE,
          AgentGrantCapability.UI_BUILDER_EXPORT,
        )
      val missing = builderCapabilities.filterNot { it in agentGrantStore.maxCapabilities }
      if (missing.isNotEmpty()) {
        System.err.println(
          "serve: agent grants cannot carry ${missing.joinToString(", ") { it.wire }} — the " +
            "UI-builder tools will refuse every agent call. Add them to --agent-grant-capabilities " +
            "if approved agents should author designs on this box."
        )
      }
    }
    landingServesSomething =
      defaultSessionId.isNotEmpty() || registry.anySessionId() != null || catalogRefs.isNotEmpty()
    // Fail-soft unless the builder was the whole server (`ui --no-project`), where serving assets
    // over an absent design API would be a builder that cannot save.
    if (
      uiBuilderLane == null &&
        uiBuilderLaneConfigured &&
        uiBuilderIsOnlyConfiguredSurface &&
        defaultSessionId.isEmpty() &&
        registry.anySessionId() == null
    ) {
      System.err.println(
        "serve: the UI builder is the only surface on this server and its service could not be " +
          "opened, so there is nothing left to serve."
      )
      exitProcess(1)
    }
    val uiBuilderAdministrators = ServeUiBuilderAdministrators(uiBuilderAdminActors)
    // Runtime UI-builder administration: needs a builder lane plus an operator credential or
    // configured administrator. The diagnostic token gets the same read port, but its HTTP gate
    // exposes only the summary.
    val uiBuilderAdmin =
      if (
        (adminToken != null || adminReadToken != null || uiBuilderAdministrators.configured) &&
          uiBuilderLane != null
      ) {
        ServeUiBuilderAdmin(
          service = uiBuilderLane.service,
          references = uiBuilderLane.references,
          comments = uiBuilderLane.comments,
          links = uiBuilderLane.links,
          reviews = uiBuilderLane.reviews,
          thumbnails = uiBuilderLane.thumbnails,
          designs = uiBuilderLane.service,
          guidelineRecords = uiBuilderLane.guidelineRecords,
        )
      } else {
        null
      }
    // Designs the served catalogs publish; same gates as the admin above, fetched through the
    // shared counted fetcher.
    val uiBuilderDesignLibrary =
      if (uiBuilderAdmin != null) {
        ServeUiBuilderDesignLibrary(
          fetch = ::fetchRegistryDocument,
          onLog = { System.err.println(it) },
        )
      } else {
        null
      }
    // Components shared across those projects' designs, cached separately from the design library
    // (designs change far more often). Gated on the builder being served, not on the admin surface:
    // the palette uses the builder's own credential. Keyed on `uiBuilderLane` like the
    // authorization below, so the two can't disagree.
    val uiBuilderComponentLibrary = uiBuilderLane?.let {
      ServeUiBuilderComponentLibrary(
        fetch = ::fetchRegistryDocument,
        onLog = { System.err.println(it) },
      )
    }
    // Comment activity webhook, off unless configured. Attached before comment routes serve so no
    // early comment is missed.
    var startedServer: ServeHttpServer? = null
    val commentWebhook = uiBuilderCommentWebhook?.let { url ->
      val comments = uiBuilderLane?.comments
      if (comments == null) {
        // Logged so an operator who configured a hook learns on deploy day why it will never fire.
        System.err.println(
          "serve: --ui-builder-comment-webhook is set and there is no comment store to watch " +
            "(the UI builder is off, or its state directory could not be opened); nothing " +
            "will be posted"
        )
        return@let null
      }
      val format =
        uiBuilderCommentWebhookFormat?.let { CommentWebhookFormat.parse(it) }
          ?: CommentWebhookFormat.PLAIN
      // The origin browsers reach this box at: `--github-auth-callback-base-url` when set (a
      // proxied deployment knows its public name), else the bind address.
      val configuredOrigin =
        githubAuthCallbackBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
      val linkHost = if (ServeUrls.isExposed(host)) ServeUrls.LOOPBACK else host
      val webhook =
        ServeUiBuilderCommentWebhook(
          config = CommentWebhookConfig(url, format),
          designs = { designId ->
            commentWebhookDesign(
              admin = uiBuilderLane.service,
              service = uiBuilderLane.service,
              links = uiBuilderLane.links,
              designId = designId,
              hostIsPublic = public,
            )
          },
          baseUrl = {
            configuredOrigin ?: ServeUrls.origin(linkHost, startedServer?.port ?: requestedPort)
          },
        )
      val events =
        uiBuilderWebhookEvents?.let { DesignActivityKind.parseEvents(it) }
          ?: WebhookEventSelection.DEFAULT
      val posted =
        listOfNotNull(if (events.comments) "comment threads, replies and resolutions" else null) +
          events.activity.map { it.wire }
      System.err.println(
        "serve: UI-builder activity posts to a ${format.wire} webhook " +
          "(${webhook.fingerprint}): ${posted.joinToString(", ").ifEmpty { "nothing selected" }}"
      )
      // On a token-gated host without sign-in, permalinks in notifications can't be opened by
      // recipients. Warned, not fixed: putting the server token into a chat channel would hand out
      // browse access. Not refused either, since loopback receivers and authenticating proxies are
      // legitimate.
      if (token.isNotBlank() && githubAuth == null) {
        System.err.println(
          "serve: note - this host is gated by a browse token and has no GitHub sign-in, so a " +
            "recipient without that token cannot open the designs these notifications link to; " +
            "configure --github-auth-client-id, or authenticate in front of this server"
        )
      }
      // Each source is attached only when asked for and present; a selected kind with nothing to
      // watch is said once, for the same reason a hook with no comment store is.
      val handles = mutableListOf<java.io.Closeable>()
      if (events.comments) handles += webhook.attach(comments)
      val reviewKinds =
        events.activity.intersect(
          setOf(DesignActivityKind.DECISION, DesignActivityKind.IMPLEMENTATION)
        )
      if (reviewKinds.isNotEmpty()) {
        val reviews = uiBuilderLane?.reviews
        if (reviews != null) handles += webhook.attachReviews(reviews, reviewKinds)
        else
          System.err.println(
            "serve: --ui-builder-webhook-events names review events and this host keeps no " +
              "review records; none will be posted"
          )
      }
      if (DesignActivityKind.FORK in events.activity) {
        val links = uiBuilderLane?.links
        if (links != null) handles += webhook.attachForks(links.ancestry)
        else
          System.err.println(
            "serve: --ui-builder-webhook-events names fork and this host keeps no design " +
              "ancestry; none will be posted"
          )
      }
      webhook to java.io.Closeable { handles.forEach { runCatching { it.close() } } }
    }
    // Telling the person: Web Push, the fourth subscriber to the same two feeds. Attached here for
    // the webhook's reason — before the routes that accept comments are serving.
    val push =
      openWebPush(uiBuilderLane, githubAuth) {
        githubAuthCallbackBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
          ?: ServeUrls.origin(
            if (ServeUrls.isExposed(host)) ServeUrls.LOOPBACK else host,
            startedServer?.port ?: requestedPort,
          )
      }
    val server =
      ServeHttpServer(
        host = host,
        requestedPort = requestedPort,
        // Only an origin the operator stated is an identity a copy can point back at. An unusable
        // callback base URL only costs the home stamp here; OAuth reports it on its own.
        canonicalOrigin =
          uiBuilderPublicOrigin
            ?: githubAuthCallbackBaseUrl?.takeIf { it.isNotBlank() }?.let(::normalizeServerHomeUrl),
        token = token,
        sessions = registry,
        defaultSessionId = defaultSessionId,
        bundleStore = bundleStore,
        isPublic = public,
        componentBrowser = componentBrowser,
        wasmCatalogs = wasmCatalogs,
        wasmUiDir = usableWasmUiDir(),
        uiBuilderDir = uiBuilderAppDir,
        uiBuilderCatalogs = uiBuilderCatalogs,
        uiBuilderSeeds = uiBuilderLane?.seeds ?: UiBuilderCatalogSeeds.BUILT_IN,
        uiBuilderRuntimeDirs = uiBuilderRuntimeDirs,
        catalogUiBuilderRuntimeAsset = { runtimeId, segments ->
          catalogStore?.uiBuilderRuntimeAsset(runtimeId, segments)?.let { asset ->
            asset.bytes to asset.etag
          }
        },
        privateWasmCatalogs = privateWasmCatalogs,
        rcPlayerWasmDir = rcPlayerWasmDir,
        preferredRcPlayer = rcDefaultPlayer,
        // Preserve the CONFIGURED set, not only startup successes. Failed rows then stay visible on
        // /status, and a catalog recovered by the refresher appears on the home index immediately.
        catalogSessions = configuredCatalogs,
        appCatalogSessions = configuredApps,
        sites = sites,
        uiBuilderHost = uiBuilderHost,
        uiBuilderStartUrl = uiBuilderStartUrl,
        uiBuilderHostRoot = uiBuilderHostRoot,
        catalogLoads = catalogLoads,
        heroCacheDir = catalogCacheDirFlag?.takeIf { it != "none" }?.let { File(it, "heroes") },
        catalogRefresh = catalogRefresh,
        catalogFeed = catalogFeed,
        maxLiveSeats = liveSeats,
        liveSeatLimiter = liveSeatLimiter,
        spareSandboxSnapshot = { spareSandboxPool?.snapshot() },
        daemonLog = daemonLog,
        allowRenderTrusted = allowRenderTrusted,
        trustStoreConfigured = trustStorePath != null,
        catalogRefreshSeconds = catalogRefreshSeconds,
        catalogRegistries = { catalogRegistryStatuses(catalogRegistrySync) },
        uiBuilderCatalogProblems = { uiBuilderCatalogProblems() },
        acceptBundlesEnabled = acceptBundles,
        catalogAdmin = catalogAdmin,
        onboarding = onboarding,
        sourceOnboarding = sourceOnboarding,
        siteAdmin = siteAdmin,
        editorAdmin = editorAdmin,
        uiBuilderSettingsAdmin = uiBuilderSettingsAdmin,
        settingsAdmin = settingsAdmin.takeIf { adminToken != null },
        uiBuilderAdmin = uiBuilderAdmin,
        uiBuilderDesignLibrary = uiBuilderDesignLibrary,
        uiBuilderDesignCatalogs = {
          uiBuilderDesignDirectories() + uiBuilderDesignCatalogCoordinates(catalogLoads)
        },
        uiBuilderComponentLibrary = uiBuilderComponentLibrary,
        uiBuilderComponentStore = uiBuilderLane?.components,
        // The same executor the export runs, so the editor's code pane and the export read the same
        // record.
        uiBuilderCatalogRecord = { systemId -> uiBuilderLane?.compose?.exportRecord(systemId) },
        trustAdmin = trustAdmin,
        adminToken = adminToken,
        adminReadToken = adminReadToken,
        uiBuilderAdminActors = uiBuilderAdminActors,
        docStore = docStore,
        imageStore = imageLane?.store,
        imageUploadAuth = imageLane?.auth,
        imageUploadLimiter = imageLane?.limiter,
        // Null on a `--compile-engine` host, which keeps the public playground surface unmounted
        // while UI-builder lanes still compile via `playgroundLane`.
        playgroundService = playgroundLane?.compile?.takeIf { publicPlayground },
        // A sibling `--role playground` process serves the editor; this host's engine only decides
        // which catalogs get a link to it.
        externalPlaygroundLinks =
          playgroundLane?.compile?.takeIf { playgroundExternal && !publicPlayground },
        playgroundHealth = playgroundLane?.health,
        branchFetchStats = catalogStore?.let { store -> { store.branchFetchStats.snapshot() } },
        themeOptimizerStats = { backgroundWork.optimizerAdmissionSnapshot() },
        themeCacheStats = { themeCacheStore?.snapshot() },
        // Null when no catalogs are published, so `/status` doesn't show a never-used cache as
        // failing.
        catalogCacheStats =
          if (needsCatalogMachinery) {
            { runCatching { catalogBlobPool.snapshot() }.getOrNull() }
          } else null,
        catalogCacheClear =
          if (needsCatalogMachinery) {
            { catalogBlobPool.clear() }
          } else null,
        themeOptimizerAdmin = backgroundWork,
        playgroundRedeem = playgroundLane?.redeem,
        githubAuth = githubAuth,
        uiBuilderGuidelines = uiBuilderGuidelines,
        uiBuilderGuidelinesPictureBudgetSeconds = uiBuilderGuidelinesPictureBudgetSeconds,
        uiBuilderGuidelinesPictureBudgetSecondsLive = guidelinesPictureBudget::get,
        imageBrowserLogin =
          githubAuth?.let { auth ->
            { call, repository ->
              // The image bit against the image repository (which may differ from sign-in's).
              // Case-insensitive, like every `owner/repo` comparison here.
              auth.currentLogin(call)?.takeIf {
                auth.hasImageRepositoryAccess(call) &&
                  auth.imageAccessRepository().equals(repository, ignoreCase = true)
              }
            }
          },
        agentGrants = agentGrantStore,
        mcpOAuthClientsFile =
          agentGrantStore?.let {
            ServeMcpOAuthClients.defaultFile(
                catalogsFilePath?.let(::File)?.toPath(),
                File(System.getProperty("user.home"), ".compose-preview").toPath(),
              )
              .toFile()
          },
        agentGrantLimiter = agentGrantStore?.let { buildAgentGrantRateLimiter() },
        catalogMcpEnabled = catalogMcp,
        machineAuthorization = machineAuthorization,
        // Wrapped so every accepted edit also queues a redraw of that design's listing card.
        uiBuilderService =
          uiBuilderLane?.let { lane ->
            ServeUiBuilderVisibility.withDefault(
              lane.thumbnails?.warming(lane.service) ?: lane.service,
              uiBuilderDefaultVisibility,
            )
          },
        uiBuilderThumbnails = uiBuilderLane?.thumbnails,
        uiBuilderReferenceStore = uiBuilderLane?.references,
        uiBuilderCommentStore = uiBuilderLane?.comments,
        uiBuilderLinksStore = uiBuilderLane?.links,
        uiBuilderReviewStore = uiBuilderLane?.reviews,
        uiBuilderGuidelineStore = uiBuilderLane?.guidelineRecords,
        uiBuilderCatalogGuidelines = uiBuilderLane?.catalogGuidelines,
        uiBuilderFolderStore = uiBuilderLane?.folders,
        uiBuilderProjectStore = uiBuilderLane?.projects,
        uiBuilderAssets = uiBuilderLane?.service,
        // Wrapped like the service, so a merge redraws the parent's listing card.
        uiBuilderBranches =
          uiBuilderLane?.let { lane ->
            val branches: UiBuilderBranchPort = lane.service
            lane.thumbnails?.warmingBranches(branches) ?: branches
          },
        uiBuilderValidator = uiBuilderLane?.validator,
        uiBuilderAuthorization =
          uiBuilderLane?.let {
            ServeUiBuilderAuthorization.fromMachineAuthorization(machineAuthorization)
          },
        // Needs both the generator and the playground lane; otherwise the route is simply absent.
        uiBuilderNativePreview =
          uiBuilderLane?.let { lane ->
            playgroundLane?.let { playground ->
              val adapter = UiBuilderGeneratedPreviewAdapter(playground.compile)
              ServeUiBuilderNativePreview(
                executor = lane.compose,
                // A builder catalog id is not a served catalog id, and the daemon follows the
                // bundle (e.g. `wear-m3` is a Robolectric bundle served from another repository).
                // Unmapped catalogs compile against a served catalog of the same name on desktop.
                nativeTarget = { builderCatalog ->
                  val served = uiBuilderNativeCatalogs[builderCatalog] ?: builderCatalog
                  // A pack declares no backend; it takes its served bundle's.
                  val backend =
                    lane.nativeBackends[builderCatalog]
                      ?: playground.catalogBackend(served)?.takeIf {
                        builderCatalog in uiBuilderPacks
                      }
                  // A catalog declaring the Android daemon but mapped to a desktop bundle is
                  // refused rather than compiled on Skiko, where every `androidx.wear.compose`
                  // import would fail. Only checked where the host reports a backend (not on pinned
                  // hosts).
                  val servedBackend = playground.catalogBackend(served)
                  when {
                    backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID &&
                      servedBackend != null &&
                      servedBackend != UiBuilderPreviewSurfaces.BACKEND_ANDROID -> null
                    backend == UiBuilderPreviewSurfaces.BACKEND_ANDROID ->
                      UiBuilderNativeTarget(served, UiBuilderGeneratedCompose.COMPOSE_ANDROID)
                    else -> UiBuilderNativeTarget(served, UiBuilderGeneratedCompose.COMPOSE_CMP)
                  }
                },
                packs = uiBuilderPacks.keys,
                widgetPlayer = options.uiBuilderWidgetPlayer,
                // The CMP player is compiled into the widget entry, so it is chosen only for a
                // bundle whose manifest carries it — positive evidence, as the live lane asks.
                carriesCmpWidgetPlayer = { served ->
                  catalogLiveBundles[served.substringBefore('@')]
                    ?.firstOrNull { it.id == served }
                    ?.file
                    ?.let(ServeRcPlayerIds::bundleCarriesCmpAndroidPlayer) ?: false
                },
                compile = { generated ->
                  // `true` only here: downstream of the route's `ui-builder-export` check, and the
                  // source comes from `ScreenGenerator`, not the caller.
                  adapter.compile(generated, isSecurityChecked = true)
                },
                captureNodeBounds = playground.captureNodeBounds,
              )
            }
          },
        // Needs the generator and the playground, but no catalog mapping: an inline body compiles
        // against whichever catalog offers `remote-compose`.
        uiBuilderInlineCapture =
          uiBuilderLane?.let {
            playgroundLane?.let { playground ->
              val captureAdapter = UiBuilderGeneratedPreviewAdapter(playground.compile)
              ServeUiBuilderInlineCapture(
                compile = { generated ->
                  // `true` for the same reason as the native lane: checked upstream,
                  // emitter-generated source.
                  captureAdapter.compile(generated, isSecurityChecked = true)
                },
                captureCatalog = playground.remoteComposeCatalog,
              )
            }
          },
        // Meters visitors of the public run route; the UI-builder lanes are gated by their own
        // export capability instead.
        playgroundRateLimiter =
          playgroundLane?.takeIf { publicPlayground }?.let { buildPlaygroundRateLimiter() },
        // Reads a served preview's Kotlin for `/playground?from=…` and the viewer's Source panel.
        // Wired unconditionally since the Source panel needs no playground; whether an editor link
        // is offered is decided by `playgroundLinkFor`.
        playgroundSourceFetch = { url: String -> PlaygroundSeedResolver.httpFetch(url) },
        trustForwardedFor = trustForwardedFor,
        engagementStore = ServeEngagementStore(engagementFile),
        projectHistory = projectHistory,
        localSourceRoots = localSourceRoots,
        push = push?.lane,
      )
    if (trustAdmin != null) {
      System.err.println(
        "serve: trust admin API enabled at /admin/trust" +
          (trustStoreFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --trust-store to persist)")
      )
    }
    if (catalogAdmin != null) {
      System.err.println(
        "serve: catalog admin API enabled at /admin/catalogs" +
          (catalogsFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --catalogs-file to persist)")
      )
    }
    if (uiBuilderAdmin != null) {
      System.err.println("serve: UI-builder admin page enabled at /admin/ui-builder")
    }
    if (editorAdmin != null) {
      System.err.println("serve: editor pin admin API enabled at /admin/editor")
    }
    if (siteAdmin != null) {
      System.err.println(
        "serve: site admin API enabled at /admin/sites" +
          (catalogsFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --catalogs-file to persist)")
      )
    }
    if (githubAuth != null) {
      System.err.println(
        "serve: GitHub auth enabled for live sessions and playground" +
          (githubAuthUsers.takeIf { it.isNotEmpty() }?.let { " (${it.size} allowed user(s))" }
            ?: "") +
          (githubAuthOrgs.takeIf { it.isNotEmpty() }?.let { " (members of ${it.joinToString()})" }
            ?: "")
      )
    }
    if (uiBuilderGuidelines != null) {
      System.err.println(
        "serve: ui-builder guidelines check enabled on ${uiBuilderGuidelines.model} for " +
          uiBuilderGuidelines.describeAccess()
      )
    }
    if (agentGrantStore != null) {
      System.err.println(
        "serve: agent access grants enabled at /agent-access — up to " +
          "${AgentGrantProtocol.formatDuration(agentGrantStore.maxGrantTtlSeconds)}, " +
          "max scope ${agentGrantStore.maxScope.wire}, approved by " +
          (if (githubAuth != null) "a signed-in GitHub user" else "the holder of --token")
      )
    }
    if (catalogMcp) {
      System.err.println(
        "serve: aggregate catalog MCP enabled at /mcp (Streamable HTTP; preview scope reads, " +
          "live scope renders)"
      )
    }

    // Advertise over mDNS when bound to a reachable interface (`--lan`) so session-viewer clients
    // can discover the server. Best-effort.
    val advertiser =
      if (mdnsModuleLabel != null && mdnsPreviewIds != null && ServeUrls.isExposed(host)) {
        ServeMdnsAdvertiser.start(
          moduleLabel = mdnsModuleLabel,
          port = server.port,
          previewIds = mdnsPreviewIds,
          secure = false,
          onLog = { System.err.println("[serve] $it") },
        )
      } else {
        null
      }

    val done = CountDownLatch(1)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          System.err.println("\nserve: shutting down…")
          runCatching { advertiser?.close() }
          runCatching { server.stop() }
          runCatching { catalogFeed?.close() }
          runCatching { catalogRegistrySync?.close() }
          runCatching { registry.close() }
          runCatching { commentWebhook?.second?.close() }
          runCatching { commentWebhook?.first?.close() }
          runCatching { push?.close() }
          runCatching { uiBuilderLane?.close() }
          closeables.forEach { c -> runCatching { c?.close() } }
          done.countDown()
        }
      )

    startedServer = server
    server.start()
    // Cadence only — the boot fold-in already read every registry once, so the first pass is a
    // reconciliation, not the initial import.
    catalogRegistrySync?.start()
    onStarted()
    printBanner(bannerLabel, server.port, token, bannerPreviewCount)
    if (openBrowser) openBrowser(server.port, token)
    val watchdog = if (exitWhenIdle) startIdleWatchdog(registry, done) else null
    done.await()
    watchdog?.shutdownNow()
  }

  /** The push lane, the notifier feeding it, and what to close when the server stops. */
  private class WebPush(
    val lane: ServePushLane,
    private val notifier: ServePushNotifier,
    private val handles: List<java.io.Closeable>,
  ) : java.io.Closeable {
    override fun close() {
      handles.forEach { runCatching { it.close() } }
      notifier.close()
    }
  }

  /**
   * Web Push, only where there is a UI builder and GitHub sign-in (someone to address); otherwise
   * its routes 404. Failing to open it never fails the server.
   */
  private fun openWebPush(
    lane: UiBuilderLane?,
    githubAuth: ServeGithubAuth?,
    origin: () -> String,
  ): WebPush? {
    if (!webPush || lane == null || githubAuth == null) return null
    val directory = lane.stateDirectory?.toPath()?.resolve("push") ?: return null
    return runCatching {
      val keys =
        ServeVapidKeys.loadOrCreate(
          directory,
          subject = ServeVapidKeys.subjectFor(vapidSubject, githubAuthCallbackBaseUrl),
          configuredPublic = vapidPublicKey,
          configuredPrivate = vapidPrivateKey,
        )
      val store = ServePushSubscriptionStore(directory)
      val readable = ServeUiBuilderVisibility.withDefault(lane.service, uiBuilderDefaultVisibility)
      val notifier =
        ServePushNotifier(
          store = store,
          keys = keys,
          designs = { designId ->
            lane.service.adminDesignSummary(designId)?.let {
              PushDesign(title = it.title, ownerActorId = it.ownerActorId)
            }
          },
          canRead = { actorId, designId ->
            readable.canRead(AuthenticatedUiBuilderActor(actorId), designId)
          },
          baseUrl = origin,
        )
      val handles = buildList {
        lane.comments?.let { add(notifier.attachComments(it)) }
        lane.reviews?.let { add(notifier.attachReviews(it)) }
      }
      System.err.println(
        "serve: Web Push enabled (VAPID subject ${keys.subject}; " +
          "${store.all().size} subscription(s))"
      )
      WebPush(
        ServePushLane(
          store = store,
          keys = keys,
          actorOf = { call ->
            githubAuth.currentSignedInLogin(call)?.let(ServeAgentGrants::githubActorId)
          },
        ),
        notifier,
        handles,
      )
    }
      .onFailure {
        System.err.println("serve: Web Push unavailable (${it.message}); notifications are off")
      }
      .getOrNull()
  }

  private fun openBrowser(port: Int, token: String) {
    val localHost =
      if (ServeUrls.isExposed(host) || host == ServeUrls.LOOPBACK) ServeUrls.LOOPBACK else host
    val url = localUrlFor(localHost, port, token, effectiveOpenPath)
    val opened = runCatching {
      if (!Desktop.isDesktopSupported()) return@runCatching false
      val desktop = Desktop.getDesktop()
      if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
      desktop.browse(URI(url))
      true
    }
      .getOrDefault(false)
    if (!opened) {
      // Print the actual open URL, not the root landing (which may 404 on a projectless builder). A
      // supplied token stays out of the log, as in [ServeBanner].
      val shown =
        if (public || tokenOverride == null) url
        else
          ServeUrls.origin(localHost, port) +
            effectiveOpenPath +
            "?token=" +
            ServeBanner.redact(token)
      System.err.println("browse: could not open a desktop browser; open $shown")
    }
  }

  /** [path] on this server, carrying the token unless the host is public. */
  private fun localUrlFor(localHost: String, port: Int, token: String, path: String): String {
    val origin = ServeUrls.origin(localHost, port)
    return if (public) "$origin$path" else ServeUrls.pathUrl(origin, path, token)
  }

  /**
   * Poll registry idle time; past [idleExitSeconds] with no open connections, release [done] so
   * [run] returns. Returns the scheduler.
   */
  private fun startIdleWatchdog(
    registry: ServeSessionRegistry,
    done: CountDownLatch,
  ): ScheduledExecutorService {
    val timeoutMillis = idleExitSeconds * 1000
    val interval = (timeoutMillis / 4).coerceIn(1_000, 30_000)
    val exec = Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "serve-idle-watchdog").apply { isDaemon = true }
    }
    exec.scheduleWithFixedDelay(
      {
        // The strict clock: an open socket keeps the process up however quiet it is.
        val idle = registry.connectionIdleMillis()
        if (idle != null && idle >= timeoutMillis) {
          System.err.println(
            "serve: idle ${idle / 1000}s (--exit-when-idle=${idleExitSeconds}s) — shutting down."
          )
          done.countDown()
        }
      },
      interval,
      interval,
      TimeUnit.MILLISECONDS,
    )
    return exec
  }

  /**
   * Repository root for project-mode git surfaces (worktrees, revisions, history); falls back to
   * the module's parent, where git calls fail harmlessly.
   */
  private fun projectRepoRoot(module: PreviewModule): File =
    gradleProjectRoot() ?: module.projectDir.absoluteFile.parentFile ?: module.projectDir

  /** Open the worktree manager rooted at the repo (project mode), gated to the allowed refs. */
  private fun openWorktrees(module: PreviewModule, rootOverride: File? = null): GitWorktrees {
    val repoRoot = rootOverride ?: projectRepoRoot(module)
    if (revisionAllowRefs.isEmpty()) {
      System.err.println(
        "serve: --revisions has no --revisions-allow refs; no revision will build (fail closed). " +
          "Pass --revisions-allow <ref>[,<ref>…] (e.g. main,release/*) to enable trusted revs."
      )
    }
    return GitWorktrees(
      repoRoot = repoRoot,
      cacheRoot = File(repoRoot, "build/serve-worktrees"),
      allowedRefs = revisionAllowRefs,
      onLog = { System.err.println("[serve worktree] $it") },
    )
  }

  /**
   * The URL-scan lane ([ServeSourceOnboarding]): reads a pasted repository, never runs it. Imported
   * projects build on a GitHub Actions runner and arrive as ordinary catalog branches via
   * [ServeOnboarding].
   */
  private fun buildSourceOnboarding(): ServeSourceOnboarding =
    ServeSourceOnboarding(
      checkouts =
        ServeSourceCheckouts(
          cacheRoot = onboardCacheDir,
          onLog = { System.err.println("[serve onboard] $it") },
        ),
      onLog = { System.err.println("[serve onboard] $it") },
    )

  /** The project-mode factory: a git revision (`?session=<rev>`) → a built [ServeSessionState]. */
  private fun revisionFactory(
    module: PreviewModule,
    worktrees: GitWorktrees,
  ): ServeRevisionFactory {
    val repoRoot = projectRepoRoot(module)
    val relativePath =
      module.projectDir.absoluteFile.relativeToOrNull(repoRoot.absoluteFile)?.path ?: ""
    // Same bootstrap args as the normal build path, so worktree builds see the injected plugin and
    // right variant.
    val bootstrapArgs =
      autoInjectInitScriptArgs(projectRoot = repoRoot) + gradleVariantArgs() + gradleBuildArgs()
    return ServeRevisionFactory(
      worktrees = worktrees,
      builder =
        GradleRevisionBuilder(
          extraArgs = bootstrapArgs,
          onLog = { System.err.println("[serve build] $it") },
        ),
      module = ServeModuleRef(module.gradlePath, relativePath),
      onLog = { System.err.println("[serve] $it") },
    )
  }

  /**
   * Discover portable bundles under `--bundles`. A directory that itself looks like a bundle is
   * served under its own name; otherwise each immediate sub-directory that looks like a bundle
   * becomes a session keyed by its name.
   */
  private fun registerBundles(): Map<String, ServeBundleHost> {
    val root = bundlesDir?.let { File(it) }?.takeIf { it.isDirectory } ?: return emptyMap()
    val result = LinkedHashMap<String, ServeBundleHost>()
    if (ServeBundleHost.looksLikeBundle(root)) {
      result[root.name] = ServeBundleHost(root, root.name)
    } else {
      root
        .listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { sub ->
          if (ServeBundleHost.looksLikeBundle(sub))
            result[sub.name] = ServeBundleHost(sub, sub.name)
        }
    }
    if (result.isEmpty()) {
      System.err.println("serve: --bundles ${root.path} held no bundles (previews/*.png).")
    } else {
      System.err.println(
        "serve: serving ${result.size} bundle session(s): ${result.keys.joinToString(", ")}"
      )
    }
    return result
  }

  /**
   * Register every `--bundle <url|path>` as its own session. Served live from a daemon only when it
   * verifies `Trusted`, `--allow-render-trusted` is set and [ServeBundleDaemon.materialize] can
   * stand it up; otherwise read-only baked PNGs ([ServeBundleStore.add]). An `Unverified` bundle is
   * never re-rendered server-side. Best-effort per bundle; returns the ids that registered.
   */
  private fun registerStartupBundles(registry: ServeSessionRegistry): List<String> {
    if (bundleSpecs.isEmpty()) return emptyList()
    val root =
      java.nio.file.Files.createTempDirectory("serve-startup-bundles").toFile().also {
        it.deleteOnExit()
      }
    val bakedStore =
      ServeBundleStore(
        root = File(root, "baked").apply { mkdirs() },
        register = { id, host -> registry.register(id, host = host, pinned = true) },
        trust = { trustStore.get() },
      )
    val registered = mutableListOf<String>()
    for (spec in bundleSpecs) {
      val bytes = obtainBundleBytes(spec) ?: continue
      // Branch-origin trust for a raw.githubusercontent.com URL; null otherwise (only a signature
      // can make it Trusted). A ref can span slashes, so try each split and prefer one the trust
      // store trusts.
      val origins = ServeStartupBundles.candidateOrigins(spec.source)
      val origin =
        origins.firstOrNull { trustStore.get().trustsBranch(it.repo, it.branch) }
          ?: origins.firstOrNull()
      val bundleFile = File(root, "${spec.name}.bundle")
      try {
        bundleFile.writeBytes(bytes)
      } catch (e: Exception) {
        System.err.println("serve: bundle ${spec.name} could not be staged (${e.message})")
        continue
      }
      val verdict = BundleVerifier.verify(bundleFile, trustStore.get(), origin)
      // Live lane: Trusted + operator opt-in. A desktop bundle materialises a daemon straight from
      // the bundle (no build); a non-desktop/foreign/empty bundle returns null → falls to baked.
      if (allowRenderTrusted && verdict is BundleVerifier.Verdict.Trusted) {
        val destDir = File(root, "${spec.name}-live").apply { mkdirs() }
        val state =
          ServeBundleDaemon.materialize(
            bundleFile,
            destDir,
            spec.name,
            extraMavenRepos = extraMavenRepos,
          )
        val host = state?.let { openHost(it) }
        if (state != null && host != null) {
          registry.register(spec.name, state, host = host)
          System.err.println(
            "serve: bundle ${spec.name} → LIVE from bundle (no build), " +
              "trust=${BundleVerifier.summary(verdict)} (/${spec.name}/)"
          )
          registered += spec.name
          continue
        }
      } else if (allowRenderTrusted) {
        System.err.println(
          "serve: bundle ${spec.name} is ${BundleVerifier.summary(verdict)} — not live-rendering; " +
            "serving baked PNGs"
        )
      }
      // Read-only fallback: serve the bundle's baked previews/<id>.png (executes no code).
      when (val r = bakedStore.add(spec.name, bytes, isSecurityChecked = true, origin = origin)) {
        is ServeBundleStore.Result.Ok -> {
          registered += r.name
          System.err.println(
            "serve: bundle ${r.name} → ${r.previewCount} baked preview(s), trust=${r.trust} " +
              "(/${r.name}/)"
          )
        }
        is ServeBundleStore.Result.Failed ->
          System.err.println("serve: bundle ${spec.name} not served: ${r.reason}")
      }
    }
    return registered
  }

  /** Fetch (URL) or read (local path) a `--bundle` spec's bytes; null (logged) on any failure. */
  private fun obtainBundleBytes(spec: ServeStartupBundles.Spec): ByteArray? {
    if (ServeStartupBundles.isUrl(spec.source)) {
      val bytes = ServeStartupBundles.fetch(spec.source)
      if (bytes == null) {
        System.err.println("serve: bundle ${spec.name} fetch failed (${spec.source})")
      }
      return bytes
    }
    val f = File(spec.source)
    if (!f.isFile) {
      System.err.println("serve: bundle ${spec.name} path not found: ${spec.source}")
      return null
    }
    return try {
      f.readBytes()
    } catch (e: Exception) {
      System.err.println("serve: bundle ${spec.name} read failed (${e.message})")
      null
    }
  }

  /**
   * Result of [registerCatalogs]: Wasm app dirs, the store a refresher re-loads from, and the
   * configured/load state exposed through status. Each `--catalogs` system is registered as a
   * pinned session, Trusted-by-origin or `unverified`; best-effort per system.
   */
  private class CatalogRegistration(
    /**
     * Served catalogs' Wasm apps, live: the server reads this map, so runtime publishes and
     * retirements apply immediately.
     */
    val wasm: MutableMap<String, File>,
    val store: ServeCatalogStore,
    val loads: CatalogLoadTracker,
    val loader: InitialCatalogLoader,
  )

  private inner class InitialCatalogLoader(
    private val store: ServeCatalogStore,
    private val loads: CatalogLoadTracker,
  ) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-initial-load").apply { isDaemon = true }
    }
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
      // Claimed at construction rather than at [start], so the window between the listener binding
      // and the first catalog load isn't a gap the theme optimizer can start in.
      backgroundWork.expectInitialCatalogLoad()
    }

    fun start(onComplete: (Set<String>) -> Unit = {}) {
      if (!started.compareAndSet(false, true)) return
      executor.execute {
        val loaded = linkedSetOf<String>()
        try {
          // Fetch order, not front-page order, so load-bearing catalogs return first after a
          // restart.
          while (true) {
            val seed = loads.nextInitialLoad() ?: break
            if (closed.get()) return@execute
            val (config, result) =
              synchronized(catalogRegistrationLock) {
                val current = loads.configFor(seed.system) ?: return@synchronized null
                val result = runCatching {
                  store.load(current.system, sourceRepo = current.repo)
                }
                  .getOrElse {
                    ServeCatalogStore.Result.Failed(
                      current.system,
                      it.message ?: it::class.simpleName ?: "load failed",
                    )
                  }
                loads.record(result)
                current to result
              } ?: continue
            when (val r = result) {
              is ServeCatalogStore.Result.Ok -> {
                if (config.listed) registeredCatalogs += r.system
                else registeredUnlistedCatalogs += r.system
                // Seed a settled head only for a complete read; an incomplete one must be re-read
                // on the first tick.
                if (!r.incomplete) loaded += r.system
                System.err.println(
                  "serve: catalog ${r.system} → ${r.previewCount} preview(s), trust=${r.trust} " +
                    "(/${r.system}/${if (config.listed) "" else ", unlisted"})"
                )
              }
              is ServeCatalogStore.Result.Failed ->
                System.err.println("serve: catalog ${r.system} not served: ${r.reason}")
            }
          }
          System.err.println("serve: ${loads.startupSummary()}")
        } finally {
          // However the pass ended — loaded, failed, or shut down mid-pass — background catalog
          // work is free to start; leaving it claimed would park the optimizer forever.
          backgroundWork.initialCatalogLoadFinished()
          sweepThemeCache()
          sweepCatalogBlobs(force = true)
          if (!closed.get()) onComplete(loaded)
        }
      }
    }

    override fun close() {
      closed.set(true)
      backgroundWork.initialCatalogLoadFinished()
      executor.shutdownNow()
    }
  }

  private fun registerCatalogs(
    registry: ServeSessionRegistry,
    worktrees: GitWorktrees?,
    openHost: (ServeSessionState) -> ServeHost?,
  ): CatalogRegistration {
    val dir =
      java.nio.file.Files.createTempDirectory("serve-catalogs").toFile().also { it.deleteOnExit() }
    // Concurrent because it's read by request threads while a background catalog refresh — or an
    // admin registration — writes to it.
    val wasm = java.util.concurrent.ConcurrentHashMap<String, File>()
    val loads =
      CatalogLoadTracker(
        catalogRefs.map { ref ->
          CatalogLoadTracker.Config(
            system = ref.system,
            listed = ref.listed,
            repo = ref.repo,
            branch = "$catalogBranchPrefix${ref.system}",
            group = ref.group,
            importedFrom = ref.importedFrom,
            loadPriority = ref.loadPriority,
            designSystem = ref.designSystem,
          )
        }
      )
    // The sweeper needs configured-but-unloaded systems, which only the tracker knows.
    themeCacheConfiguredSystems = loads::configuredSystems
    val store =
      ServeCatalogStore(
        root = dir,
        register = { id, host -> publishStaticCatalog(id, host, registry) },
        trust = { trustStore.get() },
        repo = catalogRepo,
        branchPrefix = catalogBranchPrefix,
        maxImages = catalogMaxImages,
        blobs = catalogBlobPool,
        serverSideRenderEnabled = allowRenderTrusted,
        liveLaneFailure = liveLaneLaunchLog::lastReason,
        // Post-publish lanes may be throttled after the head was recorded; un-settle the revision
        // so the next poll re-reads it.
        onPostPublishIncomplete = { system -> activeRefresher?.forgetHeads(listOf(system)) },
        registerWasm = { system, wasmDir ->
          // A local `--wasm-dir` override is never displaced or withdrawn by a published app.
          if (system !in localWasm) {
            if (wasmDir == null) {
              // Withdrawn rather than left pointing at the outgoing generation's copy, which would
              // run old code and then 404.
              if (wasm.remove(system) != null) {
                System.err.println(
                  "serve: catalog $system no longer carries an in-browser Wasm app"
                )
              }
            } else {
              val fresh = wasm.put(system, wasmDir) == null
              if (fresh) {
                System.err.println(
                  "serve: catalog $system carries an in-browser Wasm app (/wasm/$system/)"
                )
              }
            }
          }
        },
        buildTrustedBundle = {
          system,
          bundleFile,
          externalResourcesDir,
          alias,
          bakedFallback,
          perPreviewBundle ->
          buildTrustedCatalogBundle(
            system,
            bundleFile,
            externalResourcesDir,
            alias,
            bakedFallback,
            perPreviewBundle,
            registry,
            openHost,
          )
        },
        buildTrustedBundles = { system, bundles, bakedFallback ->
          buildTrustedCatalogBundles(
            system,
            bundles,
            bakedFallback,
            registry,
            openHost,
          )
        },
        recordTrustedBundles = { system, bundles -> recordCatalogCompileTargets(system, bundles) },
        clearTrustedBundles = catalogLiveBundles::remove,
        buildTrustedSource = { system, source, alias, bakedFallback ->
          buildTrustedCatalogSource(
            system,
            source,
            alias,
            bakedFallback,
            registry,
            worktrees,
            openHost,
          )
        },
      )
    return CatalogRegistration(
      wasm = wasm,
      store = store,
      loads = loads,
      loader = InitialCatalogLoader(store, loads),
    )
  }

  /**
   * Wire the runtime catalog admin ([ServeCatalogAdmin]): registrations fetch through [store], land
   * in [loads], and are written back to `--catalogs-file`. Retiring a catalog drops its session and
   * per-preview pool.
   */
  private fun buildCatalogAdmin(
    registry: ServeSessionRegistry,
    store: ServeCatalogStore,
    loads: CatalogLoadTracker,
    wasmCatalogs: MutableMap<String, File>,
    /** So retiring a catalog a hostname is published as is refused rather than stranding it. */
    sites: ServeSiteRegistry,
  ): ServeCatalogAdmin =
    ServeCatalogAdmin(
      tracker = loads,
      sites = sites,
      defaultRepo = catalogRepo,
      branchPrefix = catalogBranchPrefix,
      configFile = catalogsFile,
      groups = catalogsConfig.groups,
      // The same monitor `load` takes below, so a re-point holds it across the provenance record
      // too — see the parameter's doc for the interleaving that closes.
      registrationLock = catalogRegistrationLock,
      load = { system, repo ->
        backgroundWork.whileLoadingCatalog {
          synchronized(catalogRegistrationLock) {
            val result = store.load(system, sourceRepo = repo)
            loads.record(result)
            (result as? ServeCatalogStore.Result.Failed)?.reason
          }
        }
      },
      unload = { system ->
        // Shared with the registry sync's retirement ([unloadCatalog]) so the two cannot forget
        // different things.
        unloadCatalog(registry, wasmCatalogs, system)
      },
    )

  /**
   * The reconciler keeping the catalog set in step with nominated registry projects
   * ([ServeCatalogRegistrySync]), or null. Publishes via the tracker and store directly rather than
   * [ServeCatalogAdmin], because registry entries are derived state and must not persist in
   * `catalogs.json` after the registry drops them.
   */
  private fun buildCatalogRegistrySync(
    registry: ServeSessionRegistry,
    store: ServeCatalogStore,
    loads: CatalogLoadTracker,
    wasmCatalogs: MutableMap<String, File>,
    sites: ServeSiteRegistry,
  ): ServeCatalogRegistrySync? {
    if (catalogRegistryRepos.isEmpty()) return null
    val sync =
      ServeCatalogRegistrySync(
        repos = catalogRegistryRepos,
        read = { nomination, onProblem ->
          ServeCatalogRegistry.fetch(nomination, ::fetchRegistryDocument) {
            onProblem(it)
            System.err.println("serve: $it")
          }
        },
        tracked = { loads.snapshot().mapTo(linkedSetOf()) { it.config.system } },
        publish = { contribution, entry ->
          val config =
            CatalogLoadTracker.Config(
              system = entry.system,
              listed = entry.listed,
              repo = contribution.repo,
              branch = "$catalogBranchPrefix${entry.system}",
              group = contribution.homeGroup(entry),
              importedFrom = entry.importedFrom,
              loadPriority = entry.loadPriority,
              designSystem = entry.group == ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP,
            )
          if (!loads.add(config)) {
            "already published"
          } else {
            val failure = backgroundWork.whileLoadingCatalog {
              synchronized(catalogRegistrationLock) {
                val result = store.load(entry.system, sourceRepo = contribution.repo)
                loads.record(result)
                (result as? ServeCatalogStore.Result.Failed)?.reason
              }
            }
            // Roll back a failed publish so the next pass can retry instead of seeing "already
            // published".
            if (failure != null) {
              loads.remove(entry.system)
              runCatching { unloadCatalog(registry, wasmCatalogs, entry.system) }
            }
            failure
          }
        },
        retire = { system ->
          // A hostname published onto this catalog outranks the registry: dropping the session
          // would strand the site exactly as an admin retire would, so leave it and say so.
          val host = sites.hostFor(system)
          if (host != null) {
            System.err.println(
              "serve: catalog $system is no longer listed by its registry but is published as " +
                "the top-level site '$host' — keeping it"
            )
          } else {
            loads.remove(system)
            unloadCatalog(registry, wasmCatalogs, system)
          }
        },
        intervalMillis = catalogRefreshSeconds * 1000,
      )
    // The sync owns catalogs the boot fold-in registered from a registry, but not those an operator
    // (or earlier registry) already claimed.
    val claimed = operatorCatalogRefs().mapTo(hashSetOf()) { it.system }
    for (contribution in catalogRegistryContributions) {
      sync.adopt(contribution, contribution.entries.filter { claimed.add(it.system) })
    }
    return sync
  }

  /**
   * Drop a catalog's session, pools, live bundles, nav entries and Wasm app; shared by the admin
   * API and registry sync so both forget the same things.
   */
  private fun unloadCatalog(
    registry: ServeSessionRegistry,
    wasmCatalogs: MutableMap<String, File>,
    system: String,
  ) {
    synchronized(catalogRegistrationLock) {
      registry.unregister(system)
      catalogPerPreviewPools.remove(system)?.let { runCatching { it.close() } }
      catalogLiveBundles.remove(system)
      forgetCmpAndroidPlayer(system)
      registeredCatalogs.remove(system)
      registeredUnlistedCatalogs.remove(system)
      // Never drop a local `--wasm-dir` the operator configured; it isn't the catalog's to remove.
      if (system !in localWasm) wasmCatalogs.remove(system)
    }
    // Forget its branch head so a re-listing at the same commit is re-read.
    activeRefresher?.forgetHeads(listOf(system))
  }

  /**
   * The background poller keeping catalogs fresh against their branches ([ServeCatalogRefresher]).
   * A successful re-load re-registers the host in place and rewrites its `web/wasm/` dir.
   */
  private fun buildCatalogRefresher(
    store: ServeCatalogStore,
    loads: CatalogLoadTracker,
  ): ServeCatalogRefresher? {
    // Built even with no configured catalogs on an admin-enabled server, since entries are read
    // from the tracker per pass. Not gated on the poll interval: interval 0 only means [start]
    // isn't called, and `POST /<system>/refresh` must still work.
    if (!needsCatalogMachinery) return null
    // Read from the tracker per pass, not from the startup refs: a catalog published through the
    // admin API must start being polled without a restart (and a retired one must stop).
    val entries = {
      loads.snapshot().map {
        ServeCatalogRefresher.Entry(
          system = it.config.system,
          repo = it.config.repo,
          branch = it.config.branch,
        )
      }
    }
    return ServeCatalogRefresher(
      entries = entries,
      reload = { system, repo ->
        val result = backgroundWork.whileLoadingCatalog {
          synchronized(catalogRegistrationLock) {
            // Decline if the catalog was re-pointed since this pass snapshotted it: reloading the
            // old repo would serve from a repository it had left. The admin just fetched the new
            // one, and the next pass picks it up.
            if (!loads.stillPointsAt(system, repo)) return@synchronized null
            val result = store.load(system, sourceRepo = repo)
            loads.record(result)
            result
          }
        }
        // Handed back as-is: what a result means for the recorded head is the refresher's to
        // decide, and saying it twice is how the two would drift.
        if (result is ServeCatalogStore.Result.Failed) {
          System.err.println("serve: catalog $system refresh failed: ${result.reason}")
        } else if (result != null) {
          // A moved branch may also carry a builder catalog; refresh its definition and runtime
          // without a restart.
          runCatching { uiBuilderPublishedRefresh?.invoke(system) }
            .onFailure {
              System.err.println("serve: UI-builder catalog $system refresh failed: ${it.message}")
            }
        }
        result
      },
      intervalMillis = catalogRefreshSeconds * 1000,
    )
  }

  /**
   * Build a Trusted catalog's `liveBundle` into a daemon-backed session via
   * [ServeBundleDaemon.materialize] (no Gradle, worktree or clone); preferred over
   * [buildTrustedCatalogSource]. Adds the remaining gate: `--allow-render-trusted`. Returns true
   * once registered; false ⇒ fall back to the source build, then the static host.
   */
  private fun buildTrustedCatalogBundle(
    system: String,
    bundleFile: File,
    externalResourcesDir: File?,
    alias: Map<String, String>,
    bakedFallback: () -> ServeHost,
    perPreviewBundle: ee.schimke.composeai.cli.serve.PerPreviewBundleAccess,
    registry: ServeSessionRegistry,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted) return false
    val destDir =
      java.nio.file.Files.createTempDirectory("serve-catalog-bundle-$system").toFile().also {
        it.deleteOnExit()
      }
    // Per-preview live lane: a bounded idle-LRU pool of daemons, each from its preview's own split
    // bundle and sharing the monolithic bundle's font pool ([externalResourcesDir]). Its states
    // carry no alias, so openHost returns a bare daemon; failures fall back to the monolithic
    // daemon. Seat weight is set through a holder because the pool is built before materialization.
    var perPreviewSeatWeight = 1
    val perPreviewPool =
      ServePerPreviewDaemonPool(
        liveSeats = liveSeatLimiter,
        seatWeight = { perPreviewSeatWeight },
      ) { daemonId ->
        val ppFile = perPreviewBundle.fetch(daemonId) ?: return@ServePerPreviewDaemonPool null
        val ppDest =
          java.nio.file.Files.createTempDirectory("serve-catalog-preview-$system").toFile().also {
            it.deleteOnExit()
          }
        val ppState =
          ServeBundleDaemon.materialize(
            ppFile,
            ppDest,
            system,
            extraMavenRepos = extraMavenRepos,
            extraClasspathDirs = listOfNotNull(externalResourcesDir),
          ) ?: return@ServePerPreviewDaemonPool null
        openHost(ppState)
      }
    // Carry the alias, baked fallback and per-preview lane on the state so openHost fronts the
    // daemon with the baked catalog ([ServeCatalogLiveHost]). The rehydrated resource pool joins
    // the daemon classpath.
    val materialized =
      ServeBundleDaemon.materialize(
        bundleFile,
        destDir,
        system,
        extraMavenRepos = extraMavenRepos,
        extraClasspathDirs = listOfNotNull(externalResourcesDir),
        // Still prints as before; also keeps the last line, which on the failure path IS the
        // reason materialize returned null. ServeCatalogStore appends it to the degradation.
        onLog = liveLaneLaunchLog.sink(system),
      )
        ?: run {
          perPreviewPool.close()
          return false
        }
    val state =
      materialized.copy(
        previewAliases = alias,
        bakedFallback = bakedFallback,
        perPreviewResolve = perPreviewPool::get,
        executableBundleAvailable = perPreviewBundle.available,
        executableBundleProvider = { daemonId ->
          perPreviewBundle.fetch(daemonId)?.takeIf(File::isFile)?.readBytes()
        },
        perPreviewStreamCount = perPreviewPool::activeStreamCount,
        perPreviewRenderStats = perPreviewPool::renderPerfStats,
        perPreviewPoolStats = { listOf(perPreviewPool.snapshot()) },
        perPreviewReapIdle = perPreviewPool::reapIdle,
        catalogThemeCache = themeCacheFor(system, alias, materialized.descriptor),
        serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
        backgroundWork = backgroundWork,
      )
    // Now that the backend is known, the pool's daemons charge this catalog's real weight — an
    // Android/Robolectric per-preview daemon is not the same cost to the box as a desktop one.
    perPreviewSeatWeight = state.liveSeatWeight
    // Whether this bundle can run the CMP player is a fact about its classpath, read once here.
    val carriesCmpPlayer = ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(bundleFile)
    recordCmpAndroidPlayer(state.descriptor) { carriesCmpPlayer }
    val host =
      openHost(state)
        ?: run {
          // Nothing logged this: the bundle materialized and the render host still refused to open
          // it, which no `materialize` message covers.
          liveLaneLaunchLog.record(
            system,
            "the daemon materialized but its render host would not open",
          )
          perPreviewPool.close()
          return false
        }
    if (!publishCatalogRuntime(system, state, host, perPreviewPool, registry)) {
      liveLaneLaunchLog.record(system, "the live host could not be published for this catalog")
      return false
    }
    commitCmpAndroidPlayer(system, state.descriptor)
    // Up: drop anything the attempt recorded so a later failure can never report a stale line, and
    // an informational one (the Skiko pairing repair) is never mistaken for a failure at all.
    liveLaneLaunchLog.clear(system)
    System.err.println("serve: catalog $system → LIVE from bundle (no build) (?session=$system)")
    return true
  }

  /**
   * Publish a catalog host and its captured resources as one ownership transfer: ownership maps
   * move first, then the registry entry; on failure both are restored and the unpublished resources
   * closed. Atomic under the caller's registration lock.
   */
  private fun publishCatalogRuntime(
    system: String,
    state: ServeSessionState,
    host: ServeHost,
    resources: AutoCloseable,
    registry: ServeSessionRegistry,
  ): Boolean {
    var previousResources: AutoCloseable? = null
    try {
      synchronized(catalogRegistrationLock) {
        previousResources = catalogPerPreviewPools.put(system, resources)
        try {
          registry.register(system, state, host = host)
        } catch (failure: Throwable) {
          previousResources?.let { catalogPerPreviewPools[system] = it }
            ?: catalogPerPreviewPools.remove(system, resources)
          throw failure
        }
      }
    } catch (failure: Throwable) {
      runCatching { host.close() }
      runCatching { resources.close() }
      System.err.println("serve: catalog $system publication failed (${failure.message})")
      return false
    }
    previousResources?.let { runCatching { it.close() } }
    // Only after successful publication is the superseded generation safe to reclaim.
    sweepThemeCache()
    sweepCatalogBlobs()
    return true
  }

  /** Record every verified module bundle as an independent lazy playground compile target. */
  private fun recordCatalogCompileTargets(
    system: String,
    bundles: List<ServeCatalogStore.VerifiedModuleBundle>,
  ) {
    catalogLiveBundles[system] = bundles.mapIndexed { index, bundle ->
      val metadata = runCatching { BundleReader.readMetadata(bundle.file).manifest }.getOrNull()
      CatalogLiveBundle(
        id = catalogTargetId(system, bundle.module, primary = index == 0),
        module = bundle.module.ifBlank { metadata?.modulePath.orEmpty() },
        file = bundle.file,
        backend = metadata?.backend,
      )
    }
  }

  /** Replace a formerly-live catalog without leaving stale pools or playground targets behind. */
  private fun publishStaticCatalog(
    system: String,
    host: ServeHost,
    registry: ServeSessionRegistry,
  ) {
    var previousResources: AutoCloseable? = null
    synchronized(catalogRegistrationLock) {
      previousResources = catalogPerPreviewPools.remove(system)
      try {
        registry.register(system, host = host, pinned = true)
      } catch (failure: Throwable) {
        previousResources?.let { catalogPerPreviewPools[system] = it }
        throw failure
      }
    }
    previousResources?.let { runCatching { it.close() } }
  }

  /** Stand up one independently materialised live runtime per module and route by namespaced id. */
  private fun buildTrustedCatalogBundles(
    system: String,
    bundles: List<ServeCatalogStore.TrustedModuleBundle>,
    bakedFallback: () -> ServeHost,
    registry: ServeSessionRegistry,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted || bundles.isEmpty()) return false
    data class Runtime(
      val published: ServeCatalogStore.TrustedModuleBundle,
      val state: ServeSessionState,
      val pool: ServePerPreviewDaemonPool,
      var monolithic: ServeHost? = null,
    )

    val opened = mutableListOf<Runtime>()
    var publishedSuccessfully = false
    try {
      for ((index, published) in bundles.withIndex()) {
        var seatWeight = 1
        val pool =
          ServePerPreviewDaemonPool(
            liveSeats = liveSeatLimiter,
            seatWeight = { seatWeight },
          ) { daemonId ->
            val file =
              published.perPreviewBundle.fetch(daemonId) ?: return@ServePerPreviewDaemonPool null
            val dest =
              java.nio.file.Files.createTempDirectory("serve-catalog-preview-$system-$index")
                .toFile()
                .also { it.deleteOnExit() }
            val state =
              ServeBundleDaemon.materialize(
                file,
                dest,
                "$system:${published.module}",
                extraMavenRepos = extraMavenRepos,
                extraClasspathDirs = listOfNotNull(published.externalResourcesDir),
              ) ?: return@ServePerPreviewDaemonPool null
            openHost(state)?.let { ServeModuleLiveHost(it, published.localPreviewIds) }
          }
        val dest =
          java.nio.file.Files.createTempDirectory("serve-catalog-module-$system-$index")
            .toFile()
            .also { it.deleteOnExit() }
        val state =
          ServeBundleDaemon.materialize(
            published.file,
            dest,
            "$system:${published.module}",
            extraMavenRepos = extraMavenRepos,
            extraClasspathDirs = listOfNotNull(published.externalResourcesDir),
            // Keyed by catalog, not by module: the store looks the reason up by catalog id, and
            // the message materialize logs already names `<system>:<module>`.
            onLog = liveLaneLaunchLog.sink(system),
          )
            ?: run {
              pool.close()
              return false
            }
        seatWeight = state.liveSeatWeight
        opened += Runtime(published, state, pool)
      }

      val primary = opened.first()
      for (runtime in opened.drop(1)) {
        runtime.monolithic =
          openHost(runtime.state)?.let {
            ServeModuleLiveHost(it, runtime.published.localPreviewIds)
          } ?: return false
      }
      val ownerByDaemonId =
        opened
          .flatMap { runtime ->
            runtime.published.alias.values.map { daemonId -> daemonId to runtime }
          }
          .toMap()
      val alias = opened.flatMap { it.published.alias.entries }.associate { it.toPair() }
      // Per module: each daemon id runs on its own module's bundle, so each answers for itself.
      val cmpPlayerDaemonIds =
        opened
          .filter { ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(it.published.file) }
          .flatMapTo(HashSet()) { it.published.alias.values }
      val resolver: (String) -> ServeHost? = { daemonId ->
        val runtime = ownerByDaemonId[daemonId]
        when {
          runtime == null -> null
          runtime === primary -> runtime.pool.get(daemonId)
          else -> runtime.pool.get(daemonId) ?: runtime.monolithic
        }
      }
      val state =
        primary.state.copy(
          previewAliases = alias,
          bakedFallback = bakedFallback,
          perPreviewResolve = resolver,
          executableBundleAvailable = { daemonId ->
            ownerByDaemonId[daemonId]?.published?.perPreviewBundle?.available?.invoke(daemonId)
              ?: false
          },
          executableBundleProvider = { daemonId ->
            ownerByDaemonId[daemonId]
              ?.published
              ?.perPreviewBundle
              ?.fetch(daemonId)
              ?.takeIf(File::isFile)
              ?.readBytes()
          },
          perPreviewStreamCount = { opened.sumOf { it.pool.activeStreamCount() } },
          perPreviewRenderStats = {
            opened.flatMap { runtime ->
              buildList {
                addAll(runtime.pool.renderPerfStats())
                runtime.monolithic?.renderPerfStats()?.let(::add)
              }
            }
          },
          perPreviewPoolStats = { opened.map { it.pool.snapshot() } },
          perPreviewReapIdle = { idle -> opened.sumOf { it.pool.reapIdle(idle) } },
          catalogThemeCache =
            themeCacheFor(system, alias, *opened.map { it.state.descriptor }.toTypedArray()),
          serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
          backgroundWork = backgroundWork,
        )
      recordCmpAndroidPlayer(state.descriptor) { it in cmpPlayerDaemonIds }
      val host =
        openHost(state)
          ?: run {
            liveLaneLaunchLog.record(
              system,
              "the module daemons materialized but their render host would not open",
            )
            return false
          }
      val resources = AutoCloseable {
        opened.forEach { runtime ->
          runCatching { runtime.pool.close() }
          runCatching { runtime.monolithic?.close() }
        }
      }
      if (!publishCatalogRuntime(system, state, host, resources, registry)) {
        liveLaneLaunchLog.record(system, "the live host could not be published for this catalog")
        return false
      }
      commitCmpAndroidPlayer(system, state.descriptor)
      liveLaneLaunchLog.clear(system)
      System.err.println(
        "serve: catalog $system → LIVE from ${opened.size} module bundles (no build) (?session=$system)"
      )
      publishedSuccessfully = true
      return true
    } finally {
      // Once registered, ownership moves to catalogPerPreviewPools. Otherwise unwind partial opens.
      if (!publishedSuccessfully) {
        opened.forEach { runtime ->
          runCatching { runtime.pool.close() }
          runCatching { runtime.monolithic?.close() }
        }
      }
    }
  }

  /**
   * Build a Trusted catalog's source into a daemon-backed session (`--allow-render-trusted`).
   * Remaining gates: the flag, the source repo being [catalogRepo], and the ref clearing the
   * allowlist ([GitWorktrees.prepare]). Returns true once registered; false ⇒ baked PNGs.
   */
  private fun buildTrustedCatalogSource(
    system: String,
    source: ServeCatalogStore.CatalogSource,
    alias: Map<String, String>,
    bakedFallback: () -> ServeHost,
    registry: ServeSessionRegistry,
    worktrees: GitWorktrees?,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted || worktrees == null) return false
    if (source.repo.isNotBlank() && source.repo != catalogRepo) {
      System.err.println(
        "serve: catalog $system source repo '${source.repo}' != '$catalogRepo' — not live-rendering"
      )
      return false
    }
    if (source.ref.isBlank() || source.module.isBlank()) return false
    val repoRoot = catalogSourceRoot ?: gradleProjectRoot() ?: return false
    // The ref allowlist is enforced here (fail-closed): null = unresolvable or not in
    // --revisions-allow.
    val worktree =
      worktrees.prepare(source.ref)
        ?: run {
          System.err.println(
            "serve: catalog $system ref '${source.ref}' not allowed/resolvable — serving baked PNGs"
          )
          return false
        }
    // GradleRevisionBuilder prefixes `:` itself, so strip the leading colon (`::` fails every
    // build).
    val gradlePath = source.module.removePrefix(":")
    val relativePath = gradlePath.replace(":", "/")
    val bootstrapArgs =
      autoInjectInitScriptArgs(projectRoot = repoRoot) + gradleVariantArgs() + gradleBuildArgs()
    val builder =
      GradleRevisionBuilder(
        extraArgs = bootstrapArgs,
        onLog = { System.err.println("[serve build] $it") },
      )
    val built =
      builder.build(worktree, ServeModuleRef(gradlePath, relativePath), isSecurityChecked = true)
        ?: run {
          System.err.println(
            "serve: catalog $system build of ${source.module}@${source.ref} failed — serving baked PNGs"
          )
          return false
        }
    val state =
      ServeSessionState(
        descriptor = built.descriptor,
        workspaceRoot = built.moduleDir,
        workspaceName = built.moduleDir.name,
        previews = built.previews,
        label = "$system@${source.ref}",
        declaredThemes = built.declaredThemes,
        // Same alias and baked fallback as the bundle path.
        previewAliases = alias,
        bakedFallback = bakedFallback,
        catalogThemeCache = themeCacheFor(system, alias, built.descriptor),
        serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
        backgroundWork = backgroundWork,
        // Android source builds take the heavier live-seat weight, read from the built descriptor.
        liveSeatWeight = ServeBundleDaemon.liveSeatWeightForDescriptor(built.descriptor),
      )
    val host = openHost(state) ?: return false
    registry.register(system, state, host = host)
    // Registers directly rather than via `publishCatalogRuntime`, so it needs its own
    // post-publication sweep.
    sweepThemeCache()
    sweepCatalogBlobs()
    System.err.println(
      "serve: catalog $system → LIVE server-render from ${source.module}@${source.ref} " +
        "(?session=$system)"
    )
    return true
  }

  /**
   * Re-read every tracked catalog whose source branch [updated] trusts and [before] did not. Trust
   * is baked in at load and the refresher skips unchanged SHAs, so without this a registry catalog
   * trusted after loading keeps serving as `unverified`. Only forgets heads (no unregister): the
   * catalog serves correct content meanwhile. Scoped to the delta to avoid re-fetching everything.
   */
  private fun reverifyNewlyTrusted(
    before: TrustStore,
    updated: TrustStore,
    tracker: CatalogLoadTracker?,
  ) {
    val loads = tracker ?: return
    val affected =
      loads.snapshot().filter { state ->
        val repo = state.config.repo
        val branch = state.config.branch
        updated.trustsBranch(repo, branch) && !before.trustsBranch(repo, branch)
      }
    if (affected.isEmpty()) return
    for (state in affected) {
      System.err.println(
        "serve: re-verifying ${state.config.system} — ${state.config.repo}@${state.config.branch}" +
          " is now trusted"
      )
    }
    // Same lever the revocation path pulls, for the same reason: without clearing the remembered
    // head the next pass short-circuits on an unchanged SHA and the new verdict never lands.
    activeRefresher?.forgetHeads(affected.map { it.config.system })
  }

  /**
   * Drop every registered catalog whose source branch [updated] no longer trusts. Unregistering
   * stops daemons started under the old verdict; marking rows failed and forgetting heads gets them
   * re-fetched.
   */
  private fun retireNewlyUntrusted(
    updated: TrustStore,
    tracker: CatalogLoadTracker?,
    registry: ServeSessionRegistry,
  ) {
    val loads = tracker ?: return
    val retired = mutableListOf<String>()
    synchronized(catalogRegistrationLock) {
      for (state in loads.snapshot()) {
        val repo = state.config.repo
        val branch = state.config.branch
        if (updated.trustsBranch(repo, branch)) continue
        // Only a catalog that actually loaded under the old trust needs tearing down; a pending or
        // already-failed row has nothing serving to revoke.
        if (!state.available) continue
        registry.unregister(state.config.system)
        loads.recordFailure(state.config.system, "producer trust revoked; awaiting re-verification")
        retired += state.config.system
        System.err.println(
          "serve: retired ${state.config.system} — $repo@$branch is no longer trusted"
        )
      }
    }
    // Clear the remembered branch heads so the next refresh pass re-fetches these instead of
    // short-circuiting on an unchanged SHA — without this the teardown would be undone only by a
    // branch move or a restart.
    if (retired.isNotEmpty()) activeRefresher?.forgetHeads(retired)
  }

  /**
   * Load `--trust-store`, or the empty fail-closed store when unset. A bad path or file is a hard
   * error so an operator never silently trusts nothing.
   */
  private fun loadTrustStore(): TrustStore {
    val path = trustStorePath ?: return TrustStore.EMPTY
    val f = File(path)
    if (!f.isFile) {
      // With the trust admin armed, an absent file is a valid start (the operator will create it).
      // Otherwise it stays fatal.
      if (adminToken != null) {
        System.err.println("serve: --trust-store ${f.path} does not exist yet; starting with no")
        System.err.println("serve: trusted producers (add them via POST /admin/trust)")
        return TrustStore.EMPTY
      }
      System.err.println("serve: --trust-store not found: ${f.path}")
      exitProcess(1)
    }
    return try {
      TrustStore.load(f)
    } catch (e: Exception) {
      System.err.println("serve: could not parse --trust-store ${f.path}: ${e.message}")
      exitProcess(1)
    }
  }

  /**
   * Match a preview against `--id` / `--filter` / `--preview` via the shared
   * [previewIdMatchesRequest]; every selector given must hold.
   */
  /**
   * Each module paired with the previews it can serve for this request, dropping those with none.
   * Module selection had to keep parameterized previews that might match; with the fan-out on disk,
   * [ServeParameterRows] resolves that.
   */
  private fun modulesWithMatchingPreviews(
    manifests: List<Pair<PreviewModule, PreviewManifest>>
  ): List<Pair<PreviewModule, List<ServePreview>>> =
    manifests
      .map { (module, manifest) -> module to servablePreviewsOf(module, manifest) }
      .filter { (_, previews) -> previews.isNotEmpty() }

  /**
   * `@PreviewParameter` fan-out expansion: discovery emits one entry per parameterized function,
   * but the render wrote one file per value and the daemon accepts `<baseId>_<row>`, so each
   * on-disk row becomes its own preview (see #3749). Otherwise the single entry is kept.
   */
  private fun servablePreviewsOf(
    module: PreviewModule,
    manifest: PreviewManifest,
  ): List<ServePreview> {
    val claimedOutputs = ServeParameterRows.claimedOutputs(manifest.previews)
    return manifest.previews.flatMap { info ->
      val (focus, gestures) = detectedFeaturesOf(info)
      fun serve(id: String, label: String) =
        ServePreview(
          id = id,
          label = info.catalog?.caption?.takeIf { it.isNotBlank() } ?: label,
          uiMode = info.params.uiMode,
          showBackground = info.params.showBackground,
          backgroundColor = info.params.backgroundColor,
          supportsFocus = focus,
          supportsGestures = gestures,
          fixedTheme = info.fixedTheme,
          state = info.catalog?.state,
          props =
            info.catalog
              ?.props
              ?.takeIf { it.isNotEmpty() }
              ?.associate { it.key to JsonPrimitive(it.value) }
              ?.let(::JsonObject),
          section = info.catalog?.section,
          group = info.catalog?.group,
          sourceFile = info.sourceFile,
          bodyLine = info.bodyLine,
          componentId = info.catalog?.componentId,
        )
      val baseLabel = info.functionName.ifBlank { info.id }
      val rows = ServeParameterRows.rowsFor(info, module.projectDir, claimedOutputs)
      // Selectors match the declared preview (serving all its rows) or a row id directly. The
      // declared preview is matched as a manifest row because `--preview` also accepts
      // `<Class>.<function>` forms.
      when {
        rows.isEmpty() -> if (matches(info)) listOf(serve(info.id, baseLabel)) else emptyList()
        matches(info) -> rows.map { serve(it.id, "$baseLabel · ${it.label}") }
        else -> rows.filter { matches(it.id) }.map { serve(it.id, "$baseLabel · ${it.label}") }
      }
    }
  }

  private fun matches(preview: PreviewInfo): Boolean =
    previewIdMatchesRequest(
      preview.id,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      className = preview.className,
      functionName = preview.functionName,
    )

  /**
   * The same rule for an id with no manifest row (a `@PreviewParameter` row id); `--preview`
   * degrades to exact-or-substring.
   */
  private fun matches(id: String): Boolean =
    previewIdMatchesRequest(id, exactId = exactId, filter = filter, previewRef = previewRef)

  private fun runDaemonStart(module: PreviewModule): Boolean {
    return runGradleTasks(
      ":${module.gradlePath}:composePreviewDaemonStart",
      arguments = gradleBuildArgs(),
      silenceStdout = false,
    )
  }

  private fun buildGithubAuth(): ServeGithubAuth? {
    val provided =
      listOf(githubAuthClientId, githubAuthClientSecret, githubAuthCookieSecret, githubAuthRepo)
        .count { it != null }
    if (provided == 0) return null
    if (provided != 4) {
      error(
        "GitHub auth needs --github-auth-client-id, --github-auth-client-secret, " +
          "--github-auth-cookie-secret, and --github-auth-repo"
      )
    }
    return ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = githubAuthClientId!!,
        clientSecret = githubAuthClientSecret!!,
        cookieSecret = githubAuthCookieSecret!!,
        repository = githubAuthRepo!!,
        // So a session can answer for the image lane too, when it gates somewhere else. Null/equal
        // costs nothing: no second repository means no second GitHub call at sign-in.
        imageRepository = imageUploadRepository,
        allowedUsers = githubAuthUsers,
        allowedOrgs = githubAuthOrgs,
        allowGuests = githubAuthGuests,
        openUiBuilder = githubAuthOpenUiBuilder,
        callbackBaseUrl = githubAuthCallbackBaseUrl,
        cookieDomain = githubAuthCookieDomain,
        oauthScope = githubAuthScope,
        trustForwardedHeaders = trustForwardedFor,
      )
    )
  }

  /**
   * The `guidelines` check, when given a key and an allowlist. A key with nobody named is refused
   * at startup.
   */
  private fun buildUiBuilderGuidelines(
    env: Map<String, String> = System.getenv()
  ): ServeUiBuilderGuidelines? {
    // Either the variable or `<name>_FILE` (a Docker secret), which keeps the key out of this
    // process's environment block and so out of every child's ([ServeSecretEnv]).
    val key = ServeSecretEnv.read(ServeUiBuilderGuidelinesConfig.API_KEY_ENV, env)
    val named = uiBuilderGuidelinesUsers.isNotEmpty() || uiBuilderGuidelinesOrgs.isNotEmpty()
    if (key == null) {
      if (named) {
        System.err.println(
          "serve: --ui-builder-guidelines-users/-orgs set but " +
            "${ServeUiBuilderGuidelinesConfig.API_KEY_ENV} (or its ${ServeSecretEnv.FILE_SUFFIX}) " +
            "is not; the guidelines check is off"
        )
      }
      return null
    }
    val githubToken = ServeSecretEnv.read(ServeUiBuilderGuidelinesConfig.GITHUB_TOKEN_ENV, env)
    val config =
      ServeUiBuilderGuidelinesConfig(
        apiKey = key,
        model = uiBuilderGuidelinesModel ?: ServeUiBuilderGuidelinesConfig.DEFAULT_MODEL,
        allowedUsers = uiBuilderGuidelinesUsers,
        allowedOrgs = uiBuilderGuidelinesOrgs,
        githubToken = githubToken,
        triage = uiBuilderGuidelinesTriage,
      )
    return ServeUiBuilderGuidelines(
      config,
      ServeUiBuilderGuidelineAccess(
        config.allowedUsers,
        config.allowedOrgs,
        ServeUiBuilderGuidelineAccess.githubMembership(githubToken),
      ),
    )
  }

  /**
   * `settings.json` ([ServeSettings]) and the settings changeable at runtime (guidelines model,
   * allowlist, picture budget); anything else applies at next start.
   */
  private fun buildSettingsAdmin(
    guidelines: ServeUiBuilderGuidelines?,
    pictureBudget: java.util.concurrent.atomic.AtomicLong,
  ): ServeSettingsAdmin {
    val live = buildList {
      if (guidelines != null) add(ServeGuidelinesLiveSettings(guidelines))
      add(
        object : ServeLiveSettings {
          override val envs = setOf("SERVE_UI_BUILDER_GUIDELINES_PICTURE_BUDGET")

          override fun apply(values: Map<String, String?>) {
            pictureBudget.set(
              values.values.single()?.toLong() ?: DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS
            )
          }
        }
      )
    }
    return ServeSettingsAdmin(settingsFilePath?.toPath(), live = live)
  }

  private fun printBanner(moduleLabel: String, port: Int, token: String, previewCount: Int) {
    val exposed = ServeUrls.isExposed(host)
    val localHost = if (exposed || host == ServeUrls.LOOPBACK) ServeUrls.LOOPBACK else host
    ServeBanner.lines(
        moduleLabel = moduleLabel,
        localOrigin = ServeUrls.origin(localHost, port),
        networkOrigins =
          if (exposed) ServeUrls.siteLocalIpv4Addresses().map { ServeUrls.origin(it, port) }
          else null,
        token = token,
        tokenSupplied = tokenOverride != null,
        public = public,
        previewCount = previewCount,
        // The builder page, so `--no-open` callers of a projectless builder aren't handed a 404
        // root. Also matches `/ui-builder` exactly, which redirects.
        builderPath =
          effectiveOpenPath.takeIf { uiBuilderLaneOpen && isUiBuilderPath(openBrowserPath) },
        acceptDocs = acceptDocs,
        qr = System.console() != null,
      )
      .forEach(System.err::println)
  }

  /**
   * Render every preview once through the held session and write a portable bundle to [path] — a
   * `.zip` when the path ends in `.zip`, otherwise a directory. `--inline` bakes the PNGs into the
   * gallery for a single self-contained `index.html`.
   */
  private fun exportBundle(host: ServeRenderHost, moduleLabel: String, path: String) {
    val built =
      ServeBundle.build(
        previews = host.previews,
        title = moduleLabel,
        modulePath = moduleLabel,
        inline = inlineBundle,
      ) { preview ->
        when (val outcome = host.render(preview.id, PreviewOverrides())) {
          is RenderOutcome.Ok -> outcome.png
          is RenderOutcome.Failed -> {
            System.err.println("serve: ${preview.id} failed to render (${outcome.reason})")
            null
          }
          RenderOutcome.NotFound -> null
          // Sequential single-thread export — the per-daemon lock is never contended, so Busy
          // shouldn't occur; treat it like a skip (no PNG) if it somehow does.
          RenderOutcome.Busy -> null
        }
      }

    val target = File(path)
    if (path.endsWith(".zip", ignoreCase = true)) {
      target.absoluteFile.parentFile?.mkdirs()
      target.writeBytes(ServeBundle.zip(built.files))
    } else {
      target.mkdirs()
      ServeBundle.writeDir(built.files, target)
    }

    System.err.println(
      "serve: wrote bundle to ${target.path} " +
        "(${built.renderedCount}/${built.previewCount} previews" +
        (if (built.failed.isEmpty()) "" else ", ${built.failed.size} failed") +
        ")"
    )
  }
}
