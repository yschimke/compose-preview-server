package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignLinksV1
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What one design is **for**: typed back-links to the issue behind it, the frame it reproduces, the
 * pull request that implemented it, the thread it is discussed in, and the design it continues (see
 * [`MULTIPLAYER_WORKFLOW.md`](../../../../../../../../docs/design/MULTIPLAYER_WORKFLOW.md) §4.2).
 *
 * Kept out of the design document for the reasons [ServeUiBuilderReferenceStore] gives: it is not
 * design content and must never reach an export, `DesignMutationV1` has no mutation for it, and it
 * must not advance the revision. One small JSON file per design under `links/`; losing it loses no
 * design content.
 *
 * [StoredLinks.issue], [StoredLinks.reference], [StoredLinks.pr] and [StoredLinks.thread] are
 * absolute `http(s)` URLs, [StoredLinks.previous] is a design id on this host, each bounded at
 * [MAX_VALUE_BYTES]; an empty record is no file. **Nothing here is ever fetched**: the URLs are
 * written by any design writer, and this host holds no credential for the systems they name.
 */
class ServeUiBuilderLinksStore(private val root: Path) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder links root is not a directory: $root" }
  }

  /**
   * Where each design was forked from and what was forked from it, kept beside the links because
   * the same host that records what a design is for records where it came from. See
   * [ServeUiBuilderAncestryStore].
   */
  internal val ancestry: ServeUiBuilderAncestryStore by lazy {
    ServeUiBuilderAncestryStore(root.resolve(ServeUiBuilderAncestryStore.DIRECTORY))
  }

  /** What one design is linked to, or null when nothing has been recorded for it. */
  fun read(designId: String): StoredLinks? = readFile(fileFor(designId))

  /**
   * Replace everything recorded for [designId], or explain why not.
   *
   * Replaces rather than merges: the record is five fields an operator or an agent holds in one
   * form, and a partial write is how a design ends up citing the issue it *used* to be for.
   * Clearing a field is done by sending the record without it, which a merge would make impossible
   * to express.
   */
  fun replace(designId: String, request: StoredLinks): LinksWriteResult {
    val candidate =
      StoredLinks(
        designId = designId,
        issue = request.issue.normalized(),
        reference = request.reference.normalized(),
        pr = request.pr.normalized(),
        thread = request.thread.normalized(),
        // Blank means unset, and nothing else is touched. Trimming would change which design the
        // id names: the service creates a design under any non-blank id, whitespace included, so
        // " checkout " and "checkout" are two designs and this field must not turn one into the
        // other.
        previous = request.previous?.ifBlank { null },
        updatedAtEpochMillis = System.currentTimeMillis(),
      )
    if (candidate.isEmpty) {
      // Nothing to keep is not a refusal; it is somebody having cleared the record, and the honest
      // storage for that is no file at all. A clear that did not happen is a refusal, though: the
      // caller asked for these links to be gone, and reporting success over a record still on disk
      // is how an issue or a pull request outlives the request to forget it.
      return when (deleteRecord(designId)) {
        LinksDeleteResult.REMOVED,
        LinksDeleteResult.ABSENT -> LinksWriteResult.Stored(candidate)
        LinksDeleteResult.FAILED ->
          LinksWriteResult.Failed("the links record could not be cleared from disk")
      }
    }
    refusal(candidate)?.let {
      return LinksWriteResult.Refused(it)
    }
    return write(fileFor(designId), candidate)
  }

  /**
   * Forget what [designId] is linked to, if anything.
   *
   * Three answers rather than two, because "there was nothing to remove" and "there was something
   * and it is still there" are the same `false` to a caller that cannot tell them apart — and the
   * second one, reported as success, leaves an issue or a pull request readable after an explicit
   * clear. The caller decides what a failure is worth; the store only declines to hide it.
   */
  fun delete(designId: String): LinksDeleteResult {
    // The design is going, so where it came from goes with it; its links record decides the answer.
    runCatching { ancestry.delete(designId) }
    return deleteRecord(designId)
  }

  private fun deleteRecord(designId: String): LinksDeleteResult =
    try {
      if (Files.deleteIfExists(fileFor(designId))) LinksDeleteResult.REMOVED
      else LinksDeleteResult.ABSENT
    } catch (_: IOException) {
      LinksDeleteResult.FAILED
    }

  /**
   * Every design on this host whose record cites [issue], in a stable order.
   *
   * A directory scan, deliberately: the reverse lookup is the "what is in flight for this issue"
   * question a person asks a handful of times a day, and the alternative — an index file to keep
   * consistent with the records it indexes — is a second source of truth for a lookup that costs a
   * few hundred stat calls.
   *
   * **This answers what is stored, not what the caller may see.** Every id returned is filtered
   * through a read of the design as the calling actor before it leaves the process; a store cannot
   * do that, and one that pretended to would be the wrong place for the access check to live.
   */
  fun citing(issue: String): List<String> = matching(issue) { it.issue }

  /**
   * Every design on this host whose record names [pr] as its pull request — the reverse of "the
   * implementation PR for this design", with [citing]'s caveat: the caller filters each id through
   * a read of the design as the asking actor.
   */
  fun citingPr(pr: String): List<String> = matching(pr) { it.pr }

  private fun matching(value: String, field: (StoredLinks) -> String?): List<String> {
    val wanted = value.normalized() ?: return emptyList()
    val files =
      try {
        Files.list(root).use { entries ->
          entries.filter { it.toString().endsWith(".json") }.toList()
        }
      } catch (_: IOException) {
        return emptyList()
      }
    return files
      .mapNotNull { readFile(it) }
      .filter { field(it) == wanted && it.designId.isNotBlank() }
      .map { it.designId }
      .distinct()
      .sorted()
  }

  private fun readFile(file: Path): StoredLinks? {
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_FILE_BYTES) null
      else
        LINKS_JSON.decodeFromString(
          StoredLinks.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      // A file this process cannot read is a file it will happily replace on the next write. The
      // alternative — failing the design's open because its links record is corrupt — makes an
      // optional annotation able to take a design offline.
      null
    }
  }

  private fun write(file: Path, stored: StoredLinks): LinksWriteResult {
    val encoded = LINKS_JSON.encodeToString(StoredLinks.serializer(), stored)
    return try {
      val temporary = Files.createTempFile(root, "links", ".tmp")
      try {
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
      LinksWriteResult.Stored(stored)
    } catch (_: IOException) {
      LinksWriteResult.Failed("the links record could not be written to disk")
    }
  }

  /**
   * A design id is caller-supplied text, so it never becomes a path segment: the file is named by
   * the digest of the id, which is fixed-length, path-safe, and cannot escape [root].
   */
  private fun fileFor(designId: String): Path =
    root.resolve(sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json")

  companion object {
    /**
     * Why this record may not be kept, or null when it may. Sentences a person is meant to read.
     *
     * On the companion rather than the instance because the rule is about the links and not about
     * where they are being put: the project design index checks an entry against it without holding
     * a store, and a second copy of the rule there is a second rule that could disagree.
     */
    fun refusal(links: StoredLinks): String? {
      URL_FIELDS.forEach { (name, read) ->
        val value = read(links) ?: return@forEach
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_VALUE_BYTES) {
          return "`$name` must be under $MAX_VALUE_BYTES bytes"
        }
        if (!value.isAbsoluteHttpUrl()) {
          return "`$name` must be an absolute http or https URL"
        }
      }
      val previous = links.previous ?: return null
      if (previous.toByteArray(StandardCharsets.UTF_8).size > MAX_VALUE_BYTES) {
        return "`previous` must be under $MAX_VALUE_BYTES bytes"
      }
      // `previous` names a design on this host rather than a URL. Checked for exactly that and no
      // more: the service creates a design under any non-blank id — long, non-ASCII, or carrying
      // whitespace — so any shape imposed here would make a design that opens and edits normally
      // impossible to name as a predecessor.
      if (previous.isAbsoluteHttpUrl()) {
        return "`previous` must be a design id on this host, not a URL"
      }
      return null
    }

    /**
     * Ceiling on one link.
     *
     * Two kilobytes is longer than any tracker, forge or chat permalink in circulation and short
     * enough that a design's whole record stays a single small read. A link that does not fit is
     * not a link; it is somebody pasting a document into a field.
     */
    const val MAX_VALUE_BYTES: Int = 2 * 1024

    /** The whole record, with the JSON around it and room for a field a later release adds. */
    private const val MAX_FILE_BYTES: Long = 32L * 1024

    private val URL_FIELDS: List<Pair<String, (StoredLinks) -> String?>> =
      listOf(
        "issue" to StoredLinks::issue,
        "reference" to StoredLinks::reference,
        "pr" to StoredLinks::pr,
        "thread" to StoredLinks::thread,
      )

    private val LINKS_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }

    /** Trimmed, with blank read as absent: an empty string is how a form says "unset". */
    private fun String?.normalized(): String? = this?.trim()?.ifEmpty { null }

    /**
     * Absolute, `http` or `https`, with a host.
     *
     * Scheme-checked rather than merely parsed because the value is handed to a browser as a link:
     * a `javascript:` or `data:` "URL" stored here would be a stored cross-site script the moment a
     * panel rendered it as an anchor, and refusing it at the door is one check rather than an
     * escaping rule every reader has to remember.
     */
    private fun String.isAbsoluteHttpUrl(): Boolean {
      val uri =
        try {
          URI(this)
        } catch (_: URISyntaxException) {
          return false
        }
      if (!uri.isAbsolute) return false
      val scheme = uri.scheme?.lowercase()
      if (scheme != "http" && scheme != "https") return false
      return !uri.host.isNullOrBlank()
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
      }
  }
}

