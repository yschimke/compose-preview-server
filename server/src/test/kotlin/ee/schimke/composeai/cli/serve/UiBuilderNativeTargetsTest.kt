package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Which daemon a builder catalog's native render goes to, and when it is refused (#1867). */
class UiBuilderNativeTargetsTest {

  private fun resolve(
    declared: String?,
    served: String?,
    platform: UiBuilderCatalogPlatform?,
    catalog: String = "m3-catalog",
  ) =
    UiBuilderNativeTargets.resolve(
      builderCatalog = catalog,
      served = "$catalog-served",
      declaredBackend = declared,
      servedBackend = served,
      platform = platform,
    )

  private fun target(resolution: UiBuilderNativeTargets.Resolution) =
    assertIs<UiBuilderNativeTargets.Resolution.Target>(resolution).target

  @Test
  fun `a mobile catalog declaring android compiles on a desktop bundle through CMP`() {
    // m3-catalog's case: the policy said Robolectric, the served `:catalog` bundle is desktop.
    assertEquals(
      UiBuilderNativeTarget("m3-catalog-served", UiBuilderGeneratedCompose.COMPOSE_CMP),
      target(resolve("android", "desktop", UiBuilderCatalogPlatform.MOBILE)),
    )
  }

  @Test
  fun `a wear or remote compose catalog is never routed to a desktop bundle`() {
    for (platform in
      listOf(UiBuilderCatalogPlatform.WEAR, UiBuilderCatalogPlatform.REMOTE_COMPOSE, null)) {
      val refused =
        assertIs<UiBuilderNativeTargets.Resolution.Refused>(
          resolve("android", "desktop", platform, catalog = "wear-m3"),
          "$platform",
        )
      assertTrue("`android`" in refused.reason && "`desktop`" in refused.reason, refused.reason)
      assertTrue("--ui-builder-native-catalog wear-m3=" in refused.reason, refused.reason)
    }
  }

  @Test
  fun `an android bundle, or a host reporting none, keeps the android daemon`() {
    for (served in listOf("android", null)) {
      assertEquals(
        UiBuilderGeneratedCompose.COMPOSE_ANDROID,
        target(resolve("android", served, UiBuilderCatalogPlatform.MOBILE)).confType,
      )
      assertEquals(
        UiBuilderGeneratedCompose.COMPOSE_ANDROID,
        target(resolve("android", served, UiBuilderCatalogPlatform.WEAR)).confType,
      )
    }
  }

  @Test
  fun `a catalog declaring desktop, or nothing, compiles through CMP`() {
    for (declared in listOf("desktop", null)) {
      assertEquals(
        UiBuilderGeneratedCompose.COMPOSE_CMP,
        target(resolve(declared, "desktop", UiBuilderCatalogPlatform.MOBILE)).confType,
      )
    }
  }
}
