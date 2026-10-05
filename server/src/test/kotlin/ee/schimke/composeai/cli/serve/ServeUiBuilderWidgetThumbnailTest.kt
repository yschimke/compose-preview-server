package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
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
import java.io.Closeable
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A Wear widget's card picture is the widget drawn in its host container by the native render lane,
 * not the bare export, which has no host frame and reads as a squashed rectangle.
 */
class ServeUiBuilderWidgetThumbnailTest {
  private val actor = AuthenticatedUiBuilderActor("github:owner")
  private val exports = AtomicInteger()
  private val rendered = CopyOnWriteArrayList<Pair<String, WearWidgetHostShape>>()
  private var documents = mapOf("widget" to widget(), "screen" to screen())

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.GetDesignActions ->
            UiBuilderServiceResponse.DesignActions(
              request.designId,
              DesignAccessActionV1.entries.toList(),
            )
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
          is UiBuilderServiceRequest.ExportDesign -> {
            exports.incrementAndGet()
            UiBuilderServiceResponse.Export(
              ExportArtifactV1(
                format = ExportFormatV1.PNG,
                mediaType = "image/png",
                encoding = ExportEncodingV1.BASE64,
                content = Base64.getEncoder().encodeToString(BARE),
                contentDigest = "digest",
                diagnostics =
                  listOf(
                    ExportDiagnosticV1(
                      severity = DiagnosticSeverityV1.INFO,
                      code = "REVISION_PINNED_DAEMON_RENDER",
                      message = "Rendered design ${request.designId} revision 7 (hash).",
                    )
                  ),
              )
            )
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val thumbnails =
    ServeUiBuilderThumbnails(
        Files.createTempDirectory("serve-ui-builder-widget-thumbnails"),
        generation = "g1",
        onLog = {},
      )
      .also { it.warming(service) }

  @AfterTest fun tearDown() = thumbnails.close()

  private fun lane(image: String?) = UiBuilderNativePreviewLane { document, shape ->
    rendered += document.id to shape
    UiBuilderNativePreviewOutcome.Rendered(
      PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = image),
      taggedNodeIds = emptyList(),
    )
  }

  @Test
  fun `a caller who may compile gets the widget drawn by the native lane in the squircle host`() {
    thumbnails.nativePreview = lane(PlaygroundCompileService.toDataUri(HOSTED))

    val entry = assertNotNull(runBlocking { thumbnails.render("widget", actor, native = true) })

    assertContentEquals(HOSTED, entry.png)
    assertEquals(7, entry.revision)
    assertFalse(entry.unframed)
    assertEquals(listOf("widget" to WearWidgetHostShape.Squircle), rendered)
    assertEquals(0, exports.get(), "the bare export is not asked for")
    assertTrue(thumbnails.isCurrent("widget", 7, native = true))
  }

  @Test
  fun `a caller without the export capability never compiles, and its picture is unframed`() {
    thumbnails.nativePreview = lane(PlaygroundCompileService.toDataUri(HOSTED))

    val entry = assertNotNull(runBlocking { thumbnails.render("widget", actor) })

    assertEquals(emptyList(), rendered, "a reader's draw must not run the compile lane")
    assertContentEquals(BARE, entry.png)
    assertTrue(entry.unframed)
    assertTrue(thumbnails.isCurrent("widget", 7), "current for a reader")
    assertFalse(thumbnails.isCurrent("widget", 7, native = true), "stale for one who may compile")
  }

  @Test
  fun `a design that is not a widget keeps the export`() {
    thumbnails.nativePreview = lane(PlaygroundCompileService.toDataUri(HOSTED))

    val entry = assertNotNull(runBlocking { thumbnails.render("screen", actor, native = true) })

    assertEquals(emptyList(), rendered)
    assertEquals(1, exports.get())
    assertFalse(entry.unframed)
  }

  @Test
  fun `a failed native draw falls back, and is retried a bounded number of times`() {
    thumbnails.nativePreview = lane(image = null)

    repeat(ServeUiBuilderThumbnails.NATIVE_ATTEMPTS) {
      assertFalse(thumbnails.isCurrent("widget", 7, native = true), "attempt ${it + 1} is due")
      val entry = assertNotNull(runBlocking { thumbnails.render("widget", actor, native = true) })
      assertTrue(entry.unframed)
    }

    assertEquals(ServeUiBuilderThumbnails.NATIVE_ATTEMPTS, rendered.size)
    assertTrue(
      thumbnails.isCurrent("widget", 7, native = true),
      "a widget the lane cannot draw stops being recompiled on every view",
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

  private fun widget() =
    document("widget", "remote-m3", DesignNodeV1(id = "host", componentId = WIDGET_CONTAINER))

  private fun screen() =
    document(
      "screen",
      "wear-m3",
      DesignNodeV1(id = "host", componentId = "wear-m3/screen-scaffold"),
    )

  private fun document(id: String, catalog: String, root: DesignNodeV1) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = id,
      revision = 7,
      catalogPin = CatalogReferenceV1(catalog, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 216,
          heightDp = 124,
          density = 2.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf(root.id),
      nodes = mapOf(root.id to root),
    )

  private companion object {
    const val WIDGET_CONTAINER = "remote-m3/widget-container-large"
    val HOSTED: ByteArray = Base64.getDecoder().decode("iVBORw0KGgo=")
    val BARE: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 7)
  }
}
