package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.wearScreenUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A design that draws on a component pack, on the two lanes that generate Kotlin for it.
 *
 * The export merges the pack's record into the catalog's and resolves the pack id as an alias; the
 * native lane compiles the result against the **pack's** bundle, because that is the one classpath
 * carrying both the pack's classes and the Material 3 the catalog names.
 */
class ServeUiBuilderPackTest {

  private val submitted = mutableListOf<UiBuilderGeneratedCompose>()

  /** Every catalog id the executor was asked for a record of, in order. */
  private val lookedUp = mutableListOf<String>()

  private fun executor(
    packRecord: ComponentRecordSource.Lookup =
      ComponentRecordSource.Lookup.Found(ComponentRecordPacksTest.record())
  ) =
    ScreenGeneratorComposeExportExecutor(
      components = { catalog ->
        lookedUp += catalog
        when (catalog) {
          "test-catalog" ->
            ComponentRecordSource.Lookup.Found(ScreenGeneratorScreenFixture.components())
          "confetti-mobile" -> packRecord
          "confetti-wear" ->
            ComponentRecordSource.Lookup.Found(ComponentRecordPacksTest.wearRecord())
          else -> ComponentRecordSource.Lookup.Unconfigured
        }
      },
      packs = PACKS,
    )

  private fun lane(executor: ScreenGeneratorComposeExportExecutor = executor()) =
    ServeUiBuilderNativePreview(
      executor = executor,
      compile = { generated ->
        submitted += generated
        PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
      },
      // The host's map: `wear-m3` compiles against the served Wear Material 3 bundle, and a served
      // Android bundle — `confetti-wear`'s — compiles on the Android daemon, as `ServeRunner`
      // resolves a pack's backend from its bundle's manifest.
      nativeTarget = {
        when (it) {
          "wear-m3" ->
            UiBuilderNativeTarget("wear-m3-catalog", UiBuilderGeneratedCompose.COMPOSE_ANDROID)
          "confetti-wear" -> UiBuilderNativeTarget(it, UiBuilderGeneratedCompose.COMPOSE_ANDROID)
          else -> UiBuilderNativeTarget(it, UiBuilderGeneratedCompose.COMPOSE_CMP)
        }
      },
      packs = PACKS,
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

  /**
   * A **Wear** pack, on the record-free road: `wear-m3` has no component record and its screen is
   * written by `WearScreenCodeExporter`, so a Confetti Wear node inside it has to be written by
   * that emitter from the pack's record — the one record this design touches.
   */
  @Test
  fun `a wear screen holding a wear pack component exports its call from the pack's record alone`() {
    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        executor().generate(wearScreenWithPackNode())
      )

    assertTrue(
      "import dev.johnoreilly.confetti.wear.components.SectionHeader" in generated.source,
      generated.source,
    )
    assertTrue("SectionHeader(" in generated.source, generated.source)
    assertTrue("text = \"Thursday\"" in generated.source, generated.source)
    // Still a Wear screen around it, and still the record-free one: the Wear emitter wrote the
    // scaffold, and the only record asked for was the pack's.
    assertTrue("ScreenScaffold(" in generated.source, generated.source)
    assertEquals(listOf("confetti-wear"), lookedUp)
  }

  @Test
  fun `a wear screen using no pack still touches no record`() {
    val document =
      wearScreenUiBuilderDocument("screen-1", pin("wear-m3"), environment).toDesignDocumentV1()

    assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(executor().generate(document))

    assertEquals(emptyList(), lookedUp)
  }

  @Test
  fun `the native lane compiles a wear pack design against the pack's android bundle`() {
    val rendered =
      assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane().render(wearScreenWithPackNode()))

    val request = submitted.single()
    assertEquals("confetti-wear", request.catalog)
    assertEquals(UiBuilderGeneratedCompose.COMPOSE_ANDROID, request.confType)
    assertTrue("SectionHeader(" in request.source, request.source)
    // Tagged through the pack component's own `Modifier`, so the overlay can find the row.
    assertTrue("Modifier.testTag(\"day\")" in request.source, request.source)
    assertTrue("day" in rendered.taggedNodeIds)
  }

  /**
   * A pack component whose required parameters the design cannot set — `SessionCard` takes a
   * nullable domain object and a callback — is written with the placeholders its record proved,
   * which for Confetti is the card's own loading skeleton.
   */
  @Test
  fun `a wear pack component fills what the design cannot set from the record's proven call`() {
    val document = wearScreenWithPackNode().withListItem("card", "confetti-wear/session-card")

    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        executor().generate(document)
      )

    assertTrue("session = null," in generated.source, generated.source)
    assertTrue("sessionSelected = {}," in generated.source, generated.source)
    assertTrue("isBookmarked = false," in generated.source, generated.source)
    assertTrue(
      "import dev.johnoreilly.confetti.wear.components.SessionCard" in generated.source,
      generated.source,
    )
  }

  @Test
  fun `a wear component the pack left out is refused by node, not compiled`() {
    val document =
      wearScreenWithPackNode().withListItem("chip", "confetti-wear/session-speaker-chip")

    val refused =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Refused>(
        executor().generate(document)
      )

    // `session-speaker-chip` is not in the pack — its record proved no call site for
    // `SessionSpeakerDetails` — so the Wear emitter meets it as a stranger and says so, by node.
    assertEquals(ScreenGeneratorComposeExportExecutor.UNEXPRESSIBLE_DOCUMENT, refused.code)
    assertTrue(
      "confetti-wear/session-speaker-chip" in refused.reasons.single(),
      refused.reasons.single(),
    )
    assertFalse(submitted.any { "SessionSpeakerChip" in it.source })
  }

  /** This screen with a second list item of [componentId] after the section header. */
  private fun DesignDocumentV1.withListItem(id: String, componentId: String): DesignDocumentV1 =
    copy(
      nodes =
        nodes +
          (id to DesignNodeV1(id = id, componentId = componentId)) +
          ("wear-list" to
            nodes.getValue("wear-list").copy(slots = mapOf("items" to listOf("day", id))))
    )

  /** The Wear screen template with a Confetti `SectionHeader` as its only list item. */
  private fun wearScreenWithPackNode(): DesignDocumentV1 {
    val base = wearScreenUiBuilderDocument("screen-1", pin("wear-m3"), environment)
    val list = base.nodes.getValue("wear-list")
    return base
      .copy(
        nodes =
          base.nodes +
            ("wear-list" to list.copy(slots = mapOf("items" to listOf("day")))) +
            ("day" to
              UiBuilderNode(
                id = "day",
                componentId = "confetti-wear/section-header",
                properties =
                  JsonObject(
                    mapOf(
                      "text" to
                        JsonObject(
                          mapOf(
                            "type" to JsonPrimitive("string"),
                            "value" to JsonPrimitive("Thursday"),
                          )
                        )
                    )
                  ),
              ))
      )
      .toDesignDocumentV1()
  }

  private fun pin(systemId: String): JsonObject =
    json
      .encodeToJsonElement(
        CatalogReferenceV1(
          systemId = systemId,
          catalogRevision = "candidate",
          capabilityDigest = "candidate",
          nativeRuntimeId = "candidate",
        )
      )
      .let { it as JsonObject }

  private val environment: JsonObject =
    json
      .encodeToJsonElement(
        DesignEnvironmentV1(
          widthDp = 192,
          heightDp = 192,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        )
      )
      .let { it as JsonObject }

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

  private companion object {
    val PACKS = setOf("confetti-mobile", "jetnews", "confetti-wear")
    val json = Json {
      classDiscriminator = "type"
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}
