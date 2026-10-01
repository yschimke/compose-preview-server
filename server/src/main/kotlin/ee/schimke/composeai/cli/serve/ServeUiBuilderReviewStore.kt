package ee.schimke.composeai.cli.serve

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The review side of a design: who approved or rejected which revision, and the pull request that
 * implements it (compose-preview-server#1255).
 *
 * ## Why this is not in the design document, and not in the links record
 *
 * Not in the document for the reasons [ServeUiBuilderLinksStore] gives — a verdict is a fact
 * *about* a revision, never content of one, and recording it must not move the revision it is
 * about. Not in the links record because that record's shape is published (`DesignLinksV1`, in
 * `compose-preview-contracts`) and is five plain URLs; an implementation link here carries a
 * status, the revision it implements and whether its rendered previews were found to match, and
 * decisions are a log rather than a field. A second small file per design is cheaper than a
 * contracts release for something only this host reads.
 *
 * ## What a decision is
 *
 * The minimal record that lets an agent stop waiting: who decided ([StoredDesignDecision.decidedBy]
 * and whether that was a person or an agent), when, which revision, `approve` or `reject`, and an
 * optional note. Every write moves the design's review [StoredDesignReview.sequence], which is the
 * cursor `ui_builder_await_decision` waits past — the comment board's model, so an agent holding a
 * conversation and an agent waiting for a verdict poll the same way.
 *
 * Decisions are idempotent by id: recording the same `decisionId` twice answers the first record,
 * so a retried call after a lost reply never records a second approval.
 */
