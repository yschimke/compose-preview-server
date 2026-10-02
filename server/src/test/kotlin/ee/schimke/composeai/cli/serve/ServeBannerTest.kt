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
    qr: Boolean = false,
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
        qr = qr,
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

  /**
   * `--lan` in an interactive terminal draws the first network URL as a QR code, and says that a
   * plain-http LAN origin is not a secure context. A supplied token is redacted in the text, so the
   * code carries no token either rather than a scannable copy of the secret.
   */
  @Test
  fun `a lan banner draws a QR code and names the secure-context caveat`() {
    val generated =
      banner(
        token = "generatedtoken123",
        tokenSupplied = false,
        networkOrigins = listOf("http://192.168.1.5:8080"),
        qr = true,
      )
    assertTrue(generated.contains("adb reverse tcp:8080 tcp:8080"), generated)
    assertTrue(generated.contains("not a secure context"), generated)
    val expected =
      ServeQrCode.encode("http://192.168.1.5:8080/?token=generatedtoken123")!!.terminalLines()
    assertTrue(expected.all { generated.contains(it) }, "the code encodes the full LAN link")

    val supplied = banner(networkOrigins = listOf("http://192.168.1.5:8080"), qr = true)
    val bare = ServeQrCode.encode("http://192.168.1.5:8080/")!!.terminalLines()
    assertTrue(bare.all { supplied.contains(it) }, "a supplied token stays out of the code")

    val log = banner(networkOrigins = listOf("http://192.168.1.5:8080"), qr = false)
    assertFalse(log.contains("\u001b["), "no QR (and no ANSI) when the output is not a terminal")
  }
}
