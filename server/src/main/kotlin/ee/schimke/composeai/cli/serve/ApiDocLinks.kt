package ee.schimke.composeai.cli.serve

/**
 * API reference links behind a usage snippet: every `androidx.` / `android.` symbol the cleaned
 * code uses, resolved to its `developer.android.com` KDoc page. The snippet rather than the
 * preview, because [PlaygroundSourceCleaner] already pruned its imports to what the code touches.
 *
 * A composable's page is `<pkg>/<Name>.composable` and anything else's `<pkg>/<Name>`, so [linkFor]
 * infers the kind from use:
 * - a qualifier, annotation or type ⇒ declaration page;
 * - outside a Compose namespace ([composableNamespace]) ⇒ declaration page;
 * - called in statement position (after `{`, `}`, `)`, `;`, `->` or the start; not after `=`, `,`
 *   or `(`) ⇒ composable page;
 * - anything else (a bare property like `CircleShape`) ⇒ no link.
 *
 * Comments and strings are blanked first ([blankCommentsAndStrings]). Non-Android packages,
 * lower-case leaves, `Local…` locals and icon packs have no page. Pinned by `ApiDocLinksTest`.
 */
internal object ApiDocLinks {

  /** Where the reference pages live. A literal, so nothing snippet-derived reaches an `href`. */
  private const val BASE = "https://developer.android.com/reference/kotlin/"

  /** Most links one snippet may contribute, so a screen-sized panel stays screen-sized. */
  private const val MAX_LINKS = 24

  /** What a string literal's characters become — see [blankCommentsAndStrings]. */
  private const val STRING_FILL = '0'

  /**
   * Packages of value types only (`Color`, `Offset`, `Dp`, `TextStyle`-shaped), named because the
   * statement-position rule can't see through if/else expressions or `map { … }` lambdas, where
   * constructors sit where composables would.
   */
  private val VALUE_PACKAGES =
    listOf(
      "androidx.compose.ui.graphics",
      "androidx.compose.ui.geometry",
      "androidx.compose.ui.unit",
      "androidx.compose.ui.text",
    )

  /**
   * Trailing lambdas whose body is a value (`remember { MutableInteractionSource() }`), not a
   * composition.
   */
  private val VALUE_LAMBDAS =
    setOf(
      "remember",
      "rememberSaveable",
      "mutableStateOf",
      "mutableStateListOf",
      "derivedStateOf",
      "lazy",
      "runCatching",
    )

  /**
   * One resolved symbol: the [name] the snippet writes (alias included), the imported [fqn],
   * whether it resolved as a composable (picking the page shape), and the [url].
   */
  data class Link(val name: String, val fqn: String, val composable: Boolean, val url: String)

