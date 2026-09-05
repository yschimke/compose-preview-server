package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The catalog MCP tools added beside render/list: the device vocabulary, the semantics diff, and
 * the full-page scroll lanes.
 *
 * Each closes a gap where the capability existed below the MCP layer and only the MCP layer could
 * not reach it — the same shape as the SVG lane in #274.
 */
class ServeCatalogMcpToolsTest {

  private val pixel: ByteArray =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
      )

  /**
   * `{"previewId":…,"annotations":[],"tags":{…}}` — the shape ServeAnnotationsPayload publishes.
   */
  private fun annotations(vararg tags: Pair<String, String>): ByteArray =
    ("""{"previewId":"card","annotations":[],"tags":{""" +
        tags.joinToString(",") { (tag, entry) -> "\"$tag\":$entry" } +
        "}}")
      .toByteArray()

  private fun entry(count: Int = 1, x: Int = 0, y: Int = 0, w: Int = 10, h: Int = 10): String =
    """{"count":$count,"bounds":{"x":$x,"y":$y,"width":$w,"height":$h},"space":"render-pixels"}"""

  private class ToolHost(
    override val hasScrollExport: Boolean = false,
    private val png: ByteArray,
    private val annotationsFor: Map<String, ByteArray> = emptyMap(),
    private val scrollSvg: String? = null,
  ) : ServeHost {
    override val label: String = "tools"
    override val previews: List<ServePreview> =
      listOf(ServePreview(id = "card", label = "Card"), ServePreview(id = "other", label = "Other"))
    val scrollRenders = AtomicInteger()

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(png)

    override fun renderAnnotations(
      previewId: String,
      overrides: PreviewOverrides,
      layers: Set<String>?,
    ): AnnotationsOutcome =
      annotationsFor[previewId]?.let { AnnotationsOutcome.Ok(it) } ?: AnnotationsOutcome.NotFound

    override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      scrollRenders.incrementAndGet()
      return RenderOutcome.Ok(png)
    }

    override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
      scrollRenders.incrementAndGet()
      return scrollSvg?.let { SvgOutcome.Ok(it.toByteArray()) } ?: SvgOutcome.NotFound
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = throw AssertionError("no streaming here")

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  private fun call(host: ToolHost, tool: String, arguments: String = "{}"): JsonObject {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = host)
    val mcp = ServeCatalogMcp(registry, Semaphore(1))
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",
              "params":{"name":"$tool","arguments":$arguments}}"""
        )
        .jsonObject
    return requireNotNull(
      runBlocking {
        mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
      }
        .body
    )
  }

  private fun JsonObject.content() = this["result"]!!.jsonObject["content"]!!.jsonArray

  private fun JsonObject.isError(): Boolean =
    this["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.content == "true"

  private fun JsonObject.firstText(): String =
    content()
      .first { it.jsonObject["type"]!!.jsonPrimitive.content == "text" }
      .jsonObject["text"]!!
      .jsonPrimitive
      .content

  private fun JsonObject.parsed(): JsonObject = Json.parseToJsonElement(firstText()).jsonObject

  // ---- list_devices ---------------------------------------------------------------------------

  @Test
  fun `list_devices publishes the render lane's own catalog`() {
    // The point of the tool: an unrecognised `device=` value is NOT an error on the render path, it
    // falls through to the default frame — indistinguishable, from outside, from a device that
    // renders the same as the default. So the vocabulary has to be askable.
    val body = call(ToolHost(png = pixel), "list_devices")
    val devices = body.parsed()["devices"]!!.jsonArray

    assertTrue(devices.isNotEmpty())
    assertEquals(
      DeviceDimensions.KNOWN_DEVICE_IDS.toList(),
      devices.map { it.jsonObject["id"]!!.jsonPrimitive.content },
      "the tool must not author its own list; it mirrors the catalog the renderer resolves",
    )
    val first = devices[0].jsonObject
    assertTrue(first["widthDp"]!!.jsonPrimitive.content.toInt() > 0)
    assertTrue(first["heightDp"]!!.jsonPrimitive.content.toInt() > 0)
    assertTrue(first["density"]!!.jsonPrimitive.content.toDouble() > 0)
  }

  // ---- diff_semantics -------------------------------------------------------------------------

  private fun diffHost(left: ByteArray, right: ByteArray) =
    ToolHost(png = pixel, annotationsFor = mapOf("card" to left, "other" to right))

  private val bothSides =
    """{"catalog":"m3","previewId":"card","other":{"catalog":"m3","previewId":"other"}}"""

  @Test
  fun `identical tag indexes report identical`() {
    val same = annotations("submit" to entry())
    val body = call(diffHost(same, same), "diff_semantics", bothSides)
    val diff = body.parsed()

    assertEquals(true, diff["identical"]!!.jsonPrimitive.content.toBoolean())
    assertEquals("testTag", diff["identity"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a tag present on only one side is named on that side`() {
    val body =
      call(
        diffHost(annotations("submit" to entry()), annotations("cancel" to entry())),
        "diff_semantics",
        bothSides,
      )
    val diff = body.parsed()

    assertEquals(listOf("submit"), diff["onlyInLeft"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(listOf("cancel"), diff["onlyInRight"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(false, diff["identical"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `a moved tag reports both boxes`() {
    val body =
      call(
        diffHost(annotations("submit" to entry(x = 0)), annotations("submit" to entry(x = 40))),
        "diff_semantics",
        bothSides,
      )
    val changed = body.parsed()["changed"]!!.jsonArray

    assertEquals(1, changed.size)
    val bounds = changed[0].jsonObject["bounds"]!!.jsonObject
    assertEquals(0, bounds["before"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
    assertEquals(40, bounds["after"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `an occupancy change is reported separately from a move`() {
    // A tag carried by two nodes is no longer an identity anything can resolve, which is a
    // different event from the same single node moving.
    val body =
      call(
        diffHost(annotations("row" to entry(count = 1)), annotations("row" to entry(count = 3))),
        "diff_semantics",
        bothSides,
      )
    val changed = body.parsed()["changed"]!!.jsonArray[0].jsonObject

    assertEquals(1, changed["count"]!!.jsonObject["before"]!!.jsonPrimitive.content.toInt())
    assertEquals(3, changed["count"]!!.jsonObject["after"]!!.jsonPrimitive.content.toInt())
    assertTrue(changed["bounds"] == null, "the box did not move, so no bounds delta is reported")
  }

  @Test
  fun `two untagged previews say so rather than claiming a match`() {
    val empty = annotations()
    val body = call(diffHost(empty, empty), "diff_semantics", bothSides)
    val diff = body.parsed()

    assertTrue(diff["note"]!!.jsonPrimitive.content.contains("nothing to compare"))
  }

  @Test
  fun `a preview with no semantics is refused by name`() {
    val body =
      call(
        ToolHost(png = pixel, annotationsFor = mapOf("card" to annotations("a" to entry()))),
        "diff_semantics",
        bothSides,
      )

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("compose/semantics is not available"), body.firstText())
  }

  // ---- scroll lanes ---------------------------------------------------------------------------

  @Test
  fun `a catalog with no scroll producer is refused by name`() {
    val host = ToolHost(png = pixel, hasScrollExport = false)
    val body =
      call(host, "render_preview", """{"catalog":"m3","previewId":"card","observe":"scroll-svg"}""")

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("no full-page scroll export"), body.firstText())
    assertTrue(
      !body.firstText().contains("no such preview"),
      "the preview exists; only the tall re-render is absent",
    )
    assertEquals(0, host.scrollRenders.get(), "an unavailable lane is refused before it is entered")
  }

  @Test
  fun `scroll-svg returns the full-page vector`() {
    val host = ToolHost(png = pixel, hasScrollExport = true, scrollSvg = "<svg id='long'/>")
    val body =
      call(host, "render_preview", """{"catalog":"m3","previewId":"card","observe":"scroll-svg"}""")

    assertEquals("<svg id='long'/>", body.firstText())
    assertEquals(1, host.scrollRenders.get())
  }

  @Test
  fun `scroll-png returns the full-page raster`() {
    val host = ToolHost(png = pixel, hasScrollExport = true)
    val body =
      call(host, "render_preview", """{"catalog":"m3","previewId":"card","observe":"scroll-png"}""")

    assertEquals("image", body.content()[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(1, host.scrollRenders.get())
  }

  @Test
  fun `list_previews advertises scroll availability beside svg`() {
    val body = call(ToolHost(png = pixel, hasScrollExport = true), "list_previews")
    val preview =
      body.parsed()["catalogs"]!!.jsonArray[0].jsonObject["previews"]!!.jsonArray[0].jsonObject

    assertEquals(true, preview["scrollAvailable"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `an unknown observation lists the scroll lanes among the alternatives`() {
    val body =
      call(
        ToolHost(png = pixel),
        "render_preview",
        """{"catalog":"m3","previewId":"card","observe":"pdf"}""",
      )

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("scroll-png"), body.firstText())
    assertTrue(body.firstText().contains("scroll-svg"), body.firstText())
  }
}
