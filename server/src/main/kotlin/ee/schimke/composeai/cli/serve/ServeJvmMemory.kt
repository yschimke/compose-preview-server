package ee.schimke.composeai.cli.serve

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import kotlinx.serialization.Serializable

/**
 * The server JVM's own heap and collector, from its management beans — `/status.json` → `jvm`.
 *
 * [heapLiveAfterGcFraction] is the field to alert on. Heap *used* climbs and falls with every
 * collection, so it says little; what survived the last collection of the old generation is the
 * live set, and one near 1.0 is a server about to throw `OutOfMemoryError`. ui.coo.ee sat at about
 * 0.85 for hours before one killed its HTTP listener (2026-11-25), and nothing reported it.
 */
@Serializable
data class ServeJvmMemorySnapshot(
  val heapUsedBytes: Long,
  val heapCommittedBytes: Long,
  /** The heap ceiling (`-Xmx`, or the share `MaxRAMPercentage` gave it), or null when undefined. */
  val heapMaxBytes: Long?,
  /**
   * What the heap's collected pools held right after their last collection, summed: for G1 the old
   * generation after its last mixed or full collection. Null before any such collection.
   */
  val heapLiveAfterGcBytes: Long? = null,
  /** [heapLiveAfterGcBytes] over [heapMaxBytes]. **Alert above about 0.85.** */
  val heapLiveAfterGcFraction: Double? = null,
  /** Metaspace, code cache and other non-heap memory in use. */
  val nonHeapUsedBytes: Long,
  /** Collections since start, every collector together. */
  val gcCount: Long,
  /** Time spent collecting since start, every collector together. */
  val gcTimeMillis: Long,
  val threads: Int,
) {
  companion object {
    /** Read now. Each bean call is a cheap in-process read; nothing here walks the heap. */
    fun read(): ServeJvmMemorySnapshot {
      val memory = ManagementFactory.getMemoryMXBean()
      val heap = memory.heapMemoryUsage
      val heapMax = heap.max.takeIf { it > 0 }
      val afterGc =
        ManagementFactory.getMemoryPoolMXBeans()
          .filter { it.type == MemoryType.HEAP && it.isCollectionUsageThresholdSupported }
          .mapNotNull { pool -> pool.collectionUsage?.used }
          .takeIf { it.isNotEmpty() }
          ?.sum()
          ?.takeIf { it > 0 }
      var gcCount = 0L
      var gcTime = 0L
      for (collector in ManagementFactory.getGarbageCollectorMXBeans()) {
        collector.collectionCount.takeIf { it > 0 }?.let { gcCount += it }
        collector.collectionTime.takeIf { it > 0 }?.let { gcTime += it }
      }
      return ServeJvmMemorySnapshot(
        heapUsedBytes = heap.used,
        heapCommittedBytes = heap.committed,
        heapMaxBytes = heapMax,
        heapLiveAfterGcBytes = afterGc,
        heapLiveAfterGcFraction = liveFraction(afterGc, heapMax),
        nonHeapUsedBytes = memory.nonHeapMemoryUsage.used,
        gcCount = gcCount,
        gcTimeMillis = gcTime,
        threads = ManagementFactory.getThreadMXBean().threadCount,
      )
    }

    /** Separate from [read] so the rounding is testable without a particular heap. */
    internal fun liveFraction(liveBytes: Long?, maxBytes: Long?): Double? {
      if (liveBytes == null || maxBytes == null || maxBytes <= 0) return null
      return Math.round(liveBytes.toDouble() / maxBytes * 1000) / 1000.0
    }
  }
}
