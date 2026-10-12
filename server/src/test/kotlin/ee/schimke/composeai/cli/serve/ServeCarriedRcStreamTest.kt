package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServeCarriedRcStream]: the one-shot carried-document render driven as a clock, so a
 * document-only preview's Android player can be watched live.
 */
class ServeCarriedRcStreamTest {

  private val frames = CopyOnWriteArrayList<StreamFrameParams>()

  private val rendered = CopyOnWriteArrayList<PreviewOverrides>()

  private val errors = CopyOnWriteArrayList<String>()

  private var stream: ServeCarriedRcStream? = null

  @AfterTest
  fun stop() {
    stream?.close()
  }

  private fun open(
    clock: () -> Long = System::currentTimeMillis,
    render: (PreviewOverrides) -> ServeCarriedRcStream.Tick,
  ): ServeCarriedRcStream =
    ServeCarriedRcStream(
        base = BASE,
        renderFrame = {
          rendered += it
          render(it)
        },
        maxFps = ServeCarriedRcStream.MAX_FPS,
        onFrame = { frames += it },
        onError = { errors += it },
        clock = clock,
      )
      .also { stream = it }

  private fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "never saw $what" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `each frame samples the document at stream time and keys the render cache on it`() {
    // A stream clock that moves a whole second per read, so consecutive frames are far apart.
    val now = AtomicLong(0)
    open(clock = { now.getAndAdd(1_000) }) {
      ServeCarriedRcStream.Tick.Frame(png(it.captureAdvanceMs!!.toInt()))
    }
    awaitUntil("three frames") { frames.size >= 3 }
    stream!!.close()

    val phases = rendered.map { it.captureAdvanceMs!! }
    assertEquals(phases.sorted(), phases, "time only moves forwards")
    assertEquals(phases.size, phases.toSet().size, "every frame is a new instant")
    assertTrue(phases.all { (it - ServeCarriedRcStream.FIRST_PHASE_MILLIS) % 16 == 0L })
    for (overrides in rendered) {
      // Everything but the instant is the base render: the document, its player and its size.
      assertEquals(BASE.remoteCompose, overrides.remoteCompose)
      assertEquals(BASE.widthPx, overrides.widthPx)
      // The phase is in the knob seeds too, or the host's render cache would answer every frame
      // with the first one: `captureAdvanceMs` is not part of its key.
      assertEquals(
        PreviewOverrideValue.IntValue(overrides.captureAdvanceMs!!.toInt()),
        overrides.namedOverrides!![ServeCarriedRcStream.PHASE_KEY],
      )
    }
    // Painted frames carry their pixels and size, in order, from one stream.
    assertTrue(frames.all { it.payloadBase64 != null && it.keyframe })
    assertEquals(frames.indices.map { it.toLong() }, frames.map { it.seq })
    assertEquals(setOf(48), frames.map { it.widthPx }.toSet())
    assertEquals(1, frames.map { it.frameStreamId }.toSet().size)
  }

  @Test
  fun `unchanged pixels are relayed as heartbeats and slow the stream down`() {
    open { ServeCarriedRcStream.Tick.Frame(png(7)) }
    awaitUntil("a heartbeat") { frames.size >= 3 }
    stream!!.close()
    assertNotNull(frames.first().payloadBase64, "the first frame paints")
    assertTrue(frames.drop(1).all { it.payloadBase64 == null && !it.keyframe })
  }

  @Test
  fun `a warming daemon is asked again rather than ending the stream`() {
    val calls = AtomicLong()
    open {
      if (calls.incrementAndGet() < 3) ServeCarriedRcStream.Tick.Retry(10)
      else ServeCarriedRcStream.Tick.Frame(png(1))
    }
    awaitUntil("the first frame") { frames.isNotEmpty() }
    assertTrue(errors.isEmpty())
  }

  @Test
  fun `a failed render ends the stream and says why`() {
    val reason = AtomicReference<String>()
    open { ServeCarriedRcStream.Tick.Failed("the document does not parse") }
    awaitUntil("the stream to end") { stream!!.isClosed }
    reason.set(errors.single())
    assertEquals("the document does not parse", reason.get())
    assertTrue(frames.isEmpty())
  }

  @Test
  fun `stream time wraps so a long watch never asks for an unbounded advance`() {
    val now = AtomicLong(0)
    val s = open(clock = { now.get() }) { ServeCarriedRcStream.Tick.Retry(60_000) }
    assertEquals(ServeCarriedRcStream.FIRST_PHASE_MILLIS, s.phaseMillis(0))
    assertEquals(ServeCarriedRcStream.FIRST_PHASE_MILLIS + 992, s.phaseMillis(1_000))
    assertEquals(
      ServeCarriedRcStream.FIRST_PHASE_MILLIS + 992,
      s.phaseMillis(ServeCarriedRcStream.PHASE_LOOP_MILLIS + 1_000),
    )
  }

  @Test
  fun `png sizes come from the IHDR and anything else has none`() {
    assertEquals(48 to 24, ServeCarriedRcStream.pngSize(png(0)))
    assertNull(ServeCarriedRcStream.pngSize(byteArrayOf(1, 2, 3)))
  }

  companion object {
    private val BASE =
      PreviewOverrides(
        widthPx = 300,
        heightPx = 150,
        density = 1f,
        remoteCompose =
          ee.schimke.composeai.daemon.protocol.RemoteComposeOverride.Builder()
            .also {
              it.player = RcPlayerBackend.ANDROIDX_VIEW.playerKind
              it.documentBase64 = "AAEC"
            }
            .build(),
      )

    /** A PNG signature and IHDR (48x24) followed by [tag], so frames differ by content. */
    fun png(tag: Int): ByteArray {
      val header =
        byteArrayOf(
          0x89.toByte(),
          'P'.code.toByte(),
          'N'.code.toByte(),
          'G'.code.toByte(),
          0x0d,
          0x0a,
          0x1a,
          0x0a,
          0,
          0,
          0,
          13,
          'I'.code.toByte(),
          'H'.code.toByte(),
          'D'.code.toByte(),
          'R'.code.toByte(),
          0,
          0,
          0,
          48,
          0,
          0,
          0,
          24,
        )
      return header + java.nio.ByteBuffer.allocate(4).putInt(tag).array()
    }
  }
}
