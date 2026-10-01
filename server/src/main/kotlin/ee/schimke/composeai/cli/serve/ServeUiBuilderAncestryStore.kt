package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Where a design was forked from, and which designs were forked from it.
 *
 * ## Why it is kept here rather than in the design
 *
 * A fork is a new design made from one revision of another, and until this existed it forgot where
 * it came from the moment it was created: the HTML history page's fork made a copy with `home =
 * null` and no link back. Branches (yschimke/compose-ui-builder#375) need that relation — a branch
 * *is* a fork that remembers its fork point — so it is recorded now, for both the MCP
 * `ui_builder_fork_design` and the history page's form.
 *
 * It belongs in the runtime's model eventually, beside the document it describes. Phase 1 needs it
 * before `ui-builder-runtime` grows a branch model, and the published `DesignLinksV1` has no field
 * for it, so it is kept the way the links are kept and for the same three reasons
 * ([ServeUiBuilderLinksStore] gives them): it is not design content, the wire cannot carry it, and
 * it must not move the revision. One small JSON file per design, beside the links records, read by
 * `ui_builder_get_links` and written only by a fork.
 *
 * Both ends are written: the fork's [StoredAncestry.forkedFrom], and the parent's
 * [StoredAncestry.forks]. A design's delete removes its record and takes it off its parent's list,
 * so an id reused later does not inherit a past it never had.
 */
internal class ServeUiBuilderAncestryStore(private val root: Path) {
  private val lock = Any()

  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder ancestry root is not a directory: $root" }
  }

  /** What is recorded for [designId], or null when it was never forked and is not a fork. */
  fun read(designId: String): StoredAncestry? = synchronized(lock) { readFile(fileFor(designId)) }

  /**
   * Record that [forkId] was made from [forkedFrom], on both ends. False when the disk did not take
   * it — the fork exists either way, and the caller reports the gap rather than undoing the fork.
   */
  fun recordFork(forkedFrom: DesignForkPointV1, forkId: String, atEpochMillis: Long): Boolean =
    synchronized(lock) {
      val child =
        (readFile(fileFor(forkId)) ?: StoredAncestry(designId = forkId)).copy(
          forkedFrom = forkedFrom
        )
      val parentId = forkedFrom.designId
      val parent = readFile(fileFor(parentId)) ?: StoredAncestry(designId = parentId)
      val updatedParent =
        parent.copy(
          forks =
            (parent.forks.filterNot { it.designId == forkId } +
                DesignForkV1(forkId, forkedFrom.revision, atEpochMillis))
              .takeLast(MAX_FORKS)
        )
      write(fileFor(forkId), child) && write(fileFor(parentId), updatedParent)
    }

  /** Forget [designId]'s record, and take it off its parent's list of forks. */
  fun delete(designId: String): Boolean =
    synchronized(lock) {
      val record = readFile(fileFor(designId))
      record?.forkedFrom?.let { from ->
        readFile(fileFor(from.designId))?.let { parent ->
          val remaining = parent.copy(forks = parent.forks.filterNot { it.designId == designId })
          if (remaining.forkedFrom == null && remaining.forks.isEmpty()) {
            Files.deleteIfExists(fileFor(from.designId))
          } else {
            write(fileFor(from.designId), remaining)
          }
        }
      }
      try {
        Files.deleteIfExists(fileFor(designId))
        true
      } catch (_: IOException) {
        false
      }
    }

  private fun readFile(file: Path): StoredAncestry? {
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_FILE_BYTES) null
      else
        ANCESTRY_JSON.decodeFromString(
          StoredAncestry.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      null
    }
  }

  private fun write(file: Path, stored: StoredAncestry): Boolean =
    try {
      val temporary = Files.createTempFile(root, "ancestry", ".tmp")
      try {
        Files.writeString(
          temporary,
          ANCESTRY_JSON.encodeToString(StoredAncestry.serializer(), stored),
          StandardCharsets.UTF_8,
        )
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
      true
    } catch (_: IOException) {
      false
    }

  /** Named by the digest of the id, as the links records are: an id never becomes a path. */
  private fun fileFor(designId: String): Path =
    root.resolve(
      MessageDigest.getInstance("SHA-256")
        .digest(designId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".json"
    )

  companion object {
    /** The directory under the links root these records live in. */
    const val DIRECTORY = "ancestry"

    /**
     * The forks one design lists, newest kept. A design forked more often than this is a design
     * somebody is scripting against, and the oldest forks still know their own parent.
     */
    const val MAX_FORKS = 256

    private const val MAX_FILE_BYTES: Long = 256L * 1024

    private val ANCESTRY_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}

/** One design's ancestry record. */
@Serializable
internal data class StoredAncestry(
  val designId: String,
  val forkedFrom: DesignForkPointV1? = null,
  val forks: List<DesignForkV1> = emptyList(),
)

/** The point a fork was made from: a design, one of its revisions, and that revision's content. */
@Serializable
internal data class DesignForkPointV1(
  val designId: String,
  val revision: Long,
  /** [designDocumentDigest] of the parent's document at [revision]. */
  val documentDigest: String,
)

/** One fork a design has, as its parent lists it. */
@Serializable
internal data class DesignForkV1(
  val designId: String,
  /** The parent's revision the fork was made from. */
  val revision: Long,
  val forkedAtEpochMillis: Long,
)

/**
 * The document a fork starts as: [source] under [forkId], at revision 0, with no timestamps and no
 * home — a fork is never its parent's canonical copy. The create path then homes it here under its
 * own id when the host has a public origin, as any new design is.
 */
internal fun forkedDesignDocument(
  source: DesignDocumentV1,
  sourceDesignId: String,
  forkId: String,
  title: String? = null,
): DesignDocumentV1 =
  source.copy(
    id = forkId,
    revision = 0,
    title =
      title ?: "${source.title.ifBlank { sourceDesignId }} (from revision ${source.revision})",
    createdAtEpochMillis = null,
    updatedAtEpochMillis = null,
    home = null,
  )

/**
 * A fork's id when the caller names none: never an existing design, and says where it came from.
 */
internal fun defaultForkId(designId: String, revision: Long): String =
  "${designId.take(40)}-r$revision-" + java.util.UUID.randomUUID().toString().take(6)

/**
 * Record a fork that has just been created, where this host keeps links. Returns whether ancestry
 * was recorded; a host without a links store records none and says so.
 */
internal fun recordDesignFork(
  links: ServeUiBuilderLinksStore?,
  source: DesignDocumentV1,
  sourceDesignId: String,
  forkId: String,
): Pair<DesignForkPointV1, Boolean> {
  val point = DesignForkPointV1(sourceDesignId, source.revision, designDocumentDigest(source))
  val recorded = links?.ancestry?.recordFork(point, forkId, System.currentTimeMillis()) ?: false
  return point to recorded
}

/**
 * [linksReply] — a `ui_builder_get_links` reply — with the design's ancestry spliced on: its
 * `forkedFrom` point, and the `forks` made from it that [actor] may open. A fork the actor cannot
 * read is left out rather than named, the rule every sidecar here follows; the fork point is the
 * design's own provenance and is shown to anybody who may read the design.
 */
internal suspend fun withAncestry(
  service: UiBuilderServicePort,
  actor: AuthenticatedUiBuilderActor,
  ancestry: ServeUiBuilderAncestryStore?,
  designId: String,
  linksReply: String,
): String {
  val record = ancestry?.read(designId) ?: return linksReply
  val parsed =
    try {
      UI_BUILDER_JSON.parseToJsonElement(linksReply) as? JsonObject ?: return linksReply
    } catch (_: SerializationException) {
      return linksReply
    }
  val forks = record.forks.filter { service.canRead(actor, it.designId) }
  val extra = buildMap {
    record.forkedFrom?.let { put(FORKED_FROM_KEY, UI_BUILDER_JSON.encodeToJsonElement(it)) }
    if (forks.isNotEmpty()) put(FORKS_KEY, UI_BUILDER_JSON.encodeToJsonElement(forks))
  }
  return if (extra.isEmpty()) linksReply else JsonObject(parsed + extra).toString()
}

internal const val FORKED_FROM_KEY = "forkedFrom"
internal const val FORKS_KEY = "forks"
