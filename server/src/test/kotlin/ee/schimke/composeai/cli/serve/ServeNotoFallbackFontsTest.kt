package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeNotoFallbackFontsTest {
  private val dir: File = Files.createTempDirectory("noto-fallback-fonts").toFile()
  private val requests = mutableListOf<String>()

  private fun fonts(answer: (String) -> ByteArray?) =
    ServeNotoFallbackFonts(dir) { url ->
      requests += url
      answer(url)
    }

  @Test
  fun `a slice is fetched from gstatic once and kept at its own path`() {
    val woff2 = byteArrayOf(0x77, 0x4f, 0x46, 0x32)
    val fonts = fonts { woff2 }
    val path = "notosansmath/v18/7Aump_cpkSecTWaHRlH2hyV5UEl981wI8A.woff2"

    assertContentEquals(woff2, fonts.font(path))
    assertContentEquals(woff2, fonts.font(path))

    assertEquals(listOf("https://fonts.gstatic.com/s/$path"), requests)
    assertTrue(File(dir, path).isFile)
  }

  @Test
  fun `the numbered slices of a split family are slices`() {
    assertTrue(
      ServeNotoFallbackFonts.isNotoSlice(
        "notocoloremoji/v39/Yq6P-KqIXTD0t4D9z1ESnKM3-HpFabsE4tq3luCC7p-aXxcn.0.woff2"
      )
    )
  }

  @Test
  fun `only a Noto slice path is ever fetched`() {
    val fonts = fonts { error("fetched $it") }
    listOf(
        "roboto/v30/KFOmCnqEu92Fr1Me5Q.woff2",
        "notosans/v1/x.ttf",
        "notosans/../../etc/passwd",
        "notosans/v1/../x.woff2",
        "notosans/v1/a/b.woff2",
        "notosans/v1/x.woff2?u=https://example.com",
        "/notosans/v1/x.woff2",
      )
      .forEach { path ->
        assertFalse(ServeNotoFallbackFonts.isNotoSlice(path), path)
        assertNull(fonts.font(path), path)
      }
    assertEquals(emptyList(), requests)
  }

  @Test
  fun `a slice Google does not have is asked for once, and an outage is not cached`() {
    var down = true
    val fonts = fonts { if (down) error("unreachable") else null }
    val path = "notosanssymbols2/v24/I_uyMoGduATTei9eI8daxVHDyfisHr71ypM.woff2"

    assertFailsWith<IllegalStateException> { fonts.font(path) }
    down = false
    assertNull(fonts.font(path))
    assertNull(fonts.font(path))

    assertEquals(2, requests.size)
    assertFalse(File(dir, path).exists())
  }
}
