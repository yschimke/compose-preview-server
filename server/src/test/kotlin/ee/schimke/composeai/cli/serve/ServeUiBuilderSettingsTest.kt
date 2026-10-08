package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.ServeCatalogsConfig.UiBuilderCatalogSettings
import ee.schimke.composeai.cli.serve.ServeCatalogsConfig.UiBuilderSettings
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/**
 * `catalogs.json`'s `uiBuilder` block over the `SERVE_UI_BUILDER_*` environment: the block names
 * only what changes, so a box moving onto it keeps everything its `.env` already serves.
 */
class ServeUiBuilderSettingsTest {

  /** A box like preview.coo.ee: three catalogs, the entrypoint's derived published set, a pack. */
  private val environment =
    ServeUiBuilderSettings.Effective(
      catalogs = setOf("m3-catalog", "remote-m3", "wear-m3"),
      publishedCatalogs = setOf("m3-catalog", "remote-m3", "wear-m3"),
      catalogOwnership = CatalogOwnership.NONE,
      nativeCatalogs = mapOf("wear-m3" to "wear-m3-catalog"),
      packs = mapOf("confetti-mobile" to "mobile"),
      widgetPlayer = UiBuilderWidgetPlayer.DEFAULT,
      // As the image entrypoint passes it with --ui-builder-published-default.
      publishedDefault = setOf("m3-catalog", "remote-m3", "wear-m3", "remote-widgets"),
    )

  private fun resolve(settings: UiBuilderSettings?) =
    ServeUiBuilderSettings.resolve(environment, settings)

  @Test
  fun `no block, or an empty one, changes nothing`() {
    assertEquals(environment, resolve(null).effective)
    assertEquals(environment, resolve(UiBuilderSettings()).effective)
  }

  @Test
  fun `serving a catalog adds it to what the environment serves, published by default`() {
    val effective =
      resolve(
          UiBuilderSettings(
            catalogs = mapOf("remote-widgets" to UiBuilderCatalogSettings(serve = true))
          )
        )
        .effective
    assertEquals(setOf("m3-catalog", "remote-m3", "wear-m3", "remote-widgets"), effective.catalogs)
    // remote-widgets has no built-in catalog: like the entrypoint, it is read from its own file.
    assertEquals(effective.catalogs, effective.publishedCatalogs)
    // Everything else the environment set is kept.
    assertEquals(environment.nativeCatalogs, effective.nativeCatalogs)
    assertEquals(environment.packs, effective.packs)
  }

  @Test
  fun `a catalog outside the published default is served from the build's own definition`() {
    val effective =
      resolve(
          UiBuilderSettings(
            catalogs = mapOf("a2ui-catalog" to UiBuilderCatalogSettings(serve = true))
          )
        )
        .effective
    assertTrue("a2ui-catalog" in effective.catalogs)
    assertTrue("a2ui-catalog" !in effective.publishedCatalogs!!)
  }

  @Test
  fun `withdrawing a catalog also drops what only made sense while it was served`() {
    val resolution =
      resolve(
        UiBuilderSettings(
          catalogs = mapOf("wear-m3" to UiBuilderCatalogSettings(serve = false, owned = true))
        )
      )
    val effective = resolution.effective
    assertEquals(setOf("m3-catalog", "remote-m3"), effective.catalogs)
    assertEquals(setOf("m3-catalog", "remote-m3"), effective.publishedCatalogs)
    // An owned catalog that is no longer served is reported and dropped, not a boot failure.
    assertTrue(!effective.catalogOwnership.owns("wear-m3"))
    assertTrue(resolution.problems.isNotEmpty())
  }

