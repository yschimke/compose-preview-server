package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable

/**
 * A census of the processes this server's own container is running, read straight out of `/proc`.
 *
 * Exists because the server spawns render daemons, playground compile jails and UI-builder
 * renderers as **subprocesses**, and until now `/status` could not see them at all. The counters it
 * did publish are all session-level — `daemons.running` counts sessions, not processes, and the
 * README has to warn operators not to multiply it by a memory estimate — so a box whose real
 * problem was hundreds of unreaped children looked, from `/status`, exactly like a healthy one.
 * Diagnosing that needed `docker exec … ps`, which is precisely the shell access a status endpoint
 * exists to remove the need for.
 *
 * The measured case: `preview.coo.ee` sat at 99.98% of its 40 GiB container limit with **2099
 * `[java] <defunct>` children**, all parented to PID 1, accumulating at ~157/hour across 13 hours
 * of uptime, against an unbounded container PID budget. `/status` reported `daemons.running: 18`
 * and `status: ok` throughout.
 *
 * [zombies] is therefore the field to alert on: a healthy box reaps children as they exit and sits
 * at zero, so any sustained non-zero reading is a leak rather than a busy moment. [liveJava] is the
 * memory-relevant count — zombies hold a PID and no address space, so the two must never be added
 * together or read as one number.
 *
 * Linux-only by construction (`/proc`). [read] answers null anywhere it cannot see a `/proc`, which
 * keeps a developer's macOS `serve` free of a permanently empty status section.
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
   * The cgroup's PID ceiling, when one is set. Null when the cgroup reports `max` (unbounded) —
   * which is itself worth seeing, because an unbounded budget is what lets a reaping leak run until
   * the host runs out of PIDs rather than until this container does.
   */
  val pidsMax: Long? = null,
) {
  companion object {
    /** Keep [zombieCommands] a hint rather than a process table. */
    private const val MAX_ZOMBIE_COMMANDS = 5

    /**
     * Census `/proc` now, or null where there is none (macOS, Windows) — see the class doc.
     *
     * Per-pid failures are swallowed individually: a process that exits between the directory
     * listing and its own `stat` read is the normal case, not an error, and must not lose the whole
     * census.
     */
    fun read(procDir: File = File("/proc")): ServeProcessCensusSnapshot? {
      val pids = procDir.listFiles { f: File -> f.isDirectory && f.name.toIntOrNull() != null }
      if (pids == null) return null
      var total = 0
      var zombies = 0
      var liveJava = 0
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
        pidsCurrent = readCgroupCount("/sys/fs/cgroup/pids.current"),
        pidsMax = readCgroupCount("/sys/fs/cgroup/pids.max"),
      )
    }

    /** A cgroup counter file, or null when absent or reporting the literal `max`. */
    private fun readCgroupCount(path: String): Long? {
      return try {
        File(path).readText().trim().toLongOrNull()
      } catch (e: Exception) {
        null
      }
    }
  }
}
