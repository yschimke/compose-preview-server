package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeThumbWarmerTest {

  private class WarmHost(
    override val previews: List<ServePreview> = emptyList(),
    val gate: CountDownLatch? = null,
    val fail: Boolean = false,
  ) : ServeHost {
    override val label: String = "warm"
    val warmed = ConcurrentHashMap.newKeySet<String>()
    val threads = ConcurrentHashMap.newKeySet<String>()
    val calls = AtomicInteger()

    override fun warmBakedRender(previewId: String) {
      calls.incrementAndGet()
      warmed.add(previewId)
      threads.add(Thread.currentThread().name)
      gate?.await(5, TimeUnit.SECONDS)
      if (fail) throw IllegalStateException("branch blip")
    }

    // Unused by the warmer, which only ever calls [warmBakedRender] — that narrowness is the
    // contract: warming must never render, and never wake a daemon.
    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      throw AssertionError("the warmer must never render")

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = throw AssertionError("the warmer must never stream")

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  private fun drain(warmer: ServeThumbWarmer, host: WarmHost, expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (host.calls.get() < expected && System.nanoTime() < deadline) Thread.sleep(5)
    warmer.stop()
  }

  @Test
  fun `a queued preview is warmed off the caller's thread`() {
    val host = WarmHost()
    val warmer = ServeThumbWarmer()
    warmer.enqueue(host, "a")
    warmer.enqueue(host, "b")
    drain(warmer, host, 2)

    assertEquals(setOf("a", "b"), host.warmed)
  }

  @Test
  fun `the same preview is not warmed twice while it is in flight`() {
    // A catalog page reloaded while its fetches are still running must not queue them again — the
    // whole point of the miss being cheap is that every build can report it.
    val gate = CountDownLatch(1)
    val host = WarmHost(gate = gate)
    val warmer = ServeThumbWarmer()
    repeat(20) { warmer.enqueue(host, "a") }
    // One worker has picked it up and is parked on the gate; the other 19 offers were deduped.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (host.calls.get() < 1 && System.nanoTime() < deadline) Thread.sleep(5)
    assertEquals(1, host.calls.get(), "an in-flight preview is offered once")
    gate.countDown()
    warmer.stop()
  }

  @Test
  fun `a failed fetch is retried on the next page build`() {
    // `bakedPngFile` already declines to remember a failure so a transient branch blip self-heals.
    // The warmer must not undo that by keeping the preview marked in flight forever.
    val host = WarmHost(fail = true)
    val warmer = ServeThumbWarmer()
    warmer.enqueue(host, "a")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (host.calls.get() < 1 && System.nanoTime() < deadline) Thread.sleep(5)

    // Second offer, as the next page build would make: it must be accepted, not swallowed.
    while (host.calls.get() < 2 && System.nanoTime() < deadline) {
      warmer.enqueue(host, "a")
      Thread.sleep(5)
    }
    warmer.stop()
    assertTrue(host.calls.get() >= 2, "a failure leaves the preview offerable again")
  }

  @Test
  fun `two hosts of the same catalog are warmed independently`() {
    // Keyed on the host instance, like ServeHeroImages' bakes: a refresh installs a fresh host and
    // its pixels are a different question from the retired one's.
    val old = WarmHost()
    val fresh = WarmHost()
    val warmer = ServeThumbWarmer()
    warmer.enqueue(old, "a")
    warmer.enqueue(fresh, "a")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while ((old.calls.get() + fresh.calls.get()) < 2 && System.nanoTime() < deadline) Thread.sleep(
      5
    )
    warmer.stop()

    assertEquals(setOf("a"), old.warmed)
    assertEquals(setOf("a"), fresh.warmed)
  }

  @Test
  fun `a full queue drops rather than running the fetch on the caller`() {
    // DiscardPolicy, not CallerRuns: running here would put a delivery-branch round trip on the
    // page-build thread, which is the one thing this lane exists to avoid.
    val gate = CountDownLatch(1)
    val host = WarmHost(gate = gate)
    val warmer = ServeThumbWarmer(threads = 1, queueDepth = 1)
    val caller = Thread.currentThread().name
    repeat(50) { warmer.enqueue(host, "p$it") }

    assertTrue(
      host.warmed.size < 50,
      "with one worker parked and a queue of one, most offers are dropped: ${host.warmed.size}",
    )
    assertFalse(
      caller in host.threads,
      "no fetch ran on the calling thread — that would be a round trip on the page build",
    )
    assertTrue(
      host.threads.all { it.startsWith("serve-thumb-warm") },
      "every fetch ran on a warmer thread: ${host.threads}",
    )
    gate.countDown()
    warmer.stop()
  }
}
