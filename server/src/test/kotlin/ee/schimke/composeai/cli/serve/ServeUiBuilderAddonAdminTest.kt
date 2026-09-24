package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * UI-builder add-ons: builder catalogs an instance serves beside the built-in `m3-catalog`,
 * declared in its `catalogs.json` rather than baked into the image every adopter inherits.
 */
class ServeUiBuilderAddonAdminTest {

  private val fs = FakeFileSystem()
  private val file =
    ServeCatalogsConfigFile("/config/catalogs.json".toPath(), fs).also {
      it.save(
        ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry("m3-catalog", "a/b")))
      )
    }
  private val wear = ServeCatalogsConfig.UiBuilderAddon("wear-m3", source = "wear-m3-catalog")
  private val remote = ServeCatalogsConfig.UiBuilderAddon("remote-m3")

  private fun admin(serving: Set<String> = setOf("m3-catalog")) =
    ServeUiBuilderAddonAdmin(configFile = file, serving = { serving }, onLog = {})

  @Test
  fun `catalogs json round-trips add-ons and validates them`() {
    val config =
      ServeCatalogsConfig.parse(
        """{ "uiBuilder": { "addons": [ { "id": "wear-m3", "source": "wear-m3-catalog" },
           { "id": "remote-m3" } ] }, "catalogs": [] }"""
      )
    assertEquals(listOf(wear, remote), config.uiBuilder?.addons)
    assertEquals("remote-m3", remote.sourceSystem)
    assertEquals("wear-m3-catalog", wear.sourceSystem)
    assertEquals(config, ServeCatalogsConfig.parse(ServeCatalogsConfig.encode(config)))
    assertEquals(emptyList(), config.problems())

    // A config that says nothing has no add-ons.
    assertNull(ServeCatalogsConfig.parse("""{ "catalogs": [] }""").uiBuilder)

    val bad =
      ServeCatalogsConfig(
        uiBuilder =
          ServeCatalogsConfig.UiBuilder(
            listOf(
              ServeCatalogsConfig.UiBuilderAddon("m3-catalog"),
              ServeCatalogsConfig.UiBuilderAddon("../x"),
              ServeCatalogsConfig.UiBuilderAddon("wear-m3", source = "a/b"),
              remote,
              remote,
            )
          )
      )
    val problems = bad.problems()
    assertTrue(problems.any { "built-in UI-builder catalog" in it }, problems.toString())
    assertTrue(problems.any { "invalid UI-builder add-on id" in it }, problems.toString())
    assertTrue(problems.any { "invalid source" in it }, problems.toString())
    assertTrue(
      problems.any { "duplicate UI-builder add-on 'remote-m3'" in it },
      problems.toString(),
    )
  }

  @Test
  fun `adding an add-on writes it and owes a restart`() {
    val result = assertIs<ServeUiBuilderAddonAdmin.Result.Ok>(admin().add(wear))
    assertTrue(result.restartRequired)
    assertEquals(listOf(wear), file.load().uiBuilder?.addons)
    // The rest of the file is untouched.
    assertEquals(listOf("m3-catalog"), file.load().catalogs.map { it.system })
    // Posting the same declaration again is the reconcile's "already present".
    assertIs<ServeUiBuilderAddonAdmin.Result.Conflict>(admin().add(wear))
  }

  @Test
  fun `an add-on already serving owes no restart`() {
    val result =
      assertIs<ServeUiBuilderAddonAdmin.Result.Ok>(
        admin(serving = setOf("m3-catalog", "remote-m3")).add(remote)
      )
    assertEquals(false, result.restartRequired)
  }

  @Test
  fun `re-pointing an add-on replaces it rather than duplicating it`() {
    admin().add(wear)
    admin().add(wear.copy(source = "wear-m3-catalog-next"))
    assertEquals(listOf(wear.copy(source = "wear-m3-catalog-next")), file.load().uiBuilder?.addons)
  }

  @Test
  fun `removing an add-on drops it, and the section with the last one`() {
    admin().add(wear)
    admin().add(remote)
    val removed =
      assertIs<ServeUiBuilderAddonAdmin.Result.Ok>(
        admin(serving = setOf("m3-catalog", "wear-m3")).remove("wear-m3")
      )
    assertTrue(removed.restartRequired)
    assertEquals(listOf(remote), file.load().uiBuilder?.addons)
    admin().remove("remote-m3")
    assertNull(file.load().uiBuilder)
    // The message the reconcile's delete helper reads as "nothing to retire".
    val absent = assertIs<ServeUiBuilderAddonAdmin.Result.Conflict>(admin().remove("wear-m3"))
    assertTrue("is not configured" in absent.reason)
  }

  @Test
  fun `the built-in catalog is not an add-on`() {
    assertIs<ServeUiBuilderAddonAdmin.Result.Invalid>(
      admin().add(ServeCatalogsConfig.UiBuilderAddon("m3-catalog"))
    )
  }

  @Test
  fun `without a config file nothing can be declared`() {
    val admin = ServeUiBuilderAddonAdmin(configFile = null, serving = { emptySet() }, onLog = {})
    assertIs<ServeUiBuilderAddonAdmin.Result.Unavailable>(admin.add(wear))
    assertIs<ServeUiBuilderAddonAdmin.Result.Unavailable>(admin.remove("wear-m3"))
  }
}
