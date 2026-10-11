package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.RecordingTestGenerator
import ee.schimke.composeai.daemon.client.DataProductWireException
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.CompileResultKind
import ee.schimke.composeai.daemon.protocol.CompileSourcesParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewExtensionDescriptor
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.SemanticsDelta
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.SemanticsBounds
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCommandCatalog
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.render.matrix.ContactSheet
import ee.schimke.composeai.render.matrix.MatrixAxes
import ee.schimke.composeai.render.matrix.MatrixCell
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Which tool surface a [DaemonMcpServer] presents: [NATIVE] is the full tool set; [STORYBOOK] is
 * only the Storybook-MCP-compatible subset, so a Storybook-trained agent sees no duplicate tools.
 */
enum class McpToolProfile {
  NATIVE,
  STORYBOOK,
}

/**
 * The Storybook alias tool names (excluding `status`, which both profiles expose), used to split
 * the two profiles from the single `buildFullToolDefs` source. Top-level because the async catalog
 * loader reads it on `toolCatalogExecutor` during construction, before later instance properties
 * initialize.
 */
private val STORYBOOK_ALIAS_NAMES =
  setOf(
    "list-all-documentation",
    "get-documentation-for-story",
    "preview-stories",
    "run-story-tests",
  )

/**
 * `render_preview`'s `outputSchema`. Its `structuredContent` mirrors its JSON text blocks
 * ([withJsonTextStructure]), whose shape varies with `observe`, `inline` and `crop`, so it is
 * declared as a bare object. Top-level for the same reason as [STORYBOOK_ALIAS_NAMES].
 */
private val RENDER_PREVIEW_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
  put("type", "object")
  putJsonObject("properties") {}
}

/**
 * `render_matrix`'s `outputSchema`: the `compose-preview-matrix/v1` summary. No field is required,
 * because a call the budget cut short answers with the `pending` object instead.
 */
private val RENDER_MATRIX_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
  put("type", "object")
  putJsonObject("properties") {
    putJsonObject("schema") { put("type", "string") }
    putJsonObject("uri") { put("type", "string") }
    putJsonObject("cellCount") { put("type", "integer") }
    putJsonObject("cells") {
      put("type", "array")
      putJsonObject("items") { put("type", "object") }
    }
    putJsonObject("pending") { put("type", "boolean") }
  }
}

/**
 * The wiring layer. Owns:
 * - The per-(workspace, module) preview catalog populated from daemon `discoveryUpdated`.
 * - The MCP resources surface (`list`, `read`, `subscribe`, `unsubscribe`).
 * - The MCP tools surface.
 * - Daemon `renderFinished` → `notifications/resources/updated` (for subscribers and watchers).
 * - Daemon `discoveryUpdated` → `notifications/resources/list_changed`.
 * - Watch propagation back to daemons via [WatchPropagator].
 * - History recording on every successful render via [HistoryStore]. The catalog stores the
 *   daemon's preview ids verbatim; URIs pair them with their (workspace, module).
 */
