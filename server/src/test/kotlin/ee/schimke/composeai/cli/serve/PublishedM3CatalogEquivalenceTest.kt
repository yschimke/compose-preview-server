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

/**
 * Does the shelf composed from m3-catalog's published files match the shelf this server
 * synthesises?
 *
 * The readiness gate answers a narrower question — the catalog-level facts agree and the component
 * IDS agree — and says so. It does not compare what a component OFFERS, and the difference is not
 * academic: a first attempt at this cutover matched all the ids while replacing every component's
 * property vocabulary with its Compose parameter list, making `onClick` a required property no
 * design sets, and stripping every modifier. Ids matching is not shelves matching, which is the
 * same mistake as counting components and calling it identity.
 *
 * So this compares the composed component against the frozen one field by field, for every
 * component the frozen catalog owns. It is the check that has to pass before
 * `--ui-builder-published-catalogs` names `m3-catalog`.
 */
class PublishedM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  private fun fixture(name: String) = File("../docs/design/fixtures/ui-builder/$name").readText()

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("m3-catalog-capabilities-v1.json"))

  private val composed: CatalogCapabilityV1 by lazy {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(fixture("m3-catalog-published-v1.json"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the published pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    (result as PublishedUiBuilderCatalog.Result.Composed).catalog
  }

  /**
   * The three the published catalog cannot offer, and why — [yschimke/m3-catalog#317].
   *
   * Asserted ABSENT rather than skipped. A gap nothing states is a gap that stops being noticed,
   * and if one of these starts composing (because the record grew the callable) this test fails and
   * says to move it up here, rather than passing quietly with a shelf nobody re-checked.
   */
  private val knownAbsent =
    mapOf(
      "m3/date-picker" to "the record carries DateRangePicker but not DatePicker",
      "m3/snackbar-host" to "the record carries Snackbar but not SnackbarHost",
      "m3/time-picker" to "the record carries neither TimePicker nor TimeInput",
    )

  private fun frozenOwned() = frozen.components.filter { it.componentId.startsWith("m3/") }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of components the published catalog cannot offer has changed — see m3-catalog#317",
    )
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
    assertTrue(
      differences.isEmpty(),
      "the composed shelf differs from the frozen one:\n" + differences.joinToString("\n"),
    )
  }

  /** Everything a design depends on, and nothing a rendering detail would churn. */
  private fun compare(
    expected: ComponentCapabilityV1,
    actual: ComponentCapabilityV1,
  ): List<String> {
    val id = expected.componentId
    val out = mutableListOf<String>()
    fun check(field: String, want: Any?, got: Any?) {
      if (want != got) out += "  $id.$field: frozen=$want composed=$got"
    }
    check("displayName", expected.displayName, actual.displayName)
    check("role", expected.role, actual.role)
    check("traits", expected.traits.sorted(), actual.traits.sorted())
    check("slots", expected.slots.map { it.name }.sorted(), actual.slots.map { it.name }.sorted())
    check(
      "modifierCapabilities",
      expected.modifierCapabilities.sorted(),
      actual.modifierCapabilities.sorted(),
    )
    // Properties by name, then field by field: "the same 5 properties" is the count mistake again,
    // and a property that changed from optional to required breaks every design that omitted it.
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
   * The other half of the shelf: the builder's own components, which m3-catalog deliberately does
   * not publish.
   *
   * Its policy says why — declaring `layout/box` "would be this catalog claiming to own the
   * builder's own vocabulary" — and it is right, so the server supplies them. Without that a
   * published catalog replaces the synthesised one wholesale and the shelf loses its box, its image
   * and its gradients: sixteen components here, every one of which existing designs use.
   */
  @Test
  fun `a published catalog is still served the builder's own vocabulary`() {
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
    assertEquals(
      "published",
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .catalogSources["m3-catalog"],
      "the union must not quietly turn a published catalog back into a synthesised one",
    )
  }

  @Test
  fun `the composed catalog offers no component the frozen one does not`() {
    val frozenIds = frozen.components.map { it.componentId }.toSet()
    val surplus = composed.components.map { it.componentId }.filterNot { it in frozenIds }
    assertEquals(
      emptyList(),
      surplus,
      "the published catalog offers components the frozen shelf does not; every one is a component " +
        "a design could be built on and then lose",
    )
  }
}
