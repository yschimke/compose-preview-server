package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable

/**
 * A census of the processes in this server's container, read from `/proc`. The server spawns render
 * daemons, compile jails and renderers as subprocesses, and `/status`'s session-level counters
 * can't show a box drowning in unreaped children (one deployment reached 2099 `[java] <defunct>`
 * children near its memory limit while reporting `status: ok`).
 *
 * [zombies] is the field to alert on: a healthy box sits at zero. [liveJava] is the memory-relevant
 * count; zombies hold a PID but no memory, so never add the two.
 *
 * Linux-only; [read] returns null without `/proc` (e.g. macOS).
 */
@Serializable
data class ServeProcessCensusSnapshot(
  /** Every process in the container's PID namespace, zombies included. */
  val total: Int,
  /**
   * Processes in state `Z` — exited, but never `waitpid()`-ed by their parent. **Alert on this.**
   * Zero on a healthy box; a sustained non-zero value is a reaping leak in whichever lane
   * [zombieCommands] names.
   */
  val zombies: Int,
  /** Live (non-zombie) JVMs: the server itself plus its render/compile/renderer children. */
  val liveJava: Int,
  /**
   * Which executables the zombies are, highest count first — the lane to look at. Capped at a few
   * entries because this is a diagnostic hint, not a process table.
   */
  val zombieCommands: Map<String, Int> = emptyMap(),
  /** The cgroup's current PID count, when readable. Null outside a PID-limited cgroup. */
  val pidsCurrent: Long? = null,
  /**
   * The cgroup's PID ceiling, or null when it reports `max` (unbounded), which lets a reaping leak
   * exhaust the host's PIDs rather than the container's.
   */
  val pidsMax: Long? = null,
  /**
   * Resident memory of every live JVM in [liveJava] together: the server and its daemons, which
   * share the container's memory limit. Null when no `statm` was readable.
   */
  val liveJavaRssBytes: Long? = null,
  /** The single largest live JVM's resident memory, to tell one runaway from many daemons. */
  val largestJavaRssBytes: Long? = null,
  /** The cgroup's memory in use, page cache included, as the kernel counts it against the limit. */
  val memoryCurrentBytes: Long? = null,
  /** The cgroup's memory limit, or null when unlimited. */
  val memoryMaxBytes: Long? = null,
  /** The cgroup's swap in use, or null where the kernel does not account it. */
  val swapCurrentBytes: Long? = null,
  /**
   * Processes the kernel's OOM killer has killed in this cgroup since it was created (its
   * `memory.events` `oom_kill`). **Alert on it rising**: a killed daemon is otherwise a render
   * failure, and a killed server a restart, with nothing else saying why.
   */
  val oomKills: Long? = null,
) {
  companion object {
    /** Keep [zombieCommands] a hint rather than a process table. */
    private const val MAX_ZOMBIE_COMMANDS = 5

    /** Linux x86-64 and arm64 server kernels; `statm` counts in pages. */
    private const val PAGE_BYTES = 4096L

    /** At or above this, a cgroup v1 limit means "none" (it reports ~2^63 rounded to a page). */
    private const val UNLIMITED_V1_BYTES = 1L shl 62

    /**
     * Census `/proc` now, or null where there is none. Per-pid failures are swallowed: a process
     * exiting mid-census is normal.
     */
    fun read(
      procDir: File = File("/proc"),
      cgroupDir: File = File(ServeProcessCensus.DEFAULT_CGROUP_DIR),
    ): ServeProcessCensusSnapshot? {
      val pids = procDir.listFiles { f: File -> f.isDirectory && f.name.toIntOrNull() != null }
      if (pids == null) return null
      var total = 0
      var zombies = 0
      var liveJava = 0
      var javaRss = 0L
      var largestJavaRss = 0L
      var rssRead = false
      val zombieCommands = mutableMapOf<String, Int>()
      for (pid in pids) {
        // `comm` can contain spaces and parentheses, so the state is the field after the LAST ')'
        // rather than a naive split on whitespace — `(Web Content)` and `((sd-pam))` are both real.
        val stat = runCatching { File(pid, "stat").readText() }.getOrNull() ?: continue
        val close = stat.lastIndexOf(')')
        val open = stat.indexOf('(')
        if (close < 0 || open < 0 || close < open) continue
        val command = stat.substring(open + 1, close)
        val state = stat.substring(close + 1).trimStart().firstOrNull() ?: continue
        total++
        if (state == 'Z') {
          zombies++
          zombieCommands[command] = (zombieCommands[command] ?: 0) + 1
        } else if (command == "java") {
          liveJava++
          residentBytes(pid)?.let { rss ->
            rssRead = true
            javaRss += rss
            largestJavaRss = maxOf(largestJavaRss, rss)
          }
        }
      }
      return ServeProcessCensusSnapshot(
        total = total,
        zombies = zombies,
        liveJava = liveJava,
        zombieCommands =
          zombieCommands.entries
            .sortedByDescending { it.value }
            .take(MAX_ZOMBIE_COMMANDS)
            .associate { it.key to it.value },
        pidsCurrent = readCgroupCount(cgroupDir, "pids.current"),
        pidsMax = readCgroupCount(cgroupDir, "pids.max"),
        liveJavaRssBytes = javaRss.takeIf { rssRead },
        largestJavaRssBytes = largestJavaRss.takeIf { rssRead },
        memoryCurrentBytes =
          readLongOrNull(File(cgroupDir, "memory.current"))
            ?: readLongOrNull(File(cgroupDir, "memory/memory.usage_in_bytes")),
        memoryMaxBytes =
          (readLongOrNull(File(cgroupDir, "memory.max"))
              ?: readLongOrNull(File(cgroupDir, "memory/memory.limit_in_bytes")))
            // v1 spells "unlimited" as a huge page-rounded number rather than `max`.
            ?.takeIf { it < UNLIMITED_V1_BYTES },
        swapCurrentBytes = readLongOrNull(File(cgroupDir, "memory.swap.current")),
        oomKills = readEventCount(File(cgroupDir, "memory.events"), "oom_kill"),
      )
    }

    /** Resident set from `statm` (pages, second field), the cheapest of the per-pid files. */
    private fun residentBytes(pid: File): Long? = runCatching {
      File(pid, "statm").readText().trim().split(' ').getOrNull(1)?.toLongOrNull()
    }
      .getOrNull()
      ?.let { it * PAGE_BYTES }

    /** One counter from a cgroup `key value` events file, or null when absent. */
    private fun readEventCount(file: File, key: String): Long? = runCatching {
      file.readLines().firstNotNullOfOrNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size == 2 && parts[0] == key) parts[1].toLongOrNull() else null
      }
    }
      .getOrNull()

    /**
     * A cgroup PID counter, or null when absent or `max`. Tries v2 first (what the kernel enforces
     * when both exist), then v1's `pids/`, matching how the deployment entrypoint reads both
     * layouts for CPU and memory.
     */
    private fun readCgroupCount(cgroupDir: File, name: String): Long? =
      readLongOrNull(File(cgroupDir, name)) ?: readLongOrNull(File(File(cgroupDir, "pids"), name))

    /** A counter file's value, or null when it is absent, unreadable, or the literal `max`. */
    private fun readLongOrNull(file: File): Long? =
      try {
        file.readText().trim().toLongOrNull()
      } catch (e: Exception) {
        null
      }
  }
}

