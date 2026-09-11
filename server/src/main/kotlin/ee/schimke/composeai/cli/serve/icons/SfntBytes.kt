package ee.schimke.composeai.cli.serve.icons

/**
 * A cursor over big-endian sfnt bytes.
 *
 * Font tables are dense offset-and-length structures read strictly front to back, so a cursor with
 * an explicit [position] reads much closer to the spec than index arithmetic at every call site.
 * Every accessor is bounds-checked through [ByteArray.get], so a truncated or hostile file fails
 * with an exception rather than reading adjacent tables as coordinates.
 */
internal class SfntBytes(private val bytes: ByteArray, var position: Int = 0) {

  val size: Int
    get() = bytes.size

  fun at(offset: Int): SfntBytes = SfntBytes(bytes, offset)

  fun skip(count: Int) {
    position += count
  }

  fun u8(): Int = bytes[position++].toInt() and 0xFF

  fun s8(): Int = bytes[position++].toInt()

  fun u16(): Int = (u8() shl 8) or u8()

  fun s16(): Int = u16().toShort().toInt()

  fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

  /** A 2.14 fixed-point value, the encoding every normalised axis coordinate uses. */
  fun f2dot14(): Float = s16() / 16384f

  fun tag(): String = buildString { repeat(4) { append(u8().toChar()) } }
}
