package ee.schimke.composeai.mcp

/**
 * The source a guideline review sees for a preview: the preview function, then the declarations in
 * the same file it reaches by name (#149).
 *
 * A preview is often a thin wrapper: `fun WeatherWidgetRoundPreview(…) =
 * WearWidgetPreview(MockWeatherWidget(…), params)` says nothing about the widget's document or its
 * `@RemoteComposable` content, which sit above it in the file. The code-level rules (a background
 * on the widget document, remote composables only, themed colours) can only be judged from those,
 * so every top-level declaration the preview names is added in file order, and the ones those name
 * in turn, while the excerpt stays within its line cap. Declarations elsewhere in the module are
 * not followed.
 *
 * This is a reader of the text, not a parser: brackets are counted outside string literals and
 * comments, which is enough to find where a declaration ends, and names are matched as whole
 * identifiers.
 */
internal object PreviewSourceExcerpt {
  fun of(lines: List<String>, line: Int?, maxLines: Int = 200): String? {
    if (line == null || lines.isEmpty()) return null
    val start = (line - 1).coerceIn(0, lines.lastIndex)
    val preview = start..declarationEnd(lines, start, maxLines)
    val declarations = topLevelDeclarations(lines).filterNot { it.range.first in preview }
    val included = mutableListOf<IntRange>()
    var budget = maxLines - preview.count()
    val pending = ArrayDeque(namesIn(lines, preview))
    val seen = mutableSetOf<String>()
    while (pending.isNotEmpty() && budget > 0) {
      val name = pending.removeFirst()
      if (!seen.add(name)) continue
      declarations
        .filter { it.name == name && included.none { range -> range == it.range } }
        .forEach { declaration ->
          if (declaration.range.count() > budget) return@forEach
          included += declaration.range
          budget -= declaration.range.count()
          pending += namesIn(lines, declaration.range)
        }
    }
    val sections = listOf(preview) + included.sortedBy { it.first }
    return sections.joinToString("\n\n") { range -> range.joinToString("\n") { lines[it] } }
  }

  /** A top-level declaration: its name and lines, its leading annotations included. */
  private data class Declaration(val name: String, val range: IntRange)

  private val DECLARATION =
    Regex(
      """^(?:(?:public|internal|private|protected|data|sealed|abstract|open|enum|annotation|""" +
        """inline|value|suspend|operator|infix|tailrec|override|expect|actual|const|""" +
        """lateinit|inner)\s+)*(?:fun|class|object|interface|val|var|typealias)\s+""" +
        """(?:<[^>]*>\s*)?(?:[A-Za-z_][\w]*\.)?([A-Za-z_]\w*)"""
    )

  private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

  private val KEYWORD = Regex("""\b(?:fun|class|object|interface|val|var|typealias)\b""")

  /** Every declaration at column 0, each with the annotation lines directly above it. */
  private fun topLevelDeclarations(lines: List<String>): List<Declaration> =
    lines.indices.mapNotNull { index ->
      val name = DECLARATION.find(lines[index])?.groupValues?.get(1) ?: return@mapNotNull null
      var first = index
      while (first > 0 && lines[first - 1].startsWith("@")) first--
      Declaration(name, first..declarationEnd(lines, first, lines.size))
    }

  /** The identifiers used in [range], comments and string contents left out. */
  private fun namesIn(lines: List<String>, range: IntRange): List<String> = range.flatMap {
    IDENTIFIER.findAll(codeOf(lines[it])).map { match -> match.value }.toList()
  }

  /**
   * The last line of the declaration starting at [start]: the line that closes its block body, or
   * ends its expression body or a body-less declaration (`class A(val x: Int)`), whichever comes
   * first, and at most [maxLines] lines on.
   */
  fun declarationEnd(lines: List<String>, start: Int, maxLines: Int): Int {
    val last = minOf(lines.lastIndex, start + maxLines - 1)
    var depth = 0
    var block = false
    var expression = false
    var signature = false
    for (index in start..last) {
      val code = codeOf(lines[index])
      val trimmed = code.trim()
      val annotationOnly = trimmed.startsWith("@") && KEYWORD.find(trimmed) == null
      if (!signature && (depth > 0 || trimmed.isEmpty() || annotationOnly)) {
        // Annotations before the declaration itself, a `@Preview(…)` over several lines included.
        depth += net(code)
        continue
      }
      signature = true
      var previous = ' '
      for ((at, ch) in code.withIndex()) {
        when (ch) {
          '(',
          '[' -> depth++
          ')',
          ']' -> depth--
          '{' -> {
            if (depth == 0 && !expression) block = true
            depth++
          }
          '}' -> depth--
          '=' -> {
            val next = code.getOrNull(at + 1) ?: ' '
            if (depth == 0 && !block && next != '=' && previous !in "=!<>") expression = true
          }
        }
        previous = ch
      }
      if (depth > 0) continue
      if (block) return index
      if (endsOpen(trimmed)) continue
      if (continues(lines, index + 1, last, expression)) continue
      return index
    }
    return last
  }

  private fun net(code: String): Int =
    code.count { it == '(' || it == '[' || it == '{' } -
      code.count { it == ')' || it == ']' || it == '}' }

  /** A line that cannot end a declaration: it ends with an operator or an opening token. */
  private fun endsOpen(trimmed: String): Boolean =
    trimmed.isEmpty() ||
      OPEN_ENDINGS.any { trimmed.endsWith(it) } ||
      (trimmed.endsWith(":") && !trimmed.endsWith("::"))

  /** Whether the next non-blank line carries this declaration on. */
  private fun continues(lines: List<String>, from: Int, last: Int, expression: Boolean): Boolean {
    val next =
      (from..last).asSequence().map { codeOf(lines[it]).trim() }.firstOrNull { it.isNotEmpty() }
        ?: return false
    return CONTINUATIONS.any { next.startsWith(it) } ||
      // A signature whose body or supertypes start on the next line.
      (!expression && (next.startsWith("{") || next.startsWith(":") || next.startsWith("where ")))
  }

  private val OPEN_ENDINGS =
    listOf("=", "(", "[", ",", ".", "+", "-", "*", "/", "&&", "||", "?:", "->")
  private val CONTINUATIONS = listOf(".", "?.", "?:", "&&", "||", "=")

  /**
   * [line] with string contents, character literals and comments blanked, so brackets and names
   * inside them are not counted. Multi-line strings and block comments spanning lines are rare in
   * previews and are read as code.
   */
  private fun codeOf(line: String): String {
    val out = StringBuilder(line.length)
    var index = 0
    while (index < line.length) {
      val ch = line[index]
      when {
        line.startsWith("//", index) -> break
        line.startsWith("/*", index) -> {
          val close = line.indexOf("*/", index + 2)
          index = if (close < 0) line.length else close + 2
          continue
        }
        ch == '"' || ch == '\'' -> {
          out.append(ch)
          index++
          while (index < line.length && line[index] != ch) {
            if (line[index] == '\\') index++
            index++
          }
          if (index < line.length) out.append(ch)
          index++
          continue
        }
        else -> out.append(ch)
      }
      index++
    }
    return out.toString()
  }
}
