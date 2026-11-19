package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the sessions need from the host to draw with the real runtime, as three calls that know
 * nothing about processes: [availability], [packageApk] (`:wff-packager`) and [connect]
 * (compose-ai-tools' exported `DwfSimulatorLauncher`). [RenderHostDwfApkRuntime] is the production
 * binding and a fake stands in for it in tests.
 */
internal interface DwfApkRuntime {
  /** Whether this host can draw a watch face with the real runtime, and if not, why not. */
  fun availability(): Availability

  /**
   * Package [source] as the signed APK the runtime loads, cached by [DwfSource.digest].
   *
   * @throws DwfPackagingFailed when the packager refuses the face, with its diagnostics.
   * @throws DwfRuntimeException on any other failure (aapt2 could not run, the disk).
   */
  fun packageApk(source: DwfSource): File

  /**
   * Whether [packageApk] would answer [source] from its cache without running the packager, so a
   * session can say "Using the cached package" instead of showing a packaging step. A hint: false
   * is always safe.
   */
  fun isPackaged(source: DwfSource): Boolean = false

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

internal open class DwfRuntimeException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * Real-runtime preview sessions for `android-dwf` designs: one per tab, each holding its revision's
 * [DwfSource], its [DwfControls], and — lazily — the packaged APK.
 *
 * ## One daemon, many sessions
 *
 * The runtime daemon is a Robolectric sandbox that holds exactly one watch face at a time, and the
 * host has one of them. Sessions therefore own their state and borrow the daemon: [frame] takes the
 * lock, and when the daemon last served a different session (or a different revision of this one)
 * it pushes this session's APK, replays [DwfDaemonCommands.full] and waits for the face's first
 * frame before drawing. When it last served this session it sends only [DwfDaemonCommands.diff]. A
 * crashed daemon is dropped and the next frame starts a fresh one, replaying the same state.
 *
 * ## Getting to the first frame
 *
 * A cold first frame is slow (a large face spends minutes in the packager and the runtime), so
 * every session reports where it is as a [DwfStage] ([progress]). [start] packages on the caller's
 * thread, so a packager refusal is a refusal of the request; [startAsync] answers at once and does
 * the export, the packaging and the first frame on [executor], so a client can watch the stages
 * and, when every runtime slot is taken, its place in the queue.
 *
 * ## Bounds and release
 *
 * At most [maxSessions] sessions hold a runtime slot; [startAsync] queues up to [maxQueued] more. A
 * session is released when it is closed, when it is untouched for [idleMillis], when no socket has
 * been attached to it for [detachedGraceMillis] (a closed pane or tab), and — at once — when its
 * owner starts another session on the same design while it has no socket (a Reload). Release is
 * checked on use and on every start, so nothing here runs a timer.
 */
internal class DwfPreviewSessions(
  private val runtime: DwfApkRuntime,
  private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
  private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
  private val now: () -> Long = System::currentTimeMillis,
  private val tempDir: File = Files.createTempDirectory("dwf-preview").toFile(),
  private val maxQueued: Int = DEFAULT_MAX_QUEUED,
  private val detachedGraceMillis: Long = DEFAULT_DETACHED_GRACE_MILLIS,
  /** Runs [startAsync]'s preparation; a direct executor in tests. */
  private val executor: Executor = defaultExecutor(),
  /** How the first-frame and settle waits pause between `watchface status` polls. */
  private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
  class Session
  internal constructor(
    val id: String,
    val designId: String,
    val revision: Long,
    val ownerActorId: String,
    wffVersion: Int,
    val startedAt: Long,
    initialControls: DwfControls,
  ) {
    /** Null until an async start has exported it; always set for a [start]ed session. */
    @Volatile
    var sourceOrNull: DwfSource? = null
      internal set

    val source: DwfSource
      get() = checkNotNull(sourceOrNull) { "the session's watch face is not ready yet" }

    @Volatile
    var wffVersion: Int = wffVersion
      internal set

    @Volatile var controls: DwfControls = initialControls
    @Volatile internal var lastUsed: Long = startedAt
    @Volatile internal var apk: File? = null

    /** What the daemon has been brought to for this session; null until it has served one. */
    @Volatile internal var applied: DwfControls? = null

    /** `now` when the ticking clock last (re)started, and the wall time it counts from. */
    @Volatile internal var tickAnchor: Long = startedAt
    @Volatile internal var seq: Long = 0

    @Volatile internal var stage: DwfStage = DwfStage.PACKAGING
    @Volatile internal var stageSince: Long = startedAt
    @Volatile internal var reachedStep: Int = 0
    @Volatile internal var cachedPackage: Boolean = false
    @Volatile internal var failure: Frame.Failed? = null

    /** True while [startAsync]'s preparation is running for this session. */
    @Volatile internal var preparing: Boolean = false

    /** The frame an async start drew as its last step, handed to the first socket that asks. */
    @Volatile internal var warmFrame: Frame.Ok? = null

    internal val sockets = AtomicInteger(0)
    @Volatile internal var everAttached: Boolean = false
    @Volatile internal var detachedSince: Long = startedAt

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

  /** What [startAsync]'s source step produced: the face, or why there is none. */
  sealed interface Resolution {
    data class Ok(val source: DwfSource) : Resolution

    data class Refused(val code: String, val reason: String) : Resolution
  }

  /** A session's way to its first frame, as the status route and the socket's `stage` say it. */
  data class Progress(
    val stage: DwfStage,
    val step: Int,
    val steps: Int,
    val label: String,
    val elapsedMillis: Long,
    val stageElapsedMillis: Long,
    val cachedPackage: Boolean,
    /** Sessions ahead of this one for a runtime slot; set only while [DwfStage.QUEUED]. */
    val queueAhead: Int?,
    val code: String?,
    val reason: String?,
  )

  private val sessions = ConcurrentHashMap<String, Session>()
  private val daemonLock = Any()
  private var connection: DwfDaemonConnection? = null

  /** The session the daemon's current face and state belong to. Guarded by [daemonLock]. */
  private var holder: Pair<String, String>? = null

  /**
   * Sessions waiting for a runtime slot, in arrival order. Guarded by [slotLock], which is never
   * held while taking [daemonLock].
   */
  private val waiting = LinkedHashSet<String>()
  private val slotLock = Object()

  fun availability(): DwfApkRuntime.Availability = runtime.availability()

  /**
   * Start a session and package its face on the caller's thread; a packager refusal is a
   * [Started.Refused]. The first frame — and so the runtime stages — happen on the first [frame].
   */
  fun start(
    designId: String,
    revision: Long,
    ownerActorId: String,
    source: DwfSource,
    controls: DwfControls = DwfControls(),
  ): Started {
    (availability() as? DwfApkRuntime.Availability.Unavailable)?.let {
      return Started.Refused(it.code, it.reason)
    }
    source.problem()?.let {
      return Started.Refused(DwfPreviewSurface.SOURCE_INVALID, it)
    }
    reapIdle()
    releaseReplaced(ownerActorId, designId)
    if (occupying() >= maxSessions) return full()
    // Packaged here rather than on the first frame, so a face the packager refuses is a refusal of
    // the start request (with the packager's diagnostics) instead of an error on a socket. Cached
    // by digest, so the first frame and every later session on this revision reuse it.
    val cached = runtime.isPackaged(source)
    val apk =
      try {
        runtime.packageApk(source)
      } catch (e: DwfPackagingFailed) {
        return Started.Refused(DwfPreviewSurface.PACKAGING_FAILED, e.message ?: "packaging failed")
      } catch (e: DwfRuntimeException) {
        return Started.Refused(
          DwfPreviewSurface.PACKAGING_UNAVAILABLE,
          e.message ?: "packaging is unavailable",
        )
      }
    val session =
      Session(
        UUID.randomUUID().toString(),
        designId,
        revision,
        ownerActorId,
        source.wffVersion,
        now(),
        controls,
      )
    session.sourceOrNull = source
    session.apk = apk
    session.cachedPackage = cached
    session.reachedStep = DwfStage.PACKAGING.step
    sessions[session.id] = session
    return Started.Ok(session)
  }

  /**
   * Start a session and answer at once: [resolve] (the export, when [exports]), the packaging and
   * the first frame run on [executor], and the session's [progress] says which is running. When
   * every runtime slot is taken the session waits in a queue of at most [maxQueued].
   */
  fun startAsync(
    designId: String,
    revision: Long,
    ownerActorId: String,
    wffVersion: Int,
    exports: Boolean,
    controls: DwfControls = DwfControls(),
    resolve: () -> Resolution,
  ): Started {
    (availability() as? DwfApkRuntime.Availability.Unavailable)?.let {
      return Started.Refused(it.code, it.reason)
    }
    reapIdle()
    releaseReplaced(ownerActorId, designId)
    val session =
      Session(
        UUID.randomUUID().toString(),
        designId,
        revision,
        ownerActorId,
        wffVersion,
        now(),
        controls,
      )
    session.preparing = true
    session.stage = if (exports) DwfStage.EXPORTING else DwfStage.PACKAGING
    session.reachedStep = session.stage.step
    synchronized(slotLock) {
      // Anyone already waiting goes first: a slot freed a moment ago belongs to the head of the
      // queue, whose worker may not have woken yet, not to whoever asks next.
      if (occupying() >= maxSessions || waiting.isNotEmpty()) {
        if (waiting.size >= maxQueued) return full()
        session.stage = DwfStage.QUEUED
        waiting += session.id
      }
      sessions[session.id] = session
    }
    try {
      executor.execute { prepare(session, exports, resolve) }
    } catch (e: RejectedExecutionException) {
      close(session.id)
      return Started.Refused(DwfPreviewSurface.DAEMON_FAILED, "the server is shutting down")
    }
    return Started.Ok(session)
  }

  private fun prepare(session: Session, exports: Boolean, resolve: () -> Resolution) {
    try {
      if (!awaitSlot(session, if (exports) DwfStage.EXPORTING else DwfStage.PACKAGING)) return
      val source =
        when (val resolved = resolve()) {
          is Resolution.Ok -> resolved.source
          is Resolution.Refused -> return fail(session, resolved.code, resolved.reason)
        }
      source.problem()?.let {
        return fail(session, DwfPreviewSurface.SOURCE_INVALID, it)
      }
      session.wffVersion = source.wffVersion
      session.sourceOrNull = source
      session.cachedPackage = runtime.isPackaged(source)
      enter(session, DwfStage.PACKAGING)
      session.apk =
        try {
          runtime.packageApk(source)
        } catch (e: DwfPackagingFailed) {
          return fail(session, DwfPreviewSurface.PACKAGING_FAILED, e.message ?: "packaging failed")
        } catch (e: DwfRuntimeException) {
          return fail(
            session,
            DwfPreviewSurface.PACKAGING_UNAVAILABLE,
            e.message ?: "packaging is unavailable",
          )
        }
      if (sessions[session.id] !== session) return
      // The first frame is drawn here, so the runtime stages are reported before any socket asks,
      // and the socket that does is handed this frame rather than waiting for another.
      (frame(session.id) as? Frame.Ok)?.let { session.warmFrame = it }
    } catch (e: Exception) {
      fail(session, DwfPreviewSurface.DAEMON_FAILED, e.message ?: e.javaClass.simpleName)
    } finally {
      session.preparing = false
    }
  }

  /** Wait for a runtime slot; false when the session was closed while it waited. */
  private fun awaitSlot(session: Session, next: DwfStage): Boolean {
    while (true) {
      reapIdle()
      synchronized(slotLock) {
        if (sessions[session.id] !== session) return false
        if (session.stage != DwfStage.QUEUED) return true
        if (waiting.firstOrNull() == session.id && occupying() < maxSessions) {
          waiting.remove(session.id)
          enter(session, next)
          return true
        }
        slotLock.wait(QUEUE_POLL_MILLIS)
      }
    }
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

  /** A socket is showing this session; while one is, it is never released as abandoned. */
  fun attach(session: Session) {
    session.everAttached = true
    session.sockets.incrementAndGet()
    session.lastUsed = now()
  }

  fun detach(session: Session) {
    if (session.sockets.decrementAndGet() <= 0) {
      session.sockets.set(0)
      session.detachedSince = now()
    }
  }

  fun progress(session: Session): Progress {
    val at = now()
    val stage = session.stage
    val ahead =
      if (stage == DwfStage.QUEUED)
        synchronized(slotLock) { waiting.indexOf(session.id) }.takeIf { it >= 0 }
      else null
    val failure = session.failure.takeIf { stage == DwfStage.FAILED }
    val label =
      when (stage) {
        DwfStage.QUEUED ->
          if ((ahead ?: 0) == 0) "Waiting for a free runtime (next in line)"
          else "Waiting for a free runtime ($ahead ahead)"
        DwfStage.EXPORTING -> "Exporting the watch face"
        DwfStage.PACKAGING ->
          if (session.cachedPackage) "Using the cached package" else "Packaging the watch face"
        DwfStage.STARTING_RUNTIME -> "Starting the watch-face runtime"
        DwfStage.INSTALLING -> "Installing the watch face"
        DwfStage.LOADING_FACE -> "Loading the watch face"
        DwfStage.LIVE -> "Live"
        DwfStage.FAILED -> failure?.reason ?: "failed"
      }
    return Progress(
      stage = stage,
      step = if (stage == DwfStage.FAILED) session.reachedStep else stage.step,
      steps = DwfStage.STEPS,
      label = label,
      elapsedMillis = at - session.startedAt,
      stageElapsedMillis = at - session.stageSince,
      cachedPackage = session.cachedPackage,
      queueAhead = ahead,
      code = failure?.code,
      reason = failure?.reason,
    )
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
   * screenshot does (and, on a fresh face, as long as the runtime takes to draw it), so callers run
   * it off the request thread.
   */
  fun frame(sessionId: String): Frame {
    val session = get(sessionId) ?: return Frame.Failed("DWF_NO_SESSION", "no such preview session")
    val source =
      session.sourceOrNull
        ?: return session.failure
          ?: Frame.Failed(DwfPreviewSurface.SOURCE_UNAVAILABLE, "the watch face is not ready yet")
    // Packaging happens at start, never here: a refusal there is the session's failure, and must
    // not become an exception that drops the daemon every other session is sharing.
    val apk =
      session.apk
        ?: return session.failure
          ?: Frame.Failed(DwfPreviewSurface.SOURCE_UNAVAILABLE, "the watch face is not ready yet")
    // A failed session gave its runtime slot up; drawing again takes one back through the same
    // admission as a start, so a retry can neither exceed the bound nor jump the queue.
    if (session.stage == DwfStage.FAILED && !readmit(session)) {
      return session.failure
        ?: Frame.Failed(DwfPreviewSurface.TOO_MANY_SESSIONS, "no runtime slot is free")
    }
    synchronized(daemonLock) {
      val wanted = session.effective(now())
      return try {
        if (
          session.applied?.let { DwfDaemonCommands.clearsDaemonState(it, wanted) } == true &&
            holder?.first == session.id
        ) {
          // A cleared clock/health/battery has no restore command; a fresh daemon is the default.
          dropConnection()
        }
        if (connection?.alive != true) enter(session, DwfStage.STARTING_RUNTIME)
        val daemon = liveConnection(apk)
        val owner = session.id to source.digest()
        val before = session.applied
        val status = { daemon.command(DwfDaemonCommands.status()) }
        if (holder != owner || before == null) {
          holder = owner
          // A fresh face: the daemon holds the simulator's defaults, not what this session last
          // asked for, so the whole state is replayed.
          enter(session, DwfStage.INSTALLING)
          daemon.command(DwfDaemonCommands.push(apk.canonicalPath))
          enter(session, DwfStage.LOADING_FACE)
          DwfDaemonCommands.full(wanted).forEach(daemon::command)
          // The push answers before the runtime has drawn anything; a screenshot now is the
          // simulator's built-in face (or "has not rendered a frame"). Wait, bounded, as
          // `DwfApkDaemonBridge` does, then let the replayed overrides settle.
          poll(status, FIRST_FRAME_POLLS, FIRST_FRAME_POLL_MILLIS, ::isWaitingForFrame)
          poll(status, SETTLE_POLLS, SETTLE_POLL_MILLIS, ::isRendering)
        } else {
          val commands = DwfDaemonCommands.diff(before, wanted)
          commands.forEach(daemon::command)
          // Each override queues a render and returns at once; without this the screenshot is
          // the frame from before it, one tick behind.
          if (commands.isNotEmpty()) poll(status, SETTLE_POLLS, SETTLE_POLL_MILLIS, ::isRendering)
        }
        session.applied = wanted
        val shot = Files.createTempFile(tempDir.toPath(), "shot-", ".png").toFile()
        try {
          daemon.command(DwfDaemonCommands.screenshot(shot.absolutePath))
          val bytes = shot.readBytes()
          faceProblem(daemon.command(DwfDaemonCommands.status()))?.let {
            return failed(session, Frame.Failed(DwfPreviewSurface.DAEMON_FAILED, it))
          }
          if (bytes.isEmpty()) {
            return failed(
              session,
              Frame.Failed(
                DwfPreviewSurface.DAEMON_FAILED,
                "the daemon produced an empty screenshot",
              ),
            )
          }
          val (w, h) = pngSize(bytes)
          enter(session, DwfStage.LIVE)
          Frame.Ok(bytes, ++session.seq, w, h)
        } finally {
          shot.delete()
        }
      } catch (e: Exception) {
        // The daemon's state is unknown after a failed command: drop it, so the next frame starts
        // a clean one and replays this session from scratch.
        dropConnection()
        session.applied = null
        failed(
          session,
          Frame.Failed(DwfPreviewSurface.DAEMON_FAILED, e.message ?: e.javaClass.simpleName),
        )
      }
    }
  }

  fun close(sessionId: String) {
    val removed = release(sessionId) ?: return
    releaseDaemon(removed)
  }

  /**
   * Take a session out of the bounds and the queue at once, without waiting for [daemonLock]: a
   * start that releases an abandoned session must not wait behind another session's first frame (up
   * to 90 s) to answer. The daemon's own cleanup follows on [executor].
   */
  private fun releaseDeferred(sessionId: String) {
    val removed = release(sessionId) ?: return
    try {
      executor.execute { releaseDaemon(removed) }
    } catch (_: RejectedExecutionException) {
      releaseDaemon(removed)
    }
  }

  private fun release(sessionId: String): Session? {
    val removed = sessions.remove(sessionId) ?: return null
    synchronized(slotLock) {
      waiting.remove(removed.id)
      slotLock.notifyAll()
    }
    return removed
  }

  private fun releaseDaemon(removed: Session) {
    synchronized(daemonLock) {
      if (holder?.first == removed.id) holder = null
      // The last session out takes the daemon with it: a Robolectric sandbox is not free to idle.
      if (sessions.isEmpty()) dropConnection()
    }
  }

  fun closeAll() {
    sessions.keys.toList().forEach(::close)
  }

  /** Sessions holding a runtime slot, after releasing abandoned ones. */
  val sessionCount: Int
    get() {
      reapIdle()
      return occupying()
    }

  /** Sessions waiting in the queue for a slot. */
  val queuedCount: Int
    get() = synchronized(slotLock) { waiting.size }

  private fun occupying(): Int =
    sessions.values.count { it.stage != DwfStage.QUEUED && it.stage != DwfStage.FAILED }

  private fun full(): Started.Refused =
    Started.Refused(
      DwfPreviewSurface.TOO_MANY_SESSIONS,
      "this server is already drawing $maxSessions watch-face previews with the real " +
        "runtime; close one, or retry shortly (idle ones expire after ${idleMillis / 60_000} min)",
    )

  private fun readmit(session: Session): Boolean =
    synchronized(slotLock) {
      if (session.stage != DwfStage.FAILED) return@synchronized true
      if (occupying() >= maxSessions || waiting.isNotEmpty()) return@synchronized false
      // Back to the runtime stages; the frame moves it on from here (or fails it again).
      session.stage = DwfStage.INSTALLING
      session.stageSince = now()
      true
    }

  /** Move a session forward. Once it is live it stays live: a re-push is not a reload. */
  private fun enter(session: Session, stage: DwfStage) {
    if (session.stage == DwfStage.LIVE && stage != DwfStage.LIVE) return
    if (stage.step > 0) session.reachedStep = stage.step
    if (session.stage == stage) return
    session.stage = stage
    session.stageSince = now()
    if (stage == DwfStage.LIVE) session.failure = null
  }

  private fun fail(session: Session, code: String, reason: String) {
    failed(session, Frame.Failed(code, reason))
  }

  /** Record [failure] on a session that is not live yet, and free its runtime slot. */
  private fun failed(session: Session, failure: Frame.Failed): Frame.Failed {
    if (session.stage != DwfStage.LIVE) {
      session.failure = failure
      session.stage = DwfStage.FAILED
      session.stageSince = now()
      synchronized(slotLock) { slotLock.notifyAll() }
    }
    return failure
  }

  private fun reapIdle() {
    val at = now()
    sessions.values
      .filter {
        at - it.lastUsed > idleMillis ||
          (it.sockets.get() == 0 &&
            // A preparing session is busy, not abandoned; one still queued has cost nothing yet.
            (!it.preparing || it.stage == DwfStage.QUEUED) &&
            // A client polling the status route instead of holding a socket is still there.
            at - maxOf(it.detachedSince, it.lastUsed) > detachedGraceMillis)
      }
      .forEach { releaseDeferred(it.id) }
  }

  /**
   * A new start by [ownerActorId] on [designId] replaces that owner's sessions there that had a
   * socket and lost it: the pane's Reload, or a tab reopened. One still showing in another tab
   * keeps its socket and is left alone, as is one whose first socket has not connected yet.
   */
  private fun releaseReplaced(ownerActorId: String, designId: String) {
    sessions.values
      .filter {
        it.ownerActorId == ownerActorId &&
          it.designId == designId &&
          it.everAttached &&
          it.sockets.get() == 0
      }
      .forEach { releaseDeferred(it.id) }
  }

  private fun poll(
    status: () -> JsonElement?,
    polls: Int,
    pollMillis: Long,
    waitWhile: (JsonElement?) -> Boolean,
  ) {
    var remaining = polls
    while (waitWhile(status()) && remaining-- > 0) sleep(pollMillis)
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
    const val DEFAULT_MAX_QUEUED = 8

    /** How long a session with no socket survives: a reload reattaches well inside it. */
    const val DEFAULT_DETACHED_GRACE_MILLIS = 60_000L

    /** `DwfApkDaemonBridge`'s bounds: 90 s for a face's first frame, a short settle after. */
    private const val FIRST_FRAME_POLLS = 450
    private const val FIRST_FRAME_POLL_MILLIS = 200L
    private const val SETTLE_POLLS = 15
    private const val SETTLE_POLL_MILLIS = 100L
    private const val QUEUE_POLL_MILLIS = 1_000L

    private fun defaultExecutor(): Executor = Executors.newCachedThreadPool { r ->
      Thread(r, "dwf-preview-start").apply { isDaemon = true }
    }

    /** The app's face is loaded but has not drawn yet; an error or the built-in face is final. */
    internal fun isWaitingForFrame(status: JsonElement?): Boolean {
      val obj = status as? JsonObject ?: return false
      if ((obj["error"] as? JsonPrimitive)?.contentOrNull != null) return false
      val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
      val frames = (obj["frames"] as? JsonPrimitive)?.intOrNull
      return kind != null && kind != "built-in" && (frames == null || frames <= 0)
    }

    internal fun isRendering(status: JsonElement?): Boolean =
      ((status as? JsonObject)?.get("rendering") as? JsonPrimitive)?.booleanOrNull == true

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
