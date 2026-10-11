package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DaemonClientFactory
import ee.schimke.composeai.daemon.client.DaemonSpawn
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.BackendKind
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Owner of every per-(workspace, module) [DaemonClient] in this MCP server process.
 * Multi-workspace, so one server can host previews from several projects (including two worktrees
 * of one repo). Workspaces are registered explicitly (`register_project` or `--project`); daemons
 * spawn lazily on first reference. Every daemon's notifications are demultiplexed by method through
 * the [NotificationRouter].
 */
class DaemonSupervisor(
  private val descriptorProvider: DescriptorProvider,
  private val clientFactory: DaemonClientFactory,
  private val router: NotificationRouter = NotificationRouter(),
  /**
   * Concurrent render slots per (workspace, module) beyond the first (SANDBOX-POOL.md). Passed as
   * `composeai.daemon.sandboxCount = 1 + replicasPerDaemon`; the daemon's
   * [`RobolectricHost`][ee.schimke.composeai.daemon.RobolectricHost] hosts one sandbox and spawns a
   * worker JVM per remaining slot (Robolectric allows one sandbox per process). Each extra slot
   * costs a JVM; `0` keeps a single sandbox. The wire protocol is unchanged: one daemon process per
   * module.
   */
  private val replicasPerDaemon: Int = DEFAULT_REPLICAS_PER_DAEMON,
  /**
   * How long [spawn] waits for `initialize`. A Robolectric daemon reads nothing until its first
   * sandbox is up (~45s for a Wear OS module), so the client's 30s default failed the handshake and
   * left cached capabilities empty. See [DEFAULT_INITIALIZE_TIMEOUT].
   */
  private val initializeTimeout: Duration = DEFAULT_INITIALIZE_TIMEOUT,
  /**
   * Kinds passed through `initialize.options.attachDataProducts` to every daemon ("always-on"
   * products). Empty keeps the field absent. No production entry point sets it; kept for embedders
   * and tests.
   */
  private val globalAttachDataProducts: List<String> = emptyList(),
  /**
   * Extension ids enabled on every daemon right after `initialize` (`extensions/enable`,
   * PROTOCOL.md § 3a). Daemons start with everything inactive; empty keeps a baseline daemon lean.
   * Passed through verbatim; unknown ids are logged, not retried.
   */
  private val defaultExtensions: List<String> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * Where registrations live beyond this object: [project] and [daemonFor] fall back to it for
   * unknown ids. In memory by default; [DaemonMcpMain] passes the persistent one, shared with
   * sibling processes.
   */
  val workspaceStore: WorkspaceStore = WorkspaceStore(file = null),
  /**
   * How many registered builds may have live daemons at once (`0` = unlimited). Starting another
   * build's first daemon stops the least recently used builds beyond the limit, so moving between
   * samples of a multi-build repo doesn't leave each one running. Stopped builds respawn on their
   * next render. [DaemonMcpMain] passes [DEFAULT_MAX_ACTIVE_PROJECTS].
   */
  private val maxActiveProjects: Int = 0,
) {

  init {
    require(replicasPerDaemon >= 0) { "replicasPerDaemon must be >= 0, got $replicasPerDaemon" }
  }

  private val projects = ConcurrentHashMap<WorkspaceId, RegisteredProject>()

  /** When each project last had a daemon asked for, for [retireIdleProjects]. */
  private val lastUsedNanos = ConcurrentHashMap<WorkspaceId, Long>()

  /**
   * Registers a project at [absolutePath] and returns its [WorkspaceId]; idempotent per canonical
   * path. [rootProjectName] gives nicer ids (default: the directory name). [knownModules] are
   * advertised up front (daemons still spawn lazily).
   */
  fun registerProject(
    absolutePath: File,
    rootProjectName: String? = null,
    knownModules: List<String> = emptyList(),
  ): RegisteredProject {
    require(absolutePath.isDirectory) {
      "registerProject: path '${absolutePath.absolutePath}' is not a directory"
    }
    val canonical = runCatching {
      absolutePath.canonicalFile
    }
      .getOrDefault(absolutePath.absoluteFile)
    val name =
      rootProjectName?.takeIf { it.isNotBlank() }
        ?: canonical.name.takeIf { it.isNotBlank() }
        ?: "workspace"
    val workspaceId = WorkspaceId.derive(name, canonical)
    val project =
      projects.computeIfAbsent(workspaceId) {
        RegisteredProject(
          workspaceId = workspaceId,
          rootProjectName = name,
          path = canonical,
          knownModules = knownModules.toMutableList(),
        )
      }
    workspaceStore.remember(workspaceId.value, canonical, name)
    // Idempotent: merge module hints if the second call learned more.
    if (knownModules.isNotEmpty()) {
      synchronized(project.knownModules) {
        for (m in knownModules) if (m !in project.knownModules) project.knownModules.add(m)
      }
    }
    return project
  }

  /**
   * Tears down every daemon for [workspaceId] and forgets the project. Idempotent — unregistering
   * an unknown id is a no-op.
   */
  fun unregisterProject(workspaceId: WorkspaceId) {
    workspaceStore.forget(workspaceId.value)
    removeLiveProject(workspaceId)
  }

  /** Stops and drops this process's live project without changing the shared store. */
  private fun removeLiveProject(workspaceId: WorkspaceId) {
    val project = projects.remove(workspaceId) ?: return
    project.daemons.values.forEach { runCatching { it.shutdown() } }
    project.daemons.clear()
  }

  fun listProjects(): List<RegisteredProject> = projects.values.toList()

  /**
   * Drops live projects another server process removed from the shared [workspaceStore] (e.g. the
   * sidebar app's process after a chat's `unregister_project`). Only ids observed disappearing are
   * removed, so in-memory registrations and failed store writes survive.
   */
  fun forgetProjectsMissingFromStore(): Set<WorkspaceId> {
    val removed =
      workspaceStore.takeExternallyRemovedIds().mapTo(LinkedHashSet(), ::WorkspaceId).filterTo(
        LinkedHashSet()
      ) {
        projects.containsKey(it)
      }
    removed.forEach(::removeLiveProject)
    return removed
  }

  /**
   * The project for [workspaceId], re-registering from [workspaceStore] when not held (a restart,
   * or another process's registration).
   */
  fun project(workspaceId: WorkspaceId): RegisteredProject? =
    projects[workspaceId] ?: restore(workspaceId)

  /**
   * Re-registers every stored workspace whose path is in, under, or above one of [dirs], so a
   * restart's roots bring builds back without `register_project`.
   */
  fun restoreMatching(dirs: List<File>): List<RegisteredProject> {
    val wanted = dirs.map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }
    return workspaceStore
      .all()
      .filter { entry ->
        val path = File(entry.path)
        wanted.any { path.startsWith(it) || it.startsWith(path) }
      }
      .mapNotNull { project(WorkspaceId(it.id)) }
  }

  /** Re-registers a stored id from its path, under the same id; null when unknown or gone. */
  private fun restore(workspaceId: WorkspaceId): RegisteredProject? {
    val entry = workspaceStore.get(workspaceId.value) ?: return null
    val dir = File(entry.path).takeIf(File::isDirectory) ?: return null
    return projects.computeIfAbsent(workspaceId) {
      RegisteredProject(
        workspaceId = workspaceId,
        rootProjectName = entry.name?.takeIf { it.isNotBlank() } ?: dir.name,
        path = runCatching { dir.canonicalFile }.getOrDefault(dir.absoluteFile),
        knownModules = mutableListOf(),
      )
    }
  }

  /**
   * Forgets the [SupervisedDaemon] for [workspaceId] + [modulePath] and shuts down peer replicas
   * (still alive on the stale classpath) for the `classpathDirty` respawn flow. Returns `true` if
   * an entry was removed, so the second of two racing `classpathDirty` events skips the respawn
   * bump.
   */
  fun forgetDaemon(workspaceId: WorkspaceId, modulePath: String): Boolean {
    val project = projects[workspaceId] ?: return false
    val removed = project.daemons.remove(modulePath) ?: return false
    runCatching { removed.shutdown() }
    return true
  }

  /**
   * Returns (and lazily spawns) the daemon for [workspaceId] + [modulePath]. Throws when the
   * workspace isn't registered or the descriptor is missing. The calling thread pays the cold start
   * (3-10s Robolectric, ~600ms desktop).
   */
  fun daemonFor(workspaceId: WorkspaceId, modulePath: String): SupervisedDaemon {
    val project = project(workspaceId) ?: error("workspace not registered: $workspaceId")
    workspaceStore.touch(workspaceId.value)
    lastUsedNanos[workspaceId] = System.nanoTime()
    project.daemons[modulePath]?.let {
      return it
    }
    if (project.daemons.isEmpty()) retireIdleProjects(except = project)
    return project.daemons.computeIfAbsent(modulePath) { spawn(project, modulePath) }
  }

  /**
   * Before [active] starts its first daemon, stop every daemon of the least recently used other
   * builds beyond [maxActiveProjects]. Registrations stay.
   */
  private fun retireIdleProjects(except: RegisteredProject) {
    if (maxActiveProjects <= 0) return
    projects.values
      .filter { it.workspaceId != except.workspaceId && it.daemons.isNotEmpty() }
      .sortedByDescending { lastUsedNanos[it.workspaceId] ?: 0L }
      .drop(maxActiveProjects - 1)
      .forEach { idle ->
        System.err.println(
          "compose-preview-mcp: stopping ${idle.rootProjectName}'s daemons (" +
            "${idle.daemons.keys.sorted().joinToString(", ")}) to start ${except.rootProjectName}; " +
            "at most $maxActiveProjects build(s) render at once"
        )
        idle.daemons.keys.toList().forEach { forgetDaemon(idle.workspaceId, it) }
      }
  }

  /** Closes every daemon. After this call the supervisor is unusable. */
  fun shutdown() {
    projects.values.forEach { project ->
      project.daemons.values.forEach { runCatching { it.shutdown() } }
      project.daemons.clear()
    }
    projects.clear()
  }

  /** Returns the [NotificationRouter] so callers can register handlers. */
  fun router(): NotificationRouter = router

  // Internals.

  private fun spawn(project: RegisteredProject, modulePath: String): SupervisedDaemon {
    val baseDescriptor = descriptorProvider.descriptorFor(project, modulePath)
    // Inject `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` (SANDBOX-POOL.md) into a copy
    // of the descriptor's systemProperties; the descriptor is cached and shared across `daemonFor`
    // calls.
    val descriptor =
      baseDescriptor.withSandboxCount(1 + replicasPerDaemon).let {
        if (replicasPerDaemon > 0) it.withSystemProperty(ON_DEMAND_WORKER_BOOT_PROP, "true") else it
      }
    val supervised = SupervisedDaemon(workspaceId = project.workspaceId, modulePath = modulePath)
    val descriptorWorkingDirectory = File(descriptor.workingDirectory)
    supervised.moduleProjectDirPath =
      runCatching {
        (if (descriptorWorkingDirectory.isAbsolute) descriptorWorkingDirectory
          else File(project.path, descriptor.workingDirectory))
          .canonicalPath
      }
        .getOrElse {
          (if (descriptorWorkingDirectory.isAbsolute) descriptorWorkingDirectory
            else File(project.path, descriptor.workingDirectory))
            .absolutePath
        }

    // Synchronous spawn: the catalog is seeded before `daemonFor` returns. Sandbox bootstrap is
    // sequenced inside the daemon.
    val spawn = clientFactory.spawn(project.workspaceId, descriptor)
    spawn.client(
      onNotification = { method, params ->
        router.dispatch(supervised, method, params)
        // Also fan out to RenderSession listeners registered via
        // `supervised.session.onNotification(...)`.
        supervised.notificationFanout.dispatch(method, params)
      },
      onClose = {
        if (supervised.detachSpawn(spawn)) {
          router.dispatchClose(supervised)
        }
      },
    )
    supervised.attachSpawn(spawn)
    // Capture the workspace root before initialize so `RenderSession.workspaceRoot` is real even
    // before `initializeResult` is set.
    supervised.workspaceRootPath = project.path.absolutePath
    runCatching {
      val result =
        spawn.client.initialize(
          workspaceRoot = project.path.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          attachDataProducts = globalAttachDataProducts.takeIf { it.isNotEmpty() },
          timeout = initializeTimeout,
        )
      // Cache the full result for `RenderSession.initializeResult`; respawns overwrite it.
      supervised.initializeResult = result
      // Daemons start with every extension inactive (PROTOCOL.md § 3a), so enable
      // `defaultExtensions` and cache the updated capability lists from the response.
      val initialDataProducts: List<DataProductCapability>
      val initialDataExtensions: List<ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor>
      if (defaultExtensions.isNotEmpty()) {
        val enableResult = spawn.client.extensionsEnable(defaultExtensions)
        if (enableResult.unknown.isNotEmpty()) {
          System.err.println(
            "daemon ${project.workspaceId}/${descriptor.modulePath}: extensions/enable " +
              "skipped unknown ids ${enableResult.unknown}"
          )
        }
        initialDataProducts = enableResult.dataProducts
        initialDataExtensions = enableResult.dataExtensions
      } else {
        initialDataProducts = result.capabilities.dataProducts
        initialDataExtensions = result.capabilities.dataExtensions
      }
      supervised.dataProductCapabilities = initialDataProducts
      supervised.dataExtensionDescriptors = initialDataExtensions
      // Cache supportedOverrides and knownDevice ids so `DaemonMcpServer.toolRenderPreview` can
      // reject fields the backend ignores and typo'd devices (PROTOCOL.md § 3). Older daemons
      // advertise `[]`, so validation falls open.
      supervised.supportedOverrides = result.capabilities.supportedOverrides.toSet()
      supervised.knownDeviceIds = result.capabilities.knownDevices.map { it.id }.toSet()
      supervised.backendKind = result.capabilities.backend
      // RECORDING.md § "encoded formats" — same pattern. Empty list pre-feature; validation falls
      // open and `record_preview` calls round-trip without the diagnostic.
      supervised.recordingFormats = result.capabilities.recordingFormats.toSet()
      // Cache the manifest path so the background poller can reload it after a
      // `composePreviewDiscover` re-run. Blank for backends without `previews.json`.
      supervised.manifestPath = result.manifest.path.takeIf { it.isNotBlank() }
      // The daemon only emits `discoveryUpdated` deltas; the initial set is in
      // `initialize.manifest.path`. Synthesise an initial `discoveryUpdated` from that file through
      // the router.
      synthesiseInitialDiscovery(supervised, result.manifest.path)
      supervised.initialDiscoveryComplete = true
    }
      .onFailure { e ->
        System.err.println(
          "daemon initialize failed for ${project.workspaceId}/${descriptor.modulePath}: ${e.message}"
        )
      }

    return supervised
  }

  private fun synthesiseInitialDiscovery(daemon: SupervisedDaemon, manifestPath: String) {
    if (manifestPath.isBlank()) return
    val file = File(manifestPath)
    if (!file.isFile) return
    val previews =
      runCatching {
        val text = fileSystem.read(file.path.toPath()) { readUtf8() }
        val arr =
          (Json.parseToJsonElement(text) as? JsonObject)?.get("previews")
            as? kotlinx.serialization.json.JsonArray ?: return@runCatching null
        arr.mapNotNull { it as? JsonObject }
      }
        .getOrNull() ?: return
    if (previews.isEmpty()) return
    val params =
      kotlinx.serialization.json.buildJsonObject {
        put("added", kotlinx.serialization.json.JsonArray(previews))
        put("removed", kotlinx.serialization.json.JsonArray(emptyList()))
        put("changed", kotlinx.serialization.json.JsonArray(emptyList()))
        put("totalPreviews", kotlinx.serialization.json.JsonPrimitive(previews.size))
      }
    router.dispatch(daemon, "discoveryUpdated", params)
  }

  companion object {
    /**
     * Default [replicasPerDaemon]: 5 sandboxes per daemon so a preview grid renders concurrently.
     * Each sandbox beyond the first is a JVM (SANDBOX-POOL.md). Override via `--replicas-per-daemon
     * N` or `composeai.mcp.replicasPerDaemon`.
     */
    const val DEFAULT_REPLICAS_PER_DAEMON: Int = 4

    /**
     * Default [initializeTimeout], long enough for a cold Robolectric boot on a busy machine.
     * Override via `composeai.mcp.initializeTimeoutSeconds` or
     * `COMPOSE_PREVIEW_INITIALIZE_TIMEOUT_SECONDS`.
     */
    val DEFAULT_INITIALIZE_TIMEOUT: Duration = 120.seconds

    /**
     * Default replicas for [cores] processors: half the cores less the primary, capped at
     * [DEFAULT_REPLICAS_PER_DAEMON]. Replica boots compete with the first renders, so 4 cores get 1
     * and 8 get 3. Overridable as above.
     */
    fun defaultReplicasFor(cores: Int): Int =
      (cores / 2 - 1).coerceIn(0, DEFAULT_REPLICAS_PER_DAEMON)

    /** [maxActiveProjects] for the standalone server: one build renders at a time. */
    const val DEFAULT_MAX_ACTIVE_PROJECTS: Int = 1

    /**
     * Daemon property (`DaemonProperties.onDemandWorkerBoot`): worker JVMs boot when two different
     * previews render at once rather than at start, where they competed with the first compile.
     * Older daemons ignore it.
     */
    const val ON_DEMAND_WORKER_BOOT_PROP: String = "composeai.daemon.onDemandWorkerBoot"
  }
}

