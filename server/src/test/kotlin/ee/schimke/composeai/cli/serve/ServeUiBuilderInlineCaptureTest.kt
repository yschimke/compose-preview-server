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
 * The inline capture lane, asserted against a recording compile seam.
 *
 * Every junction on the road from a design to played pixels is silent when it is wrong, which is
 * why each is asserted here rather than left to a live host:
 *
 * 1. the **mode**. A body submitted as `compose-cmp` or `compose-android` compiles and mints a live
 *    session; only `remote-compose` publishes the document, and the difference is one string.
 * 2. the **entry**. A `@RemoteComposable` body composed directly draws nothing at all — its
 *    declarations are recorded by whatever wraps the composition — so an entry that forgot the
 *    wrapper would compile, render, and capture an empty document.
 * 3. the **catalog**. An inline body needs the Remote Compose creation library rather than the
 *    design's own design system, so the target is the host's rather than the document's.
 *
 * Standing up a Kotlin compiler and a Robolectric daemon to check the contents of a string would be
 * testing the compiler, which is the same call [ServeUiBuilderWearNativePreviewTest] makes.
 */
class ServeUiBuilderInlineCaptureTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  private fun lane(
    captureCatalog: () -> String? = { "wear-m3-catalog" },
    documentUrl: String? = "/d/abc123",
  ) =
    ServeUiBuilderInlineCapture(
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(documentUrl = documentUrl, previewId = "generated")
      },
      captureCatalog = captureCatalog,
    )

  @Test
  fun `an inline node captures as a remote-compose snippet against the host's catalog`() {
    val captured =
      assertIs<UiBuilderInlineCaptureOutcome.Captured>(lane().capture(screen(), "remote"))

    assertEquals("remote", captured.nodeId)
    assertEquals("/d/abc123", captured.documentUrl)
    assertEquals("PlayerScreenRemoteRemoteContent", captured.functionName)

    val request = submitted.single()
    // The mode that publishes a document. `compose-android` would compile the same body, render it
    // and hand back a live session token — a preview of a composable that draws nothing.
    assertEquals(ServeUiBuilderInlineCapture.REMOTE_COMPOSE_CONF_TYPE, request.confType)
    // The host's catalog, not the design's `m3-catalog`: what this body needs from a bundle is the
    // creation library, and a Material 3 desktop bundle carries none of it.
    assertEquals("wear-m3-catalog", request.catalog)
    assertTrue(request.remoteCapture, "the entry has to wrap the body in the capture")
    // The body itself, in the Remote Compose vocabulary rather than in the screen's.
    assertTrue("@RemoteComposable" in request.source, request.source)
    assertTrue("RemoteColumn {" in request.source, request.source)
  }

  /**
   * The entry wraps the body, and does so in the composable that owns the density decision.
   *
   * Asserted on the generated text because this is the one junction with no failure mode: an entry
   * that composed the body directly compiles, renders and captures nothing, and the lane would
   * report "no document" for a body that is perfectly fine.
   */
  @Test
  fun `the capture entry wraps the body in RemoteOverridablePreview`() {
    val entry =
      UiBuilderGeneratedPreviewAdapter.previewEntry(
        composableName = "PlayerScreenRemoteRemoteContent",
        widthDp = 412,
        heightDp = 892,
        remoteCapture = true,
      )

    assertTrue("RemoteOverridablePreview(" in entry, entry)
    assertTrue("import ee.schimke.composeai.daemon.RemoteOverridablePreview" in entry, entry)
    assertTrue("RcPlatformProfiles.ANDROIDX" in entry, entry)
  }

  /** And an ordinary screen's entry is untouched by the option that exists for the body. */
  @Test
  fun `the screen entry is unchanged`() {
    val entry =
      UiBuilderGeneratedPreviewAdapter.previewEntry(
        composableName = "GeneratedScreen",
        widthDp = 412,
        heightDp = 892,
      )

    assertTrue("RemoteOverridablePreview" !in entry, entry)
    assertTrue("GeneratedUiBuilderScreen()" in entry, entry)
  }

  /**
   * A host with no Remote Compose catalog says so about **itself**.
   *
   * The design is fine and would capture on a box that serves one, so telling a designer to change
   * their screen would send them to the wrong half of the system — the same split
   * `NO_NATIVE_CATALOG` makes.
   */
  @Test
  fun `a host that compiles no Remote Compose refuses by naming itself`() {
    val refused =
      assertIs<UiBuilderInlineCaptureOutcome.Refused>(
        lane(captureCatalog = { null }).capture(screen(), "remote")
      )

    assertEquals(ServeUiBuilderInlineCapture.NO_REMOTE_COMPOSE_CATALOG, refused.code)
    assertTrue("this host serves no catalog" in refused.reasons.single(), refused.reasons.single())
    assertTrue(submitted.isEmpty())
  }

  /**
   * A node that is not inline remote content has no body, on any host — so the host is not asked.
   */
  @Test
  fun `a node that is not inline content is refused before the host is consulted`() {
    val refused =
      assertIs<UiBuilderInlineCaptureOutcome.Refused>(
        lane(captureCatalog = { null }).capture(screen(), "screen")
      )

    assertEquals(ServeUiBuilderInlineCapture.NO_BODY, refused.code)
    assertTrue("layout/column" in refused.reasons.single(), refused.reasons.single())
    assertTrue(submitted.isEmpty())
  }

  /**
   * A compile that produced no document reports the compiler's own words.
   *
   * The lane has no editor to draw squiggles in, so a caller that forwarded only the response's
   * `exception` would say "captured nothing" for a body that did not compile.
   */
  @Test
  fun `no document is refused with the reason the compile lane gave`() {
    val lane =
      ServeUiBuilderInlineCapture(
        compile = {
          PlaygroundRunResponse(
            exception =
              "no Remote Compose document was captured — a remote-compose snippet must " +
                "declare an @Preview that emits a RemoteDocument"
          )
        },
        captureCatalog = { "wear-m3-catalog" },
      )

    val refused = assertIs<UiBuilderInlineCaptureOutcome.Refused>(lane.capture(screen(), "remote"))

    assertEquals(ServeUiBuilderInlineCapture.NO_DOCUMENT, refused.code)
    assertTrue("emits a RemoteDocument" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * The three-scope screen the design docs describe: Compose, then Remote Compose, then Compose.
   *
   * Pinned to `m3-catalog` on purpose — a mobile design is the case where the design's own catalog
   * and the capture's are certainly different.
   */
  private fun screen(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "player",
      title = "Player screen",
      revision = 3,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 412,
          heightDp = 892,
          density = 2.625,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to
            DesignNodeV1(
              id = "screen",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("remote")),
            ),
          "remote" to
            DesignNodeV1(
              id = "remote",
              componentId = "remote-compose/inline",
              slots = mapOf("content" to listOf("remoteColumn")),
            ),
          "remoteColumn" to
            DesignNodeV1(
              id = "remoteColumn",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("remoteLabel", "custom")),
            ),
          "remoteLabel" to
            DesignNodeV1(
              id = "remoteLabel",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Search")),
            ),
          "custom" to
            DesignNodeV1(
              id = "custom",
              componentId = "remote-compose/custom",
              properties = mapOf("name" to StringValueV1("field")),
              slots = mapOf("content" to listOf("field")),
            ),
          "field" to
            DesignNodeV1(
              id = "field",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Type to search…")),
            ),
        ),
    )
}
