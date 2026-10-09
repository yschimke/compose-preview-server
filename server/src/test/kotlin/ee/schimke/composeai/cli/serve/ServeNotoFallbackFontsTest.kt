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

  private val math = "notosansmath/v18/7Aump_cpkSecTWaHRlH2hyV5UEl981wI8A.woff2"
  private val symbols = "notosanssymbols2/v24/I_uyMoGduATTei9eI8daxVHDyfisHr71ypM.woff2"

  private fun fonts(answer: (String) -> ByteArray?) =
    ServeNotoFallbackFonts(dir, setOf(math, symbols)) { url ->
      requests += url
      answer(url)
    }

  @Test
  fun `a listed slice is fetched from gstatic once and kept at its own path`() {
    val woff2 = byteArrayOf(0x77, 0x4f, 0x46, 0x32)
    val fonts = fonts { woff2 }

    assertContentEquals(woff2, fonts.font(math))
    assertContentEquals(woff2, fonts.font(math))

    assertEquals(listOf("https://fonts.gstatic.com/s/$math"), requests)
    assertTrue(File(dir, math).isFile)
  }

  @Test
  fun `a path not in the list is never fetched, however it is shaped`() {
    val fonts = fonts { error("fetched $it") }
    listOf(
        // Shaped exactly like a slice, which is what an unbounded cache of misses was made of.
        "notox/v1/unique-${System.nanoTime()}.woff2",
        "notosansmath/v18/7Aump_cpkSecTWaHRlH2hyV5UEl981wI8B.woff2",
        "notosans/../../etc/passwd",
        "/$math",
        "$math?u=https://example.com",
      )
      .forEach { path ->
        assertFalse(fonts.knows(path), path)
        assertNull(fonts.font(path), path)
      }
    assertEquals(emptyList(), requests)
  }

  @Test
  fun `a listed slice Google does not have is asked for once, and an outage is not cached`() {
    var down = true
    val fonts = fonts { if (down) error("unreachable") else null }

    assertFailsWith<IllegalStateException> { fonts.font(symbols) }
    down = false
    assertNull(fonts.font(symbols))
    assertNull(fonts.font(symbols))

    assertEquals(2, requests.size)
    assertFalse(File(dir, symbols).exists())
  }

  @Test
  fun `the shipped list is Compose's slices, and only slice-shaped paths`() {
    val slices = ServeNotoFallbackFonts.composeSlices
    // The slice the CSP report named, and a numbered emoji slice.
    assertTrue(math in slices)
    assertTrue(
      "notocoloremoji/v39/Yq6P-KqIXTD0t4D9z1ESnKM3-HpFabsE4tq3luCC7p-aXxcn.0.woff2" in slices
    )
    assertTrue(slices.size > 1000, "${slices.size} slices")
    val shape = Regex("noto[a-z0-9]+/v[0-9]+/[A-Za-z0-9_-]+(\\.[0-9]+)?\\.woff2")
    assertEquals(emptyList(), slices.filterNot { shape.matches(it) })
  }
}