/**
 * One registered project: canonical path, assigned id, lazily populated daemon map, and optional
 * seed module list.
 */
data class RegisteredProject(
  val workspaceId: WorkspaceId,
  val rootProjectName: String,
  val path: File,
  val knownModules: MutableList<String>,
  val daemons: ConcurrentHashMap<String, SupervisedDaemon> = ConcurrentHashMap(),
)

/**
 * A live daemon owned by [DaemonSupervisor]: one process per (workspaceId, modulePath), with
 * concurrent capacity from its own sandbox pool (SANDBOX-POOL.md). [client], [allClients] and
 * [clientForRender] keep their multi-replica shapes for source compatibility.
 */
class SupervisedDaemon(val workspaceId: WorkspaceId, val modulePath: String) {

  /**
   * The single [DaemonSpawn], set by [attachSpawn] and cleared by [detachSpawn] / [shutdown].
   * `@Volatile`: written on the reader thread, read from caller threads.
   */
  @Volatile private var spawn: DaemonSpawn? = null

  /**
   * True once initialize completed and the catalog was seeded from the initial manifest. A
   * discovery-complete daemon may have zero previews, so pair this with the catalog's count.
   */
  @Volatile
  var initialDiscoveryComplete: Boolean = false
    internal set

  /**
   * Kinds advertised via `initialize.capabilities.dataProducts`, set by [DaemonSupervisor.spawn];
   * read by `DaemonMcpServer.toolListDataProducts` without a round trip.
   */
  @Volatile
  var dataProductCapabilities: List<DataProductCapability> = emptyList()
    internal set

