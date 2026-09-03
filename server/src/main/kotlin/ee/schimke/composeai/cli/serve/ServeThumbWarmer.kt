package ee.schimke.composeai.cli.serve

import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fetches a catalog's baked PNGs in the background so its **next** page build can thumbnail them.
 *
 * ## Why this exists
 *
 * [ServeHeroImages.gridThumbFor] reads [ServeHost.bakedRender], which is deliberately a local-only
 * read: a declared preview whose PNG has not arrived yet returns null rather than fetching, because
 * fetching on the page-build thread would turn one catalog page into dozens of serial round trips.
 * A card with no local pixels therefore emits the plain full-resolution `/render/<id>.png`.
 *
 * That URL is what *does* fetch. So the thumbnail lane used to warm only through the
 * full-resolution traffic it exists to eliminate: a cold catalog charged its first visitors the
 * whole bill, and a quiet catalog never converged at all. Measured on the deployed server,
 * `/jetchat/` served 67 cards as **3,332 KB** of full-resolution PNGs while the same cards on the
 * thumbnail lane are **778 KB** — a 4.3× difference that only visitor *N+1* could ever see, and on
 * `/jetsnack/` and `/jetchat/` never arrived, while the busy `/m3-catalog/` sat fully converged at
 * 58 of 58.
 *
 * This closes the loop the other way round: a page build that *misses* says so, and the miss is
 * filled off-thread. One page view then converges the catalog instead of one full-resolution image
 * load per card.
 *
 * ## What it deliberately does not do
 *
 * - **Never renders.** [ServeHost.warmBakedRender] fetches published bytes and nothing else, so a
 *   suspended daemon stays suspended. Warming a catalog must never be the thing that wakes it.
 * - **Never blocks a request.** [enqueue] only offers to a bounded queue and returns; the page
 *   build that missed still emits the plain URL for this render, exactly as before.
 * - **Never retries in a loop.** A full queue drops the request and a failed fetch is not
 *   remembered as failed — the next page build re-offers it. That is the same self-healing rule
 *   `ServeBundleHost.bakedPngFile` already applies to a transient branch blip, and it is why this
 *   needs no backoff of its own.
 *
 * ## Bounds
 *
 * [THREADS] workers against a [QUEUE] deep queue, both small on purpose: this is opportunistic work
 * on a box that may serve dozens of catalogs, and it competes for the same delivery branch as the
 * requests a reader is actually waiting on. Work already in flight or already done is skipped by
 * [inFlight], so a page reloaded ten times enqueues each preview once.
 */
internal class ServeThumbWarmer(
  private val onLog: (String) -> Unit = {},
  threads: Int = THREADS,
  queueDepth: Int = QUEUE,
) {
  /**
   * `host identity + preview id` for every fetch queued or running.
   *
   * Keyed on the host **instance** rather than the session id for the same reason [ServeHeroImages]
   * keys its bakes that way: a catalog refresh installs a fresh host, and the new one's pixels are
   * a different question from the old one's. Entries are removed when the fetch finishes, so a
   * preview whose fetch failed is retried on the next page build rather than being remembered as
   * hopeless.
   */
  private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

  private val pool =
    ThreadPoolExecutor(
        threads,
        threads,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueDepth),
        { r -> Thread(r, "serve-thumb-warm").apply { isDaemon = true } },
        // Drop silently when the queue is full. The alternative — running the fetch on the caller
        // — would put a network round trip on the page-build thread, which is the one thing this
        // whole lane exists to avoid.
        ThreadPoolExecutor.DiscardPolicy(),
      )
      .apply { allowCoreThreadTimeOut(true) }

  /**
   * Ask for [previewId]'s baked pixels to be made local, if they are not already.
   *
   * Returns immediately. Safe to call from a request thread, and safe to call on every page build:
   * a preview already queued, already running, or already local costs a set lookup.
   */
  fun enqueue(host: ServeHost, previewId: String) {
    val key = "${System.identityHashCode(host)}:$previewId"
    if (!inFlight.add(key)) return
    try {
      pool.execute {
        try {
          host.warmBakedRender(previewId)
        } catch (e: Exception) {
          onLog("thumbnail warm failed for $previewId: ${e.message}")
        } finally {
          inFlight.remove(key)
        }
      }
    } catch (e: RuntimeException) {
      // DiscardPolicy does not throw, but a shutdown pool rejects. Either way this preview was not
      // queued, so it must not be left marked in flight or it would never be offered again.
      inFlight.remove(key)
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
