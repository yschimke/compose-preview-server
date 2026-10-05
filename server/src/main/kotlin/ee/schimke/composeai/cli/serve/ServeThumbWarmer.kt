package ee.schimke.composeai.cli.serve

import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fetches a catalog's baked PNGs in the background so its **next** page build can thumbnail them.
 *
 * [ServeHeroImages.gridThumbFor] reads [ServeHost.bakedRender] local-only (fetching on the
 * page-build thread would serialise dozens of round trips), so a miss emits the full-resolution
 * `/render/<id>.png`. Without this, the thumbnail lane only ever warmed through the full-resolution
 * traffic it exists to eliminate; with it, one page view converges the catalog.
 *
 * - **Never renders**: [ServeHost.warmBakedRender] only fetches published bytes, so a suspended
 *   daemon stays suspended.
 * - **Never blocks a request**: [enqueue] offers to a bounded queue and returns.
 * - **Never retries in a loop**: a full queue drops the request and a failed fetch is forgotten, so
 *   the next page build re-offers it. Both paths must release the [inFlight] claim, or a drop
 *   becomes a permanent dedupe.
 *
 * [THREADS] workers and a [QUEUE]-deep queue are small on purpose: this is opportunistic work
 * competing with requests a reader is waiting on.
 */
internal class ServeThumbWarmer(
  private val onLog: (String) -> Unit = {},
  threads: Int = THREADS,
  queueDepth: Int = QUEUE,
) {
  /**
   * The previews queued or running, per host.
   *
   * Keyed on the host **instance** rather than the session id for the same reason [ServeHeroImages]
   * keys its bakes that way: a catalog refresh installs a fresh host, and the new one's pixels are
   * a different question from the old one's. A [WeakHashMap] for the same reason too — a retired
   * catalog's entry goes with its host rather than being retained for the life of the process.
   *
   * Deliberately keyed by the host OBJECT, not by `System.identityHashCode`: that is a 32-bit value
   * with no uniqueness guarantee, so two live hosts can share one and each would dedupe the other's
   * previews out of its own queue. `WeakHashMap` compares keys by identity, which is the property
   * actually wanted here and cannot collide.
   *
   * A claim is released when the fetch finishes OR is rejected, so a dropped or failed preview is
   * retried on the next page build rather than remembered as hopeless.
   */
  private val inFlight = WeakHashMap<ServeHost, MutableSet<String>>()

  private val inFlightLock = Any()

  /** True when [previewId] was not already queued or running for [host]. */
  private fun claim(host: ServeHost, previewId: String): Boolean =
    synchronized(inFlightLock) { inFlight.getOrPut(host) { HashSet() }.add(previewId) }

  private fun release(host: ServeHost, previewId: String) {
    synchronized(inFlightLock) { inFlight[host]?.remove(previewId) }
  }

  /** One queued warm, carrying what it claimed so a rejection can release it. */
  private class WarmTask(
    val host: ServeHost,
    val previewId: String,
    private val body: () -> Unit,
  ) : Runnable {
    override fun run() = body()
  }

  private val pool =
    ThreadPoolExecutor(
        threads,
        threads,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueDepth),
        { r -> Thread(r, "serve-thumb-warm").apply { isDaemon = true } },
        // Drop when the queue is full — but release the key on the way out.
        //
        // NOT `DiscardPolicy`: that returns normally without running the task, so the `finally`
        // that releases the claim never fires and the preview stays marked in flight forever. It
        // would then be deduplicated out of every later page build — the exact opposite of the
        // "a drop is re-offered next time" rule this class depends on, and silent.
        //
        // NOT `CallerRunsPolicy` either: running here would put a delivery-branch round trip on
        // the page-build thread, which is the one thing this whole lane exists to avoid.
        { r, _ -> (r as? WarmTask)?.let { release(it.host, it.previewId) } },
      )
      .apply { allowCoreThreadTimeOut(true) }

  /**
   * Ask for [previewId]'s baked pixels to be made local, if they are not already.
   *
   * Returns immediately. Safe to call from a request thread, and safe to call on every page build:
   * a preview already queued, already running, or already local costs a set lookup.
   */
  fun enqueue(host: ServeHost, previewId: String) {
    if (!claim(host, previewId)) return
    val task =
      WarmTask(host, previewId) {
        try {
          host.warmBakedRender(previewId)
        } catch (e: Exception) {
          onLog("thumbnail warm failed for $previewId: ${e.message}")
        } finally {
          release(host, previewId)
        }
      }
    try {
      pool.execute(task)
    } catch (e: RuntimeException) {
      // A shutdown pool rejects by throwing, which the handler above never sees. Same rule: this
      // preview was not queued, so it must not stay marked in flight.
      release(host, previewId)
      throw e
    }
  }

  /** Stop accepting work and let in-flight fetches finish briefly. */
  fun stop() {
    pool.shutdownNow()
  }

  internal companion object {
    /**
     * Two workers. Enough that a cold catalog converges within a page view or two, few enough that
     * warming never looks like a crawl to the delivery branch — and the fetches are per-id
     * serialised downstream anyway ([ServeBundleHost.bakedPngFile]).
     */
    const val THREADS = 2

    /**
     * Deep enough for one large catalog's misses (the biggest served catalog is ~60 cards), and
     * bounded so a box that has just registered a dozen cold catalogs sheds rather than queues
     * thousands of fetches. What is dropped is re-offered by the next page build.
     */
    const val QUEUE = 128
  }
}