  /**
   * `PreviewOverrides` fields this daemon applies (PROTOCOL.md § 3), set at spawn; used by
   * `DaemonMcpServer.toolRenderPreview` to reject ignored fields. Empty on older daemons
   * (validation falls open).
   */
  @Volatile
  var supportedOverrides: Set<String> = emptySet()
    internal set

  /**
   * `device` ids the daemon's catalog recognises (`ServerCapabilities.knownDevices`), used to
   * reject typo'd devices. `spec:` geometry isn't enumerable and passes through.
   */
  @Volatile
  var knownDeviceIds: Set<String> = emptySet()
    internal set

  /** Renderer backend advertised in `InitializeResult.capabilities.backend`. */
  @Volatile
  var backendKind: BackendKind? = null
    internal set

  @Volatile
  var dataExtensionDescriptors: List<ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor> =
    emptyList()
    internal set

  /**
   * Recording formats the daemon can encode (RECORDING.md), used by
   * `DaemonMcpServer.toolRecordPreview` to reject others up front. Empty on older daemons (falls
   * open).
   */
  @Volatile
  var recordingFormats: Set<String> = emptySet()
    internal set

  /**
   * Path to the module's `previews.json`, from `InitializeResult.manifest.path`. The background
   * poller stats it so a `composePreviewDiscover` re-run reaches the catalog without a restart.
   * Null/blank when not advertised.
   */
  @Volatile
  var manifestPath: String? = null
    internal set

