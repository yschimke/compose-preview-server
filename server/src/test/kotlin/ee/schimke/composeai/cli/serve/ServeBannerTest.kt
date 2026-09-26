package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeBannerTest {
  private val supplied = "s3cr3t-operator-token-value"

  private fun banner(
    token: String = supplied,
    tokenSupplied: Boolean = true,
    public: Boolean = false,
    networkOrigins: List<String>? = null,
  ): String =
    ServeBanner.lines(
        moduleLabel = ":app",
        localOrigin = "http://127.0.0.1:8080",
        networkOrigins = networkOrigins,
        token = token,
        tokenSupplied = tokenSupplied,
        public = public,
        previewCount = 3,
        builderPath = "/ui-builder/",
        acceptDocs = true,
      )
      .joinToString("\n")

  @Test
  fun `a supplied token never appears in full, only as a prefix`() {
    val text = banner(networkOrigins = listOf("http://192.168.1.5:8080"))
    assertFalse(text.contains(supplied), text)
    assertTrue(text.contains("  Local:   http://127.0.0.1:8080/?token=s3cr…"), text)
    assertTrue(text.contains("  Network: http://192.168.1.5:8080/?token=s3cr…"), text)
    assertTrue(text.contains("  Builder: http://127.0.0.1:8080/ui-builder/?token=s3cr…"), text)
    assertTrue(text.contains("  Documents: http://127.0.0.1:8080/docs?token=s3cr…"), text)
    assertTrue(text.contains("--token"), "says where the token is configured: $text")
  }

  @Test
  fun `a short supplied token is not shown at all`() {
    val text = banner(token = "abcdef")
    assertFalse(text.contains("abcd"), text)
    assertTrue(text.contains("?token=…"), text)
  }

  @Test
  fun `a generated token is printed whole, because the banner is the only place it exists`() {
    val generated = "generated-token-value"
    val text = banner(token = generated, tokenSupplied = false)
    assertTrue(text.contains("  Local:   http://127.0.0.1:8080/?token=$generated"), text)
    assertFalse(text.contains("Token:"), text)
  }

  @Test
  fun `a public server prints no token at all`() {
    val text = banner(public = true)
    assertFalse(text.contains("token="), text)
    assertEquals(1, text.lines().count { it.startsWith("  Local:") })
  }
}
