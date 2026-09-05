package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the catalog MCP tells a caller about a render *besides* the pixels.
 *
 * The gap these close was found by driving the endpoint: two different overrides returned
 * byte-identical PNGs and nothing in the reply said whether either had reached the renderer, and
 * probing eight axes cost twenty sequential calls. Provenance, strict override keys and
 * `render_matrix` are the three answers, pinned here.
 */
class ServeCatalogMcpObservabilityTest {

  /** A 1x1 PNG — enough for `pngDimensions` and a stable hash, and cheap to hand back per cell. */
  private val pixel: ByteArray =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
      )

  private class FakeHost(
    private val generation: RenderOutcome.Generation = RenderOutcome.Generation.DAEMON,
    private val png: ByteArray,
    override val hasSvgExport: Boolean = false,
  ) : ServeHost {
    override val label: String = "fake"
    override val previews: List<ServePreview> = listOf(ServePreview(id = "card", label = "Card"))
    val renders = AtomicInteger()
    val seen = mutableListOf<PreviewOverrides>()

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      renders.incrementAndGet()
      synchronized(seen) { seen.add(overrides) }
      return RenderOutcome.Ok(png, generation)
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

  private fun call(host: FakeHost, arguments: String, tool: String = "render_preview"): JsonObject {
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

  // ---- provenance -----------------------------------------------------------------------------

  @Test
  fun `a hash observation reports how the bytes were produced`() {
    val body =
      call(FakeHost(png = pixel), """{"catalog":"m3","previewId":"card","observe":"hash"}""")
    val observation = Json.parseToJsonElement(body.firstText()).jsonObject

    assertEquals("daemon", observation["generation"]!!.jsonPrimitive.content)
    // No overrides were asked for, so there is nothing to say about them.
    assertNull(observation["requestedOverrides"])
    assertNull(observation["overridesApplied"])
  }

  @Test
  fun `an override-bearing render says the overrides reached the renderer`() {
    val body =
      call(
        FakeHost(png = pixel),
        """{"catalog":"m3","previewId":"card","observe":"hash","overrides":{"uiMode":"dark"}}""",
      )
    val observation = Json.parseToJsonElement(body.firstText()).jsonObject

    assertEquals(true, observation["overridesApplied"]!!.jsonPrimitive.content.toBoolean())
    assertEquals(
      listOf("uiMode"),
      observation["requestedOverrides"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `a baked answer admits the overrides are not reflected in the bytes`() {
    // The case that is invisible without this: the caller asked for an override, got a 200 and a
    // plausible PNG, and those pixels ignore what was asked. `Generation.BAKED` already knew.
    val body =
      call(
        FakeHost(generation = RenderOutcome.Generation.BAKED, png = pixel),
        """{"catalog":"m3","previewId":"card","observe":"hash","overrides":{"uiMode":"dark"}}""",
      )
    val observation = Json.parseToJsonElement(body.firstText()).jsonObject

    assertEquals("baked", observation["generation"]!!.jsonPrimitive.content)
    assertEquals(false, observation["overridesApplied"]!!.jsonPrimitive.content.toBoolean())
    assertTrue(
      observation["overridesIgnoredReason"]!!.jsonPrimitive.content.contains("not reflected")
    )
  }

  @Test
  fun `observe=png keeps its bare image shape when nothing was overridden`() {
    val body =
      call(FakeHost(png = pixel), """{"catalog":"m3","previewId":"card","observe":"png"}""")

    assertEquals(1, body.content().size, "an override-free browse returns pixels and nothing else")
    assertEquals("image", body.content()[0].jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `observe=png carries provenance beside the pixels once overrides are asked for`() {
    val body =
      call(
        FakeHost(png = pixel),
        """{"catalog":"m3","previewId":"card","observe":"png","overrides":{"uiMode":"dark"}}""",
      )

    assertEquals(2, body.content().size)
    assertEquals("image", body.content()[0].jsonObject["type"]!!.jsonPrimitive.content)
    val provenance = Json.parseToJsonElement(body.firstText()).jsonObject
    assertEquals("daemon", provenance["generation"]!!.jsonPrimitive.content)
  }

  // ---- strict override keys -------------------------------------------------------------------

  @Test
  fun `an unknown override key is refused rather than dropped`() {
    // `GET /render` ignores unknown query keys so a URL may carry a cache-buster. An MCP overrides
    // object carries only what the caller typed, so the same silence would hide a typo behind a
    // successful render.
    val host = FakeHost(png = pixel)
    val body =
      call(
        host,
        """{"catalog":"m3","previewId":"card","observe":"hash","overrides":{"widthPixels":600}}""",
      )

    assertTrue(body.isError())
    val message = body.firstText()
    assertTrue(message.contains("unknown override key widthPixels"), message)
    assertTrue(message.contains("widthPx"), "the message lists what is supported: $message")
    assertEquals(0, host.renders.get(), "a bad request must not reach the renderer")
  }

  @Test
  fun `a declared knob and an rc seed are not mistaken for unknown keys`() {
    val host = FakeHost(png = pixel)
    val body =
      call(
        host,
        """{"catalog":"m3","previewId":"card","observe":"hash","overrides":{"knob.label":"Tap","rc.tint":"string:red"}}""",
      )

    assertTrue(!body.isError(), body.firstText())
    assertEquals(1, host.renders.get())
  }

  // ---- render_matrix --------------------------------------------------------------------------

  @Test
  fun `render_matrix renders the cross-product in one call`() {
    val host = FakeHost(png = pixel)
    val body =
      call(
        host,
        """{"catalog":"m3","previewId":"card","axes":{"uiMode":["light","dark"],"fontScale":[1.0,2.0]}}""",
        tool = "render_matrix",
      )
    val result = Json.parseToJsonElement(body.firstText()).jsonObject

    assertEquals(4, result["cellCount"]!!.jsonPrimitive.content.toInt())
    assertEquals(4, host.renders.get(), "one render per cell, in a single MCP call")
    val cells = result["cells"]!!.jsonArray
    assertEquals(
      setOf("light", "dark"),
      cells
        .map { it.jsonObject["overrides"]!!.jsonObject["uiMode"]!!.jsonPrimitive.content }
        .toSet(),
    )
    // Every cell returned the same fake bytes, so this reports 1 — which is exactly the signal a
    // caller wants: these axes did not move the pixels.
    assertEquals(1, result["distinctRenders"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `render_matrix layers each cell over the base overrides`() {
    val host = FakeHost(png = pixel)
    val body =
      call(
        host,
        """{"catalog":"m3","previewId":"card","overrides":{"uiMode":"dark"},"axes":{"fontScale":[1.0,2.0]}}""",
        tool = "render_matrix",
      )
    val cells = Json.parseToJsonElement(body.firstText()).jsonObject["cells"]!!.jsonArray

    assertEquals(2, cells.size)
    assertTrue(
      cells.all {
        it.jsonObject["overrides"]!!.jsonObject["uiMode"]!!.jsonPrimitive.content == "dark"
      },
      "the base override is the floor every cell starts from",
    )
  }

  @Test
  fun `render_matrix refuses an oversized product before rendering anything`() {
    val host = FakeHost(png = pixel)
    val body =
      call(
        host,
        """{"catalog":"m3","previewId":"card","axes":{"fontScale":[1,2,3,4,5],"density":[1,2,3,4,5],"uiMode":["light","dark"]}}""",
        tool = "render_matrix",
      )

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("50 cells"), body.firstText())
    assertEquals(0, host.renders.get(), "the cap must be enforced before the machine time is spent")
  }

  @Test
  fun `render_matrix rejects an empty axis`() {
    val body =
      call(
        FakeHost(png = pixel),
        """{"catalog":"m3","previewId":"card","axes":{"uiMode":[]}}""",
        tool = "render_matrix",
      )

    assertTrue(body.isError())
    assertTrue(body.firstText().contains("at least one value"), body.firstText())
  }

  // ---- svg availability -----------------------------------------------------------------------

  @Test
  fun `list_previews advertises whether the vector lane exists`() {
    val withSvg = call(FakeHost(png = pixel, hasSvgExport = true), """{}""", tool = "list_previews")
    val without =
      call(FakeHost(png = pixel, hasSvgExport = false), """{}""", tool = "list_previews")

    fun flag(body: JsonObject): Boolean =
      Json.parseToJsonElement(body.firstText())
        .jsonObject["catalogs"]!!
        .jsonArray[0]
        .jsonObject["previews"]!!
        .jsonArray[0]
        .jsonObject["svgAvailable"]!!
        .jsonPrimitive
        .content
        .toBoolean()

    assertEquals(true, flag(withSvg))
    assertEquals(false, flag(without))
  }
}