  /** The single [DaemonClient], used for everything. Throws before [attachSpawn] has run. */
  val client: DaemonClient
    get() {
      val s = spawn
      check(s != null) { "SupervisedDaemon($workspaceId/$modulePath): no spawn attached yet" }
      return s.client
    }

  /**
   * Cached `initialize` result backing [RenderSession.initializeResult]; set at spawn, cleared by
   * [detachSpawn].
   */
  @Volatile
  internal var initializeResult: ee.schimke.composeai.daemon.protocol.InitializeResult? = null

  /**
   * Canonical workspace root the daemon was spawned against, backing
   * [ee.schimke.composeai.render.session.RenderSession.workspaceRoot]. Cleared by [detachSpawn].
   */
  @Volatile internal var workspaceRootPath: String? = null

  /**
   * The Gradle project directory from the launch descriptor, which differs from [modulePath]'s
   * implied directory when settings.gradle.kts remaps `projectDir`. Discovery source paths resolve
   * against it.
   */
  @Volatile internal var moduleProjectDirPath: String? = null

  /**
   * Notification fan-out for [session] listeners
   * ([ee.schimke.composeai.render.session.RenderSession.onNotification]), alongside the
   * [NotificationRouter].
   */
  internal val notificationFanout: NotificationFanout = NotificationFanout()

