package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The seam to compose-ai-tools' `DwfApkDaemonBridge`, which is `internal` there and keyed on a
 * served bundle's `ServeHost` + `ServePreview`. A design revision is neither, so this server cannot
 * call it; the three things it would call are named here instead, and what compose-ai-tools must
 * export for the real adapter is listed in the design doc ("What compose-ai-tools needs to
 * export"). Until then [RenderHostDwfApkRuntime] answers [Availability.Unavailable] and a fake
 * stands in for it in tests — which is also why nothing below knows about processes.
 */
internal interface DwfApkRuntime {
  /** Whether this host can draw a watch face with the real runtime, and if not, why not. */
  fun availability(): Availability

  /**
   * Package [source] as the resource-only APK the runtime loads (res/ + `watchface.xml` + a
   * synthesized manifest and `watch_face_info.xml`, via aapt2), cached by [DwfSource.digest].
   * compose-ai-tools' `ensureWffApk` does this for a bundle today; it is the part not to fork.
   *
   * @throws DwfRuntimeException when packaging fails (aapt2 missing, a malformed resource).
   */
  fun packageApk(source: DwfSource): File

  /**
   * The shared simulator daemon, started on first use with [initialApk] as the face it boots with.
   * One process per host: compose-ai-tools keeps a single `activeDaemon`, so [DwfPreviewSessions]
   * serializes access and re-pushes when sessions alternate.
   *
   * @throws DwfRuntimeException when the process cannot start or does not answer `watchface
   *   status`.
   */
  fun connect(initialApk: File): DwfDaemonConnection

  sealed interface Availability {
    data object Ready : Availability

    /** [code] is a [DwfPreviewSurface] code; [reason] is a sentence for the tab. */
    data class Unavailable(val code: String, val reason: String) : Availability
  }
}

/**
 * The daemon's stdio JSON-lines channel as a function: one command, its `result` (or an exception
 * carrying the daemon's `error`). [DwfDaemonCommands] builds the commands.
 */
internal interface DwfDaemonConnection : AutoCloseable {
  val alive: Boolean

  fun command(command: DwfCommand): JsonElement?
}

internal class DwfRuntimeException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * The production [DwfApkRuntime] until compose-ai-tools exports packaging and the daemon launcher.
 * It can still tell the tab the one thing that is true today and decides whether anything would
 * work: whether the proprietary `DeclarativeWatchFaceRuntime.apk` is installed here (same lookup as
 * `GET /admin/dwf-runtime`), so a host that lacks it says "runtime missing" rather than something
 * about packaging.
 */
internal class RenderHostDwfApkRuntime(
  private val runtimeInstalled: () -> Boolean,
) : DwfApkRuntime {
  override fun availability(): DwfApkRuntime.Availability =
    if (!runtimeInstalled()) {
      DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.RUNTIME_MISSING,
        "The Wear OS watch-face runtime (DeclarativeWatchFaceRuntime.apk) is not installed on " +
          "this server. It is Google's proprietary runtime and is never redistributed; install " +
          "it with `compose-preview dwf-runtime pull` on the host, or PUT /admin/dwf-runtime. " +
          "The fast Remote Compose preview in the editor is unaffected.",
      )
    } else {
      DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.PACKAGING_UNAVAILABLE,
        "The runtime is installed, but this server's compose-ai-tools release does not yet " +
          "export watch-face packaging and the simulator launcher for a design revision " +
          "(DwfApkDaemonBridge is bundle-only).",
      )
    }

  override fun packageApk(source: DwfSource): File =
    throw DwfRuntimeException("watch-face packaging is not exported by compose-ai-tools yet")

  override fun connect(initialApk: File): DwfDaemonConnection =
    throw DwfRuntimeException("the simulator launcher is not exported by compose-ai-tools yet")
}

/**
 * Real-runtime preview sessions for `android-dwf` designs: one per tab, each holding its revision's
 * [DwfSource], its [DwfControls], and — lazily — the packaged APK.
 *
 * ## One daemon, many sessions
 *
 * The runtime daemon is a Robolectric sandbox that holds exactly one watch face at a time, and the
 * host has one of them. Sessions therefore own their state and borrow the daemon: [frame] takes the
 * lock, and when the daemon last served a different session (or a different revision of this one)
 * it pushes this session's APK and replays [DwfDaemonCommands.full] before drawing. When it last
 * served this session it sends only [DwfDaemonCommands.diff]. A crashed daemon is dropped and the
 * next frame starts a fresh one, replaying the same state.
 *
 * Sessions are bounded ([maxSessions]) and expire when untouched ([idleMillis]); expiry is checked
 * on use, so nothing here runs a thread.
 */
