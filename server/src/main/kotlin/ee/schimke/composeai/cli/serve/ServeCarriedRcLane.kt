package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The live lane of a document-only preview on an Android player: a **held** session playing the
 * carried document when a daemon can hold one ([ServeRcPlayerDaemon.openLive]), so viewer input
 * reaches the document, else the repeated-render replay ([ServeCarriedRcStream]), which animates
 * but takes no input.
 *
 * A cold player daemon cannot say which it is until it has started (its `initialize` capabilities
 * decide), and a start takes ~15 s. So the lane opens on the replay — which is what warms the
 * daemon — and keeps asking for the held session every [retryMillis] for up to [maxWaitMillis].
 * When one opens, frames switch to it and the replay closes; when the daemon answers it never will
 * ([Attempt.Never]), the replay simply stays. [onLane] reports each change (true: input is
 * forwarded) so the socket can tell the page which it is looking at.
 *
 * Frames from both sources go to [onFrame], gated so a replay frame already in flight when the held
 * session takes over is dropped rather than painted over it.
 */
class ServeCarriedRcLane
internal constructor(
  /** Ask for the held session; frames it opens with go to the callback. Blocking. */
  private val openHeld: ((StreamFrameParams) -> Unit) -> Attempt,
  /** Open the replay, frames to the callback. */
  private val openReplay: ((StreamFrameParams) -> Unit) -> StreamHandle,
  private val onFrame: (StreamFrameParams) -> Unit,
  private val onLane: (takesInput: Boolean) -> Unit,
  private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
  private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) : StreamHandle {

  /** What one [openHeld] call came to. */
  sealed interface Attempt {
    class Held(val handle: StreamHandle) : Attempt

    /** Not yet (a daemon starting); worth asking again. */
    data object NotYet : Attempt

    /** Not here: no daemon, or one that cannot hold this document. */
    data class Never(val reason: String) : Attempt
  }

  private val lock = Any()
  private var current: StreamHandle? = null
  private var held = false
  private var closed = false
  private var visible = true
  private var visibilityFps: Int? = null
  private val startedAt = clock()
  private var executor: ScheduledExecutorService? = null

  init {
    when (val first = openHeld(::onHeldFrame)) {
      is Attempt.Held -> adopt(first.handle)
      Attempt.NotYet -> {
        startReplay()
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
          Thread(runnable, "carried-rc-lane").apply { isDaemon = true }
        }
        executor = scheduler
        scheduler.schedule(::retry, retryMillis, TimeUnit.MILLISECONDS)
      }
      is Attempt.Never -> startReplay()
    }
  }

  /** Whether input reaches a held composition now. */
  val takesInput: Boolean
    get() = synchronized(lock) { held }

  val isClosed: Boolean
    get() = synchronized(lock) { closed }

  private fun onHeldFrame(frame: StreamFrameParams) = onFrame(frame)

  private fun onReplayFrame(frame: StreamFrameParams) {
    if (synchronized(lock) { held || closed }) return
    onFrame(frame)
  }

  private fun startReplay() {
    val replay = openReplay(::onReplayFrame)
    synchronized(lock) {
      if (closed) {
        replay.close()
        return
      }
      current = replay
      if (!visible) replay.visibility(false, visibilityFps)
    }
    onLane(false)
  }

  /** Make [handle] the lane's stream, closing the replay it replaces. */
  private fun adopt(handle: StreamHandle) {
    val replaced =
      synchronized(lock) {
        if (closed) {
          handle.close()
          return
        }
        val previous = current
        current = handle
        held = true
        if (!visible) handle.visibility(false, visibilityFps)
        previous
      }
    runCatching { replaced?.close() }
    onLane(true)
  }

  private fun retry() {
    if (isClosed) return
    when (val next = runCatching { openHeld(::onHeldFrame) }.getOrElse { Attempt.Never("") }) {
      is Attempt.Held -> {
        adopt(next.handle)
        executor?.shutdown()
      }
      Attempt.NotYet ->
        if (clock() - startedAt < maxWaitMillis && !isClosed) {
          runCatching { executor?.schedule(::retry, retryMillis, TimeUnit.MILLISECONDS) }
        } else executor?.shutdown()
      is Attempt.Never -> executor?.shutdown()
    }
  }

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
    val target = synchronized(lock) { current }
    target?.input(kind, pixelX, pixelY, pointerId, scrollDeltaY, keyCode, text, pointerType)
  }

  override fun visibility(visible: Boolean, fps: Int?) {
    val target =
      synchronized(lock) {
        this.visible = visible
        visibilityFps = fps
        current
      }
    target?.visibility(visible, fps)
  }

  override fun close() {
    val target =
      synchronized(lock) {
        if (closed) return
        closed = true
        current.also { current = null }
      }
    executor?.shutdownNow()
    runCatching { target?.close() }
  }

  companion object {
    const val DEFAULT_RETRY_MILLIS: Long = 1_000

    /** A cold Robolectric daemon may download its runtime first; beyond this, keep the replay. */
    const val DEFAULT_MAX_WAIT_MILLIS: Long = 3 * 60_000L
  }
}
