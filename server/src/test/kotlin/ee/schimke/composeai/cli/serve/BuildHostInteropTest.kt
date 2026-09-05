package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Drives the **real** `compose-preview` build host, when one is pointed at.
 *
 * Opt-in, because the CLI is published from another repository and is not on a CI runner here:
 * ```
 * COMPOSE_PREVIEW_CLI=/path/to/compose-preview ./gradlew :server:test --tests '*BuildHostInterop*'
 * ```
 *
 * Everything else about this seam is tested against a stand-in — [ProcessBuildHostTest] uses a
 * shell script, and compose-ai-tools' own tests drive its request loop directly. Neither proves the
 * two halves agree, which is the only thing that actually matters about a protocol spanning two
 * repositories on two release cadences. This is where that is checked.
 */
class BuildHostInteropTest {

  private val cli: String? = System.getenv("COMPOSE_PREVIEW_CLI")?.takeIf { it.isNotBlank() }

  @Test
  fun `the published CLI agrees the protocol and answers`() {
    assumeTrue(cli != null, "set COMPOSE_PREVIEW_CLI to run the cross-repository interop check")
    assumeTrue(File(cli!!).canExecute(), "COMPOSE_PREVIEW_CLI is not executable: $cli")

    val host = assertNotNull(ProcessBuildHost.spawn(cli, workingDirectory = File(".")))

    try {
      // A handshake happened or `spawn` would have returned null. These exercise the three shapes
      // the protocol carries back — a string list, an optional path, and a module list — against a
      // real implementation rather than a script echoing canned JSON.
      host.gradleVariantArgs()
      host.gradleBuildArgs(listOf("--offline"))
      val root = host.gradleProjectRoot()
      if (root != null) {
        assertTrue(root.isAbsolute, "the CLI sent a relative project root: $root")
        assertTrue(
          root.path == File(root.path).normalize().path,
          "project root not normalised: $root",
        )
      }
    } finally {
      host.close()
    }
  }
}