internal class DwfPreviewSessions(
  private val runtime: DwfApkRuntime,
  private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
  private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
  private val now: () -> Long = System::currentTimeMillis,
  private val tempDir: File = Files.createTempDirectory("dwf-preview").toFile(),
) {
  class Session
  internal constructor(
    val id: String,
    val designId: String,
    val revision: Long,
    val ownerActorId: String,
    val source: DwfSource,
    startedAt: Long,
  ) {
    @Volatile var controls: DwfControls = DwfControls()
    @Volatile internal var lastUsed: Long = startedAt
    @Volatile internal var apk: File? = null

    /** What the daemon has been brought to for this session; null until it has served one. */
    @Volatile internal var applied: DwfControls? = null

    /** `now` when the ticking clock last (re)started, and the wall time it counts from. */
    @Volatile internal var tickAnchor: Long = startedAt
    @Volatile internal var seq: Long = 0

    /** [controls] with the ticking clock advanced to [now]; a still clock is returned as is. */
    internal fun effective(now: Long): DwfControls {
      val c = controls
      if (!c.ticking) return c
      return c.copy(timeMillis = (c.timeMillis ?: tickAnchor) + (now - tickAnchor))
    }
  }

  sealed interface Started {
    data class Ok(val session: Session) : Started

    data class Refused(val code: String, val reason: String) : Started
  }

  sealed interface Frame {
    data class Ok(val png: ByteArray, val seq: Long, val widthPx: Int?, val heightPx: Int?) : Frame

    data class Failed(val code: String, val reason: String) : Frame
  }

  private val sessions = ConcurrentHashMap<String, Session>()
  private val daemonLock = Any()
  private var connection: DwfDaemonConnection? = null

  /** The session the daemon's current face and state belong to. Guarded by [daemonLock]. */
  private var holder: Pair<String, String>? = null

  fun availability(): DwfApkRuntime.Availability = runtime.availability()

  fun start(
    designId: String,
    revision: Long,
    ownerActorId: String,
    source: DwfSource,
  ): Started {
    (availability() as? DwfApkRuntime.Availability.Unavailable)?.let {
      return Started.Refused(it.code, it.reason)
    }
    source.problem()?.let {
      return Started.Refused(DwfPreviewSurface.SOURCE_INVALID, it)
    }
    reapIdle()
    if (sessions.size >= maxSessions) {
      return Started.Refused(
        DwfPreviewSurface.TOO_MANY_SESSIONS,
        "this server is already drawing $maxSessions watch-face previews with the real " +
          "runtime; close one, or retry shortly (idle ones expire after ${idleMillis / 60_000} min)",
      )
    }
    val session =
      Session(UUID.randomUUID().toString(), designId, revision, ownerActorId, source, now())
    sessions[session.id] = session
    return Started.Ok(session)
  }

  /** The live session, or null once unknown or expired. Touching it keeps it alive. */
  fun get(sessionId: String): Session? {
    val session = sessions[sessionId] ?: return null
    if (now() - session.lastUsed > idleMillis) {
      close(sessionId)
      return null
    }
    session.lastUsed = now()
    return session
  }

  /** Apply a client's control patch; the daemon sees it on the next [frame]. */
  fun patch(sessionId: String, patch: JsonObject): DwfControls.Patched {
    val session = get(sessionId) ?: return DwfControls.Patched.Invalid("no such preview session")
    val current = session.effective(now())
    val result = current.patch(patch)
    if (result is DwfControls.Patched.Ok) {
      // Re-anchoring on every patch keeps "ticking" meaning "from the time you last set".
      session.tickAnchor = now()
      session.controls = result.controls
    }
    return result
  }

  /**
   * Draw the session's face at its controls. Serialized on the shared daemon; takes as long as a
   * screenshot does, so callers run it off the request thread.
   */
  fun frame(sessionId: String): Frame {
    val session = get(sessionId) ?: return Frame.Failed("DWF_NO_SESSION", "no such preview session")
    synchronized(daemonLock) {
      val wanted = session.effective(now())
      return try {
        val apk = session.apk ?: runtime.packageApk(session.source).also { session.apk = it }
        if (
          session.applied?.let { DwfDaemonCommands.clearsDaemonState(it, wanted) } == true &&
            holder?.first == session.id
        ) {
          // A cleared clock/health/battery has no restore command; a fresh daemon is the default.
          dropConnection()
        }
        val daemon = liveConnection(apk)
        val owner = session.id to session.source.digest()
        val before = session.applied
        val commands =
          if (holder != owner || before == null) {
            holder = owner
            // A fresh face: the daemon holds the simulator's defaults, not what this session last
            // asked for, so the whole state is replayed.
            listOf(DwfDaemonCommands.push(apk.canonicalPath)) + DwfDaemonCommands.full(wanted)
          } else {
            DwfDaemonCommands.diff(before, wanted)
          }
        commands.forEach(daemon::command)
        session.applied = wanted
        val shot = Files.createTempFile(tempDir.toPath(), "shot-", ".png").toFile()
        try {
          daemon.command(DwfDaemonCommands.screenshot(shot.absolutePath))
          val bytes = shot.readBytes()
          faceProblem(daemon.command(DwfDaemonCommands.status()))?.let {
            return Frame.Failed(DwfPreviewSurface.DAEMON_FAILED, it)
          }
          if (bytes.isEmpty()) {
            return Frame.Failed(
              DwfPreviewSurface.DAEMON_FAILED,
              "the daemon produced an empty screenshot",
            )
          }
          val (w, h) = pngSize(bytes)
          Frame.Ok(bytes, ++session.seq, w, h)
        } finally {
          shot.delete()
        }
      } catch (e: Exception) {
        // The daemon's state is unknown after a failed command: drop it, so the next frame starts
        // a clean one and replays this session from scratch.
        dropConnection()
        session.applied = null
        Frame.Failed(DwfPreviewSurface.DAEMON_FAILED, e.message ?: e.javaClass.simpleName)
      }
    }
  }

  fun close(sessionId: String) {
    val removed = sessions.remove(sessionId) ?: return
    synchronized(daemonLock) {
      if (holder?.first == removed.id) holder = null
      // The last session out takes the daemon with it: a Robolectric sandbox is not free to idle.
      if (sessions.isEmpty()) dropConnection()
    }
  }

  fun closeAll() {
    sessions.keys.toList().forEach(::close)
  }

  val sessionCount: Int
    get() = sessions.size

  private fun reapIdle() {
    val cutoff = now() - idleMillis
    sessions.values.filter { it.lastUsed < cutoff }.forEach { close(it.id) }
  }

  private fun liveConnection(initialApk: File): DwfDaemonConnection {
    connection
      ?.takeIf { it.alive }
      ?.let {
        return it
      }
    dropConnection()
    return runtime.connect(initialApk).also { connection = it }
  }

  private fun dropConnection() {
    runCatching { connection?.close() }
    connection = null
    holder = null
  }

  companion object {
    const val DEFAULT_MAX_SESSIONS = 4
    const val DEFAULT_IDLE_MILLIS = 15L * 60_000

    /**
     * Why the simulator is not showing the app's face, from a `watchface status` result; the same
     * rule as `DwfApkDaemonBridge.appWatchFaceProblem`, because the simulator draws its built-in
     * demo face when the app's face fails to load, and a screenshot alone cannot tell the two.
     */
    internal fun faceProblem(status: JsonElement?): String? {
      val obj = status as? JsonObject ?: return "the simulator returned no watch face status"
      val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
      val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
      val frames = (obj["frames"] as? JsonPrimitive)?.intOrNull
      return when {
        error != null -> "the watch face failed: $error"
        kind == null || kind == "built-in" ->
          "the watch face did not load; the simulator is showing its built-in face"
        frames != null && frames <= 0 -> "the watch face has not rendered a frame"
        else -> null
      }
    }

    /** Width and height from a PNG's IHDR, or nulls when the bytes are not a PNG. */
    internal fun pngSize(bytes: ByteArray): Pair<Int?, Int?> {
      if (bytes.size < 24 || bytes[1] != 'P'.code.toByte()) return null to null
      fun int(at: Int) =
        (bytes[at].toInt() and 0xff shl 24) or
          (bytes[at + 1].toInt() and 0xff shl 16) or
          (bytes[at + 2].toInt() and 0xff shl 8) or
          (bytes[at + 3].toInt() and 0xff)
      return int(16) to int(20)
    }
  }
}
