package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
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
 * The catalog MCP's vector lane (issue #273).
 *
 * `render_preview` served raster only, while the HTTP `/render/<id>.svg` route on the same host
 * answered the same preview with a `compose/figma-svg` export. These pin the MCP lane to the
 * capability [ServeHost] already models, including the two things that are easy to regress: that
 * asking for SVG does not also pay for a PNG, and that a catalog with no vectors says so rather
 * than claiming the preview does not exist.
 */
class ServeCatalogMcpSvgTest {

  private val svgBytes =
    """<svg xmlns="http://www.w3.org/2000/svg" width="8" height="4"></svg>""".toByteArray()

  private class VectorHost(
    override val hasSvgExport: Boolean = true,
    private val svg: ByteArray = ByteArray(0),
    private val svgOutcome: SvgOutcome? = null,
  ) : ServeHost {
    override val label: String = "vectors"
    override val previews: List<ServePreview> = listOf(ServePreview(id = "card", label = "Card"))
    val renders = AtomicInteger()
    val svgRenders = AtomicInteger()

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renders.incrementAndGet()
      return RenderOutcome.Failed("the vector lane must not commission a raster render")
    }

    override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
      svgRenders.incrementAndGet()
      return svgOutcome ?: SvgOutcome.Ok(svg)
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = throw AssertionError("the SVG lane must never stream")

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  private fun call(host: VectorHost, observe: String): JsonObject {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = host)
    val mcp = ServeCatalogMcp(registry, Semaphore(1))
    val request =
      Json.parseToJsonElement(
          """
          {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"render_preview",
           "arguments":{"catalog":"m3","previewId":"card","observe":"$observe"}}}
          """
        )
        .jsonObject
    val reply = runBlocking {
      mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
    }
    return requireNotNull(reply.body)
  }

  private fun JsonObject.firstContent(): JsonObject =
    this["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject

  private fun JsonObject.isError(): Boolean =
    this["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.content == "true"

  private fun JsonObject.text(): String = firstContent()["text"]!!.jsonPrimitive.content

  @Test
  fun `observe=svg returns the vector export as SVG source`() {
    val host = VectorHost(svg = svgBytes)
    val body = call(host, "svg")

    val content = body.firstContent()
    assertEquals("text", content["type"]!!.jsonPrimitive.content)
    assertEquals(svgBytes.decodeToString(), content["text"]!!.jsonPrimitive.content)
    assertEquals(1, host.svgRenders.get())
  }

  @Test
  fun `the vector lane never commissions a raster render`() {
    // renderContent used to render the PNG unconditionally before branching on `observe`, so an
    // SVG request paid for pixels it discarded. VectorHost.render fails loudly rather than
    // returning bytes, so a regression here surfaces as this assertion, not as a slow test.
    val host = VectorHost(svg = svgBytes)
    call(host, "svg")

    assertEquals(0, host.renders.get(), "asking for SVG must not also render a PNG")
  }

  @Test
  fun `a raster-only catalog says so rather than denying the preview`() {
    val host = VectorHost(hasSvgExport = false)
    val body = call(host, "svg")

    assertTrue(body.isError(), "a catalog with no vectors is an error, not empty content")
    val message = body.text()
    assertTrue(message.contains("no compose/figma-svg export"), message)
    assertTrue(message.contains("raster only"), message)
    assertTrue(
      !message.contains("no such preview"),
      "the preview exists; only the vector lane is absent: $message",
    )
    assertEquals(0, host.svgRenders.get(), "an unavailable lane is refused before it is entered")
  }

  @Test
  fun `a failed export surfaces the host's reason`() {
    val host = VectorHost(svgOutcome = SvgOutcome.Failed("daemon busy"))
    val body = call(host, "svg")

    assertTrue(body.isError())
    assertTrue(body.text().contains("daemon busy"), body.text())
  }

  @Test
  fun `an unknown observation names svg among the alternatives`() {
    val body = call(VectorHost(), "pdf")

    assertTrue(body.isError())
    assertEquals("'observe' must be one of png, svg, semantics, or hash", body.text())
  }

  @Test
  fun `the advertised render_preview schema offers svg`() {
    val registry = ServeSessionRegistry(open = { null })
    val mcp = ServeCatalogMcp(registry, Semaphore(1))
    val request =
      Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        .jsonObject
    val body =
      runBlocking { mcp.handle(request) { ServeMachineAuthorization.Decision.Missing } }.body!!

    val render =
      body["result"]!!
        .jsonObject["tools"]!!
        .jsonArray
        .map { it.jsonObject }
        .single { it["name"]!!.jsonPrimitive.content == "render_preview" }
    val enum =
      render["inputSchema"]!!
        .jsonObject["properties"]!!
        .jsonObject["observe"]!!
        .jsonObject["enum"]!!
        .jsonArray
        .map { it.jsonPrimitive.content }
    assertEquals(listOf("png", "svg", "semantics", "hash"), enum)
  }
}
