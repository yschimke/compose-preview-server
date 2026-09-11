package ee.schimke.composeai.cli.serve.icons

/**
 * The `gvar` table: per-glyph outline deltas for a region of the axis space.
 *
 * A variable font stores one drawing plus, per glyph, a set of *tuples* — each a region of the
 * normalised axis space and a delta per point. Resolving an axis position means scoring every tuple
 * for how much it applies there and accumulating the scaled deltas. This is what makes the font
 * 10.68 MB instead of the 158 MB an enumerated matrix of the same coverage costs, and it is why the
 * font can answer an axis value nobody chose in advance.
 *
 * Two details carry most of the risk and both are handled below rather than approximated: a tuple
 * may name only the points it moves, and the rest of the contour has to be *interpolated* from its
 * moved neighbours (IUP); and a tuple may be intermediate, applying over an asymmetric region
 * rather than linearly from the default.
 */
internal class GlyphVariations
private constructor(
  private val bytes: ByteArray,
  private val axisCount: Int,
  private val sharedTuples: List<FloatArray>,
  private val glyphDataOffsets: IntArray,
) {

  /** Accumulated per-point movement, parallel to the glyph's own point list. */
  internal class Deltas(val x: FloatArray, val y: FloatArray)

  fun deltas(
    glyphId: Int,
    normalised: FloatArray,
    glyph: MaterialSymbolsFont.SimpleGlyph,
  ): Deltas? {
    val start = glyphDataOffsets[glyphId]
    val end = glyphDataOffsets[glyphId + 1]
    if (start >= end) return null

    val pointCount = glyph.points.size
    val cursor = SfntBytes(bytes, start)
    val rawCount = cursor.u16()
    val tupleCount = rawCount and 0x0FFF
    val tuplesSharePointNumbers = rawCount and 0x8000 != 0
    var serialised = start + cursor.u16()

    var sharedPoints: IntArray? = null
    if (tuplesSharePointNumbers) {
      val reader = SfntBytes(bytes, serialised)
      sharedPoints = readPointNumbers(reader, glyph.drawablePointCount)
      serialised = reader.position
    }

    val x = FloatArray(pointCount)
    val y = FloatArray(pointCount)
    var applied = false

    repeat(tupleCount) {
      val variationDataSize = cursor.u16()
      val tupleIndex = cursor.u16()
      val peak =
        if (tupleIndex and EMBEDDED_PEAK_TUPLE != 0) FloatArray(axisCount) { cursor.f2dot14() }
        else sharedTuples[tupleIndex and 0x0FFF]
      var lower: FloatArray? = null
      var upper: FloatArray? = null
      if (tupleIndex and INTERMEDIATE_REGION != 0) {
        lower = FloatArray(axisCount) { cursor.f2dot14() }
        upper = FloatArray(axisCount) { cursor.f2dot14() }
      }
      val tupleStart = serialised
      serialised += variationDataSize

      val scalar = scalar(normalised, peak, lower, upper)
      if (scalar == 0f) return@repeat

      val reader = SfntBytes(bytes, tupleStart)
      val points =
        if (tupleIndex and PRIVATE_POINT_NUMBERS != 0) {
          readPointNumbers(reader, glyph.drawablePointCount)
        } else {
          sharedPoints
        }
      val deltaCount = points?.size ?: pointCount
      val deltaX = readDeltas(reader, deltaCount)
      val deltaY = readDeltas(reader, deltaCount)

      if (points == null) {
        for (index in 0 until minOf(pointCount, deltaCount)) {
          x[index] += deltaX[index] * scalar
          y[index] += deltaY[index] * scalar
        }
      } else {
        val spreadX = interpolateUntouched(glyph, points, deltaX, horizontal = true)
        val spreadY = interpolateUntouched(glyph, points, deltaY, horizontal = false)
        for (index in 0 until pointCount) {
          x[index] += spreadX[index] * scalar
          y[index] += spreadY[index] * scalar
        }
      }
      applied = true
    }
    return if (applied) Deltas(x, y) else null
  }

  /**
   * How much a tuple applies at [normalised], per the OpenType variation model.
   *
   * `lower`/`upper` are the tuple's own region when it declares one and the span between the
   * default and the peak otherwise, so both cases go through one piece of arithmetic.
   */
  private fun scalar(
    normalised: FloatArray,
    peak: FloatArray,
    lower: FloatArray?,
    upper: FloatArray?,
  ): Float {
    var scalar = 1f
    for (axis in 0 until axisCount) {
      val peakValue = peak[axis]
      if (peakValue == 0f) continue
      val coordinate = normalised.getOrElse(axis) { 0f }
      if (coordinate == peakValue) continue
      val start = lower?.get(axis) ?: minOf(peakValue, 0f)
      val end = upper?.get(axis) ?: maxOf(peakValue, 0f)
      if (start > peakValue || peakValue > end) continue
      if (start < 0f && end > 0f && peakValue != 0f && lower == null) continue
      if (coordinate <= start || coordinate >= end) return 0f
      scalar *=
        if (coordinate < peakValue) (coordinate - start) / (peakValue - start)
        else (end - coordinate) / (end - peakValue)
    }
    return scalar
  }

  /**
   * Spreads a sparse tuple's deltas over the points it does not name (IUP).
   *
   * A tuple that moves three points of a contour is not saying the rest stay still — it is saying
   * the rest follow, proportionally to where they sit between the points that did move. Treating an
   * unnamed point as zero is the single most visible way to get a variable font wrong: letters and
   * icons come out with pinched or torn contours at every weight except the default.
   */
  private fun interpolateUntouched(
    glyph: MaterialSymbolsFont.SimpleGlyph,
    touchedPoints: IntArray,
    deltas: FloatArray,
    horizontal: Boolean,
  ): FloatArray {
    val result = FloatArray(glyph.points.size)
    val touched = BooleanArray(glyph.points.size)
    for (index in touchedPoints.indices) {
      val point = touchedPoints[index]
      if (point in result.indices) {
        result[point] = deltas[index]
        touched[point] = true
      }
    }
    var start = 0
    for (end in glyph.contourEnds) {
      interpolateContour(glyph, result, touched, start, end, horizontal)
      start = end + 1
    }
    return result
  }

  private fun interpolateContour(
    glyph: MaterialSymbolsFont.SimpleGlyph,
    deltas: FloatArray,
    touched: BooleanArray,
    start: Int,
    end: Int,
    horizontal: Boolean,
  ) {
    val size = end - start + 1
    if (size <= 0) return
    val anchors = (start..end).filter { touched[it] }
    if (anchors.isEmpty()) return
    if (anchors.size == size) return
    fun coordinate(index: Int) = if (horizontal) glyph.points[index].x else glyph.points[index].y
    for (anchorIndex in anchors.indices) {
      val first = anchors[anchorIndex]
      val second = anchors[(anchorIndex + 1) % anchors.size]
      // Walk forward from one anchor to the next, wrapping at the contour's end: a contour is a
      // ring, so the gap between the last anchor and the first is a run like any other.
      var cursor = first + 1
      if (cursor > end) cursor = start
      while (cursor != second) {
        deltas[cursor] =
          interpolate(
            coordinate(cursor),
            coordinate(first),
            coordinate(second),
            deltas[first],
            deltas[second],
          )
        cursor = if (cursor == end) start else cursor + 1
      }
    }
  }

  private fun interpolate(
    position: Float,
    firstPosition: Float,
    secondPosition: Float,
    firstDelta: Float,
    secondDelta: Float,
  ): Float {
    if (firstPosition == secondPosition) {
      return if (firstDelta == secondDelta) firstDelta else 0f
    }
    val lowFirst = firstPosition < secondPosition
    val low = if (lowFirst) firstPosition else secondPosition
    val high = if (lowFirst) secondPosition else firstPosition
    val lowDelta = if (lowFirst) firstDelta else secondDelta
    val highDelta = if (lowFirst) secondDelta else firstDelta
    if (position <= low) return lowDelta
    if (position >= high) return highDelta
    val ratio = (position - low) / (high - low)
    return lowDelta + (highDelta - lowDelta) * ratio
  }

  /**
   * Point numbers, run-length encoded as cumulative deltas.
   *
   * A leading count of zero is not an empty set but "every point in the glyph", which is the
   * encoding's one genuine trap: read literally it silently drops the tuple.
   */
  private fun readPointNumbers(cursor: SfntBytes, drawablePointCount: Int): IntArray {
    val first = cursor.u8()
    val count = if (first and 0x80 != 0) ((first and 0x7F) shl 8) or cursor.u8() else first
    if (count == 0) {
      val total = drawablePointCount + MaterialSymbolsFont.PHANTOM_POINTS
      return IntArray(total) { it }
    }
    val points = IntArray(count)
    var read = 0
    var value = 0
    while (read < count) {
      val control = cursor.u8()
      val wide = control and 0x80 != 0
      val runLength = (control and 0x7F) + 1
      repeat(runLength) {
        if (read < count) {
          value += if (wide) cursor.u16() else cursor.u8()
          points[read++] = value
        }
      }
    }
    return points
  }

  /** Deltas, run-length encoded with zero, byte and word runs. */
  private fun readDeltas(cursor: SfntBytes, count: Int): FloatArray {
    val deltas = FloatArray(count)
    var read = 0
    while (read < count) {
      val control = cursor.u8()
      val runLength = (control and 0x3F) + 1
      when {
        control and DELTAS_ARE_ZERO != 0 ->
          repeat(runLength) { if (read < count) deltas[read++] = 0f }
        control and DELTAS_ARE_WORDS != 0 ->
          repeat(runLength) { if (read < count) deltas[read++] = cursor.s16().toFloat() }
        else -> repeat(runLength) { if (read < count) deltas[read++] = cursor.s8().toFloat() }
      }
    }
    return deltas
  }

  internal companion object {
    private const val EMBEDDED_PEAK_TUPLE = 0x8000
    private const val INTERMEDIATE_REGION = 0x4000
    private const val PRIVATE_POINT_NUMBERS = 0x2000
    private const val DELTAS_ARE_ZERO = 0x80
    private const val DELTAS_ARE_WORDS = 0x40

    fun read(
      bytes: ByteArray,
      offset: Int,
      axisCount: Int,
      numberOfGlyphs: Int,
    ): GlyphVariations {
      val cursor = SfntBytes(bytes, offset)
      cursor.skip(4) // major and minor version
      val declaredAxisCount = cursor.u16()
      check(declaredAxisCount == axisCount) {
        "gvar describes $declaredAxisCount axes but fvar declares $axisCount"
      }
      val sharedTupleCount = cursor.u16()
      val sharedTuplesOffset = cursor.u32().toInt()
      val glyphCount = cursor.u16()
      val longOffsets = cursor.u16() and 0x0001 != 0
      val dataArrayOffset = cursor.u32().toInt()
      val offsets =
        IntArray(glyphCount + 1) {
          val relative = if (longOffsets) cursor.u32().toInt() else cursor.u16() * 2
          offset + dataArrayOffset + relative
        }
      val shared =
        SfntBytes(bytes, offset + sharedTuplesOffset).let { reader ->
          List(sharedTupleCount) { FloatArray(axisCount) { reader.f2dot14() } }
        }
      check(glyphCount <= numberOfGlyphs) {
        "gvar covers $glyphCount glyphs but the font declares $numberOfGlyphs"
      }
      return GlyphVariations(bytes, axisCount, shared, offsets)
    }
  }
}