  private val IMPORT =
    Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*(?:as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""")

  /**
   * A fully qualified platform API in the body: lower-case package segments and exactly one
   * capitalised type, since `….Color.Transparent` is a member of `Color`, not a nested type.
   * Imports keep their whole chain because they name the type exactly.
   */
  private val QUALIFIED =
    Regex(
      """(?<![A-Za-z0-9_.])((?:androidx|android)(?:\.[a-z][A-Za-z0-9_]*)+\.[A-Z][A-Za-z0-9_]*)"""
    )

  /**
   * Reference links for [snippet]: composables first, then declarations, each in first-use order,
   * which puts the component the preview is about at the head. Empty when nothing is documented.
   */
  fun of(snippet: String): List<Link> {
    val body = StringBuilder()
    val candidates = mutableListOf<Pair<String, String>>()
    for (line in snippet.lineSequence()) {
      val match = IMPORT.matchEntire(line)
      if (match != null) {
        val fqn = match.groupValues[1]
        // A star import names no symbol, and has no leaf to choose a page shape for.
        if (!fqn.endsWith(".*")) {
          candidates += match.groupValues[2].ifEmpty { fqn.substringAfterLast('.') } to fqn
        }
      }
      // Import and `package` lines are blanked rather than dropped so the offsets that order the
      // links stay comparable with the source a reader is looking at.
      val keep = match == null && !line.trimStart().startsWith("package ")
      body.append(if (keep) line else "").append('\n')
    }
    val code = blankCommentsAndStrings(body.toString())
    // Fully qualified uses carry no import; the cleaner's `MATERIAL3_SYSTEM_THEME` rewrite emits
    // `androidx.compose.material3.MaterialTheme(...)` exactly this way.
    for (match in QUALIFIED.findAll(code)) {
      candidates += match.groupValues[1] to match.groupValues[1]
    }
    return candidates
      .mapNotNull { (spelling, fqn) -> linkFor(spelling, fqn, code) }
      // De-duplicated by page, since an alias and a qualified use can reach the same one.
      .distinctBy { it.link.url }
      .sortedWith(compareBy({ if (it.link.composable) 0 else 1 }, { it.firstUse }))
      .take(MAX_LINKS)
      .map { it.link }
  }

  /** A resolved link plus the offset that orders it; the offset never leaves this file. */
  private class Ranked(val link: Link, val firstUse: Int)

  private fun linkFor(spelling: String, fqn: String, code: String): Ranked? {
    if (!fqn.startsWith("androidx.") && !fqn.startsWith("android.")) return null
    val leaf = fqn.substringAfterLast('.')
    if (leaf.firstOrNull()?.isUpperCase() != true) return null
    // `android.permission.BLUETOOTH_CONNECT` is a String constant, not a type. Reached only
    // through the qualified scan, which cannot lean on an import line to tell it otherwise.
    if (leaf.length > 1 && leaf == leaf.uppercase()) return null
    if (fqn.contains(".compose.material.icons.") || fqn.contains(".compose.material3.icons.")) {
      return null
    }
    if (Regex("""^Local[A-Z]""").containsMatchIn(leaf)) return null
    val quoted = Regex.escape(spelling)
    // The name written on its own — not the tail of `Icons.Filled.Add`, not part of a longer
    // identifier. An import the snippet never spells this way is not a symbol its code uses.
    val firstUse =
      Regex("""(?<![A-Za-z0-9_.])$quoted(?![A-Za-z0-9_])""").find(code)?.range?.first ?: return null
    val composable =
      when {
        usedAsDeclaration(quoted, code) -> false
        // Outside a Compose namespace there is no `.composable` page, so call position isn't
        // consulted (constructors in callback lambdas look like composable calls).
        !composableNamespace(fqn) -> false
        calledInStatementPosition(spelling, code) -> true
        // Mentioned, but neither a type nor a call: a property, which has no page of its own.
        else -> return null
      }
    val url = BASE + referencePath(fqn) + if (composable) ".composable" else ""
    // Show the name the code writes (aliases included); a qualified use shows its leaf.
    val label = if (spelling.contains('.')) leaf else spelling
    return Ranked(Link(name = label, fqn = fqn, composable = composable, url = url), firstUse)
  }

  /**
   * Whether a `.composable` page could exist for [fqn]: namespaces publishing composables
   * (`.compose.` segment, plus Glance and TV Material) minus [VALUE_PACKAGES].
   */
  private fun composableNamespace(fqn: String): Boolean =
    (fqn.contains(".compose.") ||
      fqn.startsWith("androidx.glance.") ||
      fqn.startsWith("androidx.tv.material3.")) && VALUE_PACKAGES.none { fqn.startsWith("$it.") }

  /**
   * The reference path for [fqn]: lower-case package segments joined with `/`, the class chain kept
   * dotted (`LayoutElementBuilders.Box`), as the site spells nested types.
   */
  private fun referencePath(fqn: String): String {
    val segments = fqn.split('.')
    val firstType = segments.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    if (firstType < 0) return segments.joinToString("/")
    return (segments.subList(0, firstType) +
        segments.subList(firstType, segments.size).joinToString("."))
      .joinToString("/")
  }

  /** Qualifier, annotation, or type position — three uses a composable function never has. */
  private fun usedAsDeclaration(quoted: String, code: String): Boolean =
    Regex("""(?<![A-Za-z0-9_.])$quoted\s*\.""").containsMatchIn(code) ||
      Regex("""@$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex(""":\s*$quoted(?![A-Za-z0-9_])""").containsMatchIn(code) ||
      Regex("""[<,]\s*$quoted\s*[>,]""").containsMatchIn(code)

  /**
   * Whether [name] is called where a statement may start: code start, after `{` (except
   * [VALUE_LAMBDAS] and trailing lambdas of parenthesised calls), `}`, `)`, `;`, `->`, a `fun … ()
   * =` body, or after a line break that ended an expression. Kotlin has no terminator, so that line
   * break is what separates `val enabled = true` from `Button(…)`; a line ending in `=`, `,` or `(`
   * is a wrapped argument, not a statement.
   */
  private fun calledInStatementPosition(name: String, code: String): Boolean {
    val call = Regex("""(?<![A-Za-z0-9_.])${Regex.escape(name)}\s*[({]""")
    for (match in call.findAll(code)) {
      var j = match.range.first - 1
      var crossedLineBreak = false
      while (j >= 0 && code[j].isWhitespace()) {
        if (code[j] == '\n') crossedLineBreak = true
        j--
      }
      if (j < 0) return true
      when (code[j]) {
        '}',
        ';',
        ')' -> return true
        '>' -> if (j > 0 && code[j - 1] == '-') return true
        '=' -> {
          // `fun kitGlyph() = Icon(…)`: an expression body, whose `=` follows the parameter list.
          // An ordinary `argument = Value(…)` has an identifier there instead, and is not one.
          var k = j - 1
          while (k >= 0 && code[k].isWhitespace()) k--
          if (k >= 0 && code[k] == ')') return true
        }
        '{' -> if (ownerOfBrace(code, j) !in VALUE_LAMBDAS) return true
        else ->
          if (crossedLineBreak && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == ']')) {
            return true
          }
      }
    }
    return false
  }

