package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.VerticalScrollModifierV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A screen built from the palette **without special knowledge** produces Kotlin with no
 * diagnostics.
 *
 * That sentence is the whole of compose-preview-server#488, and it was false: three screens built
 * over MCP in one session — a news feed, a Google-app home, a Discord channel — each exported as a
 * file of comments until rebuilt out of the subset of the palette that happened to work. The subset
 * was not documented anywhere; the way to a working design was knowing which third of the palette
 * to avoid.
 *
 * The tests here are that subset's complement, one per gap the cluster named: every arrangement
 * value (#475), `fontStyle` and the four components the generator had no record for (#477), the
 * `verticalScroll` a feed column reaches for first (#481), and the `alignment` property #475 asked
 * to have checked in the same pass. The last test is the screen itself.
 */
class OrdinaryScreenExportTest {

  private val record: ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(File(RECORD).readText())

  private val catalog =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3-catalog", "candidate", "candidate"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
    )

  private fun document(roots: List<String>, nodes: Map<String, DesignNodeV1>) =
    ScreenGeneratorScreenFixture.document().copy(roots = roots, nodes = nodes)

  private fun label(
    id: String,
    text: String = "Label",
    vararg properties: Pair<String, UiValueV1>,
  ) =
    DesignNodeV1(
      id = id,
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1(text), *properties),
    )

  private fun source(document: DesignDocumentV1): String =
    when (val outcome = ScreenExportGate.export(document, record)) {
      is ScreenExportGate.Outcome.Emitted -> outcome.source
      is ScreenExportGate.Outcome.Refused -> error("refused: ${outcome.reasons}")
    }

  private fun refusals(document: DesignDocumentV1): List<String> =
    ScreenExportGate.refusals(document, record)

  /** A single layout holding one label, with the properties under test. */
  private fun layout(componentId: String, vararg properties: Pair<String, UiValueV1>) =
    document(
      roots = listOf("layout"),
      nodes =
        linkedMapOf(
          "layout" to
            DesignNodeV1(
              id = "layout",
              componentId = componentId,
              properties = mapOf(*properties),
              slots = mapOf("children" to listOf("label")),
            ),
          "label" to label("label"),
        ),
    )

  @Test
  fun `a row exports each of its six arrangements`() {
    // The regression #488 asks for by name. Every value the catalog allows on
    // `horizontalArrangement`, each as the `Arrangement` member it names — the `spaceBetween` of a
    // top bar, the `center` of a wordmark, the `spaceEvenly` of a navigation bar.
    for ((value, member) in
      listOf(
        "start" to "Start",
        "center" to "Center",
        "end" to "End",
        "spaceBetween" to "SpaceBetween",
        "spaceAround" to "SpaceAround",
        "spaceEvenly" to "SpaceEvenly",
      )) {
      val source = source(layout("layout/row", "horizontalArrangement" to EnumValueV1(value)))
      assertTrue("horizontalArrangement = Arrangement.$member" in source, "$value:\n$source")
      assertTrue("import androidx.compose.foundation.layout.Arrangement" in source, source)
    }
  }

  @Test
  fun `a column exports each of its six arrangements`() {
    for ((value, member) in
      listOf(
        "top" to "Top",
        "center" to "Center",
        "bottom" to "Bottom",
        "spaceBetween" to "SpaceBetween",
        "spaceAround" to "SpaceAround",
        "spaceEvenly" to "SpaceEvenly",
      )) {
      // Both wrappers: `enum` is what the reducer writes now and `string` is what documents
      // committed before #339 still hold, and the arrangement has to read the same from either.
      for (wrapper in listOf(EnumValueV1(value), StringValueV1(value))) {
        val source = source(layout("layout/column", "verticalArrangement" to wrapper))
        assertTrue("verticalArrangement = Arrangement.$member" in source, "$value:\n$source")
      }
    }
  }

  @Test
  fun `an arrangement with a gap composes the way the canvas draws it`() {
    // The pair the loop could not read one row at a time: an aligned arrangement and a spacing
    // name one `Arrangement` between them, through `spacedBy(space, alignment)` — and note the
    // alignment is `Alignment.End`, not the `Arrangement.End` the value names alone.
    assertTrue(
      "horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)" in
        source(
          layout(
            "layout/row",
            "horizontalArrangement" to EnumValueV1("end"),
            "horizontalSpacingDp" to IntegerValueV1(8),
          )
        )
    )
    assertTrue(
      "verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)" in
        source(
          layout(
            "layout/column",
            "verticalArrangement" to EnumValueV1("center"),
            "verticalSpacingDp" to IntegerValueV1(12),
          )
        )
    )
    // A `space*` arrangement distributes the free space itself, and Compose has no form of it that
    // also inserts a gap — the catalog's own note — so the arrangement wins and the gap is spent,
    // exactly as the canvas renders the pair.
    val spaced =
      source(
        layout(
          "layout/column",
          "verticalArrangement" to EnumValueV1("spaceBetween"),
          "verticalSpacingDp" to IntegerValueV1(8),
        )
      )
    assertTrue("verticalArrangement = Arrangement.SpaceBetween" in spaced, spaced)
    assertTrue("spacedBy" !in spaced, spaced)
    // A zero gap beside an aligned arrangement is the bare member — `spacedBy(0.dp, Top)` is
    // `Top`, and the shorter spelling is the one a person writes.
    assertTrue(
      "verticalArrangement = Arrangement.Top" in
        source(
          layout(
            "layout/column",
            "verticalArrangement" to EnumValueV1("top"),
            "verticalSpacingDp" to IntegerValueV1(0),
          )
        )
    )
    // And a gap alone still exports as it always has.
    assertTrue(
      "horizontalArrangement = Arrangement.spacedBy(8.dp)" in
        source(layout("layout/row", "horizontalSpacingDp" to IntegerValueV1(8)))
    )
  }

  @Test
  fun `an arrangement nothing names refuses with the six it could be`() {
    val alone = refusals(layout("layout/row", "horizontalArrangement" to EnumValueV1("middle")))
    assertEquals(
      listOf(
        "node `layout`.`horizontalArrangement` is the enum value `middle`, which is not one of " +
          "center, end, spaceAround, spaceBetween, spaceEvenly, start"
      ),
      alone,
    )
    // The same refusal beside a gap: the pair reads through the same table.
    assertEquals(
      alone,
      refusals(
        layout(
          "layout/row",
          "horizontalArrangement" to EnumValueV1("middle"),
          "horizontalSpacingDp" to IntegerValueV1(8),
        )
      ),
    )
  }

  @Test
  fun `an italic caption exports its font style`() {
    // The table already mapped `fontStyle`; the record's `Text` simply did not declare the
    // parameter, so one italic caption refused as "`Text` has no parameter `fontStyle`" and cost
    // the whole file (#477).
    val source =
      source(
        document(
          roots = listOf("caption"),
          nodes =
            linkedMapOf(
              "caption" to
                label("caption", "Posted yesterday", "fontStyle" to EnumValueV1("italic"))
            ),
        )
      )
    assertTrue("fontStyle = FontStyle.Italic" in source, source)
    assertTrue("import androidx.compose.ui.text.font.FontStyle" in source, source)
  }

  @Test
  fun `a box child's alignment property is the box's align modifier, and nothing else's`() {
    // "How a parent Box aligns this" node, as the catalog puts it: the authored `align` modifier
    // under a property's name, so it resolves inside a box slot and refuses by placement anywhere
    // else — never silently dropped, and never emitted where `BoxScope.align` does not resolve.
    fun inside(container: String) =
      document(
        roots = listOf("container"),
        nodes =
          linkedMapOf(
            "container" to
              DesignNodeV1(
                id = "container",
                componentId = container,
                slots = mapOf("children" to listOf("badge")),
              ),
            "badge" to label("badge", "3", "alignment" to EnumValueV1("bottomEnd")),
          ),
      )
    assertTrue("modifier = Modifier.align(Alignment.BottomEnd)" in source(inside("layout/box")))
    val refused = refusals(inside("layout/column"))
    assertEquals(1, refused.size, refused.toString())
    assertTrue("`Modifier.align` supplies from a box's scope" in refused.single(), refused.single())
    assertTrue("ColumnScope" in refused.single(), refused.single())
  }

  @Test
  fun `a list item fills ListItem's named regions`() {
    fun item(accent: String?) =
      document(
        roots = listOf("item"),
        nodes =
          linkedMapOf(
            "item" to
              DesignNodeV1(
                id = "item",
                componentId = "m3/list-item",
                properties =
                  accent?.let { mapOf("startAccentColor" to StringValueV1(it)) } ?: emptyMap(),
                slots =
                  mapOf(
                    "headline" to listOf("headline"),
                    "supporting" to listOf("supporting"),
                    "trailing" to listOf("trailing"),
                  ),
              ),
            "headline" to label("headline", "Compose 1.9 released"),
            "supporting" to label("supporting", "2 hours ago"),
            "trailing" to
              DesignNodeV1(
                id = "trailing",
                componentId = "m3/icon",
                properties = mapOf("iconKey" to EnumValueV1("chevronRight")),
              ),
          ),
      )
    val source = source(item(accent = null))
    assertTrue("ListItem(" in source, source)
    assertTrue("headlineContent = {" in source, source)
    assertTrue("supportingContent = {" in source, source)
    assertTrue("trailingContent = {" in source, source)
    // "Empty draws none" — the catalog's own words on the accent bar, so an empty one is spent.
    assertEquals(source, source(item(accent = "")))
    // A colour is a `drawBehind` with a statement in it, which no value here is; it refuses by
    // name rather than exporting a schedule's colour-coded tracks as a plain list.
    val refused = refusals(item(accent = "#6750A4"))
    assertEquals(1, refused.size, refused.toString())
    assertTrue("`Modifier.drawBehind { … }`" in refused.single(), refused.single())
  }

  @Test
  fun `a top app bar carries its colours and scroll behaviour under the opt-in they need`() {
    val source =
      source(
        document(
          roots = listOf("bar"),
          nodes =
            linkedMapOf(
              "bar" to
                DesignNodeV1(
                  id = "bar",
                  componentId = "m3/center-aligned-top-app-bar",
                  properties =
                    mapOf(
                      "containerColor" to ColorTokenValueV1("surface"),
                      "scrolledContainerColor" to ColorTokenValueV1("surfaceContainer"),
                      "scrollBehavior" to EnumValueV1("enterAlways"),
                    ),
                  slots = mapOf("title" to listOf("title")),
                ),
              "title" to label("title", "Inbox"),
            ),
        )
      )
    assertTrue("CenterAlignedTopAppBar(" in source, source)
    // Two roles, one bundle: read one at a time the second would have overwritten the first.
    assertTrue(
      "colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = " +
        "MaterialTheme.colorScheme.surface, scrolledContainerColor = " +
        "MaterialTheme.colorScheme.surfaceContainer)" in source,
      source,
    )
    // The factory the catalog promised the value "reaches the generated Kotlin as".
    assertTrue("scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()" in source, source)
    assertTrue("androidx.compose.material3.ExperimentalMaterial3Api::class" in source, source)
    assertTrue("title = {" in source, source)
  }

  @Test
  fun `a slider exports its value and steps, and refuses a range Material does not default`() {
    fun slider(vararg properties: Pair<String, UiValueV1>) =
      document(
        roots = listOf("volume"),
        nodes =
          linkedMapOf(
            "volume" to
              DesignNodeV1(
                id = "volume",
                componentId = "m3/slider",
                properties = mapOf(*properties),
                modifiers = listOf(FillMaxWidthModifierV1),
              )
          ),
      )
    val source =
      source(
        slider(
          "value" to DecimalValueV1(0.4),
          "steps" to IntegerValueV1(4),
          "enabled" to BooleanValueV1(false),
          // The default bounds, spelled out: omitting them leaves `valueRange` at exactly this.
          "valueFrom" to IntegerValueV1(0),
          "valueTo" to DecimalValueV1(1.0),
        )
      )
    assertTrue("value = 0.4f" in source, source)
    // Required, and not a handler the document can bind; the placeholder the record licenses.
    assertTrue("onValueChange = {}" in source, source)
    assertTrue("steps = 4" in source, source)
    assertTrue("enabled = false" in source, source)
    assertTrue("valueRange" !in source, source)
    // A whole number is still a `Float` on this parameter.
    assertTrue("value = 1.0f" in source(slider("value" to IntegerValueV1(1))))
    // A range is `0f..100f`, and a range expression is outside the packages a screen may name.
    val refused = refusals(slider("value" to IntegerValueV1(50), "valueTo" to IntegerValueV1(100)))
    assertEquals(1, refused.size, refused.toString())
    assertTrue("`valueRange = valueFrom..valueTo`" in refused.single(), refused.single())
  }

  @Test
  fun `a colour dot is a clipped, tinted box`() {
    fun dot(vararg properties: Pair<String, UiValueV1>) =
      document(
        roots = listOf("dot"),
        nodes =
          linkedMapOf(
            "dot" to
              DesignNodeV1(
                id = "dot",
                componentId = "shape/colour-dot",
                properties = mapOf(*properties),
              )
          ),
      )
    // The canvas draws `size(d).clip(CircleShape).background(colour)` on a `Box`; the export
    // writes the same chain on the same component, the record reached by alias. A missing
    // diameter is the canvas's 8dp, not a 0dp box that vanished.
    val source = source(dot("color" to ColorValueV1("#6750A4")))
    assertTrue(
      "Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color = Color(" in source,
      source,
    )
    assertTrue("import androidx.compose.foundation.shape.CircleShape" in source, source)
    assertTrue(
      "Modifier.size(12.dp).clip(CircleShape)" in
        source(dot("color" to StringValueV1("#00FF00"), "diameterDp" to IntegerValueV1(12)))
    )
    assertEquals(
      listOf("node `dot` sets no `color`, and a colour dot is nothing but its colour"),
      refusals(dot()),
    )
  }

  /**
   * The screen itself: a channel view of the shape #488's `agent-welcome` has — a centred top bar,
   * rows laid out with arrangements, an italic caption, a colour dot beside a name, a list item, a
   * slider, and a feed column that scrolls — built from the palette as a person would build it and
   * exported through the served executor, so what is asserted is the artifact's own diagnostics and
   * not a projection's outcome.
   */
  @Test
  fun `an ordinary screen built from the palette exports with no diagnostics`() {
    val document =
      document(
        roots = listOf("screen"),
        nodes =
          linkedMapOf(
            "screen" to
              DesignNodeV1(
                id = "screen",
                componentId = "layout/column",
                properties = mapOf("verticalArrangement" to EnumValueV1("top")),
                slots = mapOf("children" to listOf("bar", "header", "feed")),
              ),
            "bar" to
              DesignNodeV1(
                id = "bar",
                componentId = "m3/center-aligned-top-app-bar",
                properties =
                  mapOf(
                    "containerColor" to ColorTokenValueV1("surfaceContainer"),
                    "scrolledContainerColor" to ColorTokenValueV1("surfaceContainerHigh"),
                    "scrollBehavior" to EnumValueV1("pinned"),
                  ),
                slots = mapOf("title" to listOf("channel")),
              ),
            "channel" to label("channel", "# general"),
            "header" to
              DesignNodeV1(
                id = "header",
                componentId = "layout/row",
                properties =
                  mapOf(
                    "horizontalArrangement" to EnumValueV1("spaceBetween"),
                    "verticalAlignment" to EnumValueV1("center"),
                  ),
                modifiers = listOf(FillMaxWidthModifierV1),
                slots = mapOf("children" to listOf("presence", "count", "settings")),
              ),
            "presence" to
              DesignNodeV1(
                id = "presence",
                componentId = "layout/row",
                properties =
                  mapOf(
                    "horizontalArrangement" to EnumValueV1("start"),
                    "horizontalSpacingDp" to IntegerValueV1(6),
                    "verticalAlignment" to EnumValueV1("center"),
                  ),
                slots = mapOf("children" to listOf("online", "name")),
              ),
            "online" to
              DesignNodeV1(
                id = "online",
                componentId = "shape/colour-dot",
                properties = mapOf("color" to ColorValueV1("#23A55A")),
              ),
            "name" to label("name", "yuri"),
            "count" to label("count", "42 members", "fontStyle" to EnumValueV1("italic")),
            "settings" to
              DesignNodeV1(
                id = "settings",
                componentId = "m3/icon",
                properties = mapOf("iconKey" to EnumValueV1("settings")),
              ),
            "feed" to
              DesignNodeV1(
                id = "feed",
                componentId = "layout/column",
                properties =
                  mapOf(
                    "verticalArrangement" to EnumValueV1("top"),
                    "verticalSpacingDp" to IntegerValueV1(8),
                  ),
                modifiers = listOf(FillMaxWidthModifierV1, VerticalScrollModifierV1),
                slots = mapOf("children" to listOf("message", "chips", "volume")),
              ),
            "message" to
              DesignNodeV1(
                id = "message",
                componentId = "m3/list-item",
                properties = mapOf("startAccentColor" to StringValueV1("")),
                slots =
                  mapOf(
                    "headline" to listOf("who"),
                    "supporting" to listOf("what"),
                    "trailing" to listOf("unread"),
                  ),
              ),
            "who" to label("who", "claude", "fontWeight" to EnumValueV1("semiBold")),
            "what" to label("what", "Every row with an arrangement exports now."),
            // A badge: a box whose child says where in the box it sits.
            "unread" to
              DesignNodeV1(
                id = "unread",
                componentId = "layout/box",
                modifiers = listOf(SizeModifierV1(JsonPrimitive(40), JsonPrimitive(40))),
                slots = mapOf("children" to listOf("badge")),
              ),
            "badge" to label("badge", "3", "alignment" to EnumValueV1("bottomEnd")),
            "chips" to
              DesignNodeV1(
                id = "chips",
                componentId = "layout/row",
                properties =
                  mapOf(
                    "horizontalArrangement" to EnumValueV1("spaceEvenly"),
                    "horizontalSpacingDp" to IntegerValueV1(8),
                  ),
                modifiers = listOf(HorizontalScrollModifierV1),
                slots = mapOf("children" to listOf("chip")),
              ),
            "chip" to label("chip", "#announcements"),
            "volume" to
              DesignNodeV1(
                id = "volume",
                componentId = "m3/slider",
                properties =
                  mapOf(
                    "value" to DecimalValueV1(0.7),
                    "steps" to IntegerValueV1(9),
                    "enabled" to BooleanValueV1(true),
                  ),
              ),
          ),
      )

    val artifact =
      ScreenGeneratorComposeExportExecutor(
          { ComponentRecordSource.Lookup.Found(record) },
          ScreenGeneratorScreenFixture.PACKAGE_NAME,
        )
        .export(
          RevisionPinnedUiBuilderExport(
            actor = AuthenticatedUiBuilderActor("tester"),
            designId = document.id,
            revision = document.revision,
            documentHash = "hash",
            document = document,
            catalog = catalog,
            format = ExportFormatV1.COMPOSE,
          )
        )

    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    val source = artifact.content
    assertTrue("horizontalArrangement = Arrangement.SpaceBetween" in source, source)
    assertTrue("Arrangement.spacedBy(6.dp, Alignment.Start)" in source, source)
    assertTrue("Arrangement.spacedBy(8.dp, Alignment.Top)" in source, source)
    assertTrue(".verticalScroll(rememberScrollState())" in source, source)
    assertTrue("import androidx.compose.foundation.rememberScrollState" in source, source)
    // Kept where the compile check can pick it up: `ui-builder-generated-jetcaster` compiles
    // generated screens against real Material 3, and this is the file to hand it.
    File("build/ordinary-screen-export")
      .also(File::mkdirs)
      .resolve("OrdinaryScreen.kt")
      .writeText(source)
  }

  private companion object {
    const val RECORD = "../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
  }
}
