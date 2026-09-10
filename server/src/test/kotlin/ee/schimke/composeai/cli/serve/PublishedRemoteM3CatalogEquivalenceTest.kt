package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * What `remote-m3` gains and what it loses if `--ui-builder-published-catalogs` names it.
 *
 * The m3 sibling of this test composes a fixture written by hand, which proved the composer and
 * proved nothing about the catalog — see its KDoc. **This pair is generated**, captured verbatim
 * from `:remote-catalog:composePreviewDiscover`, so the question here is the real one.
 *
 * The answer is not symmetric, and that is the point of writing it down:
 * - it **gains** twenty-five Remote Compose components the synthesised shelf has never had, which
 *   until recently the record could not name at all (all 49 catalog ids collapsed onto the
 *   project's own `RemoteSticker` wrapper, two records for the whole module);
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

  @Test
  fun `the generated pair composes at all`() {
    // The floor. remote-catalog published two component records until the scan classpath started
    // resolving AARs by coordinate, and a two-record file for a 725-preview module composes to a
    // shelf with nothing on it.
    assertTrue(
      composed.components.size > 20,
      "composed only ${composed.components.size} components",
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
   * The gain, asserted so a regression in discovery shows up here rather than in the builder.
   *
   * These are the Remote Compose components remote-catalog actually draws. Every one of them was
   * invisible while the AAR carrying them was off the scan classpath.
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
