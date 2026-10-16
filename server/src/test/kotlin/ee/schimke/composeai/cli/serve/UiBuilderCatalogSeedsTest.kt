package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every surface offers exactly the catalog's published templates, or a generic blank. */
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
  fun `a catalog without published templates starts with a generic blank`() {
    val seeds = UiBuilderCatalogSeeds.EMPTY
    for (catalog in listOf("m3-catalog", "wear-m3", "remote-m3", "a2ui-catalog", "new-catalog")) {
      assertEquals(setOf(UiBuilderNewDesignSeed.BLANK_TEMPLATE), seeds.templateIds(catalog))
      assertEquals(UiBuilderNewDesignSeed.BLANK_TEMPLATE, seeds.defaultTemplate(catalog))
    }
  }

  @Test
  fun `an owned catalog offers what it publishes, in its order, and starts from the first`() {
    val seeds = UiBuilderCatalogSeeds() { published.takeIf { _ -> it == "wear-m3" } }
    assertEquals(listOf("wear-screen", "wear-list"), seeds.templateIds("wear-m3").toList())
    assertEquals("wear-screen", seeds.defaultTemplate("wear-m3"))
    // Catalogs without published templates use a generic blank.
    assertEquals(setOf(UiBuilderNewDesignSeed.BLANK_TEMPLATE), seeds.templateIds("m3-catalog"))
  }
}
