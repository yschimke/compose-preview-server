package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.io.TempDir

class ServeComponentPublicationsTest {
  @TempDir lateinit var directory: Path
  private val client = OkHttpClient()
  private var server: ServeHttpServer? = null
  private val registry = ServeSessionRegistry(open = { null })
  private val publicationRoot
    get() = directory.resolve("publications")

  @AfterTest
  fun close() {
    server?.stop()
    registry.close()
  }

  private fun start(viewer: Boolean = true, enabled: Boolean = true) {
    val viewerRoot = directory.resolve("viewer")
    Files.createDirectories(viewerRoot)
    Files.writeString(viewerRoot.resolve("viewer.js"), "// operator viewer")
    Files.writeString(viewerRoot.resolve("viewer.css"), "body { color: black; }")
    Files.writeString(viewerRoot.resolve("credentials.json"), "must not be served")
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "operator-secret",
          sessions = registry,
          defaultSessionId = "unused",
          componentPublications =
            if (!enabled) null
            else
              ServeComponentPublications.fromEnvironment(
                buildMap {
                  put("SERVE_COMPONENT_PUBLICATIONS_DIR", publicationRoot.toString())
                  put("SERVE_COMPONENT_PUBLICATIONS_ORIGIN", "https://preview.example")
                  if (viewer) put("SERVE_COMPONENT_VIEWER_DIR", viewerRoot.toString())
                  put(
                    "SERVE_COMPONENT_VIEWER_CONFIG",
                    """{"keys":[{"keyId":"publisher","publicKey":"public-test-key"}]}""",
                  )
                },
              ),
        )
        .also(ServeHttpServer::start)
  }

  private fun request(path: String, method: String = "GET", etag: String? = null) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server!!.port}$path")
          .method(method, null)
          .also { if (etag != null) it.header("If-None-Match", etag) }
          .build(),
      )
      .execute()

  private fun bundle(revision: String = "one", title: String = "Quantity & preview"): Path {
    val path = publicationRoot.resolve("quantity/r/$revision")
    Files.createDirectories(path.resolve("catalog/templates"))
    val template = "sample artifact for HTTP tests".toByteArray()
    val sha = sha(template)
    val preview =
      """{
      "schemaVersion":1,"mode":"preview","publicationId":"quantity","revision":"$revision",
      "canonicalUrl":"./","catalog":{"manifestUrl":"./catalog/manifest.json"},
      "template":{"contract":"shop/quantity@1","designId":"quantity-stepper","sha256":"$sha"},
      "data":{"quantity":1},"fallback":{"title":${JsonPrimitive(title)},"description":"Local example only"}
    }"""
    Files.writeString(path.resolve("preview.json"), preview)
    Files.writeString(
      path.resolve("catalog/manifest.json"),
      """{"templates":[{"sha256":"$sha"}]}""",
    )
    Files.write(path.resolve("catalog/templates/$sha.rc"), template)
    Files.write(
      path.resolve("thumbnail.png"),
      Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9n8AAAAASUVORK5CYII=",
        ),
    )
    seal(path)
    return path
  }

  private fun seal(path: Path) {
    val inventory =
      Files.walk(path).use { files ->
        files
          .filter { Files.isRegularFile(it) && it.fileName.toString() != "publication-files.json" }
          .toList()
          .associate {
            path.relativize(it).toString().replace('\\', '/') to
              JsonPrimitive(sha(Files.readAllBytes(it)))
          }
      }
    Files.writeString(path.resolve("publication-files.json"), JsonObject(inventory).toString())
  }

  @Test
  fun `anonymous page unfurl descriptor image and HEAD require no editor credential`() {
    bundle(title = "<script>alert(1)</script>")
    start()
    request("/p/quantity/r/one/").use {
      assertEquals(200, it.code)
      val html = it.body.string()
      assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
      assertFalse(html.contains("<script>alert(1)</script>"))
      assertTrue(html.contains("application/vnd.livery.preview+json"))
      assertTrue(it.header("Link")!!.contains("/p/quantity/r/one/preview.json"))
      assertTrue(html.contains("https://preview.example/p/quantity/r/one/thumbnail.png"))
      assertTrue(html.contains("data-descriptor=\"/p/quantity/r/one/preview.json\""))
      assertTrue(html.contains("data-config=\"{&quot;keys&quot;"))
      assertEquals("public, max-age=0, must-revalidate", it.header("Cache-Control"))
      assertTrue(it.header("Content-Security-Policy")!!.contains("'wasm-unsafe-eval'"))
      assertTrue(it.header("Content-Security-Policy")!!.contains("frame-ancestors 'self'"))
      assertEquals(null, it.header("Set-Cookie"))
    }
    for (file in listOf("preview.json", "thumbnail.png", "catalog/manifest.json")) {
      request("/p/quantity/r/one/$file").use { get ->
        assertEquals(200, get.code)
        if (file == "preview.json") {
          assertEquals("application/vnd.livery.preview+json", get.header("Content-Type"))
        }
        assertTrue(get.body.bytes().isNotEmpty())
        request("/p/quantity/r/one/$file", "HEAD").use { head ->
          assertEquals(200, head.code)
          assertEquals(get.header("Content-Type"), head.header("Content-Type"))
          assertEquals(get.header("ETag"), head.header("ETag"))
          assertEquals(0, head.body.bytes().size)
        }
      }
    }
  }

  @Test
  fun `only embed page permits framing and viewer serves only code assets`() {
    bundle()
    start()
    request("/p/quantity/r/one/embed/").use {
      assertEquals(200, it.code)
      assertFalse(it.header("Content-Security-Policy")!!.contains("frame-ancestors"))
      assertTrue(it.body.string().contains("data-mode=\"embed\""))
    }
    request("/assets/livery-preview/viewer.js").use {
      assertEquals(200, it.code)
      assertTrue(it.header("Content-Type")!!.contains("javascript"))
    }
    request("/assets/livery-preview/credentials.json").use { assertEquals(404, it.code) }
    request("/p/quantity/r/one/embed/preview.json").use { assertEquals(404, it.code) }
    assertFalse(ServePagePolicy.isFramable("/p/quantity/r/one/"))
    assertFalse(ServePagePolicy.isFramable("/p/quantity/r/one/catalog/embed/"))
    assertFalse(ServePagePolicy.hostsWasmApp("/p/quantity/r/one/arbitrary.html"))
  }

  @Test
  fun `revision stays pinned and withdrawal wins over an existing validator`() {
    val first = bundle()
    start()
    val before = request("/p/quantity/r/one/preview.json").use { it.body.string() }
    val etag = request("/p/quantity/r/one/preview.json").use { it.header("ETag")!! }
    bundle("two", "New title")
    request("/p/quantity/r/one/preview.json").use { assertEquals(before, it.body.string()) }
    request("/p/quantity/r/two/preview.json").use { assertNotEquals(before, it.body.string()) }
    request("/p/quantity/r/one/preview.json", etag = etag).use { assertEquals(304, it.code) }
    Files.writeString(first.resolve("withdrawn"), "operator withdrew publication")
    for (suffix in listOf("", "embed/", "preview.json", "thumbnail.png", "catalog/manifest.json")) {
      request("/p/quantity/r/one/$suffix", etag = etag).use {
        assertEquals(410, it.code)
        assertEquals("no-store", it.header("Cache-Control"))
      }
    }
    request("/p/quantity/r/two/").use { assertEquals(200, it.code) }
  }

  @Test
  fun `unlisted and private neighboring content is never published`() {
    val path = bundle()
    start()
    Files.writeString(path.resolve("private-editor-history.json"), "secret")
    Files.writeString(directory.resolve("private-state.json"), "editor token")
    for (file in
      listOf(
        "private-editor-history.json",
        "publication-files.json",
        "catalog/../../../private-state.json",
      )) {
      request("/p/quantity/r/one/$file").use { assertTrue(it.code != 200, file) }
    }
  }

  @Test
  fun `viewer absence retains static fallback and default config does not enable hosting`() {
    bundle()
    start(viewer = false)
    request("/p/quantity/r/one/").use {
      assertEquals(200, it.code)
      val html = it.body.string()
      assertTrue(html.contains("Component preview fallback"))
      assertFalse(html.contains("data-livery-preview"))
    }
    assertEquals(null, ServeComponentPublications.fromEnvironment(emptyMap()))
  }

  @Test
  fun `disabled publication setting leaves public routes unavailable`() {
    bundle()
    start(enabled = false)
    for (path in listOf("/p/quantity/r/one/", "/assets/livery-preview/viewer.js")) {
      request(path).use { assertEquals(404, it.code) }
    }
  }

  private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
