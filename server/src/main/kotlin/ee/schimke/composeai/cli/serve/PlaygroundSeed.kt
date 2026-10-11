package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.UsageRules.Companion.appliesToModule
import ee.schimke.composeai.cli.serve.UsageRules.Companion.declaresCatalogScaffolds
import java.util.concurrent.ConcurrentHashMap

/**
 * A served preview's Kotlin, staged as the playground editor's opening buffer
 * (`/playground?from=<system>/<previewId>`), with its catalog preselected.
 *
 * Ready to compile is a starting point, not a promise: unresolvable references come back as
 * ordinary diagnostics against the right classpath, so the seed is never rewritten into something
 * that would no longer be the preview's source. With a recorded anchor (`PreviewInfo.bodyLine`) the
 * seed is the file header plus that one declaration, verbatim; without one, the whole file.
 */
data class PlaygroundSeed(
  /** The catalog to preselect — the system the preview belongs to. */
  val catalog: String,
  /** Owning Gradle module, used to select the matching compile bundle in a multi-module catalog. */
  val sourceModule: String? = null,
  /** The preview this came from, for the note the editor shows. */
  val previewId: String,
  /** Editor tab name, from the source path's basename (`FilledButton.kt`). */
  val fileName: String,
  /** The seeded Kotlin: the whole file, or its header plus one declaration when [sliced]. */
  val text: String,
  /** Where it was read from, so the note can link back to the human-readable blob. */
  val blobUrl: String?,
  /**
   * True when [text] is one declaration rather than the whole file, so the editor's note says
   * which.
   */
  val sliced: Boolean = false,
  /**
   * True when [text] was rewritten into plain Compose by [PlaygroundSourceCleaner] (usage code,
   * ready to Run) rather than carried verbatim.
   */
  val cleaned: Boolean = false,
  /**
   * Declared scaffolding that survived cleaning ([PlaygroundSourceCleaner.Result.residue]);
   * non-empty means partly cleaned, and the note says so.
   */
  val residue: List<String> = emptyList(),
  /**
   * True when the catalog declared its helpers (a `compose-usage.json` with scaffold rules) rather
   * than getting [UsageRules.GENERIC]. Under generic rules only shared annotations come off and
   * catalog helpers remain (not [residue], since nothing was declared), so the note must not claim
   * they are gone.
   */
  val scaffoldsDeclared: Boolean = false,
)

/**
 * Resolves `(system, previewId)` to a [PlaygroundSeed] by reading the preview's source file from
 * GitHub.
 *
 * Safe on a public host: the fetch URL is built only from the catalog's trusted metadata via this
 * server's registry, never from the client. Results are cached by resolved location (so a
 * republished or moved catalog misses by construction) with a [ttlSeconds] deadline (a branch `ref`
 * is stable while its files aren't). Bounded at [maxEntries]: new entries stop being accepted
 * rather than evicting, and expired ones are swept when full.
 */
