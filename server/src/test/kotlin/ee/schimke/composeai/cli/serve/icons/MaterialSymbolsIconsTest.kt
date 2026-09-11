package ee.schimke.composeai.cli.serve.icons

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** What the icon route answers, without an HTTP server in the way. */
class MaterialSymbolsIconsTest {

  private fun resource(name: String) =
    checkNotNull(javaClass.getResourceAsStream("/material-symbols/$name")).use { it.readBytes() }

  private fun icons(temp: File): MaterialSymbolsIcons {
    val font = resource("outlined-subset.ttf")
    val codePoints = resource("outlined-subset.codepoints")
    fun sha256(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val style =
      MaterialSymbolsStyle(
        id = "outlined",
        fontUrl = "https://example.invalid/font.ttf",
        fontDigest = sha256(font),
        fontBytes = font.size,
      )
    val served = mapOf(style.fontUrl to font, MaterialSymbolsSource.CODE_POINTS_URL to codePoints)
    return MaterialSymbolsIcons(
      MaterialSymbolsSource(
        cacheDirectory = File(temp, "cache"),
        styles = listOf(style),
        codePointsUrl = MaterialSymbolsSource.CODE_POINTS_URL,
        codePointsDigest = sha256(codePoints),
      ) { url ->
        served.getValue(url)
      }
    )
  }

  private fun <T> answered(result: IconResult<T>): T {
    assertTrue(result is IconResult.Answered, "expected an answer, got $result")
    return result.value
  }

  private fun refused(result: IconResult<*>): IconRequestFailure {
    assertTrue(result is IconResult.Refused, "expected a refusal, got $result")
    return result.failure
  }

  @Test
  fun `answers a batch and reports only the names it does not know`(@TempDir temp: File) {
    val response =
      answered(
        icons(temp).outlines("outlined", listOf("search", "home", "no_such_icon"), emptyMap())
      )
    assertEquals(setOf("search", "home"), response.icons.keys)
    assertEquals(listOf("no_such_icon"), response.missing)
    // One unknown name must not cost the others their pictures: a grid page is a batch.
    response.icons.values.forEach { assertTrue(it.startsWith("M") && it.endsWith("Z"), it) }
  }

  @Test
  fun `axes reach the outline`(@TempDir temp: File) {
    val thin = answered(icons(temp).outlines("outlined", listOf("search"), mapOf("wght" to 100f)))
    val bold = answered(icons(temp).outlines("outlined", listOf("search"), mapOf("wght" to 700f)))
    assertTrue(
      thin.icons.getValue("search") != bold.icons.getValue("search"),
      "the weight axis did not change the path data",
    )
    assertEquals(mapOf("wght" to 100f), thin.axes)
  }

  @Test
  fun `names are served sorted, and only once`(@TempDir temp: File) {
    val names = answered(icons(temp).names("outlined")).names
    assertEquals(16, names.size)
    assertEquals(names.sorted(), names)
    assertEquals(names.distinct(), names)
    assertTrue("search" in names)
  }

  @Test
  fun `a repeated name is resolved once`(@TempDir temp: File) {
    val response =
      answered(icons(temp).outlines("outlined", listOf("search", "search", "search"), emptyMap()))
    assertEquals(1, response.icons.size)
  }

  @Test
  fun `an outline is resolved once per host`(@TempDir temp: File) {
    val icons = icons(temp)
    val first = answered(icons.outlines("outlined", listOf("search"), mapOf("wght" to 300f)))
    val second = answered(icons.outlines("outlined", listOf("search"), mapOf("wght" to 300f)))
    // Same string instance: the second call must come from the memo rather than repeat the glyph
    // read, the interpolation and the serialisation. Browser caching cannot cover this — a
    // different visitor, or a differently-composed batch, is a different request.
    assertSame(first.icons.getValue("search"), second.icons.getValue("search"))

    val other = answered(icons.outlines("outlined", listOf("search"), mapOf("wght" to 400f)))
    assertTrue(
      other.icons.getValue("search") != first.icons.getValue("search"),
      "a different axis position is a different outline, not a cache hit",
    )
  }

  @Test
  fun `refusals name what was wrong`(@TempDir temp: File) {
    assertEquals(
      IconRequestFailure.NoNames,
      refused(icons(temp).outlines("outlined", emptyList(), emptyMap())),
    )
    val tooMany =
      refused(icons(temp).outlines("outlined", List(300) { "search" }, emptyMap()))
        as IconRequestFailure.TooManyNames
    assertEquals(300, tooMany.asked)
    assertEquals(MaterialSymbolsIcons.MAXIMUM_NAMES, tooMany.limit)

    val unknown = refused(icons(temp).names("engraved")) as IconRequestFailure.UnknownStyle
    assertEquals("engraved", unknown.style)
    assertEquals(listOf("outlined"), unknown.known)
    assertTrue(MaterialSymbolsIcons.describe(unknown).contains("outlined"))
  }

  @Test
  fun `axis parameters are parsed, and refused when they are not numbers`() {
    val parsed =
      answered(
        MaterialSymbolsIcons.parseAxes(mapOf("wght" to "300", "FILL" to "1", "opsz" to "40")::get)
      )
    assertEquals(mapOf("FILL" to 1f, "opsz" to 40f, "wght" to 300f), parsed)

    // Both spellings, because the axis tags themselves are inconsistent.
    assertEquals(
      mapOf("GRAD" to 200f),
      answered(MaterialSymbolsIcons.parseAxes(mapOf("grad" to "200")::get)),
    )
    assertEquals(emptyMap(), answered(MaterialSymbolsIcons.parseAxes { null }))

    val bad = refused(MaterialSymbolsIcons.parseAxes(mapOf("wght" to "heavy")::get))
    assertEquals(IconRequestFailure.BadAxis("wght", "heavy"), bad)
    assertTrue(
      refused(MaterialSymbolsIcons.parseAxes(mapOf("wght" to "NaN")::get))
        is IconRequestFailure.BadAxis
    )
  }

  @Test
  fun `names parameter splitting tolerates the separators a client actually sends`(
    @TempDir temp: File
  ) {
    assertEquals(listOf("search", "home"), MaterialSymbolsIcons.parseNames("search,home"))
    assertEquals(listOf("search", "home"), MaterialSymbolsIcons.parseNames(" search , home ,"))
    assertEquals(emptyList(), MaterialSymbolsIcons.parseNames(null))
    assertEquals(emptyList(), MaterialSymbolsIcons.parseNames(" , "))
  }

  @Test
  fun `a grid page is small enough to be worth serving this way`(@TempDir temp: File) {
    val page = answered(icons(temp).names("outlined")).names
    val response = answered(icons(temp).outlines("outlined", page, emptyMap()))
    val bytes = response.icons.values.sumOf { it.length }
    // Sixteen icons here; the real page is eighty. The point of the measurement is the per-icon
    // cost the design rests on — about 550 bytes against 4.8 MB for the face they came from.
    assertTrue(bytes / page.size < 1_500, "per-icon payload grew to ${bytes / page.size} bytes")
  }
}
