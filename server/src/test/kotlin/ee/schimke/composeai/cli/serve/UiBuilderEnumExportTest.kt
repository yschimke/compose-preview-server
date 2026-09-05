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
  fun `a card variant selects the component it names`() {
    // `m3/card`.`variant` is `Card`, `ElevatedCard` or `OutlinedCard` — three symbols behind one
    // catalog id, which is what made it a call-site decision rather than a value. It is a lookup
    // now, so the export reads as the component the designer picked.
    fun sourceFor(variant: String) =
      (ScreenExportGate.export(
          document(
            roots = listOf("card"),
            nodes =
              linkedMapOf(
                "card" to
                  DesignNodeV1(
                    id = "card",
                    componentId = "m3/card",
                    properties =
                      mapOf(
                        "variant" to EnumValueV1(variant),
                        "containerColor" to
                          ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1("surface"),
                      ),
                    slots = mapOf("content" to listOf("label")),
                  ),
                "label" to
                  DesignNodeV1(
                    id = "label",
                    componentId = "m3/text",
                    properties = mapOf("text" to StringValueV1("Latest")),
                  ),
              ),
          ),
          record,
        ) as ScreenExportGate.Outcome.Emitted)
        .source

    assertTrue("ElevatedCard(" in sourceFor("elevated"), sourceFor("elevated"))
    assertTrue("OutlinedCard(" in sourceFor("outlined"), sourceFor("outlined"))
    assertTrue("Card(" in sourceFor("filled"), sourceFor("filled"))
    // The defaults follow the component. All three factories return a `CardColors`, so the filled
    // one would compile on an elevated card and quietly give it the filled card's other roles.
    assertTrue("CardDefaults.elevatedCardColors(" in sourceFor("elevated"), sourceFor("elevated"))
    assertTrue("CardDefaults.outlinedCardColors(" in sourceFor("outlined"), sourceFor("outlined"))
    assertTrue("CardDefaults.cardColors(" in sourceFor("filled"), sourceFor("filled"))
  }

  @Test
  fun `a button style and an icon-button variant select their components`() {
    fun sourceFor(componentId: String, property: String, value: String) =
      (ScreenExportGate.export(
          document(
            roots = listOf("button"),
            nodes =
              linkedMapOf(
                "button" to
                  DesignNodeV1(
                    id = "button",
                    componentId = componentId,
                    properties = mapOf(property to EnumValueV1(value)),
                    slots = mapOf("content" to listOf("label")),
                  ),
                "label" to
                  DesignNodeV1(
                    id = "label",
                    componentId = "m3/text",
                    properties = mapOf("text" to StringValueV1("Go")),
                  ),
              ),
          ),
          record,
        ) as ScreenExportGate.Outcome.Emitted)
        .source

    // Twelve values, twelve components. `fab` is the one that is not a rename — see
    // `ScreenDocumentProjectionTest` for the slot scope that follows from it.
    assertTrue("FilledTonalButton(" in sourceFor("m3/button", "style", "filledTonal"))
    assertTrue("TextButton(" in sourceFor("m3/button", "style", "text"))
    assertTrue("FloatingActionButton(" in sourceFor("m3/button", "style", "fab"))
    assertTrue("FilledIconButton(" in sourceFor("m3/icon-button", "variant", "filled"))
    assertTrue("FilledTonalIconButton(" in sourceFor("m3/icon-button", "variant", "tonal"))
    assertTrue("OutlinedIconButton(" in sourceFor("m3/icon-button", "variant", "outlined"))
    assertTrue("IconButton(" in sourceFor("m3/icon-button", "variant", "standard"))
  }

  @Test
  fun `a text field and a progress indicator select their components`() {
    fun textField(variant: String) =
      ScreenExportGate.export(
        document(
          roots = listOf("field"),
          nodes =
            linkedMapOf(
              "field" to
                DesignNodeV1(
                  id = "field",
                  componentId = "m3/text-field",
                  properties =
                    mapOf("variant" to EnumValueV1(variant), "value" to StringValueV1("Sofia")),
                  // A nullable composable slot — `label` is `@Composable (() -> Unit)?` — which is
                  // the shape a bare `{ … }` has to satisfy for any of these to export at all.
                  slots = mapOf("label" to listOf("caption")),
                ),
              "caption" to
                DesignNodeV1(
                  id = "caption",
                  componentId = "m3/text",
                  properties = mapOf("text" to StringValueV1("Name")),
                ),
            ),
        ),
        record,
      )

    val filled =
      textField("filled") as? ScreenExportGate.Outcome.Emitted
        ?: error("refused: ${(textField("filled") as ScreenExportGate.Outcome.Refused).reasons}")
    assertTrue("""TextField(value = "Sofia"""" in filled.source, filled.source)
    assertTrue("label = {" in filled.source, filled.source)
    assertTrue(
      "OutlinedTextField(" in (textField("outlined") as ScreenExportGate.Outcome.Emitted).source
    )

    fun indicator(
      variant: String,
      vararg extra: Pair<String, ee.schimke.composeai.uibuilder.protocol.UiValueV1>,
    ) =
      ScreenExportGate.export(
        document(
          roots = listOf("bar"),
          nodes =
            linkedMapOf(
              "bar" to
                DesignNodeV1(
                  id = "bar",
                  componentId = "m3/progress-indicator",
                  properties = mapOf("variant" to EnumValueV1(variant)) + extra,
                )
            ),
        ),
        record,
      )

    assertTrue(
      "LinearProgressIndicator(" in (indicator("linear") as ScreenExportGate.Outcome.Emitted).source
    )
    assertTrue(
      "CircularProgressIndicator(" in
        (indicator("circular") as ScreenExportGate.Outcome.Emitted).source
    )
    // The determinate overload takes `progress: () -> Float`, and no value here is a lambda. The
    // refusal has to say that rather than "no parameter `progress`", which is true and misleading.
    val determinate =
      (indicator(
          "linear",
          "progress" to ee.schimke.composeai.uibuilder.protocol.DecimalValueV1(0.4),
        )
          as ScreenExportGate.Outcome.Refused)
        .reasons
    assertTrue(determinate.any { "no value in this vocabulary is a lambda" in it }, "$determinate")
  }

  @Test
  fun `a variant nothing selects still refuses as a variant, not as a missing entry`() {
    // `layout/supporting-pane-scaffold` is an adaptive API whose panes are not plain composable
    // slots, so nothing selects its `layoutMode`. The refusal must keep reading as a call-site
    // decision rather than as a table entry somebody could add — and must describe an adaptive
    // component's modes rather than a card's three callables.
    val refusals =
      ScreenExportGate.refusals(
        document(
          roots = listOf("panes"),
          nodes =
            linkedMapOf(
              "panes" to
                DesignNodeV1(
                  id = "panes",
                  componentId = "layout/supporting-pane-scaffold",
                  properties = mapOf("layoutMode" to EnumValueV1("expandedTwoPane")),
                )
            ),
        ),
        record,
      )

    assertTrue(
      refusals.any { "names a layout mode of one adaptive component rather than a value" in it },
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
                  slots = mapOf("children" to listOf("label", "list")),
                ),
              "label" to
                DesignNodeV1(
                  id = "label",
                  componentId = "m3/text",
                  // Unexpressible: a state read needs a `remember` preamble this projection does
                  // not emit. Enough to make the projection refuse, which is the precondition.
                  properties = mapOf("text" to StateValueV1("caption")),
                ),
              // `m3/icon` used to be the example here and is recorded now. `layout/lazy-column`
              // is still uncovered for a reason no table fixes — `items` is a `LazyListScope` DSL,
              // not a composable slot — so it is the durable one to test the layering with.
              "list" to DesignNodeV1(id = "list", componentId = "layout/lazy-column"),
            ),
        ),
        record,
      )

    assertTrue(refusals.any { "state variable `caption`" in it }, refusals.toString())
    assertTrue(
      refusals.any { it == "no component `layout/lazy-column` in this catalog" },
      refusals.toString(),
    )
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
