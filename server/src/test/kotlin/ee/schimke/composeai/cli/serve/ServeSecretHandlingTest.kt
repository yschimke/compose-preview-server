package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server's own secrets: how they are read, printed and kept from build children. */
class ServeSecretHandlingTest {
  private val key = "sk-or-v1-not-a-real-key"
  private val token = "ghp_not_a_real_token"

  @Test
  fun `the guidelines config never prints its secrets`() {
    val config =
      ServeUiBuilderGuidelinesConfig(
        apiKey = key,
        allowedUsers = setOf("alice"),
        githubToken = token,
      )
    val printed = config.toString()
    assertFalse(printed.contains(key), printed)
    assertFalse(printed.contains(token), printed)
    assertTrue(printed.contains("apiKey=<redacted>"), printed)
    assertTrue(printed.contains("githubToken=<redacted>"), printed)
    assertTrue(printed.contains("alice"), printed)
    assertTrue(printed.contains(config.model), printed)
    // Interpolation goes through toString too.
    assertFalse("$config".contains(key))
    assertTrue(config.copy(githubToken = null).toString().contains("githubToken=null"))
  }

  @Test
  fun `a secret is read from the variable or from the file its _FILE names`() {
    val name = ServeUiBuilderGuidelinesConfig.API_KEY_ENV
    assertNull(ServeSecretEnv.read(name, emptyMap()))
    assertEquals(key, ServeSecretEnv.read(name, mapOf(name to " $key\n")))

    val file = Files.createTempFile("openrouter", ".key").toFile().apply { writeText("$key\n") }
    val warnings = mutableListOf<String>()
    assertEquals(key, ServeSecretEnv.read(name, mapOf("${name}_FILE" to file.path), warnings::add))
    assertTrue(warnings.isEmpty())

    // Both set: the file wins, and the warning names variables, never a value.
    assertEquals(
      key,
      ServeSecretEnv.read(name, mapOf(name to "other", "${name}_FILE" to file.path), warnings::add),
    )
    assertEquals(1, warnings.size)
    assertFalse(warnings.single().contains(key))
    assertFalse(warnings.single().contains("other"))
  }

  @Test
  fun `a missing or empty secret file reads as unset, without its contents`() {
    val name = ServeUiBuilderGuidelinesConfig.GITHUB_TOKEN_ENV
    val warnings = mutableListOf<String>()
    val missing = File(Files.createTempDirectory("secrets").toFile(), "absent")
    assertNull(ServeSecretEnv.read(name, mapOf("${name}_FILE" to missing.path), warnings::add))
    val empty = Files.createTempFile("token", "").toFile()
    assertNull(ServeSecretEnv.read(name, mapOf("${name}_FILE" to empty.path), warnings::add))
    assertEquals(2, warnings.size)
    assertTrue(warnings.all { it.contains("${name}_FILE") })
  }

  @Test
  fun `a build child keeps what Gradle needs and none of the server's secrets`() {
    val parent =
      mutableMapOf(
        "PATH" to "/usr/bin",
        "HOME" to "/root",
        "JAVA_HOME" to "/opt/jdk",
        "GRADLE_USER_HOME" to "/root/.gradle",
        "GRADLE_OPTS" to "-Xmx2g",
        "ANDROID_HOME" to "/opt/android",
        "ANDROID_NDK_HOME" to "/opt/android/ndk",
        "ORG_GRADLE_PROJECT_mirror" to "https://mirror.example",
        "https_proxy" to "http://proxy:3128",
        "COMPOSEAI_AGENT_ID" to "a1",
        "COMPOSE_PREVIEW_OFFLINE" to "1",
        "SERVE_TOKEN" to "operator-token",
        "SERVE_GITHUB_AUTH_CLIENT_SECRET" to "client-secret",
        ServeUiBuilderGuidelinesConfig.API_KEY_ENV to key,
        ServeUiBuilderGuidelinesConfig.GITHUB_TOKEN_ENV to token,
        "COMPOSE_PREVIEW_OPENROUTER_KEY" to key,
        "COMPOSE_PREVIEW_SERVE_TOKEN" to "cli-token",
        "GITHUB_TOKEN" to token,
        "AWS_SECRET_ACCESS_KEY" to "aws",
      )
    BuildChildEnvironment.apply(parent)
    assertEquals(
      setOf(
        "PATH",
        "HOME",
        "JAVA_HOME",
        "GRADLE_USER_HOME",
        "GRADLE_OPTS",
        "ANDROID_HOME",
        "ANDROID_NDK_HOME",
        "ORG_GRADLE_PROJECT_mirror",
        "https_proxy",
        "COMPOSEAI_AGENT_ID",
        "COMPOSE_PREVIEW_OFFLINE",
      ),
      parent.keys,
    )
    assertFalse(parent.values.any { it == key || it == token })
  }

  @Test
  fun `the allowlist reaches a real build child's environment`() {
    val builder = ProcessBuilder("true")
    builder.environment()["SERVE_TOKEN"] = "operator-token"
    builder.environment()["JAVA_HOME"] = "/opt/jdk"
    BuildChildEnvironment.applyTo(builder)
    assertNull(builder.environment()["SERVE_TOKEN"])
    assertEquals("/opt/jdk", builder.environment()["JAVA_HOME"])
  }
}
