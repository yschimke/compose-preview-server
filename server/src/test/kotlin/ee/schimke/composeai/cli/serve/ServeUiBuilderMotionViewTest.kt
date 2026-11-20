package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * `ui_builder_view` over time for a watch face (yschimke/watchface-tools#39): each frame is the
 * design with `environment.fixedTime` set to the frame's instant, drawn by the editor's export.
 *
 * The export here stands in for the builder's renderer and follows the one contract this server
 * relies on: a node driven by the seconds clock draws differently at a different
 * `environment.fixedTime`, and nothing else moves. The renderer's own time evaluation is
 * compose-ui-builder's and is tested there.
 */
class ServeUiBuilderMotionViewTest {
  private val actor = AuthenticatedUiBuilderActor("github:owner")
  private val documents =
    mapOf(
      "sweep" to face("sweep", secondDriven = true),
      "still" to face("still", secondDriven = false),
      "pinned" to face("pinned", secondDriven = true, fixedTime = "2026-11-20T10:08:32.000Z"),
      "phone" to face("phone", secondDriven = true),
    )

  private val watchFaceCatalog = catalog("wff-watchface", "watchface")
  private val phoneCatalog = catalog("m3", "android")

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.GetSnapshot ->
            UiBuilderServiceResponse.Snapshot(
              ServiceSnapshotV1(
                designId = request.designId,
                state =
                  DesignStateV1(lastSequence = 0, document = documents.getValue(request.designId)),
                catalog = if (request.designId == "phone") phoneCatalog else watchFaceCatalog,
                retainedFromSequence = 0,
              ),
            )
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  /** Whether the stand-in export refuses as a renderer at capacity does. */
  private var busy = false

  /** Every document the export was asked to draw. */
  private val exported = mutableListOf<DesignDocumentV1>()
  private val validator =
    object : UiBuilderDraftValidator {
      override suspend fun validate(
        actor: AuthenticatedUiBuilderActor,
        document: DesignDocumentV1,
        operations: List<ee.schimke.composeai.uibuilder.protocol.DesignMutationV1>?,
      ) = emptyList<UiBuilderValidationProblemV1>()

      override suspend fun exportPngs(
        actor: AuthenticatedUiBuilderActor,
        documents: List<DesignDocumentV1>,
      ): List<UiBuilderScratchPng> = documents.map {
        exported += it
        if (busy) UiBuilderScratchPng(null, "export failed: UI-builder renderer is busy")
        else UiBuilderScratchPng(render(it), null)
      }
    }

  private fun mcp(limiter: ServeRateLimiter? = null) =
    ServeUiBuilderMcp(
      service,
      validator = validator,
      motionLimiter = limiter,
      motionClock = { START + 437 },
    )

  private fun view(arguments: String, mcp: ServeUiBuilderMcp = mcp()): JsonObject {
    val text = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.VIEW,
        Json.parseToJsonElement(arguments).jsonObject,
        actor,
        callId = "1",
      )
    }
    return Json.parseToJsonElement(text).jsonObject
  }

  private fun refused(arguments: String, mcp: ServeUiBuilderMcp = mcp()): String =
    assertFailsWith<McpRequestException> { view(arguments, mcp) }.message.orEmpty()

  private fun JsonObject.motion() = this["motion"]!!.jsonObject

  private fun JsonObject.frames() = motion()["frames"]!!.jsonArray.map { it.jsonObject }

  @Test
  fun `a second-driven transform draws a different frame at each instant`() {
    val reply = view("""{"designId":"sweep","frames":4,"durationMs":1000}""")

    val frames = reply.frames()
    assertEquals(listOf(0L, 250L, 500L, 750L), frames.map { it["offsetMs"]!!.jsonPrimitive.long })
    // Starts at the current whole second, so `[SECOND_MILLISECOND]` reads 0 in the first frame.
    assertEquals(START, frames.first()["epochMillis"]!!.jsonPrimitive.long)
    assertEquals(4, reply.motion()["distinctFrames"]!!.jsonPrimitive.int)
    assertTrue(frames.all { it["changed"]!!.jsonPrimitive.boolean }, frames.toString())
    assertEquals(
      listOf(
        "2026-11-20T10:08:30.000Z",
        "2026-11-20T10:08:30.250Z",
        "2026-11-20T10:08:30.500Z",
        "2026-11-20T10:08:30.750Z",
      ),
      exported.map { it.environment.fixedTime },
    )
    assertEquals("filmstrip", reply.motion()["format"]!!.jsonPrimitive.content)
    assertEquals("export", reply["renderer"]!!.jsonPrimitive.content)
    assertEquals(documents.getValue("sweep").revision, reply["revision"]!!.jsonPrimitive.long)

    // One picture: four labelled cells in a row, each where the reply says it is.
    val picture = picture(reply)
    assertEquals(reply["image"]!!.jsonObject["widthPx"]!!.jsonPrimitive.int, picture.width)
    val colors = frames.map { frame ->
      picture.getRGB(
        frame["x"]!!.jsonPrimitive.int + frame["width"]!!.jsonPrimitive.int / 2,
        frame["y"]!!.jsonPrimitive.int + frame["height"]!!.jsonPrimitive.int / 2,
      ) and 0xFFFFFF
    }
    assertEquals(4, colors.distinct().size, colors.toString())
  }

  @Test
  fun `explicit times are drawn as given`() {
    val times = listOf(START, START + 15_000, START + 30_000)
    val reply = view("""{"designId":"sweep","times":${times}}""")
    assertEquals(times, reply.frames().map { it["epochMillis"]!!.jsonPrimitive.long })
    assertEquals(3, reply.motion()["distinctFrames"]!!.jsonPrimitive.int)
  }

  @Test
  fun `a design that pins fixedTime draws the same frame every time`() {
    val reply = view("""{"designId":"pinned","frames":4,"durationMs":1000}""")

    assertEquals(1, reply.motion()["distinctFrames"]!!.jsonPrimitive.int)
    assertEquals(
      "2026-11-20T10:08:32.000Z",
      reply.motion()["pinnedFixedTime"]!!.jsonPrimitive.content,
    )
    assertEquals(List(4) { "2026-11-20T10:08:32.000Z" }, exported.map { it.environment.fixedTime })
    val hashes = reply.frames().map { it["sha256"]!!.jsonPrimitive.content }
    assertEquals(1, hashes.distinct().size)
    assertEquals(listOf(true, false, false, false), reply.frames().map { it.changed() })
    val notes = reply["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertTrue(notes.any { "pins `environment.fixedTime`" in it }, notes.toString())
  }

  @Test
  fun `a face with nothing time-driven says that nothing moved`() {
    val reply = view("""{"designId":"still","frames":3}""")
    assertEquals(1, reply.motion()["distinctFrames"]!!.jsonPrimitive.int)
    val notes = reply["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertTrue(notes.any { "nothing in this design moves" in it }, notes.toString())
  }

  @Test
  fun `ambient applies the design's ambient variants to their parents`() {
    val both = view("""{"designId":"sweep","ambient":true}""")
    // Alone: the interactive and ambient modes at the same instant, side by side.
    assertEquals(listOf(false, true), both.frames().map { it["ambient"]!!.jsonPrimitive.boolean })
    assertEquals(1, both.motion()["ambientVariants"]!!.jsonPrimitive.int)
    assertNull(exported[0].nodes.getValue("hand").properties["alpha"])
    assertEquals(IntegerValueV1(0), exported[1].nodes.getValue("hand").properties["alpha"])
    assertEquals(2, both.motion()["distinctFrames"]!!.jsonPrimitive.int)

    exported.clear()
    val frames = view("""{"designId":"sweep","ambient":true,"frames":2}""").frames()
    assertTrue(frames.all { it["ambient"]!!.jsonPrimitive.boolean })
    assertTrue(exported.all { it.nodes.getValue("hand").properties["alpha"] == IntegerValueV1(0) })
  }

  @Test
  fun `ambient false is the ordinary view, not a motion view`() {
    // The plain view asks the service for its PNG export, which this stand-in service refuses; a
    // motion view would have gone to the draft validator instead.
    for (designId in listOf("sweep", "phone")) {
      val message = refused("""{"designId":"$designId","ambient":false}""")
      assertTrue("PNG export" in message, message)
    }
    assertEquals(emptyList(), exported)
  }

  @Test
  fun `an ambient override keeps a fractional value on a whole-number property`() {
    val base = face("frac", secondDriven = false)
    val hand = base.nodes.getValue("hand")
    val variant = base.nodes.getValue("hand-ambient")
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("hand" to hand.copy(properties = mapOf("pivotX" to IntegerValueV1(0)))) +
            ("hand-ambient" to
              variant.copy(
                properties =
                  variant.properties +
                    ("target" to StringValueV1("pivotX")) +
                    ("value" to StringValueV1("0.5")),
              )),
      )
    assertEquals(
      ee.schimke.composeai.uibuilder.protocol.DecimalValueV1(0.5),
      UiBuilderMotionView.ambient(document).nodes.getValue("hand").properties["pivotX"],
    )
  }

  @Test
  fun `animated is one animated PNG with a frame per instant`() {
    val reply = view("""{"designId":"sweep","frames":3,"format":"animated"}""")
    val png = Base64.getDecoder().decode(reply["imageBase64"]!!.jsonPrimitive.content)
    val chunks = UiBuilderMotionView.Apng.chunks(png)
    val control = chunks.single { it.first == "acTL" }.second
    assertEquals(3, java.nio.ByteBuffer.wrap(control).int)
    assertEquals(3, chunks.count { it.first == "fcTL" })
    assertEquals(2, chunks.count { it.first == "fdAT" })
    // Still a PNG: a viewer that does not animate shows the first frame.
    assertEquals(reply["image"]!!.jsonObject["widthPx"]!!.jsonPrimitive.int, picture(reply).width)
  }

  @Test
  fun `the caps are enforced`() {
    assertTrue("`frames`" in refused("""{"designId":"sweep","frames":13}"""))
    assertTrue("`frames`" in refused("""{"designId":"sweep","frames":1}"""))
    assertTrue("`times`" in refused("""{"designId":"sweep","times":${List(13) { START }}}"""))
    assertTrue("`times`" in refused("""{"designId":"sweep","times":[$START]}"""))
    assertTrue("not both" in refused("""{"designId":"sweep","times":[1,2],"frames":2}"""))
    assertTrue(
      "`durationMs`" in refused("""{"designId":"sweep","durationMs":${25L * 60 * 60 * 1000}}"""),
    )
    assertTrue("`format`" in refused("""{"designId":"sweep","frames":2,"format":"gif"}"""))
    assertTrue("renderer" in refused("""{"designId":"sweep","frames":2,"renderer":"native"}"""))
    assertTrue("wff-watchface" in refused("""{"designId":"phone","frames":2}"""))
    assertTrue("`format`" in refused("""{"designId":"sweep","format":"animated"}"""))
    assertTrue("boolean" in refused("""{"designId":"sweep","ambient":"yes"}"""))
    assertEquals(emptyList(), exported, "a refused call renders nothing")

    // A frame is never drawn larger than the cell cap, whatever the render's size.
    val reply = view("""{"designId":"sweep","frames":12}""")
    val motion = reply.motion()
    assertTrue(motion["cellWidthPx"]!!.jsonPrimitive.int <= UiBuilderMotionView.MAX_CELL_PX)
    assertTrue(motion["cellHeightPx"]!!.jsonPrimitive.int <= UiBuilderMotionView.MAX_CELL_PX)
    assertEquals(454, reply["frame"]!!.jsonObject["renderWidthPx"]!!.jsonPrimitive.int)
    assertEquals(12, reply.frames().size)
  }

  @Test
  fun `motion views are charged to the account`() {
    var now = 0L
    val mcp =
      mcp(ServeRateLimiter(permitsPerWindow = 1, windowSeconds = 60, maxConcurrent = 1) { now })
    view("""{"designId":"sweep","frames":2}""", mcp)
    val message = refused("""{"designId":"sweep","frames":2}""", mcp)
    assertTrue(message.startsWith(ServeUiBuilderMcp.MOTION_RATE_LIMITED), message)
    assertEquals(2, exported.size, "the throttled call rendered nothing")
    // Another account has its own budget, and the window refills.
    runBlocking {
      mcp.call(
        ServeUiBuilderMcp.VIEW,
        Json.parseToJsonElement("""{"designId":"sweep","frames":2}""").jsonObject,
        AuthenticatedUiBuilderActor("github:other"),
        callId = "2",
      )
    }
    now += 60_000
    view("""{"designId":"sweep","frames":2}""", mcp)
    assertEquals(6, exported.size)
  }

  @Test
  fun `a renderer at capacity is an overload to retry, and the budget is given back`() {
    busy = true
    val mcp =
      mcp(ServeRateLimiter(permitsPerWindow = 5, windowSeconds = 60, maxConcurrent = 1) { 0L })
    assertFailsWith<ExportOverloadedException> { view("""{"designId":"sweep","frames":2}""", mcp) }
    busy = false
    // The in-flight permit was released, so the next call is admitted at once.
    view("""{"designId":"sweep","frames":2}""", mcp)
  }

  private fun JsonObject.changed() = this["changed"]!!.jsonPrimitive.boolean

  private fun picture(reply: JsonObject): BufferedImage =
    ImageIO.read(
      Base64.getDecoder().decode(reply["imageBase64"]!!.jsonPrimitive.content).inputStream(),
    )

  /**
   * The stand-in renderer: a face whose `second-hand` is driven by the seconds clock is filled with
   * a colour of `fixedTime`'s second and millisecond, and an ambient `alpha` of 0 hides it.
   */
  private fun render(document: DesignDocumentV1): ByteArray {
    val hand = document.nodes["hand"]
    val driven = document.nodes.values.any { it.componentId == "wff/transform" }
    val hidden = hand?.properties?.get("alpha") == IntegerValueV1(0)
    val millis =
      document.environment.fixedTime?.let { Instant.parse(it).toEpochMilli() % 60_000 } ?: 0
    val fill =
      when {
        hidden -> 0x000000
        driven -> (0x400000 + millis * 97).toInt() and 0xFFFFFF
        else -> 0x3366CC
      }
    val size = (document.environment.widthDp * document.environment.density).toInt()
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = java.awt.Color(fill)
    graphics.fillRect(0, 0, size, size)
    graphics.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun catalog(systemId: String, platform: String) =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(systemId, "source", systemId, "candidate", "candidate").build(),
        emptyList(),
      )
      .also {
        it.statusSemantics =
          JsonObject(
            mapOf("platform" to kotlinx.serialization.json.JsonPrimitive(platform)),
          )
      }
      .build()

  private fun face(id: String, secondDriven: Boolean, fixedTime: String? = null): DesignDocumentV1 {
    val transform =
      DesignNodeV1(
        id = "hand-angle",
        componentId = "wff/transform",
        properties =
          mapOf("target" to StringValueV1("angle"), "rotateWith" to StringValueV1("second-sweep")),
      )
    val variant =
      DesignNodeV1(
        id = "hand-ambient",
        componentId = UiBuilderMotionView.VARIANT_COMPONENT,
        properties =
          mapOf(
            "mode" to StringValueV1("AMBIENT"),
            "target" to StringValueV1("alpha"),
            "value" to StringValueV1("0"),
          ),
      )
    val hand =
      DesignNodeV1(
        id = "hand",
        componentId = "wff/group",
        slots =
          buildMap {
            if (secondDriven) put("content", listOf(transform.id))
            put("variants", listOf(variant.id))
          },
      )
    val scene =
      DesignNodeV1(
        id = "scene",
        componentId = "wff/scene",
        slots = mapOf("content" to listOf("hand")),
      )
    return DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = id,
      revision = 7,
      catalogPin = CatalogReferenceV1("wff-watchface", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 227,
          heightDp = 227,
          density = 2.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          fixedTime = fixedTime,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("scene"),
      nodes =
        listOfNotNull(scene, hand, variant, transform.takeIf { secondDriven }).associateBy {
          it.id
        },
    )
  }

  private companion object {
    /** 2026-11-20T10:08:30Z, a whole second. */
    val START = Instant.parse("2026-11-20T10:08:30Z").toEpochMilli()
  }
}