  /**
   * The identifier a `{` at [brace] belongs to (`remember` in `remember { … }` or `remember(key) {
   * … }`); empty for a brace following no call.
   */
  private fun ownerOfBrace(code: String, brace: Int): String {
    var k = brace - 1
    while (k >= 0 && code[k].isWhitespace()) k--
    if (k >= 0 && code[k] == ')') {
      var depth = 0
      while (k >= 0) {
        if (code[k] == ')') depth++
        if (code[k] == '(') {
          depth--
          if (depth == 0) break
        }
        k--
      }
      k--
      while (k >= 0 && code[k].isWhitespace()) k--
    }
    val end = k
    while (k >= 0 && (code[k].isLetterOrDigit() || code[k] == '_')) k--
    return if (end > k) code.substring(k + 1, end + 1) else ""
  }

  /**
   * Replace comments and string contents with filler, keeping newlines so offsets survive. A
   * scanner rather than a regex, since `"a // b"` and `// "a` defeat any single pattern. Raw
   * `"""…"""` strings are consumed whole first. Strings blank to digits (still an expression that
   * can end a statement), comments to spaces.
   */
  private fun blankCommentsAndStrings(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
      val c = source[i]
      when {
        c == '"' && source.startsWith("\"\"\"", i) -> {
          val end = source.indexOf("\"\"\"", i + 3)
          val stop = if (end < 0) source.length else end + 3
          while (i < stop) {
            out.append(if (source[i] == '\n') '\n' else STRING_FILL)
            i++
          }
        }
        c == '"' -> {
          out.append(STRING_FILL)
          i++
          while (i < source.length) {
            if (source[i] == '\\') {
              out.append(STRING_FILL)
              if (i + 1 < source.length) {
                out.append(if (source[i + 1] == '\n') '\n' else STRING_FILL)
              }
              i += 2
              continue
            }
            val ch = source[i]
            out.append(if (ch == '\n') '\n' else STRING_FILL)
            i++
            if (ch == '"') break
          }
        }
        c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
          while (i < source.length && source[i] != '\n') {
            out.append(' ')
            i++
          }
        }
        c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
          val end = source.indexOf("*/", i + 2)
          val stop = if (end < 0) source.length else end + 2
          while (i < stop) {
            out.append(if (source[i] == '\n') '\n' else ' ')
            i++
          }
        }
        else -> {
          out.append(c)
          i++
        }
      }
    }
    return out.toString()
  }
}