  /**
   * Public [RenderSession] view of this daemon, so `:render-session-api` consumers can drive it
   * without the internal [DaemonClient]. `close()` is a no-op (the client may be shared);
   * [shutdown] / [detachSpawn] own teardown. Each access returns a fresh view over shared state;
   * throws until the handshake completes.
   */
  val session: ee.schimke.composeai.render.session.RenderSession
    get() {
      val s = spawn
      check(s != null) { "SupervisedDaemon($workspaceId/$modulePath): no spawn attached yet" }
      val init =
        initializeResult
          ?: error(
            "SupervisedDaemon($workspaceId/$modulePath): initialize handshake hasn't completed"
          )
      val root =
        workspaceRootPath
          ?: error(
            "SupervisedDaemon($workspaceId/$modulePath): workspaceRootPath not captured at spawn"
          )
      return DaemonClientRenderSession(
        workspaceRoot = root,
        modulePath = modulePath,
        initializeResult = init,
        client = s.client,
        notificationFanout = notificationFanout,
      )
    }

  /** Every active client, for fan-out APIs. Always one element; a list for source compatibility. */
  fun allClients(): List<DaemonClient> = spawn?.let { listOf(it.client) } ?: emptyList()

  /**
   * The client for a render keyed on [previewId]: always the single client, since the daemon
   * dispatches across its sandbox slots. [previewId] is kept for future affinity.
   */
  fun clientForRender(@Suppress("UNUSED_PARAMETER") previewId: String): DaemonClient = client

