package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeUiBuilderStorageHeadroomTest {
  @Test
  fun `the percentage is one decimal of the ceiling`() {
    assertEquals(75.0, storageUsedPercent(24L * 1024 * 1024, 32L * 1024 * 1024))
    assertEquals(0.0, storageUsedPercent(0, 32L * 1024 * 1024))
    assertEquals(100.0, storageUsedPercent(32L * 1024 * 1024, 32L * 1024 * 1024))
    assertEquals(33.3, storageUsedPercent(1_000, 3_000))
  }

  @Test
  fun `an unbounded storage has no percentage to report`() {
    assertEquals(0.0, storageUsedPercent(1_000, 0))
    assertNull(uiBuilderStorageWarning(1_000, 0), "nothing bounds it, so nothing is nearly full")
  }

  @Test
  fun `headroom below the warning line says nothing`() {
    // The state file that prompted this sat here for weeks. It is loud enough to find in a deploy
    // log and quiet enough not to be printed on every healthy start.
    assertNull(uiBuilderStorageWarning(24L * 1024 * 1024, 32L * 1024 * 1024))
    assertNull(uiBuilderStorageWarning(0, 32L * 1024 * 1024))
  }

  @Test
  fun `at the warning line it names the number, the consequence and the remedy`() {
    val warning = assertNotNull(uiBuilderStorageWarning(26L * 1024 * 1024, 32L * 1024 * 1024))

    assertTrue(warning.startsWith("serve: WARNING"), warning)
    assertTrue(warning.contains("26.0 MB of 32.0 MB"), warning)
    assertTrue(warning.contains("81.2%"), warning)
    assertTrue(warning.contains("saves are refused at the ceiling"), warning)
    assertTrue(warning.contains("retainedRevisionSnapshots"), warning)
    assertTrue(warning.contains("state-size-report.mjs"), warning)
  }

  @Test
  fun `a full store still warns rather than refusing`() {
    // Deliberately not an error and deliberately not fatal: a host that would not start because its
    // state file is large is the failure this whole line exists to avoid.
    val warning = assertNotNull(uiBuilderStorageWarning(32L * 1024 * 1024, 32L * 1024 * 1024))

    assertTrue(warning.contains("100.0%"), warning)
  }
}
