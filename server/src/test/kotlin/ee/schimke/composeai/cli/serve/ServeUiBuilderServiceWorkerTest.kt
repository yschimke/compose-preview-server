package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The UI-builder bundle's service worker (`ui-builder-sw.js`), served with the headers a worker
 * needs: revalidated every time, allowed the editor's `/ui-builder/` scope, and absent — a plain
 * 404 — from a pinned bundle that does not ship one yet.
 */
class ServeUiBuilderServiceWorkerTest {

  private fun bundle(withWorker: Boolean): File {
    val dir = Files.createTempDirectory("ui-builder-sw").toFile().also { it.deleteOnExit() }
    File(dir, "index.html")
      .writeText("""<html><script type="module" src="app.mjs"></script></html>""")
    File(dir, "app.mjs").writeText("export const a = 1")
    if (withWorker)
      File(dir, "ui-builder-sw.js").writeText("self.addEventListener('fetch', () => {})")
    return dir
  }

  private fun <T> withServer(dir: File, block: (port: Int) -> T): T {
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
      return block(server.port)
    } finally {
      server.stop()
    }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(port: Int, path: String): okhttp3.Response =
    client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()

  @Test
  fun `the worker is served uncached, with the editor's scope, and without a credential`() {
    withServer(bundle(withWorker = true)) { port ->
      val version =
        get(port, "/ui-builder/").use { response ->
          Regex("""src="/ui-builder/v/([^/"]+)/""").find(response.body.string())!!.groupValues[1]
        }
      get(port, "/ui-builder/ui-builder-sw.js").use { response ->
        assertEquals(200, response.code)
        assertTrue(response.header("Content-Type")!!.startsWith("text/javascript"))
        assertEquals("no-cache", response.header("Cache-Control"))
        assertEquals("/ui-builder/", response.header("Service-Worker-Allowed"))
        assertTrue(response.body.string().contains("addEventListener"))
      }
      // Never under the immutable versioned prefix: the worker's URL is its identity across
      // releases, and a second copy there would register a second worker.
      get(port, "/ui-builder/v/$version/ui-builder-sw.js").use { assertEquals(404, it.code) }
      // Every other bundle asset keeps its own contract: the versioned prefix stays immutable.
      get(port, "/ui-builder/v/$version/app.mjs").use { response ->
        assertTrue(response.header("Cache-Control")!!.contains("immutable"))
        assertEquals(null, response.header("Service-Worker-Allowed"))
      }
    }
  }

  @Test
  fun `a bundle without a worker answers 404`() {
    withServer(bundle(withWorker = false)) { port ->
      get(port, "/ui-builder/ui-builder-sw.js").use { assertEquals(404, it.code) }
    }
  }
}
