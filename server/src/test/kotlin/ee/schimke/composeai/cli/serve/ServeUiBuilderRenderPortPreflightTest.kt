package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one diagnostic standing between a wrong-JVM launch and an `UnsupportedClassVersionError` on
 * the stderr of a daemon the operator never started
 * ([#344](https://github.com/yschimke/compose-preview-server/issues/344)).
 *
 * Asserted on content rather than wording, because the wording is not the contract: the three facts
 * are. An operator who is told only that the renderer is unavailable learns nothing they could not
 * already see, and that is exactly the message this replaced.
 */
class ServeUiBuilderRenderPortPreflightTest {

  @Test
  fun `a JVM at or above the bundle's floor passes`() {
    assertNull(ServeUiBuilderRenderPort.jvmPreflightFailure(21, 21, "/opt/java/21"))
    assertNull(ServeUiBuilderRenderPort.jvmPreflightFailure(21, 25, "/opt/java/25"))
  }

  @Test
  fun `a JVM below it fails naming both versions and the way out`() {
    val message =
      requireNotNull(ServeUiBuilderRenderPort.jvmPreflightFailure(21, 17, "/opt/java/17"))

    assertTrue("Java 21" in message, message)
    assertTrue("Java 17" in message, message)
    assertTrue("/opt/java/17" in message, message)
    assertTrue("JAVA_HOME" in message, message)
    assertTrue("--ui-builder-state-dir none" in message, message)
  }
}
