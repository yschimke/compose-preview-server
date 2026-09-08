package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Everything a store holds, as the service wants it at construction.
 *
 * [quarantined] carries the designs whose files could not be read, by id and reason, so a design
 * that cannot be loaded costs exactly itself: the single-file store had one checksum over every
 * design at once, which is why one bad byte took the whole lane down.
 */
internal data class StoredDesigns(
  val designs: Map<String, PersistedDesignV1> = emptyMap(),
  val quarantined: Map<String, String> = emptyMap(),
)

/**
 * Where the UI builder's designs are kept.
 *
 * The seam this replaces — [UiBuilderStateStorage], `load(): ByteArray?` and `replace(ByteArray)` —
 * can express nothing except "here is all of it", so an edit to one design rewrote every design.
 * This one names the design an edit touched, and is handed the value before and after so it can
 * write only the parts that differ. See `docs/design/UI_BUILDER_STATE_STORAGE.md`.
 */
internal interface UiBuilderDesignStore {
  /** Every stored design. Called once, at construction. */
  fun load(): StoredDesigns

  /**
   * Persists [next], writing only the parts in which it differs from [previous].
   *
   * [previous] is what this store last held for the design, or null when it held nothing. The
   * candidate is built with `copy()`, so an untouched part is the same object and the comparison
   * costs a pointer.
   */
  fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1)

  fun remove(designId: String)

  /** What is held against the ceiling that would refuse a write, or null when there is neither. */
  fun usage(): UiBuilderStorageUsage? = null
}

/**
 * The v3 per-design store on disk, as a host constructs it.
 *
 * Opaque on purpose: the shapes it reads and writes are the service's own persistence model, which
 * is not published, so this exposes only what a host needs — where the state is and how full it is.
 */
public class UiBuilderDesignStateStore
internal constructor(internal val store: UiBuilderDesignStore) {
  /** What the store holds against the ceiling a write is refused at. */
  public fun usage(): UiBuilderStorageUsage? = store.usage()

  public companion object {
    /** The marker whose presence means a state directory holds the per-design store. */
    public const val STORE_FILE: String = "store.json"

    /**
     * Opens the store under [root], migrating a v2 single-file state the first time it is found.
     *
     * The migration is one-shot and one-directional: `store.json` present means v3 and the old file
     * is ignored; otherwise `ui-builder-service-v1.json` is decoded, written out per design, and
     * renamed to `.migrated` — never deleted, because it is the rollback.
     */
    @JvmStatic
    public fun open(
      root: Path,
      limits: UiBuilderStoreLimits = UiBuilderStoreLimits(),
    ): UiBuilderDesignStateStore = UiBuilderDesignStateStore(FileUiBuilderDesignStore(root, limits))
  }
}

/**
 * The byte budgets the per-design store applies.
 *
 * A design can no longer fail to save because another design grew, so the one whole-store cliff
 * becomes two numbers that say what they bound: [maximumDesignBytes] refuses a write to the design
 * that is too large, and [maximumBytes] is the gauge an operator watches.
 */
public data class UiBuilderStoreLimits(
  /** Reported by [UiBuilderDesignStateStore.usage]; nothing is refused for reaching it. */
  val maximumBytes: Long = 1_024L * 1_024 * 1_024,
  /** A single design's own ceiling: its parts, its retained revisions and its journal. */
  val maximumDesignBytes: Long = 64L * 1_024 * 1_024,
  /** The journal is rewritten once it is this large and this much larger than what it holds. */
  val journalCompactionBytes: Long = 256L * 1_024,
  val journalCompactionRatio: Int = 4,
) {
  init {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    require(maximumDesignBytes > 0) { "maximumDesignBytes must be positive" }
    require(journalCompactionBytes > 0) { "journalCompactionBytes must be positive" }
    require(journalCompactionRatio > 1) { "journalCompactionRatio must be greater than one" }
  }
}

/** The header of one stored design: which file each part is in, and what a listing needs. */
@Serializable
internal data class StoredDesignHeaderV3(
  val designId: String,
  val title: String,
  val revision: Long,
  val lastSequence: Long,
  val access: DesignAccessControlV1,
  val catalogPin: CatalogReferenceV1,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val documentFile: String,
  val positionsFile: String,
  /** Retained revision to the file holding it, keyed by revision as a string because JSON. */
  val revisionFiles: Map<String, String> = emptyMap(),
  val journalFile: String? = null,
  /**
   * How much of [journalFile] this header commits to.
   *
   * The append happens before the header lands, so a crash between the two leaves records nothing
   * points at. Replay reads exactly this many bytes; the tail is truncated by the next commit.
   */
  val journalBytes: Long = 0,
  /**
   * What writing this design's collections out whole cost, the last time that was done.
   *
   * The measure of when a journal stops paying: appends are worth it while the file is small
   * against what it describes, and a file several times that size is cheaper to replace than to
   * keep replaying.
   */
  val journalCompactedBytes: Long = 0,
)

