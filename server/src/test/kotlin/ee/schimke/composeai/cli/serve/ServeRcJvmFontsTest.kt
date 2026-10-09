package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fonts the server-side cmp-jvm lane resolves `google:Roboto Flex` through.
 *
 * The first test is the regression: it reads the fonts the distribution packages inside
 * `rc-player-wasm/` (`stageRcPlayerWasm`), not a source tree, because the outage was a server
 * install that carried no manifest at all — every remote-m3 card then drew in the worker's fallback
 * sans, 26–31% off the published captures, and nothing reported a failure.
 */
class ServeRcJvmFontsTest {

  private var savedProperty: String? = null

  @BeforeTest
  fun saveProperty() {
    savedProperty = System.getProperty(ServeRcJvmFonts.PROPERTY)
    System.clearProperty(ServeRcJvmFonts.PROPERTY)
  }

  @AfterTest
  fun restoreProperty() {
    savedProperty?.let { System.setProperty(ServeRcJvmFonts.PROPERTY, it) }
      ?: System.clearProperty(ServeRcJvmFonts.PROPERTY)
  }

  @Test
  fun `the distribution packages a manifest whose default family is the documents' Roboto Flex`() {
    val packaged =
      File(
        assertNotNull(
          System.getProperty("composeai.test.rcJvmFontsDir"),
          "the test task passes the staged player fonts directory",
        )
      )
    val manifest = File(packaged, ServeRcJvmFonts.MANIFEST)
    assertTrue(manifest.isFile, "the distribution's player fonts carry no fonts.json: $packaged")

    val families =
      Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("families").jsonArray.map {
        it.jsonObject
      }
    // `google:Roboto Flex` resolves by name, and a CoreText naming no family resolves through the
    // `default` role — the remote-m3 catalog leans on both.
    val default = families.single { it["role"]?.jsonPrimitive?.content == "default" }
    assertEquals("Roboto Flex", default.getValue("name").jsonPrimitive.content)

    // Every face the manifest names must ship beside it: the worker drops a family whose file is
    // missing, silently, back to the fallback face.
    for (family in families) {
      for (font in family.getValue("fonts").jsonArray) {
        val file = File(packaged, font.jsonObject.getValue("file").jsonPrimitive.content)
        assertTrue(file.isFile, "fonts.json names ${file.name}, which is not packaged")
        val head = file.readBytes().take(4).map { it.toInt() and 0xff }
        assertEquals(listOf(0x00, 0x01, 0x00, 0x00), head, "${file.name} is not a TrueType file")
      }
    }
  }

  @Test
  fun `a server install resolves its packaged player fonts directory`() {
    val install = createTempDirectory("rcjvm-install").toFile()
    try {
      assertNull(ServeRcJvmFonts.packagedDir(appHome = null, installDir = install))

      val fonts = File(install, ServeRcJvmFonts.PACKAGED_DIR).apply { mkdirs() }
      // A directory without a manifest does not count: the worker would ignore it.
      assertNull(ServeRcJvmFonts.packagedDir(appHome = null, installDir = install))

      File(fonts, ServeRcJvmFonts.MANIFEST).writeText("""{"families":[]}""")
      assertEquals(fonts, ServeRcJvmFonts.packagedDir(appHome = null, installDir = install))
      // An explicit app home is consulted first, and falls through when it has no fonts.
      assertEquals(
        fonts,
        ServeRcJvmFonts.packagedDir(appHome = install.path, installDir = File("/nonexistent")),
      )
      assertEquals(
        fonts,
        ServeRcJvmFonts.packagedDir(appHome = "/nonexistent", installDir = install),
      )
    } finally {
      install.deleteRecursively()
    }
  }

  @Test
  fun `installing points the cmp-jvm worker at the packaged faces`() {
    val packaged = File("/opt/compose-preview-server/rc-player-wasm/fonts")
    assertEquals(packaged, ServeRcJvmFonts.installPackaged(packaged))
    assertEquals(packaged.absolutePath, System.getProperty(ServeRcJvmFonts.PROPERTY))
  }

  @Test
  fun `an operator's explicit fonts directory wins`() {
    System.setProperty(ServeRcJvmFonts.PROPERTY, "/srv/my-fonts")
    assertEquals(
      File("/srv/my-fonts"),
      ServeRcJvmFonts.installPackaged(File("/opt/compose-preview-server/rc-player-wasm/fonts")),
    )
    assertEquals("/srv/my-fonts", System.getProperty(ServeRcJvmFonts.PROPERTY))
  }

  @Test
  fun `no packaged faces leaves the renderer's own fallbacks alone`() {
    assertNull(ServeRcJvmFonts.installPackaged(null))
    assertNull(System.getProperty(ServeRcJvmFonts.PROPERTY))
  }
}
