package ee.schimke.composeai.cli.serve

/**
 * What a motion capture is called in the picker, and what it says once picked. Real catalog
 * captions are an instruction plus a paragraph of what to watch for:
 *
 * > Toggle repeatedly. The container morphs between its unchecked and checked shapes through the
 * > theme's spatial animation — Baseline swaps the shape, Expressive travels between them.
 *
 * Printing that on a button is a wall of prose, so the caption is split rather than truncated:
 * - [title] is the caption's first clause (ellipsized if long), shown in the closed menu.
 * - [detail] is the full caption, shown beside the frames once a capture is on stage.
 *
 * Free of HTML and [ServeWeb] so the cut rule is unit-testable.
 */
internal data class MotionCaptureLabel(
  /** The brief name: what the menu shows, always non-blank. */
  val title: String,
  /** The caption in full, whitespace-normalised. Blank when the annotation declared none. */
  val detail: String,
)

internal object MotionCaptureLabels {
  /**
   * Past this the closed menu sets the control row's width. Long enough for typical instruction
   * clauses ("Press and hold the card") to survive uncut.
   */
  private const val TITLE_MAX = 42

  /**
   * Label every capture of one preview, disambiguating only where titles would repeat (e.g. two
   * caption-less captures both reading "Interaction"); numbering every capture would add a "1" to
   * single-capture previews. First-clause titles collide often, so the detail line says what
   * differs.
   *
   * Where a colliding set is one component's light and dark recordings (the common case), the ids
   * already say which is which, so "(Light)"/"(Dark)" is used instead of numbers; see
   * [themeSuffixes].
   */
  fun of(captures: List<ServeMotion>): List<MotionCaptureLabel> {
    val base = captures.map { capture ->
      MotionCaptureLabel(
        title = briefTitle(capture.caption, capture.kind),
        detail = normalise(capture.caption),
      )
    }
    val totals = base.groupingBy { it.title }.eachCount()
    // Light/dark recordings of one gesture are the common collision; use the ids' theme rather than
    // numbers.
    val themed = themeSuffixes(captures, base, totals)
    val seen = mutableMapOf<String, Int>()
    return base.mapIndexed { i, label ->
      when {
        totals[label.title] == 1 -> label
        themed != null -> label.copy(title = "${label.title} (${themed[i]})")
        else -> {
          val n = (seen[label.title] ?: 0) + 1
          seen[label.title] = n
          label.copy(title = "${label.title} $n")
        }
      }
    }
  }

  /**
   * A theme word per capture, or null when the ids can't tell the whole set apart. All or nothing,
   * so "(Light)" never sits beside "2": every capture needs a distinct theme token, else numbering.
   */
  private fun themeSuffixes(
    captures: List<ServeMotion>,
    base: List<MotionCaptureLabel>,
    totals: Map<String, Int>,
  ): List<String>? {
    val themes = captures.map { themeToken(it.id) }
    for ((title, count) in totals) {
      if (count == 1) continue
      val group = base.indices.filter { base[it].title == title }.map { themes[it] }
      if (group.any { it == null } || group.distinct().size != group.size) return null
    }
    return themes.map { it?.replaceFirstChar { c -> c.uppercaseChar() } ?: "" }
  }

  /**
   * The theme a capture id names: the last standalone `light`/`dark` segment after the head, as in
   * the grid's theme pairing (earlier segments may be state, and the head is never a token).
   */
  private fun themeToken(id: String): String? {
    val parts = id.split("__")
    val idx = parts.indices.lastOrNull { it >= 1 && (parts[it] == "light" || parts[it] == "dark") }
    return idx?.let { parts[it] }
  }

  /** The caption's opening clause, or the capture's kind when there is no caption. */
  private fun briefTitle(caption: String?, kind: String?): String {
    val text = normalise(caption)
    if (text.isEmpty())
      return when (kind) {
        "interaction" -> "Interaction"
        "animation" -> "Animation"
        else -> "Capture"
      }
    return ellipsize(firstClause(text))
  }

  /**
   * Where the caption stops being a name: the end of its first sentence, or the first
   * dash/colon/semicolon, whichever comes first. Separators must be followed by a space (so
   * "1.5dp", "e.g.", "1:1" survive) and dashes preceded by one (so "press-and-hold" survives). A
   * terminator ending the whole caption is dropped.
   */
  private fun firstClause(text: String): String {
    var cut = text.length
    for (i in text.indices) {
      val c = text[i]
      val ends = i + 1 >= text.length || text[i + 1] == ' '
      val boundary =
        when {
          c == '.' -> ends && !abbreviationPeriod(text, i)
          c == '!' || c == '?' || c == ';' || c == ':' -> ends
          c == '—' || c == '–' || c == '-' -> ends && i > 0 && text[i - 1] == ' '
          else -> false
        }
      if (boundary) {
        cut = i
        break
      }
    }
    return text.substring(0, cut).trim().trimEnd('.', ',')
  }

  /** Periods in common prose abbreviations do not end the instruction clause. */
  private fun abbreviationPeriod(text: String, index: Int): Boolean {
    val prefix = text.substring(0, index + 1).lowercase()
    return ABBREVIATIONS.any(prefix::endsWith) ||
      // Initialisms such as "U.S." and "a.m." consist of repeated letter-period pairs.
      Regex("(?:[a-z]\\.){2,}$").containsMatchIn(prefix)
  }

  /** Cut long, on a word boundary, with the ellipsis that says the rest is in the detail line. */
  private fun ellipsize(text: String): String {
    if (text.length <= TITLE_MAX) return text
    val head = text.substring(0, TITLE_MAX)
    val lastSpace = head.lastIndexOf(' ')
    // A single word longer than the budget has no boundary to break on, so it is cut mid-word
    // rather than collapsed to an ellipsis on its own.
    val kept = if (lastSpace >= TITLE_MAX / 2) head.substring(0, lastSpace) else head
    return kept.trimEnd().trimEnd(',', '.') + "…"
  }

  /**
   * One line, single-spaced: captions arrive with source-formatter wrapping, and both controls are
   * one-line.
   */
  private fun normalise(text: String?): String = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

  private val ABBREVIATIONS =
    setOf("e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "prof.")
}
