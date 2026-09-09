package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ServeProcessCensusTest {

  /** Write one `/proc/<pid>/stat` in the kernel's format: `pid (comm) state <the rest>`. */
  private fun proc(root: File, pid: Int, command: String, state: Char) {
    val dir = File(root, pid.toString())
    dir.mkdirs()
    File(dir, "stat").writeText("$pid ($command) $state 1 1 0 0 -1 4194560 0 0 0 0 0 0 0 0")
  }

  @Test
  fun `counts live jvms and zombies separately`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    proc(tmp, 2, "java", 'R')
    proc(tmp, 3, "java", 'Z')
    proc(tmp, 4, "java", 'Z')
    proc(tmp, 5, "sh", 'S')
    // Not a pid — `/proc` is full of these, and treating one as a process would inflate the census.
    File(tmp, "meminfo").writeText("MemTotal: 1 kB")

    val census = ServeProcessCensusSnapshot.read(tmp)!!

    assertEquals(5, census.total)
    assertEquals(2, census.zombies)
    // The two zombies are NOT counted as live JVMs: they hold a pid and no address space, and the
    // whole point of the split is that adding them would misstate memory in either direction.
    assertEquals(2, census.liveJava)
    assertEquals(mapOf("java" to 2), census.zombieCommands)
  }

  @Test
  fun `reads a command containing spaces and parentheses`(@TempDir tmp: File) {
    // Both are real: Firefox's `(Web Content)` and systemd's `((sd-pam))`. Parsing the state as the
    // field after the FIRST ')' would read ' ' here and lose the process entirely.
    proc(tmp, 1, "Web Content", 'Z')
    proc(tmp, 2, "(sd-pam)", 'Z')

    val census = ServeProcessCensusSnapshot.read(tmp)!!

    assertEquals(2, census.total)
    assertEquals(2, census.zombies)
    assertEquals(mapOf("Web Content" to 1, "(sd-pam)" to 1), census.zombieCommands)
  }

  @Test
  fun `ranks zombie commands and caps the list`(@TempDir tmp: File) {
    var pid = 1
    // Six distinct commands, so the cap of five actually bites, with `java` the clear top offender.
    repeat(6) { proc(tmp, pid++, "java", 'Z') }
    repeat(5) { proc(tmp, pid++, "gradle", 'Z') }
    listOf("git", "sh", "node", "python").forEach { proc(tmp, pid++, it, 'Z') }

    val census = ServeProcessCensusSnapshot.read(tmp)!!

    assertEquals(5, census.zombieCommands.size)
    assertEquals(listOf("java", "gradle"), census.zombieCommands.keys.take(2))
    assertEquals(6, census.zombieCommands["java"])
  }

  @Test
  fun `answers null where there is no proc`(@TempDir tmp: File) {
    // A developer's macOS `serve` must get no section at all rather than a permanently empty one.
    assertNull(ServeProcessCensusSnapshot.read(File(tmp, "absent")))
  }

  @Test
  fun `survives a process that exits mid-census`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    // A pid directory with no readable `stat` is the ordinary race, not an error: the process
    // exited between the listing and the read. It must not lose the rest of the census.
    File(tmp, "2").mkdirs()

    val census = ServeProcessCensusSnapshot.read(tmp)!!

    assertEquals(1, census.total)
    assertEquals(1, census.liveJava)
    assertEquals(0, census.zombies)
  }

  /** A cgroup PID controller in the unified (v2) layout: counters at the hierarchy root. */
  private fun cgroupV2(root: File, current: String, max: String): File {
    val dir = File(root, "cgroup2").also { it.mkdirs() }
    File(dir, "pids.current").writeText("$current\n")
    File(dir, "pids.max").writeText("$max\n")
    return dir
  }

  /** A cgroup PID controller in the v1 layout: its own `pids/` mount beneath the hierarchy. */
  private fun cgroupV1(root: File, current: String, max: String): File {
    val dir = File(root, "cgroup1")
    val pids = File(dir, "pids").also { it.mkdirs() }
    File(pids, "pids.current").writeText("$current\n")
    File(pids, "pids.max").writeText("$max\n")
    return dir
  }

  @Test
  fun `reads the pid budget from a unified cgroup`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')

    val census = ServeProcessCensusSnapshot.read(tmp, cgroupV2(tmp, "2140", "4096"))!!

    assertEquals(2140L, census.pidsCurrent)
    assertEquals(4096L, census.pidsMax)
  }

  @Test
  fun `reads the pid budget from a cgroup v1 controller`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    // The regression: reading only the v2 path answered null here, so the PID meter vanished on a
    // v1 host with a perfectly finite budget. The deployment entrypoint already falls back this way
    // for the CPU and memory limits, so a host it supports must not be blind to its PID ceiling.
    val census = ServeProcessCensusSnapshot.read(tmp, cgroupV1(tmp, "2140", "4096"))!!

    assertEquals(2140L, census.pidsCurrent)
    assertEquals(4096L, census.pidsMax)
  }

  @Test
  fun `reports an unbounded budget as no ceiling`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    // `max` is the literal a cgroup writes for "no limit", and it is the state the measured
    // incident ran under. It must read as null so the page draws no meter, rather than as a number.
    val census = ServeProcessCensusSnapshot.read(tmp, cgroupV2(tmp, "2140", "max"))!!

    assertEquals(2140L, census.pidsCurrent)
    assertNull(census.pidsMax)
  }

  @Test
  fun `answers no budget where the host has no pid controller`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')

    val census = ServeProcessCensusSnapshot.read(tmp, File(tmp, "no-cgroup"))!!

    assertNull(census.pidsCurrent)
    assertNull(census.pidsMax)
  }

  @Test
  fun `samples once per interval rather than once per request`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    var now = 1_000L
    val census =
      ServeProcessCensus(
        intervalMillis = 5_000,
        procDir = tmp,
        cgroupDir = File(tmp, "no-cgroup"),
        clock = { now },
      )

    assertEquals(1, census.sample()!!.total)
    // A second process appears, and a caller inside the window still gets the previous reading —
    // which is the point: `/status` is unauthenticated and uncached, so a monitor polling every
    // second must not turn a 2,000-process box into 2,000 file reads a second.
    proc(tmp, 2, "java", 'S')
    assertEquals(1, census.sample()!!.total)

    now += 5_000
    assertEquals(2, census.sample()!!.total)
  }

  @Test
  fun `resamples when the clock goes backwards`(@TempDir tmp: File) {
    proc(tmp, 1, "java", 'S')
    var now = 10_000L
    val census =
      ServeProcessCensus(
        intervalMillis = 5_000,
        procDir = tmp,
        cgroupDir = File(tmp, "no-cgroup"),
        clock = { now },
      )

    assertEquals(1, census.sample()!!.total)
    proc(tmp, 2, "java", 'S')
    // A reading taken in the future is not a reading this cache may serve: without the guard, a
    // clock step backwards would pin the census until wall time caught up again.
    now = 1_000
    assertEquals(2, census.sample()!!.total)
  }
}
