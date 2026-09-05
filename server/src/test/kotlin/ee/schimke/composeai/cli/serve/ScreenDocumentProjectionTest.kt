package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.protocol.AccessibilityV1
import ee.schimke.composeai.uibuilder.protocol.ClipModifierV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DimensionUnitV1
import ee.schimke.composeai.uibuilder.protocol.DimensionValueV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StateTruthyPredicateV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ToggleActionV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the projection **refuses**, node by node.
 *
 * The successful path is covered by the golden in [ScreenGeneratorComposeExportExecutorTest], which
 * is the better place for it: a golden shows the whole output at once and a per-value assertion
 * would only restate the table. What is worth asserting one at a time is each refusal, because each
 * one is a promise that a document holding that content produces an error naming it rather than
 * source that quietly omits it — which is exactly what the executor this replaced did.
 */
class ScreenDocumentProjectionTest {

  private fun document(vararg nodes: DesignNodeV1, roots: List<String> = listOf(nodes.first().id)) =
    ScreenGeneratorScreenFixture.document().copy(roots = roots, nodes = nodes.associateBy { it.id })

  private fun text(vararg properties: Pair<String, UiValueV1>) =
    DesignNodeV1(
      id = "text",
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1("hi")) + properties,
    )

  private fun dp(value: Long) =
    ScreenValue.Chain(
      receiver = ScreenValue.Whole(value),
      links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
      typeFqn = "androidx.compose.ui.unit.Dp",
    )

  /** Every reason a document produced, empty when it projected — for cases that assert both. */
  private fun reasonsFor(document: DesignDocumentV1): List<String> =
    when (val outcome = ScreenDocumentProjection.project(document)) {
      is ScreenDocumentProjection.Outcome.Refused -> outcome.reasons
      is ScreenDocumentProjection.Outcome.Projected -> emptyList()
    }

  private fun refusal(document: DesignDocumentV1): List<String> =
    (ScreenDocumentProjection.project(document) as ScreenDocumentProjection.Outcome.Refused).reasons

  private fun projected(document: DesignDocumentV1) =
    (ScreenDocumentProjection.project(document) as ScreenDocumentProjection.Outcome.Projected)
      .document

  @Test
  fun `a state read is refused by variable name, not dropped`() {
    assertEquals(
      listOf(
        "node `text`.`text` reads the state variable `query`, which needs a " +
          "`remember { mutableStateOf(…) }` preamble this projection does not emit"
      ),
      refusal(
        document(
          DesignNodeV1(
            id = "text",
            componentId = "m3/text",
            properties = mapOf("text" to StateValueV1("query")),
          )
        )
      ),
    )
  }

  @Test
  fun `an event binding is refused by event name`() {
    assertEquals(
      listOf(
        "node `text` binds the event(s) click, which need an event adapter this projection has " +
          "no channel for"
      ),
      refusal(
        document(text().copy(eventBindings = mapOf("click" to listOf(ToggleActionV1("expanded")))))
      ),
    )
  }

  @Test
  fun `a conditional node is refused`() {
    assertEquals(
      listOf("node `text` is conditional on a predicate, which reads state"),
      refusal(document(text().copy(predicate = StateTruthyPredicateV1("visible")))),
    )
  }

  @Test
  fun `an asset binding is refused by slot name`() {
    assertEquals(
      listOf(
        "node `text` binds the asset(s) artwork, which need a caller-supplied artwork adapter"
      ),
      refusal(document(text().copy(assetBindings = mapOf("artwork" to "podcast-1")))),
    )
  }

  @Test
  fun `accessibility is refused as the modifier it would have to become`() {
    assertEquals(
      listOf(
        "node `text` sets accessibility, which is a `semantics {}` modifier the component record " +
          "cannot type-check"
      ),
      refusal(document(text().copy(accessibility = AccessibilityV1(label = "Schedule")))),
    )
  }

  @Test
  fun `a pixel dimension is refused because it needs the composition's density`() {
    assertEquals(
      listOf(
        "node `text`.`color` is in pixels, which needs the composition's density to become a `Dp`"
      ),
      refusal(document(text("color" to DimensionValueV1(JsonPrimitive(12), DimensionUnitV1.PX)))),
    )
  }

  @Test
  fun `an unknown colour token names itself`() {
    assertEquals(
      listOf(
        "node `text`.`color` is the colour token `brandTeal`, which is not one this catalog's " +
          "theme defines"
      ),
      refusal(document(text("color" to ColorTokenValueV1("brandTeal")))),
    )
  }

  @Test
  fun `a colour that is not hex is refused with the value quoted`() {
    assertEquals(
      listOf(
        "node `text`.`color` is the colour `rebeccapurple`, which is not #RRGGBB or #AARRGGBB"
      ),
      refusal(document(text("color" to ColorValueV1("rebeccapurple")))),
    )
  }

  @Test
  fun `a six-digit colour is made opaque rather than transparent`() {
    val projected = projected(document(text("color" to ColorValueV1("#6750A4"))))
    val color = projected.root.arguments.getValue("color") as ScreenValue.Construct
    // 0xFF6750A4. Left at zero alpha the colour would render invisible, which is a bug that looks
    // like a theme problem rather than like a parse problem.
    assertEquals(ScreenValue.Whole(4284960932L), color.positional.single())
  }

  @Test
  fun `a mapped enum value becomes the Kotlin member the table names`() {
    // The wire spelling is lower-camel and the member is not, which is why appending the document's
    // value to the parameter's recorded type never compiled and why `ENUM_MEMBERS` exists.
    val align =
      projected(document(text("textAlign" to EnumValueV1("center"))))
        .root
        .arguments
        .getValue("textAlign") as ScreenValue.Reference
    assertEquals("androidx.compose.ui.text.style.TextAlign", align.rootFqn)
    assertEquals(listOf("Center"), align.members)
    assertEquals("androidx.compose.ui.text.style.TextAlign", align.typeFqn)
  }

  @Test
  fun `a variant nothing selects is refused for its own reason, not a card's`() {
    // A variant property is not a value at all. Where `COMPONENT_VARIANTS` says which component it
    // names, the projection selects it; where nothing does, this is the sentence — and it has to
    // describe **this** property. It used to say "the catalog spells three Compose components as
    // one id", which was true of `m3/card` and has never been true of `layoutMode`: `adaptive`,
    // `expandedTwoPane`, `singlePane` and `twoPane` are modes of one adaptive component. The
    // reason is authored per entry now, which is what keeps that from happening again.
    assertEquals(
      listOf(
        "node `panes`.`layoutMode` is `expandedTwoPane`, which names a layout mode of one " +
          "adaptive component rather than a value: `SupportingPaneScaffold` decides how many " +
          "panes to show from a scaffold directive and the window it is measured in, so there " +
          "is no parameter for a mode to be written to"
      ),
      refusal(
        document(
          DesignNodeV1(
            id = "panes",
            componentId = "layout/supporting-pane-scaffold",
            properties = mapOf("layoutMode" to EnumValueV1("expandedTwoPane")),
          )
        )
      ),
    )
  }

  @Test
  fun `a button style selects the component, and a fab is not a Button under another name`() {
    // Eleven of the twelve variants are the same signature under another name. `fab` is not:
    // `FloatingActionButton`'s content slot has no receiver where `Button`'s is a `RowScope`, so a
    // weight that is legal in a `TextButton` must refuse inside a fab. Reading the scope from the
    // catalog id rather than from the variant would have emitted it against a receiver that is not
    // there.
    fun weightInside(style: String) =
      reasonsFor(
        document(
          DesignNodeV1(
            id = "button",
            componentId = "m3/button",
            properties = mapOf("style" to EnumValueV1(style)),
            slots = mapOf("content" to listOf("text")),
          ),
          text("weight" to DecimalValueV1(1.0)),
          roots = listOf("button"),
        )
      )
    assertEquals(emptyList(), weightInside("text"))
    assertEquals(
      listOf(
        "node `text`.`weight` is a layout weight, which `Modifier.weight` supplies from a row's " +
          "or column's scope; this node sits at the root, which has no receiver"
      ),
      weightInside("fab"),
    )
  }

  @Test
  fun `an enum value the table does not carry is refused, and names the ones it does`() {
    // The half worth keeping from the behaviour before the table: a value with no member behind it
    // is refused by name rather than emitting a reference that does not exist.
    assertEquals(
      listOf(
        "node `text`.`textAlign` is the enum value `middle`, which is not one of " +
          "center, end, justify, start"
      ),
      refusal(document(text("textAlign" to EnumValueV1("middle")))),
    )
  }

  @Test
  fun `a property whose values are an enumeration is read the same through either wrapper`() {
    // A `string` wrapper on an `allowedValues` property is the legacy spelling the reducer now
    // rejects on a write (#339). Documents already hold it, already render, and refused here with
    // a type error naming `TextStyle` — a message about the wrapper's consequence rather than the
    // wrapper. Both spellings resolve through the one table instead.
    assertEquals(
      projected(document(text("textAlign" to EnumValueV1("center")))).root.arguments,
      projected(document(text("textAlign" to StringValueV1("center")))).root.arguments,
    )
  }

  @Test
  fun `an icon key becomes a chain, because the icon is an extension property`() {
    // `androidx.compose.material.icons.Icons.Filled.Star` written as one qualified path does not
    // resolve: the member is an extension on `Icons.Filled` declared in a different package, so it
    // has to be imported and read by its simple name.
    val icon =
      projected(
          document(
            DesignNodeV1(
              id = "icon",
              componentId = "m3/icon",
              properties = mapOf("iconKey" to EnumValueV1("star")),
            )
          )
        )
        .root
        .arguments
        .getValue("imageVector") as ScreenValue.Chain
    assertEquals(
      listOf(ChainLink("androidx.compose.material.icons.filled.Star", property = true)),
      icon.links,
    )
    assertEquals("androidx.compose.ui.graphics.vector.ImageVector", icon.typeFqn)
  }

  @Test
  fun `a weight in a column becomes a scoped Modifier weight`() {
    // Both halves of what made this inexpressible: the link is scoped to the slot the node sits in
    // (`ColumnScope`, claimed and checked rather than assumed), and its argument is a `Float`,
    // because a nested `Fractional` renders as a `Double` and `weight(1.0)` does not compile.
    val column =
      projected(
        document(
          DesignNodeV1(
            id = "column",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("text")),
          ),
          text("weight" to DecimalValueV1(1.0)),
          roots = listOf("column"),
        )
      )
    val child = column.root.slots.getValue("content").single()
    assertFalse("weight" in child.arguments)
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.ColumnScope.weight",
          positional = listOf(ScreenValue.Fractional32(1f)),
          receiverScopeFqn = "androidx.compose.foundation.layout.ColumnScope",
        )
      ),
      (child.arguments.getValue("modifier") as ScreenValue.Chain).links,
    )
  }

  @Test
  fun `a weight outside a row or column is refused, and says where the node is`() {
    // `m3/surface`'s content slot has no receiver, so there is no `weight` to call. The refusal
    // names the placement rather than the property, because the property is fine and the placement
    // is the thing a designer can change.
    assertEquals(
      listOf(
        "node `text`.`weight` is a layout weight, which `Modifier.weight` supplies from a row's " +
          "or column's scope; this node sits at the root, which has no receiver"
      ),
      refusal(document(text("weight" to DecimalValueV1(1.0)))),
    )
  }

  @Test
  fun `matchParentSize resolves inside a box and is refused outside one`() {
    val box =
      projected(
        document(
          DesignNodeV1(
            id = "box",
            componentId = "layout/box",
            slots = mapOf("children" to listOf("text")),
          ),
          text().copy(modifiers = listOf(MatchParentSizeModifierV1)),
          roots = listOf("box"),
        )
      )
    val child = box.root.slots.getValue("content").single()
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.BoxScope.matchParentSize",
          receiverScopeFqn = "androidx.compose.foundation.layout.BoxScope",
        )
      ),
      (child.arguments.getValue("modifier") as ScreenValue.Chain).links,
    )
    assertEquals(
      listOf(
        "node `text` uses `matchParentSize`, which is declared on `BoxScope` and is in scope only " +
          "inside a `layout/box` slot; this node sits at the root, which has no receiver"
      ),
      refusal(document(text().copy(modifiers = listOf(MatchParentSizeModifierV1)))),
    )
  }

  @Test
  fun `an icon size becomes a modifier link, because Icon has no such parameter`() {
    val icon =
      projected(
        document(
          DesignNodeV1(
            id = "icon",
            componentId = "m3/icon",
            properties = mapOf("iconKey" to EnumValueV1("star"), "sizeDp" to DecimalValueV1(24.0)),
          )
        )
      )
    assertFalse("sizeDp" in icon.root.arguments)
    assertEquals(
      listOf(ChainLink("androidx.compose.foundation.layout.size", listOf(dp(24)))),
      (icon.root.arguments.getValue("modifier") as ScreenValue.Chain).links,
    )
  }

  @Test
  fun `a one-axis size becomes width or height, never a one-named-axis size call`() {
    // `size(size: Dp)` names its parameter `size` and `size(width, height)` requires both, so
    // `.size(width = 120.dp)` compiles as neither.
    val width =
      projected(
          document(text().copy(modifiers = listOf(SizeModifierV1(JsonPrimitive(120), JsonNull))))
        )
        .root
        .arguments
        .getValue("modifier") as ScreenValue.Chain
    assertEquals(
      listOf(ChainLink("androidx.compose.foundation.layout.width", listOf(dp(120)))),
      width.links,
    )
    val height =
      projected(
          document(text().copy(modifiers = listOf(SizeModifierV1(JsonNull, JsonPrimitive(40)))))
        )
        .root
        .arguments
        .getValue("modifier") as ScreenValue.Chain
    assertEquals(
      listOf(ChainLink("androidx.compose.foundation.layout.height", listOf(dp(40)))),
      height.links,
    )
    val both =
      projected(
          document(
            text().copy(modifiers = listOf(SizeModifierV1(JsonPrimitive(10), JsonPrimitive(20))))
          )
        )
        .root
        .arguments
        .getValue("modifier") as ScreenValue.Chain
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.size",
          named = mapOf("width" to dp(10), "height" to dp(20)),
        )
      ),
      both.links,
    )
  }

  @Test
  fun `a clip to a theme shape resolves through MaterialTheme, not a constant`() {
    val chain =
      projected(document(text().copy(modifiers = listOf(ClipModifierV1(shape = "medium")))))
        .root
        .arguments
        .getValue("modifier") as ScreenValue.Chain
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.ui.draw.clip",
          positional =
            listOf(
              ScreenValue.Reference(
                "androidx.compose.material3.MaterialTheme",
                listOf("shapes", "medium"),
                typeFqn = "androidx.compose.ui.graphics.Shape",
              )
            ),
        )
      ),
      chain.links,
    )
  }

  @Test
  fun `a clip to a constant shape still resolves, and an unknown one names both sets`() {
    val chain =
      projected(document(text().copy(modifiers = listOf(ClipModifierV1(shape = "circle")))))
        .root
        .arguments
        .getValue("modifier") as ScreenValue.Chain
    assertEquals(
      "androidx.compose.foundation.shape.CircleShape",
      (chain.links.single().positional.single() as ScreenValue.Reference).rootFqn,
    )
    assertTrue(
      refusal(document(text().copy(modifiers = listOf(ClipModifierV1(shape = "squircle")))))
        .single()
        .startsWith("node `text` clips to shape `squircle`, which is neither a theme shape")
    )
  }

  @Test
  fun `the surface container roles the catalog offers are expressible`() {
    // All four appear in the checked-in Jetcaster and Confetti documents and are real
    // `MaterialTheme.colorScheme` accessors; the first table omitted every one of them.
    for (role in
      listOf(
        "surfaceContainer",
        "surfaceContainerLow",
        "surfaceContainerHigh",
        "surfaceContainerHighest",
      )) {
      val color =
        projected(document(text("color" to ColorTokenValueV1(role))))
          .root
          .arguments
          .getValue("color") as ScreenValue.Reference
      assertEquals(listOf("colorScheme", role), color.members, role)
    }
  }

  @Test
  fun `every unexpressible modifier is reported, not only the first`() {
    // The promise of `Outcome.Refused` is that a document can be fixed in one pass. A non-local
    // return out of the modifier loop broke it silently: the second problem only appeared after
    // the first was fixed and the export re-run.
    val reasons =
      refusal(
        document(
          text()
            .copy(
              modifiers =
                listOf(
                  MatchParentSizeModifierV1,
                  SizeModifierV1(JsonNull, JsonNull),
                  ClipModifierV1(shape = "squircle"),
                )
            )
        )
      )
    assertEquals(3, reasons.size, reasons.toString())
    assertTrue(reasons.any { it.contains("matchParentSize") }, reasons.toString())
    assertTrue(reasons.any { it.contains("neither a width nor a height") }, reasons.toString())
    assertTrue(reasons.any { it.contains("squircle") }, reasons.toString())
  }

  @Test
  fun `a padding with no numeric axis is refused, not emitted as an ambiguous call`() {
    // `Modifier.padding()` and `PaddingValues()` are each ambiguous between fully-defaulted
    // overloads, so an empty argument list compiles as none of them. Catalog validation checks the
    // modifier type and not its axes, and the renderer reads a bad number as zero, so a document
    // like this really does arrive here.
    assertEquals(
      listOf("node `text` pads with no axis that is a number"),
      refusal(
        document(
          text().copy(modifiers = listOf(PaddingModifierV1(JsonNull, JsonNull, JsonNull, JsonNull)))
        )
      ),
    )
    assertEquals(
      listOf("node `text`.`color` has no axis that is a number"),
      refusal(document(text("color" to PaddingValueV1(JsonNull, JsonNull, JsonNull, JsonNull)))),
    )
  }

  @Test
  fun `two roots are refused, because a screen body is one expression`() {
    assertEquals(
      listOf(
        "the document has 2 roots; a generated screen body needs exactly one, so wrap them in a " +
          "layout component in the builder"
      ),
      refusal(
        document(
          text(),
          DesignNodeV1(
            id = "other",
            componentId = "m3/text",
            properties = mapOf("text" to StringValueV1("two")),
          ),
          roots = listOf("text", "other"),
        )
      ),
    )
  }

  @Test
  fun `a node the document does not define is refused`() {
    assertEquals(
      listOf("the document references node `absent`, which it does not define"),
      refusal(document(text(), roots = listOf("absent"))),
    )
  }

  @Test
  fun `a slot cycle is refused rather than recursed into`() {
    assertEquals(
      listOf("node `card` contains itself through its slots"),
      refusal(
        document(
          DesignNodeV1(
            id = "card",
            componentId = "m3/card",
            slots = mapOf("content" to listOf("card")),
          )
        )
      ),
    )
  }

  @Test
  fun `a screen name comes from the title, with an identifier's positional rule applied`() {
    val document = ScreenGeneratorScreenFixture.document()
    assertEquals("ScheduleOperations", ScreenDocumentProjection.screenNameFor(document))
    assertEquals(
      "Screen2026Review",
      ScreenDocumentProjection.screenNameFor(document.copy(title = "2026 review")),
    )
    assertEquals(
      "GeneratedScreen",
      ScreenDocumentProjection.screenNameFor(document.copy(title = "—")),
    )
  }

  @Test
  fun `every refusal in one document is reported, not just the first`() {
    val reasons =
      refusal(
        document(
          DesignNodeV1(
            id = "text",
            componentId = "m3/text",
            properties =
              mapOf("text" to StateValueV1("query"), "color" to ColorTokenValueV1("brandTeal")),
            predicate = StateTruthyPredicateV1("visible"),
          )
        )
      )
    assertEquals(3, reasons.size, reasons.toString())
    assertTrue(reasons.any { it.contains("state variable `query`") }, reasons.toString())
    assertTrue(reasons.any { it.contains("brandTeal") }, reasons.toString())
    assertTrue(reasons.any { it.contains("predicate") }, reasons.toString())
  }

  @Test
  fun `a multi-root document reports what is wrong inside the roots too`() {
    // Refusing on the count alone made this two exports: wrap the roots, export again, discover
    // the padding. `Outcome.Refused` promises one pass.
    val document =
      ScreenGeneratorScreenFixture.document().let { base ->
        base.copy(
          roots = listOf("heading", "time"),
          nodes =
            base.nodes +
              ("heading" to
                base.nodes.getValue("heading").copy(modifiers = listOf(MatchParentSizeModifierV1))),
        )
      }
    val reasons =
      assertIs<ScreenDocumentProjection.Outcome.Refused>(ScreenDocumentProjection.project(document))
        .reasons
    assertTrue(reasons.any { it.contains("2 roots") }, reasons.toString())
    assertTrue(reasons.any { it.contains("matchParentSize") }, reasons.toString())
  }

  @Test
  fun `a dimension past the Int range keeps a receiver that has a dp extension`() {
    // `2147483648.dp` does not compile: Compose declares `dp` on Int, Double and Float, never on
    // Long. It used to be emitted as a clean success.
    val document =
      ScreenGeneratorScreenFixture.document().let { base ->
        base.copy(
          roots = listOf("heading"),
          nodes =
            base.nodes +
              ("heading" to
                base.nodes
                  .getValue("heading")
                  .copy(
                    modifiers =
                      listOf(
                        SizeModifierV1(widthDp = JsonPrimitive(2147483648L), heightDp = JsonNull)
                      )
                  )),
        )
      }
    val projected =
      assertIs<ScreenDocumentProjection.Outcome.Projected>(
        ScreenDocumentProjection.project(document)
      )
    val rendered = projected.document.toString()
    assertTrue(rendered.contains("Fractional"), rendered)
    assertFalse(rendered.contains("Whole(value=2147483648)"), rendered)
  }

  @Test
  fun `a dimension that cannot survive the narrowing to Float is refused`() {
    // `1e100.dp` compiles — Compose declares `dp` on `Double` too — and evaluates to
    // `Float.POSITIVE_INFINITY`, because `Dp` is a value class over `Float`. A success carrying a
    // number the design never contained is worse than a refusal.
    val document =
      ScreenGeneratorScreenFixture.document().let { base ->
        base.copy(
          roots = listOf("heading"),
          nodes =
            base.nodes +
              ("heading" to
                base.nodes
                  .getValue("heading")
                  .copy(
                    properties =
                      base.nodes.getValue("heading").properties +
                        ("size" to DimensionValueV1(JsonPrimitive(1e100), DimensionUnitV1.DP))
                  )),
        )
      }
    val reasons =
      assertIs<ScreenDocumentProjection.Outcome.Refused>(ScreenDocumentProjection.project(document))
        .reasons
    assertTrue(reasons.any { it.contains("narrowing to `Float`") }, reasons.toString())
  }

  @Test
  fun `a colour without the hash is refused, because the renderer reads it as a token`() {
    val document =
      ScreenGeneratorScreenFixture.document().let { base ->
        base.copy(
          roots = listOf("time"),
          nodes =
            base.nodes +
              ("time" to
                base.nodes
                  .getValue("time")
                  .copy(properties = mapOf("color" to ColorValueV1("6750A4")))),
        )
      }
    val reasons =
      assertIs<ScreenDocumentProjection.Outcome.Refused>(ScreenDocumentProjection.project(document))
        .reasons
    assertTrue(reasons.any { it.contains("`#6750A4`") }, reasons.toString())
  }
}