class PlaygroundSeedResolver(
  /** Where a preview's source lives, or null when this server can't say. */
  private val locate: (system: String, previewId: String) -> Location?,
  /** Fetches a URL, returning its bytes or null. Injected so tests never touch the network. */
  private val fetch: (String) -> ByteArray?,
  /** Source files are code; anything larger than this is not a preview file worth seeding from. */
  private val maxBytes: Int = DEFAULT_MAX_BYTES,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
  /** How long a cached seed may be served before it is re-read; see the class KDoc. */
  private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) {

  /** A preview's source location, entirely from catalog metadata. */
  data class Location(
    val repo: String,
    val ref: String,
    val module: String?,
    /** Module-relative path, as discovery recorded it. */
    val sourceFile: String,
    /**
     * 1-based line inside the preview body from discovery, the anchor [sliceDeclaration] expands
     * from; null means the whole file. Part of the cache key, so moved declarations don't reuse an
     * old offset.
     */
    val bodyLine: Int? = null,
  )

  /**
   * The cache key: request identity plus resolved location. A data class rather than a joined
   * string, since path components may contain any separator and joined strings could collide.
   */
  private data class CacheKey(val system: String, val previewId: String, val where: Location)

  private class Entry(val seed: PlaygroundSeed, val readAtMillis: Long)

  private val cache = ConcurrentHashMap<CacheKey, Entry>()

  /**
   * One monitor per in-flight key, so a cold key is fetched once however many callers arrive
   * together (the viewer's Source panel can bring a dozen at once after a restart).
   */
  private val inFlight = ConcurrentHashMap<CacheKey, Flight>()

  /**
   * One resolution attempt carrying its outcome. Failed fetches aren't cached and successes may be
   * dropped at [maxEntries], so waiters signalled only through the cache would each repeat the
   * GitHub round trip.
   */
  private class Flight {
    var done = false
    var seed: PlaygroundSeed? = null
  }

  fun seed(system: String, previewId: String): PlaygroundSeed? {
    // Resolve the location first (an in-memory read) and key on it, so a refreshed catalog misses
    // by construction.
    val where =
      locate(system, previewId)
        ?: run {
          onLog("no source path recorded for $system/$previewId; playground seed unavailable")
          return null
        }
    val key = CacheKey(system, previewId, where)
    cachedSeed(key)?.let {
      return it
    }
    // Single-flight: the first caller fetches, the rest wait on its monitor; re-checked inside the
    // lock since waiters arrive after the result is stored.
    val flight = inFlight.computeIfAbsent(key) { Flight() }
    try {
      synchronized(flight) {
        // The leader's answer, whatever it was — including "no seed", which is a result and not a
        // reason to try again.
        if (flight.done) return flight.seed
        val seed = cachedSeed(key) ?: fetchSeed(system, previewId, where, key)
        flight.seed = seed
        flight.done = true
        return seed
      }
    } finally {
      // Removed by whoever leaves first; remaining waiters read the recorded outcome, later
      // arrivals start a fresh flight.
      inFlight.remove(key, flight)
    }
  }

  private fun cachedSeed(key: CacheKey): PlaygroundSeed? {
    val now = clock()
    return cache[key]?.takeIf { now - it.readAtMillis < ttlSeconds * 1000 }?.seed
  }

  private fun fetchSeed(
    system: String,
    previewId: String,
    where: Location,
    key: CacheKey,
  ): PlaygroundSeed? {
    val now = clock()
    val rawUrl =
      ServeUrls.githubRawUrl(where.repo, where.ref, where.module, where.sourceFile)
        ?: run {
          onLog("could not build a source URL for $system/$previewId")
          return null
        }
    val bytes =
      try {
        fetch(rawUrl)
      } catch (e: Exception) {
        onLog("fetching $rawUrl failed (${e.message})")
        null
      }
    if (bytes == null) {
      onLog("could not read $rawUrl; playground seed unavailable for $system/$previewId")
      return null
    }
    if (bytes.size > maxBytes) {
      onLog("$rawUrl is ${bytes.size} bytes, over the ${maxBytes}-byte seed cap")
      return null
    }
    val text = bytes.decodeToString()
    // A file that decodes to replacement characters isn't Kotlin the editor can usefully open —
    // better no seed (and the sample) than a buffer full of U+FFFD.
    if (text.contains('�')) {
      onLog("$rawUrl is not valid UTF-8; playground seed unavailable")
      return null
    }
    // Cleaning first, slicing as fallback: the cleaner does its own slicing (it needs the same-file
    // helpers a slice would cut). Gated on the anchor, so rules aren't fetched for catalogs
    // predating `bodyLine`.
    val cleaned =
      try {
        if (where.bodyLine == null) null
        else {
          val rules = rulesFor(where)
          PlaygroundSourceCleaner.clean(
            source = text,
            bodyLine = where.bodyLine,
            rules = rules,
            strings = stringsFor(where, rules),
            helperSources = helperSourcesFor(where, rules),
            followedSources = followedSourcesFor(where, text, rules),
          )
        }
      } catch (e: Exception) {
        // A seed is a convenience. A cleaner bug must degrade to the verbatim slice that worked
        // before it existed, never take the playground handoff down with it.
        onLog("cleaning $system/$previewId failed (${e.message}); seeding the verbatim slice")
        null
      }
    val sliced = sliceDeclaration(text, where.bodyLine)
    val seed =
      PlaygroundSeed(
        catalog = system,
        sourceModule = where.module,
        previewId = previewId,
        fileName = fileNameFor(where.sourceFile),
        text = cleaned?.text ?: sliced ?: text,
        blobUrl = ServeUrls.githubBlobUrl(where.repo, where.ref, where.module, where.sourceFile),
        sliced = cleaned != null || sliced != null,
        cleaned = cleaned != null,
        residue = cleaned?.residue.orEmpty(),
        scaffoldsDeclared = cleaned != null && rulesFor(where).declaresCatalogScaffolds(),
      )
    // Bounded, not LRU: entries are small and a catalog's previews are finite. A full cache first
    // drops expired entries.
    if (cache.size >= maxEntries) {
      cache.entries.removeIf { now - it.value.readAtMillis >= ttlSeconds * 1000 }
    }
    if (cache.size < maxEntries) cache[key] = Entry(seed, now)
    return seed
  }

  /**
   * The catalog's own [UsageRules] from `compose-usage.json` at the repo root, at the catalog's
   * published `ref` so rules and source match. Cached per `(repo, ref)`, including absence (as
   * [UsageRules.GENERIC]).
   */
  private val rulesCache = ConcurrentHashMap<Pair<String, String>, Pair<UsageRules, Long>>()

  private val stringsCache =
    ConcurrentHashMap<Pair<String, String>, Pair<Map<String, String>, Long>>()

  private val helperCache = ConcurrentHashMap<Pair<String, String>, Pair<List<String>, Long>>()

  private fun rulesFor(where: Location): UsageRules {
    val key = where.repo to where.ref
    val now = clock()
    rulesCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first.takeIf { rules -> rules.appliesToModule(where.module) }
          ?: UsageRules.GENERIC
      }
    val url = ServeUrls.githubRawUrl(where.repo, where.ref, null, USAGE_RULES_FILE)
    val rules =
      url
        ?.let { u ->
          try {
            fetch(u)
          } catch (_: Exception) {
            null
          }
        }
        ?.takeIf { it.size <= maxBytes }
        ?.decodeToString()
        ?.let { UsageRules.parse(it, onLog) } ?: UsageRules.GENERIC
    // Bounded and swept like the seed cache: a TTL alone never removes keys, so historical refs
    // would accumulate.
    evictExpired(rulesCache, now)
    if (rulesCache.size < maxEntries) rulesCache[key] = rules to now
    // Cached by `(repo, ref)` because that is what was FETCHED; scoped by module on the way out,
    // because a repo can publish several catalogs from one rules file. See [UsageRules.modules].
    return rules.takeIf { it.appliesToModule(where.module) } ?: UsageRules.GENERIC
  }

  /**
   * The catalog's English string resources, so `stringResource(Res.string.x)` can be inlined. A
   * narrow regex rather than an XML parser: unrecognised entries simply stay as lookups.
   */
  private fun stringsFor(where: Location, rules: UsageRules): Map<String, String> {
    val path = rules.stringsPath?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val key = where.repo to "${where.ref}:${where.module}:$path"
    val now = clock()
    stringsCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first
      }
    // A leading `/` means repo root (for a shared component module's resources); existing
    // module-relative paths are unaffected.
    val url =
      if (path.startsWith("/")) ServeUrls.githubRawUrl(where.repo, where.ref, null, path)
      else ServeUrls.githubRawUrl(where.repo, where.ref, where.module, path)
    val text =
      url
        ?.let { u ->
          try {
            fetch(u)
          } catch (_: Exception) {
            null
          }
        }
        ?.takeIf { it.size <= maxBytes }
        ?.decodeToString()
    val strings =
      if (text == null) emptyMap()
      else
        STRING_RESOURCE.findAll(text).associate {
          it.groupValues[1] to unescapeAndroidString(it.groupValues[2])
        }
    evictExpired(stringsCache, now)
    if (stringsCache.size < maxEntries) stringsCache[key] = strings to now
    return strings
  }

  /**
   * The catalog's scaffold sources ([UsageRules.scaffoldSources]), repo-root-relative at the
   * preview's `ref`. Cached per `(repo, ref)` and capped at [MAX_SCAFFOLD_SOURCES].
   */
  private fun helperSourcesFor(where: Location, rules: UsageRules): List<String> {
    val paths = rules.scaffoldSources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (paths.isEmpty()) return emptyList()
    if (paths.size > MAX_SCAFFOLD_SOURCES) {
      onLog(
        "compose-usage.json names ${paths.size} scaffold sources; reading the first $MAX_SCAFFOLD_SOURCES"
      )
    }
    val wanted = paths.take(MAX_SCAFFOLD_SOURCES)
    val key = where.repo to "${where.ref}:${wanted.joinToString("|")}"
    val now = clock()
    helperCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first
      }
    val texts = wanted.mapNotNull { path ->
      val url = ServeUrls.githubRawUrl(where.repo, where.ref, null, path) ?: return@mapNotNull null
      val bytes =
        try {
          fetch(url)
        } catch (e: Exception) {
          onLog("fetching scaffold source $url failed (${e.message})")
          null
        }
      if (bytes == null) {
        onLog("could not read scaffold source $url")
        return@mapNotNull null
      }
      if (bytes.size > maxBytes) {
        onLog("$url is ${bytes.size} bytes, over the ${maxBytes}-byte cap")
        return@mapNotNull null
      }
      bytes.decodeToString().takeIf { !it.contains('\uFFFD') }
    }
    evictExpired(helperCache, now)
    if (helperCache.size < maxEntries) helperCache[key] = texts to now
    return texts
  }

  private val followedCache = ConcurrentHashMap<Pair<String, String>, Pair<String?, Long>>()

  /**
   * Files behind the imported functions the preview calls, one level down, for previews that only
   * delegate (`SampleScreen { ChoicePickerSample(…) }`). Reads `<root>/<package path>/<Name>.kt` at
   * the same `ref` under the file's implied root and [UsageRules.sourceRoots]. Misses (library
   * imports, differently named files) are cached like hits.
   */
  private fun followedSourcesFor(where: Location, text: String, rules: UsageRules): List<String> {
    val candidates =
      followedCallPaths(text, where.bodyLine, where.sourceFile, rules.sourceRoots)
        .take(MAX_FOLLOWED_PATHS)
    if (candidates.isEmpty()) return emptyList()
    val now = clock()
    return candidates
      .groupBy({ it.first }, { it.second })
      .values
      .mapNotNull { paths ->
        // The first root that has the file wins; the rest are not asked for.
        paths.firstNotNullOfOrNull { path -> followedSource(where, path, now) }
      }
      .take(MAX_FOLLOWED_FILES)
  }

  private fun followedSource(where: Location, path: String, now: Long): String? {
    val key = where.repo to "${where.ref}:${where.module.orEmpty()}:$path"
    followedCache[key]
      ?.takeIf { now - it.second < ttlSeconds * 1000 }
      ?.let {
        return it.first
      }
    val url = ServeUrls.githubRawUrl(where.repo, where.ref, where.module, path)
    val text =
      url
        ?.let { u ->
          try {
            fetch(u)
          } catch (_: Exception) {
            null
          }
        }
        ?.takeIf { it.size <= maxBytes }
        ?.decodeToString()
        ?.takeIf { !it.contains('\uFFFD') }
    evictExpired(followedCache, now)
    if (followedCache.size < maxEntries) followedCache[key] = text to now
    return text
  }

  /** Drops every entry past its TTL. Called before an insert, so the caps stay reachable. */
  private fun <K, V> evictExpired(cache: ConcurrentHashMap<K, Pair<V, Long>>, now: Long) {
    cache.entries.removeIf { now - it.value.second >= ttlSeconds * 1000 }
  }

  companion object {
    /** Where a catalog declares its scaffolding: repo root, beside `catalog.spec.json`. */
    const val USAGE_RULES_FILE = "compose-usage.json"

    private val STRING_RESOURCE =
      Regex("""<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    /** Android/CMP resource escapes a label can carry. Unknown entities are left as written. */
    internal fun unescapeAndroidString(raw: String): String =
      raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .trim()

    /** Scaffold sources one catalog may have read; bounds a mistaken rules file. */
    const val MAX_SCAFFOLD_SOURCES = 12

    /** Imported calls followed out of one preview: a delegating preview calls one or two. */
    const val MAX_FOLLOWED_FILES = 3

    /** Candidate paths tried for them, across every root. Bounds the misses a page can cost. */
    const val MAX_FOLLOWED_PATHS = 8

    /**
     * `(importedName, modulePath)` candidates for imported functions the declaration at [bodyLine]
     * calls, in call order. A call is a capitalised imported name followed by `(` or `{`; same-file
     * names and aliased imports are skipped.
     */
    internal fun followedCallPaths(
      text: String,
      bodyLine: Int?,
      sourceFile: String,
      extraRoots: List<String>,
    ): List<Pair<String, String>> {
      val lines = text.lines()
      // The anchored declaration's own lines, not [sliceDeclaration]: that one declines a file that
      // is nothing but the declaration, which is exactly the one-preview file this is for.
      val entry =
        declarationLines(lines, bodyLine)
          ?.let { lines.subList(it.first, it.last + 1) }
          ?.joinToString("\n") ?: return emptyList()
      val pkg =
        lines
          .firstOrNull { it.startsWith("package ") }
          ?.removePrefix("package ")
          ?.trim()
          ?.removeSuffix(";")
          .orEmpty()
      val imports =
        lines
          .asSequence()
          .filter { it.startsWith("import ") && " as " !in it }
          .map { it.removePrefix("import ").trim().removeSuffix(";") }
          .filter { !it.endsWith(".*") && it.contains('.') }
          .associateBy { it.substringAfterLast('.') }
      val declared =
        Regex(
            """^(?:[a-z]+\s+)*(?:fun|val|var|class|object|interface)\s+(\w+)""",
            RegexOption.MULTILINE,
          )
          .findAll(text)
          .map { it.groupValues[1] }
          .toSet()
      val called =
        Regex("""(?<![.\w@])([A-Z]\w*)\s*[({]""")
          .findAll(entry)
          .map { it.groupValues[1] }
          .filter { it in imports && it !in declared }
          .distinct()
          .toList()
      if (called.isEmpty()) return emptyList()
      val dir = sourceFile.substringBeforeLast('/', "")
      val pkgPath = pkg.replace('.', '/')
      val impliedRoot =
        when {
          pkgPath.isEmpty() -> dir
          dir == pkgPath -> ""
          dir.endsWith("/$pkgPath") -> dir.removeSuffix("/$pkgPath")
          else -> null
        }
      val roots =
        (listOfNotNull(impliedRoot) + extraRoots.map { it.trim().trim('/') }).distinct().filter {
          !it.split('/').contains("..")
        }
      return called.flatMap { name ->
        val path = imports.getValue(name).substringBeforeLast('.').replace('.', '/') + "/$name.kt"
        roots.map { root -> name to (if (root.isEmpty()) path else "$root/$path") }
      }
    }

    /** A preview source file. Well above any real one, well below "somebody linked a blob". */
    const val DEFAULT_MAX_BYTES = 256 * 1024

    /** Cached seeds. A large catalog set is a few hundred previews; this holds the popular ones. */
    const val DEFAULT_MAX_ENTRIES = 256

    /**
     * How long a cached seed is served before re-reading; matched to the catalog refresh interval,
     * bounding how far the handoff can lag a moved branch.
     */
    const val DEFAULT_TTL_SECONDS = 600L

    /**
     * Editor tab name for a source path, sanitised like [PlaygroundCompileService.safeKtName] since
     * the seed goes through the same request shape as a typed file.
     */
    internal fun fileNameFor(sourceFile: String): String =
      PlaygroundCompileService.safeKtName(sourceFile.replace('\\', '/').substringAfterLast('/'))

    /**
     * Narrows [text] to the file's **header plus the one declaration** [bodyLine] falls inside, or
     * null when it can't be done and the caller should seed the whole file.
     *
     * ### Why the header is kept whole
     *
     * "Header" is everything above the first top-level declaration: the `package` line, any
     * `@file:` annotations, and the imports. It is carried verbatim rather than pruned to the
     * imports this one declaration happens to use, because deciding that needs a Kotlin parser and
     * getting it wrong turns a working buffer into a wall of unresolved references. An unused
     * import costs a visitor nothing; a missing one costs them the compile. `@file:OptIn(...)` is
     * in there too, which a body using an experimental API genuinely needs.
     *
     * ### How the declaration's bounds are found
     *
     * From **one** anchor, expanded to the enclosing *top-level declaration*.
     *
     * A span would be the obvious input, and the classfile appears to offer one — but its upper
     * bound is fiction on Kotlin whenever the method inlines anything (see `PreviewInfo.bodyLine`),
     * and a wrong end here silently cuts into the next declaration. One line known to be *inside*
     * the body is enough, because the boundaries are findable from the source.
     *
     * A boundary is [startsTopLevelDeclaration]: a non-blank line at **column 0** whose predecessor
     * is **blank**. The declaration containing the anchor runs from the nearest such line at or
     * above it, to the last non-blank line before the next one.
     *
     * Both halves of that test earn their place, and the first version of this had only one of them
     * — "walk outwards over non-blank lines" — which was wrong in a way worth recording. A blank
     * line *inside* a body is ordinary formatted Kotlin, not an oddity (`OverridablePreviews`
     * separates its `previewOverride*` declarations from the `Surface` they feed), and treating it
     * as the end truncated the declaration mid-body, closing braces and all. Requiring column 0
     * *and* a preceding blank is what tells a separator from a breath inside a body: an internal
     * blank line is followed by indented code, and a top-level closing brace sits at column 0 but
     * is not preceded by a blank.
     *
     * A brace-counting scan would be more general still, but it has to model strings, char
     * literals, comments and nested lambdas to not go wrong, and going wrong means silently
     * truncating somebody's code mid-expression. This rule fails in the safer direction: on source
     * that puts no blank line between two declarations it over-selects, taking both — a bigger
     * buffer rather than a broken one.
     *
     * ktfmt (Google style, which every catalog in this repo is formatted with) guarantees the
     * separating blank line, and never puts one inside an annotation stack or between KDoc and what
     * it documents — so the annotations and the KDoc come along for free rather than needing a
     * doc-comment-matching special case.
     *
     * Returns null — meaning "seed the whole file" — when there is no anchor, when the anchor does
     * not fall inside the text (the file moved under a branch `ref` since discovery ran), or when
     * the slice would be the whole file anyway.
     */
    internal fun sliceDeclaration(text: String, bodyLine: Int?): String? {
      val lines = text.lines()
      val bounds = declarationLines(lines, bodyLine) ?: return null
      val start = bounds.first
      val end = bounds.last

      val headerEnd = headerEndExclusive(lines)
      if (headerEnd == 0 && start == 0 && end == lines.lastIndex) return null

      val header = lines.subList(0, headerEnd).joinToString("\n").trimEnd()
      val declaration = lines.subList(start, end + 1).joinToString("\n")
      val slice = if (header.isEmpty()) declaration else "$header\n\n$declaration"
      return slice.takeIf { it.trimEnd() != text.trimEnd() }
    }

    /**
     * 0-based inclusive line range of the top-level declaration containing [bodyLine], or null when
     * the anchor is unusable (absent, out of range, on a blank line, or in the header). Shared by
     * [sliceDeclaration] and [PreviewUsageIndex] so both agree where declarations end. Null never
     * means "whole file"; that is [sliceDeclaration]'s own fallback.
     */
    internal fun declarationLines(lines: List<String>, bodyLine: Int?): IntRange? {
      if (bodyLine == null) return null
      if (bodyLine < 1 || bodyLine > lines.size) return null
      if (lines[bodyLine - 1].isBlank()) return null

      // Up to the declaration this anchor sits in…
      var start = bodyLine - 1 // to 0-based
      while (start > 0 && !startsTopLevelDeclaration(lines, start)) start--
      // …and down to the last non-blank line before the next declaration begins.
      var next = start + 1
      while (next <= lines.lastIndex && !startsTopLevelDeclaration(lines, next)) next++
      var end = next - 1
      while (end > start && lines[end].isBlank()) end--

      // The declaration starting at or inside the header means the scan escaped upwards past the
      // imports — unusual formatting, and re-emitting the header would then duplicate lines.
      if (start < headerEndExclusive(lines)) return null
      return start..end
    }

    /**
     * Whether `lines[i]` starts a top-level declaration: non-blank at column 0 and preceded by a
     * blank line (or file start). Column 0 alone matches a closing brace; a blank alone matches
     * indented statements in a body.
     */
    private fun startsTopLevelDeclaration(lines: List<String>, i: Int): Boolean {
      val line = lines[i]
      if (line.isBlank()) return false
      if (line.first().isWhitespace()) return false
      return i == 0 || lines[i - 1].isBlank()
    }

    /**
     * Index of the first non-header line, anchored on the last import, then `package`, rather than
     * the first declaration-looking line (comments may mention `fun`). A file with neither has no
     * header.
     */
    private fun headerEndExclusive(lines: List<String>): Int {
      val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
      if (lastImport >= 0) return lastImport + 1
      val packageLine = lines.indexOfLast { it.trimStart().startsWith("package ") }
      return if (packageLine >= 0) packageLine + 1 else 0
    }

    private val httpClient: okhttp3.OkHttpClient by lazy {
      okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // Shorter than the catalog store's 30 s: this one is on a page-load path, and a slow
        // GitHub is better answered by opening the sample than by holding the request open.
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    }

    /** The production fetcher: one capped GET, fail-soft on anything non-2xx. */
    fun httpFetch(url: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray? =
      try {
        httpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) null else response.body.byteStream().readNBytes(maxBytes + 1)
        }
      } catch (_: Exception) {
        null
      }
  }
}
