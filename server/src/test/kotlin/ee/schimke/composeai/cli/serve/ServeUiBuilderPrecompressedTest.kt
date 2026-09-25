package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The UI-builder bundle's gzip copies, as a browser receives them.
 *
 * Each test serves a real bundle through a real server, because what matters is the response on the
 * wire: that a copy decodes to exactly the file, that the server's own `Compression` plugin does
 * not encode it a second time, and that the two representations never share a validator.
 */
class ServeUiBuilderPrecompressedTest {
  @Test
  fun `the wasm arrives gzipped once its copy is ready, and decodes to the same bytes`() {
    withBundle { base, dir ->
      val original = File(dir, "app.wasm").readBytes()
      val response = awaitGzip(base, "app.wasm")
      response.use {
        assertEquals("application/wasm", it.header("Content-Type"))
        assertTrue(it.header("ETag")!!.endsWith("-gzip\""), "ETag: ${it.header("ETag")}")
        assertTrue(it.headers("Vary").any { vary -> "Accept-Encoding" in vary })
        val body = it.body.bytes()
        assertTrue(body.size < original.size / 4, "gzip copy is ${body.size} of ${original.size}")
        assertContentEquals(original, GZIPInputStream(body.inputStream()).readBytes())
      }
    }
  }

  @Test
  fun `a javascript copy is encoded exactly once, although the plugin also compresses javascript`() {
    // `Compression` gzips `text/javascript` on its own. It looks for an existing encoding on the
    // response body only, so a copy that declared its encoding anywhere else came out gzipped twice
    // and decoded to gzip rather than to the module.
    withBundle { base, dir ->
      val original = File(dir, "app.mjs").readBytes()
      awaitGzip(base, "app.mjs").use {
        assertEquals(listOf("gzip"), it.headers("Content-Encoding"))
        assertContentEquals(original, GZIPInputStream(it.body.byteStream()).readBytes())
      }
    }
  }

  @Test
  fun `a client that does not take gzip gets the file itself, under its own validator`() {
    withBundle { base, dir ->
      val gzipEtag = awaitGzip(base, "app.wasm").use { it.header("ETag")!! }
      get(base, "app.wasm", acceptEncoding = "identity").use {
        assertNull(it.header("Content-Encoding"))
        assertContentEquals(File(dir, "app.wasm").readBytes(), it.body.bytes())
        assertNotEquals(gzipEtag, it.header("ETag"))
      }
    }
  }

  @Test
  fun `a validator for the plain bytes is not a 304 for the gzip ones`() {
    withBundle { base, _ ->
      val plainEtag = get(base, "app.wasm", acceptEncoding = "identity").use { it.header("ETag")!! }
      awaitGzip(base, "app.wasm").close()
      get(base, "app.wasm", acceptEncoding = "gzip", ifNoneMatch = plainEtag).use {
        assertEquals(200, it.code)
        assertEquals("gzip", it.header("Content-Encoding"))
      }
    }
  }

  @Test
  fun `the copies live outside the bundle, so filling them does not move its version`() {
    withBundle { base, dir ->
      val before = dir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
      awaitGzip(base, "app.wasm").close()
      assertEquals(before, dir.walkTopDown().filter { it.isFile }.map { it.name }.toSet())
    }
  }

  @Test
  fun `stopping the server stops its compression thread`() {
    // One parked thread per start and stop is a leak a test suite multiplies by hundreds.
    withBundle { base, _ -> awaitGzip(base, "app.wasm").close() }
    val deadline = System.currentTimeMillis() + 5_000
    while (gzipThreads().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(emptyList(), gzipThreads())
  }

  private fun gzipThreads(): List<String> =
    Thread.getAllStackTraces()
      .keys
      .filter { it.isAlive && it.name == "ui-builder-gzip" }
      .map { it.name }

  @Test
  fun `accept-encoding is read with its qualities`() {
    val accepts = UiBuilderPrecompressedAssets::acceptsGzip
    assertTrue(accepts("gzip, deflate, br, zstd"))
    assertTrue(accepts("br;q=1.0, gzip;q=0.8"))
    assertTrue(accepts("*"))
    assertFalse(accepts(null))
    assertFalse(accepts("identity"))
    assertFalse(accepts("gzip;q=0"))
    assertFalse(accepts("*;q=0.5, gzip;q=0"))
  }

  private companion object {
    const val SHELL = """<html><script type="module" src="app.mjs"></script></html>"""
  }

  private val client = OkHttpClient()

  /**
   * A server over a bundle with a compressible Wasm and module, and the versioned prefix the shell
   * publishes for it.
   */
  private fun withBundle(block: (base: String, dir: File) -> Unit) {
    val dir = Files.createTempDirectory("ui-builder-bundle").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText(SHELL)
    File(dir, "app.mjs")
      .writeText("export const names = [\n" + "  'layer',\n".repeat(4000) + "];\n")
    // Repetitive the way a real module's names and tables are, so the copy clears the 10% bar.
    File(dir, "app.wasm")
      .writeBytes(ByteArray(256 * 1024) { i -> (if (i % 64 < 8) i / 64 else 0).toByte() })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "private-token",
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "none",
          uiBuilderDir = dir,
        )
        .also { it.start() }
    try {
      val root = "http://127.0.0.1:${server.port}"
      val shell =
        client.newCall(Request.Builder().url("$root/ui-builder/").build()).execute().use {
          it.body.string()
        }
      val prefix = Regex("""src="(/ui-builder/v/[^/"]+/)""").find(shell)!!.groupValues[1]
      block(root + prefix, dir)
    } finally {
      server.stop()
    }
  }

  /**
   * The asset, asked for with gzip until the background copy is what answers. Recognised by its
   * validator rather than its encoding: the plugin gzips a module on the fly before the copy
   * exists, and that response must not pass for the copy.
   */
  private fun awaitGzip(base: String, name: String): Response {
    val deadline = System.currentTimeMillis() + 20_000
    while (true) {
      val response = get(base, name, acceptEncoding = "gzip")
      if (response.header("ETag").orEmpty().endsWith("-gzip\"")) return response
      response.close()
      check(System.currentTimeMillis() < deadline) { "$name was never served gzipped" }
      Thread.sleep(50)
    }
  }

  /**
   * An explicit `Accept-Encoding`, which is what stops OkHttp decoding the body behind the test's
   * back: it only decompresses transparently when it added the header itself.
   */
  private fun get(
    base: String,
    name: String,
    acceptEncoding: String,
    ifNoneMatch: String? = null,
  ): Response =
    client
      .newCall(
        Request.Builder()
          .url(base + name)
          .header("Accept-Encoding", acceptEncoding)
          .apply { ifNoneMatch?.let { header("If-None-Match", it) } }
          .build()
      )
      .execute()
}