  @Test
  fun `the block cannot serve a catalog this machine opted out of`() {
    // SERVE_UI_BUILDER_WEAR=0: the entrypoint strips wear-m3 and marks it unavailable.
    val noWear =
      environment.copy(
        catalogs = setOf("m3-catalog", "remote-m3"),
        publishedCatalogs = setOf("m3-catalog", "remote-m3"),
        unavailable = setOf("wear-m3"),
      )
    val resolution =
      ServeUiBuilderSettings.resolve(
        noWear,
        UiBuilderSettings(catalogs = mapOf("wear-m3" to UiBuilderCatalogSettings(serve = true))),
      )
    assertEquals(setOf("m3-catalog", "remote-m3"), resolution.effective.catalogs)
    assertTrue(resolution.problems.isNotEmpty())
  }

  @Test
  fun `a hand-edited block that would not validate leaves the box on its environment`() {
    val fs = okio.fakefilesystem.FakeFileSystem()
    val path = "/config/catalogs.json".toPath()
    ServeCatalogsConfigFile(path, fs)
      .save(
        ServeCatalogsConfig(
          uiBuilder =
            UiBuilderSettings(
              catalogs = mapOf("remote-widgets" to UiBuilderCatalogSettings(serve = true)),
              packs = mapOf("confetti-mobile" to "toaster"),
            )
        )
      )
    val logs = mutableListOf<String>()
    val options =
      ServeCommandOptions(
        args = listOf("--catalogs-file", path.toString()),
        defaultTimeoutSeconds = 600L,
        previewMatcher = { _, _, _, _, _, _ -> true },
      )
    val overlaid = ServeUiBuilderSettings.overlay(options, logs::add, fs)
    assertTrue(overlaid === options, "an invalid block must not be applied")
    assertTrue(logs.any { "toaster" in it }, logs.toString())
  }

  @Test
  fun `the block cannot withdraw every catalog`() {
    val resolution =
      resolve(
        UiBuilderSettings(
          catalogs = environment.catalogs.associateWith { UiBuilderCatalogSettings(serve = false) }
        )
      )
    assertEquals(environment.catalogs, resolution.effective.catalogs)
    assertTrue(resolution.problems.isNotEmpty())
  }

  @Test
  fun `ownership, native catalogs, packs and the widget player override per entry`() {
    val effective =
      resolve(
          UiBuilderSettings(
            catalogs =
              mapOf(
                "remote-m3" to UiBuilderCatalogSettings(owned = true),
                "wear-m3" to UiBuilderCatalogSettings(nativeCatalog = ""),
              ),
            packs = mapOf("confetti-mobile" to null, "confetti-wear" to "wear"),
            widgetPlayer = "androidx",
          )
        )
        .effective
    assertTrue(effective.catalogOwnership.owns("remote-m3"))
    assertTrue(!effective.catalogOwnership.owns("m3-catalog"))
    assertEquals(emptyMap(), effective.nativeCatalogs)
    assertEquals(mapOf("confetti-wear" to "wear"), effective.packs)
    assertEquals("androidx", effective.widgetPlayer.flagValue)
  }

  @Test
  fun `an environment publishing everything stays that way unless a catalog opts out`() {
    val all = environment.copy(publishedCatalogs = null)
    assertNull(
      ServeUiBuilderSettings.resolve(
          all,
          UiBuilderSettings(
            catalogs = mapOf("remote-widgets" to UiBuilderCatalogSettings(serve = true))
          ),
        )
        .effective
        .publishedCatalogs
    )
    assertEquals(
      setOf("m3-catalog", "remote-m3"),
      ServeUiBuilderSettings.resolve(
          all,
          UiBuilderSettings(
            catalogs = mapOf("wear-m3" to UiBuilderCatalogSettings(published = false))
          ),
        )
        .effective
        .publishedCatalogs,
    )
  }

