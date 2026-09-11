package ee.schimke.composeai.cli.serve.icons

import kotlinx.serialization.Serializable

/** The icon names a face carries, which is what the picker's search box filters. */
@Serializable
internal data class IconNamesResponse(
  val style: String,
  /** The pin these names came from; a client echoes it so its cached URLs expire with the pin. */
  val pin: String,
  val names: List<String>,
)

/**
 * Outlines for the icons a caller asked for, at one axis position.
 *
 * [missing] is a field rather than an error because a batch is a grid page: one unknown name — a
 * design saved against an older pin, a typo in an MCP write — should not cost the other
 * seventy-nine their pictures.
 */
@Serializable
internal data class IconOutlinesResponse(
  val style: String,
  val axes: Map<String, Float>,
  val icons: Map<String, String>,
  val missing: List<String> = emptyList(),
)

/** What a caller got wrong, as something the route can turn into a status and a sentence. */
internal sealed interface IconRequestFailure {
  data class UnknownStyle(val style: String, val known: List<String>) : IconRequestFailure

  data class TooManyNames(val asked: Int, val limit: Int) : IconRequestFailure

  data class BadAxis(val axis: String, val value: String) : IconRequestFailure

  data object NoNames : IconRequestFailure
}

/** Either an answer or a reason there is none; every failure here is a 4xx. */
internal sealed interface IconResult<out T> {
  data class Answered<T>(val value: T) : IconResult<T>

  data class Refused(val failure: IconRequestFailure) : IconResult<Nothing>
}

/**
 * Serves outlines for the icons a client is about to draw, and nothing else.
 *
 * This is the whole reason no font reaches a browser: a design names about five icons and a grid
 * page shows eighty, so answering with ~550 bytes each is three orders of magnitude less traffic
 * than shipping the 6,614-glyph face it came from. The client caches what it gets by name and axis
 * tuple, and a design records the ones it keeps, so the same icon is asked for once.
 */
internal class MaterialSymbolsIcons(private val source: MaterialSymbolsSource) {

  /** The pin for [style], or null when the style is unknown. */
  fun pin(style: String): String? = source.pin(style)

  /**
   * The names a face carries.
   *
   * Answered from the shared code point list alone: the picker asks for this the moment it opens,
   * and making it wait on a 10 MB font transfer would defeat the route.
   */
  fun names(style: String): IconResult<IconNamesResponse> {
    val names =
      source.names(style)
        ?: return IconResult.Refused(IconRequestFailure.UnknownStyle(style, source.styleIds))
    return IconResult.Answered(
      IconNamesResponse(style, source.pin(style).orEmpty(), names.sorted())
    )
  }

  fun outlines(
    style: String,
    names: List<String>,
    axes: Map<String, Float>,
  ): IconResult<IconOutlinesResponse> {
    if (names.isEmpty()) return IconResult.Refused(IconRequestFailure.NoNames)
    if (names.size > MAXIMUM_NAMES) {
      return IconResult.Refused(IconRequestFailure.TooManyNames(names.size, MAXIMUM_NAMES))
    }
    val catalog =
      source.catalog(style)
        ?: return IconResult.Refused(IconRequestFailure.UnknownStyle(style, source.styleIds))
    val icons = LinkedHashMap<String, String>(names.size)
    val missing = ArrayList<String>()
    names.distinct().forEach { name ->
      val path = catalog.pathData(name, axes)
      if (path == null) missing += name else icons[name] = path
    }
    return IconResult.Answered(IconOutlinesResponse(style, axes, icons, missing))
  }

  internal companion object {
    /**
     * How many icons one request may ask for.
     *
     * A grid page is eighty and a design's whole icon set is a handful, so this is not a budget
     * anyone hits by using the picker — it is a bound on the work a single request can ask a host
     * to do, each name being a glyph parse and an interpolation.
     */
    const val MAXIMUM_NAMES = 256

    /** The axes a caller may set; an axis left out keeps the face's own default. */
    val AXES = listOf("FILL", "GRAD", "opsz", "wght")

    /**
     * Reads the repeated `names` parameter: `?names=search&names=home`, one name per value.
     *
     * One name per parameter rather than one comma-separated list, because the delimiter cannot
     * survive the round trip. A client percent-encodes a comma inside a name, but the query is
     * decoded before this sees it, so `%2C` and `,` arrive identical and a stale name containing a
     * comma — whatever an older design saved — would split into two. It would then push the batch
     * past [MAXIMUM_NAMES] and cost a whole grid page its pictures over one bad row. The query
     * parser already keeps repeated values apart, so this borrows that instead of inventing an
     * escape.
     *
     * Blanks are dropped rather than reported: an empty value is a client that built its query
     * string with one separator too many, not a request for an icon called "".
     */
    fun parseNames(raw: List<String>?): List<String> =
      raw?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    /**
     * Reads the axis query parameters, refusing anything that is not a number.
     *
     * A silently ignored `wght=heavy` would draw a regular-weight icon and look like a bug in the
     * slider, so it is a 400 naming the parameter instead. Both spellings are accepted because the
     * axis tags themselves are inconsistent — `FILL` and `GRAD` shout, `wght` and `opsz` do not —
     * and nobody should have to remember which.
     */
    fun parseAxes(parameter: (String) -> String?): IconResult<Map<String, Float>> {
      val axes = LinkedHashMap<String, Float>()
      AXES.forEach { axis ->
        val raw = parameter(axis) ?: parameter(axis.lowercase()) ?: return@forEach
        val value = raw.toFloatOrNull()
        if (value == null || !value.isFinite()) {
          return IconResult.Refused(IconRequestFailure.BadAxis(axis, raw))
        }
        axes[axis] = value
      }
      return IconResult.Answered(axes)
    }

    /** The sentence a refusal becomes, for a client that will show it to somebody. */
    fun describe(failure: IconRequestFailure): String =
      when (failure) {
        is IconRequestFailure.UnknownStyle ->
          "unknown icon style '${failure.style}'; this host serves ${failure.known.joinToString()}"
        is IconRequestFailure.TooManyNames ->
          "asked for ${failure.asked} icons; at most ${failure.limit} in one request"
        is IconRequestFailure.BadAxis ->
          "axis ${failure.axis} must be a number, was '${failure.value}'"
        IconRequestFailure.NoNames -> "name at least one icon with ?names="
      }
  }
}
