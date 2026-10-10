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
import kotlin.test.assertFalse
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
    private val guidelines:
      Map<String, ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1> =
      emptyMap(),
    override val previews: List<ServePreview> =
      listOf(
        ServePreview(id = "card", label = "Card"),
        ServePreview(id = "other", label = "Other"),
      ),
  ) : ServeHost {
    override val label: String = "tools"
    val scrollRenders = AtomicInteger()

    override fun guidelineResultFor(previewId: String) = guidelines[previewId]

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

  private fun guidelineRecord() =
    ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1.Builder(
        revision = 0,
        model = "deepseek/deepseek-v4.1-flash",
        rulesVersion = 3,
        asked = listOf("wear.layout.responsive-width"),
        verdicts =
          listOf(
            ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1.Builder(
                "wear.layout.responsive-width",
                ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1.FAIL,
              )
              .apply { reason = "Fixed width." }
              .build()
          ),
      )
      .build()

  @Test
  fun `a preview with a published guidelines result advertises and serves it`() {
    val host = ToolHost(png = pixel, guidelines = mapOf("card" to guidelineRecord()))

    val listed =
      Json.parseToJsonElement(
          call(host, "catalog_list_data_products", """{"catalog":"m3"}""").firstText()
        )
        .jsonArray
        .associate {
          it.jsonObject["previewId"]!!.jsonPrimitive.content to
            it.jsonObject["kinds"]!!.jsonArray.map { kind -> kind.jsonPrimitive.content }
        }
    assertTrue(GUIDELINES_RESULT_KIND in listed.getValue("card"), listed.toString())
    assertFalse(GUIDELINES_RESULT_KIND in listed.getValue("other"), listed.toString())

    val found =
      call(
          host,
          "catalog_get_preview_data",
          """{"catalog":"m3","previewId":"card","kind":"guidelines/result"}""",
        )
        .parsed()
    assertEquals("true", found["found"]!!.jsonPrimitive.content, found.toString())
    assertEquals(
      "Fixed width.",
      found["record"]!!
        .jsonObject["verdicts"]!!
        .jsonArray
        .single()
        .jsonObject["reason"]!!
        .jsonPrimitive
        .content,
    )

    val missing =
      call(
          host,
          "catalog_get_preview_data",
          """{"catalog":"m3","previewId":"other","kind":"guidelines/result"}""",
        )
        .parsed()
    assertEquals("false", missing["found"]!!.jsonPrimitive.content, missing.toString())
    assertTrue("no guidelines result" in missing["message"]!!.jsonPrimitive.content)
  }

  @Test
  fun `data-product array text is wrapped for its object output schema`() {
    val body = call(ToolHost(png = pixel), "catalog_list_data_products", """{"catalog":"m3"}""")

    assertEquals(false, body.isError(), body.toString())
    val legacyArray = Json.parseToJsonElement(body.firstText()).jsonArray
    assertEquals(
      legacyArray,
      body["result"]!!.jsonObject["structuredContent"]!!.jsonObject["dataProducts"],
    )
  }

  @Test
  fun `preview stories preserves every structured observation`() {
    val body =
      call(
        ToolHost(png = pixel),
        "preview-stories",
        """{"storyIds":["m3::card","m3::other"],"observe":"hash"}""",
      )
    // Each observation's text block, beside the resource link that follows it.
    val legacy =
      body
        .content()
        .map { it.jsonObject }
        .filter { it["type"]!!.jsonPrimitive.content == "text" }
        .map { Json.parseToJsonElement(it["text"]!!.jsonPrimitive.content).jsonObject }
    val observations =
      body["result"]!!
        .jsonObject["structuredContent"]!!
        .jsonObject["observations"]!!
        .jsonArray
        .map { it.jsonObject }

    assertEquals(2, observations.size)
    assertEquals(legacy, observations)
    assertEquals(
      setOf("compose-preview://catalog/m3/card", "compose-preview://catalog/m3/other"),
      observations.map { it["uri"]!!.jsonPrimitive.content }.toSet(),
    )
  }

  // ---- catalog_render_preview image URL (#1160)
  // -------------------------------------------------------------------------

  private fun renderPng(mcp: ServeCatalogMcp): JsonObject {
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",
              "params":{"name":"catalog_render_preview",
                        "arguments":{"catalog":"m3","previewId":"card","observe":"png"}}}"""
        )
        .jsonObject
    return requireNotNull(
        runBlocking {
          mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
        }
          .body
      )
      .get("result")!!
      .jsonObject
  }

  private fun imageUrlOf(result: JsonObject): String? =
    result["content"]!!
      .jsonArray
      .map { it.jsonObject }
      .firstOrNull { it["uri"]?.jsonPrimitive?.content?.startsWith("https://") == true }
      ?.get("uri")
      ?.jsonPrimitive
      ?.content

  private fun mcpWith(now: () -> Long, origin: String?): ServeCatalogMcp {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = ToolHost(png = pixel))
    return ServeCatalogMcp(registry, Semaphore(1), nowMillis = now, publicOrigin = { origin })
  }

  @Test
  fun `a png render carries a signed https url that fetches exactly that image`() {
    var now = 1_000_000L
    val mcp = mcpWith({ now }, "https://preview.example/")
    val result = renderPng(mcp)

    val url = requireNotNull(imageUrlOf(result))
    assertTrue(url.startsWith("https://preview.example${ServeCatalogMcp.IMAGE_URL_PATH}?"), url)
    assertEquals(
      url,
      result["structuredContent"]!!.jsonObject["imageUrl"]!!.jsonPrimitive.content,
    )
    // The inline image stays for hosts that render it.
    assertTrue(
      result["content"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "image" }
    )

    val query =
      java.net.URI(url).rawQuery.split('&').associate {
        it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
      }
    val uri = query.getValue("uri")
    val exp = query.getValue("exp").toLong()
    val sig = query.getValue("sig")

    assertTrue(pixel.contentEquals(runBlocking { mcp.signedImagePng(uri, exp, sig) }))
    // A signature is bound to its resource: another preview's uri does not verify.
    assertEquals(null, runBlocking { mcp.signedImagePng(uri.replace("card", "other"), exp, sig) })
    assertEquals(null, runBlocking { mcp.signedImagePng(uri, exp, sig.reversed()) })
    // ...and to its expiry.
    assertEquals(null, runBlocking { mcp.signedImagePng(uri, exp + 1, sig) })
    now += (ServeCatalogMcp.SIGNED_RESOURCE_TTL_SECONDS + 1) * 1000
    assertEquals(null, runBlocking { mcp.signedImagePng(uri, exp, sig) })
  }

  @Test
  fun `no public origin means no image url`() {
    val result = renderPng(mcpWith({ 1_000_000L }, origin = null))

    assertEquals(null, imageUrlOf(result))
    // An image-only reply still carries the (empty) object its output schema promises.
    assertEquals(JsonObject(emptyMap()), result["structuredContent"])
  }

  // ---- catalog_list_devices
  // ---------------------------------------------------------------------------

  @Test
  fun `catalog_list_devices publishes the render lane's own catalog`() {
    // The point of the tool: an unrecognised `device=` value is NOT an error on the render path, it
    // falls through to the default frame — indistinguishable, from outside, from a device that
    // renders the same as the default. So the vocabulary has to be askable.
    val body = call(ToolHost(png = pixel), "catalog_list_devices")
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

  // ---- catalog_diff_semantics
  // -------------------------------------------------------------------------

  private fun diffHost(left: ByteArray, right: ByteArray) =
    ToolHost(png = pixel, annotationsFor = mapOf("card" to left, "other" to right))

  private val bothSides =
    """{"catalog":"m3","previewId":"card","other":{"catalog":"m3","previewId":"other"}}"""

  @Test
  fun `identical tag indexes report identical`() {
    val same = annotations("submit" to entry())
    val body = call(diffHost(same, same), "catalog_diff_semantics", bothSides)
    val diff = body.parsed()

    assertEquals(true, diff["identical"]!!.jsonPrimitive.content.toBoolean())
    assertEquals("testTag", diff["identity"]!!.jsonPrimitive.content)
    assertEquals(
      "compose-preview://catalog/m3/card",
      diff["left"]!!.jsonObject["uri"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "compose-preview://catalog/m3/other",
      diff["right"]!!.jsonObject["uri"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `diff resource URIs preserve each side's overrides`() {
    val same = annotations("submit" to entry())
    val body =
      call(
        diffHost(same, same),
        "catalog_diff_semantics",
        """{"catalog":"m3","previewId":"card","overrides":{"uiMode":"dark"},"other":{"catalog":"m3","previewId":"other"},"otherOverrides":{"fontScale":1.3}}""",
      )
    val diff = body.parsed()

    fun overrides(side: String): String {
      val uri = diff[side]!!.jsonObject["uri"]!!.jsonPrimitive.content
      return Base64.getUrlDecoder().decode(uri.substringAfter("?overrides=")).decodeToString()
    }

    assertEquals("""{"uiMode":"dark"}""", overrides("left"))
    assertEquals("""{"fontScale":1.3}""", overrides("right"))
  }

  @Test
  fun `a tag present on only one side is named on that side`() {
    val body =
      call(
        diffHost(annotations("submit" to entry()), annotations("cancel" to entry())),
        "catalog_diff_semantics",
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
        "catalog_diff_semantics",
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
        "catalog_diff_semantics",
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
    val body = call(diffHost(empty, empty), "catalog_diff_semantics", bothSides)
    val diff = body.parsed()

    assertTrue(diff["note"]!!.jsonPrimitive.content.contains("nothing to compare"))
  }

  @Test
  fun `a preview with no semantics is refused by name`() {
    val body =
      call(
        ToolHost(png = pixel, annotationsFor = mapOf("card" to annotations("a" to entry()))),
        "catalog_diff_semantics",
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
      call(
        host,
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"scroll-svg"}""",
      )

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
      call(
        host,
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"scroll-svg"}""",
      )

    assertEquals("<svg id='long'/>", body.firstText())
    assertEquals(1, host.scrollRenders.get())
  }

  @Test
  fun `scroll-png returns the full-page raster`() {
    val host = ToolHost(png = pixel, hasScrollExport = true)
    val body =
      call(
        host,
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"scroll-png"}""",
      )

    assertEquals("image", body.content()[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(1, host.scrollRenders.get())
  }

  @Test
  fun `catalog_list_previews advertises scroll availability beside svg`() {
    val body = call(ToolHost(png = pixel, hasScrollExport = true), "catalog_list_previews")
    val preview =
      body.parsed()["catalogs"]!!.jsonArray[0].jsonObject["previews"]!!.jsonArray[0].jsonObject

    assertEquals(true, preview["scrollAvailable"]!!.jsonPrimitive.content.toBoolean())
  }

  /** How `a2ui render` finds the preview that takes a document without a call per preview. */
  @Test
  fun `catalog_list_previews reports declared knobs, and omits the field when there are none`() {
    val document =
      ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration(
        key = "document",
        type = ee.schimke.composeai.data.overrides.PreviewOverrideType.STRING,
        label = "Document",
        default = ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.StringValue("{}"),
      )
    val host =
      ToolHost(
        png = pixel,
        previews =
          listOf(
            ServePreview(id = "card", label = "Card"),
            ServePreview(id = "doc", label = "Doc", overrides = listOf(document)),
          ),
      )
    val previews =
      call(host, "catalog_list_previews")
        .parsed()["catalogs"]!!
        .jsonArray[0]
        .jsonObject["previews"]!!
        .jsonArray
        .map { it.jsonObject }

    assertEquals(null, previews[0]["knobs"])
    val knob = previews[1]["knobs"]!!.jsonArray.single().jsonObject
    assertEquals("document", knob["key"]!!.jsonPrimitive.content)
    assertEquals("string", knob["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an unknown observation lists the scroll lanes among the alternatives`() {
    val body =
      call(
        ToolHost(png = pixel),
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"pdf"}""",
      )

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("scroll-png"), body.firstText())
    assertTrue(body.firstText().contains("scroll-svg"), body.firstText())
  }
}
