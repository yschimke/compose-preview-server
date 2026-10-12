package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ServeCarriedRcLane]'s choice between a held session and the replay, and its hand-over. */
class ServeCarriedRcLaneTest {

  private class FakeHandle(val name: String) : StreamHandle {
    val input = CopyOnWriteArrayList<InteractiveInputKind>()
    val visibility = CopyOnWriteArrayList<Boolean>()
    @Volatile var closed = false
    var frames: ((StreamFrameParams) -> Unit)? = null

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
      input += kind
    }

    override fun visibility(visible: Boolean, fps: Int?) {
      visibility += visible
    }

    override fun close() {
      closed = true
    }

    fun emit(seq: Long) =
      frames?.invoke(
        StreamFrameParams(frameStreamId = name, seq = seq, ptsMillis = 0, widthPx = 1, heightPx = 1)
      )
  }

  private val frames = CopyOnWriteArrayList<String>()
  private val lanes = CopyOnWriteArrayList<Boolean>()

  @Test
  fun `a held session opens straight away and takes input`() {
    val held = FakeHandle("held")
    val lane =
      ServeCarriedRcLane(
        openHeld = {
          held.frames = it
          ServeCarriedRcLane.Attempt.Held(held)
        },
        openReplay = { error("no replay when a held session opens") },
        onFrame = { frames += it.frameStreamId },
        onLane = { lanes += it },
      )
    assertTrue(lane.takesInput)
    assertEquals(listOf(true), lanes)
    lane.input(InteractiveInputKind.CLICK, 1, 1)
    assertEquals(listOf(InteractiveInputKind.CLICK), held.input)
    lane.close()
    assertTrue(held.closed)
  }

  @Test
  fun `a daemon that never holds the document keeps the replay`() {
    val replay = FakeHandle("replay")
    val lane =
      ServeCarriedRcLane(
        openHeld = { ServeCarriedRcLane.Attempt.Never("old daemon") },
        openReplay = {
          replay.frames = it
          replay
        },
        onFrame = { frames += it.frameStreamId },
        onLane = { lanes += it },
      )
    assertFalse(lane.takesInput)
    assertEquals(listOf(false), lanes)
    replay.emit(0)
    assertEquals(listOf("replay"), frames)
    lane.close()
    assertTrue(replay.closed)
  }

  @Test
  fun `a warming daemon hands over from the replay once its held session opens`() {
    val replay = FakeHandle("replay")
    val held = FakeHandle("held")
    val attempts = AtomicInteger()
    val adopted = CountDownLatch(1)
    val lane =
      ServeCarriedRcLane(
        openHeld = {
          if (attempts.incrementAndGet() < 3) ServeCarriedRcLane.Attempt.NotYet
          else {
            held.frames = it
            ServeCarriedRcLane.Attempt.Held(held)
          }
        },
        openReplay = {
          replay.frames = it
          replay
        },
        onFrame = { frames += it.frameStreamId },
        onLane = {
          lanes += it
          if (it) adopted.countDown()
        },
        retryMillis = 10,
      )
    // Hidden while warming: the held session must open hidden too.
    lane.visibility(false, 1)
    replay.emit(0)
    assertTrue(adopted.await(5, TimeUnit.SECONDS))
    assertEquals(listOf(false, true), lanes)
    assertTrue(replay.closed, "the replay stops once the held session plays")
    assertEquals(listOf(false), held.visibility)
    // A replay frame already in flight is not painted over the held session.
    replay.emit(1)
    held.emit(0)
    assertEquals(listOf("replay", "held"), frames)
    lane.input(InteractiveInputKind.POINTER_DOWN, 2, 2)
    assertEquals(listOf(InteractiveInputKind.POINTER_DOWN), held.input)
    assertTrue(replay.input.isEmpty())
    lane.close()
    assertTrue(held.closed)
  }
}
