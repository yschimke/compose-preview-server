package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The hosted `/mcp`'s text-and-image fallbacks for chat surfaces (#1254).
 *
 * A Slack-hosted agent has no MCP App, viewer or editor: whatever it shows a person is text and a
 * link. So on a host with a public origin every result that carries pixels also carries a signed
 * https link to them, said once as a `resource_link` and once as a line of text, and no text the
 * model reads carries the pixels themselves. The audit table these tests pin is in
 * `docs/design/CHAT_SURFACES.md`.
 */
class ServeCatalogMcpChatFallbackTest {

  private val origin = "https://preview.example"

  private val png: ByteArray = solidPng(40, 80, Color(0x33, 0x66, 0x99))

  private class Host(
    private val png: ByteArray,
    override val hasScrollExport: Boolean = true,
  ) : ServeHost {
    override val label: String = "chat"
    override val previews: List<ServePreview> = listOf(ServePreview(id = "card", label = "Card"))

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(png)

    override fun renderScrollPng(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(png)

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

  private fun mcp(origin: String? = this.origin): ServeCatalogMcp {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = Host(png))
    return ServeCatalogMcp(registry, Semaphore(1), publicOrigin = { origin })
  }

  private fun call(mcp: ServeCatalogMcp, tool: String, arguments: String): JsonObject {
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",
              "params":{"name":"$tool","arguments":$arguments}}"""
        )
        .jsonObject
    val body =
      requireNotNull(
        runBlocking {
          mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
        }
          .body
      )
    return body["result"]!!.jsonObject
  }

  private fun fetch(mcp: ServeCatalogMcp, url: String): ByteArray? {
    val query =
      URI(url).rawQuery.split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
      }
    return runBlocking {
      mcp.signedImagePng(
        query.getValue("uri"),
        query.getValue("exp").toLong(),
        query.getValue("sig"),
      )
    }
  }

  @Test
  fun `a png render says its https link in text as well as in a resource link`() {
    val result =
      call(
        mcp(),
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"png"}""",
      )

    ChatFallbackAssertions.assertChatReadable(result, origin, png)
  }

  @Test
  fun `an override-bearing png render keeps its provenance first and the link last`() {
    val result =
      call(
        mcp(),
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"png","overrides":{"uiMode":"dark"}}""",
      )

    ChatFallbackAssertions.assertChatReadable(result, origin, png)
    val texts =
      result["content"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["type"]!!.jsonPrimitive.content == "text" }
    assertTrue(texts.first()["text"]!!.jsonPrimitive.content.startsWith("{"), "provenance first")
    assertTrue(texts.last()["text"]!!.jsonPrimitive.content.startsWith("Image: https://"))
  }

  @Test
  fun `a full-page capture is kept behind a link, since a resource uri replays the viewport`() {
    val mcp = mcp()
    val result =
      call(
        mcp,
        "catalog_render_preview",
        """{"catalog":"m3","previewId":"card","observe":"scroll-png"}""",
      )

    ChatFallbackAssertions.assertChatReadable(result, origin, png)
    val url = result["structuredContent"]!!.jsonObject["imageUrl"]!!.jsonPrimitive.content
    assertTrue(png.contentEquals(fetch(mcp, url)), "the link fetches exactly the capture")
  }

  @Test
  fun `a png matrix moves its pixels out of the text and offers one numbered contact sheet`() {
    val mcp = mcp()
    val result =
      call(
        mcp,
        "catalog_render_matrix",
        """{"catalog":"m3","previewId":"card","observe":"png","axes":{"uiMode":["light","dark"],"fontScale":[1.0,2.0]}}""",
      )

    ChatFallbackAssertions.assertChatReadable(result, origin, png)
    val body =
      Json.parseToJsonElement(
          result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        .jsonObject
    val cells = body["cells"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(1, 2, 3, 4), cells.map { it["index"]!!.jsonPrimitive.content.toInt() })
    for (cell in cells) {
      assertEquals(null, cell["png"], "no base64 in a hosted cell")
      val url = cell["imageUrl"]!!.jsonPrimitive.content
      assertTrue(url.startsWith("$origin${ServeCatalogMcp.IMAGE_URL_PATH}?"), url)
      assertTrue(png.contentEquals(fetch(mcp, url)), "each cell's link re-renders that cell")
    }
    // Each cell's link carries its own overrides, so the four links are four different renders.
    assertEquals(4, cells.map { it["imageUrl"]!!.jsonPrimitive.content }.toSet().size)

    val sheetUrl = body["contactSheet"]!!.jsonObject["url"]!!.jsonPrimitive.content
    val sheet = assertNotNull(fetch(mcp, sheetUrl), "the contact sheet is fetchable")
    val image = ImageIO.read(ByteArrayInputStream(sheet))
    assertTrue(image.width > 2 * 40 && image.height > 2 * 80, "a 2x2 grid of the cells")

    // The viewer still gets the bytes, where the model does not read them.
    val metaPngs = result["_meta"]!!.jsonObject["composePreview/cellPngs"]!!.jsonArray
    assertEquals(4, metaPngs.size)
    assertEquals(Base64.getEncoder().encodeToString(png), metaPngs[0].jsonPrimitive.content)
  }

  @Test
  fun `without a public origin a png matrix keeps its inline cells, as before`() {
    val result =
      call(
        mcp(origin = null),
        "catalog_render_matrix",
        """{"catalog":"m3","previewId":"card","observe":"png","axes":{"uiMode":["light","dark"]}}""",
      )
    val body =
      Json.parseToJsonElement(
          result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        .jsonObject
    val cells = body["cells"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cells.all { it["png"] != null && it["imageUrl"] == null })
    assertEquals(null, body["contactSheet"])
  }

  @Test
  fun `a UI-builder native render and a png export each carry a kept https link`() {
    val mcp = mcp()
    val encoded = Base64.getEncoder().encodeToString(png)
    val native =
      """{"designId":"demo","previewToken":"pg_secret","previewUrl":"/pg/pg_secret","imageBase64":"$encoded","compileError":null}"""
    val nativeResult = mcp.uiBuilderToolResult(ServeUiBuilderMcp.RENDER_NATIVE, native)
    ChatFallbackAssertions.assertChatReadable(nativeResult, origin, png)

    val exported =
      """{"callId":"ui_builder_export_document","response":{"artifact":{"format":"png","mediaType":"image/png","encoding":"base64","content":"$encoded","contentDigest":"abc","diagnostics":[]}}}"""
    val exportResult = mcp.uiBuilderToolResult(ServeUiBuilderMcp.EXPORT_DOCUMENT, exported)
    ChatFallbackAssertions.assertChatReadable(exportResult, origin, png)
    val url = ChatFallbackAssertions.httpsImageLinks(exportResult, origin).single()
    assertTrue(png.contentEquals(fetch(mcp, url)))
  }

  @Test
  fun `ui_builder_view links its picture rather than inlining it`() {
    val encoded = Base64.getEncoder().encodeToString(png)
    val result =
      mcp().uiBuilderViewResult("""{"designId":"demo","imageBase64":"$encoded"}""", inline = false)

    ChatFallbackAssertions.assertChatReadable(result, origin, png)
  }

  private companion object {
    fun solidPng(width: Int, height: Int, color: Color): ByteArray {
      val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val g = image.createGraphics()
      g.color = color
      g.fillRect(0, 0, width, height)
      g.dispose()
      return ByteArrayOutputStream().use {
        ImageIO.write(image, "png", it)
        it.toByteArray()
      }
    }
  }
}
