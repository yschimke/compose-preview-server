package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The spec lane's second source, **cell by cell** (issue #4838).
 *
 * The pairing used to end at the component: whichever render the sibling's manifest listed first
 * stood for every cell of the component, so a `disabled` sticker was compared against the sibling's
 * `default` one — silently, and on a surface whose whole subject is whether two systems agree.
 * These are the two ends of that: a cell the sibling *does* draw is paired with, and a cell it does
 * not draw is still shown against the canonical sticker (the floor this keeps) with the
 * substitution said out loud in the lane's own provenance line.
 */
class ServeSpecLaneParallelCellTest {

  private val token = "t0ken"

  /** The one Figma file both catalogs reproduce — the premise the kit-cell pairing rests on. */
  private val KIT_FILE = "B24oss2tTeXAFykyeyusz0"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /**
   * One published catalog: a preview per cell, all of one component.
   *
   * Each cell is `id to state to node`: the render's id, the state it bakes, and the design-kit
   * node it is specified by (null ⇒ no reference for that cell). The primary catalog needs at least
   * one reference for the spec lane to exist at all; the nodes are what let the two sides pair on
   * the kit rather than on their own spelling of an axis.
   */
  private fun catalog(
    label: String,
    componentId: String,
    cells: List<Triple<String, String?, String?>>,
    compareWith: String? = null,
    parallel: String? = null,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("parallel-cell-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    for ((id, _, _) in cells) File(dir, "previews/$id.png").writeBytes(png())
    val variants =
      cells.joinToString(",") { (id, state, _) ->
        val stateJson = state?.let { ""","state":"$it"""" } ?: ""
        """"$id":{"componentId":"$componentId"$stateJson}"""
      }
    File(dir, "previews/variants.json").writeText("{$variants}")
    val referenced = cells.filter { it.third != null }
    if (referenced.isNotEmpty()) {
      File(dir, ServeDesignReferenceStore.DIRECTORY).mkdirs()
      val entries =
        referenced.joinToString(",") { (id, _, node) ->
          File(dir, "${ServeDesignReferenceStore.DIRECTORY}/$id-figma.png").writeBytes(png())
          """{"id":"$id-figma","previewId":"$id","label":"$componentId",
             "source":{"provider":"figma","uri":"figma:$KIT_FILE/$node"},
             "raster":{"path":"references/$id-figma.png"}}"""
        }
      File(dir, "${ServeDesignReferenceStore.DIRECTORY}/${ServeDesignReferenceStore.INDEX_FILE}")
        .writeText("""{"schema":"${DesignReferenceManifest.SCHEMA}","references":[$entries]}""")
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

  /**
   * [siblingCells] is what the wear-side catalog publishes — varied per test so one server shape
   * covers both a sibling that draws this cell and one that does not.
   */
  private fun newServer(
    siblingCells: List<Triple<String, String?, String?>> =
      listOf(
        Triple("child-button__default", "default", null),
        Triple("child-button__disabled", "disabled", null),
      )
  ): ServeHttpServer {
    registry.register(
      "remote-m3",
      host =
        catalog(
          "remote-m3",
          componentId = "Button/Child",
          cells =
            listOf(
              Triple("button-child__default", null, "10:1"),
              Triple("button-child__disabled", "disabled", "10:2"),
              Triple("button-child__left", "left", "10:3"),
            ),
          compareWith = "wear-m3",
          parallel = "Button/Child",
        ),
      pinned = true,
    )
    registry.register(
      "wear-m3",
      // Deliberately in an order that makes the old first-match walk visibly wrong: the sibling's
      // default is listed first and draws `disabled` too, but not `left`.
      host = catalog("wear-m3", componentId = "Button/Child", cells = siblingCells),
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

  private fun page(server: ServeHttpServer, previewId: String): String {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}/remote-m3/p/$previewId")
    client.newCall(request.build()).execute().use { response ->
      assertEquals(200, response.code)
      return response.body.string()
    }
  }

  private fun parallelSrc(html: String): String? =
    Regex("data-cp-spec-source=\"parallel\" data-spec-src=\"([^\"]*)\"")
      .find(html)
      ?.groupValues
      ?.get(1)

  private fun provenance(html: String): String =
    Regex("data-cp-spec-source=\"parallel\"[^>]*data-spec-provenance=\"([^\"]*)\"")
      .find(html)
      ?.groupValues
      ?.get(1)
      .orEmpty()

  @Test
  fun `a state cell is compared against the sibling's same cell`() {
    val server = newServer()
    try {
      val html = page(server, "button-child__disabled")
      assertEquals("/wear-m3/render/child-button__disabled.png", parallelSrc(html))
      assertTrue(
        provenance(html).contains("paired on the state=disabled cell"),
        "the lane says which cell it paired: ${provenance(html)}",
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the default render still pairs with the sibling's default`() {
    val server = newServer()
    try {
      assertEquals(
        "/wear-m3/render/child-button__default.png",
        parallelSrc(page(server, "button-child__default")),
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a cell the sibling does not draw keeps the canonical sticker and states the absence`() {
    val server = newServer()
    try {
      val html = page(server, "button-child__left")
      // The floor: exactly what this lane offered before, for the cells it cannot pair.
      assertEquals("/wear-m3/render/child-button__default.png", parallelSrc(html))
      assertTrue(
        provenance(html)
          .contains("its default render, because that catalog publishes no state=left cell"),
        "an unpaired cell is a stated absence, not a silent substitution: ${provenance(html)}",
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `the shared kit node pairs two catalogs that spell the axis differently`() {
    // What the two live sheets actually look like: `remote-m3` calls the cell `disabled`, the wear
    // sheet calls it `not-enabled`, and both design maps resolved it to node 10:2 of the one kit
    // file they reproduce. Nothing about the two ids or the two axis spellings could pair them.
    val server =
      newServer(
        siblingCells =
          listOf(
            Triple("child-button__default", "default", "10:1"),
            Triple("child-button__not-enabled", "not-enabled", "10:2"),
          )
      )
    try {
      val html = page(server, "button-child__disabled")
      assertEquals("/wear-m3/render/child-button__not-enabled.png", parallelSrc(html))
      assertTrue(
        provenance(html).contains("paired on the design-kit node"),
        "the lane says the pairing came from the kit: ${provenance(html)}",
      )
    } finally {
      server.stop()
    }
  }
}