  /**
   * Always 1: one supervised subprocess per daemon; capacity comes from the daemon's own pool. Kept
   * for source compatibility.
   */
  fun replicaCount(): Int = if (spawn != null) 1 else 0

  internal fun attachSpawn(spawn: DaemonSpawn) {
    check(this.spawn == null) {
      "SupervisedDaemon($workspaceId/$modulePath): spawn already attached"
    }
    this.spawn = spawn
  }

  /**
   * Detaches [s] if current. Returns `true` if removed, so callers know whether to run group-level
   * cleanup (e.g. `onClose`).
   */
  internal fun detachSpawn(s: DaemonSpawn): Boolean {
    if (this.spawn !== s) return false
    this.spawn = null
    this.initializeResult = null
    this.workspaceRootPath = null
    this.moduleProjectDirPath = null
    notificationFanout.clear()
    return true
  }

  fun shutdown() {
    val s = spawn ?: return
    spawn = null
    initializeResult = null
    workspaceRootPath = null
    moduleProjectDirPath = null
    notificationFanout.clear()
    runCatching { s.shutdown() }
  }
}

/**
 * Resolves the per-module daemon launch descriptor. The default reads
 * `<workingDir>/build/compose-previews/daemon-launch.json` written by
 * [`composePreviewDaemonStart`][ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask]; tests
 * substitute an in-memory provider.
 */
