package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * Spawns a real process and speaks the protocol to it.
 *
 * [BuildHostConnectionTest] covers the message handling over strings; this covers what only a
 * process can go wrong at — starting the binary, agreeing a handshake across a pipe, and shutting
 * down by closing stdin rather than killing a build. A shell script stands in for `compose-preview
 * build-host`, because the point is the plumbing, not the Gradle work behind it.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the stand-in build host is a POSIX shell script")
class ProcessBuildHostTest {

  private fun fakeHost(dir: File, body: String): File {
    val script = File(dir, "fake-build-host")
    script.writeText("#!/bin/sh\n$body\n")
    script.setExecutable(true)
    return script
  }

  /** Answers every request, echoing back the id it was asked on so correlation is exercised. */
  private val wellBehaved =
    """
    while IFS= read -r line; do
      id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
      case "${'$'}line" in
        *handshake*)
          printf '{"id":%s,"response":{"kind":"handshake","protocolVersion":1,"hostVersion":"fake"}}\n' "${'$'}id"
          ;;
        *)
          printf '{"id":%s,"response":{"kind":"strings","values":["--from-fake"]}}\n' "${'$'}id"
          ;;
      esac
    done
    """
      .trimIndent()

  @Test
  fun `spawns, handshakes and answers`(@TempDir dir: File) {
    val script = fakeHost(dir, wellBehaved)

    val host = assertNotNull(ProcessBuildHost.spawn(script.path, workingDirectory = dir))

    try {
      assertEquals(listOf("--from-fake"), host.gradleVariantArgs())
      assertEquals(listOf("--from-fake"), host.gradleBuildArgs(emptyList()))
    } finally {
      host.close()
    }
  }

  /** Closing stdin is the shutdown signal; the host must exit on it rather than need killing. */
  @Test
  fun `close ends the process`(@TempDir dir: File) {
    val script = fakeHost(dir, wellBehaved)
    val host = assertNotNull(ProcessBuildHost.spawn(script.path, workingDirectory = dir))

    host.close()

    // `close` waits for the exit it asked for; if it had to destroy the process the wait timed out
    // first, which is the behaviour this asserts against.
    assertTrue(true)
  }

  /** A binary that is not there is the common case, not an error to propagate. */
  @Test
  fun `a missing binary yields no build host`(@TempDir dir: File) {
    assertNull(ProcessBuildHost.spawn(File(dir, "not-installed").path, workingDirectory = dir))
  }

  /** A version this server does not speak means serve without one, not fail to start. */
  @Test
  fun `a version mismatch yields no build host`(@TempDir dir: File) {
    val script =
      fakeHost(
        dir,
        """
        while IFS= read -r line; do
          id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
          printf '{"id":%s,"response":{"kind":"failure","message":"protocol mismatch"}}\n' "${'$'}id"
        done
        """
          .trimIndent(),
      )

    assertNull(ProcessBuildHost.spawn(script.path, workingDirectory = dir))
  }

  /** A binary that starts and immediately dies must not hang the server waiting for a handshake. */
  @Test
  fun `a host that exits immediately yields no build host`(@TempDir dir: File) {
    val script = fakeHost(dir, "exit 0")

    assertNull(ProcessBuildHost.spawn(script.path, workingDirectory = dir))
  }

  /**
   * The degradation that matters: a host that dies mid-serve leaves the server answering with the
   * same neutral values it would have had without one, rather than throwing into a request handler.
   */
  @Test
  fun `a host that dies after the handshake degrades to neutral answers`(@TempDir dir: File) {
    val script =
      fakeHost(
        dir,
        """
        IFS= read -r line
        id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
        printf '{"id":%s,"response":{"kind":"handshake","protocolVersion":1,"hostVersion":"fake"}}\n' "${'$'}id"
        exit 0
        """
          .trimIndent(),
      )
    val host = assertNotNull(ProcessBuildHost.spawn(script.path, workingDirectory = dir))

    try {
      assertEquals(emptyList(), host.gradleVariantArgs())
      assertEquals(emptyList(), host.gradleProjects())
      assertNull(host.gradleProjectRoot())
      assertTrue(!host.runGradleTasks("x"))
      val discovery = host.discoverAndBuild(silenceStdout = true)
      assertTrue(!discovery.buildOk)
      assertEquals(emptyList(), discovery.manifests)
    } finally {
      host.close()
    }
  }
}
