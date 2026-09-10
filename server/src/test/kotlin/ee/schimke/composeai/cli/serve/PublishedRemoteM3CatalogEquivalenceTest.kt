package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `remote-m3` gains and what it loses if `--ui-builder-published-catalogs` names it.
 *
 * The m3 sibling of this test composes a fixture written by hand, which proved the composer and
 * proved nothing about the catalog — see its KDoc. **This pair is generated**, captured verbatim
 * from `:remote-catalog:composePreviewDiscover`, so the question here is the real one.
 *
 * The answer is not symmetric, and that is the point of writing it down:
 * - it **offers** twenty-five Remote Compose components the synthesised shelf has never had, which
 *   until recently the record could not name at all (all 49 catalog ids collapsed onto the
 *   project's own `RemoteSticker` wrapper, two records for the whole module) — but not one of them
 *   can be EXPORTED, so calling them a gain would be wrong (see below);
 * - it **loses** five, in two different ways.
 *
 * The loss is the blocker, and nothing else states it. `ProductionUiBuilderRuntime`'s donor unions
 * in `layout/`, `shape/`, `asset/` and `remote-compose/` and nothing else — deliberately, because
 * those are the builder's own vocabulary rather than any catalog's. Anything else the synthesised
 * shelf offers and the published file omits is simply gone:
 * - `remote-m3/widget-container-small`, `remote-m3/widget-container-large` and `remote-m3/lottie`
 *   are catalog-owned, and the record does not name them;
 * - `m3/surface` and `m3/text` are **borrowed** from the m3 catalog by the synthesised shelf, and
 *   `m3/` is not a donor namespace either, so borrowing does not survive the swap.
 *
 * Two of the five are the widget host itself, and this catalog's own policy calls that where a
 * Remote design begins: *"a Remote design starts from a container: everything else goes inside
 * one."* Cutting over while they are missing takes the container away from every design that has
 * one.
 */
class PublishedRemoteM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  private fun fixture(name: String) = File("../docs/design/fixtures/ui-builder/$name").readText()

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("remote-m3-capabilities-v1.json"))

  private val composed: CatalogCapabilityV1 by lazy {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("remote-m3-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(fixture("remote-m3-published-v1.json"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the generated pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    (result as PublishedUiBuilderCatalog.Result.Composed).catalog
  }

  /**
   * The five the published catalog cannot offer, and why each one.
   *
   * Asserted ABSENT rather than skipped, and with the reason spelled out, because a gap nothing
   * states is a gap that stops being noticed. If one starts composing this test fails and says to
   * move it out, rather than passing quietly.
   */
  private val knownAbsent =
    mapOf(
      "remote-m3/widget-container-small" to
        "WidgetContainerPreviews.kt renders through Glance Wear's WearWidgetPreview wrapper, and " +
          "androidx.glance.wear is not a component-library prefix, so no record entry exists",
      "remote-m3/widget-container-large" to "the same wrapper, at the 216x124 host size",
      "remote-m3/lottie" to "an asset player, not a Remote Compose component call the record sees",
      "m3/surface" to
        "borrowed from the m3 catalog by the synthesised shelf; `m3/` is not a donor namespace, " +
          "so the published file would have to state it, or this catalog would have to draw it",
      "m3/text" to "borrowed the same way, and lost the same way",
    )

  /** The four namespaces `ProductionUiBuilderRuntime` donates back to a published catalog. */
  private val donorNamespaces = listOf("layout/", "shape/", "asset/", "remote-compose/")

  /**
   * Everything the synthesised shelf offers that the donor will NOT put back — this catalog's own
   * components and the two it borrows from m3 alike. Both are the published file's to carry.
   */
  private fun frozenOwned() =
    frozen.components.filterNot { c -> donorNamespaces.any { c.componentId.startsWith(it) } }

  /**
   * Every component the pair composes, by name.
   *
   * A count floor was not enough and the review said so: at `size > 20` on a 27-component fixture,
   * six could vanish — `remote-button-group`, `remote-compact-button`, the progress indicators —
   * and no test here would notice, which is the one thing a pre-cutover gate exists to catch.
   * remote-catalog published TWO records before the scan classpath resolved AARs by coordinate, so
   * a regression of that size is the shape of the bug this is watching for.
   */
  @Test
  fun `the generated pair composes exactly the components it should`() {
    assertEquals(
      listOf(
        "remote-m3/remote-app-card",
        "remote-m3/remote-button",
        "remote-m3/remote-button-group",
        "remote-m3/remote-card",
        "remote-m3/remote-checkbox-button",
        "remote-m3/remote-circular-progress-indicator",
        "remote-m3/remote-compact-button",
        "remote-m3/remote-curved-progress-indicator",
        "remote-m3/remote-edge-button",
        "remote-m3/remote-horizontal-page-indicator",
        "remote-m3/remote-icon",
        "remote-m3/remote-icon-button",
        "remote-m3/remote-linear-progress-indicator",
        "remote-m3/remote-outlined-card",
        "remote-m3/remote-radio-button",
        "remote-m3/remote-slider",
        "remote-m3/remote-split-checkbox-button",
        "remote-m3/remote-split-radio-button",
        "remote-m3/remote-split-switch-button",
        "remote-m3/remote-stepper",
        "remote-m3/remote-sticker",
        "remote-m3/remote-switch-button",
        "remote-m3/remote-text",
        "remote-m3/remote-text-button",
        "remote-m3/remote-title-card",
        "remote-m3/remote-vertical-page-indicator",
        "remote-m3/theme-specimen",
      ),
      composed.components.map { it.componentId }.filter { it.startsWith("remote-m3/") }.sorted(),
      "the composed remote-m3 shelf has changed",
    )
  }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of components the published remote-m3 catalog cannot offer has changed",
    )
  }

  /**
   * The components reach the shelf at all, asserted so a discovery regression shows up here rather
   * than in the builder. Every one was invisible while the AAR carrying them was off the scan
   * classpath.
   *
   * Reaching the shelf is necessary and nowhere near sufficient — none of them can be exported yet;
   * see the test below.
   */
  @Test
  fun `the Remote Compose components the catalog draws are all offered`() {
    val offered = composed.components.map { it.componentId }.toSet()
    val expected =
      listOf(
        "remote-m3/remote-button",
        "remote-m3/remote-card",
        "remote-m3/remote-text",
        "remote-m3/remote-icon",
        "remote-m3/remote-icon-button",
        "remote-m3/remote-checkbox-button",
        "remote-m3/remote-radio-button",
        "remote-m3/remote-switch-button",
        "remote-m3/remote-slider",
        "remote-m3/remote-stepper",
        "remote-m3/remote-edge-button",
        "remote-m3/remote-title-card",
        "remote-m3/remote-app-card",
      )
    assertEquals(
      emptyList(),
      expected.filterNot { it in offered },
      "components remote-catalog draws are missing from the published shelf",
    )
  }

  /**
   * Offered is not usable, and this is the second blocker.
   *
   * `RemoteContentEmitter.emit` writes a design to Remote Compose creation-DSL Kotlin, and it can
   * write exactly the ids in [REMOTE_CONTENT_COMPONENT_IDS] — eleven of them. Everything else hits
   * its catch-all: "has no Remote Compose counterpart this generator can write". `remote-m3/lottie`
   * is in that set and is one of the five the published catalog LOSES; not one of the twenty-five
   * it adds is.
   *
   * WHICH lane a design takes is decided by its ROOT, not by its catalog:
   * `RecordFreeExport.applies` is `roots.single().componentId` being a `WearWidgetScaffoldSize` or
   * the Wear scaffold. So the two blockers here are coupled, and it is worth saying which way
   * round. A Remote design is rooted at a widget container — this catalog's policy says a Remote
   * design starts from one — and the widget containers are exactly what the published catalog
   * loses. Restore them and every design is record-free and meets this refusal head on; leave them
   * out and designs cannot be rooted correctly in the first place.
   *
   * Whether the record-driven lane is an escape is OPEN, and deliberately not answered here. Its
   * own comment says `remote-m3` "ha[s] no component record and the record-driven generator can
   * only refuse them" — written when the record did not exist, which is the thing this work
   * changed, so the sentence needs re-checking rather than citing.
   *
   * Re-checking it needs more than calling the executor. `ScreenGeneratorComposeExportExecutor`
   * resolves a component through its own `components(catalogSystemId)` source rather than through
   * the `catalog` on the export request: handed the real composed catalog — 27 components,
   * `remote-m3/remote-text` among them — it still answers "no component `remote-m3/remote-text` in
   * this catalog". A probe that does not wire that source measures its own fixture, which is what
   * two attempts at one did before this note replaced them.
   *
   * What is checkable without that wiring is the emitter's vocabulary, and that is what is asserted
   * below.
   *
   * `RemoteM3VocabularyParityTest` already holds the synthesised palette to this invariant — a
   * component you can insert and cannot export is worse than one that is missing, because the
   * author finds out at the end with the design already built. It does not cover a published
   * catalog, so the swap escapes it.
   *
   * Asserted as the exact current set rather than as `isEmpty()`, so the day the emitter learns
   * these components this test fails and says to shorten the list. Raised in review on #673.
   */
  @Test
  fun `not one component the published catalog adds can be exported`() {
    val offered = composed.components.map { it.componentId }
    val inexportable =
      offered
        .filterNot { it in REMOTE_CONTENT_COMPONENT_IDS }
        .filterNot { it.startsWith("remote-m3/widget-container-") }
        .sorted()
    assertEquals(
      offered.filter { it.startsWith("remote-m3/") }.sorted(),
      inexportable,
      "the set of offered-but-unexportable components has changed — if the emitter grew a case, " +
        "shorten this; if the catalog grew a component, it needs one",
    )
  }

  /**
   * The modifiers it advertises are unexportable too, which is the same blocker one level down.
   *
   * Not one of the twenty-seven states `modifierCapabilities`, so the composer hands each the
   * structural default — `LEAF_MODIFIERS` for a leaf, plus `fillMaxSize`, `shadow` and the rest for
   * a container. Three of those are outside [REMOTE_CONTENT_MODIFIERS]: `testTag` and `aspectRatio`
   * on everything, `shadow` on every container. `RemoteM3VocabularyParityTest` holds the
   * synthesised palette to this and does not cover a published one.
   *
   * It matters independently of the component blocker: clear that one by teaching the emitter these
   * components, and the shelf would still offer three controls whose use makes export fail later.
   * Raised in review on #673.
   */
  @Test
  fun `the modifiers the published catalog advertises are unexportable too`() {
    val offending =
      composed.components
        .filter { it.componentId.startsWith("remote-m3/") }
        .flatMap { it.modifierCapabilities }
        .filterNot { it in REMOTE_CONTENT_MODIFIERS }
        .distinct()
        .sorted()
    assertEquals(
      listOf("aspectRatio", "shadow", "testTag"),
      offending,
      "the set of advertised-but-unwritable modifiers has changed — if the emitter grew one, " +
        "shorten this; if the composer's structural default did, it needs one",
    )
  }

  /**
   * Every shelf the components use has to be in the menu order, or the editor invents one.
   *
   * `UiBuilderEditorState` appends a group the order does not name after every group it does, and
   * sorts those alphabetically — so a catalog that states an order and then uses four groups
   * outside it has published an order the builder will not follow. `CatalogMenuTest` holds the
   * synthesised catalog to this; the generated pair is not there yet, which is the third blocker.
   *
   * Both directions are asserted, because they mean different things. An unordered group is a real
   * authoring gap and was one: `Selection buttons`, `Edge-hugging buttons`, `Sliders` and
   * `Steppers` are declared only in `src/snapshot/kotlin`, so the released-lane order never named
   * them and the snapshot lane silently re-sorted four shelves. Fixed in wear-m3-catalog. An
   * ORDERED group with no component is something else — see below. Raised in review on #673.
   */
  @Test
  fun `the menu order covers the groups the shelf actually uses`() {
    val menu =
      json
        .parseToJsonElement(fixture("remote-m3-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject
    val order =
      menu["groupOrder"]!!
        .let { it as kotlinx.serialization.json.JsonArray }
        .map { it.jsonPrimitive.content }
    val used =
      menu["components"]!!
        .jsonObject
        .values
        .mapNotNull { it.jsonObject["group"]?.jsonPrimitive?.content }
        .distinct()
        .sorted()

    assertEquals(
      emptyList(),
      used.filterNot { it in order },
      "groups the shelf uses that the order does not name — the editor sorts these alphabetically " +
        "after every ordered group, so the catalog's stated order is not the one a person sees",
    )
    // The other direction, and it is NOT an authoring gap — it is what one-id-per-symbol costs.
    //
    // `RemoteText` is drawn by 29 of this catalog's stickers, across Text, Buttons, Containment
    // and more. It is one record, so it gets one id and one shelf, and the shelf is the one the
    // sorted-first catalog id names: `AppCard`, hence Containment. `Text` is left with no
    // component of its own even though six stickers declare it, and `remote-m3/remote-text` sits
    // somewhere a person would not look for it.
    //
    // That is the trade the symbol-derived id makes deliberately — 49 stickers collapse to 27
    // components — and `@BuilderComponent(group = …)` is the sanctioned way for the catalog to
    // place a component whose stickers span sections. Asserted as the exact current set so that
    // annotating one shortens this list and says so.
    assertEquals(
      listOf(
        "Confetti",
        "Iconography",
        "Position indicators",
        "Scaffold templates",
        "Shaders",
        "Shapes",
        "Text",
        "Typeface",
        "Wear M3",
        "Widget Container",
      ),
      order.filterNot { it in used }.sorted(),
      "groups the order names that no component is on",
    )
  }

  /**
   * The builder's own vocabulary survives the swap, as it must for m3 — seven of the frozen
   * catalog's twelve entries are in a donor namespace. The other five are the gap above, and are
   * asserted there rather than here so the two questions do not blur into one number.
   */
  @Test
  fun `a published remote-m3 catalog is still served the builder's own vocabulary`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("remote-m3"),
          published = mapOf("remote-m3" to composed),
        )
        .listCatalogs()
        .single()
    val builderOwned =
      frozen.components
        .map { it.componentId }
        .filter { id -> donorNamespaces.any { id.startsWith(it) } }
        .sorted()
    val servedIds = served.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      builderOwned.filterNot { it in servedIds },
      "a published catalog lost builder components the synthesised one offers",
    )
  }
}
