package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The authored join between the capability catalog and the component record.
 *
 * ## Why this file exists at all
 *
 * `ScreenGeneratorComposeExportExecutor` needs a `ComponentRecordFile` for the catalog it exports,
 * and until now **no such record existed anywhere in this repository** — `--ui-builder-components`
 * was read by `ComponentRecordSource` and written by nothing, so a stock host refused every Compose
 * export with `NO_COMPONENT_RECORD`. `m3-catalog-components-v1.json` is that record.
 *
 * ## Why it is authored rather than generated
 *
 * It cannot be projected from the capability catalog. That file declares `jsonType` — `"string"`,
 * `"number"` — where the generator needs a Kotlin `typeFqn`; it names one `code.symbol` for ids
 * that select a **different callable** by property (`m3/card` is `Card`, `ElevatedCard` or
 * `OutlinedCard` depending on `variant`); and it carries properties that are not parameters of
 * anything (`scrollStateKey`, `stableKey`, `m3/surface`'s `themeTypeScale`). That missing knowledge
 * is exactly what `CapabilityComposeCodeExporter` encodes in 1,588 lines of `when`; this file is
 * the same knowledge as data, read by the real generator.
 *
 * ## What "not covered" means, and why it is safe
 *
 * The record covers the components whose Compose mapping is unambiguous. Everything else — the
 * variant-selecting ids, the Remote Compose embed §5 deliberately keeps out of the Compose exporter
 * — is **absent rather than guessed**. That is safe by the generator's own contract: an id it has
 * no record for refuses by name, and a property the record does not declare refuses by name. The
 * failure mode of an incomplete record is a refusal an operator can read; the failure mode of a
 * wrong one is Kotlin that does not compile in someone else's project.
 *
 * [uncovered] is therefore a checked-in list rather than an absence, so growing the record is a
 * deliberate edit here and a shrinking list, not something that drifts.
 *
 * ## Three records for one catalog id
 *
 * `ElevatedCard` and `OutlinedCard` carry an **empty** `componentIds`, which is not an oversight.
 * The catalog spells all three cards as `m3/card` and picks between them with a `variant` property
 * — its `code.imports` lists all three while its `code.symbol` names one — so there is no catalog
 * id for either to answer to. `ScreenDocumentProjection` selects them by canonical id, which
 * `ScreenNode.componentId` accepts precisely so a record can be reached without one.
 *
 * That is why the two coverage tests below still balance: coverage is counted in catalog ids, and
 * these claim none.
 *
 * ## `m3/icon`, and the one thing this record authors that discovery could not print
 *
 * `m3/icon` was on that list — "`iconKey` resolves to an `ImageVector` the record cannot name" —
 * and it is covered now that `ScreenDocumentProjection.ICON_MEMBERS` names one per catalog key. Its
 * `code.call` is the single entry here that a discovery run would not have produced: `callSite`
 * refuses a component whose required parameter has no placeholder, and `ImageVector` has none.
 *
 * That refusal is about the **placeholder table**, not about calling `Icon`, and the two are
 * conflated in one boolean. `ScreenGenerator` reads `code.call` only as a licence — it builds the
 * argument list from [ComponentRecordFile] parameters and the document's own values, and for an
 * `m3/icon` node the document always supplies the vector. So the call is authored with `TODO()` in
 * the position the record cannot fill: it compiles, it is what a person scaffolding by hand would
 * write, and it does not claim a value the record does not have.
 *
 * ## The lazy three, and the field that let them in
 *
 * `layout/lazy-column`, `layout/lazy-row` and `layout/lazy-grid` were on that list for one shared
 * reason — "`items` is a `LazyListScope` DSL, not a composable slot" — and it was true of the
 * **record shape**, not of the components. `LazyColumn`'s `content` is a plain `LazyListScope.() ->
 * Unit`: not `@Composable`, so `composableSlot` was false and the container refused before anything
 * was emitted, and satisfiable by a bare `{ … }` that does not compile, because `Text` is not a
 * member of `LazyListScope`.
 *
 * Two upstream additions closed it (compose-ai-tools#5216, released in 1.83.0), and the three
 * records use both: `TargetParameter.scopeDslReceiver` on the `content` parameter, which is what
 * makes the slot fillable at all, and `ScreenNode.slotItems` from
 * `ScreenDocumentProjection.SLOT_ITEMS`, which says each child is wrapped in `item { }`. The
 * generator checks the second against the first rather than trusting it.
 *
 * `LazyVerticalGrid` is the one whose `code.call` needs a `TODO()`, for the `m3/icon` reason above:
 * `columns` is required and `GridCells` has no placeholder. Every real document supplies it — the
 * catalog marks `columns` required too — and the projection turns it into `GridCells.Adaptive(…)`.
 */
class M3CatalogComponentRecordTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val catalog =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3-catalog", "candidate", "candidate"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
    )

  private val record: ComponentRecordFile = json.decodeFromString(File(RECORD).readText())

  /** Capability ids the record deliberately does not cover yet, each with the reason. */
  private val uncovered =
    mapOf(
      "asset/image" to "Image takes a Painter; no ScreenValue expresses one",
      // Not "items is a CarouselScope DSL" — that was this list's own guess and it is wrong.
      // `HorizontalUncontainedCarousel(state = rememberCarouselState { 5 }, …) { CarouselItem() }`
      // is how m3-catalog calls it: the content is a trailing composable slot taking an item
      // index, not a scope DSL, so `ScreenNode.slotItems` does not reach it. Two things block it,
      // and both are lambdas — `rememberCarouselState { n }` is a factory whose argument is one,
      // and the slot is called per index rather than per child.
      "layout/horizontal-carousel" to
        "takes a CarouselState from rememberCarouselState { n }, whose argument is a lambda, and its content slot is called per item index rather than per child",
      "layout/supporting-pane-scaffold" to "adaptive API; panes are not plain composable slots",
      "m3/center-aligned-top-app-bar" to
        "scrollBehavior is a TopAppBarScrollBehavior from a remembered factory, which no ScreenValue expresses",
      "m3/date-picker" to
        "takes a DatePickerState from rememberDatePickerState, which no ScreenValue expresses",
      "m3/dialog" to
        "AlertDialog is a window and needs an onDismissRequest a design cannot write; the builder draws and emits its surface inline instead",
      "m3/horizontal-floating-toolbar" to "experimental; content is a FlowRow-shaped scope",
      "m3/list-item" to "startAccentColor is a drawBehind, not a parameter of ListItem",
      "m3/primary-tab-row" to "tabs is a TabRow scope, and the row's own indicator takes a lambda",
      "m3/search-bar" to "inputField is a typed lambda, not a plain composable slot",
      "m3/search-input-field" to "SearchBarDefaults.InputField is a member of an object",
      "m3/slider" to
        "onValueChange takes the new value, which the document's action vocabulary does not carry",
      "m3/snackbar-host" to "takes a SnackbarHostState, which no ScreenValue expresses",
      "m3/tab" to
        "onClick is required and a design's tab selection is not an action it can express",
      "m3/time-picker" to
        "takes a TimePickerState from rememberTimePickerState, which no ScreenValue expresses",
      "remote-compose/document" to "typed embed, kept out of the Compose exporter by design",
      "shape/colour-dot" to "a Box with a background, not a Material component",
      "shape/linear-gradient" to "a Modifier, not a component",
      "shape/radial-gradient" to "a Modifier, not a component",
    )

  private fun capabilityIds(): Set<String> =
    json
      .parseToJsonElement(File(CAPABILITIES).readText())
      .jsonObject
      .getValue("components")
      .jsonArray
      .map { it.jsonObject.getValue("componentId").jsonPrimitive.content }
      .toSet()

  @Test
  fun `every record answers to a capability id the catalog declares`() {
    val declared = capabilityIds()
    val claimed = record.components.flatMap { it.componentIds }
    // A typo here is the worst failure this file can have: the record would look complete and the
    // export would refuse the one id nobody thought to try.
    claimed.forEach {
      assertTrue(it in declared, "record claims `$it`, which the catalog does not")
    }
    assertEquals(claimed.size, claimed.toSet().size, "two records claim one capability id")
  }

  @Test
  fun `covered plus uncovered accounts for the whole catalog`() {
    val declared = capabilityIds()
    val covered = record.components.flatMap { it.componentIds }.toSet()
    assertEquals(
      emptySet(),
      declared - covered - uncovered.keys,
      "capability ids that are neither covered nor listed as uncovered — add a record or a reason",
    )
    assertEquals(
      emptySet(),
      uncovered.keys - declared,
      "uncovered names an id the catalog no longer declares",
    )
    assertEquals(emptySet(), covered intersect uncovered.keys, "an id is both covered and not")
  }

  @Test
  fun `every covered record can print a call site`() {
    // `code.call` is the generator's licence to call at all: a record without one refuses, so a
    // record that claims coverage and cannot print is worse than one that never claimed it.
    record.components.forEach { component ->
      assertTrue(
        component.code?.call != null,
        "${component.componentIds} has no call site: ${component.code?.refusedReason}",
      )
      assertTrue(component.signatureKnown, "${component.componentIds} has an unread signature")
    }
  }

  @Test
  fun `a screen built from covered ids generates against this record`() {
    // The end-to-end claim: a design using the authored ids becomes Kotlin through
    // `ScreenDocumentProjection` and the real `ScreenGenerator`, with no hand-written emitter
    // anywhere on the path. `layout/column` is filled through its **catalog** slot name
    // (`children`), which is the case the authored slot mapping exists for — a document that used
    // `content` here would pass even with the mapping deleted.
    val document =
      ScreenGeneratorScreenFixture.document()
        .copy(
          roots = listOf("surface"),
          nodes =
            linkedMapOf(
              "surface" to
                DesignNodeV1(
                  id = "surface",
                  componentId = "m3/surface",
                  properties = mapOf("color" to ColorTokenValueV1("surfaceContainer")),
                  slots = mapOf("content" to listOf("column")),
                ),
              "column" to
                DesignNodeV1(
                  id = "column",
                  componentId = "layout/column",
                  slots = mapOf("children" to listOf("heading", "divider", "agree")),
                ),
              "heading" to
                DesignNodeV1(
                  id = "heading",
                  componentId = "m3/text",
                  properties =
                    mapOf(
                      "text" to StringValueV1("Discover"),
                      "style" to TypographyTokenValueV1("headlineSmall"),
                    ),
                ),
              "divider" to DesignNodeV1(id = "divider", componentId = "m3/horizontal-divider"),
              // A selection control, which is the shape the record could not carry until now: its
              // required `onCheckedChange` is nullable, so a call site can write `null` for it, and
              // `checked` is an ordinary boolean the document supplies. Included here because a
              // record nothing generates against is a table nobody has checked.
              "agree" to
                DesignNodeV1(
                  id = "agree",
                  componentId = "m3/checkbox",
                  properties = mapOf("checked" to BooleanValueV1(true)),
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
    val source = artifact.content

    assertTrue(
      artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      "refused: ${artifact.diagnostics.map { it.message }}\n$source",
    )
    assertTrue(source.contains("Surface("), source)
    // The alias did its work: the catalog's `children` reached `Column`'s `content` parameter.
    assertTrue(source.contains("Column(content = {"), source)
    assertTrue(source.contains("""Text(text = "Discover""""), source)
    assertTrue(source.contains("HorizontalDivider("), source)
    assertTrue(source.contains("Checkbox("), source)
    assertTrue(source.contains("checked = true"), source)
    assertTrue(!source.contains("children ="), source)
  }

  private companion object {
    const val RECORD = "../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
    const val CAPABILITIES = "../docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json"
  }
}
