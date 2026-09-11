package ee.schimke.composeai.cli.serve.icons

/**
 * Resolves a Material Symbols icon *name* and a set of axis values to path data.
 *
 * Names reach glyphs through the `.codepoints` file that ships beside each variable font — a `name
 * codepoint` line per icon, 79 KB for the 4,284 names of the Outlined face. That file is fetched
 * and pinned with the font, and it is also the list the picker's search box filters, so there is no
 * second copy of the names anywhere.
 *
 * Reading the font's `GSUB` ligature table would answer the same question without the extra file
 * and is deliberately not done: it is a much larger piece of parsing for a mapping upstream already
 * publishes, and the ligature coverage is the one part of the font that cannot be subset away for a
 * test fixture.
 */
internal class MaterialSymbolsCatalog(
  private val font: MaterialSymbolsFont,
  private val codePoints: Map<String, Int>,
) {

  /** Every icon this face carries, in the order the codepoints file lists them. */
  val names: List<String>
    get() = codePoints.keys.toList()

  val axes: List<VariationAxis>
    get() = font.axes

  /** True when the name is in this face and the face actually carries a glyph for it. */
  fun contains(name: String): Boolean = glyphId(name) != null

  private fun glyphId(name: String): Int? = codePoints[name]?.let { font.glyphId(it) }

  /**
   * The outline of [name] at [axisValues], as `ImageVector` path data, or null for an unknown name.
   *
   * Callers hand this straight to `addPathNodes`, and the result is what the design's outline
   * registry records — so this is resolved once per icon per host, not once per draw.
   */
  @Synchronized
  fun pathData(name: String, axisValues: Map<String, Float> = emptyMap()): String? {
    val glyph = glyphId(name) ?: return null
    val key = name to axisValues.toSortedMap().toString()
    resolved[key]?.let {
      return it
    }
    val path = font.outline(glyph, axisValues).toPathData()
    // Bounded, and oldest-first: a host serves the same few hundred icons over and over — a
    // design's
    // own set, and whatever the pickers are scrolling — so this is a small map with a high hit rate
    // rather than an unbounded one. Without it every visitor, and every differently-composed batch,
    // repeats the glyph read, the interpolation and the serialisation for outlines that cannot
    // change while the pin is what it is.
    if (resolved.size >= MAXIMUM_RESOLVED) {
      resolved.keys.take(resolved.size - MAXIMUM_RESOLVED + 1).forEach(resolved::remove)
    }
    resolved[key] = path
    return path
  }

  private val resolved = LinkedHashMap<Pair<String, String>, String>()

  internal companion object {
    /**
     * How many resolved outlines a host keeps.
     *
     * Five thousand is roughly a face's whole default-axis set at ~550 bytes each — a few megabytes
     * — which is the shape of a host whose users browse the picker; a design's own icons are a
     * handful and never evicted in practice.
     */
    private const val MAXIMUM_RESOLVED = 5_000

    /**
     * Parses a `.codepoints` file: one `name hexcodepoint` line per icon.
     *
     * Unparseable lines are skipped rather than fatal. The file is pinned by digest, so a malformed
     * line means a format change upstream, and losing one icon is a better failure than refusing to
     * serve any.
     */
    fun readCodePoints(text: String): Map<String, Int> = buildMap {
      text.lineSequence().forEach { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 2) return@forEach
        val codePoint = parts[1].toIntOrNull(16) ?: return@forEach
        put(parts[0], codePoint)
      }
    }

    fun read(fontBytes: ByteArray, codePointsText: String): MaterialSymbolsCatalog =
      MaterialSymbolsCatalog(MaterialSymbolsFont.read(fontBytes), readCodePoints(codePointsText))
  }
}