/** One retained revision: the document at it, the positions at it, or both. */
@Serializable
internal data class StoredRevisionV3(
  val revision: Long,
  val sequence: Long? = null,
  val document: DesignDocumentV1? = null,
  val positions: Map<String, StableNodePositionV1>? = null,
)

/** The current position map, in a file of its own because it is replaced whole. */
@Serializable
internal data class StoredPositionsV3(val positions: Map<String, StableNodePositionV1>)

/**
 * One commit's changes to the collections that grow by an entry per edit.
 *
 * `history`, `audit`, `operationOutcomes`, `acceptedOperations` and `tombstones` are appended to
 * and trimmed from the front, so writing each as a whole file would put the store back where it
 * started: a design holding four megabytes of undo state would rewrite four megabytes to record one
 * outcome. Every field is null when that collection did not change.
 */
@Serializable
internal data class JournalEntryV3(
  val historyAppend: List<CommittedOperationV1>? = null,
  val historyKeep: Int? = null,
  val historySet: List<CommittedOperationV1>? = null,
  val auditAppend: List<AuditRecordV1>? = null,
  val auditKeep: Int? = null,
  val auditSet: List<AuditRecordV1>? = null,
  val outcomesPut: Map<String, OperationOutcomeRecordV1>? = null,
  val outcomesRemoved: List<String>? = null,
  val acceptedPut: Map<String, AcceptedOperationRecordV1>? = null,
  val acceptedRemoved: List<String>? = null,
  val tombstonesPut: Map<String, NodeTreeSnapshotV1>? = null,
  val tombstonesRemoved: List<String>? = null,
)

/**
 * One journal line: a record, and a checksum of the record as stored.
 *
 * The header's `journalBytes` says which records are *committed*, which is a different question
 * from whether the bytes are still the bytes — a torn tail is not corruption. Every other part of a
 * design carries a checksum of its own stored tree, and a journal record that flipped a bit while
 * staying valid JSON would otherwise be replayed as though it were authored: an outcome, an undo
 * record or a tombstone, silently changed. Per record rather than over the whole prefix, because a
 * digest of everything committed would cost the whole journal on every append, which is the cost
 * the journal exists to avoid.
 */
@Serializable internal data class JournalLineV3(val checksumSha256: String, val entry: JsonElement)

/** Why one design could not be read, written beside it rather than thrown. */
@Serializable
internal data class StoredQuarantineV3(val reason: String, val recordedAtEpochMillis: Long)

@Serializable
internal data class StoreMarkerV3(val format: String, val migratedFrom: String? = null)

/**
 * One design's parts as they are on disk, so the next commit knows what it may reuse.
 *
 * Held per design rather than derived from the header on each write because it also carries the
 * journal's live size, which decides when the journal is rewritten.
 */
private data class DesignFiles(val header: StoredDesignHeaderV3, val bytes: Long)

/**
 * One directory per design, one file per part, and a header that names them.
 *
 * A part file is named by the digest of what is in it, so a commit's new parts are invisible until
 * `design.json` names them and the header rename is the moment the design becomes the new one. A
 * crash before it leaves the previous generation complete; a crash after it leaves files nothing
 * references, which the next open of that design unlinks. See
 * `docs/design/UI_BUILDER_STATE_STORAGE.md`.
 */
