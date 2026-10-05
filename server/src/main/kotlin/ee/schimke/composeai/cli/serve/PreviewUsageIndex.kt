package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Which functions each preview's own declaration **calls**, so the landing grid's filter can answer
 * "show me the previews that call `SwipeToReveal`" — the cards a name search cannot find.
 *
 * Reuses the playground's Source-panel inputs: [PlaygroundSeedResolver.Location] for where each
 * preview's source lives and [UsageSourceParser] for the parse, with one fetch per distinct file
 * split among its previews by [PlaygroundSeedResolver.declarationLines].
 *
 * Callees are recorded as written, with no symbol resolution and no expansion through delegation (a
 * `Sticker("<slug>")` catalog indexes as calling `Sticker`). Every stage may be absent (no parser
 * sidecar, no source metadata, a failed fetch), and [Match.available] distinguishes "nothing
 * indexed" from "no preview calls that".
 */
class PreviewUsageIndex(
  /** Where a preview's source lives, or null when this server can't say. */
  private val locate: (system: String, previewId: String) -> PlaygroundSeedResolver.Location?,
  /** Fetches a URL, returning its bytes or null. Injected so tests never touch the network. */
  private val fetch: (String) -> ByteArray?,
  /**
   * The Kotlin parser, or null when its sidecar is not staged. A function rather than an instance
   * so the (one-off, ~0.5 s) classloader build is not paid by a server that never indexes.
   */
  private val parser: () -> UsageSourceParser? = { UsageSourceParser.of() },
  /** Source files are code; anything larger than this is not a preview file worth indexing. */
  private val maxBytes: Int = DEFAULT_MAX_BYTES,
  /**
   * A ceiling on the distinct files one index build will fetch. A catalog's previews cluster into a
   * few dozen section files, so this is a runaway guard rather than a working limit — and it is
   * reported ([Index.truncated]) rather than silently applied, because a partial index that looks
   * complete answers "nothing calls that" for a file it never read.
   */
  private val maxFiles: Int = DEFAULT_MAX_FILES,
  /** How long a built index may be served before it is rebuilt. */
  private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) {

  /** One catalog's previews, each with the set of names its declaration calls. */
  data class Index(
    val calleesById: Map<String, Set<String>>,
    /** False when nothing could be indexed at all — no parser, or no source metadata. */
    val available: Boolean,
    /** Whether [maxFiles] cut the build short, so absence is not evidence of absence. */
    val truncated: Boolean = false,
  )

  /** The answer to one query. [available] is [Index.available], carried through to the caller. */
  data class Match(val ids: Set<String>, val available: Boolean, val truncated: Boolean = false)

  private class Entry(val index: Index, val signature: Int, val builtAtMillis: Long)

  private val cache = ConcurrentHashMap<String, Entry>()

  /**
   * One lock per catalog, so a build never blocks a search of a DIFFERENT catalog.
   *
   * The obvious `@Synchronized` on [match] was wrong in a way worth recording: building an index is
   * up to [maxFiles] network reads, and holding one process-wide monitor across them meant a single
   * cold catalog could park every other `uses:` request behind it — on a host whose request threads
   * are shared with the routes that serve renders. Per-catalog locks keep the one property actually
   * wanted (two concurrent searches of the same cold catalog do one build, not two) and drop the
   * one that was accidental.
   */
  private val locks = ConcurrentHashMap<String, Any>()

  /**
   * The previews among [previewIds] whose declaration calls something matching [token].
   *
   * Matching is a **case-insensitive substring** of the callee's name as written, so `button` finds
   * `Button`, `FilledIconButton` and `ButtonGroup` alike. A filter box is a place to narrow by
   * half-remembered names; an exact-match operator would need the reader to already know the
   * answer.
   */
  fun match(system: String, previewIds: List<String>, token: String): Match {
    val index = index(system, previewIds)
    val needle = token.trim().lowercase()
    if (needle.isEmpty()) {
      return Match(ids = emptySet(), available = index.available, truncated = index.truncated)
    }
    val ids =
      index.calleesById
        .filterValues { callees -> callees.any { it.lowercase().contains(needle) } }
        .keys
    return Match(ids = ids, available = index.available, truncated = index.truncated)
  }

  /**
   * This catalog's index, built or reused.
   *
   * Keyed by system and *validated* against the preview list, not keyed by it: a catalog
   * republished under the same id with previews added or dropped must rebuild rather than answer
   * from the previous publication, and a hash of the ids is what notices that without holding the
   * list.
   */
  private fun index(system: String, previewIds: List<String>): Index {
    val signature = previewIds.sorted().hashCode()
    fresh(system, signature)?.let {
      return it
    }
    // Re-checked inside the lock: several requests can pass the read above together, and without
    // the second look each of them would rebuild what the first has just finished.
    synchronized(locks.computeIfAbsent(system) { Any() }) {
      fresh(system, signature)?.let {
        return it
      }
      val built = build(system, previewIds)
      cache[system] = Entry(built, signature, clock())
      return built
    }
  }

  /** The cached index for [system], if it is still current for [signature] and inside its TTL. */
  private fun fresh(system: String, signature: Int): Index? =
    cache[system]
      ?.takeIf { it.signature == signature && clock() - it.builtAtMillis < ttlSeconds * 1000 }
      ?.index

  private fun build(system: String, previewIds: List<String>): Index {
    // Grouped by file, because that is the unit of both the fetch and the parse. Previews that
    // carry no location (an uploaded bundle, or a manifest predating `sourceFile`) drop out here
    // and are simply not in the index — they match nothing, and `available` still describes whether
    // the *catalog* could be indexed.
    val byFile = LinkedHashMap<FileKey, MutableList<Pair<String, Int?>>>()
    for (id in previewIds) {
      val where = locate(system, id) ?: continue
      val key = FileKey(where.repo, where.ref, where.module, where.sourceFile)
      byFile.getOrPut(key) { mutableListOf() }.add(id to where.bodyLine)
    }
    if (byFile.isEmpty()) {
      onLog("$system carries no preview source locations; nothing to index")
      return Index(calleesById = emptyMap(), available = false)
    }
    val parse = parser()
    if (parse == null) {
      onLog("usage-source-psi not staged; $system cannot be indexed by call")
      return Index(calleesById = emptyMap(), available = false)
    }
    val truncated = byFile.size > maxFiles
    if (truncated) {
      onLog("$system spans ${byFile.size} source files; indexing the first $maxFiles")
    }
    val calleesById = HashMap<String, Set<String>>()
    for ((key, previews) in byFile.entries.take(maxFiles)) {
      val text = read(key) ?: continue
      val facts = parse.facts(text)
      if (facts == null) {
        onLog("could not parse ${key.sourceFile} for $system")
        continue
      }
      val lines = text.lines()
      val lineStarts = lineStartOffsets(lines)
      for ((previewId, bodyLine) in previews) {
        val (from, to) = declarationSpan(facts, lines, lineStarts, bodyLine) ?: continue
        val callees =
          facts.calls
            .asSequence()
            .filter { it.start in from until to }
            .map { it.callee }
            .filter { it.isNotBlank() }
            .toSet()
        // Every image of one component shares a source file and a body line, so several ids can
        // land on the same declaration. Each gets its own entry: the grid filters by id.
        calleesById[previewId] = callees
      }
    }
    return Index(calleesById = calleesById, available = true, truncated = truncated)
  }

  /**
   * The character range of the declaration [bodyLine] falls in, half-open, or null when it cannot
   * be established.
   *
   * **The parse is the authority, and the line scan is only a fallback.**
   * [PlaygroundSeedResolver.declarationLines] finds a declaration's bounds from formatting — a
   * non-blank line at column 0 preceded by a blank one — and where two top-level declarations sit
   * with no blank line between them it deliberately over-selects, taking both. That is the safe
   * failure for its own caller, which seeds an editor buffer and would rather hand over too much
   * than truncate someone's code mid-expression. It is the *unsafe* failure here: a merged range
   * gives each of those previews the other's calls, so `uses:Button` answers with a preview that
   * never calls one — a wrong answer, delivered confidently, which is worse than no answer.
   *
   * So the real declaration list from the parse decides whenever it can. The line scan stays for
   * the one case it cannot: an analyzer predating the `declarations` field, which reports none.
   * ktfmt (Google style) guarantees the blank line, so that fallback is right about every catalog
   * in this repository — it just cannot be right about every catalog anywhere, which is the gap the
   * parse closes.
   */
  private fun declarationSpan(
    facts: UsageSourceFacts,
    lines: List<String>,
    lineStarts: IntArray,
    bodyLine: Int?,
  ): Pair<Int, Int>? {
    if (bodyLine == null || bodyLine < 1 || bodyLine > lines.size) return null
    if (facts.declarations.isNotEmpty()) {
      // The anchor is a line inside the body; any offset on that line is inside the declaration,
      // and the line's first non-blank character avoids landing on trailing whitespace beyond it.
      val anchorOffset =
        lineStarts[bodyLine - 1] +
          lines[bodyLine - 1].indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
      val span = facts.declarationAt(anchorOffset) ?: return null
      return span.start to span.end
    }
    val bounds = PlaygroundSeedResolver.declarationLines(lines, bodyLine) ?: return null
    // End-exclusive: the offset one past the last character of the declaration's last line.
    return lineStarts[bounds.first] to (lineStarts[bounds.last] + lines[bounds.last].length)
  }

  /** One file's text, or null when it cannot be read as Kotlin source. */
  private fun read(key: FileKey): String? {
    val url =
      ServeUrls.githubRawUrl(key.repo, key.ref, key.module, key.sourceFile)
        ?: run {
          onLog("could not build a source URL for ${key.sourceFile}")
          return null
        }
    val bytes =
      try {
        fetch(url)
      } catch (e: Exception) {
        onLog("fetching $url failed (${e.message})")
        null
      } ?: return null
    if (bytes.size > maxBytes) {
      onLog("$url is ${bytes.size} bytes, over the ${maxBytes}-byte index cap")
      return null
    }
    val text = bytes.decodeToString()
    // Same reason the seed resolver rejects these: a file that decodes to replacement characters is
    // not Kotlin, and parsing it would report calls that are not there.
    if (text.contains('�')) {
      onLog("$url is not valid UTF-8; not indexing it")
      return null
    }
    // Normalised here, before anything measures it. `String.lines()` splits on `\r\n` too, so a
    // CRLF file would give line *contents* that are one character shorter than the source the
    // parser reported offsets into — and the drift accumulates down the file, quietly moving calls
    // out of the declaration they belong to. Parsing the same normalised text keeps one coordinate
    // system.
    return text.replace("\r\n", "\n").replace('\r', '\n')
  }

  /** The character offset each line starts at, so a call's offset can be placed in a line range. */
  private fun lineStartOffsets(lines: List<String>): IntArray {
    val starts = IntArray(lines.size)
    var offset = 0
    for (i in lines.indices) {
      starts[i] = offset
      offset += lines[i].length + 1 // `lines()` split on the newline, which is one character back
    }
    return starts
  }

  /** The identity of a source file — everything [ServeUrls.githubRawUrl] reads. */
  private data class FileKey(
    val repo: String,
    val ref: String,
    val module: String?,
    val sourceFile: String,
  )

  companion object {
    /**
     * Deliberately the **same** cap as [PlaygroundSeedResolver.DEFAULT_MAX_BYTES], and not merely a
     * similar number.
     *
     * `PlaygroundSeedResolver.httpFetch` reads `maxBytes + 1` bytes and stops. That extra byte is
     * the whole truncation protocol: a body at or over the cap comes back one byte longer than the
     * cap, so a reader whose own limit matches can tell "a big file" from "the start of a bigger
     * one". Setting a *larger* limit here silently accepts that prefix as if it were the file — and
     * a prefix still parses, so the index would answer with the calls in the first 256 KiB and
     * report itself complete, which is exactly the confident-but-wrong answer `available` exists to
     * prevent.
     */
    const val DEFAULT_MAX_BYTES: Int = PlaygroundSeedResolver.DEFAULT_MAX_BYTES
    const val DEFAULT_MAX_FILES: Int = 200
    const val DEFAULT_TTL_SECONDS: Long = 300
  }
}
