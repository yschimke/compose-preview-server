package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces post-publication theme-cache and blob-pool sweeps off the catalog registration lock and
 * the startup catalog loader.
 *
 * Two invariants keep startup and rolling updates responsive on large persistent caches:
 * 1. While the initial catalog pass is running ([beginInitialLoad]..[finishInitialLoad]),
 *    per-catalog [onCatalogPublished] calls do not sweep. `sweepThemeCache` requires the full live
 *    set before reclaiming stale generations, and walking tens of thousands of cached files inside
 *    the first catalog's load stalls every subsequent catalog (and `/readyz`) behind disk I/O.
 * 2. Once startup finishes (and on later runtime refreshes), sweeps execute asynchronously on the
 *    supplied background [executorProvider] (when active) and coalesce bursts of publications into
 *    a single trailing pass, preserving `forceBlobSweep = true` across coalesced requests.
 */
internal class CatalogPostPublishSweeper(
  private val sweep: (forceBlobSweep: Boolean) -> Unit,
  private val executorProvider: () -> Executor? = { null },
) {
  private val initialLoadInProgress = AtomicBoolean(false)
  private val pending = AtomicBoolean(false)
  private val forceBlobSweepPending = AtomicBoolean(false)
  private val workerScheduled = AtomicBoolean(false)

  val isInitialLoadInProgress: Boolean
    get() = initialLoadInProgress.get()

  fun beginInitialLoad() {
    initialLoadInProgress.set(true)
  }

  /** Clear the initial-load guard without scheduling a sweep when the loader closes early. */
  fun cancelInitialLoad() {
    initialLoadInProgress.set(false)
  }

  /**
   * Complete the startup pass, run [afterUnblock] immediately without waiting on disk census I/O,
   * and schedule the single post-startup forced sweep.
   */
  fun finishInitialLoad(afterUnblock: () -> Unit = {}) {
    initialLoadInProgress.set(false)
    afterUnblock()
    scheduleSweep(forceBlobSweep = true)
  }

  fun onCatalogPublished() {
    if (initialLoadInProgress.get()) return
    scheduleSweep(forceBlobSweep = false)
  }

  private fun scheduleSweep(forceBlobSweep: Boolean) {
    if (forceBlobSweep) forceBlobSweepPending.set(true)
    pending.set(true)
    val executor = executorProvider()
    if (executor == null) {
      drainSynchronously()
      return
    }
    tryScheduleWorker(executor)
  }

  private fun tryScheduleWorker(executor: Executor) {
    if (!workerScheduled.compareAndSet(false, true)) return
    try {
      executor.execute {
        try {
          drainPending()
        } finally {
          workerScheduled.set(false)
          if (pending.get()) {
            executorProvider()?.let(::tryScheduleWorker)
          }
        }
      }
    } catch (_: RejectedExecutionException) {
      workerScheduled.set(false)
    }
  }

  private fun drainSynchronously() {
    if (!workerScheduled.compareAndSet(false, true)) return
    try {
      drainPending()
    } finally {
      workerScheduled.set(false)
    }
  }

  private fun drainPending() {
    while (pending.getAndSet(false)) {
      val force = forceBlobSweepPending.getAndSet(false)
      runCatching { sweep(force) }
    }
  }
}
