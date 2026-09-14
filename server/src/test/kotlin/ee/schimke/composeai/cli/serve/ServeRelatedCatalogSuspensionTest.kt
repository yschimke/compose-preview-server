package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reverse `related` links across the resident → suspended catalog transition. */
class ServeRelatedCatalogSuspensionTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun catalog(
    system: String,
    previewId: String,
    componentId: String,
    title: String,
    role: String? = null,
    related: Map<String, List<ServeRelatedCatalogs.Declared>> = emptyMap(),
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("related-$system").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText("""{"$previewId":{"componentId":"$componentId"}}""")
    return ServeBundleHost(
      dir,
      label = system,
      title = title,
      catalogRole = role,
      relatedByComponentId = related,
    )
  }

  @Test
  fun `a catalog keeps reverse sample links after the samples host suspends`() {
    var now = 0L
    val sessions =
      ServeSessionRegistry(
        open = { null },
        idleTimeoutMillis = 10,
        reaperIntervalMillis = 0,
        clock = { now },
      )
    sessions.register(
      "glimmer-catalog",
      host =
        catalog(
          system = "glimmer-catalog",
          previewId = "button",
          componentId = "Button",
          title = "Compose Glimmer",
        ),
      pinned = true,
    )
    sessions.register(
      "glimmer-samples",
      host =
        catalog(
          system = "glimmer-samples",
          previewId = "button-sample",
          componentId = "Button/ButtonSample",
          title = "Compose Glimmer Samples",
          role = "samples",
          related =
            mapOf(
              "Button/ButtonSample" to
                listOf(ServeRelatedCatalogs.Declared("glimmer-catalog", "Button"))
            ),
        ),
    )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = sessions,
          defaultSessionId = "glimmer-catalog",
          isPublic = true,
          catalogSessions = listOf("glimmer-catalog", "glimmer-samples"),
        )
        .also { it.start() }
    val client = OkHttpClient()
    try {
      fun page(): String {
        val request =
          Request.Builder().url("http://127.0.0.1:${server.port}/glimmer-catalog/p/button").build()
        return client.newCall(request).execute().use { response ->
          assertEquals(200, response.code)
          response.body.string()
        }
      }

      val resident = page()
      assertTrue(resident.contains(">Samples<"), resident)
      assertTrue(resident.contains("href=\"/glimmer-samples/p/button-sample\""), resident)
      assertNotNull(sessions.peekHost("glimmer-samples"))

      now = 100
      assertEquals(1, sessions.suspendIdle(), "the unpinned samples catalog suspends")
      assertNull(sessions.peekHost("glimmer-samples"))

      val suspended = page()
      assertTrue(suspended.contains(">Samples<"), suspended)
      assertTrue(suspended.contains("href=\"/glimmer-samples/p/button-sample\""), suspended)
      assertNull(
        sessions.peekHost("glimmer-samples"),
        "building a back-link must not resume the samples daemon",
      )
    } finally {
      server.stop()
      sessions.close()
    }
  }
}
