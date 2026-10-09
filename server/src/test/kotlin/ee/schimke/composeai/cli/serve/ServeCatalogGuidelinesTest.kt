package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeCatalogGuidelinesTest {
  private fun guidelines(
    catalog: String,
    schema: String = "compose-ui-builder/catalog-guidelines/v1",
  ) =
    """
    {"schema": "$schema", "catalog": "$catalog", "platform": "wear", "version": 2,
     "frames": [{"kind": "device"}],
     "rules": [{"id": "own.rule", "kind": "structure", "severity": "info", "guidance": "g",
                "check": "ok?", "source": "https://developer.android.com/x"}]}
    """
      .trimIndent()

  @Test
  fun `a catalog's own guidelines are kept, and anything else is ignored`() {
    val logged = mutableListOf<String>()
    val store = ServeCatalogGuidelines(log = { logged += it })

    assertTrue(store.accept("wear-m3", guidelines("wear-m3").toByteArray(), "url"))
    assertEquals(2, store.forCatalog("wear-m3")!!.guidelines.version)

    assertFalse(store.accept("remote-m3", guidelines("wear-m3").toByteArray(), "url"))
    assertFalse(store.accept("remote-m3", "{not json".toByteArray(), "url"))
    assertFalse(store.accept("remote-m3", guidelines("remote-m3", "other/v1").toByteArray(), "url"))
    assertNull(store.forCatalog("remote-m3"))
    assertEquals(3, logged.size, logged.toString())

    store.remove("wear-m3")
    assertNull(store.forCatalog("wear-m3"))
  }

  @Test
  fun `a result may name the catalog's own rules and the bundled ones`() {
    val store = ServeCatalogGuidelines(log = {})
    assertEquals(DesignGuidelineRuleSet.Bundled, store.ruleSetFor("wear-m3"))
    store.accept("wear-m3", guidelines("wear-m3").toByteArray(), "url")
    val ids = store.ruleSetFor("wear-m3").rules.map { it.id }
    assertEquals("own.rule", ids.first())
    assertTrue(DesignGuidelineRuleSet.Bundled.rules.all { it.id in ids })
    assertEquals(2, store.ruleSetFor("wear-m3").version)
  }

  @Test
  fun `a local module's guidelines are keyed by the catalog its ui-builder json declares`() {
    val dir = Files.createTempDirectory("catalog-guidelines").toFile()
    try {
      dir.resolve("ui-builder.json").writeText("""{"schema":"x","catalog":{"id":"wear-m3"}}""")
      dir.resolve("ui-builder.guidelines.json").writeText(guidelines("wear-m3"))
      val store = ServeCatalogGuidelines(log = {})
      assertTrue(store.loadLocal(dir.resolve("ui-builder.guidelines.json")))
      assertEquals(setOf("wear-m3"), store.catalogIds())
      assertFalse(store.loadLocal(dir.resolve("missing.json")))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the published file sits beside the catalog's ui-builder json`() {
    assertEquals("ui-builder.guidelines.json", ServeCatalogGuidelines.siblingOf("ui-builder.json"))
    assertEquals(
      "catalog/ui-builder.guidelines.json",
      ServeCatalogGuidelines.siblingOf("catalog/ui-builder.json"),
    )
    assertEquals(
      "/api/ui-builder/v1/catalogs/wear-m3/guidelines",
      ServeCatalogGuidelines.routeFor("wear-m3"),
    )
  }
}
