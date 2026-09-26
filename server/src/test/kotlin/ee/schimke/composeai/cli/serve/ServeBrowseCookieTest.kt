package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The browse token as a cookie ([ServeBrowseCookie]): a browser page load carrying `?token=` is
 * traded for the cookie and redirected without it, the cookie alone then passes the token gate and
 * yields token-free links, and a machine client presenting the token in a header or query is
 * answered exactly as before.
 */
class ServeBrowseCookieTest {

  private lateinit var registry: ServeSessionRegistry
  private lateinit var server: ServeHttpServer
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @BeforeTest
  fun setUp() {
    val appDir =
      Files.createTempDirectory("serve-browse-cookie").toFile().also { it.deleteOnExit() }
    File(appDir, "index.html").writeText("<!doctype html><script src=app.js></script>")
    File(appDir, "app.js").writeText("window.started = true")
    registry = ServeSessionRegistry(open = { null })
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = TOKEN,
          sessions = registry,
          defaultSessionId = "local",
          wasmCatalogs = mapOf("local" to appDir),
          privateWasmCatalogs = setOf("local"),
        )
        .also { it.start() }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun <T> get(
    path: String,
    vararg headers: Pair<String, String>,
    read: (Response) -> T,
  ): T {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$path")
    headers.forEach { (name, value) -> builder.header(name, value) }
    return client.newCall(builder.build()).execute().use(read)
  }

  private val browserPage = arrayOf("Accept" to "text/html,application/xhtml+xml")
  private val cookie = "Cookie" to "${ServeBrowseCookie.NAME}=${ServeBrowseCookie.value(TOKEN)}"

  @Test
  fun `a browser page load trades the query token for a cookie and a clean URL`() {
    get("/status?a=1&token=$TOKEN&b=2", *browserPage) { response ->
      assertEquals(302, response.code)
      assertEquals("/status?a=1&b=2", response.header("Location"))
      val setCookie = response.headers("Set-Cookie").single { it.startsWith("cp_browse=") }
      assertFalse(setCookie.contains(TOKEN), "the cookie holds a derived value: $setCookie")
      assertTrue(setCookie.contains("HttpOnly"), setCookie)
      assertTrue(setCookie.contains("SameSite=Lax"), setCookie)
      assertTrue(setCookie.contains("Path=/"), setCookie)
    }
    // Fetch metadata says the same thing a modern browser means by it.
    get(
      "/status?token=$TOKEN",
      "Sec-Fetch-Mode" to "navigate",
      "Sec-Fetch-Dest" to "document",
    ) { response ->
      assertEquals(302, response.code)
      assertEquals("/status", response.header("Location"))
    }
  }

  @Test
  fun `the cookie alone passes the gate and the page links carry no token`() {
    val (code, body) = get("/status", *browserPage, cookie) { it.code to it.body.string() }
    assertEquals(200, code)
    assertFalse(body.contains("token="), "a cookie-authenticated page links without the token")
    assertEquals(404, get("/status", "Cookie" to "cp_browse=forged") { it.code })
    assertEquals(404, get("/status", "Cookie" to "cp_browse=$TOKEN") { it.code })
    assertEquals(404, get("/status") { it.code })
  }

  @Test
  fun `machine clients keep the header and query token unchanged`() {
    get("/status", ServeHttpServer.TOKEN_HEADER to TOKEN) { response ->
      assertEquals(200, response.code)
      assertTrue(response.headers("Set-Cookie").none { it.startsWith("cp_browse=") })
    }
    // A query token on a request that is not a browser page load is answered in place, and the
    // page it gets still threads that token through its links.
    get("/status?token=$TOKEN") { response ->
      assertEquals(200, response.code)
      assertTrue(response.body.string().contains("token=$TOKEN"))
    }
    get("/status.json?token=$TOKEN", "Accept" to "application/json") { assertEquals(200, it.code) }
    // An embedded frame is not the top-level load the exchange is for.
    get(
      "/status?token=$TOKEN",
      "Sec-Fetch-Mode" to "navigate",
      "Sec-Fetch-Dest" to "iframe",
    ) {
      assertEquals(200, it.code)
    }
  }

  @Test
  fun `a wrong token is never exchanged`() {
    get("/status?token=wrong", *browserPage) { response ->
      assertEquals(404, response.code)
      assertTrue(response.headers("Set-Cookie").none { it.startsWith("cp_browse=") })
    }
  }

  @Test
  fun `the private wasm route accepts the derived access segment and old token URLs`() {
    val access = ServeBrowseCookie.wasmAccess(TOKEN)
    assertNotEquals(TOKEN, access)
    assertEquals(200, get("/wasm-private/$access/local/") { it.code })
    assertEquals(
      "window.started = true",
      get("/wasm-private/$access/local/app.js") { it.body.string() },
    )
    assertEquals(200, get("/wasm-private/$TOKEN/local/") { it.code })
    assertEquals(404, get("/wasm-private/${ServeBrowseCookie.value(TOKEN)}/local/") { it.code })
  }

  @Test
  fun `only the token parameter is dropped from the redirect`() {
    assertEquals("", ServeBrowseCookie.queryWithoutToken("token=x"))
    assertEquals("?a=1&b=%20", ServeBrowseCookie.queryWithoutToken("token=x&a=1&b=%20"))
    assertEquals("?tokens=1", ServeBrowseCookie.queryWithoutToken("tokens=1&t%6Fken=x"))
  }

  private companion object {
    const val TOKEN = "browse-cookie-secret"
  }
}