/**
 * The links payload, on the wire and on disk — [DesignLinksV1], published.
 *
 * The shape moved to `compose-preview-contracts` because that is where a wire shape lives, and this
 * record is one three times over: the response body of the links routes, the payload of
 * `ui_builder_get_links`, and the file under `links/`. The alias is kept because the name says what
 * this server does with it — it *stores* the thing — and because every reader here is about storage
 * rather than about the wire.
 *
 * Nothing about the JSON changed. The published type carries the same field names and the same
 * `@SerialName`, so a host reads the records it wrote before this bump.
 *
 * What did not move is everything that is not shape: [ServeUiBuilderLinksStore.refusal] and its URL
 * rules, the digest that names the file, and [isEmpty] below. The contracts module is shape and
 * never behaviour, so a question *about* a record is answered here.
 */
typealias StoredLinks = DesignLinksV1

/** Whether this record says nothing, which is stored as no record at all. */
val StoredLinks.isEmpty: Boolean
  get() = issue == null && reference == null && pr == null && thread == null && previous == null

/** What [ServeUiBuilderLinksStore.delete] did: removed a record, found none, or could not. */
enum class LinksDeleteResult {
  REMOVED,
  ABSENT,
  FAILED,
}

sealed interface LinksWriteResult {
  data class Stored(val links: StoredLinks) : LinksWriteResult

  /**
   * These links are not ones this host will keep, whatever the state of the disk.
   *
   * A fact about the record, so the same payload will be refused again: the caller should change it
   * rather than retry it.
   */
  data class Refused(val reason: String) : LinksWriteResult

  /**
   * The record was acceptable and the storage did not take it.
   *
   * Kept apart from [Refused] because the two want opposite things from a client. A refusal is
   * permanent and a failure is not, and reporting a full disk as a validation error tells a caller
   * to stop sending a payload that would have worked.
   */
  data class Failed(val reason: String) : LinksWriteResult
}