class DaemonMcpServer(
  private val supervisor: DaemonSupervisor,
  private val sessions: SessionRegistry = SessionRegistry(),
  private val subscriptions: Subscriptions = Subscriptions(),
  private val historyStore: HistoryStore = HistoryStore.NOOP,
  private val serverInfo: Implementation =
    Implementation(name = "compose-preview-mcp", version = MCP_VERSION),
  private val renderTimeoutMs: Long = 60_000,
  /**
   * Cadence (ms) of the background source-freshness poller, which runs the same
   * `ensureSourceFreshBeforeRender` probe as the on-demand path so edits reach the daemon
   * proactively. `0` disables it (tests); production defaults to 30 s.
   */
  private val sourcePollIntervalMs: Long = DEFAULT_SOURCE_POLL_INTERVAL_MS,
  /**
   * Cadence (ms) of the random-sampling determinism probe: re-renders an idle preview without a
   * `fileChanged` and checks `renderFinished.unchanged`; anything but `true` means the bytes
   * drifted with no source change. `0` disables it; production defaults to 10 minutes.
   */
  private val samplingIntervalMs: Long = DEFAULT_SAMPLING_INTERVAL_MS,
  private val fileSystem: FileSystem = SystemFileSystem,
  fullToolDefsLoader: (() -> List<ToolDef>)? = null,
  /**
   * Which tool surface this server presents (see [McpToolProfile]). Both profiles route through the
   * same handlers.
   */
  private val profile: McpToolProfile = McpToolProfile.NATIVE,
  /** Optional remote Design API facade; never gives MCP direct reducer or store access. */
  private val uiBuilderMcp: UiBuilderMcpAdapter? = null,
  /**
   * Workspace to auto-register on the first `render_preview` when nothing is registered and the
   * client offers no MCP roots; used only when it is a Gradle build. Null disables the fallback.
   */
  private val workingDirectory: File? = File(System.getProperty("user.dir")),
  /** Environment read for `ANTIGRAVITY_CONVERSATION_ID` when choosing where a preview card goes. */
  private val environment: Map<String, String> = System.getenv(),
  private val homeDirectory: File = File(System.getProperty("user.home")),
  /**
   * Recompiles a module before its daemon is told a source changed. `null` forwards `fileChanged`
   * and trusts something else (IDE, continuous build) to have written fresh classes;
   * [DaemonMcpMain] wires [GradleSourceCompiler].
   */
  private val sourceCompiler: SourceCompiler? = null,
  /**
   * Whether a recompile first tries the daemon's in-process `compileSources`. Off by default:
   * measured slower than a warm incremental Gradle `composePreviewCompile`, since it shares the
   * render daemon's heap and CPU. `COMPOSE_PREVIEW_COMPILE_IN_PROCESS=1` turns it on.
   */
  private val compileInProcess: Boolean = environment[COMPILE_IN_PROCESS_ENV] == "1",
  /** OpenAI MCP Extensions probe tools, only with `COMPOSE_PREVIEW_MCP_OPENAI_PROBE=1`. */
  private val openAiProbe: OpenAiProbe? = OpenAiProbe.fromEnvironment(environment),
  /** The `openai/settings` defaults file, shared with the CLI. */
  private val previewSettingsStore: PreviewSettingsStore =
    PreviewSettingsStore(PreviewSettingsStore.defaultFile(environment, homeDirectory)),
  /**
   * Prepares a registered build that has no daemon launch descriptor yet (`compose-preview mcp
   * install` never ran there) on first use. `null` turns that off; tests pass a fake runner.
   */
  private val projectBootstrap: ProjectBootstrap? = ProjectBootstrap(),
  /**
   * Wall-clock budget (ms) for one `render_preview` / `render_matrix` call, including registration,
   * Gradle bootstrap and daemon spawn. Hosts abort requests at about 60 s, so past the budget the
   * call returns `pending` while work continues, and the agent's retry attaches to it. `<= 0`
   * disables it; `COMPOSE_PREVIEW_MCP_CALL_BUDGET_MS` overrides the 45 s default.
   */
  private val callBudgetMs: Long =
    environment[CALL_BUDGET_ENV]?.toLongOrNull() ?: DEFAULT_CALL_BUDGET_MS,
  /** How long a budgeted call's finished result waits for its retry; tests shorten it. */
  private val uncollectedCallResultTtlMs: Long = UNCOLLECTED_CALL_RESULT_TTL_MS,
  /** Active local session folders shared with sibling sidebar processes. */
  private val activeDesignRoots: ActiveDesignRoots = ActiveDesignRoots(),
  /**
   * `design_open` and `ui://compose-ui-builder/editor`; null (and absent from every list) unless
   * the editor archive carries the MCP App shell.
   */
  private val uiBuilderDesign: UiBuilderDesignMcp? =
    UiBuilderDesignMcp.fromEnvironment(environment),
) {

  private val fullToolDefsLoader: () -> List<ToolDef> =
    fullToolDefsLoader ?: { effectiveFullToolDefs() }

  /** `settings_read` / `settings_update` / `doctor`; native profile only. */
  private val previewSettings =
    PreviewSettingsMcp(previewSettingsStore) {
      projectDoctorChecks(supervisor.listProjects(), environment) { project ->
        catalog.entries.filter { it.key.workspaceId == project.workspaceId }.sumOf { it.value.size }
      }
    }

  /**
   * Projects brought in only by [libraryProjects]' sweep of the machine-wide [WorkspaceStore]
   * (other chats' builds). Listed, but design discovery never scans them. Registering one, or
   * restoring it from this session's roots, claims it back.
   */
  private val storeOnlyProjects: MutableSet<WorkspaceId> = ConcurrentHashMap.newKeySet()

  /** The `previews_library` sidebar app; native profile only. */
  private val previewLibrary =
    PreviewLibrary(
      designs = {
        LocalDesignDiscovery.discover(
          activeDesignRoots.all() +
            supervisor
              .listProjects()
              .filter { it.workspaceId !in storeOnlyProjects }
              .map { it.path }
        )
      },
      snapshot = { projectId -> libraryProjects(projectId) },
    )

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  private val imageSizeOverride: ImageSizeOverride = ImageSizeOverride.detect()

  /** The `.rc` Remote Compose viewer: `rc_open` and `ui://compose-preview/rc-viewer`. */
  private val rcViewer =
    RcViewerMcp(subscribers = { uri -> subscriptions.sessionsSubscribedTo(uri) })

  /**
   * Counters surfaced via the `status` tool (probe outcomes, polling cycles, sampling determinism),
   * to diagnose stale renders without wire traces.
   */
  private val freshnessMetrics = FreshnessMetrics()

  /**
   * Source files whose edit a daemon has not been recompiled for yet. The poller only records here;
   * the next render (or `notify_file_changed`) runs [sourceCompiler] then forwards `fileChanged`.
   */
  private val pendingSources = ConcurrentHashMap<DaemonAddr, MutableSet<String>>()

  /** Why the last recompile of a module failed or could not run; cleared by the next success. */
  private val staleNotes = ConcurrentHashMap<DaemonAddr, StaleNote>()

  private val compileLocks = ConcurrentHashMap<DaemonAddr, Any>()

  private data class StaleNote(val sources: List<String>, val reason: String)

  /**
   * The recompile each module ran since its last render, consumed by that render's [EditCycleWork].
   * Absent means nothing was compiled.
   */
  private val compileWorkSinceRender = ConcurrentHashMap<DaemonAddr, CompileWork>()

  /**
   * Per workspace, the cached source walk that tells a render whether anything changed since the
   * module last compiled, so an edit nobody notified us about still recompiles first.
   */
  private val sourceTrees = ConcurrentHashMap<WorkspaceId, SourceTree>()

  /** The [SourceTree.generation] each module's last recompile covered. */
  private val compiledGeneration = ConcurrentHashMap<DaemonAddr, Long>()

  /**
   * Why a module's pending sources are pending: `notify` or `detected`; see [CompileWork.trigger].
   */
  private val pendingTrigger = ConcurrentHashMap<DaemonAddr, String>()

  /** Daemon clients that declined `compileSources`; their modules compile through Gradle. */
  private val inProcessDeclined: MutableSet<Any> =
    java.util.Collections.synchronizedSet(
      java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    )

  /** The last change-detection pass per module, reported as `_meta.work.scan`. */
  private val lastScan = ConcurrentHashMap<DaemonAddr, SourceTree.Refresh>()

  /**
   * The latest edit→render cycle's work per preview, returned as `render_preview`'s `_meta.work`.
   */
  private val lastCycleWork = ConcurrentHashMap<PreviewIdKey, EditCycleWork>()

  /**
   * Per-(workspace, module) catalog: preview-id → metadata. Written on the daemon's reader thread,
   * read on session threads.
   */
  private val catalog: ConcurrentHashMap<DaemonAddr, ConcurrentHashMap<String, PreviewEntry>> =
    ConcurrentHashMap()

  /** Changed sources, renders, subscriptions and pins: the Previews tray and `@`-mentions. */
  private val previewActivity = PreviewActivity()

  private val renderThumbnails = RenderThumbnails()

  /**
   * Last PNG digest each MCP client saw for a URI via `render_preview(inline=false)`.
   * Session-scoped so one agent's render never makes another agent's first frame look unchanged.
   */
  private val previousFileRenderHashes =
    ConcurrentHashMap<Session, ConcurrentHashMap<FileRenderKey, String>>()

  /** Process-owned, bounded cache for immutable path-shaped render results. */
  private val fileRenderCacheDir = Files.createTempDirectory("compose-preview-mcp-").toFile()

  private val fileRenderCacheLock = Any()

  /**
   * Per-(workspace, module, previewId) FIFO of [PendingRenderGroup]s. The head group's `renderNow`
   * is in flight; later groups wait for its `renderFinished`. Reads with equal overrides dedup onto
   * the tail group; each source-change refresh appends its own group.
   *
   * The serialization matters because the daemon coalesces concurrent renders of one preview
   * (PROTOCOL.md § 5): without it, two calls with different overrides would race, and the
   * by-previewId fanout would hand caller B caller A's bytes.
   */
  private val previewQueues = ConcurrentHashMap<PreviewIdKey, ArrayDeque<PendingRenderGroup>>()

  /**
   * Per-(workspace, module) count of consecutive `classpathDirty` self-loops since the last clean
   * spawn; see [onClasspathDirty] for the cap.
   */
  private val respawnAttempts = ConcurrentHashMap<DaemonAddr, Int>()

  /**
   * `(workspace, module, previewId, kind)` → latest payload from `renderFinished.dataProducts`
   * (shipped only for kinds subscribed via `subscribe_preview_data` or in the global
   * `attachDataProducts` set). Lets `get_preview_data` answer from cache. Each `renderFinished`
   * replaces every cached attachment for its URI; daemon-level wipes (classpathDirty, onClose) drop
   * the module's entries.
   */
  private val dataProductCache = ConcurrentHashMap<DataAttachKey, DataAttachmentEntry>()

  private val watchPropagator =
    WatchPropagator(
      subscriptions = subscriptions,
      previewIdProvider = { daemon ->
        val byId =
          catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]
            ?: return@WatchPropagator emptyList()
        byId.values.map { entry ->
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        }
      },
    )

  /**
   * Worker for slow daemon-lifecycle work (replacement spawn after `classpathDirty`, async first
   * spawn from `watch`). Multi-threaded so modules cold-start in parallel; `daemonFor` is
   * `computeIfAbsent`-safe so one module still spawns once. Daemon-flagged.
   */
  private val daemonLifecycleExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(DAEMON_LIFECYCLE_THREADS) { r ->
      Thread(r, "mcp-daemon-lifecycle").apply { isDaemon = true }
    }

  /**
   * Worker for follow-up render dispatches. `renderFinished` runs on the daemon client's reader
   * thread, and a synchronous `renderNow` from there would deadlock waiting on that same thread.
   */
  private val renderDispatchExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(RENDER_DISPATCH_THREADS) { r ->
      Thread(r, "mcp-render-dispatch").apply { isDaemon = true }
    }

  /**
   * Scheduled worker for periodic `notifications/progress` beats during slow renders
   * (self-cancelling when the render completes), plus the one-shot slow-catalog check.
   */
  private val progressBeatExecutor: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "mcp-progress-beat").apply { isDaemon = true }
    }

  /**
   * Runs the source-freshness poller and the sampling probe. Two threads so a slow probe can't
   * delay the next poll; daemon-flagged.
   */
  private val freshnessExecutor: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newScheduledThreadPool(2) { r ->
      Thread(r, "mcp-freshness").apply { isDaemon = true }
    }

  /**
   * Per-(workspace, module, previewId) count of in-flight sampling probes, so `onRenderFinished`
   * can classify the matching render as a probe. A simultaneous user render could be misattributed,
   * but the sampler only fires when [previewQueues] is empty for that preview.
   */
  private val pendingProbes =
    ConcurrentHashMap<PreviewIdKey, java.util.concurrent.atomic.AtomicInteger>()

  /**
   * Keeps the MCP handshake off the full command-catalog path: some clients enforce a tight startup
   * deadline. Serve a compact core surface first, build the full one in the background, and notify
   * clients only if that load was delayed.
   */
  private val toolCatalogExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
      Thread(r, "mcp-tool-catalog").apply { isDaemon = true }
    }

  private val fullToolCatalogWasDelayed = AtomicBoolean(false)
  @Volatile private var fullToolCatalogError: String? = null

  /**
   * Sessions served [bootstrapToolDefs] while [fullToolDefsFuture] was loading. Each must get
   * `notifications/tools/list_changed` once the full catalog is ready, whatever the load time, or
   * `listChanged` clients never see the full tools. Guarded by [bootstrapNotifyLock] against the
   * isDone/add race.
   */
  private val bootstrapNotifyLock = Any()
  private val bootstrapServedSessions = mutableSetOf<Session>()

  private val fullToolDefsFuture: CompletableFuture<List<ToolDef>> =
    CompletableFuture.supplyAsync({ this.fullToolDefsLoader() }, toolCatalogExecutor).also { future
      ->
      progressBeatExecutor.schedule(
        {
          if (!future.isDone) {
            fullToolCatalogWasDelayed.set(true)
          }
        },
        TOOL_CATALOG_NOTIFY_DELAY_MS,
        TimeUnit.MILLISECONDS,
      )
      future.whenComplete { _, error ->
        if (error != null) {
          fullToolCatalogError = error.message ?: error::class.java.simpleName
          System.err.println("compose-preview-mcp: full tool catalog failed: ${error.message}")
        } else {
          val toNotify =
            synchronized(bootstrapNotifyLock) {
              val snapshot = bootstrapServedSessions.toList()
              bootstrapServedSessions.clear()
              snapshot
            }
          toNotify.forEach { runCatching { it.notifyToolListChanged() } }
        }
      }
    }

  init {
    val router = supervisor.router()
    router.on("discoveryUpdated") { daemon, params -> onDiscoveryUpdated(daemon, params) }
    router.on("renderFinished") { daemon, params -> onRenderFinished(daemon, params) }
    router.on("renderFailed") { daemon, params -> onRenderFailed(daemon, params) }
    router.on("classpathDirty") { daemon, params -> onClasspathDirty(daemon, params) }
    router.on("historyAdded") { daemon, params -> onHistoryAdded(daemon, params) }
    router.onClose { daemon ->
      catalog.remove(DaemonAddr(daemon.workspaceId, daemon.modulePath))
      evictDataProductsForDaemon(daemon.workspaceId, daemon.modulePath)
      watchPropagator.forget(daemon)
    }
    if (sourcePollIntervalMs > 0) {
      freshnessExecutor.scheduleWithFixedDelay(
        ::runSourceFreshnessPoll,
        sourcePollIntervalMs,
        sourcePollIntervalMs,
        TimeUnit.MILLISECONDS,
      )
    }
    if (samplingIntervalMs > 0) {
      freshnessExecutor.scheduleWithFixedDelay(
        ::runRandomSamplingProbe,
        samplingIntervalMs,
        samplingIntervalMs,
        TimeUnit.MILLISECONDS,
      )
    }
  }

  /**
   * Stops background polling and follow-up render dispatch. Idempotent. Tests call it; production
   * relies on daemon-flagged executors.
   */
  fun shutdown() {
    runCatching { freshnessExecutor.shutdownNow() }
    runCatching { renderDispatchExecutor.shutdownNow() }
    runCatching { budgetedCallScope.cancel() }
    runCatching { activeDesignRoots.close() }
    runCatching { rcViewer.shutdown() }
    runCatching { uiBuilderDesign?.close() }
    synchronized(fileRenderCacheLock) { runCatching { fileRenderCacheDir.deleteRecursively() } }
  }

  // Public API consumed by the SDK-backed MCP session.

  fun newSession(input: java.io.InputStream, output: java.io.OutputStream): McpSession {
    lateinit var session: McpSession
    session =
      McpSession(
        serverInfo = serverInfo,
        options =
          composePreviewServerOptions(
            if (profile == McpToolProfile.NATIVE) PreviewSettingsMcp.capability else emptyMap()
          ),
        input = input,
        output = output,
        configure = { sdkSession ->
          installComposePreviewHandlers(
            sdkSession = sdkSession,
            session = session,
            listTools = { viewerLinkedToolDefs(currentToolDefs(session)) },
            listPrompts = {
              if (profile == McpToolProfile.NATIVE) ComposePreviewPrompts.list() else emptyList()
            },
            getPrompt = { name, arguments ->
              require(profile == McpToolProfile.NATIVE) { "unknown prompt: $name" }
              ComposePreviewPrompts.get(name, arguments)
            },
            callTool = { name, arguments, progressToken ->
              handleCallTool(session, name, arguments, progressToken)
            },
            listResources = { catalogResources() },
            readResource = { uri, progressToken ->
              handleReadResource(session, uri, progressToken)
            },
            subscribe = { uri ->
              subscriptions.subscribe(uri, session)
              previewActivity.watched(uri)
            },
            unsubscribe = { uri -> subscriptions.unsubscribe(uri, session) },
          )
        },
        onClose = { closeSession(session) },
        instructions =
          if (profile == McpToolProfile.NATIVE) { clientName -> localInstructionsFor(clientName) }
          else { _ -> null },
      )
    sessions.register(session)
    if (profile == McpToolProfile.NATIVE)
      activeDesignRoots.register(session, listOfNotNull(workingDirectory))
    return session
  }

  private fun closeSession(session: Session) {
    // Release the session's data-product subscriptions and tell daemons to unsubscribe keys whose
    // last reference dropped, so they don't leak. Best-effort: a gone or refusing daemon doesn't
    // block teardown.
    val released = subscriptions.forgetDataSubscriptions(session)
    released.forEach { key -> dispatchDataUnsubscribe(key) }
    subscriptions.forget(session)
    sessionRootsCache.remove(session)
    activeDesignRoots.remove(session)
    previousFileRenderHashes.remove(session)
    // Nobody can collect a closed session's budgeted calls: the key holds the session.
    inFlightCalls.keys.removeIf { it.session == session }
    sweepUncollectedCalls()
    synchronized(bootstrapNotifyLock) { bootstrapServedSessions.remove(session) }
    sessions.unregister(session)
  }

  // Resource list / read.

  private fun catalogResources(): List<ResourceDescriptor> {
    val out =
      mutableListOf(
        ResourceDescriptor(
          uri = MCP_APP_VIEWER_URI,
          name = "Compose Preview viewer",
          description = "Interactive render and matrix viewer for Compose Preview tools.",
          mimeType = MCP_APP_MIME_TYPE,
          meta = viewerResourceMeta(),
        )
      )
    out += rcViewer.resourceDescriptors()
    uiBuilderDesign?.resourceDescriptors()?.let(out::addAll)
    openAiProbe?.resources()?.let(out::addAll)
    if (profile == McpToolProfile.NATIVE) out.addAll(previewLibrary.resources())
    for ((addr, byId) in catalog) {
      for (entry in byId.values) {
        val uri =
          PreviewUri(
            workspaceId = addr.workspaceId,
            modulePath = addr.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        out.add(
          ResourceDescriptor(
            uri = uri.toUri(),
            name = entry.fqn.substringAfterLast('.'),
            description = entry.displayName ?: entry.fqn,
            mimeType = "image/png",
            // The viewer's "open in editor" action reads the source location from here.
            meta =
              entry.resolvedSourcePath?.let { sourceFile ->
                buildJsonObject {
                  put("sourceFile", sourceFile)
                  entry.bodyLine?.let { put("sourceLine", it) }
                }
              },
          )
        )
      }
    }
    return out.sortedBy { it.uri }
  }

  private fun handleReadResource(
    session: Session,
    uri: String,
    progressToken: JsonElement?,
  ): ReadResourceResult {
    if (uri == MCP_APP_VIEWER_URI) {
      return ReadResourceResult(
        contents =
          listOf(
            ResourceContents.Text(
              uri = uri,
              mimeType = MCP_APP_MIME_TYPE,
              text = viewerHtml(),
              meta = viewerResourceMeta(),
            )
          )
      )
    }
    rcViewer.readResource(uri)?.let {
      return it
    }
    uiBuilderDesign
      ?.readResource(uri, layout = previewSettingsStore.read().uiBuilderMcpAppLayout)
      ?.let {
        return it
      }
    openAiProbe?.readResource(uri)?.let {
      return it
    }
    previewLibrary.readResource(uri)?.let {
      return it
    }
    // History URIs short-circuit to `history/read` against the daemon — historical bytes are
    // immutable so there's no render path involved.
    HistoryUri.parseOrNull(uri)?.let { historyUri ->
      return readHistoryResource(uri, historyUri)
    }
    val resourceUri = PreviewUri.parseOrNull(uri) ?: error("Invalid compose-preview URI: '$uri'")
    val overrides =
      resourceUri.overridesJson?.let { raw ->
        val decoded = runCatching {
          decodePreviewOverrides(json.parseToJsonElement(raw))
        }
          .getOrElse { error("Invalid compose-preview resource overrides") }
        val daemon = supervisor.daemonFor(resourceUri.workspaceId, resourceUri.modulePath)
        val violations = validateOverrides(decoded, daemon)
        check(violations.isEmpty()) {
          "Invalid compose-preview resource overrides: ${violations.joinToString("; ")}"
        }
        decoded
      }
    val parsed = resourceUri.copy(overridesJson = null)
    val pngBytes = renderAndReadBytes(parsed, session, progressToken, overrides)
    val encoded = Base64.getEncoder().encodeToString(pngBytes)
    return ReadResourceResult(
      contents = listOf(ResourceContents.Blob(uri = uri, mimeType = "image/png", blob = encoded))
    )
  }

  /**
   * Reads a `compose-preview-history://…` resource via `history/read` with `inline = true`,
   * forwarding the daemon's base64 PNG. Falls back to `pngPath` when `pngBytes` is unset.
   */
  private fun readHistoryResource(uriString: String, uri: HistoryUri): ReadResourceResult {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    val result = daemon.client.historyRead(entryId = uri.entryId, inline = true)
    val blob =
      result.pngBytes
        ?: run {
          val file = File(result.pngPath)
          check(file.isFile) { "history/read pngPath does not exist: ${result.pngPath}" }
          Base64.getEncoder()
            .encodeToString(fileSystem.read(file.path.toPath()) { readByteArray() })
        }
    return ReadResourceResult(
      contents = listOf(ResourceContents.Blob(uri = uriString, mimeType = "image/png", blob = blob))
    )
  }

  private fun renderAndReadBytes(
    uri: PreviewUri,
    session: Session? = null,
    progressToken: JsonElement? = null,
    overrides: PreviewOverrides? = null,
  ): ByteArray {
    val outcome = awaitNextRender(uri, session, progressToken, overrides)
    return applyImageSizeOverride(outcome.pngBytes)
  }

  /**
   * Like [renderAndReadBytes] but returns the full-resolution PNG, before [applyImageSizeOverride],
   * so crop coordinates match `compose/semantics` `boundsInRoot`; the crop re-applies the cap.
   */
  private fun renderAndReadRawBytes(uri: PreviewUri, overrides: PreviewOverrides?): ByteArray {
    val outcome = awaitNextRender(uri, overrides = overrides)
    return outcome.pngBytes
  }

  /**
   * Submits a `renderNow` for [uri] and blocks until its `renderFinished`. Throws on failure or
   * timeout. Used by [renderAndReadBytes] and by `get_preview_data`'s auto-render fallback (which
   * only needs some render to have happened).
   *
   * [overrides] are the per-call display overrides (PROTOCOL.md § 5); null uses the discovery-time
   * RenderSpec. Concurrent calls are serialized per preview through [previewQueues].
   */
  private fun awaitNextRender(
    uri: PreviewUri,
    session: Session? = null,
    progressToken: JsonElement? = null,
    overrides: PreviewOverrides? = null,
  ): RenderOutcome.Finished {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    ensureSourceFreshBeforeRender(uri, daemon)
    detectSourceChanges(daemon)
    recompilePendingSources(daemon)
    val key = PreviewIdKey(uri.workspaceId, uri.modulePath, uri.previewFqn)
    val renderStartedAt = System.nanoTime()
    val future = java.util.concurrent.CompletableFuture<RenderOutcome>()
    // Atomically join the right group. `becameFront` records whether we created a new head group,
    // in which case we dispatch `renderNow` (outside the per-key lock, so it isn't held across
    // IPC). Otherwise the head's completion wakes or promotes us.
    var becameFront = false
    previewQueues.compute(key) { _, queue ->
      val q = queue ?: ArrayDeque()
      val tail = q.lastOrNull()
      if (tail != null && tail.overrides == overrides) {
        // Same-overrides dedup: we're woken with that group's bytes; no new `renderNow`.
        tail.futures.add(future)
      } else {
        val group = PendingRenderGroup(overrides = overrides)
        group.futures.add(future)
        if (q.isEmpty()) {
          group.sent = true
          becameFront = true
        }
        q.addLast(group)
      }
      q
    }
    // Optional progress beats when the client sent `_meta.progressToken`; the beat stops when the
    // future completes.
    val progressBeat = startProgressBeatIfNeeded(session, progressToken, future, uri)
    if (becameFront) {
      // Shard across replicas: same preview → same replica (cache locality, dedup), different
      // previews spread out. With replicasPerDaemon = 0 this is the primary.
      dispatchHeadRender(daemon, key, overrides)
    }
    val outcome =
      try {
        future.get(renderTimeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: java.util.concurrent.TimeoutException) {
        // Best-effort cleanup: drop our future, and drop the group if empty and not the in-flight
        // head (an empty head is popped by its eventual renderFinished).
        previewQueues.computeIfPresent(key) { _, q ->
          val containing = q.firstOrNull { it.futures.contains(future) }
          containing?.futures?.remove(future)
          if (containing != null && containing.futures.isEmpty() && q.first() !== containing) {
            q.remove(containing)
          }
          if (q.isEmpty()) null else q
        }
        progressBeat?.cancel(true)
        error("awaitNextRender: timed out after ${renderTimeoutMs}ms for $uri")
      } finally {
        progressBeat?.cancel(false)
      }
    return when (outcome) {
      is RenderOutcome.Failed ->
        error(
          buildString {
            append("awaitNextRender failed for $uri: ${outcome.kind} ${outcome.message}")
            // Append the daemon's classified remediation so the agent gets the fix hint inline.
            outcome.suggestion?.let { append(" — suggestion: $it") }
          }
        )
      is RenderOutcome.Finished -> {
        val work =
          EditCycleWork(
            compile = compileWorkSinceRender.remove(DaemonAddr(uri.workspaceId, uri.modulePath)),
            renderMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - renderStartedAt),
            daemonTrace = outcome.daemonTrace,
            scan = lastScan[DaemonAddr(uri.workspaceId, uri.modulePath)],
          )
        lastCycleWork[key] = work
        if (work.compile != null) {
          System.err.println("compose-preview-mcp: edit cycle ${uri.previewFqn}: ${work.toJson()}")
        }
        outcome
      }
    }
  }

  /**
   * Decides whether [uri]'s source changed since the daemon was last told, and forwards
   * `fileChanged({kind: "source"})` if so. The daemon only rotates its user classloader
   * ([`UserClassLoaderHolder.swap`][ee.schimke.composeai.daemon.UserClassLoaderHolder.swap]) on
   * `fileChanged`, so a missed signal means stale renders.
   *
   * Two stages:
   * 1. mtime advanced: fire `fileChanged`, refresh the cached mtime and hash.
   * 2. mtime unchanged (same-millisecond writes, mtime-preserving editors, programmatic touches):
   *    hash the bytes and compare; on mismatch fire and refresh. The hash is negligible next to a
   *    render.
   *
   * The first sighting records both silently.
   */
  /** @return `true` when this probe forwarded a `fileChanged` (the poller counts these). */
  private fun ensureSourceFreshBeforeRender(uri: PreviewUri, daemon: SupervisedDaemon): Boolean {
    freshnessMetrics.probesTotal.incrementAndGet()
    val addr = DaemonAddr(uri.workspaceId, uri.modulePath)
    val entry =
      catalog[addr]?.get(uri.previewFqn)
        ?: run {
          freshnessMetrics.probesNoEntry.incrementAndGet()
          return false
        }
    val sourceFile =
      entry.resolvedSourcePath?.let(::File)
        ?: run {
          freshnessMetrics.probesNoSource.incrementAndGet()
          return false
        }
    val currentModifiedMs =
      sourceFile.lastModified().takeIf { it > 0L }
        ?: run {
          freshnessMetrics.probesNoSource.incrementAndGet()
          return false
        }

    val mtimeAdvanced =
      entry.sourceLastModifiedMs?.let { currentModifiedMs > it }
        ?: run {
          // First sighting via mtime: record and bail. The hash is filled in on the first slow-path
          // probe.
          catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
            current.copy(sourceLastModifiedMs = currentModifiedMs)
          }
          freshnessMetrics.probesFirstSighting.incrementAndGet()
          return false
        }

    val needsNotify =
      if (mtimeAdvanced) {
        freshnessMetrics.probesChangedByMtime.incrementAndGet()
        true
      } else {
        // mtime unchanged: confirm with a content hash, recording one as the baseline if none
        // exists.
        val currentHash =
          runCatching { sha256Hex(sourceFile) }.getOrNull()
            ?: run {
              freshnessMetrics.probesNoSource.incrementAndGet()
              return false
            }
        val knownHash = entry.sourceContentHash
        if (knownHash == null) {
          catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
            current.copy(sourceContentHash = currentHash)
          }
          freshnessMetrics.probesUnchangedNoBaseline.incrementAndGet()
          return false
        }
        if (currentHash == knownHash) {
          freshnessMetrics.probesUnchangedByHash.incrementAndGet()
          false
        } else {
          freshnessMetrics.probesChangedByHash.incrementAndGet()
          true
        }
      }
    if (!needsNotify) return false
    previewActivity.sourceChanged(sourceFile.absolutePath)

    if (sourceCompiler != null) {
      // Recompile first, forward `fileChanged` after: see [recompilePendingSources].
      pendingSources
        .computeIfAbsent(addr) { ConcurrentHashMap.newKeySet() }
        .add(sourceFile.absolutePath)
    } else {
      daemon.allClients().forEach { client ->
        runCatching {
          client.fileChanged(
            path = sourceFile.absolutePath,
            kind = FileKind.SOURCE,
            changeType = ChangeType.MODIFIED,
          )
        }
      }
    }
    val refreshedHash = runCatching { sha256Hex(sourceFile) }.getOrNull()
    catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
      current.copy(
        sourceLastModifiedMs = currentModifiedMs,
        sourceContentHash = refreshedHash ?: current.sourceContentHash,
      )
    }
    return true
  }

  /**
   * Runs [sourceCompiler] for [daemon]'s module when an edit is pending, then forwards
   * `fileChanged({kind:"source"})` per edited file so the daemon swaps onto the fresh classes (its
   * handler swaps but never compiles). A failed or impossible compile is remembered in [staleNotes]
   * so `render_preview` can flag the image as possibly stale. Serialized per module.
   *
   * @return the compile outcome, or `null` when nothing was pending or no compiler is configured.
   */
  private fun recompilePendingSources(daemon: SupervisedDaemon): SourceCompileOutcome? {
    val compiler = sourceCompiler ?: return null
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    synchronized(compileLocks.computeIfAbsent(addr) { Any() }) {
      val sources =
        pendingSources.remove(addr)?.toList()?.sorted()?.takeIf { it.isNotEmpty() } ?: return null
      val trigger = pendingTrigger.remove(addr) ?: TRIGGER_DETECTED
      // Whatever the outcome, this compile answers every change seen so far: a failure is reported
      // as stale until the next edit, not retried on every render.
      sourceTrees[daemon.workspaceId]?.let { compiledGeneration[addr] = it.generation }
      val root = supervisor.project(daemon.workspaceId)?.path
      val outcome =
        if (root == null) {
          SourceCompileOutcome.Unavailable(
            "workspace ${daemon.workspaceId.value} is not registered"
          )
        } else {
          val inProcess = compileInProcess(daemon, sources)
          if (inProcess is SourceCompileOutcome.Ok) inProcess
          else {
            val gradle = runCatching {
              compiler.compile(root, daemon.modulePath, sources.map(::File))
            }
              .getOrElse { SourceCompileOutcome.Failed(it.message ?: it.javaClass.simpleName) }
            // Confirm an in-process compile error with Gradle; if Gradle succeeds, the in-process
            // compiler is misconfigured for this module and later edits skip it.
            if (inProcess is SourceCompileOutcome.Failed && gradle is SourceCompileOutcome.Ok) {
              daemon.allClients().firstOrNull()?.let(inProcessDeclined::add)
            }
            gradle
          }
        }
      outcome.work?.copy(trigger = trigger)?.let { work ->
        compileWorkSinceRender[addr] = work
        val disallowed = work.disallowedTasks()
        if (disallowed.isNotEmpty()) {
          System.err.println(
            "compose-preview-mcp: recompile of ${daemon.modulePath} ran tasks an edit loop " +
              "should not need: ${disallowed.joinToString(", ")}"
          )
        }
      }
      when (outcome) {
        is SourceCompileOutcome.Ok -> staleNotes.remove(addr)
        is SourceCompileOutcome.Failed -> staleNotes[addr] = StaleNote(sources, outcome.reason)
        is SourceCompileOutcome.Unavailable ->
          staleNotes[addr] =
            StaleNote(
              sources,
              "could not recompile ${daemon.modulePath} (${outcome.reason}); build it " +
                "(`${daemon.modulePath.trimEnd(':')}:${GradleSourceCompiler.TASK}`) and call " +
                "notify_file_changed",
            )
      }
      // Never after a failed compile: the daemon would swap onto a half-written class directory and
      // drop the broken file's previews from discovery. Keeping the last good classloader renders
      // the last good image, with a stale note. A compile that could not run at all still forwards,
      // so externally built classes are picked up.
      if (outcome !is SourceCompileOutcome.Failed)
        daemon.allClients().forEach { client ->
          sources.forEach { path ->
            runCatching {
              client.fileChanged(
                path = path,
                kind = FileKind.SOURCE,
                changeType = ChangeType.MODIFIED,
              )
            }
          }
        }
      return outcome
    }
  }

  /**
   * Asks the daemon to compile [sources] in process (Kotlin Build Tools API) into the directory its
   * classloader loads, instead of Gradle. Only with [compileInProcess].
   *
   * An `Ok` is final; a compile error is confirmed with Gradle by the caller. Returns `null` ("use
   * Gradle") when the edit touches non-Kotlin sources, when the daemon answers `fallback` (no BTA
   * wiring, KSP/KAPT), or can't answer (older daemon). A declining daemon is remembered.
   */
  private fun compileInProcess(
    daemon: SupervisedDaemon,
    sources: List<String>,
  ): SourceCompileOutcome? {
    if (!compileInProcess) return null
    if (sources.any { !it.endsWith(".kt") }) return null
    val client = daemon.allClients().firstOrNull() ?: return null
    if (inProcessDeclined.contains(client)) return null
    val startedAt = System.nanoTime()
    val result = runCatching {
      client.compileSources(
        CompileSourcesParams(sources = sources),
        timeout = IN_PROCESS_COMPILE_TIMEOUT,
      )
    }
      .getOrElse {
        inProcessDeclined.add(client)
        return null
      }
    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    val work =
      CompileWork(task = IN_PROCESS_COMPILE_TASK, ms = ms, initScript = false, tasks = emptyList())
    return when (result.result) {
      CompileResultKind.OK -> SourceCompileOutcome.Ok(ms, work)
      CompileResultKind.COMPILE_ERROR ->
        SourceCompileOutcome.Failed(
          "$IN_PROCESS_COMPILE_TASK failed: " +
            result.errors
              .take(3)
              .joinToString("; ") { e -> "${File(e.file).name}:${e.line}:${e.column} ${e.message}" }
              .ifEmpty { "compile error" },
          work,
        )
      else -> {
        inProcessDeclined.add(client)
        null
      }
    }
  }

  /**
   * Queues a recompile when any source in [daemon]'s build changed since the module last compiled,
   * whether or not `notify_file_changed` was called (host edit tools never call it). A stat pass
   * over [SourceTree]'s cache.
   */
  private fun detectSourceChanges(daemon: SupervisedDaemon) {
    if (sourceCompiler == null) return
    val root = supervisor.project(daemon.workspaceId)?.path ?: return
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    val tree = sourceTrees.computeIfAbsent(daemon.workspaceId) { SourceTree(root) }
    val refresh = runCatching {
      tree.refresh()
    }
      .getOrElse {
        return
      }
    lastScan[addr] = refresh
    val compiled = compiledGeneration.getOrPut(addr) { 0L }
    if (tree.generation <= compiled) return
    val changed = tree.changedSince(compiled).map { it.absolutePath }
    if (changed.isEmpty()) return
    changed.forEach(previewActivity::sourceChanged)
    pendingSources.computeIfAbsent(addr) { ConcurrentHashMap.newKeySet() }.addAll(changed)
    pendingTrigger.putIfAbsent(addr, TRIGGER_DETECTED)
  }

  /** One line for a render result when [uri]'s module may be showing code older than its source. */
  private fun staleRenderLine(uri: PreviewUri): String? =
    staleRenderLine(DaemonAddr(uri.workspaceId, uri.modulePath))

  private fun staleRenderLine(addr: DaemonAddr): String? {
    val note = staleNotes[addr] ?: return null
    val files = note.sources.joinToString(", ") { File(it).name }
    return "stale: this render may not include the latest edit to $files — ${note.reason}"
  }

  /**
   * Records [file]'s current mtime + hash on every catalog entry of [addr] declared in it, so the
   * render-time probe does not queue a second compile for an edit `notify_file_changed` handled.
   */
  private fun markSourceSeen(addr: DaemonAddr, file: File) {
    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
    val mtime = file.lastModified().takeIf { it > 0L } ?: return
    val hash = runCatching { sha256Hex(file) }.getOrNull()
    catalog[addr]?.replaceAll { _, entry ->
      if (entry.resolvedSourcePath == canonical)
        entry.copy(sourceLastModifiedMs = mtime, sourceContentHash = hash)
      else entry
    }
  }

  /**
   * Background poller: runs [ensureSourceFreshBeforeRender] for every spawned daemon's catalog.
   * Each probe is wrapped in `runCatching` so one broken entry doesn't cancel the cycle. Internal
   * so tests can trigger it deterministically.
   */
  internal fun runSourceFreshnessPoll() {
    runCatching {
      freshnessMetrics.pollingCycles.incrementAndGet()
      supervisor.listProjects().forEach { project ->
        project.daemons.forEach { (modulePath, daemon) ->
          // Manifest first: a `composePreviewDiscover` re-run rewrites `previews.json`, and picking
          // it up here makes new preview ids renderable without restarting the server.
          runCatching { reloadManifestIfChanged(daemon) }
            .onFailure {
              System.err.println(
                "compose-preview-mcp: manifest reload failed for ${daemon.workspaceId}/${daemon.modulePath}: ${it.message}"
              )
            }
          val byId = catalog[DaemonAddr(project.workspaceId, modulePath)] ?: return@forEach
          byId.values.forEach { entry ->
            freshnessMetrics.pollingPreviewsScanned.incrementAndGet()
            val uri =
              PreviewUri(
                workspaceId = project.workspaceId,
                modulePath = modulePath,
                previewFqn = entry.fqn,
                config = entry.config,
              )
            val fired = runCatching {
              ensureSourceFreshBeforeRender(uri, daemon)
            }
              .getOrDefault(false)
            if (fired) freshnessMetrics.pollingChangesDetected.incrementAndGet()
          }
        }
      }
    }
      .onFailure {
        // Defensive — the executor swallows uncaught throws and cancels future runs; we'd lose
        // the poller silently. Logging keeps the behaviour observable.
        System.err.println("compose-preview-mcp: source-freshness poll failed: ${it.message}")
      }
  }

  /**
   * Per-(workspace, module) last-seen mtime + content hash of `previews.json`, in the same
   * two-stage shape as the source probe.
   */
  private val manifestState = ConcurrentHashMap<DaemonAddr, ManifestState>()

  private data class ManifestState(val mtimeMs: Long, val hash: String)

  /**
   * If the daemon's `previews.json` changed since the last cycle, diff its ids against the catalog
   * and route a synthetic `discoveryUpdated` through [onDiscoveryUpdated]. Idempotent.
   *
   * [force] diffs even when the file is unchanged: after a compile failure the daemon's incremental
   * discovery can empty the catalog while the manifest stays put, and an unchanged file would never
   * restore it. Internal for tests.
   */
  internal fun reloadManifestIfChanged(daemon: SupervisedDaemon, force: Boolean = false) {
    val manifestPath = daemon.manifestPath?.takeIf { it.isNotBlank() } ?: return
    val file = File(manifestPath)
    if (!file.isFile) return
    freshnessMetrics.manifestStats.incrementAndGet()
    val mtime = file.lastModified().takeIf { it > 0L } ?: return
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    val previous = manifestState[addr]
    if (!force && previous != null && mtime <= previous.mtimeMs) return

    val hash = runCatching { sha256Hex(file) }.getOrNull() ?: return
    if (!force && previous != null && hash == previous.hash) {
      // mtime moved (e.g. `touch` on previews.json) but bytes didn't — refresh mtime so the
      // next cycle skips the hash, and bail out without redispatching.
      manifestState[addr] = ManifestState(mtime, hash)
      return
    }

    val text =
      runCatching { fileSystem.read(file.path.toPath()) { readUtf8() } }.getOrNull() ?: return
    val previews =
      runCatching {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        (obj["previews"] as? JsonArray)?.mapNotNull { it as? JsonObject }
      }
        .getOrNull() ?: return
    manifestState[addr] = ManifestState(mtime, hash)

    val incomingIds = previews.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
    val currentIds = catalog[addr]?.keys?.toSet() ?: emptySet()
    val added = previews.filter { (it["id"]?.jsonPrimitive?.contentOrNull ?: "") !in currentIds }
    val changed = previews.filter { (it["id"]?.jsonPrimitive?.contentOrNull ?: "") in currentIds }
    val removed = currentIds.filter { it !in incomingIds }

    if (added.isEmpty() && removed.isEmpty()) return

    freshnessMetrics.manifestRereads.incrementAndGet()
    freshnessMetrics.manifestPreviewsAdded.addAndGet(added.size.toLong())
    freshnessMetrics.manifestPreviewsRemoved.addAndGet(removed.size.toLong())

    val params = buildJsonObject {
      put("added", JsonArray(added))
      put("removed", JsonArray(removed.map { JsonPrimitive(it) }))
      put("changed", JsonArray(changed))
      put("totalPreviews", JsonPrimitive(previews.size))
    }
    onDiscoveryUpdated(daemon, params)
  }

  /**
   * Random-sampling determinism probe: picks a preview with an empty render queue and fires
   * `renderNow` past the freshness check. The daemon's frame-hash dedup sets `unchanged: true` for
   * identical bytes; otherwise, with no source change, the preview drifted on its own
   * (clock-reading composables, classloader bugs, build-output drift). Internal so tests can
   * trigger it.
   */
  internal fun runRandomSamplingProbe() {
    runCatching {
      val candidates = mutableListOf<Triple<SupervisedDaemon, DaemonAddr, PreviewEntry>>()
      supervisor.listProjects().forEach { project ->
        project.daemons.forEach { (modulePath, daemon) ->
          val addr = DaemonAddr(project.workspaceId, modulePath)
          catalog[addr]?.values?.forEach { entry -> candidates.add(Triple(daemon, addr, entry)) }
        }
      }
      if (candidates.isEmpty()) return@runCatching
      val pick = candidates.random()
      val (daemon, addr, entry) = pick
      val key = PreviewIdKey(addr.workspaceId, addr.modulePath, entry.fqn)
      if (previewQueues.containsKey(key)) {
        freshnessMetrics.samplingSkippedBusy.incrementAndGet()
        return@runCatching
      }
      pendingProbes
        .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger() }
        .incrementAndGet()
      freshnessMetrics.samplingProbes.incrementAndGet()
      runCatching {
        daemon
          .clientForRender(entry.fqn)
          .renderNow(
            previews = listOf(entry.fqn),
            tier = RenderTier.FULL,
            reason = "freshness:sampling",
          )
      }
        .onFailure {
          // Roll back the pending count on a wire failure so a future renderFinished isn't
          // misattributed to a probe that never went out.
          pendingProbes.computeIfPresent(key) { _, c -> if (c.decrementAndGet() <= 0) null else c }
        }
    }
      .onFailure {
        System.err.println("compose-preview-mcp: random-sampling probe failed: ${it.message}")
      }
  }

  private fun resolvePreviewSourceFile(daemon: SupervisedDaemon, sourceFile: String?): File? {
    if (sourceFile.isNullOrBlank()) return null
    val direct = File(sourceFile)
    if (direct.isAbsolute) return direct.takeIf { it.isFile }
    val project = supervisor.project(daemon.workspaceId) ?: return null
    val descriptorModuleDir = daemon.moduleProjectDirPath?.let(::File)
    val layoutModuleDir = moduleDir(project.path, daemon.modulePath)
    return sequenceOf(descriptorModuleDir, layoutModuleDir)
      .filterNotNull()
      .distinctBy { it.absolutePath }
      .map { File(it, sourceFile) }
      .firstOrNull { it.isFile }
  }

  /**
   * What [PreviewGuidelinesMcp]'s tools read from this server: name resolution, the raw render, the
   * `a11y/hierarchy` data product, the preview function's source and the module's build dir.
   */
  private val previewGuidelinesHost =
    object : PreviewGuidelinesMcp.Host {
      override val openRouterKey: String?
        get() = System.getenv(PreviewGuidelinesMcp.KEY_ENV)

      override fun resolve(ref: String): PreviewGuidelinesMcp.Resolved? {
        val uriString =
          if (ref.startsWith("compose-preview://")) ref
          else (resolvePreviewName(ref) as? PreviewNameResolution.Found)?.uri ?: return null
        val uri = PreviewUri.parseOrNull(uriString) ?: return null
        val entry = guidelineCatalogEntry(uri)
        return PreviewGuidelinesMcp.Resolved(
          uriString,
          uri.previewFqn,
          entry?.simpleName ?: uri.previewFqn,
          guidelineBuildDir(uri),
        )
      }

      override fun render(preview: PreviewGuidelinesMcp.Resolved): ByteArray {
        val uri = PreviewUri.parseOrNull(preview.uri)!!
        // The standalone profile starts daemons with extensions inactive, and the hierarchy is
        // produced by the render: enable `a11y` first, so the render that follows has nodes.
        runCatching {
          enableDetailExtensions(
            supervisor.daemonFor(uri.workspaceId, uri.modulePath),
            setOf(RenderDetail.A11Y),
          )
        }
        return renderAndReadRawBytes(uri, overrides = null)
      }

      override fun a11yHierarchy(
        preview: PreviewGuidelinesMcp.Resolved
      ): PreviewGuidelinesMcp.Hierarchy {
        val uri = PreviewUri.parseOrNull(preview.uri)!!
        val daemon = runCatching {
          supervisor.daemonFor(uri.workspaceId, uri.modulePath)
        }
          .getOrElse {
            return PreviewGuidelinesMcp.Hierarchy(null, "no daemon (${it.message})")
          }
        fun fetch() = daemon.client.dataFetch(uri.previewFqn, A11Y_HIERARCHY_KIND, null, true)
        return try {
          PreviewGuidelinesMcp.Hierarchy(fetch().payload)
        } catch (e: DataProductWireException) {
          if (e.code != DataProductWireException.NOT_AVAILABLE) {
            return PreviewGuidelinesMcp.Hierarchy(null, "$A11Y_HIERARCHY_KIND: ${e.wireMessage}")
          }
          // Rendered before the extension took effect: render once more and ask again.
          runCatching {
            renderAndReadRawBytes(uri, overrides = null)
            PreviewGuidelinesMcp.Hierarchy(fetch().payload)
          }
            .getOrElse {
              PreviewGuidelinesMcp.Hierarchy(null, "$A11Y_HIERARCHY_KIND: ${it.message}")
            }
        } catch (e: Exception) {
          PreviewGuidelinesMcp.Hierarchy(null, "$A11Y_HIERARCHY_KIND: ${e.message}")
        }
      }

      override fun source(preview: PreviewGuidelinesMcp.Resolved): String? = runCatching {
        val uri = PreviewUri.parseOrNull(preview.uri)!!
        val entry = guidelineCatalogEntry(uri) ?: return@runCatching null
        val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
        val file = resolvePreviewSourceFile(daemon, entry.sourceFile) ?: return@runCatching null
        previewFunctionSource(file.readLines(), entry.sourceLine)
      }
        .getOrNull()
    }

  /**
   * [uri]'s catalog entry, matched on its whole identity — workspace, module and preview — so a
   * preview FQN registered by two checkouts or modules never reads the other one's source.
   */
  private fun guidelineCatalogEntry(uri: PreviewUri) =
    previewCatalog().firstOrNull { candidate ->
      PreviewUri.parseOrNull(candidate.uri)?.let {
        it.workspaceId == uri.workspaceId &&
          it.modulePath == uri.modulePath &&
          it.previewFqn == uri.previewFqn
      } == true
    }

  /**
   * The build directory of [uri]'s module: the daemon descriptor's project dir when the module's
   * `projectDir` is remapped, else the conventional layout under the workspace.
   */
  private fun guidelineBuildDir(uri: PreviewUri): File? {
    val project = supervisor.project(uri.workspaceId) ?: return null
    val descriptorDir = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath).moduleProjectDirPath
    }
      .getOrNull()
      ?.let(::File)
    return sequenceOf(descriptorDir, moduleDir(project.path, uri.modulePath))
      .filterNotNull()
      .map { File(it, "build") }
      .firstOrNull { File(it, "compose-previews").isDirectory }
      ?: File(descriptorDir ?: moduleDir(project.path, uri.modulePath), "build")
  }

  private fun moduleDir(projectRoot: File, modulePath: String): File {
    val trimmed = modulePath.trimStart(':')
    if (trimmed.isEmpty()) return projectRoot
    return File(projectRoot, trimmed.replace(':', File.separatorChar))
  }

  /**
   * Pop the head group for [key], wake its waiters with [outcome], and prepare the next group.
   * Called from `onRenderFinished` / `onRenderFailed`; silently ignores a missing or empty queue.
   */
  private fun popHeadAndPrepareNext(
    daemon: SupervisedDaemon,
    key: PreviewIdKey,
    outcome: RenderOutcome,
  ): RenderQueueTransition {
    var poppedGroup: PendingRenderGroup? = null
    var poppedFutures: List<java.util.concurrent.CompletableFuture<RenderOutcome>> = emptyList()
    var nextHead: PendingRenderGroup? = null
    previewQueues.compute(key) { _, queue ->
      if (queue == null || queue.isEmpty()) return@compute queue
      poppedGroup = queue.removeFirst()
      poppedFutures = poppedGroup!!.futures.toList()
      nextHead = queue.firstOrNull()?.also { it.sent = true }
      if (queue.isEmpty()) null else queue
    }
    poppedFutures.forEach { it.complete(outcome) }
    return RenderQueueTransition(completed = poppedGroup, next = nextHead)
  }

  private fun dispatchPreparedNext(
    daemon: SupervisedDaemon,
    key: PreviewIdKey,
    next: PendingRenderGroup?,
  ) {
    if (next != null) {
      renderDispatchExecutor.execute {
        // clientForRender's hash routes by previewFqn, same as the original dispatch in
        // awaitNextRender; preserves cache-locality / replica-affinity across promoted groups.
        dispatchHeadRender(daemon, key, next.overrides)
      }
    }
  }

  /**
   * Sends `renderNow` for [key]'s head group and handles a rejection, which the daemon never
   * follows with `renderFinished`/`renderFailed` (ignoring one stalled the whole queue). A
   * `coalesced:` rejection means the daemon still holds the previous override render, so it is
   * retried after a short backoff; any other rejection, or one outlasting the retries, fails the
   * head group and dispatches the next.
   */
  private fun dispatchHeadRender(
    daemon: SupervisedDaemon,
    key: PreviewIdKey,
    overrides: PreviewOverrides?,
    reason: String? = null,
  ) {
    var attempt = 0
    while (true) {
      val result =
        daemon
          .clientForRender(key.previewId)
          .renderNow(
            previews = listOf(key.previewId),
            tier = RenderTier.FULL,
            reason = reason,
            overrides = overrides,
          )
      val rejection = result.rejected.firstOrNull { it.id == key.previewId } ?: return
      if (rejection.reason.startsWith("coalesced") && attempt < RENDER_NOW_COALESCED_RETRIES) {
        attempt++
        Thread.sleep(RENDER_NOW_RETRY_BACKOFF_MS * attempt)
        continue
      }
      val transition =
        popHeadAndPrepareNext(
          daemon,
          key,
          RenderOutcome.Failed(
            kind = "RenderRejected",
            message = "the daemon rejected the render of ${key.previewId}: ${rejection.reason}",
          ),
        )
      dispatchPreparedNext(daemon, key, transition.next)
      return
    }
  }

  /**
   * Queues a source-change refresh through the same per-preview serialization as reads, so
   * [onRenderFinished] knows the exact override set that completed.
   */
  private fun enqueueRefresh(
    daemon: SupervisedDaemon,
    uri: PreviewUri,
    overrides: PreviewOverrides?,
    notificationUris: Set<String>,
    reason: String,
  ) {
    val key = PreviewIdKey(uri.workspaceId, uri.modulePath, uri.previewFqn)
    var becameFront = false
    previewQueues.compute(key) { _, queue ->
      val q = queue ?: ArrayDeque()
      val group = PendingRenderGroup(overrides = overrides)
      group.notificationUris.addAll(notificationUris)
      if (q.isEmpty()) {
        group.sent = true
        becameFront = true
      }
      // Each refresh is one file-change generation: equal overrides must not dedup, since the first
      // render may have captured the source before the second edit.
      q.addLast(group)
      q
    }
    if (becameFront) {
      dispatchHeadRender(daemon, key, overrides, reason)
    }
  }

  // Tool surface.

  private sealed interface UriOverridesFold {
    data class Folded(val args: JsonObject) : UriOverridesFold

    data class Rejected(val message: String) : UriOverridesFold
  }

  /**
   * A `resource_link` from `render_preview` may carry `?overrides=`. When passed back, overrides
   * must be applied or refused, never silently dropped. Tools with an `overrides` argument fold
   * them in (refusing a conflicting explicit one); `diff_semantics` replays them itself; every
   * other tool refuses an override-bearing URI.
   */
  private fun foldUriOverrides(name: String, args: JsonObject): UriOverridesFold {
    val raw =
      args["uri"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: return UriOverridesFold.Folded(args)
    val uri = PreviewUri.parseOrNull(raw) ?: return UriOverridesFold.Folded(args)
    val uriOverridesJson = uri.overridesJson ?: return UriOverridesFold.Folded(args)
    if (name !in URI_OVERRIDE_TOOLS) {
      return UriOverridesFold.Rejected(
        "$name: 'uri' carries render overrides (?overrides=) that this tool does not apply; " +
          "pass the preview URI without the overrides query"
      )
    }
    val uriOverrides =
      runCatching { json.parseToJsonElement(uriOverridesJson) as JsonObject }.getOrNull()
        ?: return UriOverridesFold.Rejected("$name: 'uri' carries malformed render overrides")
    val explicit = args["overrides"]
    if (
      explicit != null &&
        explicit !is kotlinx.serialization.json.JsonNull &&
        explicit != uriOverrides
    ) {
      return UriOverridesFold.Rejected(
        "$name: 'uri' carries render overrides that differ from the 'overrides' argument; " +
          "pass one or the other"
      )
    }
    return UriOverridesFold.Folded(
      JsonObject(
        args +
          mapOf(
            "uri" to JsonPrimitive(uri.copy(overridesJson = null).toUri()),
            "overrides" to uriOverrides,
          )
      )
    )
  }

  /** The viewer is an optional presentation layer: every linked tool keeps its text result. */
  private fun viewerLinkedToolDefs(toolDefs: List<ToolDef>): List<ToolDef> = toolDefs.map { tool ->
    when (tool.name) {
      in VIEWER_TOOL_NAMES ->
        tool.copy(meta = viewerToolMeta(appCallable = tool.name in APP_TOOL_NAMES))
      in APP_TOOL_NAMES -> tool.copy(meta = buildJsonObject { put("ui", appVisibility()) })
      else -> tool
    }
  }

  /**
   * MCP Apps `_meta.ui.visibility` (2026-01-26): the viewer calls these tools itself, which a host
   * allows only for app-visible tools.
   */
  private fun appVisibility(): JsonObject = buildJsonObject {
    putJsonArray("visibility") {
      add(JsonPrimitive("model"))
      add(JsonPrimitive("app"))
    }
  }

  private fun viewerHtml(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(MCP_APP_VIEWER_ASSET)) {
        "missing bundled MCP App viewer: $MCP_APP_VIEWER_ASSET"
      }
      .bufferedReader()
      .use { it.readText() }

  private fun viewerToolMeta(appCallable: Boolean): JsonObject = buildJsonObject {
    put(
      "ui",
      buildJsonObject {
        put("resourceUri", MCP_APP_VIEWER_URI)
        if (appCallable) appVisibility().forEach { (key, value) -> put(key, value) }
      },
    )
    // Pre-2026-01-26 MCP Apps hosts read the flat key; current hosts read `ui.resourceUri`.
    put("ui/resourceUri", MCP_APP_VIEWER_URI)
  }

  private fun viewerResourceMeta(): JsonObject = buildJsonObject {
    put("ui", buildJsonObject { put("prefersBorder", true) })
  }

  private fun currentToolDefs(session: Session): List<ToolDef> {
    if (fullToolDefsFuture.isDone) {
      return runCatching { fullToolDefsFuture.getNow(bootstrapToolDefs) }
        .getOrDefault(bootstrapToolDefs)
    }
    // Enroll the session under the lock so the completion handler can't race past it; if the future
    // completed meanwhile, serve the full list instead.
    val servedBootstrap =
      synchronized(bootstrapNotifyLock) {
        if (fullToolDefsFuture.isDone) {
          false
        } else {
          bootstrapServedSessions.add(session)
          true
        }
      }
    return if (servedBootstrap) {
      bootstrapToolDefs
    } else {
      runCatching { fullToolDefsFuture.getNow(bootstrapToolDefs) }.getOrDefault(bootstrapToolDefs)
    }
  }

  /** Tools served in the [McpToolProfile.STORYBOOK] profile: the aliases plus `status`. */
  private fun storybookToolDefs(): List<ToolDef> =
    buildFullToolDefs().filter { it.name == "status" || it.name in STORYBOOK_ALIAS_NAMES }

  /** The full tool list for the active [profile] — native-only, or Storybook-only. */
  private fun effectiveFullToolDefs(): List<ToolDef> =
    when (profile) {
      McpToolProfile.NATIVE -> buildFullToolDefs().filterNot { it.name in STORYBOOK_ALIAS_NAMES }
      McpToolProfile.STORYBOOK -> storybookToolDefs()
    }

  /** Bootstrap tools served before the full catalog loads, for the active [profile]. */
  private val bootstrapToolDefs: List<ToolDef> by lazy {
    when (profile) {
      McpToolProfile.NATIVE -> nativeBootstrapToolDefs
      McpToolProfile.STORYBOOK -> storybookToolDefs()
    }
  }

  private val nativeBootstrapToolDefs: List<ToolDef> by lazy {
    listOf(
      ToolDef(
        name = "status",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. Report MCP server readiness, tool-catalog loading state, registered projects, and spawned daemon discovery state. With no project registered, projectHint says what was tried and lists candidateBuilds to pass as render_preview project=<absolute path>. Available immediately after initialize.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "register_project",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. Register a project (workspace) so its previews can be listed and watched. Returns the assigned workspaceId and starts preparing that project (Gradle bootstrap and render daemon) in the background, so the first render is warm.",
        inputSchema =
          parseSchema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Absolute path to the project root."},
                "rootProjectName": {"type": "string", "description": "Optional override for the workspace's display name."},
                "modules": {"type": "array", "items": {"type": "string"}, "description": "Optional initial set of preview-eligible Gradle module paths."}
              },
              "required": ["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_projects",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. List every registered project with its workspaceId, name, and path.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "list_devices",
        description =
          "List the `@Preview(device = ...)` ids the daemon's catalog recognises, paired with resolved geometry.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "render_preview",
        description =
          "Call this FIRST with preview=<FunctionName> (e.g. ListScreenPreview); it registers the " +
            "workspace and resolves the name itself. Only explore (find_previews_for_file) if " +
            "this call fails. " +
            "Render a preview by URI (or by `preview` function name), bypassing the in-memory render cache. Returns a token-frugal " +
            "structured observation by default — the compose/semantics snapshot + sha256 + " +
            "dimensions, NO base64 PNG (the snapshot-default for an agent loop; issue #1787). " +
            "Pass `observe=\"png\"` to get the rendered PNG inline when you actually need to see " +
            "pixels, `inline=false` to return its on-disk PNG path and metadata for a local " +
            "file-reading client, or `observe=\"hash\"` for just the sha + dimensions. " +
            "Pass `force={reason}` only when the freshness probe missed a real edit (this should be rare); " +
            "report each use on https://github.com/yschimke/compose-ai-tools/issues/924. " +
            "Do NOT delete `build/classes/...` or run `./gradlew clean` to chase a stale render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "preview":{"type":"string","description":"Alternative to uri: a @Preview function name or unique FQN suffix, e.g. 'ListScreenPreview'. With several matches (such as @WearPreviewDevices variants) the first is rendered and the rest are listed as otherMatches."},
                "project":{"type":"string","description":"Absolute path to the project (or any folder in it). Only needed when the host sends no workspace roots."},
                "card":{"type":"boolean","description":"With inline=false, also write a self-contained viewer card (HTML) and return cardPath plus an <agent-embed> line for the reply. Implies inline=false. Default true for Antigravity."},
                "observe":{"type":"string","enum":["png","semantics","hash"],"description":"Observation level (issue #1787). Default 'semantics' ('png' for a client that declares the MCP Apps extension, whose viewer shows the image; semantics falls back to the image when unavailable) — the compose/semantics tree + sha256 + dimensions with NO base64, the token-frugal snapshot-default for an agent loop (fetch pixels only when you need them). 'png' returns the base64 image (request it when you need to see pixels); 'hash' returns just sha256 + dimensions."},
                "imageScale":{"type":"string","enum":["default","full"],"description":"Size of the inline image the model reads. 'default' caps the long edge at 768px (never upscales); the file on disk and the preview resource stay full size. 'full' only for pixel-level checks; costs more tokens."},
                "inline":{"type":"boolean","description":"Default true. Set false on a local-FS client to return the rendered PNG's absolute pngPath plus sha256, dimensions, changed, and durationMs as text instead of an inline observation. inline=false takes precedence over observe, so it returns no semantics or image content. Cannot be combined with crop. Known agent clients (Claude Code, Codex, Gemini CLI, OpenCode, Antigravity) default to false when observe and crop are omitted."},
                "crop":{"type":"object","description":"Return only ONE element's rectangle instead of the full frame (issue #1817) — far fewer tokens, and it focuses the view on the region you care about (the natural partner to diff_semantics: 'ref X changed' -> crop ref X). Set EITHER a semantic target (ref | testTag | role/text, resolved against compose/semantics) OR explicit render-pixel bounds {left,top,right,bottom}. Honours 'observe': png returns the cropped image (+ region metadata), hash/semantics return the crop's sha + dimensions only.","properties":{"ref":{"type":"string"},"testTag":{"type":"string"},"role":{"type":"string"},"text":{"type":"string"},"left":{"type":"integer"},"top":{"type":"integer"},"right":{"type":"integer"},"bottom":{"type":"integer"}}},
                "overrides":{"type":"object","description":"Optional per-call display overrides."},
                "details":{"type":"array","items":{"type":"string","enum":["a11y","layout"]},"description":"Opt-in, default none. Also fetch the accessibility findings and overlay (a11y) and the layout bounds (layout) in this call. Each adds ONE summary line to the result; the full detail goes only into the card, where the person toggles Plain / A11y overlay / Layout. Pass it only when the person asks about accessibility or layout."},
                "force":{"type":"object","description":"Sanctioned escape hatch when the freshness probe missed an edit. Forwards fileChanged({kind:\"classpath\"}) before rendering, dropping the daemon's user classloader. Each use is logged + counted; please report on issue #924.","properties":{"reason":{"type":"string","description":"Human-readable reason for needing force (required)."}},"required":["reason"]}
              },
              "required":[]
            }
            """
              .trimIndent()
          ),
        outputSchema = RENDER_PREVIEW_OUTPUT_SCHEMA,
      ),
      ToolDef(
        name = "find_previews_for_file",
        description =
          "Find catalogued previews declared by a Kotlin source file. `path` may be absolute, or " +
            "relative to the selected workspace (or every registered workspace when workspaceId is " +
            "omitted). Returns an empty previews array when no discovered preview uses that file.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "path":{"type":"string","description":"Absolute source-file path, or a path relative to the workspace root."},
                "workspaceId":{"type":"string","description":"Optional workspace to search. Omit to search every registered workspace."},
                "project":{"type":"string","description":"Absolute path to the project (or any folder in it). Only needed when the host sends no workspace roots."}
              },
              "required":["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "watch",
        description =
          "Register an area of interest. The server keeps the matched previews warm and pushes notifications/resources/updated as they re-render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Optional Gradle module path; null = every module in the workspace."},
                "fqnGlob":{"type":"string","description":"Optional FQN glob."},
                "awaitDiscovery":{"type":"boolean"},
                "awaitTimeoutMs":{"type":"integer"}
              },
              "required":["workspaceId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "notify_file_changed",
        description =
          "Tell every daemon in the matched workspace that a file changed so it can re-run discovery or mark previews stale. A .kt/.java edit is recompiled first, so there is no need to run Gradle yourself.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string","description":"Optional: defaults to the registered project that contains path."},
                "path":{"type":"string","description":"Absolute path of the changed file."},
                "kind":{"type":"string","enum":["source","resource","classpath"],"default":"source"},
                "changeType":{"type":"string","enum":["modified","created","deleted"],"default":"modified"}
              },
              "required":["path"]
            }
            """
              .trimIndent()
          ),
      ),
    )
  }

  private fun buildFullToolDefs(): List<ToolDef> =
    listOf(
      ToolDef(
        name = "status",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. Report MCP server readiness, tool-catalog loading state, registered projects, and spawned daemon discovery state. With no project registered, projectHint says what was tried and lists candidateBuilds to pass as render_preview project=<absolute path>.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "register_project",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. Register a project (workspace) so its previews can be listed and watched. Returns the assigned workspaceId and starts preparing that project (Gradle bootstrap and render daemon) in the background, so the first render is warm.",
        inputSchema =
          parseSchema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Absolute path to the project root."},
                "rootProjectName": {"type": "string", "description": "Optional override for the workspace's display name."},
                "modules": {"type": "array", "items": {"type": "string"}, "description": "Optional initial set of preview-eligible Gradle module paths."}
              },
              "required": ["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unregister_project",
        description = "Forget a registered project; tears down its daemons.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"workspaceId":{"type":"string"}},"required":["workspaceId"]}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_projects",
        description =
          "Not needed before render_preview — the workspace registers itself on first render. List every registered project with its workspaceId, name, and path.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "list_devices",
        description =
          "List the `@Preview(device = ...)` ids the daemon's catalog recognises, paired with " +
            "resolved geometry (widthDp/heightDp/density). Use these as the `device` field of " +
            "`render_preview.overrides` to flip a preview to any catalog device without editing " +
            "annotations. The free-form `spec:parent=…,width=…,height=…,dpi=…,orientation=…` " +
            "grammar is not enumerable and is not returned here — pass it as a `device` override " +
            "and the daemon parses it at resolve-time (`parent=` names one of these ids and " +
            "supplies whatever the string omits; `orientation=` rotates the resolved frame). " +
            "Mirror of every daemon's " +
            "`InitializeResult.capabilities.knownDevices`; read directly from the shared " +
            "`DeviceDimensions` rather than going through a daemon, so it works before any " +
            "daemon has spawned.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "render_preview",
        description =
          "Call this FIRST with preview=<FunctionName> (e.g. ListScreenPreview); it registers the " +
            "workspace and resolves the name itself. Only explore (find_previews_for_file) if " +
            "this call fails. " +
            "Render a preview by URI (or by `preview` function name), bypassing the in-memory render cache. Returns a token-frugal " +
            "structured observation by default (`observe=\"semantics\"`: the compose/semantics tree " +
            "+ sha256 + dimensions, NO base64; issue #1787) — pass `observe=\"png\"` for the rendered " +
            "PNG inline, `inline=false` for its local on-disk PNG path + metadata, or " +
            "`observe=\"hash\"` for just sha + dimensions. " +
            "Optional `overrides` apply per-call display-property overrides (size, density, " +
            "locale, fontScale, uiMode, orientation, device, inspectionMode) plus the connector- " +
            "driven extensions (material3Theme, wallpaper, ambient, focus, keyboard, touchOverlay, " +
            "permissions, remoteCompose, launcherWidget) — see PROTOCOL.md § 5 " +
            "(`renderNow.overrides`). The server validates each set field against the daemon's " +
            "advertised `supportedOverrides` and rejects ones the backend would silently ignore. " +
            "Optional `force={reason}` is the sanctioned escape hatch for when the freshness " +
            "probe missed a real edit — it forwards a `fileChanged({kind:\"classpath\"})` to every " +
            "replica before rendering, dropping the daemon's user classloader. Use of `force` is a " +
            "freshness-logic gap; please post a comment on " +
            "https://github.com/yschimke/compose-ai-tools/issues/924 with the URI, reason, and the " +
            "edit that didn't land. Do NOT delete `build/classes/...` or run `./gradlew clean` to " +
            "chase a stale render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "preview":{"type":"string","description":"Alternative to uri: a @Preview function name or unique FQN suffix, e.g. 'ListScreenPreview'. With several matches (such as @WearPreviewDevices variants) the first is rendered and the rest are listed as otherMatches."},
                "project":{"type":"string","description":"Absolute path to the project (or any folder in it). Only needed when the host sends no workspace roots."},
                "card":{"type":"boolean","description":"With inline=false, also write a self-contained viewer card (HTML) and return cardPath plus an <agent-embed> line for the reply. Implies inline=false. Default true for Antigravity."},
                "observe":{"type":"string","enum":["png","semantics","hash"],"description":"Observation level (issue #1787). Default 'semantics' ('png' for a client that declares the MCP Apps extension, whose viewer shows the image; semantics falls back to the image when unavailable) returns the compose/semantics tree + sha256 + width/height with NO base64 — the token-frugal snapshot-default for a multi-step agent loop (fetch pixels only when you need them). 'png' returns the base64 image (request it when you need to see pixels); 'hash' returns just sha256 + dimensions."},
                "imageScale":{"type":"string","enum":["default","full"],"description":"Size of the inline image the model reads. 'default' caps the long edge at 768px (never upscales); the file on disk and the preview resource stay full size. 'full' only for pixel-level checks; costs more tokens."},
                "inline":{"type":"boolean","description":"Default true. Set false on a local-FS client to return the rendered PNG's absolute pngPath plus sha256, dimensions, changed, and durationMs as text instead of an inline observation. inline=false takes precedence over observe, so it returns no semantics or image content. Cannot be combined with crop. Known agent clients (Claude Code, Codex, Gemini CLI, OpenCode, Antigravity) default to false when observe and crop are omitted."},
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides. Each field is optional; nulls fall back to the discovery-time RenderSpec. Backends that don't model a field (e.g. desktop has no Android resource qualifier system) ignore it.",
                  "properties":{
                    "widthPx":{"type":"integer","description":"Sandbox width in pixels."},
                    "heightPx":{"type":"integer","description":"Sandbox height in pixels."},
                    "density":{"type":"number","description":"Display density (1.0 = mdpi, 2.0 = xhdpi, etc.)."},
                    "localeTag":{"type":"string","description":"BCP-47 locale tag (e.g. 'en-US', 'fr', 'ja-JP')."},
                    "fontScale":{"type":"number","description":"Font scale multiplier (1.0 = system default)."},
                    "uiMode":{"type":"string","enum":["light","dark"],"description":"Light/dark mode override. Android-only today."},
                    "orientation":{"type":"string","enum":["portrait","landscape"],"description":"Portrait/landscape override. Android-only today."},
                    "device":{"type":"string","description":"@Preview(device=...) string — 'id:pixel_5', 'id:wearos_small_round', 'id:tv_1080p', or a 'spec:' string — 'spec:width=400dp,height=800dp,dpi=320', or 'spec:parent=pixel_tablet,orientation=portrait' where parent supplies whatever the string omits and orientation rotates the resolved frame. Resolved by the daemon's catalog into widthPx/heightPx/density; explicit width/height/density overrides on this same object take precedence."},
                    "captureAdvanceMs":{"type":"integer","description":"Paused-clock advance before capture. Android-only today."},
                    "inspectionMode":{"type":"boolean","description":"Override LocalInspectionMode for this one-shot render. Null/default keeps preview semantics."},
                    "material3Theme":{
                      "type":"object",
                      "description":"Material 3 theme token overrides applied as a normal MaterialTheme wrapper around the preview.",
                      "properties":{
                        "colorScheme":{"type":"object","additionalProperties":{"type":"string"},"description":"Color role names to #RRGGBB or #AARRGGBB, e.g. primary, onPrimary, surface."},
                        "typography":{"type":"object","additionalProperties":{"type":"object","properties":{"fontSizeSp":{"type":"number"},"lineHeightSp":{"type":"number"},"letterSpacingSp":{"type":"number"},"fontWeight":{"type":"integer"},"italic":{"type":"boolean"}}},"description":"Text style names to partial overrides, e.g. bodyLarge, titleMedium, labelSmall."},
                        "shapes":{"type":"object","additionalProperties":{"type":"number"},"description":"Shape token names to rounded corner size in dp, e.g. small, medium, extraLarge."}
                      }
                    },
                    "wallpaper":{
                      "type":"object",
                      "description":"Dynamic-color override. Derives a Material 3 scheme from a seed color and wraps the preview in MaterialTheme(colorScheme=…). material3Theme on the same call still wins per role.",
                      "properties":{
                        "seedColor":{"type":"string","description":"Seed color as #RRGGBB or #AARRGGBB."},
                        "isDark":{"type":"boolean","description":"Force the dark variant; null inherits the host theme's surface luminance."},
                        "paletteStyle":{"type":"string","enum":["tonalSpot","neutral","vibrant","expressive","rainbow","fruitSalad","monochrome","fidelity","content"],"description":"Algorithm variant; null = tonalSpot."},
                        "contrastLevel":{"type":"number","description":"Material 3 contrast in [-1.0,1.0]; 0.0 default, 0.5 medium, 1.0 high."}
                      },
                      "required":["seedColor"]
                    },
                    "ambient":{
                      "type":"object",
                      "description":"Wear OS ambient-state override. Drives the AmbientLifecycleObserver shadow so AmbientAware UI composes under the requested state. Wear-only; other backends ignore it.",
                      "properties":{
                        "state":{"type":"string","enum":["interactive","ambient","inactive"],"description":"Requested ambient state."},
                        "burnInProtectionRequired":{"type":"boolean","description":"Forwarded to onEnterAmbient(...); null = false."},
                        "deviceHasLowBitAmbient":{"type":"boolean","description":"Forwarded to onEnterAmbient(...); null = false."},
                        "updateTimeMillis":{"type":"integer","description":"Synthetic minute-tick timestamp; null uses render-time wall-clock."},
                        "idleTimeoutMs":{"type":"integer","description":"Idle-after-input timeout before restoring the requested state during interactive sessions; null = ~5000."}
                      },
                      "required":["state"]
                    },
                    "focus":{
                      "type":"object",
                      "description":"Focus / keyboard-traversal override. tabIndex focuses the n-th focusable; direction applies one directional step. Backends without a Compose focus owner (e.g. desktop CMP) ignore it.",
                      "properties":{
                        "tabIndex":{"type":"integer","description":"Focus the n-th focusable in tab order."},
                        "direction":{"type":"string","enum":["Next","Previous","Up","Down","Left","Right"],"description":"Single directional traversal step."},
                        "step":{"type":"integer","description":"1-based step index for overlay labels."},
                        "overlay":{"type":"boolean","description":"Draw a stroke + label over the focused element's bounds."},
                        "enterPlacesFocus":{"type":"boolean","description":"Skip the historical +1 Next compensation for focusGroup onEnter patterns."},
                        "pressed":{"type":"boolean","description":"Dispatch an indirect-pointer Press onto the focused element after the walk lands."}
                      }
                    },
                    "keyboard":{
                      "type":"object",
                      "description":"Soft-keyboard (IME) override. Forces band visibility and per-cap press highlight on top of the app's natural IME behaviour.",
                      "properties":{
                        "visible":{"type":"boolean","description":"Force the IME band visible/hidden; null observes the app's natural signals."},
                        "pressedKey":{"type":"string","description":"Highlight a key cap: a single lowercase letter or one of 'space','enter','shift','backspace','sym'."}
                      }
                    },
                    "touchOverlay":{"type":"boolean","description":"Opt-in touch-event visualization (Android 'Show touches' style) for live/recording sessions."},
                    "talkBack":{"type":"boolean","description":"Opt-in TalkBack focus-overlay visualization for recordings: a green focus rectangle, traversal-order badges, and the spoken-announcement caption, walked through the screen's focus stops."},
                    "permissions":{
                      "type":"object",
                      "description":"Android runtime-permissions override. Seeds Robolectric's grant state so checkSelfPermission reads see the requested values. Android-only; desktop ignores it.",
                      "properties":{
                        "grants":{"type":"object","additionalProperties":{"type":"string","enum":["granted","denied"]},"description":"Manifest.permission.* constant string -> grant state."}
                      }
                    },
                    "remoteCompose":{
                      "type":"object",
                      "description":"Remote Compose override. Seeds the profile and named values a RemotePreview{} block reads via LocalRemoteComposeHost. Android-only; desktop ignores it.",
                      "properties":{
                        "profile":{"type":"string","enum":["androidx","androidx7","androidx8","androidx9","widgetsV6","widgetsV7","wearWidgets"],"description":"RcPlatformProfiles variant to compile the remote document against."},
                        "namedValues":{"type":"object","additionalProperties":{"type":"object","properties":{"kind":{"type":"string","enum":["float","dp","int","string","bool","color"]}},"description":"Typed named value, e.g. {kind:'float',value:1.5} or {kind:'color',argb:'#FF0000FF'}."},"description":"Named state seeds keyed by the name user code binds."},
                        "acceptedHostActions":{"type":"array","items":{"type":"string"},"description":"Restrict which HostAction ids the connector captures; null captures all."}
                      }
                    },
                    "launcherWidget":{
                      "type":"object",
                      "description":"Launcher-widget container-size override. Lays the preview out at a whole-cell size on the host's launcher grid (defaults mirror the Pixel launcher: 72dp cells, 8dp gaps, 1×1..5×5).",
                      "properties":{
                        "cells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Target whole-cell size, clamped into minCells..maxCells."},
                        "cellSizeDp":{"type":"integer","description":"One cell's edge length in dp; null = 72."},
                        "cellSpacingDp":{"type":"integer","description":"Gap between adjacent cells in dp; null = 8."},
                        "minCells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Inclusive lower bound per axis; null = 1×1."},
                        "maxCells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Inclusive upper bound per axis; null = 5×5."},
                        "resizeOrder":{"type":"string","enum":["diagonal","widthFirst","heightFirst"],"description":"Hint for a future resize-loop orchestrator; the single-shot connector ignores it."}
                      },
                      "required":["cells"]
                    }
                  }
                },
                "details":{"type":"array","items":{"type":"string","enum":["a11y","layout"]},"description":"Opt-in, default none. Also fetch the accessibility findings and overlay (a11y) and the layout bounds (layout) in this call. Each adds ONE summary line to the result; the full detail goes only into the card, where the person toggles Plain / A11y overlay / Layout. Pass it only when the person asks about accessibility or layout."},
                "force":{
                  "type":"object",
                  "description":"Sanctioned escape hatch for stale renders. Forwards a fileChanged({kind:\"classpath\"}) to every replica of this URI's daemon before issuing renderNow, dropping the daemon's user classloader. Each use bumps a `forces.used` counter and is logged in `recent` (see `status`). Please report on https://github.com/yschimke/compose-ai-tools/issues/924.",
                  "properties":{
                    "reason":{"type":"string","description":"Human-readable reason for needing force (required). Stored in the recent-forces ring buffer for debugging."}
                  },
                  "required":["reason"]
                },
                "crop":{
                  "type":"object",
                  "description":"Return only ONE element's rectangle instead of the full-frame PNG (issue #1817). Far fewer tokens than a whole screenshot, and it focuses the view on the region you care about — the natural partner to diff_semantics: when the diff says ref X changed, crop just ref X to look. Set EITHER a semantic target (ref | testTag | role/text), resolved against the preview's compose/semantics tree in the same root-pixel space as the image, OR explicit render-pixel bounds {left,top,right,bottom}. Honours 'observe': png returns the cropped image plus a small metadata block (resolved region, ref, source dimensions, sha); hash/semantics return the crop's sha + dimensions only (a region-scoped change signal), and semantics also includes the matched node's semantics subtree.",
                  "properties":{
                    "ref":{"type":"string","description":"Stable ComposeSemanticsNode.ref (the unambiguous handle; survives content edits)."},
                    "testTag":{"type":"string","description":"Modifier.testTag(...) value (must be unique)."},
                    "role":{"type":"string","description":"Accessibility role to match, with or without text."},
                    "text":{"type":"string","description":"Visible text/label to match, with or without role."},
                    "left":{"type":"integer","description":"Explicit crop bounds in render pixels; set all four of left/top/right/bottom."},
                    "top":{"type":"integer"},
                    "right":{"type":"integer"},
                    "bottom":{"type":"integer"}
                  }
                }
              },
              "required":[]
            }
            """
              .trimIndent()
          ),
        outputSchema = RENDER_PREVIEW_OUTPUT_SCHEMA,
      ),
      ToolDef(
        name = "find_previews_for_file",
        description =
          "Find catalogued previews declared by a Kotlin source file. `path` may be absolute, or " +
            "relative to the selected workspace (or every registered workspace when workspaceId is " +
            "omitted). Returns an empty previews array when no discovered preview uses that file.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "path":{"type":"string","description":"Absolute source-file path, or a path relative to the workspace root."},
                "workspaceId":{"type":"string","description":"Optional workspace to search. Omit to search every registered workspace."},
                "project":{"type":"string","description":"Absolute path to the project (or any folder in it). Only needed when the host sends no workspace roots."}
              },
              "required":["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "watch",
        description =
          "Register an area of interest. The server keeps the matched previews warm and pushes notifications/resources/updated as they re-render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Optional Gradle module path; null = every module in the workspace."},
                "fqnGlob":{"type":"string","description":"Optional FQN glob; '*' matches non-dot, '**' matches anything, '?' one non-dot char."},
                "awaitDiscovery":{"type":"boolean","description":"When true, block until every matched daemon has completed initial discovery, then return per-module readiness. Default false preserves non-blocking watch."},
                "awaitTimeoutMs":{"type":"integer","description":"Maximum time to wait when awaitDiscovery=true. Defaults to the server render timeout."}
              },
              "required":["workspaceId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unwatch",
        description =
          "Remove watches for the current session. With no args, removes every watch the session registered.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "fqnGlob":{"type":"string"}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_watches",
        description = "List the watches registered by the current session.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "notify_file_changed",
        description =
          "Tell every daemon in the matched workspace that a file changed. Forwards a `fileChanged` notification to the daemon so it can re-run discovery / mark previews stale. Use after editing source files outside the MCP server's view (e.g. via a coding agent that doesn't run a file watcher). A .kt/.java edit is recompiled (`composePreviewCompile`) before the daemon swaps its classloader, and the result says whether that compile succeeded, so there is no need to run Gradle yourself.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string","description":"Optional: defaults to the registered project that contains path."},
                "path":{"type":"string","description":"Absolute path of the changed file."},
                "kind":{"type":"string","enum":["source","resource","classpath"],"default":"source"},
                "changeType":{"type":"string","enum":["modified","created","deleted"],"default":"modified"}
              },
              "required":["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "history_list",
        description =
          "List historical render entries for a workspace's daemon. Proxies the daemon's `history/list` JSON-RPC method (PROTOCOL.md § 5). Returns newest-first sidecar metadata; pair with `history_read` (or `resources/read` on a `compose-preview-history://` URI) to fetch bytes. Filters mirror the daemon: previewId / since / until / branch / commit / etc.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Gradle module path; required so the supervisor knows which daemon to ask."},
                "previewId":{"type":"string","description":"Optional preview FQN filter (e.g. com.example.RedSquare)."},
                "since":{"type":"string","description":"ISO-8601 lower bound, e.g. 2026-04-30T00:00:00Z."},
                "until":{"type":"string","description":"ISO-8601 upper bound."},
                "limit":{"type":"integer","description":"Default 50, max 500."},
                "cursor":{"type":"string","description":"Opaque pagination token from a previous response."},
                "branch":{"type":"string"},
                "branchPattern":{"type":"string","description":"Regex over branch."},
                "commit":{"type":"string","description":"Long or short SHA."},
                "worktreePath":{"type":"string"},
                "agentId":{"type":"string"},
                "sourceKind":{"type":"string","enum":["fs","git","http"]},
                "sourceId":{"type":"string"}
              },
              "required":["workspaceId","module"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "set_visible",
        description =
          "Override the daemon's visible-preview set for one (workspace, module) directly. The watch propagator's setVisible derives from registered watches; this tool lets an agent express \"these previews are on screen right now\" without a long-lived watch. Sets the daemon's visible filter to the given preview FQNs verbatim. The watch propagator's next recompute (e.g. on `discoveryUpdated` or `watch`/`unwatch`) will replace whatever set_visible set.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Gradle module path."},
                "ids":{"type":"array","items":{"type":"string"},"description":"Preview FQNs (e.g. com.example.PreviewsKt.RedSquare)."}
              },
              "required":["workspaceId","module","ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "set_focus",
        description =
          "Override the daemon's focused-preview set. Same shape as set_visible — focus is the higher-priority slice the daemon renders first when its queue drains. Use when an agent is about to read a specific preview and wants to express \"render this one ahead of others\".",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "ids":{"type":"array","items":{"type":"string"}}
              },
              "required":["workspaceId","module","ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "history_diff",
        description =
          "Diff two history entries by id (metadata mode only — pixel mode is reserved for daemon phase H5). Returns `{pngHashChanged, fromMetadata, toMetadata}`. Cross-source: `from` and `to` may live on different `HistorySource`s (LocalFs vs git-ref), so this is the load-bearing call for \"did my edit change rendered output vs the version on main?\".",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "from":{"type":"string","description":"Entry id (HistoryEntry.id)."},
                "to":{"type":"string"}
              },
              "required":["workspaceId","module","from","to"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_data_products",
        description =
          "Discover the data-product kinds (a11y findings, a11y hierarchy, layout tree, recomposition heat-map, theme resolution, …) the daemon can produce alongside each PNG. Returns one entry per (workspace, module) with the kinds the daemon advertised at initialize-time. Each entry carries `kind`, `schemaVersion`, `transport` (inline|path|both), and three flags: `attachable` (rides renderFinished when subscribed), `fetchable` (callable via get_preview_data), `requiresRerender` (true → fetching may pay a render cost). With no args, lists every spawned daemon; pass `workspaceId` and/or `module` to scope the answer. Empty list = pre-D2 daemon (no producers wired yet) — get_preview_data on such a daemon returns DataProductUnknown. See docs/daemon/DATA-PRODUCTS.md for the kind catalogue.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string","description":"Optional. Restrict the answer to one workspace."},
                "module":{"type":"string","description":"Optional Gradle module path; requires workspaceId."}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_extension_commands",
        description =
          "List preview-extension command ids exposed by the built-in command catalog. These are " +
            "shrinkwrapped shortcuts over generic tools such as get_preview_data, " +
            "render_preview_overlay, and render_preview. Use run_extension_command to invoke one by id.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "agentRecommended":{"type":"boolean","description":"When true, only return commands marked as useful defaults for agents."}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "enable_extensions",
        description =
          "Activate the named daemon extensions on every spawned daemon for the matching " +
            "(workspace, module). Daemons start with every extension inactive (PROTOCOL.md § 3a) — " +
            "this tool routes through the daemon's `extensions/enable` JSON-RPC and updates the MCP " +
            "supervisor's cached `dataProductCapabilities` / `dataExtensionDescriptors` so " +
            "subsequent `list_data_products` reflects the new public surface without a follow-up " +
            "`extensions/list` round-trip. Returns one entry per (workspace, module) carrying " +
            "`newlyEnabled`, `pulledIn` (deps activated as a side-effect), `alreadyEnabled`, and " +
            "`unknown` (ids not registered on this daemon). Idempotent — re-issuing with the same " +
            "ids reports them under `alreadyEnabled`. Pass `workspaceId` and/or `module` to scope; " +
            "omit both to fan out across every spawned daemon.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "ids":{"type":"array","items":{"type":"string"},"description":"Extension ids to activate (e.g. \"text/strings\", \"resources/used\", \"a11y\"). Use list_data_products afterwards to confirm the resulting public surface."},
                "workspaceId":{"type":"string","description":"Optional. Restrict the enable to one workspace."},
                "module":{"type":"string","description":"Optional Gradle module path; requires workspaceId."}
              },
              "required":["ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "run_extension_command",
        description =
          "Run a preview-extension command by id. This keeps high-level shortcuts discoverable " +
            "through list_extension_commands while routing execution through stable generic MCP " +
            "tools. Most commands require `uri`; render/data commands accept `inline`, `overrides`, " +
            "and `params` where the underlying tool supports them.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "commandId":{"type":"string","description":"Extension command id from list_extension_commands."},
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "inline":{"type":"boolean","description":"For data/media commands, return inline content when supported."},
                "params":{"type":"object","description":"Optional data/fetch params, forwarded for data commands."},
                "overrides":{"type":"object","description":"Optional render overrides for render/media commands. Same shape as render_preview.overrides."}
              },
              "required":["commandId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "get_preview_data",
        description =
          "Fetch one data product (a11y findings, a11y hierarchy, layout tree, …) for a preview. The kind names a structured payload the daemon can produce alongside the PNG; call list_data_products first to see what each daemon advertises. Returns the JSON payload as a single text content block. Auto-renders the preview if it hasn't rendered yet (so the agent doesn't need to call render_preview first). Cache short-circuit: if the kind has been subscribed (subscribe_preview_data) or globally attached (--attach-data-product server flag), the latest renderFinished payload is served from an in-memory cache with zero daemon round-trip — the response carries `cached: true`. When the daemon's latest render didn't compute the kind and it's not cached, the daemon may queue a re-render in the right mode; this is bounded by the daemon's per-request budget (DataProductBudgetExceeded if exceeded). `inline` defaults to true so the agent gets JSON back rather than a path it may not be able to read; flip to false on local-FS callers that prefer to read sibling files directly.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "kind":{"type":"string","description":"Data-product kind, e.g. a11y/hierarchy, a11y/atf, layout/inspector, compose/semantics, test/failure."},
                "params":{"type":"object","description":"Optional per-kind parameters (e.g. {nodeId} for layout/inspector). Forwarded verbatim to the daemon's data/fetch."},
                "inline":{"type":"boolean","description":"Default true. When false, the daemon returns a `path` to a sibling JSON file instead of inlining the payload."}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "diff_semantics",
        description =
          "Diff the Compose semantics trees of two live previews and report what changed semantically — the cheap, deterministic, pixel-free regression signal (the analogue of Playwright's aria-snapshot diff). Fetches compose/semantics for `baseUri` and `headUri` (two compose-preview:// URIs, auto-rendering each if needed), matches nodes by their stable `ref`, and returns added / removed / changed-field deltas as JSON plus a one-line summary. Use it to answer 'what changed?' between two rendered previews without reading two PNGs — e.g. the same preview before and after an edit, or two related previews. Text/label changes show up as field changes on the same ref (not remove+add); positional bounds churn is ignored (that's the pixel diff's job). Cheaper than reading screenshots — prefer it for copy/label/role/overflow regressions. (Diffing against a compose-preview-history:// baseline needs the semantics payload persisted per history entry — tracked as a follow-up.)",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "baseUri":{"type":"string","description":"Baseline live preview URI (compose-preview://<workspace>/<module>/<fqn>)."},
                "headUri":{"type":"string","description":"Candidate live preview URI (compose-preview://...) to compare against the baseline."}
              },
              "required":["baseUri","headUri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "render_matrix",
        description =
          "With just `preview` (a @Preview function name), renders all of its @Preview variants " +
            "(the annotation's own devices, font scales, …; up to 12) with one labelled contact " +
            "sheet. " +
            "Render one preview across a cross-product of display axes in a single call and return a token-frugal per-cell summary — for 'does this survive small screen + RTL + large font?' without looping render_preview and reading N PNGs (issue #1788). `axes` sets any of device / locale / uiMode / fontScale (each a non-empty array); the result has one cell per combination with its `overrides`, `label`, `sha256`, `widthPx`/`heightPx`, `changed` (sha differs from the first cell — the quick 'which configs render differently?' signal), and `changedSinceLastRender` (sha differs from this session's last render of that cell — use it to check an edit landed). No base64 by default; fetch a specific cell's pixels with render_preview + those overrides when you need to look, or set `contactSheet:true` to also get one stitched grid image of every cell. Bounded at 24 cells; narrow the axes if you exceed it. Pairs with diff_semantics for per-cell structural diffs.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>"},
                "preview":{"type":"string","description":"Alternative to uri: a @Preview function name or FQN suffix, e.g. 'ListScreenPreview'. Without axes, every @Preview variant it names is rendered; with axes, they apply to the first variant (named in the result)."},
                "project":{"type":"string","description":"Absolute path to the project (or any folder in it). Only needed when the host sends no workspace roots."},
                "axes":{
                  "type":"object",
                  "description":"Optional cross-product axes. Each is a non-empty array. Omit to render the preview's own @Preview variants.",
                  "properties":{
                    "device":{"type":"array","items":{"type":"string"},"description":"@Preview(device=...) ids or specs. Phone/tablet: 'id:pixel_5', 'id:pixel_7', 'id:pixel_tablet', 'id:pixel_fold'. Wear OS: 'id:wearos_small_round', 'id:wearos_large_round', 'id:wearos_square'. Or 'spec:width=411dp,height=891dp,dpi=420'. list_devices has every id; an unknown id is rejected with the valid ones."},
                    "locale":{"type":"array","items":{"type":"string"},"description":"BCP-47 locale tags, e.g. ['en','ar','ja-JP']."},
                    "uiMode":{"type":"array","items":{"type":"string","enum":["light","dark"]}},
                    "fontScale":{"type":"array","items":{"type":"number"},"description":"Font-scale multipliers, e.g. [1.0, 2.0]."}
                  }
                },
                "contactSheet":{"type":"boolean","description":"When true, also return a single stitched contact-sheet PNG (one labelled tile per cell) alongside the per-cell summary. Default false (token-frugal: hashes only)."},
                "choose":{"type":"boolean","description":"When true, ask an elicitation-capable client to choose one rendered variant. Clients without elicitation receive the same labelled choices as text."}
              },
              "required":[]
            }
            """
              .trimIndent()
          ),
        outputSchema = RENDER_MATRIX_OUTPUT_SCHEMA,
      ),
      ToolDef(
        name = "subscribe_preview_data",
        description =
          "Subscribe to a data-product kind for one preview. While subscribed, every renderFinished for the preview produces the kind alongside the PNG (subject to the daemon's producer wiring). Useful when the agent expects to ask repeatedly about the same preview — pre-computing on render avoids the get_preview_data re-render cost. Subscriptions are sticky-while-visible: the daemon drops them automatically when the preview leaves the most recent set_visible set, so re-subscribe when the preview returns to view. Idempotent. Errors: DataProductUnknown if the kind isn't advertised or isn't attachable. NOTE: today, MCP doesn't push the attached payload to clients automatically — agents still call get_preview_data to read it; the subscribe just primes the daemon-side cache.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>"},
                "kind":{"type":"string"}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unsubscribe_preview_data",
        description =
          "Drop a subscription installed by subscribe_preview_data. Idempotent — unsubscribing a kind that was never subscribed returns ok.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string"},
                "kind":{"type":"string"}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "render_preview_overlay",
        description =
          "Render a preview and return the annotated overlay PNG instead of (or alongside) the bare " +
            "screenshot. Drives the daemon's image-processor surface — when `kind` is `a11y/overlay` " +
            "(the default), the response carries a base64-encoded image with ATF findings and " +
            "Paparazzi-style accessibility legend painted on top. " +
            "Use this when you want a single tool call that (1) triggers a render in the right mode, " +
            "(2) lets the producer compose its derived image, and (3) hands you back the bytes — no " +
            "separate `render_preview` + `get_preview_data` round trip. The overlay PNG also lands on " +
            "disk under `<dataDir>/<previewId>/a11y-overlay.png` so callers that prefer the path can " +
            "set `inline=false`. " +
            "Errors: DataProductUnknown when the daemon has no producer for `kind` (for example, " +
            "the a11y data plugin is not enabled).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "kind":{"type":"string","description":"Overlay kind. Default 'a11y/overlay'. Anything advertised by list_data_products with media-bearing extras can be used here.","default":"a11y/overlay"},
                "inline":{"type":"boolean","description":"Default true. When true, returns the overlay bytes as a base64 image content block. When false, returns the on-disk path only."},
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides forwarded to render_preview. Same shape as render_preview.overrides.",
                  "properties":{
                    "widthPx":{"type":"integer"},
                    "heightPx":{"type":"integer"},
                    "density":{"type":"number"},
                    "localeTag":{"type":"string"},
                    "fontScale":{"type":"number"},
                    "uiMode":{"type":"string","enum":["light","dark"]},
                    "orientation":{"type":"string","enum":["portrait","landscape"]},
                    "device":{"type":"string"},
                    "captureAdvanceMs":{"type":"integer"},
                    "inspectionMode":{"type":"boolean"}
                  }
                }
              },
              "required":["uri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "get_preview_extras",
        description =
          "List the extra (non-JSON) outputs the producer wrote alongside a data product — typically " +
            "PNGs like the a11y overlay. Returns one entry per extra: `{name, path, mediaType?, sizeBytes?}`. " +
            "Hits the in-memory cache when the kind is subscribed/attached, otherwise round-trips a " +
            "data/fetch with `inline=false` to pick up the path-shaped result and its `extras`. Use this " +
            "when a panel UI wants to enumerate everything a producer made available without committing " +
            "to one transport (e.g. show a thumbnail of `overlay` alongside the JSON viewer).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string"},
                "kind":{"type":"string","description":"Data-product kind whose extras to enumerate. Use list_data_products to discover candidates."}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "record_preview",
        description =
          "Record a scripted screen-recording of an interactive preview. Drives the daemon's " +
            "recording surface (RECORDING.md) end-to-end: open a " +
            "held-scene session at the requested fps + scale, post the script of `(tMs, kind, " +
            "pixelX, pixelY)` events, play back the timeline at virtual frame time, and encode to " +
            "APNG/MP4/WebM on disk. " +
            "**Token-frugal default (issue #1860).** `observe` defaults to `frames`: the result is " +
            "the structured per-frame observation (hashes + changed-frame indices + on-disk paths), " +
            "NOT the inline media — a recording's base64 bytes scale with fps × duration and can " +
            "dwarf a single PNG. Pass `observe=\"media\"` to also get the encoded bytes inline (the " +
            "pre-#1860 behaviour); the artifact is always on disk at `videoPath` either way. " +
            "**Why virtual time matters.** Pointer events and `scene.render` both key off the " +
            "session's virtual nanoTime, so a script of `(tMs=0, click) + (tMs=500, click)` always " +
            "produces 500ms of inter-click animation in the output regardless of how long the " +
            "agent took to assemble the script. `LaunchedEffect`, `withFrameNanos`, and " +
            "`rememberInfiniteTransition` advance with virtual time, not wall-clock — agents can " +
            "compose a multi-second timeline in milliseconds of agent latency and the video plays " +
            "back at human cadence. " +
            "**Verification metadata.** The text metadata block includes `frames[]` with per-frame " +
            "paths, SHA-256 hashes, and changed-pixel counts from the previous frame, plus " +
            "`changedFrameCount` / first-last changed-frame paths so agents can assert that a " +
            "click or scroll changed UI without decoding APNG/MP4/WebM bytes. " +
            "**Component previews.** Pass `overrides.{widthPx,heightPx,backgroundColor}` to record " +
            "a button-sized preview at native size with a custom background; raise `scale` to " +
            "upsample for legibility. Pointer coords always reference image-natural pixels, never " +
            "the scaled output canvas. " +
            "**Errors.** MethodNotFound when the daemon's host doesn't support recording (today: " +
            "Android backend, missing previewSpecResolver); InvalidParams on out-of-range fps / " +
            "scale or unknown previewId.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "fps":{"type":"integer","description":"Frames per second of the virtual clock. Default 30; range [1, 120]."},
                "scale":{"type":"number","description":"Output-frame size multiplier. Default 1.0; range (0, 8]. Pointer coords stay in image-natural pixel space."},
                "format":{"type":"string","enum":["apng","gif","mp4","webm"],"description":"Encoded recording format. Default 'apng'. 'apng' and 'gif' are always available (pure-JVM); 'gif' is the friendliest for inline playback in chat / GitHub comments. 'mp4' and 'webm' require an ffmpeg binary on the daemon's PATH; check ServerCapabilities.recordingFormats first or expect a clean rejection if unavailable."},
                "observe":{"type":"string","enum":["frames","media"],"description":"Observation level (issue #1860). Default 'frames' returns the structured per-frame observation — per-frame sha256 + changed-pixel counts, changedFrameCount, and the on-disk frame/video paths — with NO inline media (token-frugal; recording bytes scale with fps × duration). 'media' also returns the encoded APNG/MP4/WebM bytes inline (APNG as an image block, mp4/webm as an embedded resource). The artifact is on disk at 'videoPath' regardless of this flag."},
                "emitTest":{"type":"boolean","description":"Default false. When true, also return a runnable Compose UI test generated from this interaction (issue #1786) as an extra text block — each event with a testTag/role/text target becomes an onNodeWith…().performClick() step, and each recording.probe is diffed against the previous probe's captured semantics into assertExists()/assertDoesNotExist() assertions (a TODO stub when nothing assertable was captured). Write it to src/test and review the inferred probe assertions."},
                "events":{
                  "type":"array",
                  "description":"Scripted timeline. Empty array records a single bootstrap frame.",
                  "items":{
                    "type":"object",
                    "properties":{
                      "tMs":{"type":"integer","description":"Virtual time offset from recording/start, in milliseconds. Must be ≥ 0."},
                      "kind":{"type":"string","description":"Namespaced script-event id from `list_data_products`. Every event — input (`input.click`, `input.pointerDown`, `input.rotaryScroll`, …), accessibility actions (`a11y.action.click`, …), lifecycle (`lifecycle.pause`/`resume`/`stop`), state (`state.recreate`/`save`/`restore`), `preview.reload`, `recording.probe` — is advertised in the daemon's `dataExtensions[].recordingScriptEvents[]`. Only entries with `supported = true` are accepted; `supported = false` entries are roadmap and rejected up front."},
                      "pixelX":{"type":"integer","description":"X coord in image-natural pixel space (the preview's own widthPx)."},
                      "pixelY":{"type":"integer","description":"Y coord in image-natural pixel space."},
                      "target":{"type":"object","description":"For pointer events (`input.click`, `input.pointerDown`/`Move`/`Up`, `input.rotaryScroll`) — a stable semantic handle resolved server-side to the node's centre instead of pixel coordinates (issue #1784). Set exactly one of `ref` (the compose/semantics node ref), `testTag` (a Modifier.testTag value), or `role`/`text` (accessibility role and/or visible text). Explicit pixelX/pixelY win when both are present; an unresolved target surfaces as `unsupported` script evidence.","properties":{"ref":{"type":"string"},"testTag":{"type":"string"},"role":{"type":"string"},"text":{"type":"string"}}},
                      "scrollDeltaY":{"type":"number","description":"For 'rotaryScroll'."},
                      "keyCode":{"type":"string","description":"For 'keyDown'/'keyUp' (reserved; v1 dispatch is a no-op)."},
                      "label":{"type":"string","description":"Agent label copied into scriptEvents evidence for probes/checkpoints."},
                      "checkpointId":{"type":"string","description":"Checkpoint id for state save/restore audit events."},
                      "lifecycleEvent":{"type":"string","description":"Lifecycle transition for lifecycle script events, e.g. resume, pause, destroy."},
                      "tags":{"type":"array","items":{"type":"string"},"description":"Optional agent tags copied into scriptEvents evidence."},
                      "nodeContentDescription":{"type":"string","description":"For the *targeted* `a11y.action.*` kinds (click, activate, longClick, focus, expand, collapse, dismiss, scroll*) — visible content description of the target accessibility node (`Modifier.semantics { contentDescription = ... }` / `Icon(contentDescription = ...)`). The daemon resolves this against the held composition's semantics tree and dispatches the corresponding SemanticsActions action — same lookup a screen reader walks via AccessibilityNodeInfo.performAction. Not required (and ignored) for the targetless linear-navigation verbs `a11y.action.next` / `a11y.action.previous`, which walk a session focus cursor through the focus stops in traversal order, and for input/probe/state/lifecycle events."},
                      "selector":{"type":"object","description":"For `uia.*` kinds — multi-axis BySelector-style predicate matching androidx.test.uiautomator's `By` factory. Optional fields (every axis is missing-means-not-filtered): `text` / `desc` / `clazz` / `res` (exact match) plus `textMatches` / `descMatches` / `clazzMatches` / `resMatches` (regex); boolean state predicates `enabled` / `clickable` / `longClickable` / `checkable` / `checked` / `selected` / `focused` / `scrollable`; tree predicates `hasChild` / `hasDescendant` (arrays of nested selectors). Ignored for non-`uia.*` events. See data-uiautomator-core's `SelectorJson` for the full schema."},
                      "useUnmergedTree":{"type":"boolean","description":"For `uia.*` kinds — `false` (default) walks Compose's merged accessibility tree (matches on-device UIAutomator semantics: `By.text + click` targets `Button { Text(...) }` as one node); `true` walks the unmerged tree to reach inner Compose nodes."},
                      "inputText":{"type":"string","description":"For `uia.inputText` only — the text to type into the matched editable node via SemanticsActions.SetText (Compose) or ACTION_SET_TEXT (View). Required for `uia.inputText`; ignored for other kinds."}
                    },
                    "required":["tMs","kind"]
                  }
                },
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides applied to the held scene. Same shape as render_preview.overrides.",
                  "properties":{
                    "widthPx":{"type":"integer"},
                    "heightPx":{"type":"integer"},
                    "density":{"type":"number"},
                    "localeTag":{"type":"string"},
                    "fontScale":{"type":"number"},
                    "uiMode":{"type":"string","enum":["light","dark"]},
                    "orientation":{"type":"string","enum":["portrait","landscape"]},
                    "device":{"type":"string"},
                    "captureAdvanceMs":{"type":"integer"},
                    "inspectionMode":{"type":"boolean"}
                  }
                }
              },
              "required":["uri","events"]
            }
            """
              .trimIndent()
          ),
      ),
      // Storybook-MCP-compatible aliases. Storybook's official MCP server defines tool names agents
      // learn; these kebab-named aliases map them onto our handlers and accept a Storybook story id
      // (minted by [StorybookMcp]) or a raw `compose-preview://` URI.
      ToolDef(
        name = "list-all-documentation",
        description =
          "Storybook-compatible: list every catalogued preview as a Storybook story — its stable " +
            "`id` (title--name), `title`, `name`, synthetic `importPath`, and the native " +
            "compose-preview `uri`. The story-catalog equivalent of Storybook's " +
            "`list-all-documentation`; use the returned ids with `preview-stories`, " +
            "`get-documentation-for-story`, and `run-story-tests`.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "get-documentation-for-story",
        description =
          "Storybook-compatible: return one story's metadata — id, title, name, the native " +
            "compose-preview `uri`, and its workspace/module/fqn. Accepts a story id from " +
            "`list-all-documentation` (or a raw compose-preview URI) as `storyId`/`id`. Render it " +
            "with `preview-stories`; check accessibility with `run-story-tests`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyId":{"type":"string","description":"Story id from list-all-documentation, or a raw compose-preview:// URI."},"id":{"type":"string","description":"Alias for storyId."}}}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "preview-stories",
        description =
          "Storybook-compatible: render one or more stories in isolation and return the images. " +
            "Maps to `render_preview` per story. Pass `storyIds` (array) or a single `storyId` " +
            "(ids from `list-all-documentation`, or raw compose-preview URIs). `observe` defaults " +
            "to 'png' (the rendered image); 'semantics'/'hash' return the token-frugal structured " +
            "observation instead. Optional `overrides` are the same per-call display overrides as " +
            "`render_preview.overrides`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyIds":{"type":"array","items":{"type":"string"},"description":"Story ids from list-all-documentation, or raw compose-preview:// URIs."},"storyId":{"type":"string","description":"A single story id, if not using storyIds."},"observe":{"type":"string","enum":["png","semantics","hash"],"description":"Default 'png'."},"overrides":{"type":"object","description":"Optional per-call display overrides, same shape as render_preview.overrides."}}}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "run-story-tests",
        description =
          "Storybook-compatible: run a story's tests and return structured results. Maps to our " +
            "scripted-recording assertions (record_preview) — the compose analogue of Storybook's " +
            "play-function + expect. Pass a `script`: a record_preview event timeline where " +
            "`input.*` events drive the UI and `assert.visible`/`assert.notVisible`/" +
            "`assert.textEquals`/`assert.a11y`/`assert.pixels` events check it (each records " +
            "APPLIED/FAILED). With NO script it runs an accessibility smoke (`preview.reload` + " +
            "`assert.a11y`). Set `emitTest` to also get a generated Compose UI test. Assertion " +
            "support is backend-dependent (desktop vs Android — see issue #2519). Accepts a story " +
            "id from `list-all-documentation` (or a raw compose-preview URI) as `storyId`/`id`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyId":{"type":"string","description":"Story id from list-all-documentation, or a raw compose-preview:// URI."},"id":{"type":"string","description":"Alias for storyId."},"script":{"type":"array","description":"Optional record_preview event timeline (input.* to drive, assert.* to check). Omit to run an accessibility smoke test.","items":{"type":"object"}},"emitTest":{"type":"boolean","description":"Also return a runnable Compose UI test generated from the interaction."},"observe":{"type":"string","enum":["frames","media"],"description":"record_preview observation level; default 'frames' (structured per-frame + per-assertion evidence, no inline media)."},"format":{"type":"string","enum":["apng","gif","mp4","webm"],"description":"Recording format for the artifact; default 'apng'."}}}
            """
              .trimIndent()
          ),
      ),
    ) +
      listOf(PreviewTray.toolDef(), PreviewMentions.toolDef()) +
      listOf(PreviewGuidelinesMcp.promptToolDef(), PreviewGuidelinesMcp.checkToolDef()) +
      rcViewer.toolDefs() +
      (uiBuilderDesign?.toolDefs() ?: emptyList()) +
      (uiBuilderMcp?.toolDefs() ?: emptyList()) +
      (openAiProbe?.toolDefs() ?: emptyList()) +
      previewLibrary.toolDefs() +
      previewSettings.toolDefs()

  private suspend fun handleCallTool(
    session: Session,
    name: String,
    arguments: JsonElement?,
    progressToken: JsonElement? = null,
  ): CallToolResult {
    val args =
      when (
        val normalized =
          foldUriOverrides(name, (arguments as? JsonObject) ?: JsonObject(emptyMap()))
      ) {
        is UriOverridesFold.Folded -> normalized.args
        is UriOverridesFold.Rejected -> return errorCallToolResult(normalized.message)
      }
    val projectArg =
      args["project"]?.jsonPrimitive?.contentOrNull?.takeIf {
        it.isNotBlank() && name in PROJECT_ARGUMENT_TOOLS
      }
    val scope = projectArg?.let { path ->
      when (val registered = registerProjectArgument(path)) {
        is ProjectArgument.Registered -> registered.workspaceIds
        is ProjectArgument.Rejected -> return errorCallToolResult("$name: ${registered.message}")
      }
    }
    if (profile == McpToolProfile.NATIVE) sessionRoots(session)
    val progress = progressReporter(session, progressToken)
    return when (name) {
      "status" -> toolStatus(session)
      "register_project" -> toolRegisterProject(args)
      "unregister_project" -> toolUnregisterProject(args)
      "list_projects" -> toolListProjects()
      "list_devices" -> toolListDevices()
      "find_previews_for_file" -> toolFindPreviewsForFile(args)
      PreviewTray.TOOL ->
        PreviewTray.call(args, previewCatalog(), previewActivity, renderThumbnails)
      PreviewGuidelinesMcp.PROMPT_TOOL -> PreviewGuidelinesMcp.prompt(args, previewGuidelinesHost)
      PreviewGuidelinesMcp.CHECK_TOOL -> PreviewGuidelinesMcp.check(args, previewGuidelinesHost)
      PreviewMentions.TOOL ->
        PreviewMentions.call(args, previewCatalog(), previewActivity, renderThumbnails)
      // Both declare an outputSchema, so both carry structuredContent (#1114).
      "render_preview" ->
        withCallBudget(session, name, args, progress) { report ->
            if (scope == null) autoRegisterWorkspace(session)
            renderPreviewChoosingVariant(session, withSettings(session, args), scope, report)
          }
          .withJsonTextStructure()
      "render_matrix" ->
        withCallBudget(session, name, args, progress) { report ->
            if (scope == null) autoRegisterWorkspace(session)
            toolRenderMatrix(session, args, scope, report)
          }
          .withJsonTextStructure()
      "watch" -> toolWatch(session, args)
      "unwatch" -> toolUnwatch(session, args)
      "list_watches" -> toolListWatches(session)
      "notify_file_changed" -> toolNotifyFileChanged(args)
      "set_visible" -> toolSetVisible(args)
      "set_focus" -> toolSetFocus(args)
      "history_list" -> toolHistoryList(args)
      "history_diff" -> toolHistoryDiff(args)
      "list_data_products" -> toolListDataProducts(args)
      "enable_extensions" -> toolEnableExtensions(args)
      "list_extension_commands" -> toolListExtensionCommands(args)
      "run_extension_command" -> toolRunExtensionCommand(session, args)
      "get_preview_data" -> toolGetPreviewData(args)
      "diff_semantics" -> toolDiffSemantics(args)
      "render_preview_overlay" -> toolRenderPreviewOverlay(args)
      "get_preview_extras" -> toolGetPreviewExtras(args)
      "subscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = true)
      "unsubscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = false)
      "record_preview" -> toolRecordPreview(args)
      // Storybook-MCP-compatible aliases → existing handlers via the story-id adapter.
      "list-all-documentation" -> toolStorybookListDocs()
      "get-documentation-for-story" -> toolStorybookGetDoc(args)
      "preview-stories" -> toolStorybookPreviewStories(session, args)
      "run-story-tests" -> toolStorybookRunTests(args)
      RcViewerMcp.TOOL_NAME -> rcViewer.handle(name, args)!!
      UiBuilderDesignMcp.TOOL_NAME ->
        uiBuilderDesign?.handle(name, args) ?: errorCallToolResult("unknown tool: $name")
      else ->
        if (profile == McpToolProfile.NATIVE) {
          openAiProbe?.handle(name, args, (session as? McpSession)?.clientName)
            ?: previewSettings.handle(name, args)
            ?: previewLibrary.handle(name, args)
            ?: uiBuilderMcp?.handle(name, args)
            ?: errorCallToolResult("unknown tool: $name")
        } else {
          errorCallToolResult("unknown tool: $name")
        }
    }
  }

  /** A budgeted render call still running or not yet collected; see [withCallBudget]. */
  private class InFlightCall {
    val result = CompletableDeferred<CallToolResult>()
    @Volatile var completedAtNanos: Long = 0

    /** Where progress goes: the latest caller still waiting, else only stderr. */
    @Volatile var reportTo: ((String) -> Unit)? = null
  }

  private data class InFlightCallKey(val session: Session, val tool: String, val args: JsonObject)

  private val inFlightCalls = ConcurrentHashMap<InFlightCallKey, InFlightCall>()

  private val budgetedCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Runs [block] under [callBudgetMs], detached from the request: when the budget runs out the call
   * returns a non-error `pending` result and the work continues. A later identical call (same
   * session, tool, arguments) attaches to that work. Uncollected results are dropped after
   * [uncollectedCallResultTtlMs], swept on every budgeted call and on session close.
   */
  private suspend fun withCallBudget(
    session: Session,
    tool: String,
    args: JsonObject,
    progress: (String) -> Unit,
    block: suspend (progress: (String) -> Unit) -> CallToolResult,
  ): CallToolResult {
    if (callBudgetMs <= 0) return block(progress)
    val startedAt = System.nanoTime()
    sweepUncollectedCalls()
    val key = InFlightCallKey(session, tool, args)
    var created: InFlightCall? = null
    val call =
      inFlightCalls.compute(key) { _, existing ->
        if (existing != null && !existing.isUncollectedPastTtl()) existing
        else InFlightCall().also { created = it }
      }!!
    call.reportTo = progress
    created?.let { work ->
      budgetedCallScope.launch {
        val outcome = runCatching {
          block { message ->
            work.reportTo?.invoke(message) ?: System.err.println("compose-preview-mcp: $message")
          }
        }
        work.completedAtNanos = System.nanoTime()
        outcome.fold(work.result::complete, work.result::completeExceptionally)
      }
    }
    val remainingMs = callBudgetMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    if (withTimeoutOrNull(remainingMs.coerceAtLeast(1)) { call.result.join() } == null) {
      if (call.reportTo === progress) call.reportTo = null
      return pendingCallResult(tool)
    }
    inFlightCalls.remove(key, call)
    return call.result.await()
  }

  private fun InFlightCall.isUncollectedPastTtl(): Boolean =
    completedAtNanos != 0L &&
      System.nanoTime() - completedAtNanos >
        TimeUnit.MILLISECONDS.toNanos(uncollectedCallResultTtlMs)

  /** Drops finished results whose retry never came. */
  private fun sweepUncollectedCalls() {
    inFlightCalls.entries.removeIf { (_, call) -> call.isUncollectedPastTtl() }
  }

  /** Budgeted calls still held for a retry; for tests. */
  internal fun inFlightCallCount(): Int = inFlightCalls.size

  /** The `pending` result [withCallBudget] returns when the budget runs out. */
  private fun pendingCallResult(tool: String): CallToolResult {
    // "starting" while any build is bootstrapping or starting a daemon, even if another build's
    // daemon is already up.
    val rendering =
      startingProjects.isEmpty() &&
        supervisor.listProjects().any { project ->
          project.daemons.values.any { it.initialDiscoveryComplete }
        }
    val payload = buildJsonObject {
      put("pending", true)
      put("phase", if (rendering) "rendering" else "starting")
      put("retryAfterMs", PENDING_CALL_RETRY_AFTER_MS)
      put(
        "message",
        (if (rendering) "The preview is still rendering"
        else "The project is still starting (Gradle bootstrap and render daemon)") +
          "; the work continues in the background. Call $tool again with the same arguments " +
          "to get the result.",
      )
    }
    return CallToolResult(
      content = listOf(ContentBlock.Text(payload.toString())),
      structuredContent = payload,
    )
  }

  // Storybook-MCP-compatible alias handlers (see [StorybookMcp]): each takes a story id (or raw
  // URI) and routes to an existing handler.

  /** `list-all-documentation`: the whole catalog presented as Storybook stories. */
  private fun toolStorybookListDocs(): CallToolResult {
    val stories = StorybookMcp.stories(catalogResources())
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-storybook/v1")
      put("count", stories.size)
      putJsonArray("stories") {
        stories.forEach { s ->
          add(
            buildJsonObject {
              put("id", s.storyId)
              put("title", s.title)
              put("name", s.name)
              put("type", "story")
              put("importPath", "virtual:compose-preview/${s.fqn}")
              put("uri", s.uri)
            }
          )
        }
      }
    }
    return textCallToolResult(payload.toString())
  }

  /** `get-documentation-for-story`: one story's metadata (id, title, name, native URI, coords). */
  private fun toolStorybookGetDoc(args: JsonObject): CallToolResult {
    val id =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get-documentation-for-story: missing 'storyId'")
    val story =
      StorybookMcp.stories(catalogResources()).firstOrNull { it.storyId == id || it.uri == id }
        ?: return errorCallToolResult("get-documentation-for-story: no such story: $id")
    val parsed = PreviewUri.parseOrNull(story.uri)
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-storybook/v1")
      put("id", story.storyId)
      put("title", story.title)
      put("name", story.name)
      put("uri", story.uri)
      put("importPath", "virtual:compose-preview/${story.fqn}")
      if (parsed != null) {
        put("workspaceId", parsed.workspaceId.value)
        put("module", parsed.modulePath)
        put("fqn", parsed.previewFqn)
        parsed.config?.let { put("config", it) }
      }
      put(
        "note",
        "Render with preview-stories; check accessibility with run-story-tests. Native tools " +
          "(render_preview, get_preview_data, …) accept `uri` directly.",
      )
    }
    return textCallToolResult(payload.toString())
  }

  /** `preview-stories`: render one or more stories in isolation and return the images. */
  private fun toolStorybookPreviewStories(session: Session, args: JsonObject): CallToolResult {
    val ids =
      storybookStoryIds(args)
        ?: return errorCallToolResult("preview-stories: provide 'storyIds' (array) or 'storyId'")
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "png"
    val overrides = args["overrides"]
    val resources = catalogResources()
    val blocks = mutableListOf<ContentBlock>()
    var anyOk = false
    var anyError = false
    for (id in ids) {
      val uri = StorybookMcp.resolveUri(id, resources)
      if (uri == null) {
        blocks.add(ContentBlock.Text("preview-stories: no such story: $id"))
        anyError = true
        continue
      }
      blocks.add(ContentBlock.Text("story: $id → $uri"))
      val sub = buildJsonObject {
        put("uri", uri)
        put("observe", observe)
        if (overrides != null) put("overrides", overrides)
      }
      val res = toolRenderPreview(session, sub)
      blocks.addAll(res.content)
      if (res.isError == true) anyError = true else anyOk = true
    }
    // Error only when nothing rendered — a partial success still returns the frames that worked.
    return CallToolResult(content = blocks, isError = !anyOk && anyError)
  }

  /**
   * `run-story-tests`: enable a11y, render, and return the ATF accessibility findings for a story.
   */
  private fun toolStorybookRunTests(args: JsonObject): CallToolResult {
    val id =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("run-story-tests: missing 'storyId'")
    val uri =
      StorybookMcp.resolveUri(id, catalogResources())
        ?: return errorCallToolResult("run-story-tests: no such story: $id")
    // The recording-assertion timeline is our play-function equivalent: delegate to record_preview.
    // With no script, run an a11y smoke check.
    val events = (args["script"] as? JsonArray) ?: defaultA11ySmokeScript
    return toolRecordPreview(
      buildJsonObject {
        put("uri", uri)
        put("events", events)
        put("observe", args["observe"]?.jsonPrimitive?.contentOrNull ?: "frames")
        args["emitTest"]?.let { put("emitTest", it) }
        args["format"]?.let { put("format", it) }
      }
    )
  }

  /**
   * Default `run-story-tests` script when the caller gives none: reload the scene, then assert no
   * accessibility findings — the a11y-smoke degenerate case of the interaction+assertion timeline.
   */
  private val defaultA11ySmokeScript: JsonArray = buildJsonArray {
    add(
      buildJsonObject {
        put("tMs", 0)
        put("kind", "preview.reload")
      }
    )
    add(
      buildJsonObject {
        put("tMs", 100)
        put("kind", "assert.a11y")
      }
    )
  }

  /**
   * One or many story ids from `storyIds` (array) or `storyId`/`id`. Null when neither is present.
   */
  private fun storybookStoryIds(args: JsonObject): List<String>? {
    (args["storyIds"] as? JsonArray)?.let { arr ->
      val ids = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
      return ids.ifEmpty { null }
    }
    val single =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return single?.let { listOf(it) }
  }

  private suspend fun toolStatus(session: Session): CallToolResult {
    val projects = supervisor.listProjects()
    val hint = if (projects.isEmpty()) projectHint(session) else null
    val catalogState =
      when {
        fullToolCatalogError != null -> "failed"
        fullToolDefsFuture.isDone -> "ready"
        else -> "loading"
      }
    val fullToolCount =
      if (catalogState == "ready") {
        runCatching { fullToolDefsFuture.getNow(emptyList()).size }.getOrDefault(0)
      } else {
        null
      }
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-status/v1")
      put("ready", true)
      put("serverVersion", serverInfo.version)
      putJsonObject("toolCatalog") {
        put("status", catalogState)
        put("bootstrapToolCount", bootstrapToolDefs.size)
        fullToolCount?.let { put("fullToolCount", it) }
        put("delayed", fullToolCatalogWasDelayed.get())
        fullToolCatalogError?.let { put("error", it) }
      }
      putJsonArray("projects") {
        projects.forEach { project ->
          add(
            buildJsonObject {
              put("workspaceId", project.workspaceId.value)
              put("rootProjectName", project.rootProjectName)
              put("path", project.path.absolutePath)
              putJsonArray("modules") {
                synchronized(project.knownModules) {
                  project.knownModules.forEach { add(JsonPrimitive(it)) }
                }
              }
              putJsonArray("daemons") {
                project.daemons.forEach { (module, daemon) ->
                  add(
                    buildJsonObject {
                      put("module", module)
                      put("spawned", daemon.replicaCount() > 0)
                      daemon.initializeResult?.let { init ->
                        put("daemonVersion", init.daemonVersion)
                        put("protocolVersion", init.protocolVersion)
                      }
                      put("initialDiscoveryComplete", daemon.initialDiscoveryComplete)
                      put(
                        "previewCount",
                        catalog[DaemonAddr(project.workspaceId, module)]?.size ?: 0,
                      )
                    }
                  )
                }
              }
            }
          )
        }
      }
      hint?.let { put("projectHint", it) }
      put("freshness", freshnessMetrics.toJson())
    }
    return textCallToolResult(payload.toString())
  }

  private fun toolRegisterProject(args: JsonObject): CallToolResult {
    val path =
      args["path"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("register_project: missing 'path'")
    val rootName = args["rootProjectName"]?.jsonPrimitive?.contentOrNull
    val modules =
      (args["modules"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val file = File(path)
    if (!file.isDirectory)
      return errorCallToolResult("register_project: '$path' is not a directory")
    val project = registerProjectAt(file, rootName, modules)
    warmUp(project)
    val payload = buildJsonObject {
      put("workspaceId", project.workspaceId.value)
      put("rootProjectName", project.rootProjectName)
      put("path", project.path.absolutePath)
      putJsonArray("modules") { project.knownModules.forEach { add(JsonPrimitive(it)) } }
      put("warming", true)
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /** Background warm-ups started by `register_project`, one per workspace while it runs. */
  private val warmUps = ConcurrentHashMap<WorkspaceId, Job>()

  /**
   * Starts preparing [project] in the background so the first render finds it warm: Gradle
   * bootstrap if it has no launch descriptor, then, for a single-module build, the daemon spawn and
   * `initialize` (the same steps a first render by name runs). Only an explicit `register_project`
   * warms; auto-discovered siblings and restored workspaces stay lazy.
   *
   * Nothing starts twice: [ProjectBootstrap.ensurePrepared] shares one Gradle run per build and
   * [DaemonSupervisor.daemonFor] one spawn per module, so a concurrent render joins in. Failures
   * are only logged; the next render reports them.
   */
  private fun warmUp(project: RegisteredProject) {
    val id = project.workspaceId
    warmUps
      .computeIfAbsent(id) {
        budgetedCallScope.launch(start = CoroutineStart.LAZY) {
          val log: (String) -> Unit = { System.err.println("compose-preview-mcp: warm-up: $it") }
          try {
            prepareProjects(setOf(id), log).forEach { (unprepared, reason) ->
              log("${unprepared.path} not prepared: $reason")
            }
            // One module: start its daemon now. Several: the first render starts the one that
            // holds its preview, rather than a daemon per module up front.
            val modules = runCatching {
              DescriptorProvider.indexDescriptorsByModulePath(project.path).keys
            }
              .getOrDefault(emptySet())
            if (modules.size <= 1) spawnUndiscoveredModules(setOf(id), caller = "warm-up")
            else log("${project.path}: ${modules.size} modules; daemons start on first render")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            log("failed for ${project.path}: ${e.message}")
          } finally {
            warmUps.remove(id, coroutineContext[Job])
          }
        }
      }
      .start()
  }

  /** The `register_project` path, shared with [autoRegisterWorkspace]. */
  private fun registerProjectAt(
    dir: File,
    rootName: String?,
    modules: List<String>,
  ): RegisteredProject {
    val project = supervisor.registerProject(dir, rootName, modules)
    storeOnlyProjects -= project.workspaceId
    sessions.forEach { it.notifyResourceListChanged() }
    return project
  }

  /**
   * Registers the client's workspace on first use when nothing is registered: the MCP roots, else
   * [workingDirectory]. Only Gradle builds qualify.
   */
  private suspend fun autoRegisterWorkspace(session: Session) {
    val tried = sessionRoots(session)
    val candidates = tried.dirs
    preferredRoots = candidates
    if (supervisor.listProjects().isNotEmpty()) {
      // A build of these roots that the library's sweep restored first is this session's own.
      val wanted = candidates.map { runCatching { it.canonicalFile }.getOrDefault(it) }
      supervisor
        .listProjects()
        .filter { p -> wanted.any { p.path.startsWith(it) || it.startsWith(p.path) } }
        .forEach { storeOnlyProjects -= it.workspaceId }
      registerNestedBuilds(candidates)
      return
    }
    lastTried = tried
    // After a restart, a build registered before (workspaces.json) comes back under its old id.
    val restored = supervisor.restoreMatching(candidates)
    restored.forEach { storeOnlyProjects -= it.workspaceId }
    if (restored.isNotEmpty()) {
      sessions.forEach { it.notifyResourceListChanged() }
    }
    // A root that is not itself a build: the enclosing build, else builds up to two levels below
    // (e.g. one build per sample). Only already-live builds are skipped.
    val builds = candidates.flatMap { ProjectDiscovery.buildsFor(it) }.distinct()
    val live =
      supervisor.listProjects().map { runCatching { it.path.canonicalFile }.getOrDefault(it.path) }
    val fresh = builds.filter { it !in live }
    if (builds.size == 1 && fresh.size == 1) {
      registerQuietly(fresh.single())
    } else {
      // Several: registered on the first `preview=` lookup, which then searches all of them.
      pendingBuilds = fresh
    }
  }

  private fun registerQuietly(dir: File): RegisteredProject? = runCatching {
    registerProjectAt(dir, rootName = null, modules = emptyList())
  }
    .onFailure { System.err.println("auto-register failed for $dir: ${it.message}") }
    .getOrNull()

  /** What the last auto-registration looked at, for the "nothing registered" message. */
  private data class Tried(val dirs: List<File>, val fromRoots: Boolean)

  @Volatile private var lastTried: Tried? = null

  /** Builds found under the roots when there were several; registered on a `preview=` lookup. */
  @Volatile private var pendingBuilds: List<File> = emptyList()

  private sealed interface ProjectArgument {
    data class Registered(val workspaceIds: Set<WorkspaceId>) : ProjectArgument

    data class Rejected(val message: String) : ProjectArgument
  }

  /**
   * The `project` argument: an absolute path to a build or any folder in one. Registers it so hosts
   * that send no roots need no separate register_project call.
   */
  private fun registerProjectArgument(path: String): ProjectArgument {
    val dir = File(path)
    if (!dir.isAbsolute) {
      return ProjectArgument.Rejected("project '$path' must be an absolute path")
    }
    if (!dir.exists()) return ProjectArgument.Rejected("project '$path' does not exist")
    val builds = ProjectDiscovery.buildsFor(dir)
    if (builds.isEmpty()) {
      return ProjectArgument.Rejected(
        "project '$path' is not in a Gradle build: no settings.gradle(.kts) there, above it, or " +
          "up to ${ProjectDiscovery.SEARCH_DEPTH} levels below it"
      )
    }
    val ids = builds.mapNotNull { registerQuietly(it)?.workspaceId }.toSet()
    return if (ids.isEmpty()) ProjectArgument.Rejected("project '$path' could not be registered")
    else ProjectArgument.Registered(ids)
  }

  /**
   * The error when no project is registered: what was tried and why it did not qualify, how to name
   * a project, and the builds found or used before (up to ten).
   */
  private fun notRegisteredMessage(tried: Tried? = lastTried): String {
    val triedText =
      if (tried == null || tried.dirs.isEmpty()) {
        "The client sent no workspace roots and the server has no working directory."
      } else {
        val source =
          if (tried.fromRoots) "the client's workspace roots" else "the working directory"
        "Tried ${tried.dirs.joinToString(", ") { it.path }} ($source): not Gradle builds, with " +
          "no settings.gradle(.kts) above them or up to ${ProjectDiscovery.SEARCH_DEPTH} levels " +
          "below."
      }
    val candidates = candidateBuilds()
    return buildString {
      append("no project registered. ")
      append(triedText)
      append(" Pass project=<absolute path> (the Gradle build, or any folder in it).")
      if (candidates.isNotEmpty()) append(" Candidate builds: ${candidates.joinToString(", ")}.")
    }
  }

  /** The builds found under the roots or used before (up to ten), for naming as `project=`. */
  private fun candidateBuilds(): List<String> =
    (pendingBuilds.map { it.path } + supervisor.workspaceStore.all().map { it.path })
      .distinct()
      .filter { File(it).isDirectory }
      .take(10)

  /**
   * `status`'s `projectHint` while nothing is registered: what was tried and which builds to pass
   * as `project=`. Registers nothing.
   */
  private suspend fun projectHint(session: Session): JsonObject {
    val tried = lastTried ?: sessionRoots(session)
    val builds = tried.dirs.flatMap { ProjectDiscovery.buildsFor(it) }.distinct()
    val candidates = (builds.map { it.path } + candidateBuilds()).distinct().take(10)
    return buildJsonObject {
      put(
        "message",
        if (builds.isEmpty()) notRegisteredMessage(tried)
        else
          "no project registered yet. Found ${builds.joinToString(", ") { it.path }} under " +
            "${tried.dirs.joinToString(", ") { it.path }}; render_preview registers it on first " +
            "use, or pass project=<absolute path> (the Gradle build, or any folder in it).",
      )
      putJsonArray("tried") { tried.dirs.forEach { add(JsonPrimitive(it.path)) } }
      put(
        "triedSource",
        when {
          tried.dirs.isEmpty() -> "none"
          tried.fromRoots -> "roots"
          else -> "workingDirectory"
        },
      )
      putJsonArray("candidateBuilds") { candidates.forEach { add(JsonPrimitive(it)) } }
    }
  }

  /** Progress lines for a tool call: `notifications/progress` when the client sent a token. */
  private fun progressReporter(session: Session, token: JsonElement?): (String) -> Unit {
    var step = 0
    return { message ->
      System.err.println("compose-preview-mcp: $message")
      if (token != null) {
        step++
        runCatching { session.notifyProgress(token, step.toDouble(), message = message) }
      }
    }
  }

  /**
   * Runs [projectBootstrap] for each registered project in [scope] that cannot start a daemon yet:
   * no known modules, no live daemon and no launch descriptor on disk. Returns the reason for each
   * one that is still not prepared.
   */
  private fun prepareProjects(
    scope: Set<WorkspaceId>?,
    progress: (String) -> Unit,
  ): Map<RegisteredProject, String> {
    val bootstrap = projectBootstrap ?: return emptyMap()
    return supervisor
      .listProjects()
      .filter { scope == null || it.workspaceId in scope }
      .filter { project ->
        project.daemons.isEmpty() &&
          synchronized(project.knownModules) { project.knownModules.isEmpty() }
      }
      .mapNotNull { project ->
        val outcome =
          starting(project.workspaceId) { bootstrap.ensurePrepared(project.path, progress) }
        when (outcome) {
          is ProjectBootstrap.Outcome.Ready -> null
          is ProjectBootstrap.Outcome.NotPrepared -> project to outcome.reason
        }
      }
      .toMap()
  }

  /** Builds in a Gradle bootstrap or a daemon start right now, for [pendingCallResult]. */
  private val startingProjects = ConcurrentHashMap<WorkspaceId, AtomicInteger>()

  private inline fun <T> starting(id: WorkspaceId, block: () -> T): T {
    startingProjects.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
    try {
      return block()
    } finally {
      startingProjects.computeIfPresent(id) { _, count ->
        if (count.decrementAndGet() <= 0) null else count
      }
    }
  }

  /** The client's MCP roots, else [workingDirectory]; asked once per session. */
  private suspend fun sessionRoots(session: Session): Tried {
    sessionRootsCache[session]?.let {
      return it
    }
    val roots = (session as? McpSession)?.rootDirectories().orEmpty()
    val tried =
      Tried(roots.ifEmpty { listOfNotNull(workingDirectory) }, fromRoots = roots.isNotEmpty())
    sessionRootsCache[session] = tried
    activeDesignRoots.update(session, tried.dirs)
    return tried
  }

  private val sessionRootsCache = ConcurrentHashMap<Session, Tried>()

  /**
   * Registers a session root's build nested in (or holding) a registered build, e.g. a git worktree
   * under `.claude/worktrees/`, where edits land in the worktree's copy.
   */
  private fun registerNestedBuilds(candidates: List<File>) {
    val registered =
      supervisor.listProjects().map { runCatching { it.path.canonicalFile }.getOrDefault(it.path) }
    candidates
      .flatMap { ProjectDiscovery.buildsFor(it) }
      .distinct()
      .filter { build ->
        build !in registered && registered.any { build.startsWith(it) || it.startsWith(build) }
      }
      .forEach { registerQuietly(it) }
  }

  /**
   * The latest session's roots. When a preview name matches in several registered builds (a
   * checkout and its worktree), the build containing these wins, the innermost first.
   */
  @Volatile private var preferredRoots: List<File> = emptyList()

  /** How strongly [workspaceRoot] contains one of [preferredRoots]: its path length, else -1. */
  private fun rootPreference(workspaceRoot: File?): Int {
    val root = workspaceRoot?.let { runCatching { it.canonicalFile }.getOrDefault(it) } ?: return -1
    return if (
      preferredRoots.any { runCatching { it.canonicalFile }.getOrDefault(it).startsWith(root) }
    )
      root.path.length
    else -1
  }

  private sealed interface PreviewNameResolution {
    data class Found(val uri: String, val others: List<String>) : PreviewNameResolution

    data class Missing(val message: String, val structured: JsonObject? = null) :
      PreviewNameResolution
  }

  /**
   * Resolves `render_preview`'s `preview` argument (a function name or unique FQN suffix). With no
   * match it first starts the registered modules' daemons so discovery seeds the catalog, then
   * looks again.
   */
  private fun resolvePreviewName(
    name: String,
    scope: Set<WorkspaceId>? = null,
    progress: (String) -> Unit = {},
  ): PreviewNameResolution {
    val trimmed = name.trim()
    var matches = previewNameMatches(trimmed, scope)
    var unprepared = emptyMap<RegisteredProject, String>()
    if (matches.isEmpty()) {
      val prepareScope =
        when (val narrowed = buildScopeFor(trimmed, scope)) {
          is BuildScope.Use -> narrowed.scope
          is BuildScope.Ambiguous -> return PreviewNameResolution.Missing(narrowed.message)
        }
      if (supervisor.listProjects().isEmpty()) {
        return PreviewNameResolution.Missing(notRegisteredMessage())
      }
      unprepared = prepareProjects(prepareScope, progress)
      spawnUndiscoveredModules(prepareScope, name = trimmed)
      matches = previewNameMatches(trimmed, prepareScope)
    }
    if (matches.isEmpty() && unprepared.isEmpty()) {
      matches = rediscoverForName(trimmed, scope, progress)
    }
    if (matches.isEmpty() && unprepared.isNotEmpty()) {
      // Never "no preview matches" for a build that could not even be discovered.
      return PreviewNameResolution.Missing(
        "project not prepared: " +
          unprepared.entries.joinToString("; ") { (project, reason) ->
            if (unprepared.size == 1) reason else "${project.path}: $reason"
          }
      )
    }
    if (matches.isEmpty()) return noPreviewMatches(trimmed)
    return PreviewNameResolution.Found(matches.first(), matches.drop(1))
  }

  /**
   * A name the catalog has never seen in a build with running daemons: if a `.kt` source declares
   * `fun <name>(`, rerun `composePreviewDiscover`, reload each manifest and look again (incremental
   * discovery misses previews in new files). The source scan keeps typos from costing a Gradle run;
   * each declaring file version is rediscovered at most once.
   */
  private fun rediscoverForName(
    name: String,
    scope: Set<WorkspaceId>?,
    progress: (String) -> Unit,
  ): List<String> {
    val bootstrap = projectBootstrap ?: return emptyList()
    val function = name.substringAfterLast('.').trim()
    if (function.isEmpty()) return emptyList()
    val candidates = buildList {
      add(function)
      if ('_' in function) add(function.substringBefore('_'))
    }
    var ran = false
    supervisor
      .listProjects()
      .filter { scope == null || it.workspaceId in scope }
      .filter { it.daemons.isNotEmpty() }
      .forEach { project ->
        val declaring =
          candidates.firstNotNullOfOrNull { findFunctionSource(project.path, it) } ?: return@forEach
        val attempt = RediscoveryKey(project.path.absolutePath, declaring.absolutePath)
        val version = declaring.lastModified()
        if (rediscoveredSources.put(attempt, version) == version) return@forEach
        val result = bootstrap.rediscover(project.path, progress) ?: return@forEach
        if (result.exitCode != 0) {
          System.err.println(
            "compose-preview-mcp: rediscovery in ${project.path} failed: " +
              GradleSourceCompiler.summarizeGradleFailure(result.output)
          )
        }
        project.daemons.values.forEach { reloadManifestIfChanged(it, force = true) }
        ran = true
      }
    return if (ran) previewNameMatches(name, scope) else emptyList()
  }

  private sealed interface BuildScope {
    data class Use(val scope: Set<WorkspaceId>?) : BuildScope

    data class Ambiguous(val message: String) : BuildScope
  }

  /**
   * Which builds a by-name lookup may register and prepare. Preparing is a full Gradle run, so only
   * the build holding the preview is prepared, never every build in reach. Candidates are
   * unregistered builds under the roots or remembered from earlier sessions, plus registered but
   * unprepared ones. Exactly one declaring `fun <name>(` is prepared; none is left alone; several
   * ask the agent to choose without running Gradle.
   */
  private fun buildScopeFor(name: String, scope: Set<WorkspaceId>?): BuildScope {
    if (scope != null) return BuildScope.Use(scope)
    val registered = supervisor.listProjects()
    val unprepared = registered.filter { project ->
      project.daemons.isEmpty() &&
        synchronized(project.knownModules) { project.knownModules.isEmpty() } &&
        projectBootstrap?.isPrepared(project.path) != true
    }
    val registeredPaths = registered.map { canonical(it.path) }.toSet()
    // The session's own builds first; builds remembered from earlier sessions only when the
    // session offers none, or an old checkout would compete with the one being worked on.
    val fromRoots = pendingBuilds.map(::canonical).filter { it !in registeredPaths }
    val unregistered = fromRoots.ifEmpty {
      if (registered.isNotEmpty()) emptyList()
      else candidateBuilds().map { canonical(File(it)) }.filter { it !in registeredPaths }
    }
    val candidates = unprepared.map { canonical(it.path) } + unregistered
    // One build, or nothing to choose between: prepare whatever is registered, as before.
    if (candidates.size <= 1 && unregistered.isEmpty()) return BuildScope.Use(null)
    val function = name.substringAfterLast('.').trim()
    val functions =
      listOfNotNull(function, function.substringBefore('_').takeIf { '_' in function })
    val declaring = candidates.filter { build ->
      functions.any { findFunctionSource(build, it) != null }
    }
    if (declaring.size > 1) {
      return BuildScope.Ambiguous(
        "no preview matches '$name' yet, and ${declaring.size} builds declare fun $function(. " +
          "Preparing a build is a full Gradle run, so none was started. Pass project=<absolute " +
          "path> (the folder with the build's settings.gradle(.kts)). Candidate builds: " +
          "${declaring.take(10).joinToString(", ") { it.path }}."
      )
    }
    val chosen =
      declaring.singleOrNull()?.let { build ->
        registered.firstOrNull { canonical(it.path) == build }
          ?: registerQuietly(build)?.also { pendingBuilds = pendingBuilds.filter { it != build } }
      }
    val prepared = supervisor.listProjects().filter { it !in unprepared && it != chosen }
    return when {
      chosen != null -> BuildScope.Use((prepared + chosen).map { it.workspaceId }.toSet())
      prepared.isNotEmpty() -> BuildScope.Use(prepared.map { it.workspaceId }.toSet())
      unprepared.size == 1 && unregistered.isEmpty() -> BuildScope.Use(null)
      registered.isEmpty() -> BuildScope.Use(null)
      else ->
        BuildScope.Ambiguous(
          "no preview matches '$name' yet, and none of the ${candidates.size} candidate builds " +
            "declares fun $function(. Pass project=<absolute path> (the folder with the build's " +
            "settings.gradle(.kts)). Candidate builds: " +
            "${candidates.take(10).joinToString(", ") { it.path }}."
        )
    }
  }

  private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file)

  private data class RediscoveryKey(val projectRoot: String, val source: String)

  private val rediscoveredSources = ConcurrentHashMap<RediscoveryKey, Long>()

  /** The first `.kt` under [root], outside build output, that declares `fun <function>(`. */
  private fun findFunctionSource(root: File, function: String): File? {
    val declaration = Regex("""\bfun\s+${Regex.escape(function)}\s*\(""")
    return root
      .walkTopDown()
      .onEnter { it.name !in SOURCE_SCAN_SKIPPED_DIRS }
      .filter { it.isFile && it.extension == "kt" }
      .take(MAX_SOURCE_SCAN_FILES)
      .firstOrNull { file ->
        runCatching { declaration.containsMatchIn(file.readText()) }.getOrDefault(false)
      }
  }

  /**
   * The no-match error: up to five nearest function names and the catalog's size, in the text and
   * as `structuredContent.suggestions`, so the agent can retry without listing every preview.
   */
  private fun noPreviewMatches(name: String): PreviewNameResolution.Missing {
    val previewCount = catalog.values.sumOf { it.size }
    val moduleCount = catalog.values.count { it.isNotEmpty() }
    val suggestions = closePreviewNames(name)
    val message = buildString {
      append("no preview matches '").append(name).append("'.")
      if (suggestions.isNotEmpty()) append(" Closest: ").append(suggestions.joinToString(", "))
      append(" (").append(plural(previewCount, "preview")).append(" in ")
      append(plural(moduleCount, "module")).append(").")
      append(" Call find_previews_for_file with the source file to list its previews.")
    }
    return PreviewNameResolution.Missing(
      message,
      buildJsonObject {
        putJsonArray("suggestions") { suggestions.forEach { add(JsonPrimitive(it)) } }
        put("previewCount", previewCount)
        put("moduleCount", moduleCount)
      },
    )
  }

  private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

  /**
   * Matching URIs: an exact (non-variant) match first, then the build holding the session's roots
   * (a worktree beats its checkout), then URI order.
   */
  private fun previewNameMatches(name: String, scope: Set<WorkspaceId>? = null): List<String> {
    fun matchesName(id: String) = id == name || id.endsWith(".$name")
    data class Match(val exact: Boolean, val preference: Int, val uri: String)
    return catalog
      .filter { (addr, _) -> scope == null || addr.workspaceId in scope }
      .flatMap { (addr, byId) ->
        val preference = rootPreference(supervisor.project(addr.workspaceId)?.path)
        byId.values.mapNotNull { entry ->
          val exact = matchesName(entry.fqn)
          if (!exact && previewBaseIds(entry).none(::matchesName)) return@mapNotNull null
          val uri = PreviewUri(addr.workspaceId, addr.modulePath, entry.fqn, entry.config).toUri()
          Match(exact, preference, uri)
        }
      }
      .sortedWith(
        compareBy<Match> { !it.exact }.thenByDescending { it.preference }.thenBy { it.uri }
      )
      .map { it.uri }
  }

  /** The function's own FQN for a variant id such as `…Kt.ListPreview_Devices - Small Round`. */
  private fun previewBaseIds(entry: PreviewEntry): List<String> {
    val functionName = entry.functionName ?: return emptyList()
    val owner = entry.fqn.substringBeforeLast('.', "")
    return listOf(if (owner.isEmpty()) functionName else "$owner.$functionName")
  }

  /** Up to five catalogued function names nearest to [name], for the no-match error. */
  private fun closePreviewNames(name: String): List<String> {
    val needle = name.substringAfterLast('.').lowercase()
    return catalog.values
      .flatMap { it.values }
      .map { entry -> entry.functionName ?: entry.fqn.substringAfterLast('.') }
      .distinct()
      .sortedWith(
        compareBy<String> { !it.lowercase().contains(needle) && !needle.contains(it.lowercase()) }
          .thenBy { editDistance(it.lowercase(), needle) }
          .thenBy { it }
      )
      .take(5)
  }

  private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val current = IntArray(b.length + 1)
      current[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
      }
      previous = current
    }
    return previous[b.length]
  }

  /**
   * Starts each registered module's daemon that is not running, to seed the catalog. With a preview
   * [name], only modules whose `previews.json` names it (when any does), so a multi-module build
   * doesn't start every module's daemon for one preview.
   */
  private fun spawnUndiscoveredModules(
    scope: Set<WorkspaceId>? = null,
    caller: String = "render_preview",
    name: String? = null,
  ) {
    supervisor
      .listProjects()
      .filter { scope == null || it.workspaceId in scope }
      .forEach { project ->
        val descriptors = runCatching {
          DescriptorProvider.indexDescriptorsByModulePath(project.path)
        }
          .getOrDefault(emptyMap())
        val modules =
          synchronized(project.knownModules) { project.knownModules.toSet() } + descriptors.keys
        val declaring =
          name
            ?.let { previewName ->
              descriptors.filter { (_, descriptor) ->
                manifestNamesPreview(File(descriptor.parentFile, "previews.json"), previewName)
              }
            }
            ?.keys
            .orEmpty()
        declaring
          .ifEmpty { modules }
          .filterNot { project.daemons.containsKey(it) }
          .forEach { module ->
            runCatching {
              starting(project.workspaceId) { supervisor.daemonFor(project.workspaceId, module) }
            }
              .onFailure { System.err.println("$caller: could not start $module: ${it.message}") }
          }
      }
  }

  /** Whether [manifest] (a module's `previews.json`) lists a preview that [name] matches. */
  private fun manifestNamesPreview(manifest: File, name: String): Boolean {
    if (!manifest.isFile) return false
    val previews =
      runCatching {
        (json.parseToJsonElement(manifest.readText()) as? JsonObject)?.get("previews") as? JsonArray
      }
        .getOrNull() ?: return false
    val function = name.substringAfterLast('.')
    return previews.any { element ->
      val preview = element as? JsonObject ?: return@any false
      val id = preview["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
      preview["functionName"]?.jsonPrimitive?.contentOrNull == function ||
        id == name ||
        id.endsWith(".$name")
    }
  }

  private sealed interface PreviewCard {
    /** [detailsDropped] says why the requested details were left out to fit the size cap. */
    data class Written(val file: File, val detailsDropped: String? = null) : PreviewCard

    data class Skipped(val reason: String) : PreviewCard
  }

  /**
   * Fetches `render_preview`'s opt-in [details] for the render that just finished. Never throws: an
   * unavailable detail becomes an `unavailable` summary line and is left out of the card.
   */
  private fun fetchRenderDetails(uri: PreviewUri, details: Set<RenderDetail>): RenderDetails {
    if (details.isEmpty()) return RenderDetails.NONE
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse { error ->
        val reason = "daemon unavailable (${error.message})"
        return RenderDetails(
          summaries = details.sorted().map { "${it.wire}: unavailable ($reason)" },
          card =
            buildJsonObject {
              putJsonObject("unavailable") { details.forEach { put(it.wire, reason) } }
            },
          overlayPng = null,
        )
      }
    val kinds = enableDetailExtensions(daemon, details)
    val unavailable = linkedMapOf<String, String>()
    var overlay: ByteArray? = null
    var a11y: RenderDetailReaders.A11y? = null
    var layout: Pair<String, RenderDetailReaders.Layout>? = null

    if (RenderDetail.A11Y in details) {
      if (A11Y_FINDINGS_KIND !in kinds) {
        unavailable["a11y"] = "this daemon does not produce $A11Y_FINDINGS_KIND"
      } else {
        runCatching {
          RenderDetailReaders.a11y(fetchDetailPayload(uri, daemon, A11Y_FINDINGS_KIND))
        }
          .onSuccess { a11y = it }
          .onFailure { unavailable["a11y"] = detailFailure(it) }
        if (a11y != null && DEFAULT_OVERLAY_KIND in kinds) {
          overlay = runCatching { fetchOverlayPng(uri, daemon) }.getOrNull()
        }
      }
    }
    if (RenderDetail.LAYOUT in details) {
      val kind = LAYOUT_DETAIL_KINDS.firstOrNull { it in kinds }
      if (kind == null) {
        unavailable["layout"] =
          "this daemon produces neither ${LAYOUT_DETAIL_KINDS.joinToString(" nor ")}"
      } else {
        runCatching {
          kind to RenderDetailReaders.layout(kind, fetchDetailPayload(uri, daemon, kind))
        }
          .onSuccess { layout = it }
          .onFailure { unavailable["layout"] = detailFailure(it) }
      }
    }
    val summaries = buildList {
      a11y?.let { add(it.summary) }
      unavailable["a11y"]?.let { add("a11y: unavailable ($it)") }
      layout?.let { add(it.second.summary) }
      unavailable["layout"]?.let { add("layout: unavailable ($it)") }
    }
    val card = buildJsonObject {
      a11y?.let { found ->
        putJsonObject("a11y") {
          put("summary", found.summary)
          put("overlay", overlay != null)
          put("findings", found.findings)
        }
      }
      layout?.let { (kind, found) ->
        putJsonObject("layout") {
          put("summary", found.summary)
          put("kind", kind)
          put("nodes", found.nodeCount)
          put("boxes", found.boxes)
        }
      }
      if (unavailable.isNotEmpty()) {
        putJsonObject("unavailable") { unavailable.forEach { (key, reason) -> put(key, reason) } }
      }
    }
    return RenderDetails(summaries, card, overlay)
  }

  /**
   * The data-product kinds [daemon] advertises for [details], enabling them first. Daemons start
   * with most extensions inactive (PROTOCOL.md § 3a), and a timed-out `initialize` leaves cached
   * capabilities empty, so asking for a detail enables its extensions and refreshes the snapshot,
   * like `enable_extensions`. A rejecting daemon keeps its cached kinds.
   */
  private fun enableDetailExtensions(
    daemon: SupervisedDaemon,
    details: Set<RenderDetail>,
  ): Set<String> {
    val kinds = daemon.dataProductCapabilities.map { it.kind }.toSet()
    val wanted = buildList {
      if (RenderDetail.A11Y in details && A11Y_FINDINGS_KIND !in kinds) add(A11Y_EXTENSION_ID)
      if (RenderDetail.LAYOUT in details && LAYOUT_DETAIL_KINDS.none { it in kinds }) {
        addAll(LAYOUT_DETAIL_KINDS)
      }
    }
    if (wanted.isEmpty()) return kinds
    return runCatching { daemon.client.extensionsEnable(wanted) }
      .onSuccess {
        daemon.dataProductCapabilities = it.dataProducts
        daemon.dataExtensionDescriptors = it.dataExtensions
      }
      .onFailure {
        System.err.println("render_preview: extensions/enable $wanted failed: ${it.message}")
      }
      .map { result -> result.dataProducts.map { it.kind }.toSet() }
      .getOrDefault(kinds)
  }

  /** `data/fetch` of [kind] as JSON, rendering once first if the daemon has nothing yet. */
  private fun fetchDetailPayload(
    uri: PreviewUri,
    daemon: SupervisedDaemon,
    kind: String,
  ): JsonElement? {
    val result =
      try {
        daemon.client.dataFetch(uri.previewFqn, kind, null, inline = true)
      } catch (e: DataProductWireException) {
        if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
        awaitNextRender(uri)
        daemon.client.dataFetch(uri.previewFqn, kind, null, inline = true)
      }
    result.payload?.let {
      return it
    }
    result.bytes?.let {
      return json.parseToJsonElement(String(Base64.getDecoder().decode(it), Charsets.UTF_8))
    }
    result.path?.let {
      return json.parseToJsonElement(File(it).readText())
    }
    return null
  }

  private fun fetchOverlayPng(uri: PreviewUri, daemon: SupervisedDaemon): ByteArray? {
    val path =
      daemon.client
        .dataFetch(uri.previewFqn, DEFAULT_OVERLAY_KIND, params = null, inline = false)
        .path ?: return null
    return File(path).takeIf { it.isFile }?.readBytes()
  }

  private fun detailFailure(error: Throwable): String =
    when (error) {
      is DataProductWireException -> "${nameOf(error.code)}: ${error.wireMessage}"
      else -> error.message ?: error.javaClass.simpleName
    }

  /**
   * Writes an Antigravity preview card: the bundled viewer plus the render as an inline static
   * result block. Antigravity loads `<agent-embed src="file://…">` into an `iframe srcdoc`, so the
   * result must travel inside the file. Mirrors compose-ag-plugin `assets/compose-preview-card.py`,
   * including its 500,000-byte cap.
   */
  private fun writePreviewCard(
    uri: PreviewUri,
    pngBytes: ByteArray,
    sha: String,
    stablePng: File,
    details: RenderDetails = RenderDetails.NONE,
  ): PreviewCard {
    val summary = buildJsonObject {
      put("uri", uri.toUri())
      pngDimensions(pngBytes)?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      put("sha256", sha)
    }
    fun envelope(withDetails: Boolean) = buildJsonObject {
      put("version", 1)
      putJsonObject("arguments") { put("uri", uri.toUri()) }
      putJsonObject("result") {
        putJsonArray("content") {
          add(
            buildJsonObject {
              put("type", "image")
              put("data", Base64.getEncoder().encodeToString(pngBytes))
              put("mimeType", "image/png")
            }
          )
          add(
            buildJsonObject {
              put("type", "text")
              put("text", summary.toString())
            }
          )
          if (withDetails) {
            // Detail blocks come after the render and carry a `_meta` marker, so a viewer that
            // does not know them still draws the first image and reads the first text.
            details.overlayPng?.let { overlay ->
              add(
                buildJsonObject {
                  put("type", "image")
                  put("data", Base64.getEncoder().encodeToString(overlay))
                  put("mimeType", "image/png")
                  putJsonObject("_meta") { put(RenderDetails.META_KEY, DEFAULT_OVERLAY_KIND) }
                }
              )
            }
            add(
              buildJsonObject {
                put("type", "text")
                put("text", details.card.toString())
                putJsonObject("_meta") { put(RenderDetails.META_KEY, "details") }
              }
            )
          }
        }
      }
    }
    fun fits(text: String) = text.toByteArray(Charsets.UTF_8).size <= MAX_CARD_RESULT_BYTES
    // Over the cap, the details go first and the card only after them (issue #1170).
    var detailsDropped: String? = null
    val payload =
      if (details.isEmpty) {
        envelope(withDetails = false).toString()
      } else {
        envelope(withDetails = true).toString().takeIf(::fits)
          ?: envelope(withDetails = false).toString().also {
            detailsDropped = "the details did not fit the card's 500,000-byte cap"
          }
      }
    if (!fits(payload)) {
      return PreviewCard.Skipped("the render is too large for a card (over 500,000 bytes)")
    }
    return runCatching {
      // `<` for every `<` keeps `</script>` and `<!--` out of the block; JSON.parse undoes it.
      val block =
        "<script type=\"application/json\" id=\"compose-preview-result\">" +
          payload.replace("<", "\\u003c") +
          "</script>\n"
      val target =
        File(previewCardDirectory(stablePng), "compose-preview-card-${sha.take(12)}.html")
      target.writeText(viewerHtml() + block, Charsets.UTF_8)
      PreviewCard.Written(target, detailsDropped)
    }
      .getOrElse { PreviewCard.Skipped("could not write the card: ${it.message}") }
  }

  /** Antigravity's per-conversation artifact directory when it exists, else next to the PNG. */
  private fun previewCardDirectory(stablePng: File): File {
    val conversation = environment["ANTIGRAVITY_CONVERSATION_ID"]?.trim().orEmpty()
    if (conversation.isNotEmpty() && '/' !in conversation && conversation !in setOf(".", "..")) {
      val brain = File(homeDirectory, ".gemini/antigravity/brain/$conversation")
      if (brain.isDirectory) return brain
    }
    return stablePng.parentFile
  }

  private fun toolUnregisterProject(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("unregister_project: missing 'workspaceId'")
    val id = WorkspaceId(ws)
    forgetProject(id)
    sessions.forEach { it.notifyResourceListChanged() }
    return textCallToolResult("unregistered $id")
  }

  private fun forgetProject(id: WorkspaceId) {
    supervisor.unregisterProject(id)
    storeOnlyProjects.remove(id)
    catalog.keys.removeIf { it.workspaceId == id }
  }

  private fun toolListProjects(): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("projects") {
        supervisor.listProjects().forEach { project ->
          add(
            buildJsonObject {
              put("workspaceId", project.workspaceId.value)
              put("rootProjectName", project.rootProjectName)
              put("path", project.path.absolutePath)
              putJsonArray("modules") {
                synchronized(project.knownModules) {
                  project.knownModules.forEach { add(JsonPrimitive(it)) }
                }
              }
              put("branch", JsonPrimitive(detectBranch(project.path)))
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `list_devices`: the `DeviceDimensions` catalog as `{id, widthDp, heightDp, density}`, read
   * directly from `:daemon:core` (the same source as the daemon's `knownDevices`) to avoid spawning
   * a daemon. If device catalogs become backend-specific this must consult a daemon (see
   * `KNOWN_DEVICE_IDS`).
   */
  private fun toolListDevices(): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("devices") {
        ee.schimke.composeai.daemon.devices.DeviceDimensions.KNOWN_DEVICE_IDS.sorted().forEach { id
          ->
          val spec = ee.schimke.composeai.daemon.devices.DeviceDimensions.resolve(id)
          add(
            buildJsonObject {
              put("id", id)
              put("widthDp", spec.widthDp)
              put("heightDp", spec.heightDp)
              put("density", spec.density.toDouble())
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `render_preview preview=` with several matches (e.g. `@WearPreviewDevices` variants) asks via a
   * form when the client supports form elicitation. Otherwise the first match renders and every
   * match is listed under `variantChoice`; only a decline renders nothing (so the agent doesn't
   * re-ask). Cancel renders because headless clients cancel every form unseen.
   */
  private suspend fun renderPreviewChoosingVariant(
    session: Session,
    args: JsonObject,
    scope: Set<WorkspaceId>? = null,
    progress: (String) -> Unit = {},
  ): CallToolResult {
    val previewName = args["preview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (args["uri"] != null || previewName == null) return toolRenderPreview(session, args)
    val resolved = resolvePreviewName(previewName, scope, progress)
    if (resolved !is PreviewNameResolution.Found || resolved.others.isEmpty()) {
      return toolRenderPreview(session, args, resolved)
    }
    val choices = listOf(resolved.uri) + resolved.others
    // An OpenAI-forms client picks from thumbnails. Labels omit the shared function name (it's in
    // the form's message).
    val pickerLabels = PreviewPickers.variantLabels(choices)
    // A host draws a placeholder for an option without a thumbnail, so every match that would have
    // been a grid cell is rendered for its thumbnail when there is no cached render.
    val renderMissingThumbnails = choices.size <= MAX_VARIANT_CELLS
    PreviewPickers.ambiguousMatch(
        session,
        previewName,
        choices,
        label = { pickerLabels[choices.indexOf(it)] },
        thumbnail = { uri ->
          PreviewPickers.cachedThumbnail(uri, previewActivity, renderThumbnails)
            ?: if (!renderMissingThumbnails) null
            else
              runCatching { pickerThumbnail(renderAndReadBytes(PreviewUri.parse(uri))) }.getOrNull()
        },
        render = { toolRenderPreview(session, args, PreviewNameResolution.Found(it, emptyList())) },
      )
      ?.let {
        return it
      }
    // Several matches render as one labelled contact-sheet grid. The chooser remains for more
    // matches than a grid holds, and for file results.
    val inlineResult =
      (args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: !defaultsToFileResult(session, args)) &&
        args["card"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true &&
        args["crop"] == null &&
        (session as? McpSession)?.clientName != ANTIGRAVITY_CLIENT_NAME
    // The grid renders each variant plainly; a call that shapes the render or its result
    // (overrides, non-png observation, details, full-scale pixels, force) goes through the
    // single-preview path.
    val plainRender =
      args["overrides"].isAbsent() &&
        args["details"].let { it.isAbsent() || (it as? JsonArray)?.isEmpty() == true } &&
        args["observe"].let {
          it.isAbsent() || (it as? JsonPrimitive)?.contentOrNull?.lowercase() == "png"
        } &&
        args["imageScale"].let {
          it.isAbsent() || (it as? JsonPrimitive)?.contentOrNull?.lowercase() == "default"
        } &&
        args["force"].isAbsent()
    if (inlineResult && plainRender && choices.size <= MAX_VARIANT_CELLS) {
      val grid = renderVariantMatrix(session, choices, args, choose = false)
      if (grid.isError == true) return grid
      val choice = buildJsonObject {
        putJsonObject("variantChoice") {
          put("mode", "text")
          put(
            "message",
            "Several previews match; all were rendered as a grid. Render one by uri to look closer.",
          )
          putJsonArray("choices") { choices.forEach { add(JsonPrimitive(it)) } }
        }
      }
      return grid.copy(content = grid.content + ContentBlock.Text(choice.toString()))
    }
    val labels = PreviewPickers.variantLabels(choices)
    val askedAt = System.nanoTime()
    val elicitation =
      (session as? McpSession)?.elicitForm(
        message = "Several previews match '$previewName'. Choose the one to render.",
        requestedSchema =
          buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
              putJsonObject("variant") {
                put("type", "string")
                put("title", "Preview")
                putJsonArray("enum") { labels.forEach { add(JsonPrimitive(it)) } }
              }
            }
            putJsonArray("required") { add(JsonPrimitive("variant")) }
          },
      ) ?: FormElicitation.Unsupported
    fun choiceBlock(mode: String, message: String) =
      ContentBlock.Text(
        buildJsonObject {
          putJsonObject("variantChoice") {
            put("mode", mode)
            put("message", message)
            putJsonArray("choices") { choices.forEach { add(JsonPrimitive(it)) } }
          }
        }
          .toString()
      )
    fun renderFirst(mode: String, message: String): CallToolResult {
      val result = toolRenderPreview(session, args, resolved)
      return result.copy(content = result.content + choiceBlock(mode, message))
    }
    val textFallback =
      "Several previews match; the first was rendered. Ask the user which one they meant and " +
        "call render_preview with that uri."
    val answer =
      when (elicitation) {
        FormElicitation.Unsupported -> return renderFirst("text", textFallback)
        FormElicitation.TimedOut ->
          return renderFirst(
            "timeout",
            "The chooser was not answered in time; the first match was rendered. Do not re-open " +
              "it; list the choices and let the user pick.",
          )
        is FormElicitation.Answered -> elicitation.result
      }
    val answeredMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - askedAt)
    when (answer.action) {
      // A decline faster than anyone could read the form is the host's (e.g. an SDK session
      // declining unseen), so treat it like a client without forms.
      ElicitResult.Action.Decline if answeredMs < UNSEEN_ANSWER_MS ->
        return renderFirst("text", textFallback)
      ElicitResult.Action.Decline ->
        return CallToolResult(
          content =
            listOf(
              choiceBlock(
                "declined",
                "The user declined the preview choice, so nothing was rendered. Do not ask again " +
                  "unless they bring it up; render one by uri if they do.",
              )
            )
        )
      // A cancel is not an answer: headless clients (Claude Code's print mode) cancel every form
      // without showing it. Render the first match, as for a client without forms.
      ElicitResult.Action.Cancel -> return renderFirst("cancelled", CANCELLED_CHOICE_MESSAGE)
      ElicitResult.Action.Accept -> Unit
    }
    val picked =
      (answer.content?.get("variant") as? JsonPrimitive)?.contentOrNull?.let { label ->
        choices.getOrNull(labels.indexOf(label))
      } ?: return renderFirst("text", textFallback)
    val result = toolRenderPreview(session, args, PreviewNameResolution.Found(picked, emptyList()))
    return result.copy(
      content =
        result.content +
          choiceBlock("elicitation", "The user chose this preview in a form: $picked")
    )
  }

  private fun JsonElement?.isAbsent(): Boolean = this == null || this is JsonNull

  /** [args] with the `openai/settings` defaults filled in where the call is silent. */
  private fun withSettings(session: Session, args: JsonObject): JsonObject =
    previewSettingsStore
      .read()
      .applyToRenderPreview(
        args,
        clientDefaultsToFile = (session as? McpSession)?.clientName in FILE_RESULT_CLIENT_NAMES,
      )

  /** Registered projects → modules → discovered previews, for [PreviewLibrary]. */
  private fun libraryProjects(projectId: String?): List<PreviewLibrary.Project> {
    supervisor.forgetProjectsMissingFromStore().forEach { id ->
      storeOnlyProjects.remove(id)
      catalog.keys.removeIf { it.workspaceId == id }
    }
    // The global sidebar may use a different process (and roots) from the chat that registered
    // the build. Restore remembered ids lazily: browsing must not start every build's daemons.
    val live = supervisor.listProjects().mapTo(HashSet()) { it.workspaceId }
    supervisor.workspaceStore.all().forEach { entry ->
      val id = WorkspaceId(entry.id)
      if (id !in live && supervisor.project(id) != null) storeOnlyProjects += id
    }
    val projects = supervisor.listProjects().sortedBy { it.rootProjectName }
    projects.firstOrNull { it.workspaceId.value == projectId }?.let(::warmUp)
    return projects.map { project ->
      val discovered = catalog.keys.filter { it.workspaceId == project.workspaceId }
      val modules =
        (synchronized(project.knownModules) { project.knownModules.toSet() } +
            discovered.map { it.modulePath })
          .sorted()
      PreviewLibrary.Project(
        id = project.workspaceId.value,
        name = project.rootProjectName,
        path = project.path.absolutePath,
        warming = warmUps.containsKey(project.workspaceId),
        modules =
          modules.map { module ->
            PreviewLibrary.Module(
              path = module,
              previews =
                catalog[DaemonAddr(project.workspaceId, module)]
                  ?.values
                  ?.sortedBy { it.fqn }
                  ?.map { entry ->
                    PreviewLibrary.Preview(
                      uri =
                        PreviewUri(project.workspaceId, module, entry.fqn, entry.config).toUri(),
                      name = entry.fqn.substringAfterLast('.'),
                      displayName = entry.displayName,
                      sourceFile = entry.resolvedSourcePath ?: entry.sourceFile,
                      sourceLine = entry.bodyLine,
                    )
                  }
                  .orEmpty(),
            )
          },
      )
    }
  }

  /**
   * True when `render_preview` defaults to the file result: a known file-reading agent harness
   * ([FILE_RESULT_CLIENT_NAMES]) that asked for neither an observation nor a crop. Explicit
   * `inline` wins.
   */
  private fun defaultsToFileResult(session: Session?, args: JsonObject): Boolean =
    (session as? McpSession)?.clientName in FILE_RESULT_CLIENT_NAMES &&
      args["observe"] == null &&
      args["crop"] == null

  private fun toolRenderPreview(
    session: Session,
    args: JsonObject,
    preResolved: PreviewNameResolution? = null,
  ): CallToolResult {
    val previewName = args["preview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    var otherMatches = emptyList<String>()
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: when (val resolved = preResolved ?: previewName?.let { resolvePreviewName(it) }) {
          null -> return errorCallToolResult("render_preview: missing 'uri' or 'preview'")
          is PreviewNameResolution.Missing ->
            return errorCallToolResult("render_preview: ${resolved.message}", resolved.structured)
          is PreviewNameResolution.Found -> {
            otherMatches = resolved.others
            resolved.uri
          }
        }
    val uri = PreviewUri.parseOrNull(uriStr) ?: return errorCallToolResult("invalid uri: $uriStr")
    // An MCP Apps client shows the result in the viewer, which needs the pixels; everyone else
    // gets the token-frugal semantics observation by default.
    val observe =
      args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?: if ((session as? McpSession)?.supportsMcpApps == true) "png" else "semantics"
    if (observe !in setOf("png", "semantics", "hash")) {
      return errorCallToolResult("render_preview: 'observe' must be one of png | semantics | hash")
    }
    val fullScale =
      when (val scale = args["imageScale"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
        null,
        "default" -> false
        "full" -> true
        else ->
          return errorCallToolResult(
            "render_preview: 'imageScale' must be default | full (got '$scale')"
          )
      }
    val cropArg =
      args["crop"]?.let {
        it as? JsonObject
          ?: return errorCallToolResult(
            "render_preview: 'crop' must be an object (a target {ref|testTag|role/text} or " +
              "bounds {left,top,right,bottom})"
          )
      }
    // File-reading harnesses default to the file result; Antigravity also gets a card built from
    // the on-disk PNG. An inline observation, crop or explicit `inline` keeps the inline result.
    val antigravity = (session as? McpSession)?.clientName == ANTIGRAVITY_CLIENT_NAME
    val cardArg = args["card"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    val inline =
      args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: when {
          cardArg == true -> false
          defaultsToFileResult(session, args) -> false
          else -> true
        }
    val card = cardArg ?: (antigravity && !inline)
    if (card && inline) {
      return errorCallToolResult("render_preview: 'card' needs the file result (inline=false)")
    }
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("render_preview: invalid overrides: ${e.message}")
          }
      }
    val forceReason =
      args["force"]?.let { force ->
        val reason =
          (force as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.takeIf {
            it.isNotBlank()
          }
            ?: return errorCallToolResult(
              "render_preview: 'force' requires a non-empty 'reason' string"
            )
        reason
      }
    if (overrides != null) {
      val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("render_preview: ${violations.joinToString("; ")}")
      }
    }
    if (!inline && cropArg != null) {
      return errorCallToolResult("render_preview: 'inline=false' cannot be combined with 'crop'")
    }
    val details =
      when (val raw = args["details"]) {
        null,
        JsonNull -> emptySet()
        is JsonArray ->
          raw
            .map { element ->
              (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(RenderDetail::parse)
                ?: return errorCallToolResult(
                  "render_preview: 'details' entries must be \"a11y\" or \"layout\""
                )
            }
            .toSet()
        else -> return errorCallToolResult("render_preview: 'details' must be an array")
      }
    if (forceReason != null) invalidateClasspathForForce(uri, forceReason)
    return runCatching {
      if (!inline) {
        renderPreviewFile(
          session,
          uri,
          overrides,
          resourceUri =
            uri.copy(overridesJson = (args["overrides"] as? JsonObject)?.toString()).toUri(),
          card = card,
          otherMatches = otherMatches,
          details = details,
        )
      } else if (cropArg != null) {
        renderCropped(uri, overrides, cropArg, observe)
      } else {
        val bytes = renderAndReadBytes(uri, overrides = overrides)
        if (observe == "png") {
          val inlineBytes = if (fullScale) bytes else scaleToMaxEdge(bytes, INLINE_MAX_EDGE_PX)
          val sizes =
            if (inlineBytes === bytes) null
            else {
              val full = pngDimensions(bytes)
              val shown = pngDimensions(inlineBytes)
              buildJsonObject {
                put("uri", uri.toUri())
                full?.let {
                  put("widthPx", it.first)
                  put("heightPx", it.second)
                }
                shown?.let {
                  put("inlineWidthPx", it.first)
                  put("inlineHeightPx", it.second)
                }
                put(
                  "note",
                  "inline image downscaled; pass imageScale=\"full\" only for pixel-level checks",
                )
              }
            }
          CallToolResult(
            content =
              listOfNotNull(
                ContentBlock.Image(Base64.getEncoder().encodeToString(inlineBytes), "image/png"),
                sizes?.let { ContentBlock.Text(it.toString()) },
                ContentBlock.ResourceLink(
                  uri =
                    uri
                      .copy(overridesJson = (args["overrides"] as? JsonObject)?.toString())
                      .toUri(),
                  name = "Compose Preview render",
                  mimeType = "image/png",
                  description =
                    "The current preview resource; subscribe to refresh it after edits.",
                ),
              )
          )
        } else {
          renderObservation(
            uri,
            bytes,
            includeSemantics = observe == "semantics",
            resourceUri =
              uri.copy(overridesJson = (args["overrides"] as? JsonObject)?.toString()).toUri(),
          )
        }
      }
    }
      .map { result ->
        if (!inline || otherMatches.isEmpty() || result.isError == true) result
        else
          result.copy(
            content =
              result.content +
                ContentBlock.Text(
                  buildJsonObject {
                    putJsonArray("otherMatches") { otherMatches.forEach { add(JsonPrimitive(it)) } }
                  }
                    .toString()
                )
          )
      }
      .map { result ->
        // The file path carries its own details (they also go into the card).
        if (!inline || details.isEmpty() || result.isError == true) result
        else {
          val fetched = fetchRenderDetails(uri, details)
          result.copy(
            content = result.content + ContentBlock.Text(fetched.summaries.joinToString("\n"))
          )
        }
      }
      .map { result ->
        // Never hand back an old image as if it were current.
        val stale = staleRenderLine(uri)
        if (stale == null || result.isError == true) result
        else result.copy(content = result.content + ContentBlock.Text(stale))
      }
      .map { result ->
        // What this edit→render cycle did, in `_meta` so it stays out of the agent-readable
        // content.
        val work = lastCycleWork[PreviewIdKey(uri.workspaceId, uri.modulePath, uri.previewFqn)]
        if (work == null || result.isError == true) result
        else result.copy(meta = buildJsonObject { put("work", work.toJson()) })
      }
      .getOrElse { errorCallToolResult("render_preview failed: ${it.message}") }
  }

  /**
   * Local-file variant of `render_preview`: leaves the daemon's PNG untouched (no downscaling) and
   * reports a path the client can read after this call returns.
   */
  private fun renderPreviewFile(
    session: Session,
    uri: PreviewUri,
    overrides: PreviewOverrides?,
    resourceUri: String,
    card: Boolean = false,
    otherMatches: List<String> = emptyList(),
    details: Set<RenderDetail> = emptySet(),
  ): CallToolResult {
    val startedAt = System.nanoTime()
    val outcome = awaitNextRender(uri, session, overrides = overrides)
    val fetchedDetails = fetchRenderDetails(uri, details)
    val pngBytes = outcome.pngBytes
    val sha = sha256Hex(pngBytes)
    val stablePng = cacheRenderedPng(pngBytes, sha)
    val changed = changedSinceLastRender(session, uri, overrides, sha)
    val payload = buildJsonObject {
      put("uri", uri.toUri())
      put("pngPath", stablePng.canonicalPath)
      pngDimensions(pngBytes)?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      put("sha256", sha)
      put("changed", changed)
      put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
      // In the payload, not only as text blocks after it: a client that reads just this object
      // otherwise never learns the image is stale or what the details found (#64, #76).
      staleRenderLine(uri)?.let { put("stale", it) }
      if (fetchedDetails.summaries.isNotEmpty())
        putJsonArray("details") { fetchedDetails.summaries.forEach { add(JsonPrimitive(it)) } }
      if (otherMatches.isNotEmpty())
        putJsonArray("otherMatches") { otherMatches.forEach { add(JsonPrimitive(it)) } }
      if (card) {
        when (val written = writePreviewCard(uri, pngBytes, sha, stablePng, fetchedDetails)) {
          is PreviewCard.Written -> {
            put("cardPath", written.file.canonicalPath)
            written.detailsDropped?.let { put("cardDetailsDropped", it) }
            put("embed", "<agent-embed src=\"${written.file.toPath().toUri()}\"></agent-embed>")
          }
          is PreviewCard.Skipped -> put("cardSkipped", written.reason)
        }
      }
    }
    // The link lets an MCP App host show the render even though no bytes are inline; text-only
    // clients keep the local path above.
    return CallToolResult(
      content =
        listOfNotNull(
          ContentBlock.Text(payload.toString()),
          fetchedDetails.summaries
            .takeIf { it.isNotEmpty() }
            ?.let { ContentBlock.Text(it.joinToString("\n")) },
          ContentBlock.ResourceLink(
            uri = resourceUri,
            name = "Compose Preview render",
            mimeType = "image/png",
            description = "The current preview resource; subscribe to refresh it after edits.",
          ),
        )
    )
  }

  /**
   * Copies a daemon-owned render into an immutable, content-addressed process cache: daemons may
   * reuse one output path per preview, so a later render could replace the bytes before the caller
   * reads them.
   */
  private fun cacheRenderedPng(pngBytes: ByteArray, sha: String): File =
    synchronized(fileRenderCacheLock) {
      val target = File(fileRenderCacheDir, "$sha.png")
      if (!target.isFile || runCatching { sha256Hex(target) }.getOrNull() != sha) {
        val temporary = Files.createTempFile(fileRenderCacheDir.toPath(), "$sha-", ".tmp")
        try {
          Files.write(temporary, pngBytes)
          try {
            Files.move(
              temporary,
              target.toPath(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
            )
          } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
          }
        } finally {
          Files.deleteIfExists(temporary)
        }
      }
      target.setLastModified(System.currentTimeMillis())
      fileRenderCacheDir
        .listFiles { file -> file.extension == "png" }
        .orEmpty()
        .filterNot { it == target }
        .sortedByDescending(File::lastModified)
        .drop(MAX_CACHED_FILE_RENDERS - 1)
        .forEach { stale -> runCatching { stale.delete() } }
      target
    }

  /** The catalog as [PreviewTray] and [PreviewMentions] read it; empty until discovery lands. */
  private fun previewCatalog(): List<CatalogPreview> = catalog.flatMap { (addr, byId) ->
    byId.values.map { entry ->
      CatalogPreview(
        uri = PreviewUri(addr.workspaceId, addr.modulePath, entry.fqn, entry.config).toUri(),
        fqn = entry.fqn,
        functionName = entry.functionName,
        displayName = entry.displayName,
        modulePath = addr.modulePath,
        sourceFile = entry.resolvedSourcePath,
        sourceLine = entry.bodyLine,
      )
    }
  }

  private fun toolFindPreviewsForFile(args: JsonObject): CallToolResult {
    val requestedPath =
      args["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return errorCallToolResult("find_previews_for_file: missing 'path'")
    val requestedWorkspaceId = args["workspaceId"]?.jsonPrimitive?.contentOrNull
    val projects =
      if (requestedWorkspaceId == null) {
        supervisor.listProjects()
      } else {
        listOf(
          supervisor.project(WorkspaceId(requestedWorkspaceId))
            ?: return errorCallToolResult(
              "find_previews_for_file: workspace '$requestedWorkspaceId' not registered"
            )
        )
      }
    val requestedFile = File(requestedPath)
    val targetByWorkspace = projects.associate { project ->
      val target =
        if (requestedFile.isAbsolute) requestedFile else File(project.path, requestedPath)
      project.workspaceId to target.canonicalPath
    }
    val previews = mutableListOf<JsonObject>()
    for ((addr, byId) in catalog) {
      val targetPath = targetByWorkspace[addr.workspaceId] ?: continue
      for (entry in byId.values) {
        val uri =
          PreviewUri(
            workspaceId = addr.workspaceId,
            modulePath = addr.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        val sourcePath = entry.resolvedSourcePath ?: continue
        if (sourcePath != targetPath) continue
        previews += buildJsonObject {
          put("uri", uri.toUri())
          put("fqn", entry.fqn)
          entry.displayName?.let { put("displayName", it) }
          entry.bodyLine?.let { put("bodyLine", it) }
        }
      }
    }
    val payload = buildJsonObject {
      putJsonArray("previews") {
        previews.sortedBy { it["uri"]?.jsonPrimitive?.contentOrNull }.forEach(::add)
      }
    }
    return textCallToolResult(payload.toString())
  }

  /**
   * `render_preview.crop` (issue #1817) — render the full preview, then return only the rectangle
   * of a single element. The crop is resolved either from explicit `{left,top,right,bottom}` render
   * pixels or from a semantic target (`ref` / `testTag` / `role`+`text`) resolved against the
   * preview's `compose/semantics` tree — the same vocabulary as targeting and `diff_semantics`. Far
   * fewer tokens than a full-frame PNG, and it focuses the agent's eyes on the region the semantics
   * already flagged: the natural partner to `diff_semantics` ("ref X changed" → crop just ref X).
   *
   * Crop runs entirely in this agent-facing layer: it operates on the rendered PNG bytes plus the
   * `compose/semantics` product (both backends already emit), so the daemon's cached full-frame
   * artifact is untouched and Android/Desktop behave identically. Because `boundsInRoot` is in the
   * same root-pixel space as the rendered image, the crop is applied to the **full-resolution**
   * bytes *before* the host image-size cap, then the cap is re-applied to the (small) result.
   */
  private fun renderCropped(
    uri: PreviewUri,
    overrides: PreviewOverrides?,
    crop: JsonObject,
    observe: String,
  ): CallToolResult {
    val boundKeys = listOf("left", "top", "right", "bottom")
    val presentBounds = boundKeys.filter { crop[it] != null }
    val target = cropTargetOf(crop)
    if (presentBounds.isEmpty() && target == null) {
      return errorCallToolResult(
        "render_preview: 'crop' must set a target (ref | testTag | role/text) or explicit bounds " +
          "{left,top,right,bottom}"
      )
    }
    if (presentBounds.isNotEmpty() && presentBounds.size != 4) {
      return errorCallToolResult(
        "render_preview: 'crop' bounds need all of left,top,right,bottom (got " +
          "${presentBounds.joinToString(",")})"
      )
    }

    val rawBytes = renderAndReadRawBytes(uri, overrides)
    val dims =
      pngDimensions(rawBytes)
        ?: return errorCallToolResult("render_preview: could not read rendered PNG dimensions")
    val (imgW, imgH) = dims

    var node: ComposeSemanticsNode? = null
    val bounds: SemanticsBounds
    if (presentBounds.size == 4) {
      val ints = boundKeys.map { key ->
        crop[key]!!.jsonPrimitive.intOrNull
          ?: return errorCallToolResult("render_preview: 'crop.$key' must be an integer")
      }
      bounds = SemanticsBounds(ints[0], ints[1], ints[2], ints[3])
    } else {
      val (payload, err) = fetchSemanticsPayload(uri.toUri(), "crop")
      if (payload == null) {
        return errorCallToolResult("render_preview: crop by target needs compose/semantics — $err")
      }
      when (val res = SemanticsTargets.resolve(payload.root, target!!)) {
        is TargetResolution.Resolved -> {
          node = res.node
          bounds =
            SemanticsBounds.parse(res.node.boundsInRoot)
              ?: return errorCallToolResult(
                "render_preview: matched node '${res.node.ref}' has unparseable bounds " +
                  "'${res.node.boundsInRoot}'"
              )
        }
        TargetResolution.NotFound ->
          return errorCallToolResult(
            "render_preview: crop target matched no node in compose/semantics"
          )
        is TargetResolution.Ambiguous ->
          return errorCallToolResult(
            "render_preview: crop target matched ${res.candidates.size} nodes — pass a unique " +
              "'ref' to disambiguate (candidates: " +
              res.candidates.take(6).joinToString(", ") { it.ref ?: it.nodeId } +
              (if (res.candidates.size > 6) ", …" else "") +
              ")"
          )
      }
    }

    // Clamp the (possibly off-frame) bounds to the rendered image and reject an empty region.
    val left = bounds.left.coerceIn(0, imgW)
    val top = bounds.top.coerceIn(0, imgH)
    val right = bounds.right.coerceIn(left, imgW)
    val bottom = bounds.bottom.coerceIn(top, imgH)
    if (right - left <= 0 || bottom - top <= 0) {
      return errorCallToolResult(
        "render_preview: crop region ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}" +
          " is empty after clamping to ${imgW}x${imgH}"
      )
    }

    val croppedRaw =
      cropPng(rawBytes, left, top, right - left, bottom - top)
        ?: return errorCallToolResult("render_preview: failed to crop the rendered PNG")
    // Re-apply the host image-size cap to the (small) crop so the same downscale contract holds.
    val cropped = applyImageSizeOverride(croppedRaw)

    return cropResult(uri, cropped, observe, intArrayOf(left, top, right, bottom), imgW, imgH, node)
  }

  /** Map a `crop` JSON object onto a [SemanticsTarget]; null when no target field is set. */
  private fun cropTargetOf(crop: JsonObject): SemanticsTarget? {
    fun str(key: String) = crop[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val ref = str("ref")
    val tag = str("testTag")
    val role = str("role")
    val text = str("text")
    return when {
      ref != null -> SemanticsTarget.Ref(ref)
      tag != null -> SemanticsTarget.Tag(tag)
      role != null || text != null -> SemanticsTarget.RoleText(role, text)
      else -> null
    }
  }

  /**
   * Crop a PNG to `(x, y, w, h)` (already clamped). `getSubimage` shares the parent raster, so copy
   * into a standalone buffer before encoding.
   */
  private fun cropPng(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray? {
    val src = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
    val cx = x.coerceIn(0, maxOf(0, src.width - 1))
    val cy = y.coerceIn(0, maxOf(0, src.height - 1))
    val cw = w.coerceIn(1, src.width - cx)
    val ch = h.coerceIn(1, src.height - cy)
    val copy = java.awt.image.BufferedImage(cw, ch, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = copy.createGraphics()
    try {
      g.drawImage(src.getSubimage(cx, cy, cw, ch), 0, 0, null)
    } finally {
      g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(copy, "png", out)
    return out.toByteArray()
  }

  /**
   * Build the `crop` response. `observe="png"` returns the cropped image plus a small metadata
   * block (resolved region, ref, source dimensions, sha); `observe="hash"`/`"semantics"` returns
   * just the metadata (a region-scoped change signal, no base64), and `semantics` additionally
   * carries the matched node's own semantics subtree when the crop resolved a target.
   */
  private fun cropResult(
    uri: PreviewUri,
    croppedBytes: ByteArray,
    observe: String,
    region: IntArray,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    node: ComposeSemanticsNode?,
  ): CallToolResult {
    val dims = pngDimensions(croppedBytes)
    val meta = buildJsonObject {
      put("uri", uri.toUri())
      putJsonObject("crop") {
        put("left", region[0])
        put("top", region[1])
        put("right", region[2])
        put("bottom", region[3])
      }
      node?.ref?.let { put("ref", it) }
      put("sourceWidthPx", sourceWidthPx)
      put("sourceHeightPx", sourceHeightPx)
      put("sha256", sha256Hex(croppedBytes))
      put("sizeBytes", croppedBytes.size)
      dims?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      if (observe == "semantics" && node != null) {
        put("semantics", json.encodeToJsonElement(ComposeSemanticsNode.serializer(), node))
      }
    }
    val blocks = buildList {
      if (observe == "png") {
        add(
          ContentBlock.Image(
            data = Base64.getEncoder().encodeToString(croppedBytes),
            mimeType = "image/png",
          )
        )
      }
      add(ContentBlock.Text(meta.toString()))
    }
    return CallToolResult(content = blocks)
  }

  /**
   * `render_matrix`: render one preview across a cross-product of display axes (device × locale ×
   * uiMode × fontScale) and return a compact per-cell summary (overrides, label, sha256,
   * dimensions, `changed` vs the first cell). No base64 by default; `contactSheet:true` adds one
   * stitched grid. Bounded in size.
   */
  private suspend fun toolRenderMatrix(
    session: Session,
    args: JsonObject,
    scope: Set<WorkspaceId>? = null,
    progress: (String) -> Unit = {},
  ): CallToolResult {
    val uriArg = args["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val previewArg = args["preview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val variantUris =
      when (val resolved = matrixVariants(uriArg, previewArg, scope, progress)) {
        is PreviewNameResolution.Missing ->
          return errorCallToolResult("render_matrix: ${resolved.message}", resolved.structured)
        is PreviewNameResolution.Found -> listOf(resolved.uri) + resolved.others
      }
    val axes = (args["axes"] as? JsonObject)?.takeIf { it.isNotEmpty() }
    val choose = args["choose"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    if (axes == null) return renderVariantMatrix(session, variantUris, args, choose)
    val uri = PreviewUri.parseOrNull(variantUris.first())!!
    val contactSheet =
      args["contactSheet"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

    fun stringAxis(key: String): List<String>? =
      (axes[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        ?.takeIf { it.isNotEmpty() }
    fun floatAxis(key: String): List<Float>? =
      (axes[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toFloatOrNull() }
        ?.takeIf { it.isNotEmpty() }

    val devices = stringAxis("device")
    val locales = stringAxis("locale")
    val uiModes = stringAxis("uiMode")
    val fontScales = floatAxis("fontScale")
    if (devices == null && locales == null && uiModes == null && fontScales == null) {
      return errorCallToolResult(
        "render_matrix: 'axes' must set at least one of device | locale | uiMode | fontScale " +
          "(each a non-empty array)"
      )
    }
    val cellCount = MatrixAxes.cellCount(devices, locales, uiModes, fontScales)
    if (cellCount > MatrixAxes.CELL_CAP) {
      return errorCallToolResult(
        "render_matrix: $cellCount cells exceeds the cap of ${MatrixAxes.CELL_CAP}; narrow the axes"
      )
    }

    // One cell per axis combination, in stable device → locale → uiMode → fontScale order.
    val matrixCells = MatrixAxes.expand(devices, locales, uiModes, fontScales)

    // Decode + validate EVERY cell before rendering — validateOverrides also checks the `device`
    // catalog id, which varies per cell, so a typo in any cell (not just the first) must be caught.
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("render_matrix: daemon spawn failed: ${it.message}")
      }
    val decodedCells = matrixCells.map { cell ->
      val overrides = runCatching {
        cell.toOverrides()
      }
        .getOrElse {
          return errorCallToolResult("render_matrix: invalid axis values: ${it.message}")
        }
      cell to overrides
    }
    val violations = decodedCells.flatMap { (_, overrides) -> validateOverrides(overrides, daemon) }
    if (violations.isNotEmpty()) {
      return errorCallToolResult("render_matrix: ${violations.distinct().joinToString("; ")}")
    }

    return try {
      var baselineSha: String? = null
      // Render every cell, keeping the bytes around so an optional contact sheet can stitch them.
      val rendered = decodedCells.map { (cell, overrides) ->
        val bytes = renderAndReadBytes(uri, overrides = overrides)
        val sha = sha256Hex(bytes)
        if (baselineSha == null) baselineSha = sha
        RenderedCell(cell, overrides, bytes, sha, pngDimensions(bytes))
      }
      val cells = rendered.map { rc ->
        buildJsonObject {
          put("overrides", rc.cell.overridesJson())
          put("label", rc.cell.label)
          put("sha256", rc.sha)
          rc.dimensions?.let {
            put("widthPx", it.first)
            put("heightPx", it.second)
          }
          put("changed", rc.sha != baselineSha)
          put("changedSinceLastRender", changedSinceLastRender(session, uri, rc.overrides, rc.sha))
        }
      }
      val payload = buildJsonObject {
        put("schema", "compose-preview-matrix/v1")
        put("uri", uri.toUri())
        if (variantUris.size > 1) {
          // A multipreview name: the axes apply to one variant; say which, and name the rest.
          put("variant", variantLabel(uri))
          putJsonArray("otherVariants") { variantUris.drop(1).forEach { add(JsonPrimitive(it)) } }
        }
        put("cellCount", cells.size)
        if (contactSheet) put("contactSheet", true)
        putJsonArray("cells") { cells.forEach { add(it) } }
        if (choose) {
          put(
            "selection",
            matrixSelection(session, cells) {
              rendered.map { rc ->
                PreviewPickers.option(
                  uri.copy(overridesJson = rc.cell.overridesJson().toString()).toUri(),
                  rc.cell.label,
                  thumbnailPngBase64 = pickerThumbnail(rc.bytes),
                )
              }
            },
          )
        }
      }
      val blocks = buildList {
        if (contactSheet) {
          val sheet =
            ContactSheet.stitch(
              rendered.map {
                ContactSheet.Cell(
                  it.cell.label,
                  scaleToMaxEdge(it.bytes, CONTACT_SHEET_CELL_EDGE_PX),
                )
              }
            )
          if (sheet != null) {
            add(
              ContentBlock.Image(
                data = Base64.getEncoder().encodeToString(sheet),
                mimeType = "image/png",
              )
            )
          }
        }
        add(ContentBlock.Text(payload.toString()))
      }
      CallToolResult(content = blocks)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      matrixFailure(uri, failure)
    }
  }

  /**
   * The previews a `render_matrix` call covers: `preview=<name>` resolves like `render_preview`; a
   * `uri` naming a multipreview function's bare id (not in the manifest) resolves to its variants.
   */
  private fun matrixVariants(
    uriArg: String?,
    previewArg: String?,
    scope: Set<WorkspaceId>? = null,
    progress: (String) -> Unit = {},
  ): PreviewNameResolution {
    if (uriArg == null) {
      return previewArg?.let { resolvePreviewName(it, scope, progress) }
        ?: PreviewNameResolution.Missing("missing 'uri' or 'preview'")
    }
    val uri =
      PreviewUri.parseOrNull(uriArg) ?: return PreviewNameResolution.Missing("invalid uri: $uriArg")
    val byId = catalog[DaemonAddr(uri.workspaceId, uri.modulePath)]
    if (byId.isNullOrEmpty() || byId.values.any { it.fqn == uri.previewFqn }) {
      return PreviewNameResolution.Found(uriArg, emptyList())
    }
    val variants =
      previewNameMatches(uri.previewFqn).filter { candidate ->
        PreviewUri.parseOrNull(candidate)?.let {
          it.workspaceId == uri.workspaceId && it.modulePath == uri.modulePath
        } == true
      }
    return if (variants.isEmpty()) noPreviewMatches(uri.previewFqn)
    else PreviewNameResolution.Found(variants.first(), variants.drop(1))
  }

  /** A variant's short name: `ListScreenPreview_Devices - Large Round`, plus its config if any. */
  private fun variantLabel(uri: PreviewUri): String {
    val id = uri.previewFqn.substringAfterLast('.')
    return if (uri.config == null) id else "$id (${uri.config})"
  }

  /**
   * `render_matrix` with no `axes`: one cell per `@Preview` variant of the name, capped at
   * [MAX_VARIANT_CELLS], with one labelled contact sheet.
   */
  private suspend fun renderVariantMatrix(
    session: Session,
    variantUris: List<String>,
    args: JsonObject,
    choose: Boolean,
    viewerCells: Boolean = true,
  ): CallToolResult {
    val contactSheet =
      args["contactSheet"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val rendered = variantUris.take(MAX_VARIANT_CELLS).map { PreviewUri.parseOrNull(it)!! }
    val first = rendered.first()
    return try {
      // Concurrently: variants are different previews, so the daemon spreads them over its sandbox
      // pool, and concurrent requests are what boot deferred pool workers
      // (DaemonSupervisor.ON_DEMAND_WORKER_BOOT_PROP).
      val cells = coroutineScope {
        rendered
          .map { variant ->
            async(Dispatchers.IO) {
              val bytes = renderAndReadBytes(variant)
              Triple(variant, bytes, sha256Hex(bytes))
            }
          }
          .awaitAll()
      }
      val baselineSha = cells.first().third
      val cellJson = cells.map { (variant, bytes, sha) ->
        buildJsonObject {
          put("uri", variant.toUri())
          put("label", variantLabel(variant))
          put("sha256", sha)
          pngDimensions(bytes)?.let {
            put("widthPx", it.first)
            put("heightPx", it.second)
          }
          put("changed", sha != baselineSha)
          put("changedSinceLastRender", changedSinceLastRender(session, variant, null, sha))
        }
      }
      val payload = buildJsonObject {
        put("schema", "compose-preview-matrix/v1")
        put("mode", "variants")
        put("uri", first.toUri())
        put("cellCount", cellJson.size)
        if (contactSheet) put("contactSheet", true)
        putJsonArray("cells") { cellJson.forEach { add(it) } }
        if (variantUris.size > MAX_VARIANT_CELLS) {
          putJsonArray("notRendered") {
            variantUris.drop(MAX_VARIANT_CELLS).forEach { add(JsonPrimitive(it)) }
          }
        }
        if (choose) {
          put(
            "selection",
            matrixSelection(session, cellJson) {
              cells.map { (variant, bytes, _) ->
                PreviewPickers.option(
                  variant.toUri(),
                  variantLabel(variant),
                  thumbnailPngBase64 = pickerThumbnail(bytes),
                )
              }
            },
          )
        }
      }
      val blocks = buildList {
        if (contactSheet) {
          ContactSheet.stitch(
              cells.map { (variant, bytes, _) ->
                ContactSheet.Cell(
                  variantLabel(variant),
                  scaleToMaxEdge(bytes, CONTACT_SHEET_CELL_EDGE_PX),
                )
              }
            )
            ?.let { add(ContentBlock.Image(Base64.getEncoder().encodeToString(it), "image/png")) }
        }
        add(ContentBlock.Text(payload.toString()))
      }
      // Each cell's pixels go to the viewer in `_meta`, which the model does not read: it gets
      // the one contact sheet.
      val meta =
        if (!viewerCells) null
        else
          buildJsonObject {
            putJsonArray("composePreview/cellPngs") {
              cells.forEach { (_, bytes, _) ->
                add(
                  JsonPrimitive(
                    Base64.getEncoder().encodeToString(scaleToMaxEdge(bytes, INLINE_MAX_EDGE_PX))
                  )
                )
              }
            }
          }
      CallToolResult(content = blocks, meta = meta)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      matrixFailure(first, failure)
    }
  }

  /**
   * A render failure, with the daemon's missing-manifest-entry error (e.g. a multipreview bare id)
   * mapped to the variants that do exist.
   */
  private fun matrixFailure(uri: PreviewUri, failure: Throwable): CallToolResult {
    val message = failure.message.orEmpty()
    if ("no manifest entry" in message || "PreviewManifestRouter" in message) {
      val variants = previewNameMatches(uri.previewFqn)
      val hint =
        if (variants.isEmpty()) ""
        else
          "; did you mean ${variants.take(5).joinToString(", ") { variantLabel(PreviewUri.parseOrNull(it)!!) }}"
      return errorCallToolResult("render_matrix: no preview ${uri.previewFqn}$hint")
    }
    return errorCallToolResult("render_matrix failed: $message")
  }

  /** A matrix cell's pixels as a picker thumbnail. */
  private fun pickerThumbnail(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(scaleToMaxEdge(bytes, PreviewPickers.THUMBNAIL_EDGE_PX))

  /**
   * Form chooser for a completed matrix, with an equivalent text answer for older harnesses.
   * OpenAI-forms clients pick from [pickerOptions], one per cell.
   */
  private suspend fun matrixSelection(
    session: Session,
    cells: List<JsonObject>,
    pickerOptions: () -> List<OpenAiForms.ResourceOption>,
  ): JsonObject {
    val choices = cells.map { it["label"]!!.jsonPrimitive.content }
    PreviewPickers.matrixSelection(session, choices, pickerOptions)?.let {
      return it
    }
    val fallback = buildJsonObject {
      put("mode", "text")
      put("message", "Choose one rendered variant by label: ${choices.joinToString(" | ")}")
      putJsonArray("choices") { choices.forEach { add(JsonPrimitive(it)) } }
    }
    val elicitation =
      (session as? McpSession)?.elicitForm(
        message = "Choose the rendered variant to use. Each label names its display overrides.",
        requestedSchema =
          buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
              put(
                "variant",
                buildJsonObject {
                  put("type", "string")
                  putJsonArray("enum") { choices.forEach { add(JsonPrimitive(it)) } }
                },
              )
            }
            putJsonArray("required") { add(JsonPrimitive("variant")) }
          },
      ) ?: FormElicitation.Unsupported
    val result =
      when (elicitation) {
        FormElicitation.Unsupported -> return fallback
        FormElicitation.TimedOut ->
          return buildJsonObject {
            put("mode", "timeout")
            put(
              "message",
              "The variant chooser was not answered in time. Do not re-open it or ask again " +
                "unprompted; report the labelled choices and let the user pick when they return.",
            )
            putJsonArray("choices") { choices.forEach { add(JsonPrimitive(it)) } }
          }
        is FormElicitation.Answered -> elicitation.result
      }
    when (result.action) {
      ElicitResult.Action.Decline ->
        return buildJsonObject {
          put("mode", "declined")
          put(
            "message",
            "The user declined to choose a rendered variant. Respect that: do not ask again " +
              "for a choice, in a form or in chat, unless the user brings it up.",
          )
        }
      ElicitResult.Action.Cancel ->
        return buildJsonObject {
          put("mode", "cancelled")
          put(
            "message",
            "The user cancelled variant selection. Do not re-ask for a choice unless the user " +
              "asks to pick one.",
          )
        }
      ElicitResult.Action.Accept -> Unit
    }
    val variant =
      (result.content?.get("variant") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeIf { it in choices } ?: return fallback
    return buildJsonObject {
      put("mode", "elicitation")
      put("variant", variant)
    }
  }

  /** A rendered matrix cell held in memory so the optional contact sheet can stitch the bytes. */
  /**
   * Whether [sha] differs from the last render of [uri] with [overrides] this [session] saw,
   * recording it. A cell's `changed` compares with the first cell of the call, so every cell also
   * carries this "since my last render" answer.
   */
  private fun changedSinceLastRender(
    session: Session,
    uri: PreviewUri,
    overrides: PreviewOverrides?,
    sha: String,
  ): Boolean =
    previousFileRenderHashes
      .computeIfAbsent(session) { ConcurrentHashMap() }
      .put(FileRenderKey(uri.toUri(), overrides), sha) != sha

  private class RenderedCell(
    val cell: MatrixCell,
    val overrides: PreviewOverrides?,
    val bytes: ByteArray,
    val sha: String,
    val dimensions: Pair<Int, Int>?,
  )

  /**
   * Compact `render_preview` response: sha256 + pixel dimensions and, for `observe="semantics"`,
   * the compose/semantics tree, instead of a base64 PNG (like Playwright's snapshot-by-default
   * split).
   */
  private fun renderObservation(
    uri: PreviewUri,
    pngBytes: ByteArray,
    includeSemantics: Boolean,
    resourceUri: String,
  ): CallToolResult {
    val dimensions = pngDimensions(pngBytes)
    var imageFallback = false
    val payload = buildJsonObject {
      put("observe", if (includeSemantics) "semantics" else "hash")
      put("uri", uri.toUri())
      put("sha256", sha256Hex(pngBytes))
      put("sizeBytes", pngBytes.size)
      dimensions?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      if (includeSemantics) {
        val (semantics, error) = fetchSemanticsPayload(uri.toUri(), "preview")
        if (semantics != null) {
          put(
            "semantics",
            json.encodeToJsonElement(ComposeSemanticsPayload.serializer(), semantics),
          )
        } else {
          val reason = error ?: "compose/semantics not available for this preview"
          put("semanticsUnavailable", reason)
          // Never answer with neither semantics nor pixels: show the image and say how to get the
          // default observation back.
          put("note", "semantics unavailable ($reason); showing the image instead")
          put("fix", SEMANTICS_UNAVAILABLE_FIX)
          imageFallback = true
        }
      }
    }
    return CallToolResult(
      content =
        listOfNotNull(
          ContentBlock.Text(payload.toString()),
          if (imageFallback) {
            ContentBlock.Image(
              Base64.getEncoder().encodeToString(scaleToMaxEdge(pngBytes, INLINE_MAX_EDGE_PX)),
              "image/png",
            )
          } else null,
          ContentBlock.ResourceLink(
            uri = resourceUri,
            name = "Compose Preview render",
            mimeType = "image/png",
            description = "The current preview resource; subscribe to refresh it after edits.",
          ),
        )
    )
  }

  /**
   * Parse a PNG's IHDR width/height (big-endian, at byte offsets 16/20) without decoding pixels.
   */
  private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    fun int32(offset: Int): Int =
      ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
    val width = int32(16)
    val height = int32(20)
    return if (width in 1..100_000 && height in 1..100_000) width to height else null
  }

  private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  /**
   * Sanctioned classpath invalidation for `render_preview.force`: forwards
   * `fileChanged({kind:"classpath"})` to every replica so `UserClassLoaderHolder` rotates before
   * the next render. Bumps `forces.used` and records the reason for `status`. Each use is a
   * freshness-logic gap; report on https://github.com/yschimke/compose-ai-tools/issues/924.
   */
  private fun invalidateClasspathForForce(uri: PreviewUri, reason: String) {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    // Use the catalogued source path when known (for daemon logs); the daemon only cares about
    // `kind`.
    val path =
      catalog[DaemonAddr(uri.workspaceId, uri.modulePath)]?.get(uri.previewFqn)?.sourceFile
        ?: "force-render://${uri.previewFqn}"
    daemon.allClients().forEach { client ->
      runCatching {
        client.fileChanged(path = path, kind = FileKind.CLASSPATH, changeType = ChangeType.MODIFIED)
      }
    }
    freshnessMetrics.recordForce(uri.toUri(), reason)
    System.err.println(
      "render_preview.force: ${uri.toUri()} reason='$reason' — please report on " +
        "https://github.com/yschimke/compose-ai-tools/issues/924"
    )
  }

  /**
   * Validates [overrides] against the daemon's advertised `supportedOverrides` and `knownDevices`,
   * returning human-readable violations (empty when fine). Falls open when either is empty (older
   * daemons), matching `ServerCapabilities`' contract. `spec:` device ids bypass the catalog check;
   * the daemon parses them.
   */
  private fun validateOverrides(
    overrides: PreviewOverrides,
    daemon: SupervisedDaemon,
  ): List<String> {
    val violations = mutableListOf<String>()
    val supported = daemon.supportedOverrides
    if (supported.isNotEmpty()) {
      // Each set field must be advertised, or the backend silently ignores it. The wording ("this
      // backend ignores it") points at dropping the field or using another daemon.
      fun check(name: String, set: Boolean) {
        if (set && name !in supported) {
          violations += "this backend does not apply '$name' overrides (supported: $supported)"
        }
      }
      check("widthPx", overrides.widthPx != null)
      check("heightPx", overrides.heightPx != null)
      check("density", overrides.density != null)
      check("localeTag", overrides.localeTag != null)
      check("fontScale", overrides.fontScale != null)
      check("uiMode", overrides.uiMode != null)
      check("orientation", overrides.orientation != null)
      check("device", overrides.device != null)
      check("captureAdvanceMs", overrides.captureAdvanceMs != null)
      check("inspectionMode", overrides.inspectionMode != null)
      check("material3Theme", overrides.material3Theme != null)
      // Override-extension fields: warn before a backend (e.g. desktop, which lacks the Robolectric
      // shadows) silently drops them.
      check("wallpaper", overrides.wallpaper != null)
      check("ambient", overrides.ambient != null)
      check("focus", overrides.focus != null)
      check("keyboard", overrides.keyboard != null)
      check("touchOverlay", overrides.touchOverlay != null)
      check("talkBack", overrides.talkBack != null)
      check("launcherWidget", overrides.launcherWidget != null)
      check("permissions", overrides.permissions != null)
      check("remoteCompose", overrides.remoteCompose != null)
    }
    val deviceOverride = overrides.device
    val knownIds = daemon.knownDeviceIds
    if (
      deviceOverride != null &&
        knownIds.isNotEmpty() &&
        !deviceOverride.startsWith("spec:") &&
        deviceOverride !in knownIds
    ) {
      violations +=
        "device='$deviceOverride' is not in the daemon's catalog; valid ids: " +
          knownIds.sorted().joinToString(", ") +
          " (list_devices has their sizes; or 'spec:width=…,height=…,dpi=…' for ad-hoc geometry)"
    }
    return violations
  }

  /**
   * Translates `render_preview.overrides` JSON into a typed [PreviewOverrides]. Only PROTOCOL.md §
   * 5 fields are read; unknown keys are ignored. Malformed primitives throw so the caller reports
   * "invalid overrides: …".
   */
  private fun decodePreviewOverrides(elem: JsonElement): PreviewOverrides {
    val obj = (elem as? JsonObject) ?: error("overrides must be an object")
    fun int(name: String): Int? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toInt()
    fun float(name: String): Float? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toFloat()
    fun str(name: String): String? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    fun bool(name: String): Boolean? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { it.toBooleanStrictOrNull() ?: error("$name must be true or false, got '$it'") }
    val uiMode =
      str("uiMode")?.let {
        when (it.lowercase()) {
          "light" -> UiMode.LIGHT
          "dark" -> UiMode.DARK
          else -> error("uiMode must be 'light' or 'dark', got '$it'")
        }
      }
    val orientation =
      str("orientation")?.let {
        when (it.lowercase()) {
          "portrait" -> Orientation.PORTRAIT
          "landscape" -> Orientation.LANDSCAPE
          else -> error("orientation must be 'portrait' or 'landscape', got '$it'")
        }
      }
    // Override-extension fields driving the connector-side around-composable hooks (focus,
    // keyboard, permissions, RemoteCompose, wallpaper, ambient, launcher-widget) and the touch
    // overlay. Each is decoded from its `@Serializable` wire shape; a malformed object throws.
    fun <T> nested(
      name: String,
      deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.let { json.decodeFromJsonElement(deserializer, it) }
    return PreviewOverrides(
      widthPx = int("widthPx"),
      heightPx = int("heightPx"),
      density = float("density"),
      localeTag = str("localeTag"),
      fontScale = float("fontScale"),
      uiMode = uiMode,
      orientation = orientation,
      device = str("device"),
      captureAdvanceMs = int("captureAdvanceMs")?.toLong(),
      inspectionMode = bool("inspectionMode"),
      material3Theme = nested("material3Theme", Material3ThemeOverrides.serializer()),
      wallpaper = nested("wallpaper", WallpaperOverride.serializer()),
      ambient = nested("ambient", AmbientOverride.serializer()),
      focus = nested("focus", FocusOverride.serializer()),
      keyboard = nested("keyboard", KeyboardOverride.serializer()),
      touchOverlay = bool("touchOverlay"),
      talkBack = bool("talkBack"),
      permissions = nested("permissions", PermissionsOverride.serializer()),
      remoteCompose = nested("remoteCompose", RemoteComposeOverride.serializer()),
      launcherWidget = nested("launcherWidget", LauncherWidgetOverride.serializer()),
    )
  }

  private fun toolWatch(session: Session, args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("watch: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    val project =
      supervisor.project(workspaceId)
        ?: return errorCallToolResult(
          "watch: workspace '$ws' not registered. Call register_project first."
        )
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    val glob = args["fqnGlob"]?.jsonPrimitive?.contentOrNull
    val awaitDiscovery =
      args["awaitDiscovery"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val awaitTimeoutMs =
      args["awaitTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: renderTimeoutMs
    val entry = WatchEntry(workspaceId = workspaceId, modulePath = module, fqnGlobPattern = glob)
    subscriptions.watch(session, entry)
    // Eagerly spawn the daemons matching this watch so discovery populates the catalog: just
    // `module` when given, else every `knownModules` entry.
    val toSpawn =
      if (module != null) listOf(module)
      else synchronized(project.knownModules) { project.knownModules.toList() }
    // Spawn off-thread so the session doesn't block on cold start. `daemonFor` is
    // `computeIfAbsent`-safe. Each spawn's synthetic initial discovery runs `onDiscoveryUpdated`,
    // which notifies list changes and recomputes the watch propagation.
    val toSpawnSet = toSpawn.toSet()
    val alreadySpawned = toSpawnSet.filter { project.daemons.containsKey(it) }
    val pending = toSpawnSet - alreadySpawned.toSet()
    val pendingFutures = mutableMapOf<String, CompletableFuture<SupervisedDaemon>>()
    pending.forEach { mp ->
      pendingFutures[mp] =
        CompletableFuture.supplyAsync(
            { supervisor.daemonFor(workspaceId, mp) },
            daemonLifecycleExecutor,
          )
          .whenComplete { _, error ->
            if (error != null) {
              System.err.println("watch: async spawn failed for $mp: ${error.message}")
            }
          }
    }
    // Recompute synchronously for daemons already up; async spawns recompute on their initial
    // discovery.
    alreadySpawned.forEach { mp -> project.daemons[mp]?.let { watchPropagator.recompute(it) } }
    if (awaitDiscovery && pendingFutures.isNotEmpty()) {
      val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(awaitTimeoutMs)
      for ((mp, future) in pendingFutures) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMs <= 0) break
        runCatching { future.get(remainingMs, TimeUnit.MILLISECONDS) }
          .onFailure { System.err.println("watch: awaitDiscovery failed for $mp: ${it.message}") }
      }
    }
    val readiness = watchReadiness(project, toSpawnSet)
    val readyCount = readiness.count { it.discoveryReady }
    val payload = buildJsonObject {
      put("message", "watching $entry")
      put("workspaceId", workspaceId.value)
      module?.let { put("module", it) }
      glob?.let { put("fqnGlob", it) }
      put("awaitDiscovery", awaitDiscovery)
      put("alreadyUp", alreadySpawned.size)
      put("spawning", pending.size)
      put("ready", readyCount == readiness.size)
      put("readyModules", readyCount)
      put("totalModules", readiness.size)
      if (readyCount < readiness.size) put("retryAfterMs", WATCH_DISCOVERY_RETRY_AFTER_MS)
      putJsonArray("modules") {
        readiness.forEach { state ->
          add(
            buildJsonObject {
              put("module", state.modulePath)
              put("spawned", state.spawned)
              put("discoveryReady", state.discoveryReady)
              put("previewCount", state.previewCount)
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun watchReadiness(
    project: RegisteredProject,
    modulePaths: Set<String>,
  ): List<WatchReadiness> =
    modulePaths.sorted().map { mp ->
      val daemon = project.daemons[mp]
      val addr = DaemonAddr(project.workspaceId, mp)
      WatchReadiness(
        modulePath = mp,
        spawned = daemon != null,
        discoveryReady = daemon?.initialDiscoveryComplete == true,
        previewCount = catalog[addr]?.size ?: 0,
      )
    }

  private fun toolUnwatch(session: Session, args: JsonObject): CallToolResult {
    val workspaceId = args["workspaceId"]?.jsonPrimitive?.contentOrNull?.let(::WorkspaceId)
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    val glob = args["fqnGlob"]?.jsonPrimitive?.contentOrNull
    val removed =
      subscriptions.unwatch(session) { e ->
        (workspaceId == null || e.workspaceId == workspaceId) &&
          (module == null || e.modulePath == module) &&
          (glob == null || e.fqnGlob == glob)
      }
    // After unwatch the visible/focus set may shrink; recompute every affected daemon.
    val workspaces =
      if (workspaceId != null) listOfNotNull(supervisor.project(workspaceId))
      else supervisor.listProjects()
    workspaces.forEach { project ->
      project.daemons.values.forEach { watchPropagator.recompute(it) }
    }
    return textCallToolResult("unwatched $removed entries")
  }

  private fun toolListWatches(session: Session): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("watches") {
        subscriptions.watchesFor(session).forEach { e ->
          add(
            buildJsonObject {
              put("workspaceId", e.workspaceId.value)
              if (e.modulePath != null) put("module", e.modulePath)
              if (e.fqnGlob != null) put("fqnGlob", e.fqnGlob)
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolSetVisible(args: JsonObject): CallToolResult =
    forwardVisibilityCall(args, "set_visible") { daemon, ids -> daemon.client.setVisible(ids) }

  private fun toolSetFocus(args: JsonObject): CallToolResult =
    forwardVisibilityCall(args, "set_focus") { daemon, ids -> daemon.client.setFocus(ids) }

  /**
   * Shared body for [toolSetVisible] / [toolSetFocus]: parse and validate args, find the daemon,
   * forward the call.
   */
  private fun forwardVisibilityCall(
    args: JsonObject,
    toolName: String,
    forward: (SupervisedDaemon, List<String>) -> Unit,
  ): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("$toolName: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'module'")
    val ids =
      (args["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: return errorCallToolResult("$toolName: missing 'ids' array")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("$toolName: daemon spawn failed: ${it.message}")
      }
    runCatching { forward(daemon, ids) }
      .onFailure {
        return errorCallToolResult("$toolName: wire call failed: ${it.message}")
      }
    return textCallToolResult("$toolName: forwarded ${ids.size} id(s) to $module")
  }

  private fun toolHistoryList(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_list: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("history_list: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_list: missing 'module'")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("history_list: daemon spawn failed: ${it.message}")
      }
    val params =
      ee.schimke.composeai.daemon.protocol.HistoryListParams(
        previewId = args["previewId"]?.jsonPrimitive?.contentOrNull,
        since = args["since"]?.jsonPrimitive?.contentOrNull,
        until = args["until"]?.jsonPrimitive?.contentOrNull,
        limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        cursor = args["cursor"]?.jsonPrimitive?.contentOrNull,
        branch = args["branch"]?.jsonPrimitive?.contentOrNull,
        branchPattern = args["branchPattern"]?.jsonPrimitive?.contentOrNull,
        commit = args["commit"]?.jsonPrimitive?.contentOrNull,
        worktreePath = args["worktreePath"]?.jsonPrimitive?.contentOrNull,
        agentId = args["agentId"]?.jsonPrimitive?.contentOrNull,
        sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull,
        sourceId = args["sourceId"]?.jsonPrimitive?.contentOrNull,
      )
    val result = runCatching {
      daemon.client.historyList(params)
    }
      .getOrElse {
        return errorCallToolResult("history_list failed: ${it.message}")
      }

    // Decorate each entry with the matching `compose-preview-history://` URI so clients can
    // call `resources/read` on it directly.
    val annotated = buildJsonObject {
      put("totalCount", JsonPrimitive(result.totalCount))
      if (result.nextCursor != null) put("nextCursor", JsonPrimitive(result.nextCursor))
      putJsonArray("entries") {
        result.entries.forEach { entry ->
          val obj = entry as? JsonObject ?: return@forEach
          val previewId = obj["previewId"]?.jsonPrimitive?.contentOrNull
          val entryId = obj["id"]?.jsonPrimitive?.contentOrNull
          val uri =
            if (previewId != null && entryId != null)
              HistoryUri(workspaceId, module, previewId, entryId).toUri()
            else null
          add(
            buildJsonObject {
              obj.forEach { (k, v) -> put(k, v) }
              if (uri != null) put("resourceUri", JsonPrimitive(uri))
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(annotated.toString())))
  }

  private fun toolHistoryDiff(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("history_diff: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'module'")
    val from =
      args["from"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'from'")
    val to =
      args["to"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'to'")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("history_diff: daemon spawn failed: ${it.message}")
      }
    val result = runCatching {
      daemon.client.historyDiff(
        fromId = from,
        toId = to,
        mode = ee.schimke.composeai.daemon.protocol.HistoryDiffMode.METADATA,
      )
    }
      .getOrElse {
        return errorCallToolResult("history_diff failed: ${it.message}")
      }

    val payload = buildJsonObject {
      put("pngHashChanged", JsonPrimitive(result.pngHashChanged))
      put("fromMetadata", result.fromMetadata)
      put("toMetadata", result.toMetadata)
      // Pixel-mode fields are always null in METADATA mode by design (HISTORY.md § H3).
      // We expose them so a client written against the H5-shape doesn't choke on missing keys.
      if (result.diffPx != null) put("diffPx", JsonPrimitive(result.diffPx))
      if (result.ssim != null) put("ssim", JsonPrimitive(result.ssim))
      if (result.diffPngPath != null) put("diffPngPath", JsonPrimitive(result.diffPngPath))
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  // Data product tools (docs/daemon/DATA-PRODUCTS.md). Tool-shaped rather than resource-shaped
  // because products are keyed on (previewId, kind) and `resources/read` returns one content block
  // per URI.

  private fun toolListDataProducts(args: JsonObject): CallToolResult {
    val ws = args["workspaceId"]?.jsonPrimitive?.contentOrNull
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    if (module != null && ws == null) {
      return errorCallToolResult("list_data_products: 'module' requires 'workspaceId'")
    }
    val workspaceFilter = ws?.let(::WorkspaceId)
    if (workspaceFilter != null && supervisor.project(workspaceFilter) == null) {
      return errorCallToolResult("list_data_products: workspace '$ws' not registered")
    }
    val payload = buildJsonObject {
      putJsonArray("daemons") {
        for (project in supervisor.listProjects()) {
          if (workspaceFilter != null && project.workspaceId != workspaceFilter) continue
          for ((mp, daemon) in project.daemons) {
            if (module != null && mp != module) continue
            add(
              buildJsonObject {
                put("workspaceId", project.workspaceId.value)
                put("module", mp)
                putJsonArray("kinds") {
                  daemon.dataProductCapabilities.forEach { cap ->
                    add(
                      buildJsonObject {
                        put("kind", cap.kind)
                        put("schemaVersion", cap.schemaVersion)
                        put("transport", cap.transport.name.lowercase())
                        put("attachable", cap.attachable)
                        put("fetchable", cap.fetchable)
                        put("requiresRerender", cap.requiresRerender)
                      }
                    )
                  }
                }
                putJsonArray("dataExtensions") {
                  daemon.dataExtensionDescriptors.forEach { extension ->
                    add(
                      json.encodeToJsonElement(
                        ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor.serializer(),
                        extension,
                      )
                    )
                  }
                }
              }
            )
          }
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * Routes `enable_extensions` to the daemon's `extensions/enable` for every matching (workspace,
   * module) and refreshes the supervisor's cached capabilities, so tools see the new surface
   * without an `extensions/list` round trip. Daemons start with every extension inactive
   * (PROTOCOL.md § 3a), and the standalone server enables none, so this is how an agent opts in.
   */
  private fun toolEnableExtensions(args: JsonObject): CallToolResult {
    val rawIds: JsonArray? = (args["ids"] as? JsonArray)
    if (rawIds == null || rawIds.size == 0) {
      return errorCallToolResult("enable_extensions: missing or empty 'ids'")
    }
    val ids: List<String> =
      rawIds
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
        .distinct()
    if (ids.isEmpty()) {
      return errorCallToolResult("enable_extensions: 'ids' must contain at least one extension id")
    }
    val ws = args["workspaceId"]?.jsonPrimitive?.contentOrNull
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    if (module != null && ws == null) {
      return errorCallToolResult("enable_extensions: 'module' requires 'workspaceId'")
    }
    val workspaceFilter = ws?.let(::WorkspaceId)
    if (workspaceFilter != null && supervisor.project(workspaceFilter) == null) {
      return errorCallToolResult("enable_extensions: workspace '$ws' not registered")
    }
    val perDaemon = mutableListOf<JsonObject>()
    var anyMatched = false
    for (project in supervisor.listProjects()) {
      if (workspaceFilter != null && project.workspaceId != workspaceFilter) continue
      for ((mp, daemon) in project.daemons) {
        if (module != null && mp != module) continue
        anyMatched = true
        val outcome = runCatching {
          daemon.client.extensionsEnable(ids)
        }
          .onSuccess {
            daemon.dataProductCapabilities = it.dataProducts
            daemon.dataExtensionDescriptors = it.dataExtensions
          }
        perDaemon.add(
          buildJsonObject {
            put("workspaceId", project.workspaceId.value)
            put("module", mp)
            outcome.fold(
              onSuccess = { result ->
                putJsonArray("newlyEnabled") {
                  result.newlyEnabled.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("pulledIn") { result.pulledIn.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("alreadyEnabled") {
                  result.alreadyEnabled.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("unknown") { result.unknown.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("dataProducts") {
                  result.dataProducts.forEach { cap ->
                    add(
                      buildJsonObject {
                        put("kind", cap.kind)
                        put("schemaVersion", cap.schemaVersion)
                        put("transport", cap.transport.name.lowercase())
                        put("attachable", cap.attachable)
                        put("fetchable", cap.fetchable)
                        put("requiresRerender", cap.requiresRerender)
                      }
                    )
                  }
                }
              },
              onFailure = { e -> put("error", e.message ?: e::class.java.simpleName) },
            )
          }
        )
      }
    }
    if (!anyMatched) {
      val scope =
        when {
          ws != null && module != null -> "workspace '$ws' module '$module'"
          ws != null -> "workspace '$ws'"
          else -> "any workspace"
        }
      return errorCallToolResult(
        "enable_extensions: no spawned daemon matched $scope — register the project + render at " +
          "least once to spawn a daemon before enabling extensions"
      )
    }
    val payload = buildJsonObject { putJsonArray("daemons") { perDaemon.forEach { add(it) } } }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolListExtensionCommands(args: JsonObject): CallToolResult {
    val agentRecommended =
      args["agentRecommended"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val extensions =
      if (agentRecommended) {
        PreviewExtensionCommandCatalog.extensions
          .map { extension ->
            extension.copy(cliCommands = extension.cliCommands.filter { it.agentRecommended })
          }
          .filter { it.cliCommands.isNotEmpty() }
      } else {
        PreviewExtensionCommandCatalog.extensions
      }
    val payload = buildJsonObject {
      put("schema", "compose-preview-extension-commands/v1")
      putJsonArray("extensions") {
        extensions.forEach { extension ->
          add(json.encodeToJsonElement(PreviewExtensionDescriptor.serializer(), extension))
        }
      }
      put("commandCount", extensions.sumOf { it.cliCommands.size })
    }
    return textCallToolResult(payload.toString())
  }

  private fun toolRunExtensionCommand(session: Session, args: JsonObject): CallToolResult {
    val commandId =
      args["commandId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("run_extension_command: missing 'commandId'")
    if (PreviewExtensionCommandCatalog.commandById(commandId) == null) {
      return errorCallToolResult("run_extension_command: unknown command '$commandId'")
    }
    fun data(kind: String, defaultInline: Boolean = true): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        put("kind", kind)
        args["params"]?.let { put("params", it) }
        put(
          "inline",
          JsonPrimitive(
            args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultInline
          ),
        )
      }
      return toolGetPreviewData(routed)
    }
    fun overlay(kind: String): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        put("kind", kind)
        args["inline"]?.let { put("inline", it) }
        args["overrides"]?.let { put("overrides", it) }
      }
      return toolRenderPreviewOverlay(routed)
    }
    fun render(): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        args["overrides"]?.let { put("overrides", it) }
      }
      return toolRenderPreview(session, routed)
    }
    return when (commandId) {
      "render-device-clip.get" -> data("render/deviceClip")
      "render-device-background.get" -> data("render/deviceBackground")
      "render-trace.get" -> data("render/trace")
      "compose-trace.get" -> data("render/composeAiTrace")
      "a11y.hierarchy.get" -> data("a11y/hierarchy")
      "atf-checks.run",
      "atf-checks.get" -> data("a11y/atf")
      "a11y-overlay.get" -> overlay("a11y/overlay")
      "a11y-annotated-preview.render",
      "scrolling-preview-annotation.render" -> render()
      "scroll-long.get" -> data("render/scroll/long", defaultInline = false)
      "scroll-gif.get" -> data("render/scroll/gif", defaultInline = false)
      else ->
        errorCallToolResult("run_extension_command: command '$commandId' has no MCP runner yet")
    }
  }

  private fun JsonObjectBuilder.copyArg(source: JsonObject, name: String) {
    source[name]?.let { put(name, it) }
  }

  private fun toolGetPreviewData(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_data: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("get_preview_data: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_data: missing 'kind'")
    // Default to inline=true so agents get JSON back rather than a sibling-file path they may not
    // be able to read. Local callers that prefer disk reads pass `inline: false` explicitly.
    val inline = args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val perKindParams = args["params"] as? JsonObject
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "get_preview_data: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("get_preview_data: daemon spawn failed: ${it.message}")
      }
    // Serve from cache when a previous renderFinished attached this kind (the cache mirrors the
    // latest render, so hits are fresh). Skip it when the requested transport (`inline`) differs
    // from the cached shape, or when per-kind `params` select a sub-view.
    if (perKindParams == null) {
      val cached =
        dataProductCache[DataAttachKey(uri.workspaceId, uri.modulePath, uri.previewFqn, kind)]
      if (cached != null && transportMatches(cached, inline)) {
        return renderCachedAttachment(kind, cached, inline)
      }
    }
    return runCatching {
      // Fetch first; on `DataProductNotAvailable` (-32021, never rendered) render once and retry.
      // Other errors propagate.
      val result =
        try {
          daemon.client.dataFetch(uri.previewFqn, kind, perKindParams, inline)
        } catch (e: DataProductWireException) {
          if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
          awaitNextRender(uri)
          daemon.client.dataFetch(uri.previewFqn, kind, perKindParams, inline)
        }
      renderDataFetchResult(result)
    }
      .getOrElse { e ->
        when (e) {
          is DataProductWireException ->
            errorCallToolResult("get_preview_data: ${nameOf(e.code)}: ${e.wireMessage}")
          else -> errorCallToolResult("get_preview_data failed: ${e.message}")
        }
      }
  }

  /**
   * Whether the cached entry satisfies the [inline] flag: `payload` entries serve inline requests,
   * `path` entries serve `inline = false`. Mismatches fall through to `data/fetch`.
   */
  private fun transportMatches(entry: DataAttachmentEntry, inline: Boolean): Boolean =
    when {
      inline && entry.payload != null -> true
      !inline && entry.path != null -> true
      else -> false
    }

  private fun renderCachedAttachment(
    kind: String,
    entry: DataAttachmentEntry,
    inline: Boolean,
  ): CallToolResult {
    val payload = buildJsonObject {
      put("kind", kind)
      put("schemaVersion", entry.schemaVersion)
      put("cached", true)
      val attachedPayload = entry.payload
      val attachedPath = entry.path
      if (inline && attachedPayload != null) put("payload", attachedPayload)
      if (!inline && attachedPath != null) put("path", JsonPrimitive(attachedPath))
      val extras = entry.extras
      if (extras != null) put("extras", extras)
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `render_preview_overlay`: render (so the image processor runs), fetch the overlay kind (default
   * `a11y/overlay`; any path-transport kind with PNG extras works), and return the PNG as base64.
   * `inline=false` returns the path instead. Overrides forward like `render_preview`.
   */
  private fun toolRenderPreviewOverlay(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("render_preview_overlay: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("render_preview_overlay: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_OVERLAY_KIND
    val inline = args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("render_preview_overlay: invalid overrides: ${e.message}")
          }
      }
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "render_preview_overlay: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("render_preview_overlay: daemon spawn failed: ${it.message}")
      }
    if (overrides != null) {
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("render_preview_overlay: ${violations.joinToString("; ")}")
      }
    }
    if (daemon.dataProductCapabilities.none { it.kind == kind }) {
      return errorCallToolResult(
        "render_preview_overlay: DataProductUnknown: kind '$kind' not advertised by " +
          "${uri.workspaceId.value}/${uri.modulePath}"
      )
    }
    return runCatching {
      // Force a fresh render so the overlay reflects current source.
      awaitNextRender(uri, overrides = overrides)
      val fetchResult = daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
      val pngPath =
        fetchResult.path
          ?: return@runCatching errorCallToolResult(
            "render_preview_overlay: producer for '$kind' returned no path; expected an " +
              "image-bearing kind"
          )
      if (inline) {
        val file = File(pngPath)
        if (!file.isFile) {
          return@runCatching errorCallToolResult(
            "render_preview_overlay: overlay PNG missing at $pngPath"
          )
        }
        pngCallToolResult(
          Base64.getEncoder()
            .encodeToString(fileSystem.read(file.path.toPath()) { readByteArray() })
        )
      } else {
        val payload = buildJsonObject {
          put("kind", kind)
          put("schemaVersion", fetchResult.schemaVersion)
          put("path", pngPath)
          val extras = fetchResult.extras
          if (!extras.isNullOrEmpty()) {
            putJsonArray("extras") {
              for (extra in extras) {
                add(
                  buildJsonObject {
                    put("name", extra.name)
                    put("path", extra.path)
                    if (extra.mediaType != null) put("mediaType", extra.mediaType)
                    if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
                  }
                )
              }
            }
          }
        }
        textCallToolResult(payload.toString())
      }
    }
      .getOrElse { e ->
        when (e) {
          is DataProductWireException ->
            errorCallToolResult("render_preview_overlay: ${nameOf(e.code)}: ${e.wireMessage}")
          else -> errorCallToolResult("render_preview_overlay failed: ${e.message}")
        }
      }
  }

  /**
   * `get_preview_extras`: lists the producer's extras for `(uri, kind)`. Same cache short-circuit
   * as `get_preview_data`; on a miss, `data/fetch` with `inline=false` returns the extras list.
   * Returns an `extras` array (possibly empty).
   */
  private fun toolGetPreviewExtras(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_extras: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("get_preview_extras: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_extras: missing 'kind'")
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "get_preview_extras: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("get_preview_extras: daemon spawn failed: ${it.message}")
      }
    val cached =
      dataProductCache[DataAttachKey(uri.workspaceId, uri.modulePath, uri.previewFqn, kind)]
    val cachedExtras = cached?.extras as? JsonArray
    val payload = buildJsonObject {
      put("kind", kind)
      put("uri", uriStr)
      if (cachedExtras != null) {
        put("cached", true)
        put("extras", cachedExtras)
      } else {
        val fetched = runCatching {
          try {
            daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
          } catch (e: DataProductWireException) {
            if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
            awaitNextRender(uri)
            daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
          }
        }
          .getOrElse { e ->
            return when (e) {
              is DataProductWireException ->
                errorCallToolResult("get_preview_extras: ${nameOf(e.code)}: ${e.wireMessage}")
              else -> errorCallToolResult("get_preview_extras failed: ${e.message}")
            }
          }
        putJsonArray("extras") {
          for (extra in fetched.extras.orEmpty()) {
            add(
              buildJsonObject {
                put("name", extra.name)
                put("path", extra.path)
                if (extra.mediaType != null) put("mediaType", extra.mediaType)
                if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
              }
            )
          }
        }
      }
    }
    return textCallToolResult(payload.toString())
  }

  /**
   * Forwards `data/unsubscribe` for a released `(uri, kind)`. Best-effort: runs at session
   * teardown, so failures are only logged.
   */
  private fun dispatchDataUnsubscribe(key: DataSubKey) {
    val uri = PreviewUri.parseOrNull(key.uri) ?: return
    val project = supervisor.project(uri.workspaceId) ?: return
    val daemon = project.daemons[uri.modulePath] ?: return
    runCatching { daemon.client.dataUnsubscribe(uri.previewFqn, key.kind) }
      .onFailure {
        System.err.println(
          "DaemonMcpServer: data/unsubscribe for ${key.uri} ($key.kind) failed: ${it.message}"
        )
      }
  }

  private fun renderDataFetchResult(
    result: ee.schimke.composeai.daemon.protocol.DataFetchResult
  ): CallToolResult {
    val resultPayload = result.payload
    val resultPath = result.path
    val resultBytes = result.bytes
    val resultExtras = result.extras
    val payload = buildJsonObject {
      put("kind", result.kind)
      put("schemaVersion", result.schemaVersion)
      if (resultPayload != null) put("payload", resultPayload)
      if (resultPath != null) put("path", JsonPrimitive(resultPath))
      if (resultBytes != null) put("bytes", JsonPrimitive(resultBytes))
      if (!resultExtras.isNullOrEmpty()) {
        putJsonArray("extras") {
          for (extra in resultExtras) {
            add(
              buildJsonObject {
                put("name", extra.name)
                put("path", extra.path)
                if (extra.mediaType != null) put("mediaType", extra.mediaType)
                if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
              }
            )
          }
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `diff_semantics`: fetch `compose/semantics` for two URIs and report the structural delta. Nodes
   * match on their stable `ref`, so a copy edit is a field change rather than remove + add. Returns
   * `{ schema, baseUri, headUri, summary, delta }` as one text block.
   */
  private fun toolDiffSemantics(args: JsonObject): CallToolResult {
    val baseUriStr =
      args["baseUri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("diff_semantics: missing 'baseUri'")
    val headUriStr =
      args["headUri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("diff_semantics: missing 'headUri'")
    val (base, baseErr) = fetchSemanticsPayload(baseUriStr, "base")
    if (base == null) return errorCallToolResult("diff_semantics: $baseErr")
    val (head, headErr) = fetchSemanticsPayload(headUriStr, "head")
    if (head == null) return errorCallToolResult("diff_semantics: $headErr")
    val delta = SemanticsDiff.diff(base, head)
    val out = buildJsonObject {
      put("schema", delta.schema)
      put("baseUri", baseUriStr)
      put("headUri", headUriStr)
      put("summary", summarizeSemanticsDelta(delta))
      put("delta", json.encodeToJsonElement(SemanticsDelta.serializer(), delta))
    }
    return CallToolResult(content = listOf(ContentBlock.Text(out.toString())))
  }

  /**
   * Fetch and decode `compose/semantics` for one URI, auto-rendering once on
   * `DataProductNotAvailable`. Returns `(payload, null)` or `(null, message)` with a
   * [side]-prefixed diagnostic.
   */
  private fun fetchSemanticsPayload(
    uriStr: String,
    side: String,
  ): Pair<ComposeSemanticsPayload?, String?> {
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return null to
          if (uriStr.startsWith("${HistoryUri.SCHEME}://")) {
            "$side: history URIs aren't supported yet (compose/semantics isn't persisted per " +
              "history entry); pass a live compose-preview:// URI"
          } else {
            "invalid $side uri: $uriStr"
          }
    if (supervisor.project(uri.workspaceId) == null) {
      return null to "$side workspace '${uri.workspaceId.value}' not registered"
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return null to "$side daemon spawn failed: ${it.message}"
      }
    val overrides =
      uri.overridesJson?.let { raw ->
        runCatching {
          val decoded = decodePreviewOverrides(json.parseToJsonElement(raw))
          val violations = validateOverrides(decoded, daemon)
          check(violations.isEmpty()) {
            "Invalid compose-preview resource overrides: ${violations.joinToString("; ")}"
          }
          decoded
        }
          .getOrElse {
            return null to "$side invalid resource overrides: ${it.message}"
          }
      }
    val renderUri = uri.copy(overridesJson = null)
    val result = runCatching {
      if (overrides != null) {
        // data/fetch reads the products of the preview's most recent render, so render this URI's
        // state first; otherwise semantics could describe defaults while the pixels are overridden.
        awaitNextRender(renderUri, overrides = overrides)
        daemon.client.dataFetch(
          uri.previewFqn,
          ComposeSemanticsProduct.KIND,
          null,
          inline = true,
        )
      } else {
        try {
          daemon.client.dataFetch(
            uri.previewFqn,
            ComposeSemanticsProduct.KIND,
            null,
            inline = true,
          )
        } catch (e: DataProductWireException) {
          // Unknown: the daemon hasn't activated the kind, so opt it in, then render. Not
          // available: active but not rendered yet.
          val retry =
            e.code == DataProductWireException.NOT_AVAILABLE ||
              (e.code == DataProductWireException.UNKNOWN && enableSemanticsExtension(daemon))
          if (!retry) throw e
          awaitNextRender(renderUri)
          daemon.client.dataFetch(
            uri.previewFqn,
            ComposeSemanticsProduct.KIND,
            null,
            inline = true,
          )
        }
      }
    }
      .getOrElse { e ->
        return null to
          when (e) {
            is DataProductWireException -> "$side ${nameOf(e.code)}: ${e.wireMessage}"
            else -> "$side fetch failed: ${e.message}"
          }
      }
    return runCatching { decodeSemanticsPayload(result) }
      .map { it to null }
      .getOrElse { null to "$side: could not read compose/semantics (${it.message})" }
  }

  /**
   * Activates `compose/semantics` on a daemon that doesn't advertise it. True when it now serves
   * it; false when it was already advertised (so an unknown-kind error is real) or refused.
   */
  private fun enableSemanticsExtension(daemon: SupervisedDaemon): Boolean {
    val kind = ComposeSemanticsProduct.KIND
    if (daemon.dataProductCapabilities.any { it.kind == kind }) return false
    return runCatching { daemon.client.extensionsEnable(listOf(kind)) }
      .onSuccess {
        daemon.dataProductCapabilities = it.dataProducts
        daemon.dataExtensionDescriptors = it.dataExtensions
      }
      .onFailure {
        System.err.println("render_preview: extensions/enable [$kind] failed: ${it.message}")
      }
      .map { result -> result.dataProducts.any { it.kind == kind } }
      .getOrDefault(false)
  }

  /**
   * Decode a [DataFetchResult] into a [ComposeSemanticsPayload] from whichever transport it used.
   */
  private fun decodeSemanticsPayload(
    result: ee.schimke.composeai.daemon.protocol.DataFetchResult
  ): ComposeSemanticsPayload {
    result.payload?.let {
      return json.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), it)
    }
    result.bytes?.let {
      val text = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), text)
    }
    result.path?.let { path ->
      val text = SystemFileSystem.read(path.toPath()) { readUtf8() }
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), text)
    }
    error("empty data/fetch result (no payload, bytes, or path)")
  }

  /** One-line human summary of a [SemanticsDelta] for the tool response. */
  private fun summarizeSemanticsDelta(delta: SemanticsDelta): String {
    if (delta.isEmpty) return "no semantic changes"
    return buildString {
      append("${delta.added.size} added, ${delta.removed.size} removed, ")
      append("${delta.changed.size} changed")
      delta.changed.take(3).forEach { change ->
        val fields = change.changes.joinToString(", ") { it.field }
        append("; ${change.anchor ?: change.ref}: $fields")
      }
    }
  }

  /**
   * `record_preview`: drives the daemon's `recording/start | script | stop | encode` flow (see
   * RECORDING.md). Validates `overrides` against `supportedOverrides` and decodes the script into
   * typed [RecordingScriptEvent]s so malformed events fail as clean tool errors. Errors are
   * `isError = true` text; success returns the observation or media (see `observe`). The session is
   * closed best-effort if a call fails mid-flight, without suppressing the original error.
   */
  private fun toolRecordPreview(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("record_preview: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("record_preview: invalid uri: $uriStr")
    val eventsRaw =
      (args["events"] as? JsonArray)
        ?: return errorCallToolResult("record_preview: missing 'events' (must be array)")
    val events = runCatching {
      decodeRecordingEvents(eventsRaw)
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid events: ${it.message}")
      }
    // Strict numeric validation: absent means the daemon default, malformed is an error (not a
    // silent default).
    val fps = runCatching {
      decodeOptionalInt("fps", args["fps"])
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid fps: ${it.message}")
      }
    val scale = runCatching {
      decodeOptionalFloat("scale", args["scale"])
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid scale: ${it.message}")
      }
    val formatStr = args["format"]?.jsonPrimitive?.contentOrNull?.lowercase()
    val format =
      when (formatStr) {
        null,
        "apng" -> RecordingFormat.APNG
        "gif" -> RecordingFormat.GIF
        "mp4" -> RecordingFormat.MP4
        "webm" -> RecordingFormat.WEBM
        else ->
          return errorCallToolResult(
            "record_preview: unsupported 'format' '$formatStr' — supported: apng, gif, mp4, webm"
          )
      }
    // Compact default: `frames` returns per-frame hashes, changed-frame indices and on-disk paths
    // with no inline media; `media` returns the encoded bytes inline, which scale with fps ×
    // duration.
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "frames"
    if (observe !in setOf("frames", "media")) {
      return errorCallToolResult("record_preview: 'observe' must be one of frames | media")
    }
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("record_preview: invalid overrides: ${e.message}")
          }
      }
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "record_preview: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("record_preview: daemon spawn failed: ${it.message}")
      }
    if (overrides != null) {
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("record_preview: ${violations.joinToString("; ")}")
      }
    }
    val scriptKindViolations = validateRecordingScriptKinds(events, daemon)
    if (scriptKindViolations.isNotEmpty()) {
      return errorCallToolResult("record_preview: ${scriptKindViolations.joinToString("; ")}")
    }
    // When the daemon advertises `recordingFormats`, reject other formats up front. An empty set
    // (older daemon) falls open, as `validateOverrides` does.
    val advertisedFormats = daemon.recordingFormats
    val formatWire =
      when (format) {
        RecordingFormat.APNG -> "apng"
        RecordingFormat.GIF -> "gif"
        RecordingFormat.MP4 -> "mp4"
        RecordingFormat.WEBM -> "webm"
      }
    if (advertisedFormats.isNotEmpty() && formatWire !in advertisedFormats) {
      return errorCallToolResult(
        "record_preview: format '$formatWire' not advertised by this daemon " +
          "(supported: ${advertisedFormats.sorted()}). " +
          "mp4/webm require an ffmpeg binary on the daemon's PATH."
      )
    }

    val started = runCatching {
      daemon.client.recordingStart(
        previewId = uri.previewFqn,
        fps = fps,
        scale = scale,
        overrides = overrides,
      )
    }
      .getOrElse {
        return errorCallToolResult("record_preview: recording/start failed: ${it.message}")
      }
    val recordingId = started.recordingId
    return runCatching {
      if (events.isNotEmpty()) {
        daemon.client.recordingScript(recordingId, events)
      }
      val stopResult = daemon.client.recordingStop(recordingId)
      val frameMetadata = inspectRecordingFrames(File(stopResult.framesDir))
      val encoded = daemon.client.recordingEncode(recordingId, format)
      // Read the encoded bytes only for observe="media".
      val videoBytes by lazy {
        fileSystem.read(File(encoded.videoPath).path.toPath()) { readByteArray() }
      }
      val payload = buildJsonObject {
        put("observe", observe)
        if (observe == "frames") {
          // The encoded artifact still exists on disk at `videoPath`; re-call with
          // observe="media" to get the bytes inline.
          put("mediaInline", false)
        }
        put("recordingId", recordingId)
        put("videoPath", encoded.videoPath)
        put("mimeType", encoded.mimeType)
        put("sizeBytes", encoded.sizeBytes)
        put("frameCount", stopResult.frameCount)
        put("durationMs", stopResult.durationMs)
        put("frameWidthPx", stopResult.frameWidthPx)
        put("frameHeightPx", stopResult.frameHeightPx)
        put("framesDir", stopResult.framesDir)
        put("changedFrameCount", frameMetadata.count { it.changedFromPrevious })
        frameMetadata.firstOrNull()?.let { put("firstFramePath", it.path) }
        frameMetadata.lastOrNull()?.let { put("lastFramePath", it.path) }
        frameMetadata
          .firstOrNull { it.changedFromPrevious }
          ?.let {
            put("firstChangedFramePath", it.path)
            put("firstChangedFrameIndex", it.index)
          }
        frameMetadata
          .lastOrNull { it.changedFromPrevious }
          ?.let {
            put("lastChangedFramePath", it.path)
            put("lastChangedFrameIndex", it.index)
          }
        putJsonArray("frames") {
          for (frame in frameMetadata) {
            add(
              buildJsonObject {
                put("index", frame.index)
                put("path", frame.path)
                put("sha256", frame.sha256)
                put("changedFromPrevious", frame.changedFromPrevious)
                frame.changedPixelsFromPrevious?.let { put("changedPixelsFromPrevious", it) }
                frame.dimensionChangedFromPrevious?.let { put("dimensionChangedFromPrevious", it) }
              }
            )
          }
        }
        putJsonArray("scriptEvents") {
          for (event in stopResult.scriptEvents) {
            add(
              buildJsonObject {
                put("tMs", event.tMs)
                put("kind", event.kind)
                put("status", event.status.wireName())
                event.label?.let { put("label", it) }
                event.checkpointId?.let { put("checkpointId", it) }
                event.lifecycleEvent?.let { put("lifecycleEvent", it) }
                if (event.tags.isNotEmpty()) {
                  putJsonArray("tags") { for (tag in event.tags) add(JsonPrimitive(tag)) }
                }
                event.message?.let { put("message", it) }
                // Structured semantic-target miss: code, matchCount and candidate nodes, so the
                // agent can pick a `ref` without re-rendering.
                event.targetUnresolvedReason?.let {
                  put(
                    "targetUnresolvedReason",
                    json.encodeToJsonElement(
                      ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedReason
                        .serializer(),
                      it,
                    ),
                  )
                }
              }
            )
          }
        }
      }
      // Per MCP 2025-06-18 only `image/*` belongs in `ContentBlock.Image`: APNG goes as an image;
      // mp4 / webm go as an `EmbeddedResource` wrapping a `Blob`.
      val mediaBlock: ContentBlock? =
        if (observe != "media") {
          null
        } else if (encoded.mimeType.startsWith("image/")) {
          ContentBlock.Image(
            data = Base64.getEncoder().encodeToString(videoBytes),
            mimeType = encoded.mimeType,
          )
        } else {
          ContentBlock.EmbeddedResource(
            resource =
              ResourceContents.Blob(
                uri = "compose-preview-recording://$recordingId",
                mimeType = encoded.mimeType,
                blob = Base64.getEncoder().encodeToString(videoBytes),
              )
          )
        }
      CallToolResult(
        content =
          buildList {
            // observe="media" (opt-in): the inline APNG/MP4/WebM bytes lead the result, as
            // before. observe="frames" (default): structured per-frame observation only.
            mediaBlock?.let { add(it) }
            add(ContentBlock.Text(payload.toString()))
            // Opt-in: turn the recorded interaction into a runnable Compose UI test, built from the
            // applied evidence so unresolved targets become skipped-step comments.
            if (args["emitTest"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true) {
              add(
                ContentBlock.Text(generateRecordingTestSource(uri, events, stopResult.scriptEvents))
              )
            }
          }
      )
    }
      .getOrElse { errorCallToolResult("record_preview failed: ${it.message}") }
  }

  /**
   * Generate a Compose UI test from a `record_preview` interaction. `setContent { <method>() }`
   * uses the preview's real `@Composable` name from the catalog ([PreviewEntry.functionName]),
   * since named/variant previews have synthetic ids (`…WeatherForecast_Light`) that aren't
   * callable; the id heuristic is only a fallback when the catalog has no entry. Steps come from
   * the recording's [evidence], so `unsupported` events become skipped-step comments; if evidence
   * doesn't align 1:1, every event is treated as applied.
   */
  private fun generateRecordingTestSource(
    uri: PreviewUri,
    events: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvent>,
    evidence: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence>,
  ): String {
    val resolvedFunctionName =
      catalog[DaemonAddr(uri.workspaceId, uri.modulePath)]
        ?.get(uri.previewFqn)
        ?.functionName
        ?.takeIf { it.isNotBlank() }
    val method =
      resolvedFunctionName ?: uri.previewFqn.substringAfterLast('.').ifBlank { "preview" }
    val pascal = method.replaceFirstChar { it.uppercaseChar() }
    val camel = method.replaceFirstChar { it.lowercaseChar() }
    // The recording dispatches script events in tMs order and appends one evidence each, so a
    // size-matched evidence list aligns by index with the tMs-sorted events.
    val sorted = events.sortedBy { it.tMs }
    val steps =
      if (evidence.size == sorted.size) {
        sorted.mapIndexed { i, event ->
          RecordingTestGenerator.Step(
            event,
            applied = evidence[i].status == RecordingScriptEventStatus.APPLIED,
            probeSemantics = evidence[i].probeSemantics,
          )
        }
      } else {
        RecordingTestGenerator.stepsOf(sorted)
      }
    return RecordingTestGenerator.generate(
      RecordingTestGenerator.Spec(
        className = "Generated${pascal}Test",
        methodName = "${camel}Interaction",
        composableInvocation = "$method()",
        steps = steps,
      )
    )
  }

  private data class RecordingFrameMetadata(
    val index: Int,
    val path: String,
    val sha256: String,
    val changedFromPrevious: Boolean,
    val changedPixelsFromPrevious: Int?,
    val dimensionChangedFromPrevious: Boolean?,
  )

  private fun inspectRecordingFrames(framesDir: File): List<RecordingFrameMetadata> {
    val frames =
      framesDir
        .listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    var previous: java.awt.image.BufferedImage? = null
    return frames.mapIndexed { index, frame ->
      // Read the PNG bytes through Okio, then decode from memory (ImageIO is the codec boundary).
      val image = runCatching {
        val bytes = fileSystem.read(frame.path.toPath()) { readByteArray() }
        ImageIO.read(bytes.inputStream())
      }
        .getOrNull()
      val previousImage = previous
      val changedPixels =
        if (previousImage != null && image != null && sameDimensions(previousImage, image)) {
          countChangedPixels(previousImage, image)
        } else {
          null
        }
      val dimensionChanged =
        if (previousImage != null && image != null) !sameDimensions(previousImage, image) else null
      val changedFromPrevious =
        when {
          index == 0 -> false
          changedPixels != null -> changedPixels > 0
          dimensionChanged == true -> true
          else -> false
        }
      if (image != null) previous = image
      RecordingFrameMetadata(
        index = index,
        path = frame.absolutePath,
        sha256 = sha256Hex(frame),
        changedFromPrevious = changedFromPrevious,
        changedPixelsFromPrevious = changedPixels,
        dimensionChangedFromPrevious = dimensionChanged,
      )
    }
  }

  private fun sameDimensions(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Boolean = a.width == b.width && a.height == b.height

  private fun countChangedPixels(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Int {
    var changed = 0
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
      }
    }
    return changed
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Decode an optional integer arg: `null` for absent or JSON null (daemon default); throws
   * [IllegalStateException] when present but not an integer, reported as `record_preview: invalid
   * <name>`.
   */
  private fun decodeOptionalInt(name: String, elem: JsonElement?): Int? {
    if (elem == null || elem is kotlinx.serialization.json.JsonNull) return null
    val raw =
      elem.jsonPrimitive.contentOrNull ?: error("'$name' must be a number; got null primitive")
    return raw.toIntOrNull() ?: error("'$name' must be an integer; got '$raw'")
  }

  /** As [decodeOptionalInt] but for a floating-point arg (`scale`). */
  private fun decodeOptionalFloat(name: String, elem: JsonElement?): Float? {
    if (elem == null || elem is kotlinx.serialization.json.JsonNull) return null
    val raw =
      elem.jsonPrimitive.contentOrNull ?: error("'$name' must be a number; got null primitive")
    return raw.toFloatOrNull() ?: error("'$name' must be a number; got '$raw'")
  }

  /**
   * Translate `record_preview.events` into typed [RecordingScriptEvent]s, requiring a non-negative
   * `tMs` and non-blank `kind`; throws on malformed input. Unknown extra keys are tolerated. Kind
   * validation against the daemon happens later in [validateRecordingScriptKinds].
   */
  private fun decodeRecordingEvents(arr: JsonArray): List<RecordingScriptEvent> {
    return arr.mapIndexed { idx, elem ->
      val obj = (elem as? JsonObject) ?: error("event[$idx] must be an object")
      val tMs =
        obj["tMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
          ?: error("event[$idx] missing or invalid 'tMs'")
      require(tMs >= 0) { "event[$idx] tMs must be ≥ 0; got $tMs" }
      val kindStr = obj["kind"]?.jsonPrimitive?.contentOrNull ?: error("event[$idx] missing 'kind'")
      require(kindStr.isNotBlank()) { "event[$idx] kind must not be blank" }
      RecordingScriptEvent(
        tMs = tMs,
        kind = kindStr,
        pixelX = obj["pixelX"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        pixelY = obj["pixelY"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        target =
          (obj["target"] as? JsonObject)?.let {
            json.decodeFromJsonElement(SemanticsInputTarget.serializer(), it)
          },
        scrollDeltaY = obj["scrollDeltaY"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        keyCode = obj["keyCode"]?.jsonPrimitive?.contentOrNull,
        label = obj["label"]?.jsonPrimitive?.contentOrNull,
        checkpointId = obj["checkpointId"]?.jsonPrimitive?.contentOrNull,
        lifecycleEvent = obj["lifecycleEvent"]?.jsonPrimitive?.contentOrNull,
        tags =
          (obj["tags"] as? JsonArray)?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull }
            ?: emptyList(),
        nodeContentDescription = obj["nodeContentDescription"]?.jsonPrimitive?.contentOrNull,
        selector = obj["selector"] as? JsonObject,
        useUnmergedTree =
          obj["useUnmergedTree"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
        inputText = obj["inputText"]?.jsonPrimitive?.contentOrNull,
        deepLinkUri = obj["deepLinkUri"]?.jsonPrimitive?.contentOrNull,
        backProgress = obj["backProgress"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        backEdge = obj["backEdge"]?.jsonPrimitive?.contentOrNull,
      )
    }
  }

  private fun RecordingScriptEventStatus.wireName(): String =
    when (this) {
      RecordingScriptEventStatus.APPLIED -> "applied"
      RecordingScriptEventStatus.UNSUPPORTED -> "unsupported"
      RecordingScriptEventStatus.FAILED -> "failed"
    }

  /**
   * Validates every script event id against the resolved daemon's
   * `ServerCapabilities.dataExtensions[].recordingScriptEvents[]`:
   * - `supported = true`: accepted.
   * - `supported = false`: rejected, pointing at `list_data_products`. (The daemon's own
   *   `unsupported` evidence fallback remains for other clients.)
   * - not advertised: rejected. Input kinds are advertised by the input extensions like any other,
   *   so there is no special case.
   */
  private fun validateRecordingScriptKinds(
    events: List<RecordingScriptEvent>,
    daemon: SupervisedDaemon,
  ): List<String> {
    val supportedEventIds =
      daemon.dataExtensionDescriptors
        .flatMap { it.recordingScriptEvents }
        .filter { it.supported }
        .map { it.id }
        .toSet()
    val advertisedButUnsupported =
      daemon.dataExtensionDescriptors
        .flatMap { it.recordingScriptEvents }
        .filterNot { it.supported }
        .map { it.id }
        .toSet()
    return events.mapIndexedNotNull { index, event ->
      when {
        event.kind in supportedEventIds -> null
        event.kind in advertisedButUnsupported ->
          "event[$index] script event '${event.kind}' is advertised by this daemon but not yet " +
            "implemented (supported=false); list_data_products to inspect the roadmap"
        else -> {
          val hint = suggestionFor(event.kind, supportedEventIds)
          "event[$index] kind '${event.kind}' is not advertised by this daemon. Call " +
            "list_data_products to see the available script-event ids." +
            if (hint != null) " Did you mean '$hint'?" else ""
        }
      }
    }
  }

  /**
   * Catches a dropped namespace (`"click"` for `"input.click"`): returns the supported id whose
   * tail matches, else no hint.
   */
  private fun suggestionFor(unknown: String, supported: Set<String>): String? {
    if (unknown.contains('.')) return null
    return supported.firstOrNull { it.substringAfter('.', "") == unknown }
  }

  private fun nameOf(code: Int): String =
    when (code) {
      DataProductWireException.UNKNOWN -> "DataProductUnknown"
      DataProductWireException.NOT_AVAILABLE -> "DataProductNotAvailable"
      DataProductWireException.FETCH_FAILED -> "DataProductFetchFailed"
      DataProductWireException.BUDGET_EXCEEDED -> "DataProductBudgetExceeded"
      else -> "wire-error-$code"
    }

  private fun toolDataSubOrUnsub(
    session: Session,
    args: JsonObject,
    subscribe: Boolean,
  ): CallToolResult {
    val toolName = if (subscribe) "subscribe_preview_data" else "unsubscribe_preview_data"
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("$toolName: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'kind'")
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult("$toolName: workspace '${uri.workspaceId.value}' not registered")
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("$toolName: daemon spawn failed: ${it.message}")
      }
    // Refcount across sessions so one wire `data/subscribe` serves every session; forwards only on
    // first-ref / last-ref transitions.
    return runCatching {
      if (subscribe) {
        val firstRef = subscriptions.subscribeData(uriStr, kind, session)
        if (firstRef) daemon.client.dataSubscribe(uri.previewFqn, kind)
        textCallToolResult(
          "$toolName: ok ($kind for ${uri.previewFqn}, " +
            if (firstRef) "first session)" else "shared with N≥2 sessions)"
        )
      } else {
        val lastRef = subscriptions.unsubscribeData(uriStr, kind, session)
        if (lastRef) daemon.client.dataUnsubscribe(uri.previewFqn, kind)
        textCallToolResult(
          "$toolName: ok ($kind for ${uri.previewFqn}, " +
            if (lastRef) "released)" else "still shared with other sessions)"
        )
      }
    }
      .getOrElse { errorCallToolResult("$toolName failed: ${it.message}") }
  }

  /**
   * The Gradle build [file] belongs to when it is neither [project] nor another registered build:
   * the nearest ancestor with a settings file, when [file] is outside [project]'s root or under a
   * hidden directory in it (`.claude/worktrees/<name>/…`). Null otherwise.
   */
  private fun otherBuildFor(file: File, project: RegisteredProject): File? {
    val canonical = runCatching { file.absoluteFile.canonicalFile }.getOrDefault(file.absoluteFile)
    val root = runCatching { project.path.canonicalFile }.getOrDefault(project.path)
    val inside = canonical.startsWith(root)
    val hidden =
      inside &&
        canonical.relativeTo(root).invariantSeparatorsPath.split('/').any { it.startsWith(".") }
    if (inside && !hidden) return null
    val build = canonical.parentFile?.let(ProjectDiscovery::enclosingBuild) ?: return null
    val registered =
      supervisor.listProjects().map { runCatching { it.path.canonicalFile }.getOrDefault(it.path) }
    return build.takeIf { it !in registered }
  }

  /**
   * The workspace a `notify_file_changed` without `workspaceId` means: the registered project whose
   * root holds [path] (deepest, when builds nest), else the only registered project for a relative
   * [path].
   */
  private fun workspaceForPath(path: String): WorkspaceId? {
    val projects = supervisor.listProjects()
    val file = File(path)
    if (!file.isAbsolute) return projects.singleOrNull()?.workspaceId
    val canonical = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
    return projects
      .filter { project ->
        val root = runCatching { project.path.canonicalFile }.getOrDefault(project.path)
        canonical.toPath().startsWith(root.toPath())
      }
      .maxByOrNull { it.path.absolutePath.length }
      ?.workspaceId
  }

  private fun toolNotifyFileChanged(args: JsonObject): CallToolResult {
    val path =
      args["path"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("notify_file_changed: missing 'path'")
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: workspaceForPath(path)?.value
        ?: return errorCallToolResult(
          "notify_file_changed: missing 'workspaceId', and '$path' is not inside exactly one " +
            "registered project (list_projects shows them)"
        )
    val workspaceId = WorkspaceId(ws)
    val project =
      supervisor.project(workspaceId)
        ?: return errorCallToolResult("notify_file_changed: unknown workspace '$ws'")
    File(path)
      .takeIf(File::isAbsolute)
      ?.let { otherBuildFor(it, project) }
      ?.let { build ->
        // The edit landed in another Gradle build (typically a worktree of the registered one);
        // register it so renders and `preview` names resolve to the edited tree.
        val registered = registerProjectAt(build, rootName = null, modules = emptyList())
        preferredRoots = listOf(build)
        return textCallToolResult(
          "edited file $path is not in registered project ${project.rootProjectName} " +
            "(${project.path}); rendering from ${registered.path} instead: registered it as " +
            "workspace ${registered.workspaceId.value}. Render its previews by name, or with " +
            "compose-preview://${registered.workspaceId.value}/… URIs."
        )
      }
    val kind =
      when (args["kind"]?.jsonPrimitive?.contentOrNull) {
        "resource" -> FileKind.RESOURCE
        "classpath" -> FileKind.CLASSPATH
        else -> FileKind.SOURCE
      }
    val changeType =
      when (args["changeType"]?.jsonPrimitive?.contentOrNull) {
        "created" -> ChangeType.CREATED
        "deleted" -> ChangeType.DELETED
        else -> ChangeType.MODIFIED
      }
    if (kind == FileKind.SOURCE && changeType != ChangeType.DELETED) {
      previewActivity.sourceChanged(path)
    }
    // Forward to every spawned daemon in the workspace (each decides whether the file is in its
    // source set), then re-render every URI any session watches or subscribes to, so fresh bytes
    // flow out via `renderFinished` → `notifications/resources/updated`.
    var forwarded = 0
    var rendered = 0
    // A Kotlin/Java edit needs a recompile before the classloader swap sees it. Compile the modules
    // declaring a preview in this file; if none does (shared code, a library), compile every
    // module.
    val compileTargets: Set<String> =
      if (
        sourceCompiler != null &&
          kind == FileKind.SOURCE &&
          changeType != ChangeType.DELETED &&
          File(path).extension in COMPILED_SOURCE_EXTENSIONS
      ) {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val declaring =
          project.daemons.values
            .filter { daemon ->
              catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.values?.any {
                it.resolvedSourcePath == canonical
              } == true
            }
            .map { it.modulePath }
            .toSet()
        declaring.ifEmpty { project.daemons.keys.toSet() }
      } else emptySet()
    val compileLines = mutableListOf<String>()
    val compileWork = sortedMapOf<String, CompileWork>()
    project.daemons.values.forEach { daemon ->
      val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
      if (daemon.modulePath in compileTargets) {
        pendingSources.computeIfAbsent(addr) { ConcurrentHashMap.newKeySet() }.add(path)
        markSourceSeen(addr, File(path))
        detectSourceChanges(daemon)
        pendingTrigger[addr] = TRIGGER_NOTIFY
        val outcome = recompilePendingSources(daemon)
        outcome?.work?.let { compileWork[daemon.modulePath] = it.copy(trigger = TRIGGER_NOTIFY) }
        when (outcome) {
          is SourceCompileOutcome.Ok ->
            compileLines += "recompiled ${daemon.modulePath} in ${outcome.durationMs}ms"
          null -> {}
          else -> staleRenderLine(addr)?.let { compileLines += "${daemon.modulePath}: $it" }
        }
        forwarded += daemon.allClients().size
      } else {
        // File invalidation must reach EVERY replica — each replica has its own independent
        // discovery + render cache, so missing one would leave it serving stale bytes.
        daemon.allClients().forEach { client ->
          runCatching { client.fileChanged(path = path, kind = kind, changeType = changeType) }
            .onSuccess { forwarded++ }
        }
      }
      val byId = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)] ?: return@forEach
      // Build the candidate URI set for this daemon and intersect with current watches/subs.
      val candidates =
        byId.values.map { entry ->
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        }
      candidates.forEach { uri ->
        val subscribedUris = subscriptions.subscribedUrisMatching(uri)
        val refreshes = mutableMapOf<PreviewOverrides?, MutableSet<String>>()
        if (
          subscriptions.sessionsWatching(uri).isNotEmpty() ||
            subscribedUris.containsKey(uri.toUri())
        ) {
          refreshes.getOrPut(null) { mutableSetOf() }.add(uri.toUri())
        }
        subscribedUris.keys.forEach { subscribedUri ->
          val parsed = PreviewUri.parseOrNull(subscribedUri) ?: return@forEach
          val rawOverrides = parsed.overridesJson ?: return@forEach
          val overrides =
            runCatching { decodePreviewOverrides(json.parseToJsonElement(rawOverrides)) }
              .getOrNull() ?: return@forEach
          refreshes.getOrPut(overrides) { mutableSetOf() }.add(subscribedUri)
        }
        refreshes.forEach { (overrides, notificationUris) ->
          runCatching {
            enqueueRefresh(
              daemon = daemon,
              uri = uri,
              overrides = overrides,
              notificationUris = notificationUris,
              reason = "notify_file_changed:$path",
            )
          }
          rendered++
        }
      }
    }
    return textCallToolResult(
        (listOf(
            "fileChanged forwarded to $forwarded daemon(s); re-rendered $rendered watched preview(s)"
          ) + compileLines)
          .joinToString("\n")
      )
      .let { result ->
        // Each module's recompile, as `_meta.work.compile`, keyed by module path.
        if (compileWork.isEmpty()) result
        else
          result.copy(
            meta =
              buildJsonObject {
                putJsonObject("work") {
                  putJsonObject("compile") {
                    compileWork.forEach { (module, work) -> put(module, work.toJson()) }
                  }
                }
              }
          )
      }
  }

  // Daemon notification handlers.

  private fun onDiscoveryUpdated(daemon: SupervisedDaemon, params: JsonObject?) {
    daemon.initialDiscoveryComplete = true
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    val byId = catalog.computeIfAbsent(addr) { ConcurrentHashMap() }
    val added = (params?.get("added") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val changed = (params?.get("changed") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val removed =
      (params?.get("removed") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    for (entry in added + changed) {
      val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: continue
      val sourceFile = entry["sourceFile"]?.jsonPrimitive?.contentOrNull
      val resolved = resolvePreviewSourceFile(daemon, sourceFile)
      val sourceLastModifiedMs = resolved?.lastModified()?.takeIf { it > 0L }
      // Seed the content hash at discovery so the first frozen-mtime edit is caught. On failure it
      // stays null and the probe uses mtime only.
      val sourceContentHash = resolved?.let { runCatching { sha256Hex(it) }.getOrNull() }
      byId[id] =
        PreviewEntry(
          fqn = id,
          displayName = entry["displayName"]?.jsonPrimitive?.contentOrNull,
          config = entry["config"]?.jsonPrimitive?.contentOrNull,
          sourceFile = sourceFile,
          resolvedSourcePath = resolved?.canonicalPath,
          functionName = entry["functionName"]?.jsonPrimitive?.contentOrNull,
          bodyLine = entry["bodyLine"]?.jsonPrimitive?.intOrNull,
          sourceLastModifiedMs = sourceLastModifiedMs,
          sourceContentHash = sourceContentHash,
        )
    }
    removed.forEach { byId.remove(it) }
    sessions.forEach { it.notifyResourceListChanged() }
    watchPropagator.recompute(daemon)
  }

  private fun onRenderFinished(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val pngPath = params["pngPath"]?.jsonPrimitive?.contentOrNull ?: return
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
    // 0. Sampling attribution: if a probe was pending for this preview, claim it and classify
    //    `unchanged`. Probes never enqueue futures, so step 1 is a no-op for them.
    var probeClaimed = false
    pendingProbes.computeIfPresent(key) { _, counter ->
      probeClaimed = true
      if (counter.decrementAndGet() <= 0) null else counter
    }
    if (probeClaimed) {
      val unchanged = params["unchanged"]?.jsonPrimitive?.booleanOrNull == true
      if (unchanged) {
        freshnessMetrics.samplingDeterministic.incrementAndGet()
      } else {
        freshnessMetrics.samplingNondeterministic.incrementAndGet()
        val entryForUri = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.get(previewId)
        val probeUri =
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = previewId,
            config = entryForUri?.config,
          )
        freshnessMetrics.recordNondeterministic(probeUri.toUri())
      }
    }
    // 1. Pop the head group, wake its waiters with the bytes, and dispatch the next group (see
    //    `popHeadAndPrepareNext` and `awaitNextRender`).
    val pngBytes = runCatching {
      val file = File(pngPath)
      check(file.isFile) { "renderFinished pngPath does not exist: $pngPath" }
      fileSystem.read(file.path.toPath()) { readByteArray() }
    }
      .getOrElse { failure ->
        val failed =
          popHeadAndPrepareNext(
            daemon,
            key,
            RenderOutcome.Failed("RenderOutputMissing", failure.message ?: "PNG read failed"),
          )
        dispatchPreparedNext(daemon, key, failed.next)
        return
      }
    val transition =
      popHeadAndPrepareNext(
        daemon,
        key,
        RenderOutcome.Finished(pngPath, pngBytes, params?.get("workTrace")),
      )
    val completedGroup = transition.completed
    // 2. Refresh the data-product cache for this URI: attached kinds are fresh; missing kinds are
    //    dropped (the daemon stops attaching unsubscribed kinds, so a cached payload would go
    //    stale).
    refreshDataProductCache(daemon, previewId, params["dataProducts"])
    // 3. Build the matching URI and notify subscribers + watchers.
    val entry = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.get(previewId)
    val uri =
      PreviewUri(
        workspaceId = daemon.workspaceId,
        modulePath = daemon.modulePath,
        previewFqn = previewId,
        config = entry?.config,
      )
    val uriStr = uri.toUri()
    previewActivity.rendered(uriStr, pngPath)
    val notifications = mutableMapOf<String, MutableSet<Session>>()
    if (!completedGroup?.notificationUris.isNullOrEmpty()) {
      completedGroup!!.notificationUris.forEach { updatedUri ->
        notifications
          .getOrPut(updatedUri) { mutableSetOf() }
          .addAll(subscriptions.sessionsSubscribedTo(updatedUri))
      }
    } else if (completedGroup?.overrides == null) {
      notifications
        .getOrPut(uriStr) { mutableSetOf() }
        .addAll(subscriptions.sessionsSubscribedTo(uriStr))
    } else {
      subscriptions.subscribedUrisMatching(uri).forEach { (subscribedUri, targets) ->
        val parsed = PreviewUri.parseOrNull(subscribedUri) ?: return@forEach
        val rawOverrides = parsed.overridesJson ?: return@forEach
        val subscribedOverrides =
          runCatching { decodePreviewOverrides(json.parseToJsonElement(rawOverrides)) }.getOrNull()
            ?: return@forEach
        if (subscribedOverrides == completedGroup.overrides) {
          notifications.getOrPut(subscribedUri) { mutableSetOf() }.addAll(targets)
        }
      }
    }
    notifications.getOrPut(uriStr) { mutableSetOf() }.addAll(subscriptions.sessionsWatching(uri))
    notifications.forEach { (updatedUri, targets) ->
      targets.forEach { it.notifyResourceUpdated(updatedUri) }
    }
    // 4. Record history (no-op default).
    runCatching { historyStore.record(uri, pngPath, Instant.now()) }
    // Dispatch only after the completed generation's exact resource updates are visible. This
    // preserves notification ordering when another same-overrides file-change generation is queued.
    dispatchPreparedNext(daemon, key, transition.next)
  }

  /**
   * Replaces [dataProductCache] entries for `(daemon, previewId)` with [attachmentsField]. A
   * malformed entry skips itself. Null or empty (the common case) evicts every entry for the URI.
   */
  private fun refreshDataProductCache(
    daemon: SupervisedDaemon,
    previewId: String,
    attachmentsField: JsonElement?,
  ) {
    // Drop everything the cache had for this preview — the daemon's latest render is the truth.
    dataProductCache.keys.removeIf {
      it.workspaceId == daemon.workspaceId &&
        it.modulePath == daemon.modulePath &&
        it.previewId == previewId
    }
    val arr = attachmentsField as? kotlinx.serialization.json.JsonArray ?: return
    for (elem in arr) {
      val obj = elem as? JsonObject ?: continue
      val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: continue
      val schemaVersion =
        obj["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
      val payload = obj["payload"]
      val path = obj["path"]?.jsonPrimitive?.contentOrNull
      val extras = obj["extras"]
      val key = DataAttachKey(daemon.workspaceId, daemon.modulePath, previewId, kind)
      dataProductCache[key] = DataAttachmentEntry(schemaVersion, payload, path, extras)
    }
  }

  /**
   * Drops every cached attachment for `(workspace, module)` (from `onClose` and the
   * `classpathDirty` respawn path), since the payloads belong to a renderer that no longer exists.
   */
  private fun evictDataProductsForDaemon(workspaceId: WorkspaceId, modulePath: String) {
    dataProductCache.keys.removeIf { it.workspaceId == workspaceId && it.modulePath == modulePath }
  }

  private fun onRenderFailed(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val errorObj = params["error"] as? JsonObject
    val kind = errorObj?.get("kind")?.jsonPrimitive?.contentOrNull ?: "unknown"
    val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "no message"
    // Carry the daemon's classified one-line remediation through to the agent-facing error.
    val suggestion = errorObj?.get("suggestion")?.jsonPrimitive?.contentOrNull
    // Same pop-and-promote shape as `onRenderFinished`. A failed head does not fail queued groups:
    // different overrides may succeed (e.g. a composable that fails only at small widths).
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
    val transition =
      popHeadAndPrepareNext(daemon, key, RenderOutcome.Failed(kind, message, suggestion))
    dispatchPreparedNext(daemon, key, transition.next)
  }

  /**
   * Per PROTOCOL.md § 6, the daemon emits `classpathDirty` once and exits within
   * [`daemon.classpathDirtyGraceMs`][..] (default 2000ms). Here we:
   * 1. Forget the dying daemon so the next `daemonFor` spawns afresh.
   * 2. Purge cached state (catalog, propagator memo, in-flight waiters); the new daemon's synthetic
   *    initial discovery repopulates the catalog.
   * 3. Send `notifications/resources/list_changed`.
   * 4. Schedule a respawn on [daemonLifecycleExecutor], off the dying reader thread. If the on-disk
   *    descriptor is itself stale, the new daemon dirties again; we stop after one self-loop.
   */
  /**
   * `historyAdded` carries one new [HistoryEntry] per render. Per HISTORY.md § Subscriptions, only
   * interested sessions get `list_changed`: subscribers to the matching live URI, and sessions
   * whose watch set covers it. A malformed payload (no parseable previewId) falls back to
   * broadcasting.
   */
  private fun onHistoryAdded(daemon: SupervisedDaemon, params: JsonObject?) {
    val entry = params?.get("entry") as? JsonObject
    val previewFqn = entry?.get("previewId")?.jsonPrimitive?.contentOrNull
    if (previewFqn == null) {
      // Degraded fallback: tell everyone.
      sessions.forEach { it.notifyResourceListChanged() }
      return
    }
    val configValue =
      (entry["previewMetadata"] as? JsonObject)?.get("config")?.jsonPrimitive?.contentOrNull
    val liveUri =
      PreviewUri(
        workspaceId = daemon.workspaceId,
        modulePath = daemon.modulePath,
        previewFqn = previewFqn,
        config = configValue,
      )
    val liveUriStr = liveUri.toUri()
    val targets = mutableSetOf<Session>()
    targets.addAll(subscriptions.sessionsSubscribedTo(liveUriStr))
    targets.addAll(subscriptions.sessionsWatching(liveUri))
    targets.forEach { it.notifyResourceListChanged() }
  }

  private fun onClasspathDirty(daemon: SupervisedDaemon, params: JsonObject?) {
    val detail = params?.get("detail")?.jsonPrimitive?.contentOrNull ?: "<no detail>"
    val reason = params?.get("reason")?.jsonPrimitive?.contentOrNull ?: "<no reason>"
    System.err.println(
      "DaemonMcpServer: classpathDirty for ${daemon.workspaceId}/${daemon.modulePath} " +
        "(reason=$reason): $detail"
    )

    val workspaceId = daemon.workspaceId
    val modulePath = daemon.modulePath

    // Fail every in-flight and queued waiter for this daemon: it is exiting and won't send
    // `renderFinished`, which is the only trigger for dispatching the next group.
    val matchingKeys =
      previewQueues.keys.filter { it.workspaceId == workspaceId && it.modulePath == modulePath }
    matchingKeys.forEach { key ->
      val drained = previewQueues.remove(key) ?: return@forEach
      val outcome = RenderOutcome.Failed("classpathDirty", "daemon exiting: $detail")
      drained.forEach { group -> group.futures.forEach { it.complete(outcome) } }
    }

    // With `replicasPerDaemon > 0`, several replicas may emit `classpathDirty`; only the first
    // `forgetDaemon` returns true, so the respawn is scheduled once.
    val firstClassedDirty = supervisor.forgetDaemon(workspaceId, modulePath)
    catalog.remove(DaemonAddr(workspaceId, modulePath))
    evictDataProductsForDaemon(workspaceId, modulePath)
    watchPropagator.forget(daemon)
    sessions.forEach { it.notifyResourceListChanged() }
    if (!firstClassedDirty) return

    // Track respawn attempts so a permanently-stale descriptor (one whose own classpath
    // fingerprint disagrees with reality) doesn't loop forever.
    val attemptKey = DaemonAddr(workspaceId, modulePath)
    val attempts = respawnAttempts.merge(attemptKey, 1) { a, b -> a + b } ?: 1
    if (attempts > MAX_RESPAWN_ATTEMPTS_PER_LIFETIME) {
      System.err.println(
        "DaemonMcpServer: respawn attempt cap reached for $workspaceId/$modulePath " +
          "($attempts > $MAX_RESPAWN_ATTEMPTS_PER_LIFETIME); giving up. " +
          "Re-run `./gradlew $modulePath:composePreviewDaemonStart` and call `register_project` " +
          "again to retry."
      )
      return
    }

    daemonLifecycleExecutor.execute {
      val outcome = runCatching {
        supervisor.daemonFor(workspaceId, modulePath)
      }
        .onFailure {
          System.err.println(
            "DaemonMcpServer: classpathDirty respawn failed for $workspaceId/$modulePath: " +
              "${it.message}"
          )
        }
      if (outcome.isSuccess) {
        // Reset the attempt counter on a clean respawn — a future classpathDirty starts fresh.
        respawnAttempts.remove(attemptKey)
      }
    }
  }

  // Internals.

  private data class DaemonAddr(val workspaceId: WorkspaceId, val modulePath: String)

  private data class PreviewEntry(
    val fqn: String,
    val displayName: String?,
    val config: String?,
    val sourceFile: String?,
    /** Canonical source path resolved once when discovery updates this entry. */
    val resolvedSourcePath: String? = null,
    /**
     * Bare `@Composable` method name of the `@Preview` function (wire field `functionName`).
     * Variant previews have synthetic ids (`…WeatherForecast_Light`) but share this name. `null`
     * for entries that don't send it. Used by [generateRecordingTestSource] to emit a compilable
     * call.
     */
    val functionName: String? = null,
    /** One-based declaration anchor from discovery, when the backend can provide it. */
    val bodyLine: Int? = null,
    val sourceLastModifiedMs: Long? = null,
    /**
     * SHA-256 of the source captured at discovery and refreshed whenever
     * [ensureSourceFreshBeforeRender] fires `fileChanged`, to catch content-only edits mtime
     * misses. Null until first hashed.
     */
    val sourceContentHash: String? = null,
  )

  /** Session-local comparison identity for path-shaped renders. */
  private data class FileRenderKey(val uri: String, val overrides: PreviewOverrides?)

  /**
   * Queue key for [previewQueues]. Overrides deliberately aren't part of it: groups within the
   * queue discriminate by them, which is what lets renders of one preview serialize.
   */
  private data class PreviewIdKey(
    val workspaceId: WorkspaceId,
    val modulePath: String,
    val previewId: String,
  )

  /**
   * One batch of waiters sharing `PreviewOverrides` in a [previewQueues] entry. `sent` once its
   * `renderNow` is issued (always true for the head). `futures` is copy-on-write because fanout
   * iterates outside the compute lambda while dedup appends inside it.
   */
  private class PendingRenderGroup(
    val overrides: PreviewOverrides?,
    val futures:
      java.util.concurrent.CopyOnWriteArrayList<
        java.util.concurrent.CompletableFuture<RenderOutcome>
      > =
      java.util.concurrent.CopyOnWriteArrayList(),
    val notificationUris: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    @Volatile var sent: Boolean = false,
  )

  private data class RenderQueueTransition(
    val completed: PendingRenderGroup?,
    val next: PendingRenderGroup?,
  )

  /** Cache key for [dataProductCache]: the preview plus the data-product `kind`. */
  private data class DataAttachKey(
    val workspaceId: WorkspaceId,
    val modulePath: String,
    val previewId: String,
    val kind: String,
  )

  /**
   * Cached `(payload | path)` from one `renderFinished.dataProducts[*]` entry, with `schemaVersion`
   * and `extras` so a cache hit matches a direct `data/fetch`.
   */
  private data class DataAttachmentEntry(
    val schemaVersion: Int,
    val payload: JsonElement?,
    val path: String?,
    val extras: JsonElement? = null,
  )

  private data class WatchReadiness(
    val modulePath: String,
    val spawned: Boolean,
    val discoveryReady: Boolean,
    val previewCount: Int,
  )

  private sealed interface RenderOutcome {
    data class Finished(
      val pngPath: String,
      val pngBytes: ByteArray,
      /** The daemon's per-render work trace, when it sends one; see [EditCycleWork]. */
      val daemonTrace: JsonElement? = null,
    ) : RenderOutcome

    data class Failed(
      val kind: String,
      val message: String,
      /**
       * One-line remediation the daemon classified for a recognised failure (e.g. classpath skew,
       * Robolectric SDK mismatch); `null` when none.
       */
      val suggestion: String? = null,
    ) : RenderOutcome
  }

  private fun parseSchema(s: String): JsonElement = json.parseToJsonElement(s)

  /**
   * Sends `notifications/progress` to [session] every [PROGRESS_BEAT_INTERVAL_MS] until [future]
   * completes; returns the handle to cancel. No-op without [session] or [progressToken]. Progress
   * is elapsed ms (the daemon exposes no render progress); total is unset.
   */
  private fun startProgressBeatIfNeeded(
    session: Session?,
    progressToken: JsonElement?,
    future: java.util.concurrent.CompletableFuture<*>,
    uri: PreviewUri,
  ): java.util.concurrent.ScheduledFuture<*>? {
    if (session == null || progressToken == null) return null
    val start = System.currentTimeMillis()
    return progressBeatExecutor.scheduleAtFixedRate(
      {
        if (future.isDone) return@scheduleAtFixedRate
        runCatching {
          val elapsed = (System.currentTimeMillis() - start).toDouble()
          session.notifyProgress(
            token = progressToken,
            progress = elapsed,
            message = "rendering ${uri.previewFqn}",
          )
        }
      },
      PROGRESS_BEAT_INTERVAL_MS,
      PROGRESS_BEAT_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun detectBranch(workspacePath: File): String? {
    val head = File(workspacePath, ".git/HEAD").takeIf { it.isFile } ?: return null
    val content =
      runCatching { fileSystem.read(head.path.toPath()) { readUtf8() }.trim() }.getOrNull()
        ?: return null
    return if (content.startsWith("ref:"))
      content.removePrefix("ref:").trim().substringAfterLast('/')
    else content.take(8)
  }

  private fun applyImageSizeOverride(pngBytes: ByteArray): ByteArray {
    val maxEdgePx = imageSizeOverride.maxEdgePx ?: return pngBytes
    return scaleToMaxEdge(pngBytes, maxEdgePx)
  }

  /**
   * The inline image the model reads: long edge at most [maxEdgePx] (never upscaled). The file on
   * disk and the preview resource keep the full-size render.
   */
  private fun scaleToMaxEdge(pngBytes: ByteArray, maxEdgePx: Int): ByteArray {
    val source = runCatching { ImageIO.read(pngBytes.inputStream()) }.getOrNull() ?: return pngBytes
    if (source.width <= maxEdgePx && source.height <= maxEdgePx) return pngBytes
    val scale = minOf(maxEdgePx.toDouble() / source.width, maxEdgePx.toDouble() / source.height)
    val targetWidth = maxOf(1, kotlin.math.floor(source.width * scale).toInt())
    val targetHeight = maxOf(1, kotlin.math.floor(source.height * scale).toInt())
    val target =
      java.awt.image.BufferedImage(
        targetWidth,
        targetHeight,
        java.awt.image.BufferedImage.TYPE_INT_ARGB,
      )
    val g = target.createGraphics()
    try {
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_RENDERING,
        java.awt.RenderingHints.VALUE_RENDER_QUALITY,
      )
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
      )
      g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
      g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(target, "png", out)
    return out.toByteArray()
  }

  private data class ImageSizeOverride(val maxEdgePx: Int?) {
    companion object {
      fun detect(env: Map<String, String> = System.getenv()): ImageSizeOverride {
        // Claude Code frequently accumulates many screenshots in one request; Anthropic enforces a
        // 2000px/dimension cap on many-image requests there, so pre-scale defensively.
        if (
          !env["CLAUDE_CODE_SESSION_ID"].isNullOrBlank() || !env["CLAUDE_ENV_FILE"].isNullOrBlank()
        ) {
          return ImageSizeOverride(maxEdgePx = 2000)
        }
        if (
          env["__CFBundleIdentifier"] == "com.google.antigravity" ||
            !env["ANTIGRAVITY_CLI_ALIAS"].isNullOrBlank()
        ) {
          return ImageSizeOverride(maxEdgePx = 3072)
        }
        if (!env["CODEX_SANDBOX"].isNullOrBlank() || !env["CODEX_SESSION_ID"].isNullOrBlank()) {
          return ImageSizeOverride(maxEdgePx = 3072)
        }
        return ImageSizeOverride(maxEdgePx = null)
      }
    }
  }

  companion object {
    /** [CompileWork.trigger]: `notify_file_changed` asked for the recompile. */
    const val TRIGGER_NOTIFY = "notify"

    /** [CompileWork.trigger]: a render found the change itself. */
    const val TRIGGER_DETECTED = "detected"

    /** [CompileWork.task] of an in-process (Build Tools API) compile inside the daemon. */
    const val IN_PROCESS_COMPILE_TASK = "daemon:compileSources"

    /** Set to `1` to try the daemon's in-process compile before Gradle; see `compileInProcess`. */
    const val COMPILE_IN_PROCESS_ENV = "COMPOSE_PREVIEW_COMPILE_IN_PROCESS"
    private val IN_PROCESS_COMPILE_TIMEOUT = 120.seconds

    const val MCP_APP_VIEWER_URI: String = "ui://compose-preview/viewer"
    const val MCP_APP_MIME_TYPE: String = "text/html;profile=mcp-app"
    private const val MCP_APP_VIEWER_ASSET: String = "compose-preview-viewer.html"

    /**
     * `clientInfo.name` Antigravity sends; it gets the file result and a preview card by default.
     */
    private const val ANTIGRAVITY_CLIENT_NAME: String = "antigravity-client"

    /** Largest static result a preview card embeds (UTF-8 bytes), as in compose-preview-card.py. */
    private const val MAX_CARD_RESULT_BYTES: Int = 500_000

    /** A directory holding one of these is a Gradle build the server may auto-register. */
    /** Tools that take a `project` path and register its build on the fly. */
    private val PROJECT_ARGUMENT_TOOLS =
      setOf("render_preview", "render_matrix", "find_previews_for_file")

    /**
     * `clientInfo.name`s of agent harnesses that read local files: `render_preview` defaults to the
     * file result for them, inline for everyone else.
     */
    internal val FILE_RESULT_CLIENT_NAMES: Set<String> =
      setOf(
        "opencode",
        "codex-mcp-client",
        "gemini-cli-mcp-client",
        "claude-code",
        ANTIGRAVITY_CLIENT_NAME,
      )

    /** An elicitation answered faster than this was never shown to a person. */
    internal const val UNSEEN_ANSWER_MS: Long = 1_000

    /**
     * `variantChoice.message` when a preview chooser was cancelled and the first match rendered.
     */
    internal const val CANCELLED_CHOICE_MESSAGE: String =
      "The preview chooser was cancelled; the first match was rendered. Do not re-open it; list " +
        "the choices and let the user pick."

    /** The one-line remedy `render_preview` gives when it can't serve `compose/semantics`. */
    internal const val SEMANTICS_UNAVAILABLE_FIX: String =
      "list_data_products shows what this daemon serves; if compose/semantics is missing, " +
        "update the compose-preview plugin/CLI, whose renderer lacks it. Pass observe=png to " +
        "ask for the image directly; crop by ref/testTag and diff_semantics need semantics."

    /** Short `initialize` instructions for the local server. */
    internal const val LOCAL_INSTRUCTIONS: String =
      "Renders the person's own Compose @Preview functions from their Gradle workspace, " +
        "registered automatically on first use (call register_project only if list_projects " +
        "stays empty).\n" +
        "Render one with render_preview preview=<FunctionName> (a function name or FQN suffix). " +
        "To find a file's previews use find_previews_for_file; never search " +
        "source files for a preview ID.\n" +
        "render_preview returns the semantics tree by default, observe=png the image and " +
        "observe=hash only the sha256; describe an image rather than re-encoding or re-saving " +
        "it. Use inline=false (the PNG's pngPath) for sweeps.\n" +
        "Library components (Material, Wear and published catalogs) are on the hosted catalog " +
        "server, not here.\n" +
        "Never fake a render: don't hand-build an HTML, CSS or SVG mock of a preview; " +
        "if rendering fails, report the error."

    /** The client-specific last line of the `initialize` instructions, or null. */
    internal fun localInstructionsTail(clientName: String?): String? =
      when (clientName) {
        ANTIGRAVITY_CLIENT_NAME ->
          "Show a render to the person by pasting the <agent-embed> line render_preview " +
            "returns (it writes cardPath beside pngPath) into your reply."
        in FILE_RESULT_CLIENT_NAMES ->
          "render_preview returns pngPath rather than the image here; open it with your " +
            "file-read tool to see the render."
        else -> null
      }

    /** [LOCAL_INSTRUCTIONS] plus the tail for [clientName]. */
    internal fun localInstructionsFor(clientName: String?): String =
      localInstructionsTail(clientName)?.let { "$LOCAL_INSTRUCTIONS\n$it" } ?: LOCAL_INSTRUCTIONS

    /** Tools whose existing text output gains an optional, portable MCP Apps presentation. */
    private val VIEWER_TOOL_NAMES =
      setOf(
        "render_preview",
        "render_matrix",
        "diff_semantics",
      )

    /** Long edge of the inline image the model reads by default; `imageScale="full"` skips it. */
    private const val INLINE_MAX_EDGE_PX: Int = 768

    /** Long edge of each contact-sheet cell. */
    private const val CONTACT_SHEET_CELL_EDGE_PX: Int = 256

    /** Most `@Preview` variants one `render_matrix` (no axes) renders. */
    private const val MAX_VARIANT_CELLS: Int = 12

    /** Tools the viewer calls through the MCP Apps bridge. */
    private val APP_TOOL_NAMES =
      setOf("render_preview", "render_preview_overlay", "get_preview_data")

    /** Tools that accept an `overrides` argument, so an override-bearing `uri` can be folded in. */
    private val URI_OVERRIDE_TOOLS =
      setOf(
        "render_preview",
        "render_preview_overlay",
        "record_preview",
        "run_extension_command",
      )

    /**
     * Cap on consecutive `classpathDirty` self-loops before respawning stops. One retry covers a
     * re-run of `composePreviewDaemonStart` between the event and the respawn; more would just
     * thrash on a stale descriptor.
     */
    private const val MAX_RESPAWN_ATTEMPTS_PER_LIFETIME: Int = 1

    /** Cadence for progress beats during a slow `resources/read`. */
    private const val PROGRESS_BEAT_INTERVAL_MS: Long = 500
    private const val MAX_CACHED_FILE_RENDERS = 128

    /**
     * If the full MCP tool catalog is still loading after this grace period, clients should keep
     * using the bootstrap tools and refresh `tools/list` after `notifications/tools/list_changed`.
     */
    private const val TOOL_CATALOG_NOTIFY_DELAY_MS: Long = 3_000

    /**
     * Worker count for [daemonLifecycleExecutor]: a few parallel cold starts without thrashing the
     * host; matches the supervisor's replica-spawn pool cap.
     */
    private const val DAEMON_LIFECYCLE_THREADS: Int = 4

    /** Worker count for follow-up render dispatches; matches the daemon lifecycle pool cap. */
    private const val RENDER_DISPATCH_THREADS: Int = 4

    /** Suggested delay before polling `watch(awaitDiscovery=false)` readiness again. */
    private const val WATCH_DISCOVERY_RETRY_AFTER_MS: Long = 500

    /** Overrides [DEFAULT_CALL_BUDGET_MS]; see `callBudgetMs`. */
    const val CALL_BUDGET_ENV = "COMPOSE_PREVIEW_MCP_CALL_BUDGET_MS"

    /** Below the ~60 s at which Claude Desktop and Claude Code abort a request. */
    const val DEFAULT_CALL_BUDGET_MS: Long = 45_000

    /** `retryAfterMs` of a `pending` render result; the retry itself waits up to the budget. */
    private const val PENDING_CALL_RETRY_AFTER_MS: Long = 1_000

    /** How long a budgeted call's finished result waits for its retry before it is dropped. */
    private const val UNCOLLECTED_CALL_RESULT_TTL_MS: Long = 60_000

    /**
     * Default source-freshness poll cadence: cheap (a stat, occasionally a SHA-256, per preview)
     * yet fresh by the next render. Override via `sourcePollIntervalMs`; `0` disables.
     */
    const val DEFAULT_SOURCE_POLL_INTERVAL_MS: Long = 30_000

    /**
     * How often a `coalesced:` rejection is retried, and the linear backoff step (about 2 s in
     * all). The daemon clears the previous override render well within that.
     */
    /** Directories the name-miss source scan never enters; and how many `.kt` files it reads. */
    private val SOURCE_SCAN_SKIPPED_DIRS =
      setOf("build", ".gradle", ".git", ".idea", "node_modules", ".kotlin", "out")
    private const val MAX_SOURCE_SCAN_FILES = 20_000

    internal const val RENDER_NOW_COALESCED_RETRIES: Int = 8
    internal const val RENDER_NOW_RETRY_BACKOFF_MS: Long = 50

    /** Source extensions whose edit needs a recompile before the daemon can render it. */
    private val COMPILED_SOURCE_EXTENSIONS = setOf("kt", "java")

    /**
     * Default sampling-probe cadence: well under 1% of render work, enough samples to spot flaky
     * previews. Override via `samplingIntervalMs`; `0` disables.
     */
    const val DEFAULT_SAMPLING_INTERVAL_MS: Long = 10 * 60_000

    /**
     * Default `kind` for `render_preview_overlay`. `a11y/overlay` is the only image-bearing kind
     * today; future PNG-extras kinds work without changes here.
     */
    private const val DEFAULT_OVERLAY_KIND: String = "a11y/overlay"

    /** ATF findings, fetched for `render_preview`'s `details: ["a11y"]`. */
    private const val A11Y_FINDINGS_KIND: String = "a11y/atf"

    /** The daemon extension that produces [A11Y_FINDINGS_KIND] and [DEFAULT_OVERLAY_KIND]. */
    private const val A11Y_EXTENSION_ID: String = "a11y"
    private const val A11Y_HIERARCHY_KIND: String = "a11y/hierarchy"

    /**
     * Layout sources for `details: ["layout"]`, in order of preference. Each is also the id of the
     * daemon extension that produces it.
     */
    private val LAYOUT_DETAIL_KINDS: List<String> = listOf("layout/inspector", "compose/semantics")
  }
}

/**
 * The preview function starting near 1-based [line] (its annotation or `fun`): from there to the
 * brace that closes its body, capped at [maxLines]. Null when [line] is unknown.
 */
internal fun previewFunctionSource(lines: List<String>, line: Int?, maxLines: Int = 200): String? {
  val start = ((line ?: return null) - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
  if (lines.isEmpty()) return null
  var depth = 0
  var opened = false
  val out = mutableListOf<String>()
  for (index in start until minOf(lines.size, start + maxLines)) {
    val text = lines[index]
    out += text
    text.forEach { ch ->
      if (ch == '{') {
        depth++
        opened = true
      } else if (ch == '}') depth--
    }
    if (opened && depth <= 0) break
  }
  return out.joinToString("\n")
}
