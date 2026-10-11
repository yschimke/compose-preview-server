package ee.schimke.composeai.cli.serve

/**
 * How `ui_builder_search_components` matches a query against a catalog's components.
 *
 * An agent asks for a component the way it would write it — `TextField`, `text field`,
 * `outlined_text_field` — and the catalog spells the same thing `m3/text-field`, `Text field` and
 * `OutlinedTextField`. A literal substring match of the query against the id found none of those
 * but `text-field` (compose-preview-server, eval against 3.121.0). So both sides are folded the
 * same way before they are compared: lower case, with every separator (space, hyphen, underscore,
 * slash, dot) and every camelCase boundary dropped, which leaves `textfield` for all of them.
 *
 * A component matches when the folded query occurs in one of its folded names, or when every word
 * of the query occurs somewhere in it (`outlined field`). Matches are ranked so the component the
 * query names comes first: an exact name, then a name the query starts, then a name it occurs in,
 * then the role, traits and description.
 */
internal object UiBuilderComponentSearch {
  /** The facts of one component a query is matched against. */
  data class Candidate(
    val id: String,
    val displayName: String? = null,
    /** The Compose symbol the component exports as, e.g. `OutlinedTextField`. */
    val symbol: String? = null,
    val role: String? = null,
    val traits: List<String> = emptyList(),
    val description: String? = null,
  ) {
    /**
     * What the component is called: the id, its last path segment, the display name, the symbol.
     */
    internal val names: List<String> =
      listOfNotNull(id, id.substringAfterLast('/'), displayName, symbol)
        .map(::fold)
        .filter { it.isNotEmpty() }
        .distinct()

    /** Everything else that describes it. */
    internal val details: List<String> =
      (listOfNotNull(role, description) + traits).map(::fold).filter { it.isNotEmpty() }

    internal val words: Set<String> =
      (listOfNotNull(id, displayName, symbol, role, description) + traits).flatMap(::words).toSet()
  }

  /** The rank of a match; lower is better. */
  const val EXACT = 0
  const val PREFIX = 1
  const val NAME = 2
  const val DETAIL = 3
  const val WORDS = 4

  /**
   * [query]'s rank against [candidate], or null when it does not match. A query that folds to
   * nothing (only separators) matches nothing.
   */
  fun rank(query: String, candidate: Candidate): Int? {
    val folded = fold(query)
    if (folded.isEmpty()) return null
    return when {
      candidate.names.any { it == folded } -> EXACT
      candidate.names.any { it.startsWith(folded) } -> PREFIX
      candidate.names.any { folded in it } -> NAME
      candidate.details.any { folded in it } -> DETAIL
      else -> {
        val wanted = words(query)
        val haystack = (candidate.names + candidate.details).joinToString(" ")
        if (
          wanted.size > 1 &&
            wanted.all { word ->
              word in candidate.words ||
                candidate.words.any { it.startsWith(word) } ||
                word in haystack
            }
        )
          WORDS
        else null
      }
    }
  }

  /** The candidates [query] matches, best first, ties in their given order. */
  fun <T> search(query: String, items: List<T>, candidate: (T) -> Candidate): List<T> =
    items
      .mapIndexedNotNull { index, item ->
        rank(query, candidate(item))?.let { Triple(it, index, item) }
      }
      .sortedWith(compareBy({ it.first }, { it.second }))
      .map { it.third }

  /**
   * Lower case, with separators and camelCase boundaries removed: `Outlined-Text_Field` →
   * `outlinedtextfield`.
   */
  fun fold(text: String): String =
    buildString(text.length) {
      text.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
    }

  /**
   * The words of [text]: split at separators and camelCase boundaries, lower case.
   * `OutlinedTextField` is `outlined`, `text`, `field`; `m3/text-field` is `m3`, `text`, `field`.
   */
  fun words(text: String): List<String> =
    text
      .replace(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"), " ")
      .split(Regex("[^A-Za-z0-9]+"))
      .filter { it.isNotEmpty() }
      .map { it.lowercase() }
}
