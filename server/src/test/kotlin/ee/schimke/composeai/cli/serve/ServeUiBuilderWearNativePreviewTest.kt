package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
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
 * A **widget** is the deliberate counter-case at the bottom: it is record-free too and it still
 * refuses, because its source is a Remote Compose document rather than a `@Preview` this lane can
 * discover.
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
   * A widget is record-free too, and still has nothing for this lane to render.
   *
   * `WearWidgetCodeExporter` writes a `WearWidgetDocument` of Remote Compose — played by a player,
   * not composed — so compiling it would produce no `@Preview` and no frame. The refusal is the
   * right answer here, and keeping it while the screen passes is the whole shape of this change.
   */
  @Test
  fun `a wear widget is still refused, because Remote Compose has no preview to render`() {
    val refused = assertIs<UiBuilderNativePreviewOutcome.Refused>(lane().render(wearWidget()))

    assertEquals(ScreenGeneratorComposeExportExecutor.RECORD_FREE_DESIGN, refused.code)
    assertTrue("Remote Compose" in refused.reasons.single(), refused.reasons.single())
    assertTrue(submitted.isEmpty())
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

  private fun wearWidget(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "widget",
      title = "Widget",
      revision = 0,
      catalogPin = CatalogReferenceV1("remote-m3", "candidate", "candidate", "candidate"),
      environment = environment(),
      roots = listOf("host"),
      nodes =
        mapOf(
          "host" to DesignNodeV1(id = "host", componentId = "remote-m3/widget-container-small")
        ),
    )
}
