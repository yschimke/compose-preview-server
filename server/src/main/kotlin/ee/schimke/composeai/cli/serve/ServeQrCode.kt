package ee.schimke.composeai.cli.serve

/**
 * A QR code for one short string — the `--lan` URL a phone on the same network should open.
 *
 * Small on purpose, and here rather than a dependency: the server needs exactly one shape (byte
 * mode, error-correction level M, versions 1–10, which hold up to 213 bytes — a LAN origin plus a
 * token is under 80), and adding a barcode library to the distribution would mean another entry in
 * `checkServeModuleBoundary`'s positive allowlist for a startup nicety. The construction follows
 * ISO/IEC 18004 as Project Nayuki's reference encoder lays it out (MIT): function patterns, the
 * data and Reed–Solomon codewords interleaved by block, then the mask with the lowest penalty.
 *
 * [modules] is `size × size`, `true` for a dark module, without the quiet zone; [terminalLines] and
 * [svg] add the four-module quiet zone every reader expects.
 */
internal class ServeQrCode private constructor(val version: Int, val modules: Array<BooleanArray>) {

  val size: Int
    get() = modules.size

  /**
   * The code as text for a terminal: two module rows per line with half-block characters, drawn in
   * explicit black on white so it scans the same on a dark terminal theme as on a light one.
   */
  fun terminalLines(): List<String> {
    val quiet = 2
    val span = size + quiet * 2
    fun dark(x: Int, y: Int): Boolean {
      val mx = x - quiet
      val my = y - quiet
      return mx in 0 until size && my in 0 until size && modules[my][mx]
    }
    return (0 until span step 2).map { y ->
      buildString {
        append(ANSI_BLACK_ON_WHITE)
        for (x in 0 until span) {
          val top = dark(x, y)
          val bottom = y + 1 < span && dark(x, y + 1)
          append(
            when {
              top && bottom -> '█'
              top -> '▀'
              bottom -> '▄'
              else -> ' '
            }
          )
        }
        append(ANSI_RESET)
      }
    }
  }

