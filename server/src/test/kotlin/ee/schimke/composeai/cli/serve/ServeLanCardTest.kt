package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The operator's "Open on your phone" card on a `--lan` server's landing page. It carries the
 * operator's token, so it must appear only for a direct, local, token-holding request.
 */
class ServeLanCardTest {

  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  private fun start(host: String): ServeHttpServer {
    val dir = Files.createTempDirectory("lan-card").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    registry.register(
      "default-mod",
      host = ServeBundleHost(dir, label = "default-mod", declaredBaked = emptyList()),
      pinned = true,
    )
    return ServeHttpServer(
        host = host,
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "default-mod",
        lanAddresses = { listOf("192.168.1.23") },
      )
      .also { it.start() }
  }

  private fun get(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    return client.newCall(request).execute().use { it.code to it.body.string() }
  }

  @Test
  fun `the operator sees the card on a lan server, nobody else does`() {
    server = start(ServeUrls.ALL_INTERFACES)

    val (code, page) = get("/?token=operator-token")
    assertEquals(200, code)
    assertTrue(page.contains("cp-lan-card"), page)
    assertTrue(page.contains("<svg"), "the QR code is drawn on the page")
    assertTrue(page.contains("http://192.168.1.23:${server!!.port}/?token=operator-token"), page)
    assertTrue(page.contains("adb reverse tcp:${server!!.port} tcp:${server!!.port}"), page)

    // Through a proxy on the same box every visitor looks local; the card must not follow them.
    val (_, proxied) = get("/?token=operator-token", mapOf("X-Forwarded-For" to "203.0.113.9"))
    assertFalse(proxied.contains("cp-lan-card"), "never through a proxy")
    // Nor to an agent or anyone else without the operator's own token.
    assertEquals(404, get("/").first)
  }

  @Test
  fun `a loopback-only server shows no card`() {
    server = start(ServeUrls.LOOPBACK)
    val (code, page) = get("/?token=operator-token")
    assertEquals(200, code)
    assertFalse(page.contains("cp-lan-card"))
  }
}
