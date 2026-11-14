package ee.schimke.composeai.cli.serve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogPostPublishSweeperTest {

  @Test
  fun `per-catalog publications during initial load skip sweeps until startup finishes`() {
    val sweeps = mutableListOf<Boolean>()
    val events = mutableListOf<String>()
    val exec = Executors.newSingleThreadExecutor()
    try {
      val latch = CountDownLatch(1)
      val sweeper =
        CatalogPostPublishSweeper(
          sweep = { force ->
            sweeps += force
            events += "sweep:$force"
            latch.countDown()
          },
          executorProvider = { exec },
        )

      sweeper.beginInitialLoad()
      assertTrue(sweeper.isInitialLoadInProgress)

      // Simulates 56 catalog loads publishing during startup: none may stall on disk sweeps.
      repeat(56) { sweeper.onCatalogPublished() }
      assertEquals(emptyList(), sweeps)

      sweeper.finishInitialLoad { events += "onComplete" }
      assertFalse(sweeper.isInitialLoadInProgress)
      assertTrue(latch.await(5, TimeUnit.SECONDS))
      assertEquals(listOf(true), sweeps)
      assertEquals(listOf("onComplete", "sweep:true"), events)
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun `finishInitialLoad invokes afterUnblock without waiting for a slow background sweep`() {
    val sweepStarted = CountDownLatch(1)
    val releaseSweep = CountDownLatch(1)
    val unblocked = AtomicBoolean(false)
    val exec = Executors.newSingleThreadExecutor()
    try {
      val sweeper =
        CatalogPostPublishSweeper(
          sweep = {
            sweepStarted.countDown()
            assertTrue(releaseSweep.await(5, TimeUnit.SECONDS))
          },
          executorProvider = { exec },
        )

      sweeper.beginInitialLoad()
      sweeper.finishInitialLoad { unblocked.set(true) }

      assertTrue(unblocked.get(), "refresher seeding must not block behind the background sweep")
      assertTrue(sweepStarted.await(5, TimeUnit.SECONDS))
      releaseSweep.countDown()
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun `bursts of post-startup publications coalesce onto the background executor`() {
    val sweepStarted = CountDownLatch(1)
    val releaseFirstSweep = CountDownLatch(1)
    val allSweepsDone = CountDownLatch(2)
    val runCount = AtomicInteger()
    val exec = Executors.newSingleThreadExecutor()
    try {
      val sweeper =
        CatalogPostPublishSweeper(
          sweep = {
            if (runCount.incrementAndGet() == 1) {
              sweepStarted.countDown()
              assertTrue(releaseFirstSweep.await(5, TimeUnit.SECONDS))
            }
            allSweepsDone.countDown()
          },
          executorProvider = { exec },
        )

      sweeper.onCatalogPublished()
      assertTrue(sweepStarted.await(5, TimeUnit.SECONDS))

      // Ten refreshes completing while one sweep is mid-flight coalesce into a single trailing
      // pass instead of queueing ten back-to-back full directory walks.
      repeat(10) { sweeper.onCatalogPublished() }
      releaseFirstSweep.countDown()

      assertTrue(allSweepsDone.await(5, TimeUnit.SECONDS))
      exec.submit {}.get(5, TimeUnit.SECONDS)
      assertEquals(2, runCount.get())
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun `cancelInitialLoad suppresses the startup sweep when the loader shuts down early`() {
    val sweeps = AtomicInteger()
    val sweeper = CatalogPostPublishSweeper(sweep = { sweeps.incrementAndGet() })

    sweeper.beginInitialLoad()
    sweeper.onCatalogPublished()
    sweeper.cancelInitialLoad()

    assertFalse(sweeper.isInitialLoadInProgress)
    assertEquals(0, sweeps.get())
  }
}
