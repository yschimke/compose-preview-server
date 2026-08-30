package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `GET /{system}/parallel/{preview}` — the cross-catalog layer diff over real HTTP (issue #4838),
 * in both spellings.
 *
 * The fixture is the shape the live sheets are in: two catalogs of one design system that publish
 * typography over their own baked frames, agreeing about the size of a label and disagreeing about
 * the family it resolved — the exact divergence a 227dp pixel diff cannot show.
 */
class ServeParallelLayersRoutingTest {

  private val token = "t0ken"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /** A published catalog with one preview and, optionally, a typography layer over it. */
  private fun catalog(
    label: String,
    previewId: String,
    componentId: String,
    family: String? = null,
    compareWith: String? = null,
    parallel: String? = null,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("layers-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText("""{"$previewId":{"componentId":"$componentId"}}""")
    if (family != null) {
      File(dir, ServeAnnotationStore.DIRECTORY).mkdirs()
      File(dir, "${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}")
        .writeText(
          """
          {"schema":"${AnnotationManifest.SCHEMA}","previews":{"$previewId":[
            {"kind":"typography","label":"16.0sp/24.0sp · $family","role":"Continue",
             "bounds":{"x":0,"y":0,"width":8,"height":8},
             "detail":{"fontFamily":"$family","fontSize":"16.0sp","token":"bodyLarge"}}]}}
          """
            .trimIndent()
        )
    }
    return ServeBundleHost(
      dir,
      label = label,
      title = label,
      compareWithSystem = compareWith,
      parallelByComponentId = parallel?.let { mapOf(componentId to it) } ?: emptyMap(),
    )
  }

  private val registry = ServeSessionRegistry(open = { null })

  private fun newServer(
    hereFamily: String? = "Inter",
    thereFamily: String? = "Roboto",
  ): ServeHttpServer {
    registry.register(
      "remote-m3",
      host =
        catalog(
          "remote-m3",
          previewId = "button-child",
          componentId = "Button/Child",
          family = hereFamily,
          compareWith = "wear-m3",
          parallel = "Button/Child",
        ),
      pinned = true,
    )
    registry.register(
      "wear-m3",
      host =
        catalog(
          "wear-m3",
          previewId = "child-button",
          componentId = "Button/Child",
          family = thereFamily,
        ),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "remote-m3",
        isPublic = true,
        catalogSessions = listOf("remote-m3", "wear-m3"),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(server: ServeHttpServer, path: String): Pair<Int, String> {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  @Test
  fun `the page states what each catalog resolved for the same node`() {
    val server = newServer()
    try {
      val (code, html) = get(server, "/remote-m3/parallel/button-child")
      assertEquals(200, code)
      assertTrue(html.contains("Cross-catalog layers"), "the page is served")
      assertTrue(html.contains("Continue"), "the node is named by the text it draws")
      // The finding itself, spelled out: what each side resolved, side by side.
      assertTrue(
        html.contains("Inter") && html.contains("Roboto"),
        "both families are shown: $html",
      )
      assertTrue(html.contains("fontFamily"), "and the property they disagree about is named")
      // The size they agree on is not reported as a difference.
      assertFalse(
        html.contains("<code>fontSize</code>"),
        "an agreement is not a finding: $html",
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the same page as JSON is what a CI gate reads`() {
    val server = newServer()
    try {
      val (code, body) = get(server, "/remote-m3/parallel/button-child?format=json")
      assertEquals(200, code)
      assertTrue(body.contains("\"schema\":\"${ServeParallelLayersPayload.SCHEMA}\""))
      assertTrue(body.contains("\"differing\":1"), "the count a gate reads: $body")
      assertTrue(body.contains("\"status\":\"differs\""))
      assertTrue(body.contains("\"pairedBy\":\"variantCell\""))
      assertTrue(body.contains("\"previewId\":\"child-button\""), "and which render it compared")
    } finally {
      server.stop()
    }
  }

  @Test
  fun `an unknown format is refused rather than quietly served as HTML`() {
    val server = newServer()
    try {
      val (code, body) = get(server, "/remote-m3/parallel/button-child?format=yaml")
      assertEquals(400, code)
      assertTrue(body.contains("unsupported format"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a render with no counterpart says so instead of comparing nothing`() {
    val server = newServer()
    try {
      // The sibling catalog declares no `compareWith` of its own, so its own renders have no pair.
      assertEquals(404, get(server, "/wear-m3/parallel/child-button").first)
      assertEquals(404, get(server, "/remote-m3/parallel/no-such-preview").first)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a pair neither side publishes layers for is a 404, not an empty agreement`() {
    val server = newServer(hereFamily = null, thereFamily = null)
    try {
      val (code, body) = get(server, "/remote-m3/parallel/button-child?format=json")
      assertEquals(404, code, "nothing was compared, so nothing may read as agreement: $body")
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the viewer offers the layer diff for a render that has a counterpart`() {
    val server = newServer()
    try {
      val (code, html) = get(server, "/remote-m3/p/button-child")
      assertEquals(200, code)
      assertTrue(
        html.contains("href=\"/remote-m3/parallel/button-child\""),
        "a page nobody can reach is half a feature: $html",
      )
      // …and a catalog with no sibling keeps the lane exactly as it was.
      assertFalse(get(server, "/wear-m3/p/child-button").second.contains("/parallel/"))
    } finally {
      server.stop()
    }
  }
}
