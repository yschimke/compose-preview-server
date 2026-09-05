package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.protocol.AlignHorizontalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignVerticalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignmentV1
import ee.schimke.composeai.uibuilder.protocol.AlphaModifierV1
import ee.schimke.composeai.uibuilder.protocol.AspectRatioModifierV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BorderModifierV1
import ee.schimke.composeai.uibuilder.protocol.ClipModifierV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxHeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightInModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.OffsetModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.RotateModifierV1
import ee.schimke.composeai.uibuilder.protocol.ScaleModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShadowModifierV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.TestTagModifierV1
import ee.schimke.composeai.uibuilder.protocol.VerticalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.VerticalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.WeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthInModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.WrapContentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.ZIndexModifierV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every modifier the builder can author, and what it exports as.
 *
 * The catalog admits a modifier onto a component by **type** — `modifierCapabilities` is a list of
 * type names — so any type the projection has no branch for refuses a document the builder was
 * happy to write. Six of the twenty-eight had branches, which is why a design carrying a
 * `background`, a `border`, a `width` or an `align` came back as "which this projection has no
 * expression for" however carefully it was drawn.
 *
 * The first test below is the one that keeps that from happening again: it is a list of every
 * subtype, and a modifier added to `ui-builder-protocol` without a branch here lands in the
 * catch-all it asserts against. The rest assert the argument shapes that are easy to get subtly
 * wrong — a `Float` where the API takes one, a named axis Compose does not declare, a scope a
 * member extension is not in.
 */
class DesignModifierExportTest {

  private fun document(vararg nodes: DesignNodeV1, roots: List<String> = listOf(nodes.first().id)) =
    ScreenGeneratorScreenFixture.document().copy(roots = roots, nodes = nodes.associateBy { it.id })

