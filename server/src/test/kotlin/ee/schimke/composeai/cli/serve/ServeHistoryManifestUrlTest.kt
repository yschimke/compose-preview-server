package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The viewer advertises a delivery branch's `history.json` (`data-history-url`) only when the
 * catalog's load found one. Provenance alone is not enough: a branch that publishes no manifest
 * made every viewer load fetch a guaranteed 404 from `raw.githubusercontent.com` (#1592).
 */
class ServeHistoryManifestUrlTest {

  private val previewId = "com.example.Red"

  private val provenance =
    ServeWeb.CatalogProvenance(
      repo = "yschimke/design",
      branch = "design/m3",
      commit = "4".repeat(40),
    )

  private fun png(): ByteArray {
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  private val manifest =
    PreviewHistoryManifest.Manifest(
      generatedFrom = "3".repeat(40),
      previews =
        mapOf(
          previewId to
            PreviewHistoryManifest.PreviewTimeline(
              path = "renders/samples:demo/Red.png",
              versions = emptyList(),
              observations = 0,
              unstable = false,
              flapCount = 0,
            ),
        ),
    )

  private fun bundle(indexed: PreviewHistoryManifest.Manifest?): ServeBundleHost {
    val dir = Files.createTempDirectory("history-url").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png())
    return ServeBundleHost(
      dir,
      label = "demo",
      title = "Demo",
      provenance = provenance,
      indexedPreviewHistory = indexed,
    )
  }

  private val registry = ServeSessionRegistry(open = { null })

  private val server: ServeHttpServer by lazy {
    registry.register("with-history", host = bundle(manifest), pinned = true)
    registry.register("without-history", host = bundle(null), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "with-history",
        isPublic = true,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun viewer(system: String): String {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}/$system/p/$previewId").build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code, "viewer for $system")
      return r.body.string()
    }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `a delivery branch that publishes history json is advertised to the viewer`() {
    val body = viewer("with-history")
    assertTrue(
      body.contains(
        "data-history-url=\"https://raw.githubusercontent.com/yschimke/design/design/m3/history.json\"",
      ),
      "expected the manifest URL the load already read",
    )
    assertTrue(body.contains("data-history-repo=\"yschimke/design\""))
  }

  @Test
  fun `a delivery branch without history json advertises no manifest to fetch`() {
    val body = viewer("without-history")
    assertFalse(body.contains("data-history-url="), "no manifest was published, so none to fetch")
    assertFalse(body.contains("data-history-repo="), "the pair is both or neither")
  }
}
