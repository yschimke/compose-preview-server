package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `m3-catalog` gains and what it loses if `--ui-builder-published-catalogs` names it.
 *
 * The sibling [PublishedM3CatalogEquivalenceTest] composes a **hand-built** published file and says
 * so in its KDoc: that is a real test of the composer and no test at all of the catalog. This pair
 * is **generated** — captured verbatim from `:catalog:composePreviewDiscover` in
 * yschimke/m3-catalog — which is the same way `remote-m3` got its gate, and the difference that
 * turned 14 green tests into 11 failures the first time it was tried.
 *
 * The comparison is not the one the name "equivalence" suggests, and
 * `UI_BUILDER_CATALOG_CONTRACT.md` says so outright: the frozen `m3-catalog` capability document is
 * a hand-transcribed *Jetcaster* catalog, unrelated to yschimke/m3-catalog, and "the two describe
 * different component sets on purpose … so here the reviewed difference list is the point and a
 * byte-equal result would be the surprising outcome". So this test does not ask for equality. It
 * asks three separable questions and pins each as an exact set:
 * - which of the frozen twenty-five the real catalog can offer (**twenty-three**, and the two it
 *   cannot are named with a reason);
 * - what each of those twenty-three offers, field by field, against the frozen vocabulary a saved
 *   design was authored against;
 * - what the real catalog adds (**eighty-five**), because a shelf growing by that much is a product
 *   change rather than a rounding error.
 */
