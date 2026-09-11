package ee.schimke.composeai.cli.serve.icons

/**
 * Renders a resolved glyph as the SVG path data an `ImageVector` is built from.
 *
 * Two conversions happen here and both are load-bearing.
 *
 * **The y axis flips.** Font outlines grow upward from a baseline; `ImageVector` grows downward
 * from the top-left. Upstream's own SVGs express this as `viewBox="0 -960 960 960"`, keeping font
 * coordinates and moving the box; a viewport cannot be negative here, so the coordinates move
 * instead: `y' = unitsPerEm - y`. With Material Symbols' 960 units per em that puts a 24 dp icon in
 * a 960 × 960 viewport with no further scaling, and `search` lands in a 120–840 box — the same
 * place upstream's `search_24px.svg` puts it.
 *
 * **Implied on-curve points are made explicit.** TrueType allows two consecutive off-curve points,
 * with an on-curve point implied at their midpoint; SVG has no such shorthand. Emitting the control
 * points without the implied midpoints produces a subtly wrong, lumpier outline that still looks
 * plausible, which is the worst kind of wrong.
 */
internal fun GlyphOutline.toPathData(): String {
  if (contours.isEmpty()) return ""
  val out = StringBuilder()
  contours.forEach { contour -> out.appendContour(contour, unitsPerEm) }
  return out.toString().trim()
}

private fun StringBuilder.appendContour(contour: List<GlyphPoint>, unitsPerEm: Int) {
  if (contour.isEmpty()) return
  fun flip(point: GlyphPoint) = GlyphPoint(point.x, unitsPerEm - point.y, point.onCurve)
  val points = contour.map(::flip)

  // Rotate so the contour starts on an on-curve point. A contour made only of off-curve points is
  // legal: its start is the implied midpoint between the last and the first.
  val firstOnCurve = points.indexOfFirst { it.onCurve }
  val ordered: List<GlyphPoint>
  val start: GlyphPoint
  if (firstOnCurve >= 0) {
    ordered = points.drop(firstOnCurve) + points.take(firstOnCurve)
    start = ordered.first()
  } else {
    start = midpoint(points.last(), points.first())
    ordered = listOf(start) + points
  }

  append('M')
  appendPoint(start)
  var index = 1
  while (index < ordered.size) {
    val point = ordered[index]
    if (point.onCurve) {
      append('L')
      appendPoint(point)
      index++
      continue
    }
    val next = ordered.getOrNull(index + 1)
    val end =
      when {
        next == null -> start
        next.onCurve -> next
        else -> midpoint(point, next)
      }
    append('Q')
    appendPoint(point)
    append(' ')
    appendPoint(end)
    index += if (next != null && next.onCurve) 2 else 1
  }
  append('Z')
}

private fun midpoint(first: GlyphPoint, second: GlyphPoint) =
  GlyphPoint((first.x + second.x) / 2f, (first.y + second.y) / 2f, onCurve = true)

private fun StringBuilder.appendPoint(point: GlyphPoint) {
  appendCoordinate(point.x)
  append(' ')
  appendCoordinate(point.y)
}

/**
 * Coordinates are rounded to whole font units.
 *
 * At 960 units per em a unit is a fortieth of a device pixel in a 24 dp icon, so the rounding is
 * invisible and it keeps the emitted string short — which matters, because this string is what the
 * document stores per icon and what an export inlines into generated Kotlin.
 */
private fun StringBuilder.appendCoordinate(value: Float) {
  append(Math.round(value))
}
