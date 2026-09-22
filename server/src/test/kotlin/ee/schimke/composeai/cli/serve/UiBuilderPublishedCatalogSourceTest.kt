package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals

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

}
