package ee.schimke.composeai.cli.serve.icons

/** One point of a glyph contour, in font units with the y axis still pointing up. */
internal data class GlyphPoint(val x: Float, val y: Float, val onCurve: Boolean)

/** A resolved glyph: closed contours of quadratic points, in a [unitsPerEm] square. */
internal data class GlyphOutline(val contours: List<List<GlyphPoint>>, val unitsPerEm: Int)

/** One `fvar` axis, in user coordinates — `wght` 100..700, `FILL` 0..1, and so on. */
internal data class VariationAxis(
  val tag: String,
  val minimum: Float,
  val default: Float,
  val maximum: Float,
)

/**
 * Reads glyph outlines out of a Material Symbols variable font at arbitrary axis values.
 *
 * This exists so that nothing large has to be generated, shipped or regenerated: the variable font
 * *is* the compressed form of every axis point, and resolving one is a glyph lookup plus a delta
 * interpolation rather than a table someone had to enumerate in advance.
 * [`UI_BUILDER_MATERIAL_SYMBOLS.md`](../../../../../../../../../docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md)
 * carries the measurements behind that choice.
 *
 * It is deliberately not a general font library. It reads exactly what this one family needs —
 * `head`, `maxp`, `cmap`, `loca`, `glyf`, `fvar`, `avar` and `gvar` — and refuses anything else
 * loudly, because a silent approximation here is a wrong picture in somebody's design. Composite
 * glyphs are the clearest case: Material Symbols has none (checked across all 6,615 glyphs of the
 * Outlined face), so meeting one means the input is not the font this was written for.
 */
