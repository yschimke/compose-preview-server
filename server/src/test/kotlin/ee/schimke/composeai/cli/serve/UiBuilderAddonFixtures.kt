package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlinx.serialization.json.Json

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
