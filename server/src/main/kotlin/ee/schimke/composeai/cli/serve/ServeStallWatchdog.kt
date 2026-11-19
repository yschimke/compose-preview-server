package ee.schimke.composeai.cli.serve

import java.lang.management.ManagementFactory

/** A request at least this slow is logged by the server's monitoring interceptor. */
internal const val SLOW_REQUEST_MILLIS = 2_000L

/**
 * Says when the whole server process stopped running, as distinct from one slow request: a daemon
 * thread that asks to wake every [tickMillis] and logs when it wakes [thresholdMillis] late.
 *
 * A late wake means no thread of this process was being scheduled, for one of two reasons the log
 * line tells apart. If the collectors' own time grew by about the stall, it was a GC pause. If it
 * did not, the process was descheduled: on a memory-constrained cgroup that is usually its heap
 * being paged back in, which is what a fast request turning into a 30–90 s one looks like from
 * outside.
 */
internal class ServeStallWatchdog(
  private val tickMillis: Long = 500L,
  private val thresholdMillis: Long = 2_000L,
  private val log: (String) -> Unit = System.err::println,
) {
  @Volatile private var thread: Thread? = null

  fun start() {
    if (thread != null) return
    thread =
      Thread(::run, "serve-stall-watchdog").apply {
        isDaemon = true
        start()
      }
  }

  fun stop() {
    thread?.interrupt()
    thread = null
  }

  private fun run() {
    var gcBefore = gcMillis()
    while (!Thread.currentThread().isInterrupted) {
      val before = System.nanoTime()
      try {
        Thread.sleep(tickMillis)
      } catch (_: InterruptedException) {
        return
      }
      val lateMillis = (System.nanoTime() - before) / 1_000_000 - tickMillis
      val gcNow = gcMillis()
      if (lateMillis >= thresholdMillis) {
        log(describe(lateMillis, gcNow - gcBefore))
      }
      gcBefore = gcNow
    }
  }

  internal fun describe(lateMillis: Long, gcDeltaMillis: Long): String {
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    val verdict =
      if (gcDeltaMillis * 2 >= lateMillis) "mostly GC"
      else "not GC: the process was descheduled (paging or CPU starvation)"
    return "serve: process stalled $lateMillis ms ($verdict; GC ${gcDeltaMillis} ms in the " +
      "window; heap $usedMb of $maxMb MB)"
  }

  private fun gcMillis(): Long =
    ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
}
