package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the UI-builder bundle's version prefix is derived from.
 *
 * The prefix is the whole point of serving that bundle immutably, and it is only worth anything if
 * it tracks the bytes rather than the filesystem. These start a server per bundle and read the
 * version off the redirect the unversioned entry answers with, because that redirect is the only
 * place the current prefix is published.
 */
class ServeUiBuilderBundleVersionTest {
  @Test
  fun `the same bytes give the same version, whatever their timestamps`() {
    // The bundle ships inside the deploy image, and rebuilding that image rewrites timestamps
    // whether or not the builder changed. An mtime-derived version would retire every viewer's
    // cached bundle on a redeploy that shipped identical bytes.
    val first =
      bundle(index = "<html>builder</html>", module = "export const a = 1", mtime = 1_000_000_000L)
    val second =
      bundle(index = "<html>builder</html>", module = "export const a = 1", mtime = 2_000_000_000L)

    assertEquals(versionOf(first), versionOf(second))
  }

  @Test
  fun `a changed byte gives a different version`() {
    val before = bundle(index = "<html>builder</html>", module = "export const a = 1")
    val after = bundle(index = "<html>builder</html>", module = "export const a = 2")

    assertNotEquals(versionOf(before), versionOf(after))
  }

  @Test
  fun `a renamed file gives a different version, though every byte is still present`() {
    // Path is mixed in alongside the content precisely so this is not a collision: the same bytes
    // reachable under a different name are a different bundle to anything that imports them.
    val before = bundle(index = "<html>builder</html>", module = "export const a = 1")
    val after =
      bundle(index = "<html>builder</html>", module = "export const a = 1").also {
        File(it, "app.mjs").renameTo(File(it, "renamed.mjs"))
      }

    assertNotEquals(versionOf(before), versionOf(after))
  }

  private fun bundle(index: String, module: String, mtime: Long? = null): File {
    val dir = Files.createTempDirectory("ui-builder-bundle").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText(index)
    File(dir, "app.mjs").writeText(module)
    mtime?.let { dir.listFiles()?.forEach { file -> file.setLastModified(it) } }
    return dir
  }

  /** The version prefix this server publishes, read off the entry redirect. */
  private fun versionOf(dir: File): String {
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
      val client = OkHttpClient.Builder().followRedirects(false).build()
      val request = Request.Builder().url("http://127.0.0.1:${server.port}/ui-builder/").build()
      return client.newCall(request).execute().use { response ->
        assertEquals(302, response.code)
        val location = response.header("Location")!!
        assertTrue(location.startsWith("/ui-builder/v/"), location)
        location.removePrefix("/ui-builder/v/").trimEnd('/')
      }
    } finally {
      server.stop()
    }
  }
}
