package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Which functions each preview's declaration calls, so the landing grid's filter can find previews
 * that call e.g. `SwipeToReveal`. Reuses the playground's Source-panel inputs
 * ([PlaygroundSeedResolver.Location], [UsageSourceParser]), fetching each distinct file once and
 * splitting it by [PlaygroundSeedResolver.declarationLines].
 *
 * Callees are recorded as written, unresolved. Any stage may be absent, and [Match.available]
 * distinguishes "nothing indexed" from "no preview calls that".
 */
class PreviewUsageIndex(
  /** Where a preview's source lives, or null when this server can't say. */
  private val locate: (system: String, previewId: String) -> PlaygroundSeedResolver.Location?,
  /** Fetches a URL, returning its bytes or null. Injected so tests never touch the network. */
  private val fetch: (String) -> ByteArray?,
  /**
   * The Kotlin parser, or null when its sidecar isn't staged. A function so the ~0.5 s classloader
   * build is paid only by a server that indexes.
   */
  private val parser: () -> UsageSourceParser? = { UsageSourceParser.of() },
  /** Source files are code; anything larger than this is not a preview file worth indexing. */
  private val maxBytes: Int = DEFAULT_MAX_BYTES,
  /**
   * Ceiling on distinct files one build fetches: a runaway guard, reported via [Index.truncated] so
   * a partial index doesn't claim "nothing calls that".
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
   * One lock per catalog: building is up to [maxFiles] network reads, and a process-wide lock let
   * one cold catalog block every other `uses:` request. Concurrent searches of the same cold
   * catalog still share one build.
   */
  private val locks = ConcurrentHashMap<String, Any>()

  /**
   * The previews among [previewIds] whose declaration calls something matching [token], by
   * case-insensitive substring of the callee name (`button` finds `Button`, `FilledIconButton`,
   * `ButtonGroup`).
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
   * This catalog's index, built or reused. Validated against a hash of the preview list so a
   * republished catalog with changed previews rebuilds.
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
    // Grouped by file, the unit of fetch and parse. Previews with no location aren't indexed and
    // match nothing.
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
    // Normalise line endings before measuring: `String.lines()` splits on `\r\n`, so CRLF text
    // would drift from the parser's offsets and misattribute calls.
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
     * Deliberately the same cap as [PlaygroundSeedResolver.DEFAULT_MAX_BYTES]: `httpFetch` reads
     * `maxBytes + 1` bytes to signal truncation, so a larger limit here would accept a truncated
     * prefix as the whole file and report a confidently incomplete index.
     */
    const val DEFAULT_MAX_BYTES: Int = PlaygroundSeedResolver.DEFAULT_MAX_BYTES
    const val DEFAULT_MAX_FILES: Int = 200
    const val DEFAULT_TTL_SECONDS: Long = 300
  }
}
