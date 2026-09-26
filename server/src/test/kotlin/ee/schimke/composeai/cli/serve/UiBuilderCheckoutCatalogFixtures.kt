package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import java.io.File
import kotlinx.serialization.json.Json

/** Published catalog snapshots supplied by a UI-builder checkout in the cross-repository gate. */
internal object UiBuilderCheckoutCatalogFixtures {
  private val json = Json { ignoreUnknownKeys = true }

  fun executor(
    catalogSystemIds: Set<String>,
    exportCapabilities: ExportCapabilitiesV1? = null,
    composeExportFor: ((String) -> Boolean)? = null,
  ): CurrentM3UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also { builder ->
        builder.catalogSystemIds = catalogSystemIds
        exportCapabilities?.let { builder.exportCapabilities = it }
        composeExportFor?.let { builder.composeExportFor = it }
        builder.published = publishedFor(catalogSystemIds)
      }
      .build()

  /**
   * Empty for a normal build, where the released runtime still owns its compatible catalogs.
   *
   * `server-against-checkout` supplies the checkout path. Reading its committed, already-composed
   * snapshots makes this gate exercise the new runtime exactly as production does, without making
   * the server repository a second owner of those catalog definitions.
   */
  private fun publishedFor(systemIds: Set<String>): Map<String, CatalogCapabilityV1> {
    val checkout = System.getProperty("composeUiBuilderDir")?.let(::File) ?: return emptyMap()
    val fixtures = checkout.resolve("ui-builder/src/jvmTest/resources/published")
    return systemIds
      .filter { it == "remote-m3" || it == "wear-m3" }
      .associateWith { systemId ->
        val fixture = fixtures.resolve("$systemId-capabilities-v1.json")
        require(fixture.isFile) {
          "UI-builder checkout has no published catalog fixture for $systemId at $fixture"
        }
        json.decodeFromString(fixture.readText())
      }
  }
}
