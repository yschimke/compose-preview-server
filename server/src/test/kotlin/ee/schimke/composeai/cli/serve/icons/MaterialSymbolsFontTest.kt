package ee.schimke.composeai.cli.serve.icons

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds the variation reader to outlines produced independently, because nothing else can.
 *
 * A wrong `gvar` implementation does not throw — it draws. Deltas applied without interpolating the
 * untouched points, an `avar` map skipped, an intermediate tuple scored as if it were linear: each
 * produces a plausible icon that is quietly the wrong shape at every axis value except the default.
 * So the expectations here are glyph geometry generated from the *same* pinned font by fontTools,
 * an implementation with no code in common with this one, and compared point by point.
 *
 * The fixture is a 22 KB subset of the Outlined face — sixteen icons, every axis and the whole
 * `gvar` table intact — beside 48 KB of expected contours at five axis positions. Kilobytes,
 * deliberately: this file exists so that the design can keep the fonts out of git and out of the
 * distribution without giving up on proving the reader correct.
 *
 * The five positions are chosen to exercise the parts that fail silently: the default, both ends of
 * `wght`, a filled weight, and one deliberately off-grid position (`wght 350, FILL 0.5, GRAD 120,
 * opsz 31`) where no tuple sits on a peak, every scalar is fractional, and `avar` actually bends
 * the result.
 */
class MaterialSymbolsFontTest {

  private fun resource(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/material-symbols/$name")) {
        "missing test fixture $name"
      }
      .use { it.readBytes() }

  private val catalog =
    MaterialSymbolsCatalog.read(
      resource("outlined-subset.ttf"),
      resource("outlined-subset.codepoints").decodeToString(),
    )

  private val font = MaterialSymbolsFont.read(resource("outlined-subset.ttf"))

  private val codePoints =
    MaterialSymbolsCatalog.readCodePoints(resource("outlined-subset.codepoints").decodeToString())

  private val expected: JsonObject =
    Json.parseToJsonElement(resource("outlined-subset-outlines.json").decodeToString()).jsonObject

  @Test
  fun `reads the axes the face declares`() {
    assertEquals(
      listOf("FILL", "GRAD", "opsz", "wght"),
      font.axes.map { it.tag }.sorted().sortedBy { it },
    )
    val weight = font.axes.single { it.tag == "wght" }
    assertEquals(100f, weight.minimum)
    assertEquals(400f, weight.default)
    assertEquals(700f, weight.maximum)
  }

  @Test
  fun `resolves names through the codepoints file`() {
    assertEquals(16, codePoints.size)
    assertEquals(0xEF7A, codePoints["search"])
    assertTrue(catalog.contains("search"))
    assertNull(catalog.pathData("no_such_icon"))
  }

  @Test
  fun `interpolates every fixture case exactly`() {
    val cases = expected["cases"]!!.jsonArray
    val unitsPerEm = expected["upem"]!!.jsonPrimitive.int
    assertEquals(960, unitsPerEm)
    assertEquals(80, cases.size)
    var comparedPoints = 0
    cases.forEach { element ->
      val case = element.jsonObject
      val name = case["name"]!!.jsonPrimitive.content
      val axes = case["axes"]!!.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.float }
      val glyph = checkNotNull(font.glyphId(codePoints.getValue(name))) { "no glyph for $name" }
      val outline = font.outline(glyph, axes)
      val expectedContours = case["contours"]!!.jsonArray
      val label = "$name at $axes"
      assertEquals(expectedContours.size, outline.contours.size, "contour count for $label")
      expectedContours.forEachIndexed { contourIndex, expectedContour ->
        val actual = outline.contours[contourIndex]
        val points = (expectedContour as JsonArray)
        assertEquals(points.size, actual.size, "point count for $label contour $contourIndex")
        points.forEachIndexed { pointIndex, expectedPoint ->
          val values = expectedPoint.jsonArray
          val point = actual[pointIndex]
          // The fixture is stored flipped into the ImageVector space, the same transform
          // `toPathData` applies, so the comparison is made there.
          val x = point.x
          val y = unitsPerEm - point.y
          val where = "$label contour $contourIndex point $pointIndex"
          assertTrue(
            abs(values[0].jsonPrimitive.int - Math.round(x)) <= 1,
            "x for $where: expected ${values[0].jsonPrimitive.int}, was ${Math.round(x)}",
          )
          assertTrue(
            abs(values[1].jsonPrimitive.int - Math.round(y)) <= 1,
            "y for $where: expected ${values[1].jsonPrimitive.int}, was ${Math.round(y)}",
          )
          assertEquals(
            values[2].jsonPrimitive.int == 1,
            point.onCurve,
            "on-curve flag for $where",
          )
          comparedPoints++
        }
      }
    }
    // Exact, not a floor: if the fixture ever shrinks, that should fail here rather than quietly
    // reduce what this test proves.
    assertEquals(3_410, comparedPoints, "fixture coverage changed")
  }

  @Test
  fun `weight actually changes the drawing`() {
    val glyph = checkNotNull(font.glyphId(codePoints.getValue("search")))
    val thin = font.outline(glyph, mapOf("wght" to 100f)).toPathData()
    val regular = font.outline(glyph, mapOf("wght" to 400f)).toPathData()
    val bold = font.outline(glyph, mapOf("wght" to 700f)).toPathData()
    assertTrue(thin != regular, "wght 100 and 400 produced identical path data")
    assertTrue(bold != regular, "wght 700 and 400 produced identical path data")
  }

  @Test
  fun `an axis value outside its range is clamped rather than extrapolated`() {
    val glyph = checkNotNull(font.glyphId(codePoints.getValue("search")))
    assertEquals(
      font.outline(glyph, mapOf("wght" to 700f)).toPathData(),
      font.outline(glyph, mapOf("wght" to 900f)).toPathData(),
    )
  }

  @Test
  fun `path data lands in the viewport upstream uses`() {
    val path = assertNotNull(catalog.pathData("search"))
    assertTrue(path.startsWith("M"), "path data should start with a move: $path")
    assertTrue(path.endsWith("Z"), "path data should close: $path")
    assertTrue(
      path.none { it == 'C' || it == 'c' },
      "TrueType outlines are quadratic; no cubic command should appear",
    )
    val numbers = Regex("-?\\d+").findAll(path).map { it.value.toInt() }.toList()
    assertTrue(numbers.isNotEmpty())
    assertTrue(
      numbers.all { it in -10..970 },
      "coordinates should sit in the 960 viewport, saw ${numbers.minOrNull()}..${numbers.maxOrNull()}",
    )
    // `search` occupies the same 120..840 box as upstream's own search_24px.svg.
    assertTrue(numbers.max() in 830..850, "expected the glyph to reach ~840, saw ${numbers.max()}")
    assertTrue(
      numbers.min() in 110..130,
      "expected the glyph to start at ~120, saw ${numbers.min()}",
    )
  }

  @Test
  fun `every fixture icon resolves to drawable path data`() {
    codePoints.keys.forEach { name ->
      val path = assertNotNull(catalog.pathData(name), "no path data for $name")
      assertTrue(path.length > 20, "suspiciously short path for $name: $path")
    }
  }
}
