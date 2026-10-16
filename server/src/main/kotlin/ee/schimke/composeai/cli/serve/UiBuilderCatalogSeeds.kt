package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlinx.serialization.json.JsonObject

/**
 * What a new design can start as, for every surface that asks: the create route, the New design
 * form and its validation. One object so the form cannot offer a template the route then refuses.
 *
 * Every catalog is seeded from the template documents it publishes ([templates], read from its
 * delivery branch beside `ui-builder.json`). With none, the builder supplies a generic blank.
 */
class UiBuilderCatalogSeeds(
  private val templates: (catalogSystemId: String) -> CatalogSeedTemplates? = { null }
) {

  fun templateIds(catalogSystemId: String): Set<String> =
    UiBuilderNewDesignSeed.templateIds(
      catalogSystemId,
      CatalogOwnership.ALL,
      templates(catalogSystemId),
    )

  /** The first template the catalog lists, or the generic blank when none are published. */
  fun defaultTemplate(catalogSystemId: String): String = templateIds(catalogSystemId).first()

  fun document(
    designId: String,
    catalogSystemId: String,
    templateId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
    fixture: JsonObject,
    state: List<NewDesignState>,
  ): UiBuilderDocument =
    UiBuilderNewDesignSeed.document(
      designId = designId,
      catalogSystemId = catalogSystemId,
      templateId = templateId,
      catalogRevision = catalogRevision,
      nativeRuntimeId = nativeRuntimeId,
      fixture = fixture,
      ownership = CatalogOwnership.ALL,
      published = templates(catalogSystemId),
      state = state,
    )

  companion object {
    val EMPTY: UiBuilderCatalogSeeds = UiBuilderCatalogSeeds()
  }
}
