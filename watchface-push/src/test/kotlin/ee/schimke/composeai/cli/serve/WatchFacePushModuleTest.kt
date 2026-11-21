package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchFacePushModuleTest {

  @Test
  fun `packageName prefixes marketplace applicationId and keeps long ids bounded`() {
    val shortPkg = WatchFacePushNaming.packageName("my-watch-face")
    assertTrue(shortPkg.startsWith(WatchFacePushNaming.PACKAGE_PREFIX))

    val longId = "a".repeat(80)
    val longPkg = WatchFacePushNaming.packageName(longId)
    assertTrue(longPkg.startsWith(WatchFacePushNaming.PACKAGE_PREFIX))
    assertEquals(longPkg, WatchFacePushNaming.packageName(longId))
  }

  @Test
  fun `push builds setting defaults to true and disables on 0 or false`() {
    assertEquals(true, WatchFacePushBuildsSetting.enabled(emptyMap()))
    assertEquals(
      true,
      WatchFacePushBuildsSetting.enabled(mapOf(WatchFacePushBuildsSetting.ENV to "1")),
    )
    assertEquals(
      false,
      WatchFacePushBuildsSetting.enabled(mapOf(WatchFacePushBuildsSetting.ENV to "0")),
    )
    assertEquals(
      false,
      WatchFacePushBuildsSetting.enabled(mapOf(WatchFacePushBuildsSetting.ENV to "false")),
    )
  }
}
