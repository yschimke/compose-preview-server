package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
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
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ui_builder_render_design_matrix` for a Wear widget (yschimke/skills#156): the widget is drawn in
 * the hosts it ships into — Samsung round, Pixel Watch squircle, rectangular — at each host's
 * frame, not on a round watch screen where it reads as a band across the face.
 */
class ServeUiBuilderWidgetMatrixTest {
  private val actor = AuthenticatedUiBuilderActor("github:owner")
  private val documents =
    mapOf(
      "widget" to widget("widget", "remote-m3/widget-container-small"),
      "adaptive" to widget("adaptive", AdaptiveWearWidget.COMPONENT_ID),
    )

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
                catalog = catalog,
                retainedFromSequence = 0,
              )
            )
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  /** Each document the export was asked to draw, standing in for the editor's PNG export. */
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
        UiBuilderScratchPng(png(it.environment.widthDp, it.environment.heightDp), null)
      }
    }

  /** What the native lane drew: the host shape and the root it was handed. */
  private val drawn = mutableListOf<Pair<WearWidgetHostShape, String>>()
  private val lane = UiBuilderNativePreviewLane { document, shape ->
    val root = document.nodes.getValue(document.roots.single()).componentId
    drawn += shape to root
    // A widget is drawn at its container's frame in that host, whatever the environment says.
    val spec = WearWidgetScaffoldSize.entries.single { it.componentId == root }.hostSpec(shape)
    UiBuilderNativePreviewOutcome.Rendered(
      PlaygroundRunResponse(
        previewId = "generated",
        previewToken = "token",
        image = Base64.getEncoder().encodeToString(png(spec.frameWidthDp, spec.frameHeightDp)),
      ),
      taggedNodeIds = emptyList(),
    )
  }

  private fun matrix(
    arguments: String,
    native: UiBuilderNativePreviewLane? = lane,
    mayExport: Boolean = true,
  ): JsonObject {
    val mcp = ServeUiBuilderMcp(service, nativePreview = native, validator = validator)
    val text = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.RENDER_DESIGN_MATRIX,
        Json.parseToJsonElement(arguments).jsonObject,
        actor,
        callId = "1",
        mayExport = { mayExport },
      )
    }
    return Json.parseToJsonElement(text).jsonObject
  }

  private fun JsonObject.cells() = this["cells"]!!.jsonArray.map { it.jsonObject }

  private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.content

  private fun JsonObject.size() =
    this["widthDp"]!!.jsonPrimitive.int to this["heightDp"]!!.jsonPrimitive.int

  @Test
  fun `a widget's default matrix is its container in each widget host, drawn natively there`() {
    val cells = matrix("""{"designId":"widget"}""").cells()

    assertEquals(
      listOf("widget_small_round", "widget_small_squircle", "widget_small_rectangular"),
      cells.map { it.text("device") },
    )
    assertEquals(listOf(230 to 76, 216 to 76, 224 to 84), cells.map { it.size() })
    assertEquals(
      listOf("round", "squircle", "rectangular"),
      cells.map { it.text("widgetHostShape") },
    )
    assertTrue(cells.all { it.text("formFactor") == "widget" }, cells.toString())
    assertTrue(cells.all { it.text("renderer") == "native" }, cells.toString())
    assertTrue(cells.all { it["rendered"]!!.jsonPrimitive.booleanOrNull == true }, cells.toString())
    assertEquals(
      listOf(
        WearWidgetHostShape.Round,
        WearWidgetHostShape.Squircle,
        WearWidgetHostShape.Rectangular,
      ),
      drawn.map { it.first },
    )
    assertEquals(emptyList(), exported, "a natively drawn cell is not exported as well")
  }

  @Test
  fun `the widget form factor follows the container, and other form factors still draw`() {
    matrix("""{"designId":"adaptive","formFactor":"widget"}""")
    // The adaptive container is both sizes, each drawn as the fixed design it resolves to.
    assertEquals(
      List(3) { "remote-m3/widget-container-small" } +
        List(3) { "remote-m3/widget-container-large" },
      drawn.map { it.second },
    )

    val wear = matrix("""{"designId":"widget","formFactor":"wear"}""").cells()
    assertEquals(listOf("wear_small_round", "wear_large_round"), wear.map { it.text("device") })
  }

  @Test
  fun `without the export grant or a native lane a widget host cell is the export at its frame`() {
    for ((native, mayExport) in listOf(lane to false, null to true)) {
      exported.clear()
      val cells =
        matrix(
            """{"designId":"widget","devices":["widget_large_squircle","phone"]}""",
            native = native,
            mayExport = mayExport,
          )
          .cells()
      assertEquals(listOf(216 to 124, 412 to 915), cells.map { it.size() })
      assertTrue(cells.all { it["rendered"]!!.jsonPrimitive.booleanOrNull == true })
      assertTrue(cells.all { it["renderer"] == null }, cells.toString())
      assertEquals("squircle", cells[0].text("widgetHostShape"))
      assertNull(cells[1]["widgetHostShape"])
      assertEquals(
        listOf(216 to 124, 412 to 915),
        exported.map { it.environment.widthDp to it.environment.heightDp },
      )
    }
    assertEquals(emptyList(), drawn, "a caller without the export grant never compiles")
  }

  @Test
  fun `a widget preset is sized from the export's own host table`() {
    for (size in WearWidgetScaffoldSize.entries) {
      for (shape in WearWidgetHostShape.entries) {
        val preset =
          assertNotNull(UiBuilderDesignMatrix.preset("widget_${size.name.lowercase()}_${shape.id}"))
        val spec = size.hostSpec(shape)
        assertEquals(spec.frameWidthDp to spec.frameHeightDp, preset.widthDp to preset.heightDp)
        assertEquals(shape, preset.widgetHostShape)
        assertEquals(UiBuilderDesignMatrix.FORM_WIDGET, preset.formFactor)
        assertFalse(preset.round)
      }
    }
    assertEquals(
      listOf("widget_large_round", "widget_large_squircle", "widget_large_rectangular"),
      UiBuilderDesignMatrix.widgetDefaults(widget("w", "remote-m3/widget-container-large")),
    )
    assertNull(UiBuilderDesignMatrix.widgetDefaults(widget("s", "wear-m3/screen-scaffold")))
    assertTrue(UiBuilderDesignMatrix.FORM_WIDGET in UiBuilderDesignMatrix.FORM_FACTORS)
  }

  @Test
  fun `a stadium host is masked to its outline on the sheet`() {
    val round = UiBuilderDesignMatrix.preset("widget_small_round")!!
    val sheet =
      UiBuilderDesignMatrix.compose(
        listOf(UiBuilderDesignMatrix.Cell(round, "light", 1.0, png(230, 76), null))
      )
    val image = ImageIO.read(sheet.png.inputStream())
    val placed = sheet.placed.single()
    // The corner is the sheet's background, the middle is the picture.
    assertEquals(SHEET_BACKGROUND, image.getRGB(placed.x + 1, placed.y + 1) and 0xffffff)
    assertEquals(
      FILL,
      image.getRGB(placed.x + placed.width / 2, placed.y + placed.height / 2) and 0xffffff,
    )
  }

  private val catalog =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("remote-m3", "source", "remote-m3", "candidate", "candidate")
          .build(),
        emptyList(),
      )
      .build()

  private fun widget(id: String, container: String) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = id,
      revision = 3,
      catalogPin = CatalogReferenceV1("remote-m3", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 216,
          heightDp = 76,
          density = 2.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("host"),
      nodes = mapOf("host" to DesignNodeV1(id = "host", componentId = container)),
    )

  private fun png(width: Int, height: Int): ByteArray {
    val image = BufferedImage(maxOf(width, 1), maxOf(height, 1), BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = java.awt.Color(FILL)
    graphics.fillRect(0, 0, image.width, image.height)
    graphics.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private companion object {
    const val FILL = 0x3366CC
    const val SHEET_BACKGROUND = 0xF2F2F2
  }
}
