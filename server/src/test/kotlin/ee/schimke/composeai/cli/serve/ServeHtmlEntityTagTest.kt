package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * #217: the `ETag` an assembled HTML page is revalidated against.
 *
 * [ServeHttpServer.ANON_PAGE_CACHE_CONTROL] is `max-age=0, … must-revalidate` — a standing
 * instruction to ask again on every navigation. With no validator beside it there was nothing for
 * that question to be answered `304` with, so every repeat visit re-assembled the page server-side
 * and re-sent the whole body (367 KB of markup on the deployed `/m3-catalog/`). These tests pin
 * that a permitted revalidation now ends in a `304`, and that nothing about **who may store a
 * response** moved: that is still decided by `Vary: Cookie` and the `private`/`public` split.
 */
class ServeHtmlEntityTagTest {

  private val clock =
    object : Clock() {
      override fun getZone() = ZoneOffset.UTC

      override fun withZone(zone: java.time.ZoneId?) = this

      override fun instant(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body("""{"login":"octo"}""".toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    val dir = Files.createTempDirectory("etag-catalog").toFile()
    dir.deleteOnExit()
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    registry.register(
      "m3-catalog",
      host = ServeBundleHost(dir, label = "m3-catalog", title = "M3"),
      pinned = true,
    )
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "m3-catalog",
        isPublic = true,
        // Configured auth is what puts the landing on the anonymous page lifetime — the
        // `must-revalidate` with nothing to revalidate against that this is all about.
        githubAuth =
          ServeGithubAuth(
            ServeGithubAuthConfig(
              clientId = "client",
              clientSecret = "secret",
              cookieSecret = "x".repeat(32),
              repository = "yschimke/compose-ai-tools",
              callbackBaseUrl = "https://preview.coo.ee",
            ),
            verifier = GitHubOAuthVerifier(fakeGitHub),
            clock = clock,
            anonymousClient = fakeGitHub,
          ),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun request(
    path: String,
    ifNoneMatch: String? = null,
    acceptEncoding: String? = null,
    head: Boolean = false,
  ): Response {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$path")
    if (ifNoneMatch != null) builder.header("If-None-Match", ifNoneMatch)
    if (acceptEncoding != null) builder.header("Accept-Encoding", acceptEncoding)
    if (head) builder.head()
    return client.newCall(builder.build()).execute()
  }

  @Test
  fun `an anonymous page carries a strong validator its revalidation can land on`() {
    val etag =
      request("/m3-catalog/").use {
        assertEquals(200, it.code)
        assertEquals(ServeHttpServer.ANON_PAGE_CACHE_CONTROL, it.header("Cache-Control"))
        val tag = assertNotNull(it.header("ETag"), "the page a cache must revalidate needs one")
        // Strong, not `W/`: the body was hashed, so "these exact bytes" is a claim the server can
        // make — which is what lets a shared cache reuse the entry rather than merely compare it.
        assertFalse(tag.startsWith("W/"), tag)
        assertTrue(tag.startsWith("\"") && tag.endsWith("\""), tag)
        assertTrue(it.body.string().isNotEmpty())
        tag
      }

    request("/m3-catalog/", ifNoneMatch = etag).use {
      assertEquals(304, it.code)
      assertEquals(0, it.body.bytes().size, "a 304 carries no body — that is the whole saving")
      // The directives a revalidating cache is asking to have refreshed come back with it, so the
      // privacy rule survives the short-circuit intact.
      assertEquals(ServeHttpServer.ANON_PAGE_CACHE_CONTROL, it.header("Cache-Control"))
      assertEquals(etag, it.header("ETag"))
      assertTrue(it.headers("Vary").any { value -> value.contains("Cookie", ignoreCase = true) })
    }
  }

  @Test
  fun `a validator that names other bytes gets the page`() {
    request("/m3-catalog/", ifNoneMatch = "\"0-0000000000000000\"").use {
      assertEquals(200, it.code)
      assertTrue(it.body.string().isNotEmpty())
    }
  }

  @Test
  fun `HEAD names the same validator as GET, so a probe learns the truth`() {
    val fromGet = request("/m3-catalog/").use { it.header("ETag") }
    request("/m3-catalog/", head = true).use {
      assertEquals(200, it.code)
      assertEquals(fromGet, it.header("ETag"))
    }
  }

  @Test
  fun `the validator is the page's, not the frame it was gzipped into`() {
    val identity =
      request("/m3-catalog/", acceptEncoding = "identity").use {
        assertNull(it.header("Content-Encoding"))
        it.header("ETag")
      }
    val gzipped =
      request("/m3-catalog/", acceptEncoding = "gzip").use {
        assertEquals("gzip", it.header("Content-Encoding"))
        it.header("ETag")
      }
    assertEquals(identity, gzipped)
  }

  @Test
  fun `a page nobody may store carries no validator at all`() {
    // `/status` is `no-store`. There is nothing to revalidate against a cache that was told to
    // keep nothing, and the same reasoning covers every signed-in page
    // ([ServeHttpServer.SIGNED_IN_PAGE_CACHE_CONTROL] is `private, no-store`).
    request("/status").use {
      assertEquals(200, it.code)
      assertEquals("no-store", it.header("Cache-Control"))
      assertNull(it.header("ETag"))
    }
  }

  @Test
  fun `the validator is a hash of the page, minus the tally that moves under it`() {
    request("/m3-catalog/", acceptEncoding = "identity").use {
      assertEquals(ServeHttpServer.pageEntityTag(it.body.string()), it.header("ETag"))
    }
    // Markup that changes gets a different validator — which is what makes the 304 a true claim…
    assertTrue(
      ServeHttpServer.pageEntityTag("<html>a</html>") !=
        ServeHttpServer.pageEntityTag("<html>b</html>")
    )
    // …except inside the marked element, whose whole job is to not decide this.
    val volatile = ServeWeb.VOLATILE_ATTR
    assertEquals(
      ServeHttpServer.pageEntityTag("<p>x<span $volatile>1 view</span></p>"),
      ServeHttpServer.pageEntityTag("<p>x<span $volatile>2.3k views</span></p>"),
    )
    assertTrue(
      ServeHttpServer.pageEntityTag("<p>x<span $volatile>1 view</span></p>") !=
        ServeHttpServer.pageEntityTag("<p>y<span $volatile>1 view</span></p>")
    )
  }

  @Test
  fun `an If-None-Match a proxy weakened, or a list, still hits`() {
    val etag = "\"2a-0123456789abcdef\""
    assertTrue(ServeHttpServer.ifNoneMatchHits(etag, etag))
    assertTrue(ServeHttpServer.ifNoneMatchHits("W/$etag", etag))
    assertTrue(ServeHttpServer.ifNoneMatchHits("\"other\", $etag", etag))
    assertTrue(ServeHttpServer.ifNoneMatchHits("*", etag))
    assertFalse(ServeHttpServer.ifNoneMatchHits(null, etag))
    assertFalse(ServeHttpServer.ifNoneMatchHits("", etag))
    assertFalse(ServeHttpServer.ifNoneMatchHits("\"other\"", etag))
  }
}
