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
}
