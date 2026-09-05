package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The native render lane: a design compiled and drawn by real Compose instead of by the browser.
 *
 * ## What is worth asserting here
 *
 * Not that Compose renders — [UiBuilderGeneratedPreviewAdapterTest] already compiles a
 * representative generated screen and enters first-frame render. What this lane adds is the three
 * decisions between a design and that adapter, and each of them is silent when wrong:
 *
 * 1. the generated source is **tagged** with design node ids, which is the only thing that makes a
 *    streamed frame addressable — an untagged render is a picture;
 * 2. the **export** artifact for the same design is **not** tagged, because a test tag is not
 *    something a designer asked for in source they keep;
 * 3. a design the generator refuses never reaches the compiler at all, and comes back with the
 *    generator's own reasons rather than a second vocabulary.
 *
 * The compile lane is a recorder rather than a real `PlaygroundCompileService`, so what the lane
 * submits can be read directly. Standing up a Kotlin compiler and a catalog classpath to assert the
 * contents of a string would test the compiler.
 */
class ServeUiBuilderNativePreviewTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  private val executor =
    ScreenGeneratorComposeExportExecutor(
      ComponentRecordSource(mapOf(CATALOG to ScreenGeneratorScreenFixture.componentsFile()))::record
    )

  private var boundsCaptures = 0

  private val lane =
    ServeUiBuilderNativePreview(
      executor = executor,
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
      },
      captureNodeBounds = { response ->
        boundsCaptures++
        assertEquals("token", response.previewToken)
        mapOf("heading" to AnnotationBounds(x = 8, y = 16, width = 200, height = 24))
      },
    )

  @Test
  fun `the rendered source carries every design node id as a test tag`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane.render(document()))

    val source = submitted.single().source
    assertTrue(source.contains("""testTag("heading")"""), source)
    assertTrue(source.contains("""testTag("column")"""), source)
    // Reported as well as emitted: a client keying a bounds lookup off this list must not have to
    // recompute it from the document and drift when the projection stops tagging something.
    assertEquals(listOf("column", "heading"), rendered.taggedNodeIds)

    // The design's own frame and catalog, not a default. A design pinned to one catalog and
    // compiled against another is a screen of different components that happens to type-check.
    val generated = submitted.single()
    assertEquals(CATALOG, generated.catalog)
    assertEquals(400, generated.widthDp)
    assertEquals(800, generated.heightDp)
    assertEquals("AgentScreen", generated.composableName)
  }

  @Test
  fun `the export of the same design is not tagged`() {
    // The claim the `tagNodes` flag exists to make. A test tag in an artifact somebody keeps is
    // noise they did not ask for, and the two paths differ by exactly this.
    lane.render(document())
    val artifact =
      executor.export(
        RevisionPinnedUiBuilderExport(
          actor = AuthenticatedUiBuilderActor("tester"),
          designId = "agent-screen",
          revision = 0,
          documentHash = "hash",
          document = document(),
          catalog =
            CatalogCapabilityV1(
              schema = "compose-catalog-capabilities/v1",
              benchmark = CatalogBenchmarkV1(CATALOG, "source", CATALOG, "candidate", "candidate"),
              components = emptyList(),
              exportCapabilities =
                ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
            ),
          format = ExportFormatV1.COMPOSE,
        )
      )

    assertTrue(submitted.single().source.contains("testTag("))
    assertTrue(!artifact.content.contains("testTag("), artifact.content)
    assertEquals(emptyList(), artifact.diagnostics)
  }

  @Test
  fun `a design the generator refuses never reaches the compiler`() {
    // `matchParentSize` is declared on `BoxScope`, and the projection refuses it outside one. The
    // point is not the modifier: it is that a refusal stops here rather than submitting Kotlin
    // that cannot compile and reporting the compiler's opinion of it.
    val broken =
      document().let { document ->
        document.copy(
          nodes =
            document.nodes +
              mapOf(
                "heading" to
                  document.nodes
                    .getValue("heading")
                    .copy(modifiers = listOf(MatchParentSizeModifierV1))
              )
        )
      }

    val refused = assertIs<UiBuilderNativePreviewOutcome.Refused>(lane.render(broken))

    assertEquals(emptyList(), submitted)
    assertTrue(refused.reasons.any { it.contains("matchParentSize") }, refused.reasons.toString())
    assertNull(refused.reasons.firstOrNull { it.contains("compile") })
  }

  @Test
  fun `the frame carries the boxes its tagged nodes drew`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane.render(document()))

    // Keyed by design node id, in the frame's own render pixels: the pane scales both by the one
    // factor it computes to fit the image, so a box means the same thing as the pixels under it.
    assertEquals(
      mapOf("heading" to AnnotationBounds(x = 8, y = 16, width = 200, height = 24)),
      rendered.nodeBounds,
    )
    // A subset of the tag set, not a rename of it: `column` is tagged and has no box here, which is
    // what a node the render never placed looks like.
    assertEquals(listOf("column", "heading"), rendered.taggedNodeIds)
  }

  @Test
  fun `a compile with no frame is not asked for bounds`() {
    // A failed compile has no render to read semantics off, and asking anyway would stand up a
    // second daemon session to answer nothing.
    val laneWithoutFrame =
      ServeUiBuilderNativePreview(
        executor = executor,
        compile = { PlaygroundRunResponse(exception = "compilation failed") },
        captureNodeBounds = {
          boundsCaptures++
          mapOf("heading" to AnnotationBounds(0, 0, 1, 1))
        },
      )

    val rendered =
      assertIs<UiBuilderNativePreviewOutcome.Rendered>(laneWithoutFrame.render(document()))

    assertEquals(emptyMap(), rendered.nodeBounds)
    assertEquals(0, boundsCaptures)
    assertEquals("compilation failed", rendered.response.exception)
    assertEquals("compilation failed", rendered.failure)
  }

  @Test
  fun `a frame reports no failure`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane.render(document()))

    assertNull(rendered.failure)
  }

  @Test
  fun `compiler errors are the failure when the lane set no exception`() {
    // The regression this exists for: the compile lane reports a broken snippet as ERROR
    // diagnostics with a null `exception`, because the playground frontend draws those as inline
    // squiggles. A caller that read the exception alone reported a compile failure as "compiled,
    // no frame" — the one reading of a frameless response that is never true.
    val laneWithDiagnostics =
      ServeUiBuilderNativePreview(
        executor = executor,
        compile = {
          PlaygroundRunResponse(
            diagnostics =
              listOf(
                PlaygroundDiagnostic(PlaygroundSeverity.WARNING, "unused import", "Screen.kt", 1),
                PlaygroundDiagnostic(
                  PlaygroundSeverity.ERROR,
                  "unresolved reference: Scaffold",
                  "UiBuilderGeneratedScreen.kt",
                  line = 11,
                  ch = 4,
                ),
              )
          )
        },
      )

    val rendered =
      assertIs<UiBuilderNativePreviewOutcome.Rendered>(laneWithDiagnostics.render(document()))

    // Positions are shifted to 1-based: the wire shape counts from zero because CodeMirror does,
    // and this string is read by a person.
    assertEquals(
      "UiBuilderGeneratedScreen.kt:12:5: unresolved reference: Scaffold",
      rendered.failure,
    )
  }

  @Test
  fun `a compile that neither failed nor drew names the renderer`() {
    // Compiled, a @Preview was discovered (that miss sets `exception`), and the render seam still
    // came back empty. Blaming the design here would send the reader to the wrong half of the lane.
    val laneWithoutRenderer =
      ServeUiBuilderNativePreview(executor = executor, compile = { PlaygroundRunResponse() })

    val rendered =
      assertIs<UiBuilderNativePreviewOutcome.Rendered>(laneWithoutRenderer.render(document()))

    assertEquals(
      "the design compiled, but this host's renderer produced no frame for it",
      rendered.failure,
    )
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "agent-screen",
      title = "Agent screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("heading")),
            ),
          "heading" to
            DesignNodeV1(
              id = "heading",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            ),
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
  }
}