fun interface DescriptorProvider {
  fun descriptorFor(project: RegisteredProject, modulePath: String): DaemonLaunchDescriptor

  companion object {
    /**
     * Reads `build/compose-previews/daemon-launch.json` per module from disk, written by `./gradlew
     * :<module>:composePreviewDaemonStart`; a clear error is raised if missing.
     */
    fun readingFromDisk(fileSystem: FileSystem = SystemFileSystem): DescriptorProvider {
      // Per-project-root index of modulePath -> descriptor file, built on the first fast-path miss.
      // Only positive results are cached, so a newly written descriptor is found without a restart;
      // the rescan cost lands only on the error path.
      val scannedIndexByRoot = ConcurrentHashMap<String, Map<String, File>>()
      return DescriptorProvider { project, modulePath ->
        // Fast path: the Gradle path mirrors the directory layout (`:a:b` → <root>/a/b).
        val guessed =
          File(
            gradlePathToFile(project.path, modulePath),
            "build/compose-previews/daemon-launch.json",
          )
        val descriptorFile =
          if (guessed.isFile) {
            guessed
          } else {
            // Fallback for projects that remap projectDir in settings.gradle.kts: find the
            // descriptor by the modulePath recorded inside each daemon-launch.json. Reuse the
            // cached index only if it resolves this module; otherwise rescan and cache.
            val root = project.path.absolutePath
            scannedIndexByRoot[root]?.get(modulePath)
              ?: run {
                val rescanned = indexDescriptorsByModulePath(project.path, fileSystem)
                scannedIndexByRoot[root] = rescanned
                rescanned[modulePath]
                  ?: error(
                    "Missing daemon launch descriptor for $modulePath under " +
                      "${project.path.absolutePath}. " +
                      "Run `./gradlew $modulePath:composePreviewDaemonStart` first."
                  )
              }
          }
        DaemonLaunchDescriptor.parse(fileSystem.read(descriptorFile.path.toPath()) { readUtf8() })
      }
    }

    private fun gradlePathToFile(projectRoot: File, modulePath: String): File {
      // ":" → root, ":a:b" → projectRoot/a/b
      val trimmed = modulePath.trimStart(':')
      if (trimmed.isEmpty()) return projectRoot
      val rel = trimmed.replace(':', File.separatorChar)
      return File(projectRoot, rel)
    }

    /**
     * Scans [projectRoot] for `build/compose-previews/daemon-launch.json` and indexes each by its
     * recorded `modulePath`. Prunes VCS, Gradle/IDE metadata, `node_modules`, `src` and other
     * `build/` subtrees.
     */
    internal fun indexDescriptorsByModulePath(
      projectRoot: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): Map<String, File> {
      val index = HashMap<String, File>()
      projectRoot
        .walkTopDown()
        .onEnter { dir ->
          when {
            dir.name in setOf(".git", ".gradle", ".idea", "node_modules", "src") -> false
            dir.parentFile?.name == "build" && dir.name != "compose-previews" -> false
            else -> true
          }
        }
        .filter {
          it.isFile && it.name == "daemon-launch.json" && it.parentFile?.name == "compose-previews"
        }
        .forEach { file ->
          val recorded = runCatching {
            DaemonLaunchDescriptor.parse(fileSystem.read(file.path.toPath()) { readUtf8() })
              .modulePath
          }
            .getOrNull()
          if (recorded != null) index.putIfAbsent(recorded, file)
        }
      return index
    }
  }
}

