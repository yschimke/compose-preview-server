package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderComponentPackSource
import java.io.File
import kotlinx.serialization.json.Json

/** Historical Jetcaster input for tests, supplied explicitly rather than by a runtime fallback. */
internal fun benchmarkCatalog(source: String? = null): CatalogCapabilityV1 =
  Json.decodeFromString(
    source ?: File("../docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json").readText()
  )

/**
 * The benchmark and any additional published catalogs, handed to the builder through its host seam.
 * Tests of HTTP, persistence and authorization keep their historical document without relying on an
 * implicit Kotlin catalog that the builder no longer serves.
 */
internal fun benchmarkCatalogExecutor(
  source: String? = null,
  catalogSystemIds: Set<String> = setOf("m3-catalog"),
  exportCapabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1.Builder()
      .apply {
        composeCode = true
        svg = false
        png = false
      }
      .build(),
  composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
  packs: List<UiBuilderComponentPackSource> = emptyList(),
  published: Map<String, CatalogCapabilityV1> = emptyMap(),
): CurrentM3UiBuilderCatalogExecutor =
  CurrentM3UiBuilderCatalogExecutor.Builder()
    .also {
      source?.let { source -> it.source = source }
      it.catalogSystemIds = catalogSystemIds
      it.exportCapabilities = exportCapabilities
      it.composeExportFor = composeExportFor
      it.packs = packs
      it.published =
        (if ("m3-catalog" in catalogSystemIds)
          mapOf(
            "m3-catalog" to
              benchmarkCatalog(source)
                .newBuilder()
                .also { it.exportCapabilities = exportCapabilities }
                .build()
          )
        else emptyMap()) + published
      it.catalogOwnership = CatalogOwnership.ALL
    }
    .build()
