package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The live lane for a Remote Compose document that only a carried-document replay can draw: a
 * document-only catalog's preview on an Android player ([ServeRcPlayerDaemon], or a resident
 * catalog daemon lending itself).
 *
 * A daemon's own live lane is `stream/start`, which holds one composition and pushes its frames.
 * Only a daemon advertising [ServeRcPlayerDaemon.CARRIED_LIVE_CAPABILITY] holds a carried document
 * there, and [ServeCarriedRcLane] prefers that held session whenever the server's player daemon
 * offers one. An older daemon's held session resolves its content from the preview id (the bundle's
 * own IR replay store, else the preview class), never from
 * `overrides.remoteCompose.documentBase64`, which only its one-shot render honours; and a borrowed
 * catalog daemon is never asked. For those this stream is the fallback: the one-shot render driven
 * as a clock: each frame replays the document with `captureAdvanceMs` set to the time since the
 * stream started, so an animated document (`#animation`) plays through the same paused frame clock
 * a capture settles on, and a static one stops costing renders once its frames stop changing
 * ([IDLE_INTERVAL_MILLIS]).
 *
 * What it does not do is take input: there is no held composition for a tap to land on, so [input]
 * is ignored, and the page says so ([ServeWeb]'s `data-live-input`).
 *
 * Frames go to [onFrame] in the daemon's `streamFrame` shape, so [ServeLiveSession] relays them
 * exactly as it relays a daemon's: unchanged pixels as a payload-less heartbeat. A render that
 * fails outright ends the stream through [onError].
 */
class ServeCarriedRcStream
internal constructor(
  /** The carried-document render every frame starts from (document, player, size, theme, seeds). */
  private val base: PreviewOverrides,
  /** Draws one frame. Blocking; called on this stream's own thread, never concurrently. */
  private val renderFrame: (PreviewOverrides) -> Tick,
  maxFps: Int?,
  private val onFrame: (StreamFrameParams) -> Unit,
  private val onError: (String) -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
) : StreamHandle {

  /** What one [renderFrame] call came to. */
  sealed interface Tick {
    /** Fresh pixels for the requested phase. */
    class Frame(val png: ByteArray) : Tick

    /** Nothing to paint yet (a daemon warming, a busy render queue); ask again after [millis]. */
    data class Retry(val millis: Long) : Tick

    /** The document cannot be drawn on this player; the stream ends. */
    data class Failed(val reason: String) : Tick
  }

  private val frameStreamId = "carried-rc-" + NEXT_ID.incrementAndGet()
  private val closed = AtomicBoolean(false)
  private val activeIntervalMillis = 1000L / (maxFps ?: DEFAULT_FPS).coerceIn(1, MAX_FPS)
  private val startedAt = clock()
  private var seq = 0L
  private var lastPng: ByteArray? = null

  /** The current pause between frames: [activeIntervalMillis], doubling while nothing changes. */
  private var intervalMillis = activeIntervalMillis

  @Volatile private var hiddenIntervalMillis: Long? = null

  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "carried-rc-stream").apply { isDaemon = true }
    }

  init {
    executor.execute(::tick)
  }

  /** The `captureAdvanceMs` the next frame samples: stream time, on the 16 ms frame grid. */
  internal fun phaseMillis(now: Long = clock()): Long {
    val elapsed = (now - startedAt).coerceAtLeast(0) % PHASE_LOOP_MILLIS
    return FIRST_PHASE_MILLIS + elapsed / FRAME_MILLIS * FRAME_MILLIS
  }

  private fun tick() {
    if (closed.get()) return
    val phase = phaseMillis()
    val overrides =
      base.copy(
        captureAdvanceMs = phase,
        // The phase in the render cache's key as well: the host caches by override set, and
        // `captureAdvanceMs` is not part of that key, so without it every frame after the first
        // would be the first frame again. A knob seed no replayed document reads.
        namedOverrides =
          (base.namedOverrides.orEmpty() +
            (PHASE_KEY to PreviewOverrideValue.IntValue(phase.toInt()))),
      )
    val renderStartNanos = System.nanoTime()
    val next =
      try {
        when (val tick = renderFrame(overrides)) {
          // The pause is between frame starts, so a slow render eats into it rather than adding to
          // it; never below a short gap, so a waiting `/render` can take the queue between frames.
          is Tick.Frame ->
            (paint(tick.png) - (System.nanoTime() - renderStartNanos) / 1_000_000).coerceAtLeast(
              MIN_FRAME_GAP_MILLIS
            )
          is Tick.Retry -> tick.millis.coerceAtLeast(MIN_RETRY_MILLIS)
          is Tick.Failed -> {
            if (!closed.get()) onError(tick.reason)
            close()
            return
          }
        }
      } catch (e: Exception) {
        if (!closed.get()) onError("live stream stopped: ${e.message ?: e.javaClass.simpleName}")
        close()
        return
      }
    if (closed.get()) return
    val delay = hiddenIntervalMillis?.let { maxOf(it, next) } ?: next
    runCatching { executor.schedule(::tick, delay, TimeUnit.MILLISECONDS) }
  }

  /**
   * Relay [png] (or a heartbeat when it is the last frame again); returns the pause to the next.
   */
  private fun paint(png: ByteArray): Long {
    val unchanged = lastPng?.contentEquals(png) == true
    val size = pngSize(png)
    intervalMillis =
      if (unchanged) (intervalMillis * 2).coerceAtMost(IDLE_INTERVAL_MILLIS)
      else activeIntervalMillis
    lastPng = png
    if (closed.get()) return intervalMillis
    onFrame(
      StreamFrameParams(
        frameStreamId = frameStreamId,
        seq = seq++,
        ptsMillis = clock() - startedAt,
        widthPx = size?.first ?: 0,
        heightPx = size?.second ?: 0,
        codec = StreamCodec.PNG,
        keyframe = !unchanged,
        final = false,
        payloadBase64 = if (unchanged) null else Base64.getEncoder().encodeToString(png),
      )
    )
    return intervalMillis
  }

  /** No held composition takes input; a replayed frame is a picture of the document's clock. */
  override fun input(
    kind: InteractiveInputKind,
    pixelX: Int?,
    pixelY: Int?,
    pointerId: Int?,
    scrollDeltaY: Float?,
    keyCode: String?,
    text: String?,
    pointerType: String?,
  ) = Unit

  /** A hidden watcher keeps the stream alive at [fps] (default 1), as a daemon stream does. */
  override fun visibility(visible: Boolean, fps: Int?) {
    hiddenIntervalMillis = if (visible) null else 1000L / (fps ?: 1).coerceIn(1, MAX_FPS)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    executor.shutdownNow()
  }

  val isClosed: Boolean
    get() = closed.get()

  companion object {
    private val NEXT_ID = AtomicLong()

    /**
     * The knob-seed key that puts a frame's phase into the render cache key. Namespaced so it can
     * never collide with an author's knob, and never read by a replayed document.
     */
    const val PHASE_KEY: String = "composeai.carriedRcStream.phaseMs"

    /**
     * A replayed frame costs a render; four a second shows an animation without hogging a daemon.
     */
    const val DEFAULT_FPS: Int = 4

    const val MAX_FPS: Int = 10

    /** The daemon's frame (16 ms); a phase off the grid would land a frame past it anyway. */
    const val FRAME_MILLIS: Long = 16

    /** The daemon's own default capture advance, so the first frame is the published still's. */
    const val FIRST_PHASE_MILLIS: Long = 32

    /**
     * Where stream time wraps. Each frame advances a fresh composition by its phase, so the cost of
     * a frame grows with it; a minute bounds that while being longer than any loop a catalog draws.
     */
    const val PHASE_LOOP_MILLIS: Long = 60_000

    /** The slowest a stream of unchanging frames polls, so a static document stops costing much. */
    const val IDLE_INTERVAL_MILLIS: Long = 2_000

    private const val MIN_RETRY_MILLIS: Long = 100

    private const val MIN_FRAME_GAP_MILLIS: Long = 50

    /** A PNG's IHDR width and height, or null for bytes that are not a PNG. */
    internal fun pngSize(png: ByteArray): Pair<Int, Int>? {
      if (png.size < 24 || png[1] != 'P'.code.toByte() || png[12] != 'I'.code.toByte()) return null
      fun int(at: Int) =
        ((png[at].toInt() and 0xff) shl 24) or
          ((png[at + 1].toInt() and 0xff) shl 16) or
          ((png[at + 2].toInt() and 0xff) shl 8) or
          (png[at + 3].toInt() and 0xff)
      return int(16) to int(20)
    }
  }
}
