package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A design that draws on a component pack, on the two lanes that generate Kotlin for it.
 *
 * The export merges the pack's record into the catalog's and resolves the pack id as an alias; the
 * native lane compiles the result against the **pack's** bundle, because that is the one classpath
 * carrying both the pack's classes and the Material 3 the catalog names.
 */
class ServeUiBuilderPackTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  private fun executor(
    packRecord: ComponentRecordSource.Lookup =
      ComponentRecordSource.Lookup.Found(ComponentRecordPacksTest.record())
  ) =
    ScreenGeneratorComposeExportExecutor(
      components = { catalog ->
        when (catalog) {
          "test-catalog" ->
            ComponentRecordSource.Lookup.Found(ScreenGeneratorScreenFixture.components())
          "confetti-mobile" -> packRecord
          else -> ComponentRecordSource.Lookup.Unconfigured
        }
      },
      packs = setOf("confetti-mobile", "jetnews"),
    )

  private fun lane(executor: ScreenGeneratorComposeExportExecutor = executor()) =
    ServeUiBuilderNativePreview(
      executor = executor,
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
      },
      nativeTarget = { UiBuilderNativeTarget(it, UiBuilderGeneratedCompose.COMPOSE_CMP) },
      packs = setOf("confetti-mobile", "jetnews"),
    )

  @Test
  fun `a pack component exports through the pack's record, beside the catalog's own`() {
    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        executor().generate(withPackNode())
      )

    assertTrue("import dev.confetti.ui.SessionCard" in generated.source, generated.source)
    assertTrue("SessionCard(" in generated.source, generated.source)
    assertTrue("title = \"Compose everywhere\"" in generated.source, generated.source)
    // The catalog's own components still resolve against the catalog's own record.
    assertTrue("import androidx.compose.material3.Card" in generated.source, generated.source)
  }

  @Test
  fun `a design using no pack is generated from the catalog's record alone`() {
    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        executor(packRecord = ComponentRecordSource.Lookup.Unconfigured)
          .generate(ScreenGeneratorScreenFixture.document())
      )
    assertTrue("SessionCard" !in generated.source)
  }

  @Test
  fun `a pack whose record is missing refuses by naming the pack and the flag`() {
    val refused =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Refused>(
        executor(packRecord = ComponentRecordSource.Lookup.Unconfigured).generate(withPackNode())
      )

    assertEquals(ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD, refused.code)
    val reason = refused.reasons.single()
    assertTrue("`confetti-mobile`" in reason, reason)
    assertTrue("--ui-builder-components confetti-mobile=" in reason, reason)
  }

  @Test
  fun `the native lane compiles a pack design against the pack's bundle`() {
    val rendered = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane().render(withPackNode()))

    assertEquals("confetti-mobile", submitted.single().catalog)
    assertTrue("session" in rendered.taggedNodeIds)
    assertTrue("""testTag("session")""" in submitted.single().source)
  }

  @Test
  fun `the native lane compiles a pack-free design against its own catalog`() {
    lane().render(ScreenGeneratorScreenFixture.document())
    assertEquals("test-catalog", submitted.single().catalog)
  }

  @Test
  fun `two packs in one design have no bundle and are refused as such`() {
    val document =
      withPackNode().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("story" to DesignNodeV1(id = "story", componentId = "jetnews/story-card")) +
              ("column" to
                base.nodes
                  .getValue("column")
                  .copy(slots = mapOf("content" to listOf("heading", "card", "session", "story"))))
        )
      }

    val refused = assertIs<UiBuilderNativePreviewOutcome.Refused>(lane().render(document))

    assertEquals(ServeUiBuilderNativePreview.MIXED_PACKS, refused.code)
    assertTrue("`confetti-mobile`" in refused.reasons.single())
    assertTrue("`jetnews`" in refused.reasons.single())
    assertTrue(submitted.isEmpty())
  }

  /** The fixture screen with a Confetti `SessionCard` dropped into its column. */
  private fun withPackNode(): DesignDocumentV1 {
    val base = ScreenGeneratorScreenFixture.document()
    return base.copy(
      nodes =
        base.nodes +
          ("session" to
            DesignNodeV1(
              id = "session",
              componentId = "confetti-mobile/session-card",
              properties = mapOf("title" to StringValueV1("Compose everywhere")),
            )) +
          ("column" to
            base.nodes
              .getValue("column")
              .copy(slots = mapOf("content" to listOf("heading", "card", "session"))))
    )
  }
}