/**
 * A [ServeProcessCensusSnapshot] resampled at most once per [intervalMillis], shared by all
 * callers. `/status` is unauthenticated and uncached, and a census reads one file per PID, so on a
 * box with thousands of processes frequent polling would add load; a few seconds of staleness costs
 * nothing. The walk runs under the lock so concurrent requests share one traversal.
 */
class ServeProcessCensus(
  private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
  private val procDir: File = File("/proc"),
  private val cgroupDir: File = File(DEFAULT_CGROUP_DIR),
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val lock = Any()
  private var sampledAtMillis: Long? = null
  private var last: ServeProcessCensusSnapshot? = null

  /** The current census, reusing the previous one while it is younger than [intervalMillis]. */
  fun sample(): ServeProcessCensusSnapshot? =
    synchronized(lock) {
      val now = clock()
      val taken = sampledAtMillis
      // A clock that went backwards resamples rather than serving a reading from the future.
      if (taken != null && now >= taken && now - taken < intervalMillis) return last
      last = ServeProcessCensusSnapshot.read(procDir, cgroupDir)
      sampledAtMillis = now
      last
    }

  companion object {
    /**
     * Long enough that a monitor polling every second costs one traversal in five, short enough
     * that an operator refreshing `/status` while watching a leak grow sees it move.
     */
    const val DEFAULT_INTERVAL_MILLIS: Long = 5_000

    /** The unified-hierarchy mount; the v1 PID controller sits at `pids/` beneath it. */
    const val DEFAULT_CGROUP_DIR: String = "/sys/fs/cgroup"
  }
}
