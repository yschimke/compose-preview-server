package ee.schimke.composeai.cli.serve

/**
 * Turns a catalog sticker's source into the **usage code** a developer would write, for the
 * playground handoff. Verbatim slices carry catalog scaffolding (annotations, `Sticker { }`, knobs,
 * wrappers), much of which does not resolve against the published bundle; cleaning is what makes
 * the seed runnable.
 *
 * Driven by the catalog's [UsageRules], in order:
 * 1. slice to the declaration containing the anchor line;
 * 2. strip catalog annotations, resolved through the file's imports;
 * 3. inline string resources;
 * 4. apply the scaffold rules ([UsageRules.Kind]);
 * 5. pull in and clean same-file references, recursively;
 * 6. prune imports, add what rewrites need, stamp a real `@Preview`.
 *
 * Structural questions go to [UsageSourceParser] (`:usage-source-psi` in an isolated classloader,
 * keeping the Kotlin frontend off this classpath) when its sidecar is staged; the remaining passes
 * are text, masked against strings and comments. Anything not understood is left alone,
 * [Result.residue] reports surviving scaffolding, and the caller falls back to the verbatim slice
 * when [clean] returns null. Formatting assumptions are ktfmt's Google style.
 */
object PlaygroundSourceCleaner {

  /**
   * @property text the cleaned Kotlin: imports, the entry declaration, and any same-file helpers it
   *   still needs.
   * @property entryFunction the name of the declaration the anchor fell in, for the editor's note.
   * @property residue declared scaffolding that survived every pass. Non-empty is reportable, not a
   *   failure: these are the names whose rules need writing.
   */
  data class Result(val text: String, val entryFunction: String?, val residue: List<String>)

  /**
   * Clean [source] around [bodyLine], or null when nothing is safe to do (no anchor, an anchor
   * outside any declaration, or no import header); the caller then seeds the verbatim slice.
   *
   * [strings] maps string-resource keys to English literals, empty without
   * [UsageRules.stringsPath].
   */
  fun clean(
    source: String,
    bodyLine: Int?,
    rules: UsageRules,
    strings: Map<String, String> = emptyMap(),
    parser: UsageSourceParser? = UsageSourceParser.of(),
    helperSources: List<String> = emptyList(),
    followedSources: List<String> = emptyList(),
  ): Result? {
    if (bodyLine == null) return null
    val lines = source.lines()
    if (bodyLine < 1 || bodyLine > lines.size) return null
    if (lines[bodyLine - 1].isBlank()) return null

    val helpers = helperIndex(helperSources)
    val extraImports = LinkedHashSet<Import>()
    val imports = importMap(lines)
    val blocks = topLevelBlocks(lines)
    val entryIndex =
      blocks.indexOfFirst { bodyLine - 1 in it.range }.takeIf { it >= 0 } ?: return null
    val declaredAt = blocks.withIndex().mapNotNull { (i, b) -> b.name?.let { it to i } }.toMap()

    val residue = LinkedHashSet<String>()
    val addedImports = LinkedHashSet<String>()
    val cleanedByIndex = LinkedHashMap<Int, String>()

    // Close over same-file references breadth-first; `seen` stops mutual recursion from looping.
    val queue = ArrayDeque(listOf(entryIndex))
    val seen = mutableSetOf<Int>()
    while (queue.isNotEmpty()) {
      val index = queue.removeFirst()
      if (!seen.add(index)) continue
      val block = blocks[index]
      val cleaned =
        cleanBlock(
          text = block.text,
          rules = rules,
          imports = imports,
          strings = strings,
          isEntry = index == entryIndex,
          residue = residue,
          addedImports = addedImports,
          parser = parser,
          helpers = helpers,
          extraImports = extraImports,
        )
      cleanedByIndex[index] = cleaned
      for ((name, at) in declaredAt) {
        if (at != index && at !in seen && mentionsWord(cleaned, name)) queue.addLast(at)
      }
    }

    // A cross-file helper named like the entry would make the preview call itself, so rename it
    // before the closure runs.
    val collision =
      resolveEntryNameCollision(
        blocks[entryIndex].name,
        cleanedByIndex[entryIndex],
        helpers,
        declaredAt.keys + rules.scaffolds.keys,
      )
    val closureHelpers = collision?.helpers ?: helpers
    collision?.let { cleanedByIndex[entryIndex] = it.entryBody }

    // Close over the declared scaffold sources too, which the same-file pass can't reach. Bounded,
    // since these are someone else's whole module.
    val cleanedHelpers =
      closeOverHelpers(
        seeds = cleanedByIndex.values,
        helpers = closureHelpers,
        skip = declaredAt.keys + rules.scaffolds.keys,
        rules = rules,
        strings = strings,
        residue = residue,
        addedImports = addedImports,
        parser = parser,
        extraImports = extraImports,
      )

    // Then one level into the files behind the preview's imported calls (PlaygroundSeedResolver's
    // followed calls). This is the code the reader came for, so it has larger limits; anything left
    // out is still residue, so a truncated closure isn't called runnable.
    val followedHelpers =
      closeOverHelpers(
        seeds = cleanedByIndex.values + cleanedHelpers,
        helpers = helperIndex(followedSources),
        skip = declaredAt.keys + rules.scaffolds.keys + closureHelpers.keys,
        rules = rules,
        strings = strings,
        residue = residue,
        addedImports = addedImports,
        parser = parser,
        extraImports = extraImports,
        maxClosures = MAX_FOLLOWED_CLOSURES,
        maxBytes = MAX_FOLLOWED_BYTES,
      )

    // Entry first, then its helpers in file order — a reader wants the composable they clicked at
    // the top, not after two private helpers they did not ask about.
    val bodies = buildList {
      add(cleanedByIndex.getValue(entryIndex))
      cleanedByIndex.keys
        .sorted()
        .filter { it != entryIndex }
        .forEach { add(cleanedByIndex.getValue(it)) }
      addAll(cleanedHelpers)
      addAll(followedHelpers)
    }
    val body = bodies.joinToString("\n\n").trimEnd()
    if (body.isBlank()) return null

    val header = headerFor(lines, imports, body, addedImports, rules, residue, extraImports)
    val text = if (header.isEmpty()) body else "$header\n\n$body"
    return Result(text, blocks[entryIndex].name, residue.toList())
  }

  // Blocks split by the "column 0 after a blank line" rule of PlaygroundSeed.sliceDeclaration (see
  // its KDoc), applied to every declaration.

  private data class Block(val range: IntRange, val text: String, val name: String?)

  private fun topLevelBlocks(lines: List<String>): List<Block> {
    val headerEnd = headerEndExclusive(lines)
    val starts = (headerEnd..lines.lastIndex).filter { startsTopLevelDeclaration(lines, it) }
    return starts.mapIndexed { i, start ->
      val nextStart = starts.getOrNull(i + 1) ?: (lines.size)
      var end = nextStart - 1
      while (end > start && lines[end].isBlank()) end--
      val text = lines.subList(start, end + 1).joinToString("\n")
      Block(start..end, text, declaredName(text))
    }
  }

  private fun startsTopLevelDeclaration(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    if (line.isBlank()) return false
    if (line.first().isWhitespace()) return false
    return i == 0 || lines[i - 1].isBlank()
  }

  private fun headerEndExclusive(lines: List<String>): Int {
    val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
    if (lastImport >= 0) return lastImport + 1
    val packageLine = lines.indexOfLast { it.trimStart().startsWith("package ") }
    return if (packageLine >= 0) packageLine + 1 else 0
  }

  /**
   * Anchored at column 0, making it a top-level declaration matcher; unanchored it would match
   * locals inside bodies.
   */
  /**
   * Modifiers match any run of lowercase words rather than a closed list, which missed `data
   * class`, `enum class` and the like. Over-matching is harmless since the pattern must still reach
   * a declaration keyword at column 0.
   */
  /**
   * The leading annotations a one-line declaration carries — `@Composable fun Sticker(id: String) =
   * …`, which is what ktfmt emits whenever the whole thing fits. Without this the declaration has
   * no *name* as far as [declaredName] is concerned, so it is invisible to both closure passes: a
   * one-line helper simply never came along, and the snippet called something it never brought.
   */
  private const val ANNOTATION_RUN = """(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)\n]*\))?\s+)*"""

