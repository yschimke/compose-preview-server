package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server's one answer to "what can a new design start as", with the catalog-owned flag off and
 * on. Off must be exactly the built-in seeds every surface used before; on, an owned catalog offers
 * the templates it publishes and nothing the builder's Kotlin knows about it.
 */
class UiBuilderCatalogSeedsTest {

  private val published: CatalogSeedTemplates =
    (CatalogSeedTemplates.read(
        "wear-m3",
        listOf("ui-builder/designs/wear-screen.json", "ui-builder/designs/wear-list.json"),
      ) { path ->
        val id = path.substringAfterLast('/').removeSuffix(".json")
        """
        {"schema":"compose-ui-builder-document/v1-candidate","id":"$id","title":"$id","revision":0,
         "catalogPin":{"systemId":"wear-m3","catalogRevision":"candidate",
           "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
         "environment":{"widthDp":192,"heightDp":192,"density":2.0},
         "stateVariables":{},"roots":["s"],
         "nodes":{"s":{"id":"s","componentId":"wear-m3/screen-scaffold","properties":{},
           "modifiers":[],"slots":{}}}}
        """
      } as CatalogSeedTemplates.Result.Read)
      .templates

  @Test
  fun `built in, every catalog keeps the seeds and default it always had`() {
    val seeds = UiBuilderCatalogSeeds.BUILT_IN
    for (catalog in listOf("m3-catalog", "wear-m3", "remote-m3", "a2ui-catalog")) {
      assertEquals(UiBuilderNewDesignSeed.templateIds(catalog), seeds.templateIds(catalog))
      assertEquals(UiBuilderNewDesignSeed.DEFAULT_TEMPLATE, seeds.defaultTemplate(catalog))
    }
  }

  @Test
  fun `the flag off ignores published templates even when it has them`() {
    val seeds = UiBuilderCatalogSeeds(CatalogOwnership.NONE) { published }
    assertEquals(UiBuilderNewDesignSeed.templateIds("wear-m3"), seeds.templateIds("wear-m3"))
  }

  @Test
  fun `an owned catalog offers what it publishes, in its order, and starts from the first`() {
    val seeds =
      UiBuilderCatalogSeeds(CatalogOwnership.of(setOf("wear-m3"))) {
        published.takeIf { _ -> it == "wear-m3" }
      }
    assertEquals(listOf("wear-screen", "wear-list"), seeds.templateIds("wear-m3").toList())
    assertEquals("wear-screen", seeds.defaultTemplate("wear-m3"))
    // A catalog the flag does not name is untouched beside it.
    assertEquals(UiBuilderNewDesignSeed.templateIds("m3-catalog"), seeds.templateIds("m3-catalog"))
  }
}
