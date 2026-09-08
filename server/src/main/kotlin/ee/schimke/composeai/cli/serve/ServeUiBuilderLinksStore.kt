package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What one design is **for**: the issue behind it, the frame it reproduces, the pull request that
 * implemented it, the thread it is being discussed in, and the design it continues.
 *
 * A design on this host knows its catalog pin and nothing else. The work it belongs to — the brief,
 * the review, the ship — lives in the tracker, the design tool, the forge and the chat channel, and
 * every one of those already has a durable identifier. This is the join: five typed back-links
 * beside a design, so the thread of work can be assembled from the outside without this server
 * growing a saga object of its own (see
 * [`MULTIPLAYER_WORKFLOW.md`](../../../../../../../../docs/design/MULTIPLAYER_WORKFLOW.md) §4.2).
 *
 * ### Why this is not in the design document
 *
 * The same three reasons [ServeUiBuilderReferenceStore] gives for the reference overlay, and they
 * hold here without weakening:
 *
 * 1. **It is not part of the design.** Which issue a screen was drawn for is scaffolding around the
 *    work, not content of it. It must never reach the Compose export, the SVG export or the
 *    rendered document — a `pr` URL is not a thing that ships in generated Kotlin.
 * 2. **The wire cannot carry it.** `DesignMutationV1` is a closed set with no mutation for any of
 *    this, so there is no way to write it after `createDesign` without releasing
 *    `ui-builder-protocol` — to carry something point 1 says should not be in the document.
 * 3. **It must not move the revision.** The document is replayed, hashed, diffed for catalog
 *    upgrades and pushed to every subscriber on every edit. Pasting an issue URL would advance the
 *    design's revision and invalidate every open client's optimistic state, which is a large price
 *    for a fact about the design rather than a change to it.
 *
 * So it lives here: one small JSON file per design under a `links/` directory beside the UI-builder
 * state, read when a design is opened and replaced whole when it changes. Losing this directory
 * loses the back-links and no design content, which is the correct blast radius.
 *
 * ### What it will accept
 *
 * [StoredLinks.issue], [StoredLinks.reference], [StoredLinks.pr] and [StoredLinks.thread] are
 * absolute `http(s)` URLs; [StoredLinks.previous] is a design id on this host. Every value is
 * bounded at [MAX_VALUE_BYTES]. All of them are optional, and an absent field means unset rather
 * than empty — a record with nothing in it is no file at all.
 *
 * Nothing here is ever fetched. A link is provenance the reader follows, and this host holds no
 * credential for any of the systems these URLs name; the schemes are checked so that what is stored
 * is something a browser can be handed, not so that this process can go and get it.
 */
class ServeUiBuilderLinksStore(private val root: Path) {
  init {
    Files.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder links root is not a directory: $root" }
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
        previous = request.previous.normalized(),
        updatedAtEpochMillis = System.currentTimeMillis(),
      )
    if (candidate.isEmpty) {
      // Nothing to keep is not a refusal; it is somebody having cleared the record, and the honest
      // storage for that is no file at all. A clear that did not happen is a refusal, though: the
      // caller asked for these links to be gone, and reporting success over a record still on disk
      // is how an issue or a pull request outlives the request to forget it.
      return when (delete(designId)) {
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
  fun delete(designId: String): LinksDeleteResult =
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
  fun citing(issue: String): List<String> {
    val wanted = issue.normalized() ?: return emptyList()
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
      .filter { it.issue == wanted && it.designId.isNotBlank() }
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
      // more: the service creates a design under any non-blank id, so holding this field to the
      // stricter shape the project index requires would make a design that opens and edits
      // normally impossible to name as a predecessor.
      if (
        previous.isBlank() || previous.isAbsoluteHttpUrl() || previous.any { it.isWhitespace() }
      ) {
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
 * The links payload, on the wire and on disk.
 *
 * One shape for both, for the reason [StoredReference] gives: the file *is* the response body, plus
 * the design id and a timestamp somebody looking at the directory will want. [designId] and
 * [updatedAtEpochMillis] are assigned by the host, so a client may send them and they are
 * overwritten rather than trusted.
 */
@Serializable
data class StoredLinks(
  @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
  val designId: String = "",
  /** The tracker issue this design is for. */
  val issue: String? = null,
  /** The frame in the design tool it reproduces — any tool; this host resolves none of them. */
  val reference: String? = null,
  /** The pull request that implemented it. */
  val pr: String? = null,
  /** The chat thread it is being discussed in, as a permalink. */
  val thread: String? = null,
  /** The design on this host that this one continues. */
  val previous: String? = null,
  val updatedAtEpochMillis: Long = 0,
) {
  /** Whether this record says nothing, which is stored as no record at all. */
  val isEmpty: Boolean
    get() = issue == null && reference == null && pr == null && thread == null && previous == null

  companion object {
    const val SCHEMA_VERSION: Int = 1
  }
}

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
