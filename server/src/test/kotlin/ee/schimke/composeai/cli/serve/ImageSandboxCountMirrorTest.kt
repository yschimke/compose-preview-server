package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The image's `JAVA_TOOL_OPTIONS` carries the daemon sandbox-count key as a **literal**, which no
 * Kotlin scan can see. Rename the key upstream and the Dockerfile goes on setting a property
 * nothing reads: every daemon silently falls back to a pool of one, and nothing fails — the box
 * just gets slower.
 *
 * This used to be checked from compose-ai-tools, which resolved this repository's Dockerfile from a
 * sibling checkout. That was backwards twice over. It inverted the dependency — that repository
 * consumes nothing from this one — and it compared two default branches, while the image actually
 * runs a *pinned* release. It could go red for a rename that had not shipped, and green while the
 * pinned pairing disagreed.
 *
 * Here it is an ordinary unit test: [DaemonLaunchDescriptor.SANDBOX_COUNT_PROP] is a `public const
 * val` in the published ABI of `daemon-protocol`, which this module already resolves, so the
 * constant read below comes from **the exact artifact version this image ships against**. No
 * checkout, no environment variable, nothing to skip.
 */
class ImageSandboxCountMirrorTest {

  @Test
  fun `the image sets the sandbox-count property the daemon actually reads`() {
    val dockerfile = File(repoRoot(), "deploy/image/Dockerfile")
    assertTrue(dockerfile.isFile, "missing ${dockerfile.path}")
    val text = dockerfile.readText()

    val key = DaemonLaunchDescriptor.SANDBOX_COUNT_PROP
    // `-Dkey=value`, not a bare mention: the point is that the image SETS it. A comment naming the
    // property — and this Dockerfile has several — would satisfy a plain `contains(key)` while the
    // JVM flag was gone.
    val assignment = Regex("-D${Regex.escape(key)}=(\\S+)").find(text)
    assertNotNull(
      assignment,
      "deploy/image/Dockerfile does not set -D$key=… . If the key was renamed upstream, update " +
        "JAVA_TOOL_OPTIONS to match; otherwise every daemon on the box silently gets a pool of one.",
    )

    // The descriptor's own `withSandboxCount()` requires >= 1, so a value it would reject is a
    // misconfiguration however well the key is spelled.
    val count = assignment.groupValues[1].toIntOrNull()
    assertNotNull(count, "-D$key=${assignment.groupValues[1]} is not an integer")
    assertTrue(count >= 1, "-D$key=$count, but sandboxCount must be >= 1")
  }

  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root from ${System.getProperty("user.dir")}")
  }
}
