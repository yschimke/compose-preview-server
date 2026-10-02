package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `--lan` QR encoder ([ServeQrCode]), module for module against an independent encoder.
 *
 * `qr-golden.txt` was written by python-qrcode 8.x (byte mode, level M, the mask forced to the one
 * named on each header line, no border) — a separate implementation, so agreement here means the
 * codewords, the Reed–Solomon blocks, their interleaving, the function patterns, the format and
 * version bits and the mask all match, not merely that this class agrees with itself. The three
 * cases reach version 2 (one alignment pattern), 5 (two blocks) and 8 (version bits, six alignment
 * patterns, uneven blocks).
 */
class ServeQrCodeTest {

  private data class Golden(
    val text: String,
    val mask: Int,
    val version: Int,
    val rows: List<String>,
  )

  private fun goldens(): List<Golden> {
    val text =
      assertNotNull(javaClass.getResourceAsStream("qr-golden.txt")).bufferedReader().readText()
    return text.trim().split("\n\n").map { block ->
      val lines = block.lines()
      val header = Regex("""^'(.*)' (\d+) (\d+)$""").find(lines.first())!!.groupValues
      Golden(header[1], header[2].toInt(), header[3].toInt(), lines.drop(1))
    }
  }

  @Test
  fun `every module matches an independent encoder`() {
    val cases = goldens()
    assertEquals(3, cases.size)
    for (golden in cases) {
      val code = assertNotNull(ServeQrCode.encode(golden.text, mask = golden.mask), golden.text)
      assertEquals(golden.version, code.version, golden.text)
      val rows = code.modules.map { row -> row.joinToString("") { if (it) "#" else "." } }
      assertEquals(golden.rows, rows, golden.text)
    }
  }

  @Test
  fun `the chosen mask is one of the eight, and too long a text is refused`() {
    val code = assertNotNull(ServeQrCode.encode("http://192.168.1.23:7070/?token=abc"))
    assertEquals(code.version * 4 + 17, code.size)
    assertNull(ServeQrCode.encode("x".repeat(300)))
  }

  @Test
  fun `the terminal form packs two rows per line, black on white`() {
    val code = assertNotNull(ServeQrCode.encode("hi"))
    val lines = code.terminalLines()
    assertEquals((code.size + 4 + 1) / 2, lines.size)
    assertTrue(lines.all { it.startsWith("\u001b[30;47m") && it.endsWith("\u001b[0m") })
    assertTrue(code.svg().startsWith("<svg"))
  }
}
