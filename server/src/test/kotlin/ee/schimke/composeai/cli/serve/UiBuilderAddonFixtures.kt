package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The UI-builder add-ons (`wear-m3`, `remote-m3`) as their committed capability fixtures, handed to
 * the executor as `published` — the way a deployment serves them from `wear-m3-catalog`'s delivery
 * branch.
 *
 * Material 3 is the one catalog the runtime defines. An add-on exists only when it is published, so
 * a test that serves one names it here rather than letting the runtime answer for it. The fixture
 * is the frozen description the retired synthesised catalog left, which is what these tests were
 * written against; what the published policy composes to is
 * `PublishedRemoteM3CatalogEquivalenceTest` and `PublishedM3CatalogEquivalenceTest`'s subject.
 */
internal object UiBuilderAddonFixtures {

  private val json = Json { ignoreUnknownKeys = true }

  val addonIds: Set<String> = setOf("remote-m3", "wear-m3")

  fun catalog(systemId: String): CatalogCapabilityV1 =
    json.decodeFromString(
      File("../docs/design/fixtures/ui-builder/$systemId-capabilities-v1.json").readText()
    )

  /**
   * `wear-m3` as `wear-m3-catalog` publishes it today, captured from a running server's
   * `listCatalogs`: a slot-based vocabulary (`time-text` and `scroll-indicator` in the scaffold's
   * slots, `title-card`, a list header holding its label as a child) that the frozen fixture above,
   * written for the retired Kotlin catalog, does not describe.
   */
  fun publishedWearCatalog(): CatalogCapabilityV1 =
    json.decodeFromString(
      File("../docs/design/fixtures/ui-builder/published/wear-m3-capabilities-v1.json").readText()
    )

  /**
   * The `wear-m3` fixture the builder's own Wear templates validate against.
   *
   * Transitional, and deliberately so: compose-ui-builder#231 rewrites the templates in the
   * published vocabulary, and this server tests against both that checkout
   * (`server-against-checkout`) and the released runtime it pins, whose templates still use the
   * frozen one. Which one the runtime writes is read off the template itself — a
   * published-vocabulary screen puts a `wear-m3/time-text` in its scaffold. Once the pin
   * carries #231, this is always [publishedWearCatalog] and the choice can go.
   */
  fun wearCatalogForTemplates(fixture: JsonObject): CatalogCapabilityV1 {
    val seeded =
      UiBuilderNewDesignSeed.document(
        designId = "probe",
        catalogSystemId = "wear-m3",
        templateId = UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
        catalogRevision = "probe",
        nativeRuntimeId = "probe",
        fixture = fixture,
      )
    return if (seeded.nodes.values.any { it.componentId == "wear-m3/time-text" }) {
      publishedWearCatalog()
    } else {
      catalog("wear-m3")
    }
  }

  /** The `published` map for whichever add-ons [catalogSystemIds] names. */
  fun publishedFor(catalogSystemIds: Set<String>): Map<String, CatalogCapabilityV1> =
    catalogSystemIds.filter { it in addonIds }.associateWith(::catalog)

  /** An executor serving [catalogSystemIds], with the add-ons among them published. */
  fun executor(catalogSystemIds: Set<String>): CurrentM3UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = catalogSystemIds,
      published = publishedFor(catalogSystemIds),
    )
}
