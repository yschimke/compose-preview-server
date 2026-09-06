package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AlignModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A card's content is a box in the generated screen, as it is on the canvas.
 *
 * The record-driven lane used to compose a card's children straight under `Card`'s own
 * `ColumnScope`: two children stacked top to bottom, and a `matchParentSize` among them — the
 * Jetcaster podcast cards lay an image and a gradient under an aligned title — refused as out of
 * scope. The canvas and the capability exporter had always drawn `Card { Box { … } }`, so the lane
 * whose whole claim is fidelity was the one drawing a different card.
 *
 * Asserted against the shipped M3 record rather than the small test one, because the claim is about
 * what the real catalog's `Box` attests, and `matchParentSize` is the modifier that only compiles
 * once the projection knows the children sit in a `BoxScope`.
 */
class CardContentBoxExportTest {

  private val record: ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(File(RECORD).readText())

  private val executor =
    ScreenGeneratorComposeExportExecutor(
      { ComponentRecordSource.Lookup.Found(record) },
      ScreenGeneratorScreenFixture.PACKAGE_NAME,
    )

  @Test
  fun `a card's children are composed inside a Box, where matchParentSize and align resolve`() {
    val source = generate(card(FillMaxWidthModifierV1))

    assertTrue("Card(" in source, source)
    assertTrue("Box(modifier = Modifier.fillMaxWidth(), content = {" in source, source)
    assertTrue("Modifier.matchParentSize()" in source, source)
    assertTrue("Modifier.align(Alignment.BottomStart)" in source, source)
    assertTrue("androidx.compose.foundation.layout.Box" in source, source)
    // The box is the card's own content, not a node of the design: it carries no test tag.
    assertEquals(1, Regex("""Box\(""").findAll(source).count(), source)
  }

  /** The same rule the canvas and the capability exporter apply — see `cardContentFill` (#483). */
  @Test
  fun `the box fills only the axes the card was sized on`() {
    assertTrue(
      "Box(modifier = Modifier.fillMaxWidth(), content = {" in
        generate(card(FillMaxWidthModifierV1))
    )
    assertTrue(
      "Box(modifier = Modifier.fillMaxSize(), content = {" in
        generate(card(FillMaxWidthModifierV1, HeightModifierV1(heightDp = JsonPrimitive(200))))
    )
    assertTrue(
      "Box(modifier = Modifier.fillMaxHeight(), content = {" in
        generate(card(HeightModifierV1(heightDp = JsonPrimitive(200))))
    )
    assertTrue("Box(content = {" in generate(card()))
  }

  private fun generate(document: DesignDocumentV1): String {
    val generated = executor.generate(document)
    return assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        generated,
        generated.toString(),
      )
      .source
  }

  /** A card holding a `matchParentSize` backdrop and a title aligned to its bottom-start corner. */
  private fun card(vararg modifiers: DesignModifierV1): DesignDocumentV1 {
    val base = ScreenGeneratorScreenFixture.document()
    return base.copy(
      catalogPin = base.catalogPin.copy(systemId = "m3-catalog"),
      roots = listOf("card"),
      nodes =
        linkedMapOf(
          "card" to
            DesignNodeV1(
              id = "card",
              componentId = "m3/card",
              modifiers = modifiers.toList(),
              slots = mapOf("content" to listOf("backdrop", "title")),
            ),
          "backdrop" to
            DesignNodeV1(
              id = "backdrop",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("backdrop")),
              modifiers = listOf(MatchParentSizeModifierV1),
            ),
          "title" to
            DesignNodeV1(
              id = "title",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("title")),
              modifiers = listOf(AlignModifierV1(alignment = AlignmentV1.BOTTOM_START)),
            ),
        ),
    )
  }

  private companion object {
    const val RECORD = "../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
  }
}
