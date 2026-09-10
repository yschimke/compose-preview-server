package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServeSpareSandboxesTest {

  @Test
  fun `no budget or no worker slot means no pool`() {
    assertNull(ServeSpareSandboxes.config(maxSpares = 0, sandboxCount = 3))
    assertNull(ServeSpareSandboxes.config(maxSpares = -1, sandboxCount = 3))
    // A single-sandbox daemon has no worker slot to adopt into.
    assertNull(ServeSpareSandboxes.config(maxSpares = 4, sandboxCount = 1))
    assertNull(ServeSpareSandboxes.forBudget(maxSpares = 0, sandboxCount = 3, log = {}))
  }

  @Test
  fun `one signature keeps a daemon's worth of workers warm, within the budget`() {
    // The image's shape: three sandboxes per daemon, so two workers to adopt.
    val config = ServeSpareSandboxes.config(maxSpares = 4, sandboxCount = 3)!!
    assertEquals(4, config.maxSpares)
    assertEquals(2, config.perSignature)
    // A budget smaller than a daemon's worker count is honoured as the cap.
    assertEquals(1, ServeSpareSandboxes.config(maxSpares = 1, sandboxCount = 3)!!.perSignature)
  }

  @Test
  fun `a pool for a budget closes cleanly without ever launching`() {
    val lines = mutableListOf<String>()
    val spares = ServeSpareSandboxes.forBudget(maxSpares = 2, sandboxCount = 3, log = lines::add)!!
    val snapshot = spares.snapshot()
    assertEquals(0, snapshot.warm)
    assertEquals(0, snapshot.booting)
    assertEquals(
      listOf("keeping up to 2 warm Android sandbox worker(s), 2 per daemon classpath"),
      lines,
    )
    spares.close()
  }
}
