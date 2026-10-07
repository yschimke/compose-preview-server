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
 * Under [ownership] a catalog is seeded from the template documents it publishes ([templates], read
 * from its delivery branch beside `ui-builder.json`), never from the builder's Kotlin templates;
 * any other catalog keeps the built-in seeds. [BUILT_IN] — no catalog owned — is exactly the
 * behaviour before the flag existed.
 */
class UiBuilderCatalogSeeds(
  val ownership: CatalogOwnership = CatalogOwnership.NONE,
  private val templates: (catalogSystemId: String) -> CatalogSeedTemplates? = { null },
) {

  fun templateIds(catalogSystemId: String): Set<String> =
    UiBuilderNewDesignSeed.templateIds(catalogSystemId, ownership, templates(catalogSystemId))

  /**
   * The template a request that names none starts from. Built in, that is the Jetcaster fixture; an
   * owned catalog has no such thing, so it is the first template the catalog lists.
   */
  fun defaultTemplate(catalogSystemId: String): String =
    if (ownership.owns(catalogSystemId)) templateIds(catalogSystemId).first()
    else UiBuilderNewDesignSeed.DEFAULT_TEMPLATE

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
      ownership = ownership,
      published = templates(catalogSystemId),
      state = state,
    )

  companion object {
    val BUILT_IN: UiBuilderCatalogSeeds = UiBuilderCatalogSeeds()
  }
}
