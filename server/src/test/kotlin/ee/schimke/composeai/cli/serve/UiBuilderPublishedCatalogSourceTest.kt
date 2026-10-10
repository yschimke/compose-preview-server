package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiBuilderPublishedCatalogSourceTest {

  @Test
  fun `Wear Builder identity loads policy from its delivery catalog`() {
    assertEquals(
      "wear-m3-catalog",
      uiBuilderPublishedSourceSystem(
        builderSystem = "wear-m3",
        nativeCatalogs = mapOf("wear-m3" to "wear-m3-catalog"),
      ),
    )
  }

  @Test
  fun `an unmapped Builder catalog loads policy from itself`() {
    assertEquals("remote-m3", uiBuilderPublishedSourceSystem("remote-m3", emptyMap()))
  }

  @Test
  fun `Wear's record comes from its delivery catalog, not the served catalog sharing its id`() {
    // The box serves both: compose-ai-tools' Wear harness as the catalog `wear-m3`, and the Builder
    // catalog `wear-m3` delivered as `wear-m3-catalog`. Asking the store for `wear-m3` returned the
    // harness record, the published policy would not compose against it, and owned wear-m3 offered
    // only `blank`.
    val harness = File("harness-components.json")
    val delivered = File("wear-m3-catalog-components.json")
    val store = mapOf("wear-m3" to harness, "wear-m3-catalog" to delivered)
    assertEquals(
      delivered,
      uiBuilderCatalogRecord(
        builderSystem = "wear-m3",
        nativeCatalogs = mapOf("wear-m3" to "wear-m3-catalog"),
        served = store::get,
        startup = { null },
      ),
    )
  }

  @Test
  fun `before its delivery catalog loads, the record fetched at startup is used`() {
    val fetched = File("fetched.json")
    assertEquals(
      fetched,
      uiBuilderCatalogRecord(
        builderSystem = "wear-m3",
        nativeCatalogs = mapOf("wear-m3" to "wear-m3-catalog"),
        // Only the harness has loaded; it must not stand in for the delivery catalog.
        served = mapOf("wear-m3" to File("harness.json"))::get,
        startup = mapOf("wear-m3" to fetched)::get,
      ),
    )
  }

  @Test
  fun `an unmapped Builder catalog's record is its own`() {
    val own = File("remote-m3.json")
    assertEquals(
      own,
      uiBuilderCatalogRecord("remote-m3", emptyMap(), mapOf("remote-m3" to own)::get) { null },
    )
    assertNull(uiBuilderCatalogRecord("remote-m3", emptyMap(), { null }) { null })
  }
}
