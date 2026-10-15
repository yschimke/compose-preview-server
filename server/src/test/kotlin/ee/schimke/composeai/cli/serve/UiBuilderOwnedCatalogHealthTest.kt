package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiBuilderOwnedCatalogHealthTest {
  private val logged = mutableListOf<String>()
  private val health =
    UiBuilderOwnedCatalogHealth(owns = { it in setOf("wear-m3", "m3-catalog") }, log = logged::add)
  private val enabled = setOf("wear-m3", "m3-catalog", "remote-m3")

  @Test
  fun `an owned catalog whose publish does not compose is withheld with its reason, not served`() {
    health.withhold("wear-m3", "ui-builder.json was generated against a record not available here")

    assertTrue(health.isWithheld("wear-m3"))
    assertEquals(setOf("m3-catalog", "remote-m3"), health.served(enabled))
    assertEquals(
      mapOf(
        "wear-m3" to
          "unavailable: ui-builder.json was generated against a record not available here"
      ),
      health.problems(),
    )
    assertTrue(logged.single().contains("ERROR UI-builder catalog wear-m3"), logged.toString())
    assertTrue(logged.single().contains("no built-in fallback"), logged.toString())
  }

  @Test
  fun `a refresh that composes again restores the catalog and says so`() {
    health.withhold("wear-m3", "no published ui-builder.json")
    val before = health.stateOf("wear-m3")

    health.healthy("wear-m3")

    assertFalse(health.isWithheld("wear-m3"))
    assertEquals(enabled, health.served(enabled))
    assertTrue(health.problems().isEmpty())
    // A changed state is what makes the refresher swap the served catalogs.
    assertTrue(before != health.stateOf("wear-m3"))
    assertEquals("serve: UI-builder catalog wear-m3 is available again", logged.last())
  }

  @Test
  fun `the same reason is said once, a new one again`() {
    repeat(3) { health.withhold("m3-catalog", "no published ui-builder.json") }
    health.withhold("m3-catalog", "does not compose")

    assertEquals(2, logged.size, logged.toString())
  }

  @Test
  fun `a catalog that is not owned keeps today's behaviour and is never recorded`() {
    health.withhold("remote-m3", "does not compose")
    health.degrade("remote-m3", "templates do not read")

    assertFalse(health.isWithheld("remote-m3"))
    assertEquals(enabled, health.served(enabled))
    assertTrue(health.problems().isEmpty())
    assertTrue(logged.isEmpty())
  }

  @Test
  fun `unreadable templates degrade a served catalog until they read`() {
    health.degrade("m3-catalog", "ui-builder/designs/hello.json is missing")

    assertEquals(enabled, health.served(enabled), "a degraded catalog is still served")
    assertTrue(health.problems().getValue("m3-catalog").startsWith("served, but its templates"))

    health.templatesRead("m3-catalog")
    assertTrue(health.problems().isEmpty())
  }

  @Test
  fun `a withheld catalog restored with unreadable templates stays reported as degraded`() {
    health.withhold("wear-m3", "no published ui-builder.json")

    // The order a refresh records them in: templates first, then the withhold is lifted.
    health.degrade("wear-m3", "ui-builder/designs/hello.json is missing")
    health.healthy("wear-m3")

    assertFalse(health.isWithheld("wear-m3"))
    assertEquals(enabled, health.served(enabled))
    assertTrue(health.problems().getValue("wear-m3").startsWith("served, but its templates"))
  }

  /**
   * What `ServeRunner` wires: built-in catalogs fall back, anything else is withheld when it fails.
   */
  private val wired =
    UiBuilderOwnedCatalogHealth(
      owns = { false },
      log = logged::add,
      builtIn = UI_BUILDER_BUILT_IN_CATALOGS::contains,
    )

  @Test
  fun `an unowned catalog this build cannot define is withheld, not handed on to fail the builder`() {
    val served = setOf("m3-catalog", "a2ui-catalog")
    assertTrue(wired.withhold("a2ui-catalog", "no published ui-builder.json could be read"))

    assertTrue(wired.isWithheld("a2ui-catalog"))
    assertEquals(setOf("m3-catalog"), wired.served(served))
    assertTrue(logged.single().contains("has no built-in definition"), logged.toString())
    // Templates are read only for owned catalogs, so nothing degrades it.
    wired.degrade("a2ui-catalog", "templates do not read")
    assertEquals(setOf("a2ui-catalog"), wired.problems().keys)
  }

  @Test
  fun `an unowned non-built-in catalog whose publish does not compose is withheld`() {
    // The `PublishedUiBuilderCatalog.Result.Unusable` path: a file was read but did not compose.
    // Withholding must not depend on ownership, or the catalog reaches the executor with no
    // published entry and fails the whole builder.
    val served = setOf("m3-catalog", "glimmer-catalog")

    assertTrue(wired.withhold("glimmer-catalog", "does not compose against its record"))

    assertEquals(setOf("m3-catalog"), wired.served(served))
    assertEquals(
      mapOf("glimmer-catalog" to "unavailable: does not compose against its record"),
      wired.problems(),
    )
  }

  @Test
  fun `withholding an owned catalog reports that it was withheld, even on a repeated reason`() {
    assertTrue(health.withhold("wear-m3", "does not compose"))
    assertTrue(health.withhold("wear-m3", "does not compose"))
    assertFalse(health.withhold("remote-m3", "does not compose"))
  }

  @Test
  fun `an unowned built-in catalog still falls back to its built-in definition`() {
    assertFalse(wired.withhold("m3-catalog", "no published ui-builder.json could be read"))

    assertFalse(wired.isWithheld("m3-catalog"))
    assertTrue(logged.isEmpty(), logged.toString())
  }

  @Test
  fun `the built-in set names only what compose-ui-builder still defines in Kotlin`() {
    assertEquals(setOf("m3-catalog"), UI_BUILDER_BUILT_IN_CATALOGS)
    assertTrue(wired.withhold("remote-m3", "no published ui-builder.json could be read"))
    assertTrue(wired.withhold("wear-m3", "no published ui-builder.json could be read"))
  }
}