// Notification routing.

/**
 * Demultiplexes daemon notifications by method. Handlers run in registration order on the daemon's
 * reader thread, so they must be cheap and non-blocking.
 */
class NotificationRouter {
  private val handlers =
    ConcurrentHashMap<String, MutableList<(SupervisedDaemon, JsonObject?) -> Unit>>()
  private val closeHandlers = mutableListOf<(SupervisedDaemon) -> Unit>()

  fun on(method: String, handler: (SupervisedDaemon, JsonObject?) -> Unit) {
    val list = handlers.computeIfAbsent(method) { mutableListOf() }
    synchronized(list) { list.add(handler) }
  }

  fun onClose(handler: (SupervisedDaemon) -> Unit) {
    synchronized(closeHandlers) { closeHandlers.add(handler) }
  }

  internal fun dispatch(daemon: SupervisedDaemon, method: String, params: JsonObject?) {
    val list = handlers[method] ?: return
    synchronized(list) { list.toList() }.forEach { runCatching { it(daemon, params) } }
  }

  internal fun dispatchClose(daemon: SupervisedDaemon) {
    synchronized(closeHandlers) { closeHandlers.toList() }.forEach { runCatching { it(daemon) } }
  }

  /**
   * Extract `params.id` from a `renderFinished` / `renderStarted` envelope, or null when missing.
   */
  fun previewIdOf(params: JsonObject?): String? = params?.get("id")?.jsonPrimitive?.contentOrNull

  /** Convenience: extract `params.pngPath` from a `renderFinished` envelope. */
  fun pngPathOf(params: JsonObject?): String? = params?.get("pngPath")?.jsonPrimitive?.contentOrNull

  /** Convenience: extract a renderer-specific string field from any envelope. */
  fun stringField(params: JsonObject?, name: String): String? =
    params?.get(name)?.jsonPrimitive?.contentOrNull

  /** Convenience: walk a `discoveryUpdated.added[]` / `discoveryUpdated.changed[]` array. */
  fun previewsArray(params: JsonObject?, key: String): List<JsonObject> =
    (params?.get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
      runCatching { it.jsonObject }.getOrNull()
    } ?: emptyList()
}

/** This descriptor with one more JVM system property, everything else unchanged. */
internal fun DaemonLaunchDescriptor.withSystemProperty(
  name: String,
  value: String,
): DaemonLaunchDescriptor =
  DaemonLaunchDescriptor(
    schemaVersion = schemaVersion,
    modulePath = modulePath,
    variant = variant,
    enabled = enabled,
    mainClass = mainClass,
    javaLauncher = javaLauncher,
    classpath = classpath,
    jvmArgs = jvmArgs,
    systemProperties = systemProperties + (name to value),
    workingDirectory = workingDirectory,
    manifestPath = manifestPath,
    jailCommand = jailCommand,
    hardTtlSeconds = hardTtlSeconds,
  )