internal class MaterialSymbolsFont
private constructor(
  private val bytes: ByteArray,
  private val tables: Map<String, TableRecord>,
  val unitsPerEm: Int,
  private val numberOfGlyphs: Int,
  private val locaLongFormat: Boolean,
  val axes: List<VariationAxis>,
  private val avarSegments: List<List<Pair<Float, Float>>>,
) {

  private data class TableRecord(val offset: Int, val length: Int)

  private val characterToGlyph: Map<Int, Int> by lazy { readCharacterMap() }

  private val glyphOffsets: IntArray by lazy { readLoca() }

  private val glyphVariations: GlyphVariations? by lazy {
    tables["gvar"]?.let { GlyphVariations.read(bytes, it.offset, axes.size, numberOfGlyphs) }
  }

  /** The glyph a Material Symbols code point names, or null when the face does not carry it. */
  fun glyphId(codePoint: Int): Int? = characterToGlyph[codePoint]

  /**
   * The outline of [glyphId] with [axisValues] applied, in user coordinates (`wght` 400, not 0.0).
   *
   * Axes the caller does not name stay at their default, and a named axis is clamped to its own
   * range rather than extrapolated — asking for `wght 900` on a 100..700 face is a caller's
   * mistake, and the nearest real weight is a better answer than an invented one.
   */
  fun outline(glyphId: Int, axisValues: Map<String, Float> = emptyMap()): GlyphOutline {
    require(glyphId in 0 until numberOfGlyphs) {
      "glyph $glyphId is outside 0..${numberOfGlyphs - 1}"
    }
    val glyph = readGlyph(glyphId)
    val normalised = normalise(axisValues)
    val deltas = glyphVariations?.deltas(glyphId, normalised, glyph)
    val points = glyph.points
    val moved =
      if (deltas == null) points
      else
        List(points.size) { index ->
          GlyphPoint(
            points[index].x + deltas.x[index],
            points[index].y + deltas.y[index],
            points[index].onCurve,
          )
        }
    var start = 0
    val contours =
      glyph.contourEnds.map { end ->
        val contour = moved.subList(start, end + 1).toList()
        start = end + 1
        contour
      }
    return GlyphOutline(contours, unitsPerEm)
  }

  /**
   * User axis values to the -1..1 space `gvar` deltas are expressed in, through `avar` if present.
   *
   * The two-step shape is the specification's: `fvar` gives a piecewise-linear map from the user
   * range onto -1..0..1 hinged at the default, and `avar` then warps that so a designer can make
   * "half way to bold" mean something other than the arithmetic midpoint. Skipping `avar` produces
   * outlines that are subtly wrong everywhere except the axis extremes and the default — the kind
   * of error that looks like a rendering bug much later.
   */
  private fun normalise(axisValues: Map<String, Float>): FloatArray =
    FloatArray(axes.size) { index ->
      val axis = axes[index]
      val requested = axisValues[axis.tag] ?: axis.default
      val value = requested.coerceIn(axis.minimum, axis.maximum)
      val normalised =
        when {
          value == axis.default -> 0f
          value < axis.default ->
            if (axis.default == axis.minimum) 0f
            else -((axis.default - value) / (axis.default - axis.minimum))
          else ->
            if (axis.maximum == axis.default) 0f
            else (value - axis.default) / (axis.maximum - axis.default)
        }
      applyAxisVariation(index, normalised)
    }

  private fun applyAxisVariation(axisIndex: Int, value: Float): Float {
    val segments = avarSegments.getOrNull(axisIndex) ?: return value
    if (segments.size < 2) return value
    if (value <= segments.first().first) return segments.first().second
    if (value >= segments.last().first) return segments.last().second
    for (index in 1 until segments.size) {
      val (fromEnd, toEnd) = segments[index]
      if (value > fromEnd) continue
      val (fromStart, toStart) = segments[index - 1]
      if (fromEnd == fromStart) return toEnd
      return toStart + (toEnd - toStart) * (value - fromStart) / (fromEnd - fromStart)
    }
    return value
  }

  // ---------------------------------------------------------------- glyf

  /**
   * A glyph as `glyf` stores it, with the four phantom points `gvar` addresses appended.
   *
   * The phantom points (left side bearing, advance, and the two vertical equivalents) are not drawn
   * and are not returned, but they are part of the point numbering every variation delta uses, so
   * dropping them here would shift every delta by four positions.
   */
  internal class SimpleGlyph(val points: List<GlyphPoint>, val contourEnds: List<Int>) {
    val drawablePointCount: Int
      get() = points.size - PHANTOM_POINTS
  }

  private fun readGlyph(glyphId: Int): SimpleGlyph {
    val start = glyphOffsets[glyphId]
    val end = glyphOffsets[glyphId + 1]
    if (start == end) return SimpleGlyph(List(PHANTOM_POINTS) { ORIGIN }, emptyList())
    val glyf = checkNotNull(tables["glyf"]) { "the font has no glyf table" }
    val cursor = SfntBytes(bytes, glyf.offset + start)
    val contourCount = cursor.s16()
    check(contourCount >= 0) {
      "glyph $glyphId is a composite; Material Symbols carries none and this reader draws none"
    }
    cursor.skip(8) // xMin, yMin, xMax, yMax — recomputed from the points after variation anyway.
    val contourEnds = List(contourCount) { cursor.u16() }
    val pointCount = if (contourEnds.isEmpty()) 0 else contourEnds.last() + 1
    cursor.skip(cursor.u16()) // hinting instructions, which nothing here executes

    val flags = IntArray(pointCount)
    var index = 0
    while (index < pointCount) {
      val flag = cursor.u8()
      flags[index++] = flag
      if (flag and REPEAT_FLAG != 0) {
        var repeats = cursor.u8()
        while (repeats-- > 0 && index < pointCount) flags[index++] = flag
      }
    }

    val xs = IntArray(pointCount)
    var x = 0
    for (point in 0 until pointCount) {
      val flag = flags[point]
      if (flag and X_SHORT != 0) {
        val delta = cursor.u8()
        x += if (flag and X_SAME_OR_POSITIVE != 0) delta else -delta
      } else if (flag and X_SAME_OR_POSITIVE == 0) {
        x += cursor.s16()
      }
      xs[point] = x
    }
    val ys = IntArray(pointCount)
    var y = 0
    for (point in 0 until pointCount) {
      val flag = flags[point]
      if (flag and Y_SHORT != 0) {
        val delta = cursor.u8()
        y += if (flag and Y_SAME_OR_POSITIVE != 0) delta else -delta
      } else if (flag and Y_SAME_OR_POSITIVE == 0) {
        y += cursor.s16()
      }
      ys[point] = y
    }

    val points = ArrayList<GlyphPoint>(pointCount + PHANTOM_POINTS)
    for (point in 0 until pointCount) {
      points += GlyphPoint(xs[point].toFloat(), ys[point].toFloat(), flags[point] and ON_CURVE != 0)
    }
    repeat(PHANTOM_POINTS) { points += ORIGIN }
    return SimpleGlyph(points, contourEnds)
  }

  private fun readLoca(): IntArray {
    val loca = checkNotNull(tables["loca"]) { "the font has no loca table" }
    val cursor = SfntBytes(bytes, loca.offset)
    return IntArray(numberOfGlyphs + 1) {
      if (locaLongFormat) cursor.u32().toInt() else cursor.u16() * 2
    }
  }

  // ---------------------------------------------------------------- cmap

  private fun readCharacterMap(): Map<Int, Int> {
    val cmap = checkNotNull(tables["cmap"]) { "the font has no cmap table" }
    val cursor = SfntBytes(bytes, cmap.offset)
    cursor.skip(2)
    val subtableCount = cursor.u16()
    var best: Int? = null
    var bestScore = -1
    repeat(subtableCount) {
      val platform = cursor.u16()
      val encoding = cursor.u16()
      val offset = cmap.offset + cursor.u32().toInt()
      // Prefer the full-repertoire subtable: 123 Material Symbols live above the BMP (up to
      // U+FFFFD), and a format 4 subtable cannot address them at all.
      val score =
        when {
          platform == 3 && encoding == 10 -> 3
          platform == 0 && encoding == 4 -> 2
          platform == 3 && encoding == 1 -> 1
          platform == 0 -> 0
          else -> -1
        }
      if (score > bestScore) {
        bestScore = score
        best = offset
      }
    }
    val subtable = checkNotNull(best) { "the font has no usable cmap subtable" }
    val reader = SfntBytes(bytes, subtable)
    return when (val format = reader.u16()) {
      4 -> readCharacterMapFormat4(reader)
      12 -> readCharacterMapFormat12(reader)
      else -> error("unsupported cmap subtable format $format")
    }
  }

  private fun readCharacterMapFormat4(cursor: SfntBytes): Map<Int, Int> {
    cursor.skip(4) // length, language
    val segmentCount = cursor.u16() / 2
    cursor.skip(6) // searchRange, entrySelector, rangeShift
    val endCodes = IntArray(segmentCount) { cursor.u16() }
    cursor.skip(2) // reservedPad
    val startCodes = IntArray(segmentCount) { cursor.u16() }
    val idDeltas = IntArray(segmentCount) { cursor.s16() }
    val rangeOffsetPosition = cursor.position
    val idRangeOffsets = IntArray(segmentCount) { cursor.u16() }
    val map = HashMap<Int, Int>()
    for (segment in 0 until segmentCount) {
      val start = startCodes[segment]
      val end = endCodes[segment]
      if (start > end || start == 0xFFFF) continue
      for (codePoint in start..end) {
        val glyph =
          if (idRangeOffsets[segment] == 0) {
            (codePoint + idDeltas[segment]) and 0xFFFF
          } else {
            val address =
              rangeOffsetPosition + segment * 2 + idRangeOffsets[segment] + (codePoint - start) * 2
            val raw = SfntBytes(bytes, address).u16()
            if (raw == 0) 0 else (raw + idDeltas[segment]) and 0xFFFF
          }
        if (glyph != 0) map[codePoint] = glyph
      }
    }
    return map
  }

  private fun readCharacterMapFormat12(cursor: SfntBytes): Map<Int, Int> {
    cursor.skip(10) // reserved, length, language
    val groupCount = cursor.u32().toInt()
    val map = HashMap<Int, Int>()
    repeat(groupCount) {
      val start = cursor.u32().toInt()
      val end = cursor.u32().toInt()
      val startGlyph = cursor.u32().toInt()
      for (codePoint in start..end) map[codePoint] = startGlyph + (codePoint - start)
    }
    return map
  }

  internal companion object {
    private const val ON_CURVE = 0x01
    private const val X_SHORT = 0x02
    private const val Y_SHORT = 0x04
    private const val REPEAT_FLAG = 0x08
    private const val X_SAME_OR_POSITIVE = 0x10
    private const val Y_SAME_OR_POSITIVE = 0x20

    /** Left side bearing, advance width, top side bearing, advance height — `gvar` numbers them. */
    const val PHANTOM_POINTS = 4

    private val ORIGIN = GlyphPoint(0f, 0f, true)

    fun read(bytes: ByteArray): MaterialSymbolsFont {
      val cursor = SfntBytes(bytes)
      val version = cursor.u32()
      check(version == 0x00010000L || version == 0x74727565L) {
        "not a TrueType outline font (sfnt version 0x${version.toString(16)})"
      }
      val tableCount = cursor.u16()
      cursor.skip(6) // searchRange, entrySelector, rangeShift
      val tables = HashMap<String, TableRecord>(tableCount)
      repeat(tableCount) {
        val tag = cursor.tag()
        cursor.skip(4) // checksum, which nothing here verifies
        val offset = cursor.u32().toInt()
        val length = cursor.u32().toInt()
        tables[tag] = TableRecord(offset, length)
      }
      val head = checkNotNull(tables["head"]) { "the font has no head table" }
      val unitsPerEm = SfntBytes(bytes, head.offset + 18).u16()
      val locaLongFormat = SfntBytes(bytes, head.offset + 50).s16() == 1
      val maxp = checkNotNull(tables["maxp"]) { "the font has no maxp table" }
      val numberOfGlyphs = SfntBytes(bytes, maxp.offset + 4).u16()
      val axes = tables["fvar"]?.let { readAxes(bytes, it.offset) }.orEmpty()
      val avar = tables["avar"]?.let { readAxisVariations(bytes, it.offset, axes.size) }.orEmpty()
      return MaterialSymbolsFont(
        bytes = bytes,
        tables = tables,
        unitsPerEm = unitsPerEm,
        numberOfGlyphs = numberOfGlyphs,
        locaLongFormat = locaLongFormat,
        axes = axes,
        avarSegments = avar,
      )
    }

    private fun readAxes(bytes: ByteArray, offset: Int): List<VariationAxis> {
      val cursor = SfntBytes(bytes, offset)
      cursor.skip(4) // version
      val axesOffset = cursor.u16()
      cursor.skip(2) // reserved
      val axisCount = cursor.u16()
      val axisSize = cursor.u16()
      return List(axisCount) { index ->
        val axis = SfntBytes(bytes, offset + axesOffset + index * axisSize)
        val tag = axis.tag()
        // Fixed 16.16 across the three bounds, which is the one place this format does not use
        // F2Dot14: these are user coordinates (100..700), not normalised ones.
        val minimum = axis.u32().toInt() / 65536f
        val default = axis.u32().toInt() / 65536f
        val maximum = axis.u32().toInt() / 65536f
        VariationAxis(tag, minimum, default, maximum)
      }
    }

    private fun readAxisVariations(
      bytes: ByteArray,
      offset: Int,
      axisCount: Int,
    ): List<List<Pair<Float, Float>>> {
      val cursor = SfntBytes(bytes, offset)
      cursor.skip(6) // version, reserved
      val mappedAxisCount = cursor.u16()
      check(mappedAxisCount == axisCount) {
        "avar describes $mappedAxisCount axes but fvar declares $axisCount"
      }
      return List(axisCount) {
        val pairCount = cursor.u16()
        List(pairCount) { cursor.f2dot14() to cursor.f2dot14() }
      }
    }
  }
}
