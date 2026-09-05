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

  private val lane =
    ServeUiBuilderNativePreview(executor) { generated ->
      submitted += generated
      PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
    }

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
