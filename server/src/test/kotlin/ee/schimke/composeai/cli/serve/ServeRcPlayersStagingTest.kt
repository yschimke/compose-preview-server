package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `lib-rc-players-android/` the distribution packages (`stageRcPlayersAndroidLibs`), read as
 * built rather than as a source tree: the player-only daemon offers exactly the players these jars
 * carry, and preview.coo.ee listed `CMP Android (unavailable)` on every document-only catalog
 * because the directory held no CMP player.
 */
class ServeRcPlayersStagingTest {

  private val stagedJars: List<File> =
    File(
        checkNotNull(System.getProperty("composeai.test.rcPlayersAndroidDir")) {
          "composeai.test.rcPlayersAndroidDir is set by the server's Gradle test task"
        }
      )
      .listFiles { file -> file.extension == "jar" }
      .orEmpty()
      .sortedBy { it.name }

  @Test
  fun `the staged directory offers every Android player, the CMP one included`() {
    assertEquals(
      listOf(
        RcPlayerBackend.ANDROIDX_EMBEDDED,
        RcPlayerBackend.ANDROIDX_VIEW,
        RcPlayerBackend.CMP_ANDROID,
      ),
      ServeRcPlayerDaemon.playersIn(stagedJars),
    )
  }

  @Test
  fun `the CMP player's tail is staged, but not the Compose the sidecar already ships`() {
    val entries = stagedJars.flatMap { jar ->
      ZipFile(jar).use { zip -> zip.entries().toList().map { it.name } }
    }
    // What the CMP player links beyond its own jar and that the Android sidecar lacks.
    for (marker in
      listOf(
        "ee/schimke/composeai/rcplayer/protocol/",
        "ee/schimke/composeai/rcplayer/trace/",
        "coil3/network/ktor3/",
        "io/ktor/client/engine/cio/",
        "kotlinx/datetime/",
      )) {
      assertTrue(entries.any { it.startsWith(marker) }, "$marker is staged")
    }
    // These jars precede the sidecar on the daemon classpath, so a second Compose or AndroidX
    // runtime here would shadow the sidecar's (and could be older than it).
    for (sidecarPackage in
      listOf(
        "androidx/compose/runtime/",
        "androidx/compose/ui/",
        "androidx/compose/foundation/",
        "androidx/lifecycle/",
        "androidx/core/",
        "kotlin/collections/",
      )) {
      assertTrue(
        entries.none { it.startsWith(sidecarPackage) && it.endsWith(".class") },
        "$sidecarPackage is the sidecar's, not staged",
      )
    }
  }
}
