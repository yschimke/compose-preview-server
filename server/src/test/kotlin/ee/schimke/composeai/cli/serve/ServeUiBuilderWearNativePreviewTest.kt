package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
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
 * A Wear design on the native render lane, the only surface that can draw one. Separate from
 * [ServeUiBuilderNativePreviewTest] (record-driven `m3-catalog`) because a Wear screen takes
 * another road to the compiler:
 * 1. the generator handles record-free designs (the canvas only draws M3 lookalikes, since Wasm
 *    can't link an Android AAR);
 * 2. the emitted Wear Compose source must reach a bundle carrying
 *    `androidx.wear.compose:compose-material3`, a different served catalog; and
 * 3. that bundle is Android, so the compile goes to the Robolectric daemon (a different
 *    `confType`). Each fails silently when wrong (desktop compiles of Wear source fail on every
 *    import), so each is asserted against a recording compile seam. Widgets, also record-free,
 *    submit Remote Compose: a body, a brush and the authored container spec, drawn inside the
 *    Glance Wear container.
 */
class ServeUiBuilderWearNativePreviewTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  /**
   * No component record for any catalog, as `wear-m3` runs (`ScreenScaffold`'s scroll state can't
   * be expressed by a recovered signature), proving the Wear path never touches the record.
   */
  private val executor =
    ScreenGeneratorComposeExportExecutor({ ComponentRecordSource.Lookup.Unconfigured })

  private fun lane(
    nativeTarget: (String) -> UiBuilderNativeTarget? = {
      UiBuilderNativeTarget("wear-m3-catalog", UiBuilderGeneratedCompose.COMPOSE_ANDROID)
    },
    widgetPlayer: UiBuilderWidgetPlayer = UiBuilderWidgetPlayer.DEFAULT,
    carriesCmpWidgetPlayer: (String) -> Boolean = { true },
  ) =
    ServeUiBuilderNativePreview(
      executor = executor,
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
      },
      nativeTarget = nativeTarget,
      widgetPlayer = widgetPlayer,
      carriesCmpWidgetPlayer = carriesCmpWidgetPlayer,
    )

  @Test
  fun `a wear screen compiles on the android daemon, against the mapped wear bundle`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane().render(wearScreen()))

    val request = submitted.single()
    // The Robolectric daemon: Wear Material 3 is an Android AAR, so a `compose-cmp` submission
    // fails at its imports.
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
   * The frame comes back addressable (tagged), so overlays can anchor. The tag is threaded through
   * `WearScreenCodeExporter`, a second generator.
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
   * A Wear component left as the design's root is written by neither Wear emitter; the lane says
   * what to do, matching the export, Code pane and Issues panel.
   */
  @Test
  fun `a bare wear component root is refused with the wrap-it-in-a-screen sentence`() {
    val wearExecutor =
      ScreenGeneratorComposeExportExecutor(
        { ComponentRecordSource.Lookup.Unconfigured },
        catalogPlatform = { UiBuilderCatalogPlatform.WEAR },
      )
    val bareRoot =
      wearScreen()
        .copy(
          roots = listOf("heading"),
          nodes = wearScreen().nodes.filterKeys { it == "heading" },
        )

    val refused =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Refused>(
        wearExecutor.generate(bareRoot, tagNodes = true)
      )

    assertEquals("UNEXPRESSIBLE_DOCUMENT", refused.code)
    assertEquals(1, refused.reasons.size, refused.reasons.toString())
    assertTrue("wear-m3/screen-scaffold" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * No bundle mapped for this catalog is the host's problem: the refusal names the operator's flag
   * rather than producing a wall of unresolved references.
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
   * A widget renders via the three declarations `WearWidgetNativePreviewExporter` writes, not the
   * exported `GlanceWearWidget` (whose picture parameters default to a blank bitmap), handed to
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
    // With no explicit shape, the current UI-builder exporter chooses the broad rectangular host,
    // rather than the 192×496 watch screen this design's environment describes.
    assertEquals(232, request.widthDp)
    assertEquals(144, request.heightDp)
    // Remote Compose carries no test tags, so no overlay ids are reported.
    assertEquals(emptyList(), rendered.taggedNodeIds)
    assertEquals(emptyMap(), rendered.nodeBounds)
  }

  /**
   * The rectangular host container is a different frame (different content box and padding than the
   * squircle), drawn on request; a layout that fits the squircle can clip here (see
   * yschimke/compose-preview-server#587). The shape comes from the host, not the document.
   */
  @Test
  fun `a widget asked for the rectangular container gets that frame`() {
    lane().render(wearWidget(), WearWidgetHostShape.Rectangular)

    val request = submitted.single()
    assertTrue("widthDp = 168f" in request.source, request.source)
    assertTrue("heightDp = 112f" in request.source, request.source)
    assertTrue("horizontalPaddingDp = 32f" in request.source, request.source)
    assertTrue("verticalPaddingDp = 16f" in request.source, request.source)
    assertTrue("cornerRadiusDp = 0f" in request.source, request.source)
    // The container type is the size, not the shape — a Large widget stays Large in both frames.
    assertTrue("ContainerInfo.CONTAINER_TYPE_LARGE" in request.source, request.source)
    // 168 + 2×32 by 112 + 2×16, which is what `WearWidgetPreview` sizes itself to.
    assertEquals(232, request.widthDp)
    assertEquals(144, request.heightDp)
  }

  /**
   * The lane uses the player the host was started with, always on the Android daemon; only playback
   * differs.
   */
  @Test
  fun `a widget is played by the CMP player by default and by androidx when the host asks`() {
    lane().render(wearWidget())
    lane(widgetPlayer = UiBuilderWidgetPlayer.ANDROIDX).render(wearWidget())

    val (byDefault, byAndroidx) = submitted
    assertEquals(UiBuilderWidgetPlayer.CMP, byDefault.widgetPlayer)
    assertEquals(UiBuilderWidgetPlayer.ANDROIDX, byAndroidx.widgetPlayer)
    assertEquals(UiBuilderGeneratedCompose.COMPOSE_ANDROID, byDefault.confType)
    assertEquals(UiBuilderGeneratedCompose.COMPOSE_ANDROID, byAndroidx.confType)
    assertEquals(byDefault.source, byAndroidx.source)
  }

  /**
   * The CMP entry imports `rc-player-compose`; a bundle without it falls back to the upstream
   * preview.
   */
  @Test
  fun `a widget bundle without the CMP player is drawn by androidx`() {
    val asked = mutableListOf<String>()
    lane(
        carriesCmpWidgetPlayer = {
          asked += it
          false
        }
      )
      .render(wearWidget())
    lane(carriesCmpWidgetPlayer = { false }, widgetPlayer = UiBuilderWidgetPlayer.ANDROIDX)
      .render(wearWidget())

    assertEquals(listOf("wear-m3-catalog"), asked)
    assertEquals(
      listOf(UiBuilderWidgetPlayer.ANDROIDX, UiBuilderWidgetPlayer.ANDROIDX),
      submitted.map { it.widgetPlayer },
    )
  }

  /** Asking for nothing draws the broad rectangular editing host. */
  @Test
  fun `a widget with no shape asked for uses the rectangular frame`() {
    lane().render(wearWidget())

    val request = submitted.single()
    assertTrue("cornerRadiusDp = 0f" in request.source, request.source)
    assertEquals(232, request.widthDp)
    assertEquals(144, request.heightDp)
  }

  /**
   * A widget's pictures travel inside the source: the export defaults them to a blank bitmap, which
   * would render a hole where the canvas draws the picture.
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
   * A Large container, so the reported frame can't match the Small one by accident.
   * @param assetKey a picture in the container's content, embedded in the design's own asset map
   *   (no store needed).
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
