package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeGoogleFontsTest {
  private val dir: File = Files.createTempDirectory("google-fonts").toFile()
  private val requests = mutableListOf<String>()

  private fun fonts(answer: (String) -> ByteArray?) =
    ServeGoogleFonts(dir, listOf("Playfair Display", "Roboto Flex")) { url, userAgent ->
      assertEquals(ServeGoogleFonts.TTF_USER_AGENT, userAgent)
      requests += url
      answer(url)
    }

  private fun face(weight: String, file: String) =
    "@font-face {\n  font-family: 'X';\n  font-weight: $weight;\n" +
      "  src: url(https://fonts.gstatic.com/s/x/$file.ttf) format('truetype');\n}\n"

  @Test
  fun `a catalog family is fetched once, cached under the prewarm name, and matched in any case`() {
    val ttf = byteArrayOf(0, 1, 0, 0, 42)
    val fonts = fonts { url ->
      if (url.startsWith("https://fonts.googleapis.com/")) face("700", "bold").encodeToByteArray()
      else ttf
    }

    assertContentEquals(ttf, fonts.font("playfair display", 700))
    assertEquals(
      listOf(
        "https://fonts.googleapis.com/css2?family=Playfair%20Display:wght@700&display=swap",
        "https://fonts.gstatic.com/s/x/bold.ttf",
      ),
      requests,
    )
    assertTrue(File(dir, "playfair-display-700.ttf").isFile)
    assertContentEquals(ttf, fonts.font("Playfair Display", 700))
    assertEquals(2, requests.size, "the second ask is answered from the cache")
  }

  @Test
  fun `a variable-only family falls back to the whole axis, picking the closest weight`() {
    val fonts = fonts { url ->
      when {
        "wght@400&" in url -> null // Google's 400 for a single weight of a variable family
        "wght@100..1000" in url ->
          (face("300", "light") + face("500", "medium") + face("900", "black")).encodeToByteArray()
        else -> url.encodeToByteArray()
      }
    }

    assertContentEquals(
      "https://fonts.gstatic.com/s/x/light.ttf".encodeToByteArray(),
      fonts.font("Roboto Flex", 400),
    )
  }

  @Test
  fun `a single-face static family asked at another weight falls back to its one face`() {
    val fonts = fonts { url ->
      when {
        // Google's 400 for a weight a static family does not have, and for the axis range.
        ":wght@" in url -> null
        url.startsWith("https://fonts.googleapis.com/") ->
          face("400", "regular").encodeToByteArray()
        else -> url.encodeToByteArray()
      }
    }

    assertContentEquals(
      "https://fonts.gstatic.com/s/x/regular.ttf".encodeToByteArray(),
      fonts.font("Playfair Display", 700),
    )
    assertEquals(
      "https://fonts.googleapis.com/css2?family=Playfair%20Display&display=swap",
      requests[2],
    )
  }

  @Test
  fun `nothing outside the catalog or off the hundreds is ever fetched`() {
    val fonts = fonts { error("must not fetch") }

    assertNull(fonts.font("Not A Real Family", 400))
    assertNull(fonts.font("Playfair Display", 450))
    assertNull(fonts.font("Playfair Display", 1000))
  }

  @Test
  fun `a family with no truetype file is remembered as missing, an outage is not`() {
    var down = true
    val fonts = fonts { url ->
      if (down) error("unreachable")
      if ("googleapis" in url) "/* nothing */".encodeToByteArray() else url.encodeToByteArray()
    }

    assertFailsWith<IllegalStateException> { fonts.font("Playfair Display", 400) }
    down = false
    assertNull(fonts.font("Playfair Display", 400))
    val asked = requests.size
    assertNull(fonts.font("Playfair Display", 400))
    assertEquals(asked, requests.size, "a known-missing file is not asked for again")
  }

  @Test
  fun `only gstatic urls are taken from a stylesheet`() {
    val css =
      "@font-face { font-weight: 400; src: url(https://evil.example/x.ttf) format('truetype'); }"
    assertNull(ServeGoogleFonts.truetypeUrl(css, 400))
    assertEquals("playfair-display", ServeGoogleFonts.slugify("Playfair  Display"))
  }

  @Test
  fun `warming fetches the regular and bold of each catalog family a design names, once`() {
    val fonts = fonts { url ->
      if (url.startsWith("https://fonts.googleapis.com/")) {
        val weight = Regex("wght@(\\d+)&").find(url)?.groupValues?.get(1) ?: "400"
        face(weight, "w$weight").encodeToByteArray()
      } else byteArrayOf(0, 1, 0, 0)
    }

    fonts.warm(listOf("google:Playfair Display", "playfair display", "Orbitron", "Not A Family"))

    assertTrue(File(dir, "playfair-display-400.ttf").isFile)
    assertTrue(File(dir, "playfair-display-700.ttf").isFile)
    assertEquals(4, requests.size, "one stylesheet and one file per weight, for one family")
    fonts.warm(listOf("Playfair Display"))
    assertEquals(4, requests.size, "a warm cache asks for nothing")
  }

  @Test
  fun `a failed fetch while warming leaves the render to the default face`() {
    val fonts = fonts { error("offline") }

    fonts.warm(listOf("Playfair Display"))

    assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".ttf") })
  }
}