  /** The code as an SVG, one path, [moduleSize] pixels per module, quiet zone included. */
  fun svg(moduleSize: Int = 4, label: String = "QR code"): String {
    val quiet = 4
    val span = (size + quiet * 2) * moduleSize
    val path = buildString {
      for (y in 0 until size) for (x in 0 until size) {
        if (modules[y][x])
          append(
            "M${(x + quiet) * moduleSize},${(y + quiet) * moduleSize}h${moduleSize}v${moduleSize}h-${moduleSize}z"
          )
      }
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $span $span\" width=\"$span\" " +
      "height=\"$span\" role=\"img\" aria-label=\"${ee.schimke.composeai.web.WebEscaping.htmlEscape(label)}\" " +
      "shape-rendering=\"crispEdges\"><rect width=\"$span\" height=\"$span\" fill=\"#fff\"/>" +
      "<path d=\"$path\" fill=\"#000\"/></svg>"
  }

  companion object {
    private const val ANSI_BLACK_ON_WHITE = "\u001b[30;47m"
    private const val ANSI_RESET = "\u001b[0m"

    const val MAX_VERSION = 10

    /** Error-correction codewords per block at level M, by version (index 0 unused). */
    private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

    /** Reed–Solomon blocks at level M, by version (index 0 unused). */
    private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    /** Level M's two format bits. */
    private const val ECL_M_BITS = 0

    /**
     * Encode [text] as UTF-8 in byte mode at level M, in the smallest version that holds it, or
     * null when it needs more than version [MAX_VERSION].
     */
    fun encode(text: String, mask: Int? = null): ServeQrCode? {
      val data = text.toByteArray(Charsets.UTF_8)
      val version =
        (1..MAX_VERSION).firstOrNull { v ->
          4 + countBits(v) + data.size * 8 <= dataCodewords(v) * 8 &&
            data.size < (1 shl countBits(v))
        } ?: return null
      val bits = BitBuffer()
      bits.append(0b0100, 4)
      bits.append(data.size, countBits(version))
      data.forEach { bits.append(it.toInt() and 0xff, 8) }
      val capacity = dataCodewords(version) * 8
      bits.append(0, minOf(4, capacity - bits.size))
      bits.append(0, (8 - bits.size % 8) % 8)
      var pad = 0xEC
      while (bits.size < capacity) {
        bits.append(pad, 8)
        pad = pad xor 0xEC xor 0x11
      }
      val codewords = ByteArray(bits.size / 8)
      for (i in 0 until bits.size) {
        if (bits[i])
          codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
      }
      return Builder(version).build(withEcc(version, codewords), mask)
    }

    private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

    private fun rawDataModules(version: Int): Int {
      var result = (16 * version + 128) * version + 64
      if (version >= 2) {
        val align = version / 7 + 2
        result -= (25 * align - 10) * align - 55
        if (version >= 7) result -= 36
      }
      return result
    }

    private fun dataCodewords(version: Int): Int =
      rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

    private fun withEcc(version: Int, data: ByteArray): ByteArray {
      val blocks = BLOCKS[version]
      val eccLen = ECC_PER_BLOCK[version]
      val raw = rawDataModules(version) / 8
      val shortBlocks = blocks - raw % blocks
      val shortLen = raw / blocks
      val divisor = rsDivisor(eccLen)
      val out = ArrayList<ByteArray>(blocks)
      var k = 0
      for (i in 0 until blocks) {
        val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
        val dat = data.copyOfRange(k, k + len)
        k += len
        val ecc = rsRemainder(dat, divisor)
        // Short blocks get a placeholder byte so every block indexes the same; it is skipped below.
        val block = if (i < shortBlocks) dat + byteArrayOf(0) + ecc else dat + ecc
        out += block
      }
      val result = ByteArray(raw)
      var n = 0
      for (i in out[0].indices) {
        for (j in out.indices) {
          if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = out[j][i]
        }
      }
      return result
    }

    private fun rsDivisor(degree: Int): ByteArray {
      val result = ByteArray(degree)
      result[degree - 1] = 1
      var root = 1
      repeat(degree) {
        for (j in result.indices) {
          result[j] = gfMul(result[j].toInt() and 0xff, root).toByte()
          if (j + 1 < result.size)
            result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
        }
        root = gfMul(root, 0x02)
      }
      return result
    }

    private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
      val result = ByteArray(divisor.size)
      for (b in data) {
        val factor = (b.toInt() xor result[0].toInt()) and 0xff
        System.arraycopy(result, 1, result, 0, result.size - 1)
        result[result.size - 1] = 0
        for (i in result.indices) {
          result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xff, factor)).toByte()
        }
      }
      return result
    }

    private fun gfMul(x: Int, y: Int): Int {
      var z = 0
      for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
      }
      return z and 0xff
    }
  }

  private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int
      get() = bits.size

    operator fun get(i: Int): Boolean = bits[i]

    fun append(value: Int, length: Int) {
      for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
    }
  }

  private class Builder(val version: Int) {
    val size = version * 4 + 17
    val modules = Array(size) { BooleanArray(size) }
    val function = Array(size) { BooleanArray(size) }

    fun build(codewords: ByteArray, forcedMask: Int?): ServeQrCode {
      drawFunctionPatterns()
      drawCodewords(codewords)
      val mask =
        forcedMask
          ?: (0 until 8).minBy { m ->
            applyMask(m)
            drawFormatBits(m)
            val score = penalty()
            applyMask(m)
            score
          }
      applyMask(mask)
      drawFormatBits(mask)
      return ServeQrCode(version, modules)
    }

    private fun set(x: Int, y: Int, dark: Boolean) {
      modules[y][x] = dark
      function[y][x] = true
    }

    private fun drawFunctionPatterns() {
      for (i in 0 until size) {
        set(6, i, i % 2 == 0)
        set(i, 6, i % 2 == 0)
      }
      finder(3, 3)
      finder(size - 4, 3)
      finder(3, size - 4)
      val positions = alignmentPositions()
      val n = positions.size
      for (i in 0 until n) for (j in 0 until n) {
        if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
        for (dy in -2..2) for (dx in -2..2) {
          set(positions[i] + dx, positions[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
      }
      drawFormatBits(0)
      drawVersion()
    }

    private fun finder(x: Int, y: Int) {
      for (dy in -4..4) for (dx in -4..4) {
        val dist = maxOf(Math.abs(dx), Math.abs(dy))
        val xx = x + dx
        val yy = y + dy
        if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
      }
    }

    private fun alignmentPositions(): IntArray {
      if (version == 1) return IntArray(0)
      val n = version / 7 + 2
      val step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
      val result = IntArray(n)
      result[0] = 6
      var pos = size - 7
      for (i in n - 1 downTo 1) {
        result[i] = pos
        pos -= step
      }
      return result
    }

    fun drawFormatBits(mask: Int) {
      val data = (ECL_M_BITS shl 3) or mask
      var rem = data
      repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
      val bits = ((data shl 10) or rem) xor 0x5412
      fun bit(i: Int) = ((bits ushr i) and 1) != 0
      for (i in 0..5) set(8, i, bit(i))
      set(8, 7, bit(6))
      set(8, 8, bit(7))
      set(7, 8, bit(8))
      for (i in 9 until 15) set(14 - i, 8, bit(i))
      for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
      for (i in 8 until 15) set(8, size - 15 + i, bit(i))
      set(8, size - 8, true)
    }

    private fun drawVersion() {
      if (version < 7) return
      var rem = version
      repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
      val bits = (version shl 12) or rem
      for (i in 0 until 18) {
        val dark = ((bits ushr i) and 1) != 0
        val a = size - 11 + i % 3
        val b = i / 3
        set(a, b, dark)
        set(b, a, dark)
      }
    }

    private fun drawCodewords(data: ByteArray) {
      var i = 0
      var right = size - 1
      while (right >= 1) {
        if (right == 6) right = 5
        for (vert in 0 until size) {
          for (j in 0..1) {
            val x = right - j
            val upward = ((right + 1) and 2) == 0
            val y = if (upward) size - 1 - vert else vert
            if (!function[y][x] && i < data.size * 8) {
              modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
              i++
            }
          }
        }
        right -= 2
      }
    }

    private fun applyMask(mask: Int) {
      for (y in 0 until size) for (x in 0 until size) {
        val invert =
          when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
          }
        if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
      }
    }

    /** ISO 18004's four penalty rules; lower reads more reliably. */
    private fun penalty(): Int {
      var result = 0
      fun line(get: (Int) -> Boolean) {
        var run = 1
        for (i in 1 until size) {
          if (get(i) == get(i - 1)) {
            run++
          } else {
            if (run >= 5) result += run - 2
            run = 1
          }
        }
        if (run >= 5) result += run - 2
        // 1:1:3:1:1 finder-like runs with four light modules on either side (outside reads light).
        fun at(i: Int) = i in 0 until size && get(i)
        for (i in -4 until size) {
          val core =
            at(i) && !at(i + 1) && at(i + 2) && at(i + 3) && at(i + 4) && !at(i + 5) && at(i + 6)
          if (!core) continue
          val before = (1..4).none { at(i - it) }
          val after = (7..10).none { at(i + it) }
          if (before || after) result += 40
        }
      }
      for (y in 0 until size) line { modules[y][it] }
      for (x in 0 until size) line { modules[it][x] }
      for (y in 0 until size - 1) for (x in 0 until size - 1) {
        val c = modules[y][x]
        if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1])
          result += 3
      }
      val dark = modules.sumOf { row -> row.count { it } }
      val total = size * size
      val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
      result += k * 10
      return result
    }
  }
}
