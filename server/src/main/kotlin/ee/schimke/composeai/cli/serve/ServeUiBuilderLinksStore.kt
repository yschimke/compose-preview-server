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
 * What one design is for: typed back-links to its issue, reproduced frame, implementing PR,
 * discussion thread and predecessor design (see
 * [`MULTIPLAYER_WORKFLOW.md`](../../../../../../../../docs/design/MULTIPLAYER_WORKFLOW.md) §4.2).
 * Kept out of the document for [ServeUiBuilderReferenceStore]'s reasons. One small JSON file per
 * design under `links/`.
 *
 * [StoredLinks.issue], [StoredLinks.reference], [StoredLinks.pr] and [StoredLinks.thread] are
 * absolute `http(s)` URLs, [StoredLinks.previous] a design id, each bounded by [MAX_VALUE_BYTES];
 * an empty record is no file. Nothing here is ever fetched: any design writer sets these URLs.
 */
class ServeUiBuilderLinksStore(private val root: Path) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder links root is not a directory: $root" }
  }

  /** Fork ancestry, kept beside the links; see [ServeUiBuilderAncestryStore]. */
  internal val ancestry: ServeUiBuilderAncestryStore by lazy {
    ServeUiBuilderAncestryStore(root.resolve(ServeUiBuilderAncestryStore.DIRECTORY))
  }

  /** What one design is linked to, or null when nothing has been recorded for it. */
  fun read(designId: String): StoredLinks? = readFile(fileFor(designId))

  /**
   * Replace everything recorded for [designId], or explain why not. Replace rather than merge, so a
   * stale field can't survive and clearing one is expressible.
   */
  fun replace(designId: String, request: StoredLinks): LinksWriteResult {
    val candidate =
      StoredLinks(
        designId = designId,
        issue = request.issue.normalized(),
        reference = request.reference.normalized(),
        pr = request.pr.normalized(),
        thread = request.thread.normalized(),
        // Blank means unset; no trimming, since the service treats `" checkout "` and `"checkout"`
        // as different designs.
        previous = request.previous?.ifBlank { null },
        updatedAtEpochMillis = System.currentTimeMillis(),
      )
    if (candidate.isEmpty) {
      // An empty record is stored as no file; a clear that didn't actually remove the file is a
      // refusal, not success.
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
   * Forget [designId]'s links. Three answers, so "nothing to remove" is distinguishable from "still
   * there", which must not be reported as success.
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
   * Every design here whose record cites [issue], in stable order. A directory scan rather than an
   * index file, since the lookup is rare and an index would be a second source of truth. Returns
   * what is stored, not what the caller may see: callers filter each id through a read as the
   * asking actor.
   */
  fun citing(issue: String): List<String> = matching(issue) { it.issue }

  /** Every design naming [pr] as its implementation; same filtering caveat as [citing]. */
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
      // An unreadable record reads as none (and is replaced on the next write), so an optional
      // annotation can't take a design offline.
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
   * Design ids are caller-supplied, so files are named by the id's digest, which can't escape
   * [root].
   */
  private fun fileFor(designId: String): Path =
    root.resolve(sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json")

  companion object {
    /**
     * Why this record may not be kept, as readable sentences, or null. On the companion so the
     * project design index applies the same rule without a store.
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
      // `previous` is a design id, not a URL; no other shape is imposed since the service accepts
      // any non-blank id.
      if (previous.isAbsoluteHttpUrl()) {
        return "`previous` must be a design id on this host, not a URL"
      }
      return null
    }

    /**
     * Ceiling on one link: longer than any real permalink, short enough that a record stays one
     * small read.
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
     * Absolute `http`/`https` with a host. Scheme-checked because the value becomes a link in a
     * browser; a stored `javascript:` or `data:` URL would be stored XSS.
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
 * The links payload on the wire and on disk: the published [DesignLinksV1] (route body,
 * `ui_builder_get_links` payload and file under `links/`). Aliased here because this server stores
 * it. Same field names, so existing records still read. Behaviour
 * ([ServeUiBuilderLinksStore.refusal], the file digest, [isEmpty]) stays here; contracts are shape
 * only.
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

  /** These links will never be kept; change the payload rather than retry. */
  data class Refused(val reason: String) : LinksWriteResult

  /**
   * The record was acceptable but storage failed. Distinct from [Refused] because this one is worth
   * retrying.
   */
  data class Failed(val reason: String) : LinksWriteResult
}
