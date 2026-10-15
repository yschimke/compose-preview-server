package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

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

  /**
   * A static catalog has no live lane, so its default render can only answer with the baked
   * snapshot, which ignores the theme's seeds and is refused (409) for dropping them. A declared
   * theme on a replayed preview there goes to the cmp-jvm lane instead, which draws the captured
   * document with the seeds. With the desktop player absent it keeps the refusal.
   */
  @Test
  fun `a static catalog draws a declared theme through the replay lane`() {
    val dir = java.nio.file.Files.createTempDirectory("catalog").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/Card.png")
      .writeBytes(
        ByteArrayOutputStream()
          .also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", it) }
          .toByteArray()
      )
    File(dir, "ir").mkdirs()
    File(dir, "ir/Card.rc").writeBytes(byteArrayOf(1, 2, 3))
    val theme =
      ServeTheme("Mushroom Light", "ee.schimke.ha.cards.MushroomLight", "Terrazzo", "light")
    val host =
      ServeBundleHost(
        dir,
        label = "ha-cards",
        isCatalog = true,
        catalogThemes = listOf(CatalogTheme(theme, mapOf("WearM3.primary" to "#FF8A5029"))),
      )
    val registry = ServeSessionRegistry(open = { null })
    registry.register("ha-cards", host = host, pinned = true)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = registry,
          defaultSessionId = "ha-cards",
          isPublic = true,
        )
        .also { it.start() }
    val client = OkHttpClient()
    fun render(): Int =
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.port}/ha-cards/render/Card.png" +
                "?themeProvider=${theme.providerFqn}"
            )
            .build()
        )
        .execute()
        .use { it.code }
    try {
      assertEquals(409, render(), "without the desktop player only the baked snapshot can answer")

      // Empty sidecar jars make the player look installed; the render itself then fails in the
      // subprocess, which is enough to show the request reached the replay lane, not the refusal.
      val jars =
        java.nio.file.Files.createTempDirectory("sidecar").toFile().also { it.deleteOnExit() }
      File(jars, "x.jar").writeText("")
      System.setProperty("composeai.cli.libRcjvmDir", jars.absolutePath)
      System.setProperty("composeai.cli.libDaemonDesktopDir", jars.absolutePath)
      try {
        assertNotEquals(409, render(), "the theme is drawn by the replay lane, not refused")
      } finally {
        System.clearProperty("composeai.cli.libRcjvmDir")
        System.clearProperty("composeai.cli.libDaemonDesktopDir")
      }
    } finally {
      server.stop()
    }
  }
}