class ServeUiBuilderReviewStore(private val root: Path) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder review root is not a directory: $root" }
  }

  private val lock = Any()
  private val subscribers = ConcurrentHashMap<String, MutableSet<(StoredDesignReview) -> Unit>>()

  /** Everything recorded about [designId]'s review, or null when nothing has been. */
  fun read(designId: String): StoredDesignReview? = readFile(fileFor(designId))

  fun readOrEmpty(designId: String): StoredDesignReview =
    read(designId) ?: StoredDesignReview(designId = designId)

  /**
   * Record [request] as [actorId]'s verdict on one revision.
   *
   * [deciderKind] is the caller's to state — from the credential, never from the request — so an
   * agent cannot record a person's approval.
   */
  fun decide(
    designId: String,
    actorId: String,
    deciderKind: String,
    request: DecisionRequest,
  ): ReviewWriteResult {
    val verdict = request.verdict.trim().lowercase()
    if (verdict !in VERDICTS) {
      return ReviewWriteResult.Refused("`verdict` must be one of ${VERDICTS.joinToString(", ")}")
    }
    if (request.revision < 0) return ReviewWriteResult.Refused("`revision` must not be negative")
    val note = request.note?.trim()?.ifEmpty { null }
    if (note != null && note.toByteArray(StandardCharsets.UTF_8).size > MAX_NOTE_BYTES) {
      return ReviewWriteResult.Refused("`note` must be under $MAX_NOTE_BYTES bytes")
    }
    val decisionId = request.decisionId?.trim()?.ifEmpty { null }
    if (decisionId != null && decisionId.length > MAX_ID_CHARS) {
      return ReviewWriteResult.Refused("`decisionId` must be at most $MAX_ID_CHARS characters")
    }
    return synchronized(lock) {
      val current = readOrEmpty(designId)
      if (decisionId != null) {
        current.decisions
          .firstOrNull { it.decisionId == decisionId }
          ?.let { existing ->
            val same =
              existing.decidedBy == actorId &&
                existing.revision == request.revision &&
                existing.verdict == verdict &&
                existing.note == note
            return@synchronized if (same) ReviewWriteResult.Stored(current, existing, replay = true)
            else
              ReviewWriteResult.Refused(
                "`decisionId` $decisionId already records a different decision; use a new id"
              )
          }
      }
      val sequence = current.sequence + 1
      val decision =
        StoredDesignDecision(
          decisionId = decisionId ?: UUID.randomUUID().toString(),
          sequence = sequence,
          revision = request.revision,
          verdict = verdict,
          note = note,
          decidedBy = actorId,
          deciderKind = deciderKind,
          displayName = request.displayName?.trim()?.take(MAX_ID_CHARS)?.ifEmpty { null },
          decidedAtEpochMillis = System.currentTimeMillis(),
        )
      val next =
        current.copy(
          sequence = sequence,
          // Oldest dropped first: the latest verdict on each revision is what anybody waits for,
          // and an unbounded log is how one design's file outgrows the read that serves it.
          decisions = (current.decisions + decision).takeLast(MAX_DECISIONS),
        )
      when (val written = write(next)) {
        null -> ReviewWriteResult.Stored(next, decision, replay = false)
        else -> written
      }
    }
  }

  /**
   * Replace the implementation record — the pull request, its status, the revision it implements
   * and whether its previews match — or clear it with a request carrying no pull request.
   *
   * A write that changes nothing does not move [StoredDesignReview.sequence], so a CI job that
   * reports the same status on every push does not wake every agent waiting on the design.
   */
  fun setImplementation(
    designId: String,
    actorId: String,
    request: ImplementationRequest?,
  ): ReviewWriteResult {
    val candidate = request?.let {
      val pr = it.pr?.trim()?.ifEmpty { null } ?: return@let null
      StoredImplementation(
        pr = pr,
        status = it.status?.trim()?.lowercase()?.ifEmpty { null } ?: STATUS_OPEN,
        revision = it.revision,
        previewMatch = it.previewMatch,
        updatedBy = actorId,
      )
    }
    if (candidate != null) {
      ServeUiBuilderLinksStore.refusal(StoredLinks(pr = candidate.pr))?.let {
        return ReviewWriteResult.Refused(it)
      }
      if (candidate.status !in IMPLEMENTATION_STATUSES) {
        return ReviewWriteResult.Refused(
          "`status` must be one of ${IMPLEMENTATION_STATUSES.joinToString(", ")}"
        )
      }
      if (candidate.revision != null && candidate.revision < 0) {
        return ReviewWriteResult.Refused("`revision` must not be negative")
      }
      candidate.previewMatch?.let { match ->
        if (match.status !in PREVIEW_MATCH_STATUSES) {
          return ReviewWriteResult.Refused(
            "`previewMatch.status` must be one of ${PREVIEW_MATCH_STATUSES.joinToString(", ")}"
          )
        }
        match.evidence?.let { evidence ->
          ServeUiBuilderLinksStore.refusal(StoredLinks(reference = evidence))?.let {
            return ReviewWriteResult.Refused(it.replace("`reference`", "`previewMatch.evidence`"))
          }
        }
        if ((match.note?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) > MAX_NOTE_BYTES) {
          return ReviewWriteResult.Refused(
            "`previewMatch.note` must be under $MAX_NOTE_BYTES bytes"
          )
        }
      }
    }
    return synchronized(lock) {
      val current = readOrEmpty(designId)
      val existing = current.implementation
      val unchanged =
        if (candidate == null) existing == null
        else
          existing != null &&
            existing.copy(updatedBy = candidate.updatedBy, updatedAtEpochMillis = 0) ==
              candidate.copy(updatedAtEpochMillis = 0)
      if (unchanged) return@synchronized ReviewWriteResult.Stored(current, null, replay = true)
      val next =
        current.copy(
          sequence = current.sequence + 1,
          implementation = candidate?.copy(updatedAtEpochMillis = System.currentTimeMillis()),
        )
      write(next) ?: ReviewWriteResult.Stored(next, null, replay = false)
    }
  }

  /**
   * The decisions recorded after [afterSequence] — on [revision] only, when it is given — as soon
   * as there is one, or null after [timeoutMillis] with none.
   *
   * Registers before it reads, as the comment store's wait does, so a decision recorded between the
   * read and the registration cannot be missed.
   */
  suspend fun awaitDecisionsAfter(
    designId: String,
    afterSequence: Long,
    revision: Long?,
    deciderKind: String?,
    timeoutMillis: Long,
  ): StoredDesignReview? {
    fun matches(review: StoredDesignReview) =
      review.decisions.any {
        it.sequence > afterSequence &&
          (revision == null || it.revision == revision) &&
          (deciderKind == null || it.deciderKind == deciderKind)
      }
    val waiter = CompletableDeferred<StoredDesignReview>()
    val subscription = subscribe(designId) { if (matches(it)) waiter.complete(it) }
    return try {
      val current = readOrEmpty(designId)
      if (matches(current)) return current
      if (timeoutMillis <= 0) return null
      try {
        withTimeout(timeoutMillis) { waiter.await() }
      } catch (_: TimeoutCancellationException) {
        null
      }
    } finally {
      subscription.close()
    }
  }

  /** Every accepted write on [designId], until the handle is closed. Listeners must not block. */
  fun subscribe(designId: String, listener: (StoredDesignReview) -> Unit): Closeable {
    val listeners =
      subscribers.computeIfAbsent(designId) {
        ConcurrentHashMap.newKeySet<(StoredDesignReview) -> Unit>()
      }
    listeners.add(listener)
    return Closeable { listeners.remove(listener) }
  }

  /**
   * Every design whose implementation record names [pr], in a stable order.
   *
   * A directory scan, for the reason [ServeUiBuilderLinksStore.citing] gives, and with the same
   * caveat: this answers what is stored, and the caller filters each id through a read of the
   * design as the asking actor before naming it.
   */
  fun implementedBy(pr: String): List<String> {
    val wanted = pr.trim().ifEmpty { null } ?: return emptyList()
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
      .filter { it.implementation?.pr == wanted && it.designId.isNotBlank() }
      .map { it.designId }
      .distinct()
      .sorted()
  }

  /** Forget [designId]'s review, as the design's own delete does. False when it could not. */
  fun delete(designId: String): Boolean =
    try {
      Files.deleteIfExists(fileFor(designId))
      true
    } catch (_: IOException) {
      false
    }

  private fun write(review: StoredDesignReview): ReviewWriteResult.Failed? {
    val encoded = REVIEW_JSON.encodeToString(StoredDesignReview.serializer(), review)
    try {
      val temporary = Files.createTempFile(root, "review", ".tmp")
      try {
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(temporary, fileFor(review.designId), StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
    } catch (_: IOException) {
      return ReviewWriteResult.Failed("the review record could not be written to disk")
    }
    subscribers[review.designId]?.forEach { listener -> runCatching { listener(review) } }
    return null
  }

  private fun readFile(file: Path): StoredDesignReview? {
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_FILE_BYTES) null
      else
        REVIEW_JSON.decodeFromString(
          StoredDesignReview.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /** Named by the digest of the id, as the links store names its files, so no id is a path. */
  private fun fileFor(designId: String): Path =
    root.resolve(
      MessageDigest.getInstance("SHA-256")
        .digest(designId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".json"
    )

  companion object {
    const val VERDICT_APPROVE = "approve"
    const val VERDICT_REJECT = "reject"
    val VERDICTS = listOf(VERDICT_APPROVE, VERDICT_REJECT)

    const val STATUS_OPEN = "open"
    val IMPLEMENTATION_STATUSES = listOf("draft", STATUS_OPEN, "merged", "closed")

    const val MATCH_UNKNOWN = "unknown"
    val PREVIEW_MATCH_STATUSES = listOf("match", "mismatch", MATCH_UNKNOWN)

    /** Enough history for a long review; the newest of each revision is what anybody reads. */
    const val MAX_DECISIONS = 50
    const val MAX_NOTE_BYTES = 2 * 1024
    const val MAX_ID_CHARS = 128
    private const val MAX_FILE_BYTES: Long = 256L * 1024

    private val REVIEW_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}

/** A verdict as a caller states it; who gave it comes from the credential. */
@Serializable
data class DecisionRequest(
  val revision: Long,
  val verdict: String,
  val note: String? = null,
  /** The caller's id for this decision; makes a retry idempotent. */
  val decisionId: String? = null,
  val displayName: String? = null,
)

/** An implementation record as a caller states it; a null [pr] clears the record. */
@Serializable
data class ImplementationRequest(
  val pr: String? = null,
  val status: String? = null,
  val revision: Long? = null,
  val previewMatch: StoredPreviewMatch? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StoredDesignReview(
  @EncodeDefault val schema: String = "compose-preview/ui-builder-design-review/v1",
  val designId: String,
  /** Rises on every accepted write; the cursor `ui_builder_await_decision` waits past. */
  val sequence: Long = 0,
  val decisions: List<StoredDesignDecision> = emptyList(),
  val implementation: StoredImplementation? = null,
)

@Serializable
data class StoredDesignDecision(
  val decisionId: String,
  val sequence: Long,
  /** The design revision this verdict is about. */
  val revision: Long,
  /** `approve` or `reject`. */
  val verdict: String,
  val note: String? = null,
  val decidedBy: String,
  /** `human` or `agent`, from the credential that recorded it. */
  val deciderKind: String,
  val displayName: String? = null,
  val decidedAtEpochMillis: Long,
)

@Serializable
data class StoredImplementation(
  /** The pull request implementing the design, as an absolute http(s) URL. */
  val pr: String,
  /** `draft`, `open`, `merged` or `closed`. */
  val status: String,
  /** The design revision the pull request implements, when known. */
  val revision: Long? = null,
  val previewMatch: StoredPreviewMatch? = null,
  val updatedBy: String,
  val updatedAtEpochMillis: Long = 0,
)

/**
 * Whether the pull request's rendered previews were found to match the design, as reported by
 * whoever compared them — this host cannot see a pull request's renders, so it records the answer
 * and where the evidence is rather than claiming to have checked.
 */
@Serializable
data class StoredPreviewMatch(
  /** `match`, `mismatch` or `unknown`. */
  val status: String,
  /** A link to the comparison: a preview-diff comment, a CI artifact. */
  val evidence: String? = null,
  val note: String? = null,
)

sealed interface ReviewWriteResult {
  /** [decision] is the one recorded (or replayed); null for an implementation write. */
  data class Stored(
    val review: StoredDesignReview,
    val decision: StoredDesignDecision?,
    val replay: Boolean,
  ) : ReviewWriteResult

  /** Not a record this host will keep; the same payload will be refused again. */
  data class Refused(val reason: String) : ReviewWriteResult

  /** The disk did not take an acceptable record; worth retrying. */
  data class Failed(val reason: String) : ReviewWriteResult
}

internal const val UI_BUILDER_DECISION_SCHEMA = "compose-preview/ui-builder-decision/v1"
internal const val UI_BUILDER_IMPLEMENTATION_SCHEMA = "compose-preview/ui-builder-implementation/v1"
internal const val UI_BUILDER_PR_LOOKUP_SCHEMA = "compose-preview/ui-builder-pr-designs/v1"

/**
 * `ui_builder_record_decision`'s and `ui_builder_await_decision`'s reply: a sentence, the cursor to
 * quote next, the latest matching verdict whether or not it is new, and the ones that are.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderDecisionReplyV1(
  @EncodeDefault val schema: String = UI_BUILDER_DECISION_SCHEMA,
  val summary: String,
  val designId: String,
  val timedOut: Boolean,
  /** Quote as `afterSequence` on the next wait. */
  val sequence: Long,
  /** True when [latest] approves. */
  val approved: Boolean,
  val latest: StoredDesignDecision? = null,
  /** The matching decisions after the cursor, oldest first. */
  val decisions: List<StoredDesignDecision> = emptyList(),
)

/** `ui_builder_implementation_status`'s reply: what the code side needs, in one call. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderImplementationStatusV1(
  @EncodeDefault val schema: String = UI_BUILDER_IMPLEMENTATION_SCHEMA,
  val summary: String,
  val designId: String,
  val revision: Long,
  val links: StoredLinks? = null,
  val implementation: StoredImplementation? = null,
  /** True when the implementation record names this revision (or names none). */
  val implementsRevision: Boolean,
  /** The latest verdict on this revision, when anybody gave one. */
  val latestDecision: StoredDesignDecision? = null,
  val export: UiBuilderImplementationExportV1? = null,
)

@Serializable
internal data class UiBuilderImplementationExportV1(
  val format: String,
  /** False when the generator refused something; [diagnostics] says what. */
  val ok: Boolean,
  val diagnostics: List<String> = emptyList(),
  /** The generated Kotlin. */
  val source: String? = null,
)

/** `ui_builder_find_design_for_pr`'s reply: the designs a pull request implements. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderPrLookupV1(
  @EncodeDefault val schema: String = UI_BUILDER_PR_LOOKUP_SCHEMA,
  val summary: String,
  val pr: String,
  val designs: List<UiBuilderPrDesignV1>,
)

@Serializable
internal data class UiBuilderPrDesignV1(
  val designId: String,
  /** The implementation record's status, when the record names this pull request. */
  val status: String? = null,
  val revision: Long? = null,
)
