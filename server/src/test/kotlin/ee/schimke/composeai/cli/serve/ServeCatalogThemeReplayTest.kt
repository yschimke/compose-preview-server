package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A published catalog's declared themes, from `catalog.json`'s `themes[]` and each theme's DTCG
 * token file, reaching the replay lane: the token roles become the `WearM3.<role>` named values a
 * Remote Material 3 document draws through, in the `#AARRGGBB` form the seeds take.
 */
class ServeCatalogThemeReplayTest {

  @Test
  fun `dtcg colour roles become WearM3 named seeds`() {
    val colors =
      ServeThemeReplay.colorsFromDtcg(
        """
        {
          "color": {
            "primary": { "${'$'}type": "color", "${'$'}value": "#8a5029" },
            "surfaceContainerHigh": { "${'$'}type": "color", "${'$'}value": "#ffffff80" },
            "broken": { "${'$'}type": "color", "${'$'}value": "salmon" }
          },
          "spacing": { "small": { "${'$'}type": "dimension", "${'$'}value": "4px" } }
        }
        """
      )

    assertEquals(
      mapOf("WearM3.primary" to "#FF8A5029", "WearM3.surfaceContainerHigh" to "#80FFFFFF"),
      colors,
    )
  }

  @Test
  fun `unparseable token files map nothing`() {
    assertTrue(ServeThemeReplay.colorsFromDtcg("not json").isEmpty())
    assertTrue(ServeThemeReplay.colorsFromDtcg("""{"spacing":{}}""").isEmpty())
  }

  @Test
  fun `a catalog's declared themes are listed and replayable without a manifest`() {
    val dir = java.nio.file.Files.createTempDirectory("catalog").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    val mushroom =
      ServeTheme("Mushroom Light", "ee.schimke.ha.cards.MushroomLight", "Terrazzo", "light")
    val kiosk = ServeTheme("Kiosk Dark", "ee.schimke.ha.cards.KioskDark", "Terrazzo", "dark")
    val host =
      ServeBundleHost(
        dir,
        label = "ha-cards",
        isCatalog = true,
        catalogThemes =
          listOf(
            CatalogTheme(mushroom, mapOf("WearM3.primary" to "#FF8A5029")),
            CatalogTheme(kiosk, emptyMap()),
          ),
      )

    assertEquals(listOf(mushroom, kiosk), host.declaredThemes)
    assertEquals(
      mapOf("WearM3.primary" to "#FF8A5029"),
      host.themeReplayColors(mushroom.providerFqn),
    )
    // A theme whose token file could not be read stays listed but is not offered for replay.
    assertEquals(listOf(mushroom), host.replayableThemes())
  }
}
