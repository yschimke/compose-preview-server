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
 * A design page's **third source** — the `compareWith` sibling's own render of every cell the sheet
 * defines — as it arrives over real HTTP.
 *
 * The pairing itself is [ServeParallelPairing]'s and is pinned next door; what only a served page
 * can answer is whether the sheet actually offers it, and whether the URLs it offers resolve for
 * the caller holding this page. Both of the failure modes below are ones a unit test over
 * [ServeWeb.designPage] cannot see, because both are about the box rather than about the markup:
 *
 * - a **token-gated** server (every `serve` that is not `--public`) gates `/render/` like every
 *   other route, so the sibling's raster needs the same credential the sheet's own renders carry;
 * - a **top-level site** ([ServeSites]) answers a neighbour's `/{system}/…` with its own 404 by
 *   design, so on that hostname the pairing resolves and the render still does not — which is why
 *   the source is withheld there rather than offered and broken.
 */
class ServeDesignPageParallelTest {

  private val siteHost = "m3.example.test"
  private val token = "t0ken"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /**
   * A published catalog with one baked preview, and — when [pages] is set — one specimen sheet
   * naming that preview's node. The sheet's own node id is what pairs it with a render; everything
   * else about the pairing is the ordinary `compareWith` + `parallel` walk.
   */
  private fun catalog(
    label: String,
    previewId: String,
    componentId: String,
    compareWith: String? = null,
    parallel: String? = null,
    pages: Boolean = false,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("page-parallel-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText("""{"$previewId":{"componentId":"$componentId"}}""")
    if (pages) {
      File(dir, ServeDesignPageStore.DIRECTORY).mkdirs()
      File(dir, "${ServeDesignPageStore.DIRECTORY}/shape.svg")
        .writeText(
          """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">""" +
            """<g data-node-id="1:1"><rect width="10" height="10"/></g></svg>"""
        )
      File(dir, "${ServeDesignPageStore.DIRECTORY}/${ServeDesignPageStore.INDEX_FILE}")
        .writeText(
          """
          {"version":2,"source":"figma","fileKey":"ocdacdEsnHipMJD3egzxKb","pages":[
            {"id":"shape","name":"Shape","nodeId":"1:0",
             "frame":{"width":100.0,"height":100.0},
             "image":{"uri":"shape.svg","format":"svg"},
             "nodes":[{"nodeId":"1:1","name":"Shape=Circle","depth":3,"link":"manifest",
                       "confidence":"high","code":"ui/Shapes.kt#Circle","previewId":"$previewId"}]}]}
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
    isPublic: Boolean,
    sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
    compareWith: String? = "wear-m3",
  ): ServeHttpServer {
    registry.register(
      "compose-m3",
      host =
        catalog(
          "compose-m3",
          previewId = "button-filled",
          componentId = "Button/Filled",
          compareWith = compareWith,
          parallel = compareWith?.let { "Button/Filled" },
          pages = true,
        ),
      pinned = true,
    )
    registry.register(
      "wear-m3",
      host = catalog("wear-m3", previewId = "chip-filled", componentId = "Button/Filled"),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "compose-m3",
        isPublic = isPublic,
        catalogSessions = listOf("compose-m3", "wear-m3"),
        sites = sites,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(server: ServeHttpServer, path: String, host: String? = null): Pair<Int, String> {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$path")
    if (host != null) builder.header("Host", host)
    client.newCall(builder.build()).execute().use { response ->
      return response.code to response.body.string()
    }
  }

  /** The `src` of the sibling's render of node `1:1`, or null when the sheet offers none. */
  private fun parallelSrc(html: String): String? =
    Regex("""<img class="cp-page-parallel"[^>]*data-cp-node="1:1" src="([^"]*)"""")
      .find(html)
      ?.groupValues
      ?.get(1)

  @Test
  fun `the sheet offers the sibling's renders of the cells it defines`() {
    val server = newServer(isPublic = true)
    try {
      val (code, html) = get(server, "/compose-m3/pages/shape")
      assertEquals(200, code)
      assertEquals(
        "/wear-m3/render/chip-filled.png",
        parallelSrc(html),
        "the sibling's own render route on this very server",
      )
      // The images are INERT until a reader names that source: they come off another catalog's
      // daemon, and a sheet that warmed them would charge every reader for a comparison almost none
      // of them open. So they ship inside the template rather than in the stage.
      assertTrue(
        html.contains("<template data-cp-page-parallel-source>"),
        "the sibling's renders are parked in an inert template",
      )
      assertTrue(
        html.contains("value=\"parallel\" data-cp-page-lane"),
        "…and the sheet offers a control that names them",
      )
      assertTrue(
        html.contains("value=\"parallel\" data-cp-page-baseline"),
        "…and one that scores against them",
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the sibling's raster carries the same credential as the rest of the page`() {
    // `/render/` is token-gated like every other route on a server that is not `--public`, so a
    // bare
    // path would meet `rejectBadToken`'s own 404 — a lane whose only outcome is a broken image.
    val server = newServer(isPublic = false)
    try {
      val (code, html) = get(server, "/compose-m3/pages/shape?token=$token")
      assertEquals(200, code)
      val src = parallelSrc(html)
      assertEquals("/wear-m3/render/chip-filled.png?token=$token", src)
      // Fetched, not merely well-formed: the whole premise of offering the sibling here rather than
      // baking a thumbnail is that this server answers for it.
      assertEquals(200, get(server, src!!).first)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a top-level site offers no sibling at all`() {
    // One catalog per hostname: the site interceptor answers `/{system}/…` for any system but this
    // one with the site's own 404, so the sibling's render is unreachable from this page however
    // well the pairing resolves. A control whose only possible outcome is a broken image is worse
    // than no control.
    val sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3"))
    val server = newServer(isPublic = true, sites = sites)
    try {
      val (code, html) = get(server, "/pages/shape", host = siteHost)
      assertEquals(200, code)
      assertEquals(null, parallelSrc(html))
      assertFalse(html.contains("data-cp-page-parallel-source"))
      assertFalse(html.contains("value=\"parallel\" data-cp-page-lane"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a catalog with no pairing keeps exactly the sheet it had`() {
    // Which is most of them. The third source is additive: no `compareWith`, no extra control, and
    // no button that acts on nothing.
    val server = newServer(isPublic = true, compareWith = null)
    try {
      val (code, html) = get(server, "/compose-m3/pages/shape")
      assertEquals(200, code)
      assertEquals(null, parallelSrc(html))
      assertFalse(html.contains("data-cp-page-parallel-source"))
      assertFalse(html.contains("value=\"parallel\" data-cp-page-baseline"))
      // …and the two axes it always had are still both there, which is what makes this a rethink of
      // the controls rather than a feature only the paired catalogs can see.
      assertTrue(html.contains("value=\"code\" data-cp-page-lane"))
      assertTrue(html.contains("value=\"design\" data-cp-page-baseline"))
    } finally {
      server.stop()
    }
  }
}
