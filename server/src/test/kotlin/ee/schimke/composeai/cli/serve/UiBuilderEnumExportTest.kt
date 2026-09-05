package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Enum values, and what a refusal list is allowed to leave out.
 *
 * ## Why this is separate from [M3CatalogComponentRecordTest]
 *
 * That file tests the **record** — that every id it claims is one the catalog declares, and that a
 * screen built from covered ids generates. It builds its screen from `typographyToken` and
 * `colorToken` values, which is what the fixtures use and not what the builder writes: the live
 * editor authors a text style as `{"type":"enum","value":"bodyLarge"}`, and every such value was
 * refused. A 387-node design authored on `preview.coo.ee` produced 119 refusals and no Kotlin, of
 * which 89 were this one cause.
 *
 * So the case under test here is the **document a person actually authors**, and the two halves it
 * broke on: the value, and the honesty of the list that reported it.
 */
class UiBuilderEnumExportTest {

  private val record: ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      File("../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").readText()
    )

  private fun document(roots: List<String>, nodes: Map<String, DesignNodeV1>) =
    ScreenGeneratorScreenFixture.document().copy(roots = roots, nodes = nodes)

  @Test
  fun `a text style, weight and row alignment authored as enums become Kotlin`() {
    val source =
      ScreenExportGate.export(
        document(
          roots = listOf("row"),
          nodes =
            linkedMapOf(
              "row" to
                DesignNodeV1(
                  id = "row",
                  componentId = "layout/row",
                  properties = mapOf("verticalAlignment" to EnumValueV1("center")),
                  slots = mapOf("children" to listOf("label")),
                ),
              "label" to
                DesignNodeV1(
                  id = "label",
                  componentId = "m3/text",
                  properties =
                    mapOf(
                      "text" to StringValueV1("Sessions"),
                      "style" to EnumValueV1("bodyLarge"),
                      "fontWeight" to EnumValueV1("semiBold"),
                      "textAlign" to EnumValueV1("center"),
                      "overflow" to EnumValueV1("ellipsis"),
                    ),
                ),
            ),
        ),
        record,
      )

    val emitted =
      source as? ScreenExportGate.Outcome.Emitted
        ?: error("refused: ${(source as ScreenExportGate.Outcome.Refused).reasons}")

    // Each of these is a value the wire spells lower-camel and Kotlin does not, which is the whole
    // reason the derivation was refused rather than case-corrected.
    assertTrue("MaterialTheme.typography.bodyLarge" in emitted.source, emitted.source)
    assertTrue("FontWeight.SemiBold" in emitted.source, emitted.source)
    assertTrue("TextAlign.Center" in emitted.source, emitted.source)
    assertTrue("TextOverflow.Ellipsis" in emitted.source, emitted.source)
    // `center` means two different members on two different parameters, which is why the table is
    // keyed by component and property rather than by value.
    assertTrue("Alignment.CenterVertically" in emitted.source, emitted.source)
  }

  @Test
  fun `the same style spelled as an enum and as a typography token agree`() {
    // Both spellings are accepted by the reducer and both render, so an export that resolved them
    // differently would be a difference no author could see coming. One table, one answer.
    fun sourceFor(style: ee.schimke.composeai.uibuilder.protocol.UiValueV1) =
      (ScreenExportGate.export(
          document(
            roots = listOf("label"),
            nodes =
              linkedMapOf(
                "label" to
                  DesignNodeV1(
                    id = "label",
                    componentId = "m3/text",
                    properties = mapOf("text" to StringValueV1("Peak hour"), "style" to style),
                  )
              ),
          ),
          record,
        ) as ScreenExportGate.Outcome.Emitted)
        .source

    assertEquals(
      sourceFor(ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1("titleMedium")),
      sourceFor(EnumValueV1("titleMedium")),
    )
  }

  @Test
  fun `a variant-selecting property refuses as a variant, not as a missing entry`() {
    // `m3/card`.`variant` is `Card`, `ElevatedCard` or `OutlinedCard` — three symbols behind one
    // catalog id. No table of members can express that, and a refusal that reads like a missing
    // table entry would send someone to add one.
    val refusals =
      ScreenExportGate.refusals(
        document(
          roots = listOf("card"),
          nodes =
            linkedMapOf(
              "card" to
                DesignNodeV1(
                  id = "card",
                  componentId = "m3/card",
                  properties = mapOf("variant" to EnumValueV1("filled")),
                  slots = mapOf("content" to emptyList()),
                )
            ),
        ),
        record,
      )

    assertTrue(
      refusals.any { "names a component variant rather than a value" in it },
      refusals.toString(),
    )
  }

  @Test
  fun `an unrecorded component is reported in the same pass as an unexpressible value`() {
    // The layering this closes: the projection refuses first and the generator never runs, so a
    // design holding a component with no record said nothing about it while any property anywhere
    // was unexpressible. Both classes, one pass, or a refusal list is the cost of reaching the
    // next list rather than the cost of fixing the export.
    val refusals =
      ScreenExportGate.refusals(
        document(
          roots = listOf("row"),
          nodes =
            linkedMapOf(
              "row" to
                DesignNodeV1(
                  id = "row",
                  componentId = "layout/row",
                  slots = mapOf("children" to listOf("label", "icon")),
                ),
              "label" to
                DesignNodeV1(
                  id = "label",
                  componentId = "m3/text",
                  // Unexpressible: a state read needs a `remember` preamble this projection does
                  // not emit. Enough to make the projection refuse, which is the precondition.
                  properties = mapOf("text" to StateValueV1("caption")),
                ),
              "icon" to
                DesignNodeV1(
                  id = "icon",
                  componentId = "m3/icon",
                  properties = mapOf("iconKey" to EnumValueV1("star")),
                ),
            ),
        ),
        record,
      )

    assertTrue(refusals.any { "state variable `caption`" in it }, refusals.toString())
    assertTrue(refusals.any { it == "no component `m3/icon` in this catalog" }, refusals.toString())
  }

  @Test
  fun `a component with a record is not reported as unproven`() {
    // The other half of the same claim: adding the call-site pass must not invent a refusal for
    // every component on a document that merely failed to project.
    val refusals =
      ScreenExportGate.refusals(
        document(
          roots = listOf("label"),
          nodes =
            linkedMapOf(
              "label" to
                DesignNodeV1(
                  id = "label",
                  componentId = "m3/text",
                  properties = mapOf("text" to StateValueV1("caption")),
                )
            ),
        ),
        record,
      )

    assertTrue(refusals.none { "no component" in it }, refusals.toString())
  }
}