internal class FileUiBuilderDesignStore(
  root: Path,
  private val limits: UiBuilderStoreLimits = UiBuilderStoreLimits(),
) : UiBuilderDesignStore {
  private val directory = root.toAbsolutePath().normalize()
  private val designsDirectory = directory.resolve(DESIGNS_DIRECTORY)
  private val markerFile = directory.resolve(STORE_FILE)
  private val lockFile = directory.resolve(LOCK_FILE)
  private val files = linkedMapOf<String, DesignFiles>()
  /**
   * The designs that could not be read, by id and directory.
   *
   * Held because a quarantined design is still the operator's content and still on the disk: it is
   * absent from the service's design map, so `remove` has nothing else to resolve its directory
   * from, and retiring it is the one admin action that must keep working when reading it does not.
   */
  private val quarantinedSlugs = linkedMapOf<String, String>()
  private var storedBytes = 0L

  init {
    Files.createDirectories(directory)
    require(Files.isDirectory(directory)) { "UI-builder state root is not a directory: $directory" }
    // Opening is what migrates a v2 file and what declares the format, and it happens here rather
    // than on the first read so that a store this build cannot read fails where `serve` already
    // catches it — one warning and a disabled lane, never a half-open store
    // (yschimke/compose-preview-server#568).
    locked {
      migrateLegacyStateIfPresent()
      if (Files.exists(markerFile)) readMarker() else writeMarker(StoreMarkerV3(STORE_FORMAT))
    }
  }

  override fun load(): StoredDesigns = locked {
    val designs = linkedMapOf<String, PersistedDesignV1>()
    val quarantined = linkedMapOf<String, String>()
    storedBytes = 0
    for (slug in designSlugs()) {
      val designDirectory = designsDirectory.resolve(slug)
      val existingQuarantine = readQuarantine(designDirectory)
      if (existingQuarantine != null) {
        quarantined[existingQuarantine.first] = existingQuarantine.second
        quarantinedSlugs[existingQuarantine.first] = slug
        // Counted even though it cannot be decoded: a large corrupt design is still on the disk,
        // and a gauge that called those bytes free would be wrong exactly when an operator needs to
        // notice that broken data is being retained.
        storedBytes += directoryBytes(designDirectory)
        continue
      }
      try {
        val header = readHeader(designDirectory)
        val design = readDesign(designDirectory, header)
        designs[header.designId] = design
        val bytes = directoryBytes(designDirectory)
        files[header.designId] = DesignFiles(header, bytes)
        storedBytes += bytes
        sweep(designDirectory, header)
      } catch (failure: Exception) {
        val designId = quarantineDesignId(designDirectory, slug)
        val reason = failure.message ?: failure::class.simpleName ?: "unreadable"
        writeQuarantine(designDirectory, designId, reason)
        quarantined[designId] = reason
        quarantinedSlugs[designId] = slug
        storedBytes += directoryBytes(designDirectory)
      }
    }
    StoredDesigns(designs, quarantined)
  }

  override fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1) {
    locked {
      val designDirectory = designsDirectory.resolve(slug(designId))
      Files.createDirectories(designDirectory)
      if (!Files.exists(markerFile)) writeMarker(StoreMarkerV3(STORE_FORMAT))
      val current = files[designId]
      val known = if (current == null) null else previous
      val written = mutableListOf<Path>()
      try {
        val documentFile =
          if (known != null && known.document == next.document && current != null) {
            current.header.documentFile
          } else {
            writePart(designDirectory, DOCUMENT_PART, json.encodeToJsonElement(next.document))
              .also { written.add(designDirectory.resolve(it)) }
          }
        val positionsFile =
          if (known != null && known.positions == next.positions && current != null) {
            current.header.positionsFile
          } else {
            writePart(
                designDirectory,
                POSITIONS_PART,
                json.encodeToJsonElement(StoredPositionsV3(next.positions)),
              )
              .also { written.add(designDirectory.resolve(it)) }
          }
        val revisionFiles =
          writeRevisions(designDirectory, known, next, current?.header?.revisionFiles.orEmpty()) {
            written.add(it)
          }
        val entry = journalEntry(known, next)
        val previousJournal = current?.header?.journalFile
        val previousJournalBytes = current?.header?.journalBytes ?: 0
        val compactedBytes = current?.header?.journalCompactedBytes ?: 0
        val journal =
          when {
            previousJournal == null -> compactJournal(designDirectory, next, generation = 1)
            entry == null -> JournalWrite(previousJournal, previousJournalBytes, compactedBytes)
            shouldCompact(previousJournalBytes, compactedBytes) ->
              compactJournal(
                designDirectory,
                next,
                generation = journalGeneration(previousJournal) + 1,
              )
            else ->
              appendJournal(
                designDirectory,
                previousJournal,
                previousJournalBytes,
                entry,
                compactedBytes,
              )
          }
        val header =
          StoredDesignHeaderV3(
            designId = designId,
            title = next.document.title,
            revision = next.document.revision,
            lastSequence = next.lastSequence,
            access = next.access,
            catalogPin = next.document.catalogPin,
            createdAtEpochMillis = next.createdAtEpochMillis,
            updatedAtEpochMillis = next.updatedAtEpochMillis,
            documentFile = documentFile,
            positionsFile = positionsFile,
            revisionFiles = revisionFiles,
            journalFile = journal.file,
            journalBytes = journal.bytes,
            journalCompactedBytes = journal.compactedBytes,
          )
        // The budget is checked before the header lands, because the header is what makes the new
        // generation the design. Checked after it, a refused write would already be durable: the
        // caller would be told its edit failed and a restart would load the edit it was told had
        // failed. Refusing here leaves the previous generation whole and the parts this commit
        // wrote unreferenced, which the cleanup below unlinks and the next open would sweep anyway.
        val bytes = referencedBytes(designDirectory, header)
        if (bytes > limits.maximumDesignBytes) {
          written.forEach { runCatching { Files.deleteIfExists(it) } }
          throw UiBuilderPersistenceException(
            "UI-builder design $designId is $bytes bytes; limit is ${limits.maximumDesignBytes}"
          )
        }
        writeHeader(designDirectory, header)
        Files.deleteIfExists(designDirectory.resolve(QUARANTINE_FILE))
        sweep(designDirectory, header)
        storedBytes += bytes - (current?.bytes ?: 0)
        files[designId] = DesignFiles(header, bytes)
      } catch (failure: UiBuilderPersistenceException) {
        throw failure
      } catch (failure: IOException) {
        written.forEach { runCatching { Files.deleteIfExists(it) } }
        throw UiBuilderPersistenceException(
          "cannot store UI-builder design $designId under $designDirectory",
          failure,
        )
      }
    }
  }

  override fun remove(designId: String) {
    locked {
      // A quarantined design's id came out of a header this build could not otherwise read, so its
      // directory is the one recorded at load rather than one derived from the id.
      val designDirectory =
        designsDirectory.resolve(quarantinedSlugs.remove(designId) ?: slug(designId))
      val bytes = files.remove(designId)?.bytes ?: directoryBytes(designDirectory)
      storedBytes -= bytes
      if (storedBytes < 0) storedBytes = 0
      try {
        deleteRecursively(designDirectory)
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException(
          "cannot remove UI-builder design $designId at $designDirectory",
          failure,
        )
      }
    }
  }

  override fun usage(): UiBuilderStorageUsage =
    UiBuilderStorageUsage(storedBytes, limits.maximumBytes)

  // ---------------------------------------------------------------- reading

  private fun designSlugs(): List<String> {
    if (!Files.isDirectory(designsDirectory)) return emptyList()
    val slugs = mutableListOf<String>()
    Files.newDirectoryStream(designsDirectory).use { entries ->
      entries.forEach { if (Files.isDirectory(it)) slugs.add(it.fileName.toString()) }
    }
    return slugs.sorted()
  }

  private fun readHeader(designDirectory: Path): StoredDesignHeaderV3 =
    decodeChecked(designDirectory.resolve(HEADER_FILE), "header")

  private fun readDesign(
    designDirectory: Path,
    header: StoredDesignHeaderV3,
  ): PersistedDesignV1 {
    val document: DesignDocumentV1 =
      decodeChecked(designDirectory.resolve(header.documentFile), "document")
    val positions: StoredPositionsV3 =
      decodeChecked(designDirectory.resolve(header.positionsFile), "positions")
    val revisions =
      header.revisionFiles.entries
        .sortedBy { it.key.toLongOrNull() ?: 0L }
        .map { (_, file) ->
          decodeChecked<StoredRevisionV3>(designDirectory.resolve(file), "revision")
        }
    val journal = replayJournal(designDirectory, header)
    return PersistedDesignV1(
      document = document,
      lastSequence = header.lastSequence,
      access = header.access,
      history = journal.history,
      revisionSnapshots =
        revisions.mapNotNull { retained ->
          retained.document?.let { RevisionStateV1(it, retained.sequence ?: header.lastSequence) }
        },
      operationOutcomes = journal.outcomes,
      acceptedOperations = journal.accepted,
      tombstones = journal.tombstones,
      positions = positions.positions,
      positionSnapshots =
        revisions.mapNotNull { retained ->
          retained.positions?.let { PositionStateV1(retained.revision, it) }
        },
      createdAtEpochMillis = header.createdAtEpochMillis,
      updatedAtEpochMillis = header.updatedAtEpochMillis,
      audit = journal.audit,
    )
  }

  private data class JournalState(
    val history: List<CommittedOperationV1> = emptyList(),
    val audit: List<AuditRecordV1> = emptyList(),
    val outcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
    val accepted: Map<String, AcceptedOperationRecordV1> = emptyMap(),
    val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
  )

  private fun replayJournal(
    designDirectory: Path,
    header: StoredDesignHeaderV3,
  ): JournalState {
    val file = header.journalFile ?: return JournalState()
    val path = designDirectory.resolve(file)
    if (!Files.exists(path)) {
      throw UiBuilderPersistenceException("UI-builder journal $file is missing")
    }
    val stored = Files.readAllBytes(path)
    if (stored.size < header.journalBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder journal $file is ${stored.size} bytes; the header commits to ${header.journalBytes}"
      )
    }
    val committed = stored.copyOf(header.journalBytes.toInt()).decodeToString()
    var history = emptyList<CommittedOperationV1>()
    var audit = emptyList<AuditRecordV1>()
    var outcomes = emptyMap<String, OperationOutcomeRecordV1>()
    var accepted = emptyMap<String, AcceptedOperationRecordV1>()
    var tombstones = emptyMap<String, NodeTreeSnapshotV1>()
    committed
      .lineSequence()
      .filter { it.isNotBlank() }
      .forEach { line ->
        val stored =
          try {
            journalJson.decodeFromString(JournalLineV3.serializer(), line)
          } catch (failure: Exception) {
            throw UiBuilderPersistenceException(
              "invalid UI-builder journal record in $file: ${failure.message}",
              failure,
            )
          }
        if (sha256(canonicalJson(stored.entry).encodeToByteArray()) != stored.checksumSha256) {
          throw UiBuilderPersistenceException(
            "UI-builder journal record checksum mismatch in $file"
          )
        }
        val entry =
          try {
            journalJson.decodeFromJsonElement(JournalEntryV3.serializer(), stored.entry)
          } catch (failure: Exception) {
            throw UiBuilderPersistenceException(
              "invalid UI-builder journal record in $file: ${failure.message}",
              failure,
            )
          }
        entry.historySet?.let { history = it }
        entry.historyAppend?.let { appended ->
          history = (history + appended).let { it.takeLast(entry.historyKeep ?: it.size) }
        }
        entry.auditSet?.let { audit = it }
        entry.auditAppend?.let { appended ->
          audit = (audit + appended).let { it.takeLast(entry.auditKeep ?: it.size) }
        }
        entry.outcomesPut?.let { outcomes = outcomes + it }
        entry.outcomesRemoved?.let { removed -> outcomes = outcomes - removed.toSet() }
        entry.acceptedPut?.let { accepted = accepted + it }
        entry.acceptedRemoved?.let { removed -> accepted = accepted - removed.toSet() }
        entry.tombstonesPut?.let { tombstones = tombstones + it }
        entry.tombstonesRemoved?.let { removed -> tombstones = tombstones - removed.toSet() }
      }
    return JournalState(history, audit, outcomes, accepted, tombstones)
  }

  private inline fun <reified T> decodeChecked(path: Path, description: String): T {
    if (!Files.exists(path)) {
      throw UiBuilderPersistenceException("UI-builder $description ${path.fileName} is missing")
    }
    val encoded =
      try {
        Files.readAllBytes(path).decodeToString()
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException("cannot read UI-builder $description at $path", failure)
      }
    val root =
      try {
        json.parseToJsonElement(encoded).jsonObject
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException(
          "invalid UI-builder $description JSON at $path",
          failure,
        )
      }
    // The checksum covers the payload AS STORED — this parsed tree — and never a re-encode of the
    // decoded value, which is the discipline `PersistentUiBuilderService.decode` documents and the
    // reason a defaulted field arriving in the model does not make every stored file unreadable.
    val payload =
      root[PAYLOAD_FIELD]
        ?: throw UiBuilderPersistenceException("UI-builder $description payload is missing")
    val expected =
      (root[CHECKSUM_FIELD] as? JsonPrimitive)?.content
        ?: throw UiBuilderPersistenceException("UI-builder $description checksum is missing")
    if (sha256(canonicalJson(payload).encodeToByteArray()) != expected) {
      throw UiBuilderPersistenceException("UI-builder $description checksum mismatch at $path")
    }
    return try {
      json.decodeFromJsonElement(payload)
    } catch (failure: Exception) {
      throw UiBuilderPersistenceException("invalid UI-builder $description at $path", failure)
    }
  }

  // ---------------------------------------------------------------- writing

  private data class JournalWrite(val file: String, val bytes: Long, val compactedBytes: Long)

  private fun writeRevisions(
    designDirectory: Path,
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
    previousFiles: Map<String, String>,
    onWritten: (Path) -> Unit,
  ): Map<String, String> {
    val unchanged =
      previous != null &&
        previous.revisionSnapshots == next.revisionSnapshots &&
        previous.positionSnapshots == next.positionSnapshots
    if (unchanged) return previousFiles
    val documents = next.revisionSnapshots.associateBy { it.document.revision }
    val positions = next.positionSnapshots.associateBy { it.revision }
    val revisions = (documents.keys + positions.keys).sorted()
    val previousDocuments =
      previous?.revisionSnapshots?.associateBy { it.document.revision }.orEmpty()
    val previousPositions = previous?.positionSnapshots?.associateBy { it.revision }.orEmpty()
    val written = linkedMapOf<String, String>()
    for (revision in revisions) {
      val key = revision.toString()
      val retainedDocument = documents[revision]
      val retainedPositions = positions[revision]
      val same =
        previousDocuments[revision] == retainedDocument &&
          previousPositions[revision] == retainedPositions
      val existing = previousFiles[key]
      if (same && existing != null) {
        written[key] = existing
        continue
      }
      val stored =
        StoredRevisionV3(
          revision = revision,
          sequence = retainedDocument?.sequence,
          document = retainedDocument?.document,
          positions = retainedPositions?.positions,
        )
      val name =
        writePart(
          designDirectory,
          "$REVISIONS_DIRECTORY/$revision",
          json.encodeToJsonElement(stored),
        )
      onWritten(designDirectory.resolve(name))
      written[key] = name
    }
    return written
  }

  /**
   * The delta this commit adds to the journal, or null when none of those collections changed.
   *
   * A list that grew by appending records the tail and the length to keep, so a trim that dropped
   * entries from the front replays exactly. A list that was replaced outright — an asset write
   * clears `history` — records itself whole, because there is no append that describes it.
   */
  private fun journalEntry(
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
  ): JournalEntryV3? {
    val historyTail =
      if (previous != null && previous.history == next.history) null
      else appendedTail(previous?.history.orEmpty(), next.history)
    val auditTail =
      if (previous != null && previous.audit == next.audit) null
      else appendedTail(previous?.audit.orEmpty(), next.audit)
    val outcomes = mapDelta(previous?.operationOutcomes.orEmpty(), next.operationOutcomes)
    val accepted = mapDelta(previous?.acceptedOperations.orEmpty(), next.acceptedOperations)
    val tombstones = mapDelta(previous?.tombstones.orEmpty(), next.tombstones)
    val historyChanged = previous == null || previous.history != next.history
    val auditChanged = previous == null || previous.audit != next.audit
    if (
      !historyChanged && !auditChanged && outcomes == null && accepted == null && tombstones == null
    ) {
      return null
    }
    return JournalEntryV3(
      historyAppend = if (historyChanged) historyTail else null,
      historyKeep = if (historyChanged && historyTail != null) next.history.size else null,
      historySet = if (historyChanged && historyTail == null) next.history else null,
      auditAppend = if (auditChanged) auditTail else null,
      auditKeep = if (auditChanged && auditTail != null) next.audit.size else null,
      auditSet = if (auditChanged && auditTail == null) next.audit else null,
      outcomesPut = outcomes?.first,
      outcomesRemoved = outcomes?.second,
      acceptedPut = accepted?.first,
      acceptedRemoved = accepted?.second,
      tombstonesPut = tombstones?.first,
      tombstonesRemoved = tombstones?.second,
    )
  }

  private fun <T> appendedTail(previous: List<T>, next: List<T>): List<T>? {
    // Only a short tail is worth searching for: every mutation appends one record, and a commit
    // that changed a list any other way is cheaper to write out than to describe.
    for (size in 0..minOf(MAXIMUM_APPENDED_TAIL, next.size)) {
      val tail = next.takeLast(size)
      val replayed = (previous + tail).takeLast(next.size)
      if (replayed == next) return tail
    }
    return null
  }

  private fun <V> mapDelta(
    previous: Map<String, V>,
    next: Map<String, V>,
  ): Pair<Map<String, V>, List<String>>? {
    if (previous == next) return null
    val put = next.filter { (key, value) -> previous[key] != value }
    val removed = previous.keys.filter { it !in next }
    return put to removed
  }

  private fun shouldCompact(journalBytes: Long, compactedBytes: Long): Boolean =
    journalBytes > limits.journalCompactionBytes &&
      journalBytes > compactedBytes * limits.journalCompactionRatio

  private fun compactJournal(
    designDirectory: Path,
    next: PersistedDesignV1,
    generation: Int,
  ): JournalWrite {
    val entry =
      JournalEntryV3(
        historySet = next.history,
        auditSet = next.audit,
        outcomesPut = next.operationOutcomes.takeIf { it.isNotEmpty() },
        acceptedPut = next.acceptedOperations.takeIf { it.isNotEmpty() },
        tombstonesPut = next.tombstones.takeIf { it.isNotEmpty() },
      )
    val line = journalLine(entry)
    val name = "$JOURNAL_PREFIX$generation$JOURNAL_SUFFIX"
    val temporary = writeTemporary(designDirectory, name, line)
    replaceAtomically(temporary, designDirectory.resolve(name))
    return JournalWrite(name, line.size.toLong(), line.size.toLong())
  }

  private fun appendJournal(
    designDirectory: Path,
    file: String,
    committedBytes: Long,
    entry: JournalEntryV3,
    compactedBytes: Long,
  ): JournalWrite {
    val path = designDirectory.resolve(file)
    val line = journalLine(entry)
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
      // A previous commit may have appended and died before its header landed. Its records are
      // beyond the committed length, and this one overwrites them: the header is the commit.
      channel.truncate(committedBytes)
      channel.position(committedBytes)
      val buffer = ByteBuffer.wrap(line)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
    return JournalWrite(file, committedBytes + line.size, compactedBytes)
  }

  /** One record, checksummed over the tree that is written beside the number. */
  private fun journalLine(entry: JournalEntryV3): ByteArray {
    val payload = journalJson.encodeToJsonElement(JournalEntryV3.serializer(), entry)
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    return (journalJson.encodeToString(
        JournalLineV3.serializer(),
        JournalLineV3(checksum, payload),
      ) + "\n")
      .encodeToByteArray()
  }

  private fun writePart(designDirectory: Path, part: String, payload: JsonElement): String {
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    val name = "$part-${checksum.take(DIGEST_NAME_LENGTH)}$PART_SUFFIX"
    val path = designDirectory.resolve(name)
    // Deliberately written even when a file of that name is already there. The name says what the
    // content should be, not that the bytes on disk still are it — and the one moment that
    // distinction matters is the one that matters most: re-committing a design whose file was
    // corrupted in place is how it is repaired.
    Files.createDirectories(path.parent)
    val bytes =
      json.encodeToString(StoredPartSerializer, StoredPart(checksum, payload)).encodeToByteArray()
    val temporary = writeTemporary(path.parent, path.fileName.toString(), bytes)
    replaceAtomically(temporary, path)
    return name
  }

  private fun writeHeader(designDirectory: Path, header: StoredDesignHeaderV3) {
    val payload = json.encodeToJsonElement(header)
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    val bytes =
      json.encodeToString(StoredPartSerializer, StoredPart(checksum, payload)).encodeToByteArray()
    val temporary = writeTemporary(designDirectory, HEADER_FILE, bytes)
    replaceAtomically(temporary, designDirectory.resolve(HEADER_FILE))
    forceDirectory(designDirectory)
  }

  private fun writeMarker(marker: StoreMarkerV3) {
    val bytes = json.encodeToString(StoreMarkerV3.serializer(), marker).encodeToByteArray()
    val temporary = writeTemporary(directory, STORE_FILE, bytes)
    replaceAtomically(temporary, markerFile)
    forceDirectory(directory)
  }

  private fun readMarker() {
    val marker =
      try {
        json.decodeFromString(
          StoreMarkerV3.serializer(),
          Files.readAllBytes(markerFile).decodeToString(),
        )
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException(
          "invalid UI-builder store marker at $markerFile",
          failure,
        )
      }
    if (marker.format != STORE_FORMAT) {
      throw UiBuilderPersistenceException(
        "unsupported UI-builder store format ${marker.format} at $markerFile"
      )
    }
  }

  /** Unlinks the files in one design's directory that its header does not name. */
  private fun sweep(designDirectory: Path, header: StoredDesignHeaderV3) {
    val referenced = buildSet {
      add(HEADER_FILE)
      add(QUARANTINE_FILE)
      add(header.documentFile)
      add(header.positionsFile)
      header.journalFile?.let { add(it) }
      addAll(header.revisionFiles.values)
    }
    walkFiles(designDirectory).forEach { path ->
      val name = designDirectory.relativize(path).toString().replace('\\', '/')
      if (name !in referenced) runCatching { Files.deleteIfExists(path) }
    }
  }

  // ---------------------------------------------------------------- quarantine

  private fun readQuarantine(designDirectory: Path): Pair<String, String>? {
    val path = designDirectory.resolve(QUARANTINE_FILE)
    if (!Files.exists(path)) return null
    val record = runCatching {
      json.decodeFromString(
        StoredQuarantineV3.serializer(),
        Files.readAllBytes(path).decodeToString(),
      )
    }
      .getOrNull()
    val designId =
      runCatching { readHeader(designDirectory).designId }.getOrNull()
        ?: designDirectory.fileName.toString()
    return designId to (record?.reason ?: "quarantined")
  }

  private fun quarantineDesignId(designDirectory: Path, slug: String): String =
    runCatching { readHeader(designDirectory).designId }.getOrNull()
      ?: runCatching {
        json
          .parseToJsonElement(
            Files.readAllBytes(designDirectory.resolve(HEADER_FILE)).decodeToString()
          )
          .jsonObject[PAYLOAD_FIELD]
          ?.jsonObject
          ?.get("designId")
          ?.let { (it as? JsonPrimitive)?.content }
      }
        .getOrNull()
      ?: slug

  private fun writeQuarantine(designDirectory: Path, designId: String, reason: String) {
    runCatching {
      val bytes =
        json
          .encodeToString(
            StoredQuarantineV3.serializer(),
            StoredQuarantineV3(reason, System.currentTimeMillis()),
          )
          .encodeToByteArray()
      val temporary = writeTemporary(designDirectory, QUARANTINE_FILE, bytes)
      replaceAtomically(temporary, designDirectory.resolve(QUARANTINE_FILE))
    }
  }

  // ---------------------------------------------------------------- migration

  /**
   * Writes a v2 single-file state out as this tree, once.
   *
   * `store.json` present means the store is already v3 and the old file is ignored. The old file is
   * renamed rather than deleted: it is the rollback, and `--ui-builder-state-dir` pointing at a
   * copy of it is the recovery path an operator has.
   */
  private fun migrateLegacyStateIfPresent() {
    if (Files.exists(markerFile)) return
    val legacyFile = directory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    if (!Files.exists(legacyFile)) return
    val decoded = LegacyUiBuilderState.decode(Files.readAllBytes(legacyFile))
    decoded.designs.forEach { (designId, design) -> commitUnlocked(designId, null, design) }
    writeMarker(StoreMarkerV3(STORE_FORMAT, migratedFrom = decoded.format.wire))
    Files.move(
      legacyFile,
      directory.resolve(FileUiBuilderStateStorage.STATE_FILE + MIGRATED_SUFFIX),
      StandardCopyOption.REPLACE_EXISTING,
    )
  }

  private fun commitUnlocked(
    designId: String,
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
  ) {
    // The migration runs inside `load`'s lock, and `commit` takes the same non-reentrant file lock.
    val designDirectory = designsDirectory.resolve(slug(designId))
    Files.createDirectories(designDirectory)
    val documentFile =
      writePart(designDirectory, DOCUMENT_PART, json.encodeToJsonElement(next.document))
    val positionsFile =
      writePart(
        designDirectory,
        POSITIONS_PART,
        json.encodeToJsonElement(StoredPositionsV3(next.positions)),
      )
    val revisionFiles = writeRevisions(designDirectory, previous, next, emptyMap()) {}
    val journal = compactJournal(designDirectory, next, generation = 1)
    writeHeader(
      designDirectory,
      StoredDesignHeaderV3(
        designId = designId,
        title = next.document.title,
        revision = next.document.revision,
        lastSequence = next.lastSequence,
        access = next.access,
        catalogPin = next.document.catalogPin,
        createdAtEpochMillis = next.createdAtEpochMillis,
        updatedAtEpochMillis = next.updatedAtEpochMillis,
        documentFile = documentFile,
        positionsFile = positionsFile,
        revisionFiles = revisionFiles,
        journalFile = journal.file,
        journalBytes = journal.bytes,
        journalCompactedBytes = journal.compactedBytes,
      ),
    )
  }

  // ---------------------------------------------------------------- files

  private fun writeTemporary(directory: Path, name: String, value: ByteArray): Path {
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".$name.", ".tmp")
    try {
      FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(value)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      return temporary
    } catch (failure: Throwable) {
      Files.deleteIfExists(temporary)
      throw failure
    }
  }

  private fun replaceAtomically(source: Path, target: Path) {
    Files.createDirectories(target.parent)
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun walkFiles(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    val paths = mutableListOf<Path>()
    Files.walk(directory).use { stream ->
      stream.forEach { if (Files.isRegularFile(it)) paths.add(it) }
    }
    return paths
  }

  private fun directoryBytes(directory: Path): Long =
    walkFiles(directory).sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

  /**
   * What the design would cost once [header] is current: the parts it names, and nothing else.
   *
   * Not the directory's size, which at this point still holds the generation about to be swept and
   * would refuse a commit for bytes that are on their way out.
   */
  private fun referencedBytes(designDirectory: Path, header: StoredDesignHeaderV3): Long {
    val parts = buildList {
      add(header.documentFile)
      add(header.positionsFile)
      header.journalFile?.let { add(it) }
      addAll(header.revisionFiles.values)
    }
    return parts.sumOf { name ->
      runCatching { Files.size(designDirectory.resolve(name)) }.getOrDefault(0L)
    }
  }

  private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  private fun forceDirectory(path: Path) {
    try {
      FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
      // Some file systems cannot open a directory. The files themselves were already forced.
    }
  }

  private fun <T> locked(block: () -> T): T {
    Files.createDirectories(directory)
    return try {
      FileChannel.open(
          lockFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
        )
        .use { channel -> channel.lock().use { block() } }
    } catch (failure: UiBuilderPersistenceException) {
      throw failure
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException("cannot lock UI-builder state at $lockFile", failure)
    }
  }

  internal companion object {
    const val STORE_FILE: String = "store.json"
    const val STORE_FORMAT: String = "ui-builder-store-v3"
    const val DESIGNS_DIRECTORY: String = "designs"
    const val HEADER_FILE: String = "design.json"
    const val QUARANTINE_FILE: String = "quarantine.json"
    const val REVISIONS_DIRECTORY: String = "revisions"
    const val MIGRATED_SUFFIX: String = ".migrated"
    private const val LOCK_FILE = ".ui-builder-service.lock"
    private const val DOCUMENT_PART = "document"
    private const val POSITIONS_PART = "positions"
    private const val PART_SUFFIX = ".json"
    private const val JOURNAL_PREFIX = "journal-"
    private const val JOURNAL_SUFFIX = ".jsonl"
    private const val PAYLOAD_FIELD = "payload"
    private const val CHECKSUM_FIELD = "checksumSha256"
    private const val DIGEST_NAME_LENGTH = 16
    private const val MAXIMUM_APPENDED_TAIL = 4

    private val json = Json { encodeDefaults = true }
    private val journalJson = Json { encodeDefaults = false }

    /** `<part>-<digest>` for a design directory: the id is not promised to be filename-safe. */
    fun slug(designId: String): String = sha256(designId.encodeToByteArray()).take(32)

    fun journalGeneration(file: String): Int =
      file.removePrefix(JOURNAL_PREFIX).removeSuffix(JOURNAL_SUFFIX).toIntOrNull() ?: 1
  }
}

@Serializable private data class StoredPart(val checksumSha256: String, val payload: JsonElement)

private val StoredPartSerializer = StoredPart.serializer()
