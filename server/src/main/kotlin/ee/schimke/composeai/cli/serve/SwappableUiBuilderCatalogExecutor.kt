package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogIssue

/**
 * The builder's catalogs, replaceable while the service runs.
 *
 * `PersistentUiBuilderService` is handed its catalogs once, and they were composed once, at
 * startup, from each catalog's published `ui-builder.json` and runtime. A republished catalog
 * therefore reached a running server only on its next restart, while the catalog viewer beside it
 * refreshed in place (yschimke/compose-preview-server#1054). The refresher now composes the new
 * definition and [swap]s it in here; every call reads whichever executor is current, so a request
 * sees one catalog set or the other, never a mix.
 */
internal class SwappableUiBuilderCatalogExecutor(initial: UiBuilderCatalogExecutor) :
  UiBuilderCatalogExecutor {
  @Volatile private var current: UiBuilderCatalogExecutor = initial

  fun swap(next: UiBuilderCatalogExecutor) {
    current = next
  }

  override fun listCatalogs(): List<CatalogCapabilityV1> = current.listCatalogs()

  override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
    current.resolve(reference)

  override fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue? = current.validate(document, catalog)

  override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
    current.reference(catalog)

  override fun validateWrite(
    catalog: CatalogCapabilityV1,
    document: DesignDocumentV1,
    node: DesignNodeV1,
    slot: String,
  ): UiBuilderCatalogIssue? = current.validateWrite(catalog, document, node, slot)
}