class PublishedGeneratedM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  private fun fixture(name: String) = File("../docs/design/fixtures/ui-builder/$name").readText()

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("m3-catalog-capabilities-v1.json"))

  private val composed: CatalogCapabilityV1 by lazy {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(
        fixture("m3-catalog-generated-published-v1.json"),
        record,
        exports,
      )
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the generated pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    (result as PublishedUiBuilderCatalog.Result.Composed).catalog
  }

  /**
   * The two of the frozen twenty-five the published catalog cannot offer, and why each one.
   *
   * Asserted ABSENT rather than skipped: a gap nothing states is a gap that stops being noticed,
   * and if one starts composing this test fails and says to move it out rather than passing
   * quietly. `m3/time-picker` was here until the scan classpath resolved dependencies by coordinate
   * (yschimke/compose-ai-tools#5354) and the record grew `TimePicker` and `TimeInput`.
   */
  private val knownAbsent =
    mapOf(
      "m3/date-picker" to
        "the record carries DateRangePicker but not DatePicker — the preview reaches it through a " +
          "second singleton lambda, and the walk follows the accessor call rather than the " +
          "GETSTATIC read of the private `lambda$<key>` field (yschimke/m3-catalog#317)",
      "m3/snackbar-host" to "the record carries Snackbar but not SnackbarHost",
    )

  private fun frozenOwned() = frozen.components.filter { it.componentId.startsWith("m3/") }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of frozen components the real catalog cannot offer has changed — see m3-catalog#317",
    )
  }

  /**
   * What the real catalog ADDS, as an exact set.
   *
   * The contract document predicted this ("fifty-nine rendered components against twenty-five
   * transcribed ones") and the measured number is eighty-five, so the prediction was the right
   * shape and the wrong size — which is the argument for pinning it rather than bounding it. Every
   * entry is a component a design could be built on, so the list growing or shrinking is a change
   * to what the builder offers and belongs in a diff someone reads.
   *
   * Note what is NOT asserted here: that each is exportable. m3-catalog's lane writes Compose
   * source through the record-driven generator rather than through `RemoteContentEmitter`'s
   * hand-written cases, so the blocker that stops `remote-m3` does not apply — but "does every one
   * of these eighty-five round-trip to compiling Kotlin" is a separate question and this is not it.
   */
  @Test
  fun `the components the real catalog adds are the reviewed set`() {
    val frozenIds = frozen.components.map { it.componentId }.toSet()
    val added = composed.components.map { it.componentId }.filterNot { it in frozenIds }.sorted()
    assertEquals(ADDED, added, "the set of components the real catalog adds has changed")
  }

  @Test
  fun `an offered component matches the frozen one field by field`() {
    val byId = composed.components.associateBy { it.componentId }
    val differences = mutableListOf<String>()
    for (expected in frozenOwned()) {
      if (expected.componentId in knownAbsent) continue
      val actual = byId[expected.componentId] ?: continue
      differences += compare(expected, actual)
    }
    assertEquals(
      REVIEWED_DIFFERENCES,
      differences.sorted(),
      "the composed shelf differs from the frozen one in a way nobody has reviewed",
    )
  }

  /** The same comparison [PublishedM3CatalogEquivalenceTest] makes, for the same reasons. */
  private fun compare(
    expected: ComponentCapabilityV1,
    actual: ComponentCapabilityV1,
  ): List<String> {
    val id = expected.componentId
    val out = mutableListOf<String>()
    fun check(field: String, want: Any?, got: Any?) {
      if (want != got) out += "$id.$field: frozen=$want composed=$got"
    }
    check("displayName", expected.displayName, actual.displayName)
    check("role", expected.role, actual.role)
    check("traits", expected.traits.sorted(), actual.traits.sorted())
    check("slots", expected.slots.map { it.name }.sorted(), actual.slots.map { it.name }.sorted())
    val wantSlots = expected.slots.associateBy { it.name }
    val gotSlots = actual.slots.associateBy { it.name }
    for ((name, w) in wantSlots) {
      val g = gotSlots[name] ?: continue
      check("slots[$name].cardinality.min", w.cardinality.min, g.cardinality.min)
      check("slots[$name].cardinality.max", w.cardinality.max, g.cardinality.max)
      check("slots[$name].ordered", w.ordered, g.ordered)
      check("slots[$name].acceptedRoles", w.acceptedRoles.sorted(), g.acceptedRoles.sorted())
      check("slots[$name].acceptedTraits", w.acceptedTraits.sorted(), g.acceptedTraits.sorted())
    }
    check(
      "modifierCapabilities",
      expected.modifierCapabilities.sorted(),
      actual.modifierCapabilities.sorted(),
    )
    val want = expected.properties.associateBy { it.name }
    val got = actual.properties.associateBy { it.name }
    check("properties", want.keys.sorted(), got.keys.sorted())
    for ((name, w) in want) {
      val g = got[name] ?: continue
      check("properties[$name].jsonType", w.jsonType, g.jsonType)
      check("properties[$name].required", w.required, g.required)
      check("properties[$name].allowedValues", w.allowedValues, g.allowedValues)
    }
    return out
  }

  /**
   * The builder's own vocabulary survives the swap.
   *
   * All seventeen of the frozen catalog's non-`m3/` entries are in a donor namespace, so unlike
   * `remote-m3` — which borrows `m3/surface` and `m3/text` and loses both — m3 has no borrowed
   * component to lose. That is worth an assertion rather than a sentence: the donor list is a
   * constant in `ProductionUiBuilderRuntime` and a catalog gaining a borrowed component would make
   * this the same blocker it is there.
   */
  @Test
  fun `a published m3 catalog is still served the builder's own vocabulary`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    val builderOwned =
      frozen.components.map { it.componentId }.filterNot { it.startsWith("m3/") }.sorted()
    val servedIds = served.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      builderOwned.filterNot { it in servedIds },
      "a published catalog lost builder components the synthesised one offers",
    )
  }

  /**
   * Every shelf the components use has to be in the menu order, or the editor invents one.
   *
   * `UiBuilderEditorState` appends a group the order does not name after every group it does and
   * sorts those alphabetically, so a catalog that states an order and then uses a group outside it
   * has published an order the builder will not follow. The mirror of the `remote-m3` check, and
   * the reason that one exists: four wear shelves were re-sorted silently.
   */
  @Test
  fun `the menu order covers the groups the shelf actually uses`() {
    val menu =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject
    val order = (menu["groupOrder"] as JsonArray).map { it.jsonPrimitive.content }
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
    assertEquals(
      UNUSED_GROUPS,
      order.filterNot { it in used }.sorted(),
      "groups the order names that no component is on",
    )
  }

  /**
   * A component with no menu entry is a component the editor files under a role heading.
   *
   * The insert panel groups by `componentMenu`, and an entry it cannot find falls back to a generic
   * "Container" / "Leaf" heading — so this is not a cosmetic gap: it is where a person looks for
   * the component and does not find it. Twenty-eight of the hundred and eight were in that state
   * until the generator learned to shelve a component by the catalog id it is published under
   * (yschimke/compose-ai-tools#5354).
   *
   * The six left are the ones that fix cannot reach: nothing declares them, so they carry no
   * catalog id and there is no shelf to inherit. Only `ui-builder.policy.json` can place them, and
   * where an adaptive-layout scaffold belongs on a Material 3 shelf is a catalog decision rather
   * than something this test can assert — m3-catalog#323.
   */
  @Test
  fun `every component is on a shelf, or is one nothing declares`() {
    val menu =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject["components"]!!
        .jsonObject
    val unshelved = composed.components.map { it.componentId }.filterNot { it in menu }.sorted()
    assertEquals(UNSHELVED.keys.sorted(), unshelved, "the set of unshelved components has changed")
  }

  /**
   * Four components are on a shelf a person would not look on, and that is a decision, not a bug
   * this test can make.
   *
   * One component is one SYMBOL, and a symbol several stickers draw is filed under whichever
   * catalog id sorts first. Seven of those landed somewhere plainly wrong and m3-catalog now states
   * the shelf outright — `Text` on Typography, `Switch` on Switch, `IconButton` and
   * `FloatingActionButton` off Bottom app bar. These four are the remainder, and they are different
   * in kind: the catalog has no section they belong to, so placing them means inventing a shelf
   * rather than correcting a derivation. Pinned so the choice is visible in a diff and shortening
   * this list is what fixing one looks like — m3-catalog#323.
   */
  @Test
  fun `the shelves nobody has chosen are the reviewed set`() {
    val menu =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject["components"]!!
        .jsonObject
    val actual =
      UNDECIDED_SHELVES.keys.associateWith {
        menu[it]?.jsonObject?.get("group")?.jsonPrimitive?.content
      }
    assertEquals(
      UNDECIDED_SHELVES,
      actual,
      "a component whose shelf nobody chose moved — if the catalog chose one, drop it from here",
    )
  }

  private companion object {
    /**
     * The eighty-five components the real catalog adds; see the test above for why they are pinned.
     */
    val ADDED =
      listOf(
        "m3/adaptive-sticker",
        "m3/animated-pane",
        "m3/app-bar-with-search",
        "m3/assist-chip",
        "m3/badge",
        "m3/bottom-app-bar",
        "m3/centered-track",
        "m3/circular-progress-indicator",
        "m3/circular-wavy-progress-indicator",
        "m3/contained-loading-indicator",
        "m3/current-scheme",
        "m3/date-range-picker",
        "m3/docked-search-bar",
        "m3/elevated-assist-chip",
        "m3/elevated-button",
        "m3/elevated-card",
        "m3/elevated-filter-chip",
        "m3/elevated-leading-button",
        "m3/elevated-suggestion-chip",
        "m3/elevated-toggle-button",
        "m3/elevated-trailing-button",
        "m3/extended-floating-action-button",
        "m3/filled-icon-button",
        "m3/filled-tonal-button",
        "m3/filled-tonal-icon-button",
        "m3/floating-action-button",
        "m3/horizontal-centered-hero-carousel",
        "m3/horizontal-multi-browse-carousel",
        "m3/horizontal-uncontained-carousel",
        "m3/input-chip",
        "m3/large-extended-floating-action-button",
        "m3/large-floating-action-button",
        "m3/large-top-app-bar",
        "m3/leading-button",
        "m3/linear-wavy-progress-indicator",
        "m3/list-detail-pane-scaffold",
        "m3/loading-indicator",
        "m3/material-expressive-theme",
        "m3/medium-extended-floating-action-button",
        "m3/medium-floating-action-button",
        "m3/medium-top-app-bar",
        "m3/multi-choice-segmented-button-row",
        "m3/navigation-rail",
        "m3/navigation-rail-item",
        "m3/navigation-suite-item",
        "m3/navigation-suite-scaffold",
        "m3/outlined-button",
        "m3/outlined-card",
        "m3/outlined-icon-button",
        "m3/outlined-leading-button",
        "m3/outlined-text-field",
        "m3/outlined-toggle-button",
        "m3/outlined-trailing-button",
        "m3/primary-scrollable-tab-row",
        "m3/provide-text-style",
        "m3/range-slider",
        "m3/secondary-scrollable-tab-row",
        "m3/secondary-tab-row",
        "m3/segmented-button",
        "m3/short-navigation-bar",
        "m3/short-navigation-bar-item",
        "m3/single-choice-segmented-button-row",
        "m3/small-extended-floating-action-button",
        "m3/small-floating-action-button",
        "m3/snackbar",
        "m3/split-button-layout",
        "m3/sticker",
        "m3/suggestion-chip",
        "m3/supporting-pane-scaffold",
        "m3/text-button",
        "m3/thumb",
        "m3/time-input",
        "m3/toggle-button",
        "m3/tonal-leading-button",
        "m3/tonal-toggle-button",
        "m3/tonal-trailing-button",
        "m3/top-app-bar",
        "m3/track",
        "m3/trailing-button",
        "m3/tri-state-checkbox",
        "m3/vertical-divider",
        "m3/vertical-floating-toolbar",
        "m3/vertical-slider",
        "m3/wide-navigation-rail",
        "m3/wide-navigation-rail-item",
      )

    /** Reviewed differences between an offered component and the frozen vocabulary. */
    val REVIEWED_DIFFERENCES = listOf<String>()

    /** Components no sticker declares, so no catalog id gives them a shelf; see the test above. */
    val UNSHELVED =
      mapOf(
        "m3/adaptive-sticker" to "the catalog's own sticker frame, drawn by no sticker of its own",
        "m3/animated-pane" to "an adaptive-layout pane; the catalog has no section for one",
        "m3/list-detail-pane-scaffold" to "the same, and the frozen shelf calls it a Scaffold",
        "m3/navigation-suite-item" to "the same",
        "m3/navigation-suite-scaffold" to "the same",
        "m3/supporting-pane-scaffold" to "the same",
      )

    /** Shelves the derivation chose and nobody confirmed; see the test above. */
    val UNDECIDED_SHELVES =
      mapOf(
        "m3/icon" to "Bottom app bar",
        "m3/material-expressive-theme" to "Badges",
        "m3/sticker" to "Badges",
        "m3/surface" to "Chips",
      )

    /** Shelves the order names that no component is on today; see the test above. */
    val UNUSED_GROUPS = listOf("Bottom sheets", "Menus", "Shapes", "Side sheets", "Tooltips")
  }
}