  private fun text(vararg modifiers: DesignModifierV1) =
    DesignNodeV1(
      id = "text",
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1("hi")),
      modifiers = modifiers.toList(),
    )

  private fun reasonsFor(document: DesignDocumentV1): List<String> =
    when (val outcome = ScreenDocumentProjection.project(document)) {
      is ScreenDocumentProjection.Outcome.Refused -> outcome.reasons
      is ScreenDocumentProjection.Outcome.Projected -> emptyList()
    }

  private fun projected(document: DesignDocumentV1) =
    (ScreenDocumentProjection.project(document) as ScreenDocumentProjection.Outcome.Projected)
      .document

  /** The links one node's modifier list becomes, at the root where no receiver is in scope. */
  private fun links(vararg modifiers: DesignModifierV1): List<ChainLink> {
    val document = document(text(*modifiers))
    assertEquals(emptyList(), reasonsFor(document), modifiers.toList().toString())
    return (projected(document).root.arguments.getValue("modifier") as ScreenValue.Chain).links
  }

  /** The links a node placed inside `container`'s slot becomes, so a scoped modifier resolves. */
  private fun scopedLinks(container: String, vararg modifiers: DesignModifierV1): List<ChainLink> {
    val document =
      document(
        DesignNodeV1(
          id = "container",
          componentId = container,
          slots = mapOf("children" to listOf("text")),
        ),
        text(*modifiers),
        roots = listOf("container"),
      )
    assertEquals(emptyList(), reasonsFor(document), modifiers.toList().toString())
    val child = projected(document).root.slots.getValue("content").single()
    return (child.arguments.getValue("modifier") as ScreenValue.Chain).links
  }

  private fun dp(value: Long) =
    ScreenValue.Chain(
      receiver = ScreenValue.Whole(value),
      links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
      typeFqn = "androidx.compose.ui.unit.Dp",
    )

  @Test
  fun `no modifier kind falls through to the catch-all, and only the scoped ones refuse at a root`() {
    // Two claims in one loop, because they are the same claim from either side. Nothing reaches
    // the `else` branch — that is the regression this file exists for — and what does refuse at
    // the root refuses for a *reason about placement*: `weight`, the three `align`s and
    // `matchParentSize` are members of a slot's receiver, and the two scrolls need a
    // `rememberScrollState()` no value in this vocabulary is.
    val refused = mutableListOf<String>()
    for (modifier in EVERY_MODIFIER) {
      val reasons = reasonsFor(document(text(modifier)))
      val name = modifier::class.simpleName!!
      assertTrue(
        reasons.none { it.contains("has no expression for") },
        "$name reached the catch-all: $reasons",
      )
      if (reasons.isNotEmpty()) refused += name
    }
    assertEquals(
      listOf(
        "AlignHorizontalModifierV1",
        "AlignModifierV1",
        "AlignVerticalModifierV1",
        "HorizontalScrollModifierV1",
        "MatchParentSizeModifierV1",
        "VerticalScrollModifierV1",
        "WeightModifierV1",
      ),
      refused.sorted(),
    )
  }

  @Test
  fun `a scroll is refused as the remembered state it needs, not as an unknown modifier`() {
    // The one pair that stays refused on purpose. `verticalScroll` takes a `ScrollState`, which is
    // produced by `rememberScrollState()` — a `remember { … }` preamble, and no `ScreenValue` is
    // one. Naming that is the difference between a designer knowing to lay the screen out another
    // way and a designer filing a bug about a missing table entry.
    for ((modifier, name) in
      listOf(
        VerticalScrollModifierV1 to "verticalScroll",
        HorizontalScrollModifierV1 to "horizontalScroll",
      )) {
      assertEquals(
        listOf(
          "node `text` uses `$name`, which takes a `ScrollState` from `rememberScrollState()` — " +
            "a `remember { … }` preamble this projection does not emit"
        ),
        reasonsFor(document(text(modifier))),
      )
    }
  }

  @Test
  fun `the size family becomes the call Compose actually declares`() {
    assertEquals(
      listOf(
        ChainLink("androidx.compose.foundation.layout.fillMaxHeight"),
        ChainLink("androidx.compose.foundation.layout.width", positional = listOf(dp(48))),
        ChainLink("androidx.compose.foundation.layout.height", positional = listOf(dp(24))),
        ChainLink(
          "androidx.compose.foundation.layout.widthIn",
          named = mapOf("min" to dp(8), "max" to dp(96)),
        ),
        // One bound, not two: `heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified)` reads
        // an omitted bound as unconstrained, which is what a document that set only a max means.
        ChainLink("androidx.compose.foundation.layout.heightIn", named = mapOf("max" to dp(64))),
        ChainLink(
          "androidx.compose.foundation.layout.offset",
          named = mapOf("x" to dp(4), "y" to dp(2)),
        ),
      ),
      links(
        FillMaxHeightModifierV1,
        WidthModifierV1(JsonPrimitive(48)),
        HeightModifierV1(JsonPrimitive(24)),
        WidthInModifierV1(JsonPrimitive(8), JsonPrimitive(96)),
        HeightInModifierV1(null, JsonPrimitive(64)),
        OffsetModifierV1(JsonPrimitive(4), JsonPrimitive(2)),
      ),
    )
  }

  @Test
  fun `a bound with no number at all is refused, not emitted as an unconstrained call`() {
    // `widthIn()` compiles and constrains nothing, which is not what a document holding two
    // unusable numbers meant — the same reason `Modifier.padding()` is refused rather than
    // emitted. `offset()` is the same shape and gets the same answer.
    assertEquals(
      listOf("node `text` constrains `widthIn` with neither a min nor a max that is a number"),
      reasonsFor(document(text(WidthInModifierV1(JsonNull, null)))),
    )
    assertEquals(
      listOf("node `text` offsets by neither an x nor a y that is a number"),
      reasonsFor(document(text(OffsetModifierV1(JsonNull, JsonNull)))),
    )
  }

  @Test
  fun `every modifier taking a Float takes one, not a Double`() {
    // `alpha(0.5)` does not compile: these are `Float` parameters, and a nested `Fractional`
    // renders as a `Double`. It is the same narrowing `Modifier.weight` needed (#5212 upstream),
    // and five more modifiers were waiting on it.
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.ui.draw.alpha",
          positional = listOf(ScreenValue.Fractional32(0.5f)),
        ),
        ChainLink(
          "androidx.compose.ui.draw.rotate",
          positional = listOf(ScreenValue.Fractional32(90f)),
        ),
        ChainLink(
          "androidx.compose.ui.draw.scale",
          named =
            mapOf(
              "scaleX" to ScreenValue.Fractional32(2f),
              "scaleY" to ScreenValue.Fractional32(0.5f),
            ),
        ),
        ChainLink(
          "androidx.compose.ui.zIndex",
          positional = listOf(ScreenValue.Fractional32(3f)),
        ),
        ChainLink(
          "androidx.compose.foundation.layout.aspectRatio",
          positional = listOf(ScreenValue.Fractional32(1.5f)),
        ),
      ),
      links(
        AlphaModifierV1(JsonPrimitive(0.5)),
        RotateModifierV1(JsonPrimitive(90)),
        ScaleModifierV1(JsonPrimitive(2), JsonPrimitive(0.5)),
        ZIndexModifierV1(JsonPrimitive(3)),
        AspectRatioModifierV1(JsonPrimitive(1.5)),
      ),
    )
  }

  @Test
  fun `a number that does not survive Float is refused rather than exported as Infinity`() {
    assertEquals(
      listOf("node `text` sets `alpha` to 1.0E100, which does not survive `Float`"),
      reasonsFor(document(text(AlphaModifierV1(JsonPrimitive(1e100))))),
    )
    assertEquals(
      listOf("node `text` sets `rotate` to something that is not a number"),
      reasonsFor(document(text(RotateModifierV1(JsonNull)))),
    )
  }

  @Test
  fun `background border and shadow carry a colour and an optional shape`() {
    val purple =
      ScreenValue.Construct(
        callableFqn = "androidx.compose.ui.graphics.Color",
        positional = listOf(ScreenValue.Whole(0xFF6750A4L)),
        typeFqn = "androidx.compose.ui.graphics.Color",
      )
    val medium =
      ScreenValue.Reference(
        "androidx.compose.material3.MaterialTheme",
        listOf("shapes", "medium"),
        typeFqn = "androidx.compose.ui.graphics.Shape",
      )
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.background",
          named =
            mapOf(
              "color" to
                ScreenValue.Reference(
                  "androidx.compose.material3.MaterialTheme",
                  listOf("colorScheme", "primaryContainer"),
                  typeFqn = "androidx.compose.ui.graphics.Color",
                ),
              "shape" to medium,
            ),
        ),
        // No shape, so no argument: Compose's own default is `RectangleShape`, and writing it here
        // would be this projection inventing a value the document did not carry.
        ChainLink(
          "androidx.compose.foundation.border",
          named = mapOf("width" to dp(1), "color" to purple),
        ),
        ChainLink(
          "androidx.compose.ui.draw.shadow",
          named = mapOf("elevation" to dp(6), "clip" to ScreenValue.Bool(false)),
        ),
      ),
      links(
        BackgroundModifierV1(ColorTokenValueV1("primaryContainer"), shape = "medium"),
        BorderModifierV1(JsonPrimitive(1), ColorValueV1("#6750A4")),
        ShadowModifierV1(JsonPrimitive(6), clip = false),
      ),
    )
  }

  @Test
  fun `a shape nothing resolves is refused wherever it appears, not only on a clip`() {
    assertEquals(
      listOf(
        "node `text` fills with shape `squircle`, which is neither a theme shape (extraLarge, " +
          "extraSmall, large, medium, small) nor one of circle, rectangle"
      ),
      reasonsFor(document(text(BackgroundModifierV1(ColorValueV1("#000000"), shape = "squircle")))),
    )
  }

  @Test
  fun `a test tag a designer authored is the tag that is written`() {
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.ui.platform.testTag",
          positional = listOf(ScreenValue.Text("hero")),
        )
      ),
      links(TestTagModifierV1("hero")),
    )
  }

  @Test
  fun `wrapContentSize writes an alignment only when the document carried one`() {
    assertEquals(
      listOf(ChainLink("androidx.compose.foundation.layout.wrapContentSize")),
      links(WrapContentSizeModifierV1()),
    )
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.wrapContentSize",
          positional =
            listOf(
              ScreenValue.Reference(
                "androidx.compose.ui.Alignment",
                listOf("TopEnd"),
                typeFqn = "androidx.compose.ui.Alignment",
              )
            ),
        )
      ),
      links(WrapContentSizeModifierV1(AlignmentV1.TOP_END)),
    )
  }

  @Test
  fun `each align resolves in the one scope that declares it`() {
    // Three members called `align` with three different parameter types, and which one is legal is
    // decided by where the node sits rather than by anything about the node. Emitting the wrong
    // one is an unresolved reference, not a wrong picture, so it is checked here and again by the
    // generator against the record's `composableSlotReceiver`.
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.BoxScope.align",
          positional =
            listOf(
              ScreenValue.Reference(
                "androidx.compose.ui.Alignment",
                listOf("BottomEnd"),
                typeFqn = "androidx.compose.ui.Alignment",
              )
            ),
          receiverScopeFqn = "androidx.compose.foundation.layout.BoxScope",
        )
      ),
      scopedLinks("layout/box", AlignModifierV1(AlignmentV1.BOTTOM_END)),
    )
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.ColumnScope.align",
          positional =
            listOf(
              ScreenValue.Reference(
                "androidx.compose.ui.Alignment",
                listOf("CenterHorizontally"),
                typeFqn = "androidx.compose.ui.Alignment\$Horizontal",
              )
            ),
          receiverScopeFqn = "androidx.compose.foundation.layout.ColumnScope",
        )
      ),
      scopedLinks(
        "layout/column",
        AlignHorizontalModifierV1(HorizontalAlignmentV1.CENTER_HORIZONTALLY),
      ),
    )
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.RowScope.align",
          positional =
            listOf(
              ScreenValue.Reference(
                "androidx.compose.ui.Alignment",
                listOf("Bottom"),
                typeFqn = "androidx.compose.ui.Alignment\$Vertical",
              )
            ),
          receiverScopeFqn = "androidx.compose.foundation.layout.RowScope",
        )
      ),
      scopedLinks("layout/row", AlignVerticalModifierV1(VerticalAlignmentV1.BOTTOM)),
    )
  }

  @Test
  fun `an align in the wrong container names the container it needs and the slot it is in`() {
    // A horizontal align is a `ColumnScope` member, so it is a design mistake inside a row rather
    // than a gap in a table — and the refusal says which, because that is what a designer acts on.
    assertEquals(
      listOf(
        "node `text` aligns itself, which `Modifier.align` supplies from a column's scope; this " +
          "node sits in a `androidx.compose.foundation.layout.RowScope` slot"
      ),
      reasonsFor(
        document(
          DesignNodeV1(
            id = "container",
            componentId = "layout/row",
            slots = mapOf("children" to listOf("text")),
          ),
          text(AlignHorizontalModifierV1(HorizontalAlignmentV1.START)),
          roots = listOf("container"),
        )
      ),
    )
  }

  @Test
  fun `a weight authored as a modifier is the same link as one authored as a property`() {
    // The two spellings both reach the builder and mean one thing. `fill` exists only on the
    // modifier form, and is written only when the document set it — omitted, Compose's default of
    // `true` is what the property form has always meant.
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.RowScope.weight",
          positional = listOf(ScreenValue.Fractional32(2f)),
          named = mapOf("fill" to ScreenValue.Bool(false)),
          receiverScopeFqn = "androidx.compose.foundation.layout.RowScope",
        )
      ),
      scopedLinks("layout/row", WeightModifierV1(JsonPrimitive(2), fill = false)),
    )
    assertEquals(
      listOf(
        ChainLink(
          "androidx.compose.foundation.layout.ColumnScope.weight",
          positional = listOf(ScreenValue.Fractional32(1f)),
          receiverScopeFqn = "androidx.compose.foundation.layout.ColumnScope",
        )
      ),
      scopedLinks("layout/column", WeightModifierV1(JsonPrimitive(1), fill = null)),
    )
  }

  private companion object {
    /**
     * One of every `DesignModifierV1`, with values a real document would carry.
     *
     * Hand-listed rather than reflected over, because a constructed instance needs values a
     * reflection walk cannot invent — but the list is *checked* by reflection, in the last test
     * below. A subtype missing from here would be a hole in the coverage test rather than a
     * failure, which is the one thing a coverage test must not have.
     */
    val EVERY_MODIFIER: List<DesignModifierV1> =
      listOf(
        AlignHorizontalModifierV1(HorizontalAlignmentV1.START),
        AlignModifierV1(AlignmentV1.CENTER),
        AlignVerticalModifierV1(VerticalAlignmentV1.TOP),
        AlphaModifierV1(JsonPrimitive(0.5)),
        AspectRatioModifierV1(JsonPrimitive(1.5)),
        BackgroundModifierV1(ColorValueV1("#6750A4")),
        BorderModifierV1(JsonPrimitive(1), ColorValueV1("#6750A4")),
        ClipModifierV1("medium"),
        FillMaxHeightModifierV1,
        FillMaxSizeModifierV1,
        FillMaxWidthModifierV1,
        HeightInModifierV1(JsonPrimitive(8), JsonPrimitive(96)),
        HeightModifierV1(JsonPrimitive(24)),
        HorizontalScrollModifierV1,
        MatchParentSizeModifierV1,
        OffsetModifierV1(JsonPrimitive(4), JsonPrimitive(2)),
        PaddingModifierV1(JsonPrimitive(8), JsonPrimitive(8), JsonPrimitive(8), JsonPrimitive(8)),
        RotateModifierV1(JsonPrimitive(90)),
        ScaleModifierV1(JsonPrimitive(2), JsonPrimitive(2)),
        ShadowModifierV1(JsonPrimitive(6)),
        SizeModifierV1(JsonPrimitive(24), JsonPrimitive(24)),
        TestTagModifierV1("hero"),
        VerticalScrollModifierV1,
        WeightModifierV1(JsonPrimitive(1)),
        WidthInModifierV1(JsonPrimitive(8), JsonPrimitive(96)),
        WidthModifierV1(JsonPrimitive(48)),
        WrapContentSizeModifierV1(AlignmentV1.CENTER),
        ZIndexModifierV1(JsonPrimitive(3)),
      )
  }

  @Test
  fun `the list above holds every modifier the protocol declares`() {
    // The coverage test is only as good as this list, and a subtype added upstream would be
    // invisible to it. `DesignModifierV1` is sealed, so the JVM knows the real answer — this
    // module's tests are JVM-only even though the projection is not.
    assertEquals(
      DesignModifierV1::class.sealedSubclasses.mapNotNull { it.simpleName }.sorted(),
      EVERY_MODIFIER.map { it::class.simpleName!! }.sorted(),
    )
  }
}