  private val DECLARATION =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s+)?([A-Za-z_][A-Za-z0-9_]*)"""
    )

  /**
   * The name a declaration block introduces: the first column-0 declaration line, since blocks open
   * with KDoc and annotation stacks whose continuation lines look like neither.
   */
  /**
   * An extension declaration, captured by its callable name rather than its receiver ([DECLARATION]
   * would index `fun Morph.toComposePath` as `Morph`, so calls to it were never matched or
   * reported). Tried before [DECLARATION], only for `fun`.
   */
  private val EXTENSION_DECLARATION =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*fun\s+(?:<[^>]*>\s+)?[A-Za-z_][A-Za-z0-9_]*(?:<[^>]*>)?\??\.([A-Za-z_][A-Za-z0-9_]*)\s*\("""
    )

  private fun declaredName(text: String): String? =
    text.lines().firstNotNullOfOrNull { line ->
      EXTENSION_DECLARATION.find(line)?.groupValues?.get(1)
        ?: DECLARATION.find(line)?.groupValues?.get(1)
    }

  /**
   * One `import` line: the name the body uses ([name], the alias when present), the target, and how
   * to render it. Aliases are kept so `import foo.Bar as Baz` is neither pruned nor re-emitted
   * without `as Baz`.
   */
  private data class Import(val name: String, val fqn: String, val alias: String?) {
    fun render(): String = if (alias == null) "import $fqn" else "import $fqn as $alias"
  }

  private fun importsOf(lines: List<String>): List<Import> = lines.mapNotNull { line ->
    val t = line.trim()
    if (!t.startsWith("import ")) return@mapNotNull null
    val spec = t.removePrefix("import ").trim()
    val alias = spec.substringAfter(" as ", "").trim().ifEmpty { null }
    val fqn = spec.substringBefore(" as ").trim()
    val name = alias ?: fqn.substringAfterLast('.')
    if (name.isEmpty()) null else Import(name, fqn, alias)
  }

  /** Name → FQN, for resolving an annotation's simple name against the file's own imports. */
  private fun importMap(lines: List<String>): Map<String, String> =
    importsOf(lines).associate { it.name to it.fqn }

  /**
   * The cleaned file header: the imports [body] still uses plus those the rewrites introduced,
   * sorted.
   *
   * The `package` line is dropped: compiling into the catalog's package would let the snippet reach
   * `internal` members a real consumer can't. File annotations are kept only when not catalog
   * machinery and still needed by [body].
   */
  private fun headerFor(
    lines: List<String>,
    imports: Map<String, String>,
    body: String,
    addedImports: Set<String>,
    rules: UsageRules,
    residue: MutableSet<String>,
    extraImports: Set<Import> = emptySet(),
  ): String {
    // Kept whole, by paren balance rather than by line. A ktfmt-wrapped
    // `@file:OptIn(\n  A::class,\n  B::class,\n)` is one annotation across five lines, and a
    // line-at-a-time filter would emit its opening line alone — an unterminated annotation, and a
    // header that then prunes the imports only its discarded arguments referenced.
    val fileAnnotations =
      annotationBlocks(lines.takeWhile { !it.trimStart().startsWith("package ") })
        .filterNot { isScaffoldAnnotation(it.name, imports, rules) }
        .map { it.text }
    // Imports from the scaffold sources are added unless the preview file already binds that simple
    // name; duplicates wouldn't compile and the file being cleaned wins.
    val ownNames = importsOf(lines).map { it.name }.toSet()
    val candidates =
      importsOf(lines) + extraImports.filter { it.name !in ownNames }.distinctBy { it.name }
    val kept = candidates.filter { import ->
      if (isScaffoldPackage(import.fqn, rules)) {
        // A scaffold import that is still referenced means a rule is missing, not that the import
        // should be kept — record it and drop it, so the residue names the gap.
        if (mentionsIdentifier(body, import.name)) residue.add(import.name)
        false
      } else if (import.fqn in DELEGATION_IMPORTS) {
        // `by` delegation needs `getValue`/`setValue` without naming them; keep both whenever the
        // body delegates.
        usesPropertyDelegation(body)
      } else {
        mentionsIdentifier(body, import.name) ||
          fileAnnotations.any { mentionsIdentifier(it, import.name) }
      }
    }
    val all = (kept.map { it.render() } + addedImports.map { "import $it" }).distinct().sorted()
    return (fileAnnotations + (if (fileAnnotations.isEmpty()) emptyList() else listOf("")) + all)
      .joinToString("\n")
      .trim()
  }

  /** The imports a `by` delegation needs but never mentions (Compose `MutableState` delegation). */
  private val DELEGATION_IMPORTS =
    setOf("androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue")

  private fun usesPropertyDelegation(body: String): Boolean {
    val mask = codeMask(body)
    return Regex("""\b(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s+by\s""").findAll(body).any {
      mask[it.range.first]
    }
  }

  private data class AnnotationBlock(val name: String, val text: String)

  /**
   * File-level annotations as one block each, shared with [stripScaffoldAnnotations] so both agree
   * where an annotation ends.
   */
  private fun annotationBlocks(lines: List<String>): List<AnnotationBlock> {
    val out = mutableListOf<AnnotationBlock>()
    var i = 0
    while (i < lines.size) {
      if (!lines[i].trimStart().startsWith("@file:")) {
        i++
        continue
      }
      val end = annotationEnd(lines, i)
      out.add(
        AnnotationBlock(
          name = annotationName(lines[i]),
          text = lines.subList(i, end + 1).joinToString("\n"),
        )
      )
      i = end + 1
    }
    return out
  }

  private fun cleanBlock(
    text: String,
    rules: UsageRules,
    imports: Map<String, String>,
    strings: Map<String, String>,
    isEntry: Boolean,
    residue: MutableSet<String>,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser?,
    helpers: Map<String, Helper> = emptyMap(),
    extraImports: MutableSet<Import> = mutableSetOf(),
  ): String {
    var out = stripScaffoldAnnotations(text, imports, rules)
    // First: a delegating sticker has nothing to clean until its delegate is spliced in, so every
    // later pass runs over the real body. See [UsageRules.Kind.EXPAND].
    out = expandDelegates(out, rules, helpers, residue, extraImports)
    out = inlineStringResources(out, strings)
    // Before anything matches on a helper name: a call written fully qualified is the same call.
    out = unqualifyScaffoldCalls(out, rules)
    // Order matters: UNWRAP takes a wrapper's arguments away before DROP reasons about them; INLINE
    // lands substitutions before DROP inspects their arguments; theme wrappers and RENAME go last
    // so earlier passes still match original names.
    out = applyUnwrap(out, rules)
    // The parse settles argument binding, trailing-lambda calls and qualifiers; the text pass is
    // the fallback for a host with no staged sidecar (see [UsageSourceParser]).
    out =
      if (parser != null) applySubstituteParsed(out, rules, addedImports, parser)
      else applySubstitute(out, rules, addedImports)
    out = applyInline(out, rules, addedImports)
    out = applyDrop(out, rules, residue)
    out = applyMaterial3SystemTheme(out, rules)
    out = applyRename(out, rules, addedImports)
    if (isEntry) out = stampPreview(out, rules, addedImports)
    // Residue: with a parse every call is visible however qualified; the word scan stays for
    // non-call references (bindings, resource keys).
    val calledNames =
      parser?.facts(out)?.calls?.map { it.callee }?.toSet()
        ?: rules.scaffolds.keys.filter { mentionsQualifiedCall(out, it) }.toSet()
    for (name in rules.scaffolds.keys) {
      if (name in calledNames || mentionsWord(out, name)) residue.add(name)
    }
    return out.trimEnd()
  }

  /**
   * Reduce a package-qualified call to a declared helper
   * (`ee.schimke.composeai.overrides.previewOverrideString(…)`) to the bare name so the passes
   * below can rewrite it. Otherwise it is invisible: [wordOccurrences] rejects names after `.`, and
   * needing no import, it never shows as residue.
   *
   * Only packages the rules name ([UsageRules.scaffoldPackages]) count, so an ordinary receiver
   * chain like `state.metrics.counted { }` isn't stripped. Undeclared packages are caught as
   * residue by [mentionsQualifiedCall].
   */
  private fun unqualifyScaffoldCalls(text: String, rules: UsageRules): String {
    // Only helpers a pass will rewrite: unqualifying a [UsageRules.Kind.UNKNOWN] helper would turn
    // a resolving call into a bare one nothing fixes. Left qualified, it still reaches residue via
    // `mentionsQualifiedCall`.
    val rewritable = rules.scaffolds.filterValues { it.kind != UsageRules.Kind.UNKNOWN }
    if (rewritable.isEmpty() || rules.scaffoldPackages.isEmpty()) return text
    val names = rewritable.keys.joinToString("|") { Regex.escape(it) }
    val packages = rules.scaffoldPackages.joinToString("|") { Regex.escape(it) }
    // `[({]` and not just `(`: a trailing-lambda call — `counted { }` — has no parentheses at all,
    // and that is the shape most scaffolding wrappers are written in.
    val qualified = Regex("""(?<![A-Za-z0-9_.])(?:$packages)\.($names)(?=\s*[({])""")
    val mask = codeMask(text)
    val out = StringBuilder(text.length)
    var at = 0
    for (m in qualified.findAll(text)) {
      if (!mask[m.range.first]) continue
      out.append(text, at, m.range.first).append(m.groupValues[1])
      at = m.range.last + 1
    }
    return if (at == 0) text else out.append(text, at, text.length).toString()
  }

  private fun isScaffoldPackage(fqn: String, rules: UsageRules): Boolean =
    rules.scaffoldAnnotationPackages.any { fqn == it || fqn.startsWith("$it.") }

  private fun isScaffoldAnnotation(
    simpleName: String,
    imports: Map<String, String>,
    rules: UsageRules,
  ): Boolean {
    // Bare names first: an annotation in the previews' own package has no import to resolve. See
    // [UsageRules.scaffoldAnnotationNames].
    if (simpleName in rules.scaffoldAnnotationNames) return true
    val fqn = imports[simpleName] ?: return false
    return isScaffoldPackage(fqn, rules)
  }

  /**
   * Remove annotations whose simple name resolves into a catalog annotation package, including
   * multi-line forms consumed by parenthesis balance.
   */
  private fun stripScaffoldAnnotations(
    text: String,
    imports: Map<String, String>,
    rules: UsageRules,
  ): String {
    val lines = text.lines()
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trimStart()
      val isAnnotation = trimmed.startsWith("@")
      if (!isAnnotation) {
        out.add(line)
        i++
        continue
      }
      val end = annotationEnd(lines, i)
      if (!isScaffoldAnnotation(annotationName(line), imports, rules)) {
        for (j in i..end) out.add(lines[j])
      } else if (end == i) {
        // A scaffold annotation may share its line with the declaration (`@CatalogModes @Composable
        // fun X() = …`), so remove only its own span. Wrapped annotations own their whole lines and
        // are dropped whole.
        val remainder = lineWithoutLeadingAnnotation(line)
        if (remainder.isNotBlank()) out.add(remainder)
      }
      i = end + 1
    }
    return out.joinToString("\n")
  }

  /**
   * [line] with its leading `@Annotation(...)` removed, indentation kept; blank when the annotation
   * was the whole line.
   */
  private fun lineWithoutLeadingAnnotation(line: String): String {
    val indent = line.takeWhile { it.isWhitespace() }
    val body = line.substring(indent.length)
    if (!body.startsWith("@")) return line
    var index = 1
    if (body.startsWith("@file:")) index = "@file:".length
    while (index < body.length && (body[index].isLetterOrDigit() || body[index] == '_')) index++
    // Skip a balanced argument list, if any. Nesting and string literals both matter: an argument
    // can itself be an annotation (`@OptIn(A::class, B::class)`) and a string can hold a bracket.
    if (index < body.length && body[index] == '(') {
      var depth = 0
      var inString = false
      while (index < body.length) {
        val char = body[index]
        when {
          inString && char == '\\' -> index++
          char == '"' -> inString = !inString
          !inString && char == '(' -> depth++
          !inString && char == ')' -> {
            depth--
            if (depth == 0) {
              index++
              break
            }
          }
        }
        index++
      }
      // Unbalanced — not something to guess at. Leave the line to the caller's whole-span drop.
      if (depth != 0) return ""
    }
    val rest = body.substring(index).trimStart()
    return if (rest.isEmpty()) "" else indent + rest
  }

  /** `@file:OptIn(...)` / `@CatalogComponent(...)` → `OptIn` / `CatalogComponent`. */
  private fun annotationName(line: String): String =
    line.trimStart().removePrefix("@").removePrefix("file:").takeWhile {
      it.isLetterOrDigit() || it == '_'
    }

  /**
   * The index of the last line of the annotation starting at [start] — the same line when it takes
   * no arguments or fits on one, and the line closing its argument list when ktfmt has wrapped it.
   */
  private fun annotationEnd(lines: List<String>, start: Int): Int {
    var depth = 0
    var end = start
    while (end < lines.size) {
      depth += parenBalance(lines[end])
      if (depth <= 0) break
      end++
    }
    return minOf(end, lines.lastIndex)
  }

  private fun parenBalance(line: String): Int {
    val mask = codeMask(line)
    var n = 0
    for (k in line.indices) {
      if (!mask[k]) continue
      if (line[k] == '(') n++
      if (line[k] == ')') n--
    }
    return n
  }

  /**
   * `stringResource(Res.string.label_filled)` → `"Filled"`, so a snippet shows the label rather
   * than the catalog's resource lookup. Only exact single-argument `Res.string.<key>` lookups are
   * inlined.
   */
  private fun inlineStringResources(text: String, strings: Map<String, String>): String {
    if (strings.isEmpty()) return text
    // Masked so a literal or comment quoting a lookup isn't rewritten.
    val mask = codeMask(text)
    val sb = StringBuilder()
    var last = 0
    for (m in Regex("""stringResource\(\s*Res\.string\.([A-Za-z0-9_]+)\s*\)""").findAll(text)) {
      val value = strings[m.groupValues[1]] ?: continue
      if (!mask[m.range.first]) continue
      sb.append(text, last, m.range.first)
      sb.append("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      last = m.range.last + 1
    }
    if (last == 0) return text
    sb.append(text, last, text.length)
    return sb.toString()
  }

  private fun applyRename(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.RENAME || scaffold.special != null) continue
      val to = scaffold.renameTo ?: continue
      if (!mentionsWord(out, name)) continue
      out = replaceWord(out, name, to)
      addedImports.addAll(scaffold.imports)
    }
    return out
  }

  /**
   * `Sticker { … }` → a stock Material 3 theme that consumes the preview's `uiMode`. Only the name
   * is replaced, leaving the trailing lambda intact. Only argument-free trailing-lambda calls
   * qualify; `StickerFrame(tokens) { … }` may encode more and stays residue.
   */
  private fun applyMaterial3SystemTheme(text: String, rules: UsageRules): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (
        scaffold.kind != UsageRules.Kind.RENAME ||
          scaffold.special != UsageRules.MATERIAL3_SYSTEM_THEME
      )
        continue
      // Fully qualified so it can't clash with a MaterialTheme the body already uses by simple
      // name.
      for (at in wordOccurrences(out, name).asReversed()) {
        var next = at + name.length
        while (next < out.length && out[next].isWhitespace()) next++
        var replaceEnd = at + name.length
        if (out.getOrNull(next) == '(') {
          val close = matchParen(out, next) ?: continue
          if (containsCode(out.substring(next + 1, close))) continue
          replaceEnd = close + 1
          next = close + 1
          while (next < out.length && out[next].isWhitespace()) next++
        }
        if (out.getOrNull(next) != '{') continue
        out = out.replaceRange(at, replaceEnd, MATERIAL3_SYSTEM_THEME_CALL)
      }
    }
    return out
  }

  /** `ButtonFrame(size) { <body> }` → `<body>`, de-indented to the call's own column. */
  private fun applyUnwrap(text: String, rules: UsageRules): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.UNWRAP) continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val (callStart, lambdaOpen) = findWrapperCall(out, name) ?: break
        val lambdaClose = matchBrace(out, lambdaOpen) ?: break
        val inner = out.substring(lambdaOpen + 1, lambdaClose)
        val callIndent = indentOf(out, callStart)
        val lineStart = out.lastIndexOf('\n', callStart - 1) + 1
        val prefix = out.substring(lineStart, callStart)
        val body = reindent(inner, callIndent)
        // Indentation-only prefix: splice from the line start (that indent is the target column).
        // Otherwise the prefix belongs to the declaration (`fun Card() = ButtonFrame(size) { … }`):
        // keep it and drop the body's first-line indent.
        out =
          if (prefix.isBlank()) out.substring(0, lineStart) + body + out.substring(lambdaClose + 1)
          else out.substring(0, callStart) + body.trimStart() + out.substring(lambdaClose + 1)
      }
    }
    return out
  }

  /**
   * A wrapper call and the `{` of its trailing lambda, with or without parentheses. [findCall]
   * requires `(`, which missed the common paren-less form.
   */
  private fun findWrapperCall(text: String, name: String): Pair<Int, Int>? {
    for (at in wordOccurrences(text, name)) {
      var k = at + name.length
      while (k < text.length && text[k].isWhitespace()) k++
      if (k < text.length && text[k] == '(') {
        val close = matchParen(text, k) ?: continue
        k = close + 1
        while (k < text.length && text[k].isWhitespace()) k++
      }
      if (k < text.length && text[k] == '{') return at to k
    }
    return null
  }

  /** A `name = value` argument, as distinct from a positional one. */
  private val NAMED_ARG =
    Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

  /**
   * Bind a call's arguments to the positions `$0`/`$1` templates cite, using Kotlin's rule
   * (positional left to right, named by name) when [params] is known. Without [params],
   * named-argument calls return null and become residue rather than guessed. Unknown parameter
   * names are ignored and consume no slot.
   */
  private fun bindArguments(args: List<String>, params: List<String>): List<String?>? {
    val named = args.map { NAMED_ARG.find(it) }
    if (params.isEmpty()) return if (named.any { it != null }) null else args
    val bound = arrayOfNulls<String>(params.size)
    var next = 0
    for ((i, arg) in args.withIndex()) {
      val match = named[i]
      if (match == null) {
        // Positional: the next parameter no named argument has already claimed.
        while (next < params.size && bound[next] != null) next++
        if (next >= params.size) continue // beyond the declared list; nothing cites it
        bound[next++] = arg
      } else {
        val at = params.indexOf(match.groupValues[1])
        if (at >= 0) bound[at] = match.groupValues[2].trim()
      }
    }
    return bound.toList()
  }

  /**
   * [applySubstitute] over a real parse: arguments bind as Kotlin binds them, trailing-lambda calls
   * count, and qualified calls are replaced whole. Re-parses after each rewrite, innermost first,
   * so nested knobs are plain before the outer call reads them.
   */
  private fun applySubstituteParsed(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser,
  ): String {
    var out = text
    var guard = 0
    while (guard++ < MAX_REWRITES) {
      val facts = parser.facts(out) ?: return out
      val edit =
        facts.calls
          .asSequence()
          .filter { rules.scaffolds[it.callee]?.kind == UsageRules.Kind.SUBSTITUTE }
          // Only a bare call, or one qualified by a named scaffold package, is this scaffold; a
          // member call of the same name must keep its receiver.
          .filter { it.receiver == null || it.receiver in rules.scaffoldPackages }
          .sortedByDescending { it.start }
          .mapNotNull { call ->
            val scaffold = rules.scaffolds.getValue(call.callee)
            val plain = scaffold.plain ?: return@mapNotNull null
            val args = facts.bind(call, scaffold.params) ?: return@mapNotNull null
            val rendered =
              Regex("""\$(\d+)""").replace(plain) { m ->
                args.getOrNull(m.groupValues[1].toInt()) ?: m.value
              }
            // A template citing an argument the call does not have would emit a literal `$1`. Leave
            // the call alone; the residue scan then reports it as an unwritten rule.
            if (rendered.contains(Regex("""\$\d"""))) null
            else Triple(call.replaceStart, call.replaceEnd, rendered to scaffold)
          }
          .firstOrNull() ?: return out
      val (start, end, replacement) = edit
      if (start < 0 || end > out.length || start >= end) return out
      out = out.substring(0, start) + replacement.first + out.substring(end)
      addedImports.addAll(replacement.second.imports)
    }
    return out
  }

  /**
   * `catalogChoice("style", "outlined", …)` → `"outlined"`: the call replaced by its value on the
   * baked lane. Runs before [applyInline] so nested knobs are plain first.
   */
  private fun applySubstitute(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.SUBSTITUTE) continue
      val plain = scaffold.plain ?: continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val call = findCall(out, name) ?: break
        val args =
          bindArguments(
            splitTopLevel(out.substring(call.argsStart + 1, call.argsEnd)).map { it.trim() },
            scaffold.params,
          ) ?: break
        val rendered =
          Regex("""\$(\d+)""").replace(plain) { m ->
            args.getOrNull(m.groupValues[1].toInt()) ?: m.value
          }
        // A template citing an argument the call does not have would silently emit `$1`. Leave the
        // call alone instead; the residue check below then reports it as an unwritten rule.
        if (rendered.contains(Regex("""\$\d"""))) break
        out = out.substring(0, call.start) + rendered + out.substring(call.argsEnd + 1)
        addedImports.addAll(scaffold.imports)
      }
    }
    return out
  }

  /**
   * `val c = counted("Filled")` + `c.onClick` + `c.label` → `{}` + `"Filled"`, with the binding
   * line deleted.
   */
  private fun applyInline(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.INLINE) continue
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val binding = findValBinding(out, name) ?: break
        val replacements =
          scaffold.members.mapValues { (_, template) ->
            Regex("""\$(\d+)""").replace(template) { m ->
              binding.arguments.getOrNull(m.groupValues[1].toInt())?.trim() ?: m.value
            }
          }
        // A template citing an absent argument would emit a literal `$1` after deleting the
        // binding; leave the declaration and let residue report it.
        if (replacements.values.any { it.contains(Regex("""\$\d""")) }) break
        out = removeLines(out, binding.lineRange)
        for ((member, replacement) in replacements) {
          out = replaceWord(out, "${binding.name}.$member", replacement)
        }
        addedImports.addAll(scaffold.imports)
      }
    }
    return out
  }

  /**
   * Delete a knob and everything downstream: its `val` binding and every named argument mentioning
   * either. Positional arguments are never touched (removing one breaks compilation).
   *
   * All or nothing per declaration: if any reference survives, the whole DROP is abandoned, the
   * original returned, and the helper recorded in [residue].
   */
  private fun applyDrop(text: String, rules: UsageRules, residue: MutableSet<String>): String {
    val dropped = mutableSetOf<String>()
    val helpers = mutableSetOf<String>()
    var out = text
    for ((name, scaffold) in rules.scaffolds) {
      if (scaffold.kind != UsageRules.Kind.DROP) continue
      if (!mentionsWord(out, name)) continue
      helpers.add(name)
      dropped.add(name)
      var guard = 0
      while (guard++ < MAX_REWRITES) {
        val binding = findValBinding(out, name) ?: break
        dropped.add(binding.name)
        out = removeLines(out, binding.lineRange)
      }
    }
    if (dropped.isEmpty()) return out
    out =
      filterCallArguments(out) { arg ->
        isNamedArgument(arg) && dropped.any { mentionsWord(arg, it) }
      }
    val survivor = dropped.firstOrNull { mentionsWord(out, it) }
    if (survivor != null) {
      residue.addAll(helpers)
      return text
    }
    return out
  }

  private fun isNamedArgument(arg: String): Boolean =
    Regex("""^\s*[A-Za-z_][A-Za-z0-9_]*\s*=[^=]""").containsMatchIn(arg)

  /** Puts a real `@Preview` back on the entry point, since the catalog's own was just stripped. */
  private fun stampPreview(
    text: String,
    rules: UsageRules,
    addedImports: MutableSet<String>,
  ): String {
    val simple = rules.previewAnnotation.substringAfterLast('.')
    if (mentionsWord(text, "@$simple")) return text
    val lines = text.lines().toMutableList()
    val at = lines.indexOfFirst { it.trimStart().startsWith("@Composable") }
    val insertAt = if (at >= 0) at else lines.indexOfFirst { DECLARATION.containsMatchIn(it) }
    if (insertAt < 0) return text
    lines.add(insertAt, "@$simple")
    addedImports.add(rules.previewAnnotation)
    return lines.joinToString("\n")
  }

  // Cross-file scaffolding: see [UsageRules.scaffoldSources] and [UsageRules.Kind.EXPAND].

  /** One top-level declaration read out of a scaffold source, carrying its file's imports. */
  private data class Helper(
    val text: String,
    val imports: List<Import>,
    val importMap: Map<String, String>,
  )

  /**
   * Max declarations pulled from scaffold sources. The same-file closure is unbounded, but these
   * are a whole shared module.
   */
  private const val MAX_HELPER_CLOSURES = 8

  /** And no single one larger than this: past it the helper *is* the snippet. */
  private const val MAX_HELPER_BYTES = 4_000

  /** Declarations pulled in one level down, through a preview's followed calls. */
  private const val MAX_FOLLOWED_CLOSURES = 16

  /** And no single one larger than this: a whole sample screen fits, a generated table does not. */
  private const val MAX_FOLLOWED_BYTES = 24_000

  /**
   * Name → declaration across every scaffold source. Names declared more than once (across files or
   * as overloads) are removed rather than resolved by order, so one catalog's helper is never
   * spliced into another's snippet.
   */
  private fun helperIndex(sources: List<String>): Map<String, Helper> {
    if (sources.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Helper>()
    val ambiguous = mutableSetOf<String>()
    for (source in sources) {
      val lines = source.lines()
      val imports = importsOf(lines)
      val importMap = imports.associate { it.name to it.fqn }
      for (block in topLevelBlocks(lines)) {
        val name = block.name ?: continue
        if (out.put(name, Helper(block.text, imports, importMap)) != null) ambiguous.add(name)
      }
    }
    ambiguous.forEach { out.remove(it) }
    return out
  }

  /**
   * Clean whatever the seeds still call from the scaffold sources, breadth-first. [skip] holds
   * names handled elsewhere: same-file declarations and rule-described scaffolds (rewritten, never
   * copied in).
   */
  /**
   * The rename applied when a cross-file helper shares the entry's name: [helpers] re-keyed to
   * [renamedTo], and [entryBody] with its calls (not its declaration) rewritten.
   */
  private class EntryNameCollision(
    val helpers: Map<String, Helper>,
    val entryBody: String,
    val renamedTo: String,
  )

  /**
   * Rename a scaffold helper that collides with the entry preview's name, or null. After EXPAND the
   * entry's body may call a component with the entry's own name (e.g. `SegmentedToggle`), which
   * would otherwise emit a self-recursive preview. Renaming keeps the helper's shape rather than
   * inlining it.
   */
  private fun resolveEntryNameCollision(
    entryName: String?,
    entryBody: String?,
    helpers: Map<String, Helper>,
    taken: Set<String>,
  ): EntryNameCollision? {
    if (entryName == null || entryBody == null) return null
    val helper = helpers[entryName] ?: return null
    // Declaring the name is not the problem; CALLING it is. A preview that merely shares a name
    // with something in the shared module, and never references it, needs nothing done.
    val callSites = callOccurrences(entryBody, entryName)
    if (callSites.isEmpty()) return null

    val renamed = freshName(entryName, taken + helpers.keys)
    // Every word occurrence in the helper — its declaration, and any recursion inside it.
    val renamedText =
      replaceAt(helper.text, wordOccurrences(helper.text, entryName), entryName, renamed)
    return EntryNameCollision(
      helpers = helpers - entryName + (renamed to helper.copy(text = renamedText)),
      entryBody = replaceAt(entryBody, callSites, entryName, renamed),
      renamedTo = renamed,
    )
  }

  /** Offsets where [name] is called (followed by `(`), excluding its own `fun` header. */
  private fun callOccurrences(text: String, name: String): List<Int> =
    wordOccurrences(text, name).filter { at ->
      val after = text.drop(at + name.length).takeWhile { it.isWhitespace() || it == '(' }
      if (!after.contains('(')) return@filter false
      val before = text.take(at).trimEnd()
      !before.endsWith("fun")
    }

  /** [text] with each offset in [at] (which must all start [from]) replaced by [to]. */
  private fun replaceAt(text: String, at: List<Int>, from: String, to: String): String {
    if (at.isEmpty()) return text
    val builder = StringBuilder(text)
    for (offset in at.sortedDescending()) builder.replace(offset, offset + from.length, to)
    return builder.toString()
  }

  /** `Name`, `NameComponent`, `NameComponent2`, … — the first not already spoken for. */
  private fun freshName(base: String, taken: Set<String>): String {
    val preferred = base + "Component"
    if (preferred !in taken) return preferred
    var suffix = 2
    while ("$preferred$suffix" in taken) suffix++
    return "$preferred$suffix"
  }

  private fun closeOverHelpers(
    seeds: Collection<String>,
    helpers: Map<String, Helper>,
    skip: Set<String>,
    rules: UsageRules,
    strings: Map<String, String>,
    residue: MutableSet<String>,
    addedImports: MutableSet<String>,
    parser: UsageSourceParser?,
    extraImports: MutableSet<Import>,
    maxClosures: Int = MAX_HELPER_CLOSURES,
    maxBytes: Int = MAX_HELPER_BYTES,
  ): List<String> {
    if (helpers.isEmpty()) return emptyList()
    val cleaned = LinkedHashMap<String, String>()
    val queue = ArrayDeque<String>()
    val queued = mutableSetOf<String>()
    fun enqueue(text: String) {
      for (name in helpers.keys) {
        if (name in skip || name in queued) continue
        // Check extension calls too: `mentionsWord` rejects names after `.`, which is an
        // extension's only call shape.
        if (mentionsWord(text, name) || mentionsExtensionCall(text, name)) {
          queued.add(name)
          queue.addLast(name)
        }
      }
    }
    seeds.forEach(::enqueue)
    while (queue.isNotEmpty() && cleaned.size < maxClosures) {
      val name = queue.removeFirst()
      val helper = helpers.getValue(name)
      // Too big to be an example. Left uncopied and named in the residue, so the note says the
      // snippet still refers to something it did not bring along.
      if (helper.text.length > maxBytes) {
        residue.add(name)
        continue
      }
      val body =
        cleanBlock(
          text = helper.text,
          rules = rules,
          imports = helper.importMap,
          strings = strings,
          isEntry = false,
          residue = residue,
          addedImports = addedImports,
          parser = parser,
          helpers = helpers,
          extraImports = extraImports,
        )
      cleaned[name] = body
      extraImports.addAll(helper.imports)
      enqueue(body)
    }
    // Whatever the cap left in the queue is still referenced and still not here; say so rather than
    // let the note claim a snippet that closes over everything it uses.
    queue.forEach { residue.add(it) }
    return cleaned.values.toList()
  }

  /**
   * Replace every call to an [UsageRules.Kind.EXPAND] helper with its body, parameters bound to the
   * arguments. Iterative, since expansions may call further delegates; a helper whose expansion
   * calls itself is declined.
   */
  private fun expandDelegates(
    text: String,
    rules: UsageRules,
    helpers: Map<String, Helper>,
    residue: MutableSet<String>,
    extraImports: MutableSet<Import>,
  ): String {
    val expandable =
      rules.scaffolds
        .filterValues { it.kind == UsageRules.Kind.EXPAND }
        .keys
        .filter { helpers.containsKey(it) }
    if (expandable.isEmpty()) return text
    var out = text
    if (expandable.any { findCall(out, it) != null }) out = blockBodyForm(out)
    val declined = mutableSetOf<String>()
    var guard = 0
    while (guard++ < MAX_REWRITES) {
      val name = expandable.firstOrNull { it !in declined && findCall(out, it) != null } ?: break
      val helper = helpers.getValue(name)
      val call = findCall(out, name) ?: break
      val expansion = expandCall(helper, out.substring(call.argsStart + 1, call.argsEnd))
      if (expansion == null || mentionsWord(expansion, name)) {
        declined.add(name)
        residue.add(name)
        continue
      }
      out = spliceExpansion(out, call, expansion)
      extraImports.addAll(helper.imports)
    }
    return out
  }

  /**
   * `fun X() = Sticker("id")` → `fun X() { Sticker("id") }`, since a multi-statement expansion
   * can't follow `=`. Only for `@Composable` with no declared return type (i.e. `Unit`).
   */
  private fun blockBodyForm(text: String): String {
    if (!mentionsWord(text, "@Composable")) return text
    val mask = codeMask(text)
    val head = findFunctionHead(text, mask) ?: return text
    val open = head.range.last
    val close = matchParen(text, open) ?: return text
    var i = close + 1
    while (i < text.length && text[i].isWhitespace()) i++
    // A `:` here is a declared return type, and `{` is already a block body. Only a bare `=` is the
    // Unit-returning expression body this may rewrite.
    if (i >= text.length || text[i] != '=' || text.getOrNull(i + 1) == '=') return text
    val body = text.substring(i + 1).trim()
    if (body.isEmpty()) return text
    val indent = head.range.first - (text.lastIndexOf('\n', head.range.first - 1) + 1)
    return text.substring(0, i) +
      "{\n" +
      reindent(body, indent + 2) +
      "\n" +
      " ".repeat(indent) +
      "}"
  }

  /**
   * Line-start anchored: unanchored, the optional modifier run matched into the previous line
   * (`omposable`) and swallowed the real declaration.
   */
  private val FUNCTION_HEAD =
    Regex(
      """^$ANNOTATION_RUN(?:[a-z]+\s+)*fun\s+(?:<[^>]*>\s+)?[A-Za-z_][A-Za-z0-9_]*\s*\(""",
      RegexOption.MULTILINE,
    )

  /**
   * The declaration's own `fun Name(` at a code position and line start, so a `fun` in its KDoc
   * never matches.
   */
  private fun findFunctionHead(text: String, mask: BooleanArray): MatchResult? =
    FUNCTION_HEAD.findAll(text).firstOrNull { match ->
      val at = match.range.first
      mask[at] && text.lastIndexOf('\n', at - 1) + 1 == at
    }

  /** A declared parameter: the name a body refers to it by, and its default if it has one. */
  private data class Param(val name: String, val default: String?)

  private data class FunctionDecl(val params: List<Param>, val body: String)

  /**
   * [helper]'s body with parameters bound to [argsText], or null whenever that would be a guess
   * (unparseable, unbindable, missing argument, or a non-literal `when` subject). The caller then
   * reports the call as residue.
   */
  private fun expandCall(helper: Helper, argsText: String): String? {
    val decl = parseFunction(helper.text) ?: return null
    val args =
      bindArguments(splitTopLevel(argsText).map { it.trim() }, decl.params.map { it.name })
        ?: return null
    val bound =
      decl.params.mapIndexed { i, p -> p.name to (args.getOrNull(i) ?: p.default) }.toMap()
    if (bound.values.any { it == null }) return null
    // A dispatch is all-or-nothing: the selected branch or no expansion, never the whole component
    // set.
    val dispatch = loneWhen(decl.body)
    var body = if (dispatch == null) decl.body else dispatchBranch(dispatch, bound) ?: return null
    for ((name, value) in bound) body = replaceWord(body, name, value!!)
    // Reindented to column 0 rather than trimmed, which would leave continuation lines at their old
    // column.
    return reindent(body, 0).ifBlank { null }
  }

  private val WHEN_SUBJECT = Regex("""^when\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)\s*\{""")

  private val STRING_LITERAL = Regex("""^"(?:[^"\\]|\\.)*"$""")

  /** A body that is nothing but `when (<subject>) { … }` — a shared component set's dispatch. */
  private data class LoneWhen(val subject: String, val entries: List<Pair<String, String>>)

  /**
   * [body] as a lone `when` dispatch, or null; anything after the `when` means it does more than
   * dispatch.
   */
  private fun loneWhen(body: String): LoneWhen? {
    val trimmed = body.trim()
    val head = WHEN_SUBJECT.find(trimmed) ?: return null
    val open = head.range.last
    val close = matchBrace(trimmed, open) ?: return null
    if (trimmed.substring(close + 1).isNotBlank()) return null
    return LoneWhen(head.groupValues[1], whenEntries(trimmed.substring(open + 1, close)))
  }

  /**
   * The branch of a component set's `when (id)` that [bound] selects, or null (non-literal subject,
   * or no branch and no `else`).
   */
  private fun dispatchBranch(dispatch: LoneWhen, bound: Map<String, String?>): String? {
    val key = bound[dispatch.subject]?.trim() ?: return null
    if (!STRING_LITERAL.matches(key)) return null
    val match =
      dispatch.entries.firstOrNull { (conditions, _) ->
        splitTopLevel(conditions).any { it.trim() == key }
      } ?: dispatch.entries.firstOrNull { it.first.trim() == "else" } ?: return null
    // Reindented, not trimmed (see above).
    val branch = reindent(match.second, 0)
    // A braced branch body contributes its statements, not its braces.
    return if (branch.startsWith("{") && matchBrace(branch, 0) == branch.length - 1)
      branch.substring(1, branch.length - 1)
    else branch
  }

  /**
   * The `<conditions> -> <body>` entries of a `when` body, as text.
   *
   * A braced branch ends at its matching brace. An unbraced one ends at the next line indented no
   * further than the entry itself — ktfmt's own rule for continuing an expression, and the only
   * signal available without a parse. Comments between entries are skipped, which the m3 component
   * set has plenty of.
   */
  private fun whenEntries(body: String): List<Pair<String, String>> {
    val mask = codeMask(body)
    val out = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < body.length) {
      i = skipTrivia(body, i)
      if (i >= body.length) break
      val conditionStart = i
      val entryIndent = conditionStart - (body.lastIndexOf('\n', conditionStart - 1) + 1)
      val arrow = topLevelArrow(body, mask, i) ?: break
      val conditions = body.substring(conditionStart, arrow)
      var j = arrow + 2
      while (j < body.length && body[j].isWhitespace()) j++
      if (j >= body.length) break
      // A branch on its own line keeps its line's indentation so the later dedent sees its column.
      if (body[j] != '{') {
        val lineStart = body.lastIndexOf('\n', j - 1)
        if (lineStart > arrow) j = lineStart + 1
      }
      val end =
        if (body[j] == '{') (matchBrace(body, j) ?: break) + 1
        else expressionEnd(body, mask, j, entryIndent)
      out.add(conditions to body.substring(j, end))
      i = end
    }
    return out
  }

  /** Advances past whitespace and comments. */
  private fun skipTrivia(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
      when {
        text[i].isWhitespace() -> i++
        text.startsWith("//", i) -> i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
        text.startsWith("/*", i) -> i = blockCommentEnd(text, i)
        else -> return i
      }
    }
    return i
  }

  /** The `->` separating an entry's conditions from its body — at depth 0, so no lambda's. */
  private fun topLevelArrow(text: String, mask: BooleanArray, from: Int): Int? {
    var depth = 0
    var i = from
    while (i < text.length - 1) {
      if (mask[i]) {
        when (text[i]) {
          '(',
          '[',
          '{' -> depth++
          ')',
          ']',
          '}' -> depth--
          '-' -> if (depth == 0 && text[i + 1] == '>') return i
        }
      }
      i++
    }
    return null
  }

  private fun expressionEnd(
    text: String,
    mask: BooleanArray,
    from: Int,
    entryIndent: Int,
  ): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      if (mask[i]) {
        when (text[i]) {
          '(',
          '[',
          '{' -> depth++
          ')',
          ']',
          '}' -> {
            depth--
            if (depth < 0) return i
          }
        }
      }
      if (text[i] == '\n' && depth == 0) {
        val next = nextNonBlankIndent(text, i + 1)
        if (next == null || next <= entryIndent) return i
      }
      i++
    }
    return text.length
  }

  private fun nextNonBlankIndent(text: String, from: Int): Int? {
    var j = from
    while (j < text.length) {
      val end = text.indexOf('\n', j).takeIf { it >= 0 } ?: text.length
      val line = text.substring(j, end)
      if (line.isNotBlank()) return line.takeWhile { it == ' ' }.length
      j = end + 1
    }
    return null
  }

  /**
   * `fun Name(<params>) = <expr>` / `{ <body> }` as parameters and body text; found at a code
   * position and line start so KDoc mentions don't match.
   */
  private fun parseFunction(text: String): FunctionDecl? {
    val mask = codeMask(text)
    val head = findFunctionHead(text, mask) ?: return null
    val open = head.range.last
    val close = matchParen(text, open) ?: return null
    val params =
      splitTopLevel(text.substring(open + 1, close))
        .mapNotNull { parseParam(it) }
        .ifEmpty { if (text.substring(open + 1, close).isBlank()) emptyList() else return null }
    var i = close + 1
    while (i < text.length && text[i].isWhitespace()) i++
    // Skip a declared return type: it may itself contain `->` and `<…>`, neither of which is the
    // body, so scan to the first `=` or `{` that is not inside one.
    if (i < text.length && text[i] == ':') {
      var depth = 0
      i++
      while (i < text.length) {
        if (mask[i]) {
          when (text[i]) {
            '(',
            '[',
            '<' -> depth++
            ')',
            ']',
            '>' -> depth--
            '{' -> if (depth <= 0) break
            '=' -> if (depth <= 0 && text.getOrNull(i + 1) != '=') break
          }
        }
        i++
      }
    }
    if (i >= text.length) return null
    return when (text[i]) {
      '=' -> FunctionDecl(params, text.substring(i + 1).trim())
      '{' -> {
        val end = matchBrace(text, i) ?: return null
        FunctionDecl(params, text.substring(i + 1, end))
      }
      else -> null
    }
  }

  /** `id: String`, `index: Int? = null`, `content: @Composable () -> Unit`. */
  private fun parseParam(text: String): Param? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val mask = codeMask(trimmed)
    val colon =
      trimmed.indices.firstOrNull { trimmed[it] == ':' && mask[it] && !inBrackets(trimmed, it) }
        ?: return null
    val name =
      Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*$""")
        .find(trimmed.substring(0, colon))
        ?.groupValues
        ?.get(1) ?: return null
    val rest = trimmed.substring(colon + 1)
    val restMask = codeMask(rest)
    val eq =
      rest.indices.firstOrNull {
        rest[it] == '=' &&
          restMask[it] &&
          rest.getOrNull(it + 1) != '=' &&
          rest.getOrNull(it - 1) !in listOf('=', '!', '<', '>', '-') &&
          !inBrackets(rest, it)
      }
    return Param(name, eq?.let { rest.substring(it + 1).trim() })
  }

  private fun inBrackets(text: String, at: Int): Boolean {
    val mask = codeMask(text)
    var depth = 0
    for (i in 0 until at) {
      if (!mask[i]) continue
      when (text[i]) {
        '(',
        '[',
        '<',
        '{' -> depth++
        ')',
        ']',
        '>',
        '}' -> depth--
      }
    }
    return depth > 0
  }

  /**
   * Put [expansion] where the call was. A one-line expansion replaces the call in place; a
   * multi-line one breaks the line around it (prefix, expansion indented one level, suffix), since
   * the call may sit between lambda braces on one line.
   */
  private fun spliceExpansion(text: String, call: Call, expansion: String): String {
    val after = call.argsEnd + 1
    if (!expansion.contains('\n')) {
      return text.substring(0, call.start) + expansion + text.substring(after)
    }
    val lineStart = text.lastIndexOf('\n', call.start - 1) + 1
    val lineEnd = text.indexOf('\n', after).takeIf { it >= 0 } ?: text.length
    val prefix = text.substring(lineStart, call.start)
    val suffix = text.substring(after, lineEnd)
    val indent = prefix.takeWhile { it == ' ' }.length
    val head = if (prefix.isBlank()) "" else prefix.trimEnd() + "\n"
    val bodyIndent = if (prefix.isBlank()) indent else indent + 2
    val tail = if (suffix.isBlank()) "" else "\n" + " ".repeat(indent) + suffix.trimStart()
    return text.substring(0, lineStart) +
      head +
      reindent(expansion, bodyIndent) +
      tail +
      text.substring(lineEnd)
  }

  /** Re-indents a lifted body to [column], preserving its own internal shape. */
  private fun reindent(text: String, column: Int): String {
    val lines = text.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    if (lines.isEmpty()) return ""
    val common =
      lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
        ?: 0
    val pad = " ".repeat(column)
    return lines.joinToString("\n") { if (it.isBlank()) "" else (pad + it.drop(common)).trimEnd() }
  }

  // Text mechanics, all masked against string and comment content so no pass rewrites inside a
  // literal.

  private const val MAX_REWRITES = 64

  private const val MATERIAL3_SYSTEM_THEME_CALL =
    "androidx.compose.material3.MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme())"

  private data class Call(val start: Int, val argsStart: Int, val argsEnd: Int)

  private data class Binding(val name: String, val arguments: List<String>, val lineRange: IntRange)

  /**
   * Marks each character as code (true) or string/char/comment content (false), including raw
   * strings.
   */
  internal fun codeMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length) { true }
    var i = 0
    while (i < text.length) {
      when {
        text.startsWith("//", i) -> {
          val end = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
          for (k in i until end) mask[k] = false
          i = end
        }
        text.startsWith("/*", i) -> {
          val end = blockCommentEnd(text, i)
          for (k in i until end) mask[k] = false
          i = end
        }
        text.startsWith("\"\"\"", i) -> {
          val end = (text.indexOf("\"\"\"", i + 3).takeIf { it >= 0 }?.plus(3)) ?: text.length
          for (k in i until end) mask[k] = false
          i = end
        }
        text[i] == '"' || text[i] == '\'' -> {
          val quote = text[i]
          var k = i + 1
          while (k < text.length && text[k] != quote) {
            if (text[k] == '\\') k++
            k++
          }
          val end = minOf(k + 1, text.length)
          for (j in i until end) mask[j] = false
          i = end
        }
        else -> i++
      }
    }
    return mask
  }

  /** Whether [text] contains a non-whitespace code character rather than only comments. */
  private fun containsCode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      when {
        text[i].isWhitespace() -> i++
        text.startsWith("//", i) -> i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
        text.startsWith("/*", i) -> i = blockCommentEnd(text, i)
        else -> return true
      }
    }
    return false
  }

  private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

  /** Kotlin block comments nest; return the terminator paired with the opener at [start]. */
  private fun blockCommentEnd(text: String, start: Int): Int {
    var depth = 1
    var i = start + 2
    while (i < text.length - 1) {
      when {
        text.startsWith("/*", i) -> {
          depth++
          i += 2
        }
        text.startsWith("*/", i) -> {
          depth--
          i += 2
          if (depth == 0) return i
        }
        else -> i++
      }
    }
    return text.length
  }

  /** Occurrences of [word] at code positions, bounded by non-identifier characters. */
  private fun wordOccurrences(text: String, word: String): List<Int> {
    if (word.isEmpty()) return emptyList()
    val mask = codeMask(text)
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
      val at = text.indexOf(word, from).takeIf { it >= 0 } ?: return out
      from = at + 1
      if (!mask[at]) continue
      val head = word.first()
      val before = text.getOrNull(at - 1)
      val after = text.getOrNull(at + word.length)
      val leftOk =
        if (isIdentifierChar(head)) before?.let { !isIdentifierChar(it) && it != '.' } ?: true
        else true
      val rightOk = after?.let { !isIdentifierChar(it) } ?: true
      if (leftOk && rightOk) out.add(at)
    }
  }

  internal fun mentionsWord(text: String, word: String): Boolean =
    wordOccurrences(text, word).isNotEmpty()

  /**
   * Whether [text] calls [name] as an extension (`receiver.name(`). Requires parentheses so a
   * property read on an unrelated receiver doesn't match.
   */
  internal fun mentionsExtensionCall(text: String, name: String): Boolean {
    val mask = codeMask(text)
    var from = 0
    while (true) {
      val at = text.indexOf(name, from).takeIf { it >= 0 } ?: return false
      from = at + 1
      if (!mask[at]) continue
      if (text.getOrNull(at - 1) != '.') continue
      // The character before the `.` has to end an expression, or this is a package qualifier.
      val beforeDot = text.getOrNull(at - 2)
      if (
        beforeDot != null && !isIdentifierChar(beforeDot) && beforeDot != ')' && beforeDot != ']'
      ) {
        continue
      }
      val gap = text.drop(at + name.length).takeWhile { it.isWhitespace() }
      if (text.getOrNull(at + name.length + gap.length) == '(') return true
    }
  }

  /**
   * Whether [text] still calls [name] through any qualifier: the residue half of
   * [unqualifyScaffoldCalls]. Can't tell packages from receivers, so it over-reports; a false
   * residue is a note, a false silence is a broken snippet marked runnable.
   */
  private fun mentionsQualifiedCall(text: String, name: String): Boolean {
    if (name.isEmpty()) return false
    val mask = codeMask(text)
    val qualified =
      Regex("""(?<![A-Za-z0-9_])(?:[A-Za-z_][A-Za-z0-9_]*\.)+${Regex.escape(name)}(?=\s*[({])""")
    return qualified.findAll(text).any { mask[it.range.first] }
  }

  /**
   * Whether [text] refers to [name] at all, including after a `.`, for deciding an import's fate.
   * [mentionsWord] rejects those, which pruned imports like `padding` and `dp` that Compose uses
   * via receiver syntax. Erring toward keeping costs only an unused import.
   */
  private fun mentionsIdentifier(text: String, name: String): Boolean {
    val mask = codeMask(text)
    var from = 0
    while (true) {
      val at = text.indexOf(name, from).takeIf { it >= 0 } ?: return false
      from = at + 1
      if (!mask[at]) continue
      val before = text.getOrNull(at - 1)
      val after = text.getOrNull(at + name.length)
      if (before?.let { isIdentifierChar(it) } == true) continue
      if (after?.let { isIdentifierChar(it) } == true) continue
      return true
    }
  }

  private fun replaceWord(text: String, word: String, replacement: String): String {
    val hits = wordOccurrences(text, word)
    if (hits.isEmpty()) return text
    val sb = StringBuilder()
    var last = 0
    for (at in hits) {
      sb.append(text, last, at).append(replacement)
      last = at + word.length
    }
    sb.append(text, last, text.length)
    return sb.toString()
  }

  private fun findCall(text: String, name: String): Call? = findCalls(text, name).firstOrNull()

  private fun findCalls(text: String, name: String): List<Call> =
    wordOccurrences(text, name).mapNotNull { at ->
      var k = at + name.length
      while (k < text.length && text[k].isWhitespace()) k++
      if (k < text.length && text[k] == '(') matchParen(text, k)?.let { Call(at, k, it) } else null
    }

  /**
   * `val <name> = <scaffold>(<args>)` — the binding, its arguments, and the lines it occupies, or
   * **null when the call is not bound to a `val` at all**.
   *
   * Reporting an unbound call as a nameless binding, as this first did, was a real bug and not a
   * tidy default. Both callers delete `lineRange`, and for a direct call that range is the whole
   * physical line the call sits on — so a ktfmt-legal one-liner `Button(onClick = {}, enabled =
   * catalogEnabled()) { … }` was deleted **whole** rather than merely losing its `enabled`
   * argument, and the cleaner then returned an empty themed preview with no residue to show for it.
   * It looked right on the fixture only because ktfmt had wrapped that call and put every knob on a
   * line of its own, where deleting the line happens to be the correct answer.
   *
   * An unbound call is not this function's business: [filterCallArguments] removes it where it sits
   * in a named argument, and the survivor check reports it where it does not.
   */
  private fun findValBinding(text: String, scaffold: String): Binding? {
    for (call in findCalls(text, scaffold)) {
      val lineStart = text.lastIndexOf('\n', call.start - 1) + 1
      val prefix = text.substring(lineStart, call.start)
      val name =
        Regex("""^\s*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""").find(prefix)?.groupValues?.get(1)
          ?: continue
      val args =
        splitTopLevel(text.substring(call.argsStart + 1, call.argsEnd)).map {
          it.trim(',', ' ', '\n')
        }
      val firstLine = text.substring(0, lineStart).count { it == '\n' }
      val lastLine = text.substring(0, call.argsEnd).count { it == '\n' }
      return Binding(name, args, firstLine..lastLine)
    }
    return null
  }

  private fun matchParen(text: String, open: Int): Int? = matchDelimiter(text, open, '(', ')')

  private fun matchBrace(text: String, open: Int): Int? = matchDelimiter(text, open, '{', '}')

  private fun matchDelimiter(text: String, open: Int, o: Char, c: Char): Int? {
    val mask = codeMask(text)
    var depth = 0
    for (i in open until text.length) {
      if (!mask[i]) continue
      if (text[i] == o) depth++
      if (text[i] == c) {
        depth--
        if (depth == 0) return i
      }
    }
    return null
  }

  /** Splits an argument list on top-level commas, keeping each argument's own text intact. */
  private fun splitTopLevel(args: String): List<String> {
    val mask = codeMask(args)
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in args.indices) {
      if (!mask[i]) continue
      when (args[i]) {
        '(',
        '[',
        '{' -> depth++
        ')',
        ']',
        '}' -> depth--
        ',' ->
          if (depth == 0) {
            out.add(args.substring(start, i))
            start = i + 1
          }
      }
    }
    if (start <= args.lastIndex) out.add(args.substring(start))
    return out.filter { it.isNotBlank() }
  }

  /**
   * Drop arguments matching [shouldDrop] from every call, collapsing a call left with one short
   * argument onto one line.
   */
  private fun filterCallArguments(text: String, shouldDrop: (String) -> Boolean): String {
    var out = text
    var searchFrom = 0
    var guard = 0
    while (guard++ < MAX_REWRITES * 4) {
      val mask = codeMask(out)
      val open = (searchFrom until out.length).firstOrNull { out[it] == '(' && mask[it] } ?: break
      val close = matchParen(out, open)
      if (close == null) {
        searchFrom = open + 1
        continue
      }
      val inner = out.substring(open + 1, close)
      val args = splitTopLevel(inner)
      val keep = args.filterNot { shouldDrop(it) }
      if (keep.size == args.size) {
        searchFrom = open + 1
        continue
      }
      val rendered =
        when {
          keep.isEmpty() -> ""
          // Trim first: a surviving argument still carries the newline and indent of its wrapped
          // call.
          keep.size == 1 && !keep[0].trim().contains('\n') -> keep[0].trim()
          else -> keep.joinToString(",") + ","
        }
      out = out.substring(0, open + 1) + rendered + out.substring(close)
      searchFrom = open + 1
    }
    return out
  }

  private fun indentOf(text: String, at: Int): Int {
    val lineStart = text.lastIndexOf('\n', at - 1) + 1
    return text.substring(lineStart, at).takeWhile { it == ' ' }.length
  }

  private fun removeLines(text: String, range: IntRange): String {
    val lines = text.lines()
    return lines.filterIndexed { i, _ -> i !in range }.joinToString("\n")
  }
}
