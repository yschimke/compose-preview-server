package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The server's own Android daemon for the Remote Compose players: one bundle-less Robolectric
 * daemon that only ever replays a document carried on the request
 * (`overrides.remoteCompose.documentBase64`), so a shared `/d/<id>` document or a baked-only
 * catalog preview can be drawn by an Android player with no catalog daemon resident to borrow.
 *
 * The daemon stands on the Android sidecar plus `lib-rc-players-android/` (the Remote Compose
 * family the sidecar leaves for a consumer to bring) and a one-entry `previews.json` whose preview
 * exists only to give the render a sandbox and a frame: a carried document replays in place of the
 * preview's content, so its class is never loaded. The shape is [PlaygroundRcCaptureService]'s
 * bundle-less daemon, held open across renders behind a [ServeRenderHost] instead of spawned per
 * call.
 *
 * Lifecycle: nothing runs until the first [render], which starts the daemon off the request thread
 * and answers [Outcome.Warming] (the caller's `503 + Retry-After`) until it is up; that first
 * render is the warm-up, so the retry is a cache hit. An idle daemon is closed after [idleMillis]
 * and its live seat returned; the next render starts it again. A daemon that failed to start is not
 * retried for [failureBackoffMillis], so a broken runtime is not respawned on every poll.
 */
class ServeRcPlayerDaemon(
  /** The Android players this daemon's classpath can draw ([playersIn]); never empty. */
  val players: List<RcPlayerBackend>,
  /** Spawns the daemon over [PREVIEW]. Blocking; called off the request thread. */
  private val openHost: () -> ServeHost,
  /** The live-seat budget the resident daemon holds an Android seat of, or null for none. */
  private val liveSeats: LiveSeatLimiter? = null,
  private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
  private val failureBackoffMillis: Long = DEFAULT_FAILURE_BACKOFF_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) : AutoCloseable {

  /** What a [render] call came to. */
  sealed interface Outcome {
    /** The daemon answered; mapped exactly as a borrowed catalog daemon's answer is. */
    data class Rendered(val outcome: RenderOutcome) : Outcome

    /** The daemon is starting (this call may have started it); ask again shortly. */
    data object Warming : Outcome

    /** No daemon now: no live seat, or a start failed within the backoff. */
    data class Unavailable(val reason: String) : Outcome
  }

  /** `/status`'s view of the daemon: whether it is up, and how its renders have gone. */
  @Serializable
  data class Snapshot(
    val state: String,
    val players: List<String>,
    val renders: Long,
    val lastError: String? = null,
  )

  private sealed interface State {
    data object Idle : State

    data object Starting : State

    class Running(val host: ServeHost, val seat: LiveSeatLimiter.Ticket?) : State

    class Failed(val reason: String, val atMillis: Long) : State
  }

  private val lock = Any()
  private var state: State = State.Idle
  private var lastUsedMillis = 0L
  private var renders = 0L
  private var lastError: String? = null

  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "rc-player-daemon").apply { isDaemon = true }
    }

  init {
    require(players.isNotEmpty()) { "a player-only daemon with no players is no daemon" }
    val sweep = (idleMillis / 4).coerceIn(1_000, 60_000)
    executor.scheduleWithFixedDelay(::closeIfIdle, sweep, sweep, TimeUnit.MILLISECONDS)
  }

  /** Whether this daemon can draw [backend] — once started, not whether it is running now. */
  fun offers(backend: RcPlayerBackend): Boolean = backend in players

  /**
   * Draw [overrides] (which carry the document and player) on the daemon. Blocks only once the
   * daemon is up; a cold daemon is started in the background and answers [Outcome.Warming].
   */
  fun render(overrides: PreviewOverrides): Outcome {
    val host =
      synchronized(lock) {
        lastUsedMillis = clock()
        when (val current = state) {
          is State.Running -> current.host
          State.Starting -> return Outcome.Warming
          is State.Failed ->
            return if (clock() - current.atMillis < failureBackoffMillis) {
              Outcome.Unavailable(current.reason)
            } else start(overrides)
          State.Idle -> return start(overrides)
        }
      }
    // A host closed under us (idle, or the server stopping) is a failed render, never a throwable.
    val outcome =
      try {
        host.render(PREVIEW_ID, overrides)
      } catch (e: IllegalStateException) {
        RenderOutcome.Failed("the Android player daemon closed: ${e.message}")
      }
    synchronized(lock) {
      renders++
      if (outcome is RenderOutcome.Failed) lastError = outcome.reason
    }
    return Outcome.Rendered(outcome)
  }

  /** Start the daemon off the request thread, rendering [overrides] as its warm-up. */
  private fun start(overrides: PreviewOverrides): Outcome {
    // A resident Robolectric daemon costs what a catalog's does, so it holds an Android seat. A
    // background acquire: a player is an extra lane, and must never take an interactive stream's.
    val seat = liveSeats?.let {
      it.acquireBackground(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT, dedicatedSlice = false)
        ?: return Outcome.Unavailable("no live seat is free for the Android player daemon")
    }
    state = State.Starting
    executor.execute {
      val host =
        try {
          openHost()
        } catch (e: Exception) {
          val reason = "the Android player daemon did not start: ${e.message ?: e.javaClass.name}"
          onLog(reason)
          seat?.close()
          synchronized(lock) {
            lastError = reason
            state = State.Failed(reason, clock())
          }
          return@execute
        }
      onLog("rc player daemon started (${players.joinToString { ServeRcPlayerIds.of(it) }})")
      // Its answer lands in the host's render cache, so the request that started the daemon is a
      // cache hit when it asks again; a failure here is that document's, not the daemon's.
      val warm = runCatching { host.render(PREVIEW_ID, overrides) }.getOrNull()
      synchronized(lock) {
        renders++
        if (warm is RenderOutcome.Failed) lastError = warm.reason
        lastUsedMillis = clock()
        state = State.Running(host, seat)
      }
    }
    return Outcome.Warming
  }

  /** Close a daemon nobody has asked for in [idleMillis]; the next [render] starts it again. */
  internal fun closeIfIdle() {
    val running =
      synchronized(lock) {
        val current = state as? State.Running ?: return
        if (clock() - lastUsedMillis < idleMillis) return
        state = State.Idle
        current
      }
    onLog("rc player daemon idle for ${idleMillis / 1000}s; closing")
    running.release()
  }

  fun snapshot(): Snapshot =
    synchronized(lock) {
      Snapshot(
        state =
          when (state) {
            State.Idle -> "idle"
            State.Starting -> "starting"
            is State.Running -> "running"
            is State.Failed -> "failed"
          },
        players = players.map { ServeRcPlayerIds.of(it) },
        renders = renders,
        lastError = lastError,
      )
    }

  override fun close() {
    executor.shutdownNow()
    val running =
      synchronized(lock) {
        val current = state as? State.Running
        state = State.Idle
        current
      }
    running?.release()
  }

  private fun State.Running.release() {
    runCatching { host.close() }
    seat?.close()
  }

  companion object {
    /** The one preview the daemon's manifest names; it picks a sandbox and frame, nothing more. */
    const val PREVIEW_ID: String = "ee.schimke.composeai.serve.RcPlayerDaemonKt.Player"

    /**
     * The sidecar directory the Remote Compose players are staged in: `<APP_HOME>/…`, or wherever
     * `-Dcomposeai.cli.lib.rc.players.androidDir` points ([locateBundleSidecarJars]' spelling).
     */
    const val PLAYERS_SIDECAR: String = "lib-rc-players-android"

    val PREVIEW: ServePreview = ServePreview(PREVIEW_ID, "Remote Compose player")

    /** Close after five idle minutes: a player lane is bursty, and a cold start costs ~15 s. */
    const val DEFAULT_IDLE_MILLIS: Long = 5 * 60_000L

    const val DEFAULT_FAILURE_BACKOFF_MILLIS: Long = 60_000L

    /**
     * A class each player's own library provides, so a player is offered only when its library is
     * on the daemon's classpath. Linkage is the connector's to check at first use
     * (`RemoteComposePlayers`); this only stops offering a player whose library is absent.
     */
    private val PLAYER_MARKERS: Map<RcPlayerBackend, String> =
      mapOf(
        RcPlayerBackend.ANDROIDX_EMBEDDED to
          "ee/schimke/composeai/rcembedded/player/RcPlayerKt.class",
        RcPlayerBackend.ANDROIDX_VIEW to
          "androidx/compose/remote/player/view/RemoteComposePlayer.class",
        RcPlayerBackend.CMP_ANDROID to
          "ee/schimke/composeai/rcplayer/compose/RcComposePlayerKt.class",
      )

    /** The players whose libraries [jars] carry, in [PLAYER_MARKERS] order. */
    internal fun playersIn(jars: List<File>): List<RcPlayerBackend> {
      val entries =
        jars
          .flatMap { jar ->
            runCatching { ZipFile(jar).use { zip -> zip.entries().toList().map { it.name } } }
              .getOrDefault(emptyList())
          }
          .toSet()
      return PLAYER_MARKERS.filterValues { it in entries }.keys.toList()
    }

    private val json = Json { encodeDefaults = true }

    internal fun manifestJson(): String =
      json.encodeToString(
        PreviewManifest.serializer(),
        PreviewManifest(
          module = MODULE_PATH,
          variant = "",
          previews =
            listOf(
              PreviewInfo(
                id = PREVIEW_ID,
                functionName = PREVIEW_ID.substringAfterLast('.'),
                className = PREVIEW_ID.substringBeforeLast('.'),
              )
            ),
        ),
      )

    private const val MODULE_PATH = ":rc-player"

    private val INITIALIZE_TIMEOUT = 3.minutes

    /**
     * The production daemon, or null (having said why through [log]) when this host has no Android
     * runtime for it: the Android sidecar, `android.jar`, or a staged player library. The daemon
     * classpath is the [PLAYERS_SIDECAR] jars ahead of the sidecar, the order a catalog's
     * `androidx.*` jars take ([ServeBundleDaemon.jarPrecedesDaemonSidecar]).
     */
    fun subprocess(
      workRoot: File,
      liveSeats: LiveSeatLimiter?,
      log: (String) -> Unit,
    ): ServeRcPlayerDaemon? {
      val daemonJars = locateBundleSidecarJars("lib-daemon-android")
      if (daemonJars.isEmpty()) {
        log("needs the Android daemon sidecar (lib-daemon-android/) — not found")
        return null
      }
      val playerJars = locateBundleSidecarJars(PLAYERS_SIDECAR)
      val players = playersIn(playerJars)
      if (players.isEmpty()) {
        log(
          "needs the Remote Compose players ($PLAYERS_SIDECAR/) — looked in " +
            bundleSidecarSearchDescription(PLAYERS_SIDECAR)
        )
        return null
      }
      val androidJar =
        AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null)
          ?: run {
            log("needs android.jar — set ANDROID_HOME / ANDROID_SDK_ROOT")
            return null
          }
      val launch = AndroidBundleLaunch()
      val daemonClasspath = (playerJars + daemonJars + androidJar).map { it.absolutePath }
      return ServeRcPlayerDaemon(
        players = players,
        openHost = {
          val workDir = File(workRoot, "rc-player-daemon").apply { deleteRecursively() }
          val classesDir = File(workDir, "classes").apply { mkdirs() }
          val previewsJson = File(workDir, "previews.json").apply { writeText(manifestJson()) }
          val session =
            SubprocessRenderSessions.openBundleDaemon(
              daemonClasspath = daemonClasspath,
              classesDir = classesDir,
              previewsJson = previewsJson,
              workspaceRoot = workDir,
              modulePath = MODULE_PATH,
              // A cold boot may download the Robolectric runtime before it can answer.
              initializeTimeout = INITIALIZE_TIMEOUT,
              jvmArgs = launch.jvmArgs(),
              // One sandbox: [ServeRenderHost] renders one at a time anyway, and the daemon's
              // warm-spare default of five took over a minute to boot before `initialize` answered.
              extraSystemProperties =
                launch.robolectricSystemProperties() + ("composeai.daemon.sandboxCount" to "1"),
            )
          ServeRenderHost(session, listOf(PREVIEW), label = "Remote Compose players", onLog = log)
        },
        liveSeats = liveSeats,
        onLog = log,
      )
    }
  }
}
