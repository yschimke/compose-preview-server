package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * The same daemon also holds a **live** session for a carried document ([openLive]): its
 * `stream/start` plays `overrides.remoteCompose.documentBase64` in a held composition, so viewer
 * input reaches the document's click actions and touch expressions. Only a daemon that advertises
 * [CARRIED_LIVE_CAPABILITY] does that ([playsCarriedDocumentsLive]); an older one ignores the
 * document on that lane and would stream the manifest's empty preview, so it is never asked, and
 * the caller keeps its repeated-render replay ([ServeCarriedRcStream]). A held session pins one
 * sandbox and needs another left for renders, so the daemon boots two ([SANDBOX_COUNT]).
 *
 * Lifecycle: nothing runs until the first [render] (or [openLive]), which starts the daemon off the
 * request thread and answers [Outcome.Warming] (the caller's `503 + Retry-After`) until it is up;
 * that first render is the warm-up, so the retry is a cache hit. An idle daemon — no render for
 * [idleMillis] and no live session open — is closed and its live seat returned; the next render
 * starts it again. A daemon that failed to start is not retried for [failureBackoffMillis], so a
 * broken runtime is not respawned on every poll.
 *
 * The seat a start takes depends on who asked. A [render] start is background work (it has a
 * fallback: a resident catalog daemon, or a 503), so it takes the background remainder
 * ([LiveSeatLimiter.acquireBackground]) and leaves the stream reserve alone. An [openLive] start is
 * a viewer's interactive stream, which is exactly what that reserve is kept for, so it takes a
 * foreground seat ([LiveSeatLimiter.acquire]) — and may take over the viewer's own stream seat
 * (`streamSeat`) to fit, since once held the daemon *is* that stream. On a box whose resident
 * catalogs fill the background remainder (preview.coo.ee keeps two spare sandboxes and several warm
 * catalogs), a background-priced live start was refused on every attempt, silently, and the lane
 * stayed on its input-less replay. A foreground seat is repriced to background once no live session
 * is open, and when that headroom is not there the daemon closes after [foregroundIdleMillis]
 * instead of [idleMillis], so it never sits in the stream reserve for long.
 *
 * The same class fronts the server's **desktop** player daemon ([desktopSubprocess]): a bundle-less
 * `daemon-desktop` with the CMP player's JVM target on its classpath, which plays a carried
 * document in the `cmp-jvm` player. It exists only for [openLive]: the cmp-jvm render lane keeps
 * its one-shot subprocess (`RcJvmServerRenderer`), and this daemon is the held session that lane
 * cannot offer.
 */
class ServeRcPlayerDaemon(
  /** The players this daemon's classpath can draw ([playersIn]); never empty. */
  val players: List<RcPlayerBackend>,
  /** Spawns the daemon over [PREVIEW]. Blocking; called off the request thread. */
  private val openHost: () -> ServeHost,
  /** The live-seat budget the resident daemon holds an Android seat of, or null for none. */
  private val liveSeats: LiveSeatLimiter? = null,
  private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
  /** How long a daemon on a foreground seat outlives its last live session; see the class doc. */
  private val foregroundIdleMillis: Long = DEFAULT_FOREGROUND_IDLE_MILLIS,
  private val failureBackoffMillis: Long = DEFAULT_FAILURE_BACKOFF_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
  /** How messages name this daemon: "the $label closed", "$label started". */
  private val label: String = ANDROID_LABEL,
  /** The live-seat weight the running daemon holds ([liveSeats]). */
  private val seatWeight: Int = ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT,
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

  /** What an [openLive] call came to. */
  sealed interface LiveOutcome {
    /** A held session is playing the document; input on [handle] reaches it. */
    class Streaming(val handle: StreamHandle) : LiveOutcome

    /** The daemon is starting (this call may have started it); ask again shortly. */
    data object Warming : LiveOutcome

    /**
     * The daemon plays carried documents live but cannot hold one right now: its one held session
     * is taken, its live seat is refused, or a failed start is inside its backoff. Ask again later.
     */
    data class Busy(
      val reason: String,
      /**
       * Refused only for want of a live seat: another daemon releasing an idle foreground seat
       * ([releaseIfIdleForeground]) may make room.
       */
      val noSeat: Boolean = false,
    ) : LiveOutcome

    /**
     * This daemon never holds a carried document (no [CARRIED_LIVE_CAPABILITY]); replay instead.
     */
    data class Unsupported(val reason: String) : LiveOutcome
  }

  /**
   * Implemented by the host [openHost] returns when it can say whether its daemon plays a carried
   * document in a held session ([playsCarriedDocumentsLive] of its `initialize` capabilities). A
   * host that does not implement it is treated as one that cannot.
   */
  interface LiveDocuments {
    val playsCarriedDocumentsLive: Boolean
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

    class Running(
      val host: ServeHost,
      var seat: LiveSeatLimiter.Ticket?,
      /** Whether [host]'s daemon holds a live session for a carried document. */
      val playsLive: Boolean,
      /**
       * Whether [seat] is a foreground one (taken by [openLive]), which sits in the stream reserve
       * until [closeIfIdle] reprices it.
       */
      var foreground: Boolean,
    ) : State

    class Failed(val reason: String, val atMillis: Long) : State
  }

  private val lock = Any()
  private var state: State = State.Idle
  private var lastUsedMillis = 0L
  private var renders = 0L
  private var lastError: String? = null

  /** Live sessions open on the running daemon; while any is, the daemon is not idle. */
  private var liveSessions = 0

  /** When a live session last opened or closed; a foreground seat's idle clock. */
  private var lastLiveMillis = 0L

  /**
   * Whether this daemon's runtime plays carried documents live, once a start has said; null before
   * the first. The runtime is fixed for the server's life, so it outlives an idle close.
   */
  @Volatile private var playsLiveKnown: Boolean? = null

  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "rc-player-daemon").apply { isDaemon = true }
    }

  init {
    require(players.isNotEmpty()) { "a player-only daemon with no players is no daemon" }
    val sweep = (minOf(idleMillis, foregroundIdleMillis) / 4).coerceIn(1_000, 60_000)
    executor.scheduleWithFixedDelay(::closeIfIdle, sweep, sweep, TimeUnit.MILLISECONDS)
  }

  /** Whether the daemon holds its live seat now: it is up, or starting. */
  val holdsSeat: Boolean
    get() = synchronized(lock) { state is State.Running || state == State.Starting }

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
            } else start(overrides, foreground = false)
          State.Idle -> return start(overrides, foreground = false)
        }
      }
    // A host closed under us (idle, or the server stopping) is a failed render, never a throwable.
    val outcome =
      try {
        host.render(PREVIEW_ID, overrides)
      } catch (e: IllegalStateException) {
        RenderOutcome.Failed("the $label closed: ${e.message}")
      }
    synchronized(lock) {
      renders++
      if (outcome is RenderOutcome.Failed) lastError = outcome.reason
    }
    return Outcome.Rendered(outcome)
  }

  /**
   * Whether a start has shown this daemon plays carried documents live: true or false once known,
   * null before the daemon has ever run. Lets a page say up front that its live lane takes input.
   */
  val knownToPlayCarriedDocumentsLive: Boolean?
    get() = playsLiveKnown

  /**
   * Hold a live session that plays the document [overrides] carry (`stream/start` with the same
   * overrides [render] takes), frames to [onFrame]. A cold daemon is started, as [render] starts
   * it, and answers [LiveOutcome.Warming]; a daemon without [CARRIED_LIVE_CAPABILITY] answers
   * [LiveOutcome.Unsupported], and one that could not hold the session now (it holds one at a time,
   * or has no seat) answers [LiveOutcome.Busy]. Blocking while `stream/start` runs.
   *
   * A cold start here takes a foreground seat (see the class doc). [streamSeat] is the seat the
   * viewer's stream already holds, if any: when the foreground seat does not fit beside it, it is
   * released to make room, since the daemon about to hold the session is that stream's cost.
   */
  fun openLive(
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    streamSeat: (() -> LiveSeatLimiter.Ticket?)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): LiveOutcome {
    val host =
      synchronized(lock) {
        lastUsedMillis = clock()
        val current = state
        when {
          current is State.Running ->
            if (current.playsLive) current.host
            else return LiveOutcome.Unsupported(noLiveDocuments())
          current == State.Starting -> return LiveOutcome.Warming
          current is State.Failed && clock() - current.atMillis < failureBackoffMillis ->
            return LiveOutcome.Busy(current.reason)
          // Starting a daemon known not to hold a carried document would only warm the replay.
          playsLiveKnown == false -> return LiveOutcome.Unsupported(noLiveDocuments())
          else ->
            return when (val started = start(overrides, foreground = true, streamSeat)) {
              is Outcome.Unavailable ->
                LiveOutcome.Busy(started.reason, noSeat = started.reason == noSeat())
              else -> LiveOutcome.Warming
            }
        }
      }
    var unavailable: String? = null
    val handle =
      try {
        host.subscribeStream(
          PREVIEW_ID,
          overrides,
          codec,
          maxFps,
          onUnavailable = { unavailable = it },
          onFrame = onFrame,
        )
      } catch (e: IllegalStateException) {
        unavailable = "the $label closed: ${e.message}"
        null
      } ?: return LiveOutcome.Busy(unavailable ?: "the $label did not hold a live session")
    synchronized(lock) {
      liveSessions++
      lastLiveMillis = clock()
    }
    return LiveOutcome.Streaming(LiveSession(handle))
  }

  /** [delegate], counted in [liveSessions] until closed so the daemon is not closed under it. */
  private inner class LiveSession(private val delegate: StreamHandle) : StreamHandle {
    private val closed = AtomicBoolean(false)

    override fun input(
      kind: InteractiveInputKind,
      pixelX: Int?,
      pixelY: Int?,
      pointerId: Int?,
      scrollDeltaY: Float?,
      keyCode: String?,
      text: String?,
      pointerType: String?,
    ) {
      if (closed.get()) return
      delegate.input(kind, pixelX, pixelY, pointerId, scrollDeltaY, keyCode, text, pointerType)
    }

    override fun visibility(visible: Boolean, fps: Int?) {
      if (!closed.get()) delegate.visibility(visible, fps)
    }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      runCatching { delegate.close() }
      synchronized(lock) {
        liveSessions--
        // The idle clock restarts when the last viewer leaves, not when it arrived.
        lastUsedMillis = clock()
        lastLiveMillis = lastUsedMillis
      }
    }
  }

  /**
   * Start the daemon off the request thread, rendering [overrides] as its warm-up. Caller holds
   * [lock]. [foreground]: a viewer's [openLive] asked, so the seat is a foreground one, and
   * [streamSeat] may be given up for it (see [openLive]).
   */
  private fun start(
    overrides: PreviewOverrides,
    foreground: Boolean,
    streamSeat: (() -> LiveSeatLimiter.Ticket?)? = null,
  ): Outcome {
    // A resident daemon costs what a catalog's of the same backend does, so it holds that seat (an
    // Android one for Robolectric).
    val seat = liveSeats?.let { seats ->
      val ticket =
        if (foreground) acquireForeground(seats, streamSeat)
        else seats.acquireBackground(seatWeight, dedicatedSlice = false)
      ticket
        ?: return Outcome.Unavailable(noSeat()).also {
          // Said once per reason, so a lane asking every few seconds does not flood the log, and
          // kept for `/status`, which otherwise reads "idle, renders 0, no error".
          if (lastError != it.reason) onLog(it.reason)
          lastError = it.reason
        }
    }
    state = State.Starting
    executor.execute {
      val host =
        try {
          openHost()
        } catch (e: Throwable) {
          // Throwable, not Exception: an incomplete or mismatched staged classpath fails as a
          // LinkageError (NoClassDefFoundError), and that must still back off and return the seat
          // rather than leave the daemon "starting" forever. Only a VM error is rethrown.
          val reason = "the $label did not start: ${e.message ?: e.javaClass.name}"
          onLog(reason)
          seat?.close()
          synchronized(lock) {
            lastError = reason
            state = State.Failed(reason, clock())
          }
          if (e is VirtualMachineError) throw e
          return@execute
        }
      onLog("$label started (${players.joinToString { ServeRcPlayerIds.of(it) }})")
      // Its answer lands in the host's render cache, so the request that started the daemon is a
      // cache hit when it asks again; a failure here is that document's, not the daemon's.
      val warm = runCatching { host.render(PREVIEW_ID, overrides) }.getOrNull()
      val playsLive = runCatching {
        (host as? LiveDocuments)?.playsCarriedDocumentsLive == true
      }
        .getOrDefault(false)
      playsLiveKnown = playsLive
      synchronized(lock) {
        renders++
        // A seat refusal said why the daemon was not running; now it is, that is stale.
        if (lastError == noSeat()) lastError = null
        if (warm is RenderOutcome.Failed) lastError = warm.reason
        lastUsedMillis = clock()
        lastLiveMillis = lastUsedMillis
        state = State.Running(host, seat, playsLive, foreground = foreground && seat != null)
      }
    }
    return Outcome.Warming
  }

  /**
   * A foreground seat for a viewer's held session: from the general lane, without the stream
   * headroom [LiveSeatLimiter.acquireBackground] keeps (this *is* the stream it keeps it for). When
   * that does not fit, the viewer's own stream seat is given up and the acquire tried once more.
   * Not counted as a refusal: the lane keeps its replay, and asks again.
   */
  private fun acquireForeground(
    seats: LiveSeatLimiter,
    streamSeat: (() -> LiveSeatLimiter.Ticket?)?,
  ): LiveSeatLimiter.Ticket? {
    seats.acquire(seatWeight, countRefusal = false)?.let {
      return it
    }
    val own = streamSeat?.invoke()?.takeIf { it.permits > 0 } ?: return null
    // Only when giving it up would make room: a stream stripped of its seat for nothing would
    // leave the box over-admitted. Racy, but a race can only make the second acquire fail.
    val generalFree = seats.availablePermits() - seats.perPreviewPermitsAvailable()
    if (generalFree + own.permits < seatWeight) return null
    own.close()
    return seats.acquire(seatWeight, countRefusal = false)
  }

  /**
   * Close the daemon now if it sits on a foreground seat with no live session open — a viewer left
   * it warm for a reload — so a stream that needs that seat gets it. True when a seat was returned.
   */
  fun releaseIfIdleForeground(): Boolean {
    val running =
      synchronized(lock) {
        val current = state as? State.Running ?: return false
        if (!current.foreground || liveSessions > 0) return false
        state = State.Idle
        current
      }
    onLog("$label closing early: a viewer needs the live seat it was keeping warm")
    running.release()
    return true
  }

  /**
   * Close a daemon nobody has asked for in [idleMillis]; the next [render] starts it again. A
   * daemon on a foreground seat with no live session open is first repriced to a background seat;
   * when the box has no background headroom it closes once [foregroundIdleMillis] has passed since
   * its last live session, so a viewer who left does not keep the stream reserve.
   */
  internal fun closeIfIdle() {
    var idleFor = idleMillis
    val running =
      synchronized(lock) {
        val current = state as? State.Running ?: return
        if (liveSessions > 0) return
        if (current.foreground) {
          val repriced = liveSeats?.acquireBackground(seatWeight, dedicatedSlice = false)
          if (repriced != null) {
            current.seat?.close()
            current.seat = repriced
            current.foreground = false
          } else if (clock() - lastLiveMillis >= foregroundIdleMillis) {
            idleFor = foregroundIdleMillis
            state = State.Idle
            return@synchronized current
          }
        }
        if (clock() - lastUsedMillis < idleMillis) return
        state = State.Idle
        current
      }
    onLog("$label idle for ${idleFor / 1000}s; closing")
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

  private fun noSeat(): String = "no live seat is free for the $label"

  private fun noLiveDocuments(): String =
    "this $label cannot hold a carried document live (it does not advertise " +
      "$CARRIED_LIVE_CAPABILITY)"

  private fun State.Running.release() {
    runCatching { host.close() }
    seat?.close()
  }

  companion object {
    /**
     * The `InitializeResult.capabilities.supportedOverrides` token of a daemon whose held lane
     * (`stream/start`) plays `overrides.remoteCompose.documentBase64` instead of the preview id
     * (compose-preview-daemon's `docs/daemon/STREAMING.md`, "Carried Remote Compose documents").
     */
    const val CARRIED_LIVE_CAPABILITY: String = "remoteCompose.documentBase64.live"

    /**
     * Whether a daemon advertising [capabilities] holds a live session for a carried document. The
     * token is only advertised alongside `interactive`, and both are checked: a daemon that ignores
     * the document on `stream/start` would stream the bare preview id — on this daemon, nothing.
     */
    fun playsCarriedDocumentsLive(capabilities: ServerCapabilities): Boolean =
      capabilities.interactive && CARRIED_LIVE_CAPABILITY in capabilities.supportedOverrides

    const val ANDROID_LABEL: String = "Android player daemon"

    const val DESKTOP_LABEL: String = "desktop player daemon"

    /**
     * Sandboxes the daemon boots: a held live session pins one (the in-process slot) and renders
     * need another, and a daemon with fewer advertises no `interactive` at all. The second is a
     * worker JVM; still well inside the [ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT] seat a catalog
     * daemon's five-sandbox fleet is charged.
     */
    const val SANDBOX_COUNT: Int = 2

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

    /**
     * How long a daemon still on a foreground seat (no background headroom to reprice into) waits
     * after its last live session: long enough for a reload to find it warm.
     */
    const val DEFAULT_FOREGROUND_IDLE_MILLIS: Long = 60_000L

    const val DEFAULT_FAILURE_BACKOFF_MILLIS: Long = 60_000L

    /** What [playersIn] needs to see before it offers `cmp-android` (declared before its use). */
    internal val CMP_ANDROID_MARKERS: List<String> =
      listOf(
        "ee/schimke/composeai/rcplayer/compose/RcComposePlayerKt.class",
        "ee/schimke/composeai/rcplayer/runtime/RcNamedValue.class",
        "coil3/ImageLoader.class",
      )

    /**
     * Classes each player's libraries provide, so a player is offered only when all of them are on
     * the daemon's classpath. Linkage is the connector's to check at first use
     * (`RemoteComposePlayers`); this only stops offering a player whose libraries are absent.
     *
     * The CMP player needs more than its own jar: `rc-player-compose` links its runtime and draws a
     * document's bitmaps through Coil, neither of which the Android sidecar carries, so a directory
     * holding only the player jar would offer `cmp-android` into a `NoClassDefFoundError`.
     */
    private val PLAYER_MARKERS: Map<RcPlayerBackend, List<String>> =
      mapOf(
        RcPlayerBackend.ANDROIDX_EMBEDDED to
          listOf("ee/schimke/composeai/rcembedded/player/RcPlayerKt.class"),
        RcPlayerBackend.ANDROIDX_VIEW to
          listOf("androidx/compose/remote/player/view/RemoteComposePlayer.class"),
        RcPlayerBackend.CMP_ANDROID to CMP_ANDROID_MARKERS,
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
      return PLAYER_MARKERS.filterValues { entries.containsAll(it) }.keys.toList()
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
              // [SANDBOX_COUNT], not the daemon's warm-spare default of five, which took over a
              // minute
              // to boot before `initialize` answered: [ServeRenderHost] renders one at a time, and
              // the second sandbox is only there so a held live session leaves one for renders.
              extraSystemProperties =
                launch.robolectricSystemProperties() +
                  ("composeai.daemon.sandboxCount" to SANDBOX_COUNT.toString()),
            )
          val playsLive = playsCarriedDocumentsLive(session.initializeResult.capabilities)
          val host =
            ServeRenderHost(session, listOf(PREVIEW), label = "Remote Compose players", onLog = log)
          object : ServeHost by host, LiveDocuments {
            override val playsCarriedDocumentsLive: Boolean = playsLive
          }
        },
        liveSeats = liveSeats,
        onLog = log,
      )
    }

    /**
     * The sidecar the CMP player's JVM target is staged in for the desktop daemon: a delta laid
     * ahead of `lib-daemon-desktop/` (the player and the Compose line it needs; see
     * `stageRcPlayerDesktopLibs`), located as [locateBundleSidecarJars] locates any sidecar.
     */
    const val DESKTOP_PLAYER_SIDECAR: String = "lib-rc-player-desktop"

    /** A class of rc-players' `rc-player-compose`, so the player is offered only when staged. */
    internal const val CMP_PLAYER_MARKER: String =
      "ee/schimke/composeai/rcplayer/compose/RcComposePlayerKt.class"

    /**
     * The binding compose-preview-daemon's `daemon-desktop` plays a carried document through
     * (compose-preview-daemon#255). A daemon without it predates carried documents on desktop, so
     * starting it for one would only cost a JVM.
     */
    internal const val DESKTOP_BINDING_MARKER: String =
      "ee/schimke/composeai/daemon/rcplayer/CmpJvmPlayerBinding.class"

    private val SKIKO_AWT = Regex("""skiko-awt-(\d[^/]*)\.jar""")
    private val SKIKO_NATIVE = Regex("""skiko-awt-runtime-[a-z]+-[a-z0-9]+-(\d[^/]*)\.jar""")

    /**
     * Why [classpath] would fail to load Skiko, or null when it pairs: Skiko binds the FIRST
     * `skiko-awt-<v>.jar` and loads the FIRST `skiko-awt-runtime-<os>-<arch>-*.jar` native, and
     * nothing compares the two, so a mismatch boots and then throws `UnsatisfiedLinkError` on the
     * first newer JNI symbol (the image's `check-skiko-pairing.sh` has the history). The player's
     * Compose line moves Skiko ahead of the daemon's, so the native must come with it.
     */
    internal fun skikoPairingProblem(classpath: List<File>): String? {
      val awt =
        classpath.firstNotNullOfOrNull { jar ->
          SKIKO_AWT.matchEntire(jar.name)?.takeUnless { jar.name.startsWith("skiko-awt-runtime-") }
        } ?: return null
      val version = awt.groupValues[1]
      val native =
        classpath.firstNotNullOfOrNull { SKIKO_NATIVE.matchEntire(it.name) }
          ?: return "binds Skiko $version but no skiko-awt-runtime-<os>-<arch>-$version.jar is on " +
            "its classpath — stage the host's native into $DESKTOP_PLAYER_SIDECAR/"
      if (native.groupValues[1] == version) return null
      return "binds Skiko $version but would load the ${native.groupValues[1]} native " +
        "(${native.value}) — stage skiko-awt-runtime-<os>-<arch>-$version.jar into " +
        "$DESKTOP_PLAYER_SIDECAR/"
    }

    private fun anyJarHas(jars: List<File>, entry: String): Boolean = jars.any { jar ->
      runCatching { ZipFile(jar).use { it.getEntry(entry) != null } }.getOrDefault(false)
    }

    /**
     * The production desktop player daemon, or null (having said why through [log]) when this host
     * cannot run one: no staged player ([DESKTOP_PLAYER_SIDECAR]), no desktop daemon sidecar, a
     * `daemon-desktop` that predates carried documents ([DESKTOP_BINDING_MARKER]), or a classpath
     * whose Skiko would not load ([skikoPairingProblem]). Its classpath is the player sidecar ahead
     * of `lib-daemon-desktop/` + `lib-renderer/`, the order the cmp-jvm one-shot lane gives
     * `lib-rcjvm/`.
     *
     * Only for [openLive]: whether a started daemon actually holds a carried document is still its
     * `initialize` capabilities' to say ([playsCarriedDocumentsLive]).
     */
    fun desktopSubprocess(
      workRoot: File,
      liveSeats: LiveSeatLimiter?,
      log: (String) -> Unit,
    ): ServeRcPlayerDaemon? {
      val playerJars = locateBundleSidecarJars(DESKTOP_PLAYER_SIDECAR)
      if (!anyJarHas(playerJars, CMP_PLAYER_MARKER)) {
        log(
          "needs the CMP player ($DESKTOP_PLAYER_SIDECAR/) — looked in " +
            bundleSidecarSearchDescription(DESKTOP_PLAYER_SIDECAR)
        )
        return null
      }
      val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
      val rendererJars = locateBundleSidecarJars("lib-renderer")
      if (daemonJars.isEmpty() || rendererJars.isEmpty()) {
        log("needs the desktop daemon sidecar (lib-daemon-desktop/ + lib-renderer/) — not found")
        return null
      }
      if (!anyJarHas(daemonJars, DESKTOP_BINDING_MARKER)) {
        log(
          "this daemon-desktop cannot play a carried Remote Compose document " +
            "(it predates compose-preview-daemon#255); cmp-jvm stays on its one-shot lane"
        )
        return null
      }
      val classpath = (playerJars + daemonJars + rendererJars).distinctBy { it.absolutePath }
      skikoPairingProblem(classpath)?.let {
        log(it)
        return null
      }
      return ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.CMP_JVM),
        openHost = {
          val workDir = File(workRoot, "rc-desktop-player-daemon").apply { deleteRecursively() }
          val classesDir = File(workDir, "classes").apply { mkdirs() }
          val previewsJson = File(workDir, "previews.json").apply { writeText(manifestJson()) }
          val session =
            SubprocessRenderSessions.openBundleDaemon(
              daemonClasspath = classpath.map { it.absolutePath },
              classesDir = classesDir,
              previewsJson = previewsJson,
              workspaceRoot = workDir,
              modulePath = MODULE_PATH,
              // As every desktop daemon launch: native access for Skiko, and on macOS a background
              // agent rather than a Dock icon.
              jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
            )
          val playsLive = playsCarriedDocumentsLive(session.initializeResult.capabilities)
          val host =
            ServeRenderHost(session, listOf(PREVIEW), label = "CMP JVM player", onLog = log)
          object : ServeHost by host, LiveDocuments {
            override val playsCarriedDocumentsLive: Boolean = playsLive
          }
        },
        liveSeats = liveSeats,
        onLog = log,
        label = DESKTOP_LABEL,
        // A desktop catalog daemon's weight: one Skiko JVM, no Robolectric sandboxes.
        seatWeight = 1,
      )
    }
  }
}
