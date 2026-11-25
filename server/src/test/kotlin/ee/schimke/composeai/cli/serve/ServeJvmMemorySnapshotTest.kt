package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeJvmMemorySnapshotTest {

  @Test
  fun `reads this jvm's heap`() {
    val snapshot = ServeJvmMemorySnapshot.read()

    assertTrue(snapshot.heapUsedBytes > 0)
    assertTrue(snapshot.heapCommittedBytes >= snapshot.heapUsedBytes)
    val max = assertNotNull(snapshot.heapMaxBytes, "a test JVM has a heap ceiling")
    assertTrue(max >= snapshot.heapCommittedBytes)
    assertTrue(snapshot.threads > 0)
  }

  @Test
  fun `the live fraction is rounded and absent without both halves`() {
    assertEquals(0.857, ServeJvmMemorySnapshot.liveFraction(2_400, 2_800))
    assertNull(ServeJvmMemorySnapshot.liveFraction(null, 2_800))
    assertNull(ServeJvmMemorySnapshot.liveFraction(2_400, null))
    assertNull(ServeJvmMemorySnapshot.liveFraction(2_400, 0))
  }
}
