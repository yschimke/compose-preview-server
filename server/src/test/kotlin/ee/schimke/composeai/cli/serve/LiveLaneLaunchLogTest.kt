package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveLaneLaunchLogTest {
  @Test
  fun `the last line of a failed launch is what the degradation gets`() {
    val log = LiveLaneLaunchLog()
    val sink = log.sink("m3-catalog")
    // A real launch logs informational lines first (the Skiko pairing repair, the classpath split)
    // and its cause LAST, immediately before materialize returns null.
    sink("catalog m3-catalog: bundle carries Skiko bindings 0.150.1 with no native runtime")
    sink("catalog m3-catalog: no daemon jars found (looked in \$APP_HOME/lib-daemon-desktop)")

    val reason = log.lastReason("m3-catalog")
    assertTrue(reason!!.contains("no daemon jars found"), reason)
  }

  @Test
  fun `a lane that comes up leaves nothing behind`() {
    // The regression this guards: without the clear, a successful launch's last INFO line stays in
    // the map, and the next catalog-level failure — a fetch that fails on a later branch refresh,
    // say — would report the Skiko repair notice as its cause. A wrong reason is worse than none.
    val log = LiveLaneLaunchLog()
    log.sink("compose-m3")("catalog compose-m3: 34 shared bundle dependency classpath entry(s)")
    log.clear("compose-m3")

    assertNull(log.lastReason("compose-m3"))
  }

  @Test
  fun `a reason nothing logged can still be recorded`() {
    // openHost returning null is a real failure that no materialize message covers.
    val log = LiveLaneLaunchLog()
    log.record(
      "meshcore-mobile",
      "the module daemons materialized but their render host would not open",
    )

    assertEquals(
      "the module daemons materialized but their render host would not open",
      log.lastReason("meshcore-mobile"),
    )
  }

  @Test
  fun `blank lines never displace a real reason`() {
    val log = LiveLaneLaunchLog()
    val sink = log.sink("m3-catalog")
    sink("catalog m3-catalog: bundle extraction failed (truncated zip)")
    sink("   ")

    assertEquals(
      "catalog m3-catalog: bundle extraction failed (truncated zip)",
      log.lastReason("m3-catalog"),
    )
  }

  @Test
  fun `the map is bounded, and the systems it already tracks keep updating`() {
    // A box pointed at a catalog registry serves catalogs this map never enumerated, so it must not
    // grow without bound. The bound must not freeze the entries it holds, though: the catalog that
    // keeps failing is exactly the one whose newest reason matters.
    val log = LiveLaneLaunchLog(maxSystems = 2)
    log.record("a", "first")
    log.record("b", "second")
    log.record("c", "turned away at the cap")
    log.record("a", "updated")

    assertEquals("updated", log.lastReason("a"))
    assertEquals("second", log.lastReason("b"))
    assertNull(log.lastReason("c"))
  }
}