  @Test
  fun `validation refuses what the flags would refuse`() {
    assertNotNull(
      ServeUiBuilderSettings.validate(
        UiBuilderSettings(catalogs = mapOf("../etc" to UiBuilderCatalogSettings(serve = true)))
      )
    )
    assertNotNull(
      ServeUiBuilderSettings.validate(UiBuilderSettings(packs = mapOf("confetti" to "toaster")))
    )
    assertNotNull(ServeUiBuilderSettings.validate(UiBuilderSettings(widgetPlayer = "vlc")))
    assertNotNull(
      ServeUiBuilderSettings.validate(
        UiBuilderSettings(
          catalogs = mapOf("remote-m3" to UiBuilderCatalogSettings(published = false, owned = true))
        )
      )
    )
    assertNull(
      ServeUiBuilderSettings.validate(
        UiBuilderSettings(
          catalogs = mapOf("remote-widgets" to UiBuilderCatalogSettings(serve = true))
        )
      )
    )
  }

  @Test
  fun `a block the server cannot resolve is refused by the admin and leaves the file alone`() {
    val fs = okio.fakefilesystem.FakeFileSystem()
    val file = ServeCatalogsConfigFile("/config/catalogs.json".toPath(), fs)
    // Every served catalog reads its published file, so ownership reaches the cutover's id check.
    val all = environment.copy(publishedCatalogs = null)
    val admin = ServeUiBuilderSettingsAdmin(file, all, all)
    // Upper case passes the flag's id pattern but not the cutover's.
    val result =
      admin.set(
        UiBuilderSettings(
          catalogs = mapOf("Remote-M3" to UiBuilderCatalogSettings(serve = true, owned = true))
        )
      )
    assertTrue(result is ServeUiBuilderSettingsAdmin.Result.Invalid, result.toString())
    assertNull(file.load().uiBuilder)
  }

  @Test
  fun `the image entrypoint passes the list it derives the published catalogs from`() {
    val entrypoint =
      generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
        .map { java.io.File(it, "deploy/image/entrypoint.sh") }
        .first { it.isFile }
        .readText()
    // One variable feeds both the entrypoint's own derivation and the server's flag.
    assertTrue("for candidate in \${ui_builder_published_default}; do" in entrypoint)
    assertTrue(
      "--ui-builder-published-default \"\${ui_builder_published_default// /,}\"" in entrypoint
    )
  }

  @Test
  fun `a shadowed catalog is reported on and served as it was`() {
    val resolution =
      resolve(
        UiBuilderSettings(catalogs = mapOf("wear-m3" to UiBuilderCatalogSettings(shadow = true)))
      )
    assertEquals(setOf("wear-m3"), resolution.effective.shadowCatalogs)
    assertEquals(environment.copy(shadowCatalogs = setOf("wear-m3")), resolution.effective)
    assertEquals(emptyList(), resolution.problems)
    assertEquals(listOf("wear-m3"), resolution.effective.describe().shadowCatalogs)
  }

  @Test
  fun `only a published catalog that is not owned yet can be shadowed`() {
    val resolution =
      resolve(
        UiBuilderSettings(
          catalogs =
            mapOf(
              "m3-catalog" to UiBuilderCatalogSettings(published = false, shadow = true),
              "remote-m3" to UiBuilderCatalogSettings(owned = true, shadow = true),
              "a2ui-catalog" to UiBuilderCatalogSettings(shadow = true),
              "wear-m3" to UiBuilderCatalogSettings(shadow = true),
            )
        )
      )
    assertEquals(setOf("wear-m3"), resolution.effective.shadowCatalogs)
    assertTrue(
      resolution.problems.any { "a2ui-catalog, m3-catalog, remote-m3" in it && "shadow" in it },
      resolution.problems.toString(),
    )
  }

  @Test
  fun `the overlay answers the shadow set`() {
    val overlay =
      ServeUiBuilderSettings.Overlay(
        ServeCommandOptions(
          args = emptyList(),
          defaultTimeoutSeconds = 600L,
          previewMatcher = { _, _, _, _, _, _ -> true },
        ),
        environment.copy(shadowCatalogs = setOf("wear-m3")),
      )
    assertEquals(setOf("wear-m3"), overlay.uiBuilderShadowCatalogs)
  }
}
