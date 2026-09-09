package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EmbeddedAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A **Wear** design on the native render lane, which is the only surface that can draw one.
 *
 * ## Why this is its own test and not a case in [ServeUiBuilderNativePreviewTest]
 *
 * That file is about a record-driven `m3-catalog` screen: generate from the component record,
 * compile, read back bounds. A Wear screen reaches the same compiler by a different road and every
 * junction on it used to be a wall:
 *
 * 1. the generator **refused** every record-free design outright, telling a designer to "preview it
 *    on the canvas" — a canvas that draws Material 3 lookalikes because Wasm cannot link an Android
 *    AAR, which is the one thing a Wear design must not be judged on;
 * 2. the source it now emits is Wear Compose, so it has to reach a bundle carrying
 *    `androidx.wear.compose:compose-material3` — a *different* served catalog from the design's own
 *    id; and
 * 3. that bundle is an Android one, so the compile has to go to the Robolectric daemon rather than
 *    to Skiko, which is a different `confType`.
 *
 * Each of those is silent when wrong: a desktop compile of Wear source fails on every import and
 * reads like the design is broken. So each is asserted here, against a recording compile seam —
 * standing up a Kotlin compiler and a Wear classpath to check the contents of a string would be
 * testing the compiler.
 *
 * A **widget** is record-free too and reaches the same compiler by a fourth road, asserted at the
 * bottom: its source is Remote Compose, so the lane submits a body, a brush and the container spec
 * the design authored, and the entry draws them inside the Glance Wear container rather than
 * composing a screen.
 */
class ServeUiBuilderWearNativePreviewTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  /**
   * No component record for any catalog, which is the deployment `wear-m3` actually runs in.
   *
   * `wear-m3` deliberately has none — `ScreenScaffold` takes a scroll state that has to agree with
   * the list inside its own content lambda, which no recovered signature can express — so a lane
   * that needed one would be a lane Wear could never use. Passing `Unconfigured` here proves the
   * Wear path does not touch the record at all rather than happening to find one.
   */
  private val executor =
    ScreenGeneratorComposeExportExecutor({ ComponentRecordSource.Lookup.Unconfigured })

  private fun lane(
    nativeTarget: (String) -> UiBuilderNativeTarget? = {
      UiBuilderNativeTarget("wear-m3-catalog", UiBuilderGeneratedCompose.COMPOSE_ANDROID)
    }
  ) =
    ServeUiBuilderNativePreview(
      executor = executor,
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
      },
      nativeTarget = nativeTarget,
    )

  @Test
  fun `a wear screen compiles on the android daemon, against the mapped wear bundle`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane().render(wearScreen()))

    val request = submitted.single()
    // The Robolectric daemon, and this is the assertion that matters most in the file: Wear
    // Material 3 is an Android AAR, so a `compose-cmp` submission does not fail at render time —
    // it fails at `import androidx.wear.compose.material3.ScreenScaffold`.
    assertEquals(UiBuilderGeneratedCompose.COMPOSE_ANDROID, request.confType)
    // The served bundle, not the design's catalog id. Those were the same string only while
    // `m3-catalog` was the only catalog with a native lane.
    assertEquals("wear-m3-catalog", request.catalog)
    assertEquals("ActivityScreen", request.composableName)
    assertTrue("androidx.wear.compose.material3.ScreenScaffold" in request.source, request.source)
    assertTrue("TransformingLazyColumn(" in request.source, request.source)
    assertEquals(listOf("heading", "list", "screen"), rendered.taggedNodeIds)
  }

  /**
   * The frame comes back addressable, which is what makes it an editor rather than a picture.
   *
   * Same requirement as the mobile lane and a harder one to meet here: the Wear source is written
   * by `WearScreenCodeExporter` rather than projected through `ScreenDocumentProjection`, so the
   * tag has to be threaded through a second generator. Untagged, an overlay has nothing to anchor
   * to and clicking the render selects nothing.
   */
  @Test
  fun `the wear source carries every node id as a test tag`() {
    lane().render(wearScreen())

    val source = submitted.single().source
    assertTrue("""testTag("heading")""" in source, source)
    assertTrue("""testTag("list")""" in source, source)
    assertTrue("androidx.compose.ui.platform.testTag" in source, source)
  }

  /** An export is the source a designer keeps, and a test tag is not something they asked for. */
  @Test
  fun `the untagged generation carries no test tag`() {
    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        executor.generate(wearScreen(), tagNodes = false)
      )

    assertTrue("testTag" !in generated.source, generated.source)
  }

  /**
   * No bundle mapped for this catalog is the **host's** problem, and says so.
   *
   * A compile against a desktop classpath would fail on every `androidx.wear.compose` import and
   * report a wall of unresolved references, which reads like the design is broken. The refusal
   * names the flag an operator sets instead.
   */
  @Test
  fun `a host with no wear bundle refuses by naming the flag, not the design`() {
    val refused =
      assertIs<UiBuilderNativePreviewOutcome.Refused>(
        lane(nativeTarget = { null }).render(wearScreen())
      )

    assertEquals(ServeUiBuilderNativePreview.NO_NATIVE_CATALOG, refused.code)
    assertTrue("--ui-builder-native-catalog" in refused.reasons.single(), refused.reasons.single())
    assertTrue("wear-m3" in refused.reasons.single(), refused.reasons.single())
    assertTrue(submitted.isEmpty())
  }

  /**
   * A widget renders, and what it submits is not the file a designer exports.
   *
   * `WearWidgetCodeExporter` writes the artifact: a `GlanceWearWidget`, its `WearWidgetDocument`,
   * and a `@Preview` driven by a shipped params provider. None of that is submittable here — the
   * class's picture parameters default to a blank 1×1 bitmap nothing downstream can replace, and
   * the providers carry only the published container spec. So this lane submits the three
   * declarations `WearWidgetNativePreviewExporter` writes instead, and the entry hands them to
   * `WearWidgetPreview`.
   */
  @Test
  fun `a wear widget submits its body, its brush and its own container spec`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane().render(wearWidget()))

    val request = submitted.single()
    assertTrue(request.wearWidget, "the submission must take the widget entry")
    assertEquals(UiBuilderGeneratedCompose.COMPOSE_ANDROID, request.confType)
    assertEquals("Widget", request.composableName)
    assertTrue("fun WidgetContent() {" in request.source, request.source)
    assertTrue("fun WidgetBackground(): WearWidgetBrush {" in request.source, request.source)
    assertTrue("ContainerInfo.CONTAINER_TYPE_LARGE" in request.source, request.source)
    // No widget class: there is nothing here for the lane to construct one with.
    assertTrue("GlanceWearWidget" !in request.source, request.source)
    // The Large container's frame — 200×108dp of content inside 8dp of padding — rather than the
    // 192×496 watch screen this design's environment describes.
    assertEquals(216, request.widthDp)
    assertEquals(124, request.heightDp)
    // Remote Compose carries no test tag, so the frame comes back as a picture with no overlay.
    // Reported as none rather than as every node, so a client does not look up bounds that a
    // tagless render was never going to have.
    assertEquals(emptyList(), rendered.taggedNodeIds)
    assertEquals(emptyMap(), rendered.nodeBounds)
  }

  /**
   * A widget's pictures travel inside the source, because nothing downstream can pass one.
   *
   * The export asks for them as parameters — a widget's artwork is application data — and defaults
   * them to a blank bitmap so its `@Preview` compiles. Submitted here that would render a hole
   * where the canvas beside it draws the picture, which is the one disagreement between the two
   * surfaces this lane would have caused itself.
   */
  @Test
  fun `a widget picture is inlined into the native preview source`() {
    lane().render(wearWidget(assetKey = "cover"))

    val source = submitted.single().source
    assertTrue("RemoteImage(" in source, source)
    assertTrue(ONE_PIXEL_PNG_BASE64 in source, source)
    assertTrue("decodeInlineBitmap(" in source, source)
    assertTrue("RemoteImageBitmap)" !in source, source)
  }

  private fun environment() =
    DesignEnvironmentV1(
      widthDp = 192,
      heightDp = 496,
      density = 1.0,
      theme = ThemeV1.DARK,
      locale = "en-US",
      fontScale = 1.0,
      layoutDirection = LayoutDirectionV1.LTR,
      windowPosture = WindowPostureV1.FLAT,
      animations = AnimationStateV1.SETTLED,
      networkAccess = false,
    )

  private fun wearScreen(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "activity",
      title = "Activity",
      revision = 0,
      catalogPin = CatalogReferenceV1("wear-m3", "candidate", "candidate", "candidate"),
      environment = environment(),
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to
            DesignNodeV1(
              id = "screen",
              componentId = "wear-m3/screen-scaffold",
              slots = mapOf("content" to listOf("list")),
            ),
          "list" to
            DesignNodeV1(
              id = "list",
              componentId = "wear-m3/transforming-lazy-column",
              slots = mapOf("items" to listOf("heading")),
            ),
          "heading" to
            DesignNodeV1(
              id = "heading",
              componentId = "wear-m3/list-header",
              properties = mapOf("text" to StringValueV1("Today")),
            ),
        ),
    )

  /**
   * A **Large** container, so the frame this lane reports is not the same number as the Small one's
   * and an assertion about it cannot pass by accident.
   *
   * @param assetKey a picture in the container's content, whose bytes ride in the design's own
   *   asset map — an embedded binding rather than an uploaded one, so the fixture needs no store.
   */
  private fun wearWidget(assetKey: String? = null): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "widget",
      title = "Widget",
      revision = 0,
      catalogPin = CatalogReferenceV1("remote-m3", "candidate", "candidate", "candidate"),
      environment = environment(),
      roots = listOf("host"),
      nodes =
        buildMap {
          put(
            "host",
            DesignNodeV1(
              id = "host",
              componentId = "remote-m3/widget-container-large",
              slots = if (assetKey == null) emptyMap() else mapOf("content" to listOf("art")),
            ),
          )
          if (assetKey != null) {
            put(
              "art",
              DesignNodeV1(
                id = "art",
                componentId = "asset/image",
                properties = mapOf("assetKey" to StringValueV1(assetKey)),
              ),
            )
          }
        },
      assets =
        if (assetKey == null) emptyMap()
        else
          mapOf(
            assetKey to
              AssetBindingV1(
                mediaType = "image/png",
                contentDigest = "sha256:unused",
                source = EmbeddedAssetSourceV1(ONE_PIXEL_PNG_BASE64),
              )
          ),
    )

  private companion object {
    /** Stands in for artwork: the bytes are never decoded here, only carried into the source. */
    const val ONE_PIXEL_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUg"
  }
}
