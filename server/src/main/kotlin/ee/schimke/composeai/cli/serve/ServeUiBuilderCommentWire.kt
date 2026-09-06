package ee.schimke.composeai.cli.serve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The discussion attached to one design, on the wire and on disk.
 *
 * One shape for both, for the reason [StoredReference] gives: the file *is* the response body, and
 * translating between two identical shapes buys nothing. Its mirror in the editor (`:ui-builder`'s
 * wasm host) is a separate declaration decoding the same JSON leniently, because the two modules
 * cannot share a type and a tolerant client is what lets this payload gain a field without blanking
 * somebody's comment panel mid-release.
 *
 * ### Why the whole board is the delta
 *
 * [sequence] rises by one on every accepted write, and a reader that quotes the sequence it last
 * saw is answered with the whole board rather than the threads that changed since. That is a
 * deliberate choice and not laziness: a design's discussion is a few kilobytes of text, replaying
 * it costs less than the bookkeeping a per-thread log would need, and a client that has just
 * reconnected after a nap gets one answer that is correct rather than a window it may have fallen
 * out of. The design document's own event log makes the opposite trade for the opposite reason — it
 * is replayed into a reducer, and it is large.
 */
@Serializable
data class StoredCommentBoard(
  @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
  val designId: String,
  /**
   * Monotonic per design, never reused, and the only thing a watcher has to remember.
   *
   * Rises on every accepted write — a comment, an edit, a resolve, a delete — so "has anything
   * changed" is one comparison rather than a diff of the threads.
   */
  val sequence: Long = 0,
  val threads: List<StoredCommentThread> = emptyList(),
  val updatedAtEpochMillis: Long = 0,
) {
  companion object {
    const val SCHEMA_VERSION: Int = 1
  }
}

/**
 * One conversation, and where on the design it is about.
 *
 * A thread rather than a flat list of comments because a discussion has replies, and because
 * "resolved" is a property of the question rather than of any one sentence in it.
 */
@Serializable
data class StoredCommentThread(
  val id: String,
  /** Where this is pinned, or null for a thread about the design as a whole. */
  val anchor: StoredCommentAnchor? = null,
  val resolved: Boolean = false,
  /** Who resolved it, and when. Null while it is open. */
  val resolvedBy: String? = null,
  val resolvedAtEpochMillis: Long? = null,
  val createdAtEpochMillis: Long = 0,
  val updatedAtEpochMillis: Long = 0,
  /**
   * The board [StoredCommentBoard.sequence] this thread last *said* something at.
   *
   * The wall clock next to it is for a person reading the panel; this is what [acknowledgedBy] is
   * compared against, because acknowledgement has to be exact. A millisecond comparison would call
   * a reply acknowledged whenever it landed inside the same millisecond as the acknowledgement,
   * which is rare, silent and exactly the failure this whole field exists to stop.
   *
   * Only what somebody *said* moves it — a comment, a resolve, a reopen. An acknowledgement and a
   * reaction deliberately do not: both are one actor's own bookkeeping, and bumping this would make
   * an agent's `eyes` on a thread read to every other actor as new activity to catch up on.
   *
   * A thread stored before this field existed carries 0, which is below every acknowledgement and
   * so reads as unacknowledged. That is the safe direction: an old thread resurfaces once rather
   * than being silently marked as seen by somebody who never saw it.
   */
  val updatedAtSequence: Long = 0,
  /**
   * Per actor, the board sequence at which they last acknowledged this thread.
   *
   * Acknowledgement is **per actor and is not resolution**: it says "I have read this", not "this
   * is settled", and the two are different claims. An agent that resolves a thread it has not fixed
   * is lying; an agent that stays silent is invisible. This is the third answer.
   *
   * Writing into a thread acknowledges it for the writer, so posting a reply, resolving, or
   * reacting never leaves an actor being nagged about their own words.
   */
  val acknowledgedBy: Map<String, Long> = emptyMap(),
  val comments: List<StoredComment> = emptyList(),
) {
  /** Whether [actorId] has yet to catch up with what was said here. */
  fun isUnacknowledgedBy(actorId: String): Boolean =
    (acknowledgedBy[actorId] ?: -1L) < updatedAtSequence
}

/**
 * One thing somebody said.
 *
 * [authorId] is the authenticated actor, assigned by the host. A client's proposal is overwritten
 * rather than trusted, exactly as [StoredReferenceImage.id] is — the whole value of a discussion
 * between a person and an agent is that each line is attributed to whoever actually wrote it.
 */
@Serializable
data class StoredComment(
  val id: String,
  val authorId: String,
  /**
   * What the author's name reads as in the panel. Client-supplied and cosmetic — [authorId] is the
   * identity, and this is the label beside it.
   */
  val displayName: String = "",
  /**
   * `human` or `agent`, as declared by the caller.
   *
   * Cosmetic in exactly the same way: it decides an icon, never a permission. The host cannot tell
   * a person's browser from an agent's MCP session by the credential alone — both are grants — so
   * asking is more honest than guessing, and nothing downstream depends on the answer.
   */
  val authorKind: String = AUTHOR_KIND_HUMAN,
  val body: String,
  val createdAtEpochMillis: Long = 0,
  val editedAtEpochMillis: Long? = null,
  /**
   * Emoji to the actors who reacted with it, oldest first.
   *
   * A map rather than a list of rows because that is how a panel draws it — one chip per emoji with
   * a count and a tooltip of who — and because it makes a second reaction from the same actor
   * idempotent by construction.
   *
   * Reactions exist for the large class of answers that do not deserve a reply: a person 👍-ing a
   * fix, an agent 👀-ing a comment it has just picked up. A reply to say either would be noise in a
   * thread somebody has to read.
   */
  val reactions: Map<String, List<String>> = emptyMap(),
) {
  companion object {
    const val AUTHOR_KIND_HUMAN: String = "human"
    const val AUTHOR_KIND_AGENT: String = "agent"

    val KNOWN_AUTHOR_KINDS: Set<String> = setOf(AUTHOR_KIND_HUMAN, AUTHOR_KIND_AGENT)
  }
}

/**
 * Where a thread is pinned.
 *
 * Three ways of saying it, and a thread may use more than one at once because they answer different
 * questions. [markId] ties the discussion to a stroke somebody drew on the reference — the point of
 * linking comments to markup at all: "this arrow, why?" is a sentence that needs the arrow.
 * [nodeId] ties it to a node in the design, so it survives the reference being replaced. [x] and
 * [y] are frame fractions, the same coordinate space [StoredReferenceMark.points] uses, so a pin
 * lands in the same place on a phone frame and on the tablet the operator switches to.
 */
@Serializable
data class StoredCommentAnchor(
  val markId: String? = null,
  val nodeId: String? = null,
  val x: Float? = null,
  val y: Float? = null,
) {
  val isEmpty: Boolean
    get() = markId == null && nodeId == null && x == null && y == null

  /**
   * On the frame, or null where the point is unusable.
   *
   * Both coordinates or neither: a pin with one of them is a pin nobody can draw, and silently
   * filling the other with zero would put it in a corner the author never chose.
   */
  fun sanitized(): StoredCommentAnchor? {
    val point =
      if (x != null && y != null && x.isFinite() && y.isFinite()) {
        x.coerceIn(-1f, 2f) to y.coerceIn(-1f, 2f)
      } else null
    val cleaned =
      StoredCommentAnchor(
        markId = markId?.take(MAX_ANCHOR_ID)?.takeIf { it.isNotBlank() },
        nodeId = nodeId?.take(MAX_ANCHOR_ID)?.takeIf { it.isNotBlank() },
        x = point?.first,
        y = point?.second,
      )
    return if (cleaned.isEmpty) null else cleaned
  }
}

/** The request body for `POST …/comments`: one comment, into a new thread or an existing one. */
@Serializable
data class CommentPostRequest(
  /** The thread to reply to. Null starts one, which is what [anchor] is for. */
  val threadId: String? = null,
  val anchor: StoredCommentAnchor? = null,
  val body: String,
  val displayName: String = "",
  val authorKind: String = StoredComment.AUTHOR_KIND_HUMAN,
)

/** The request body for `POST …/comments/{threadId}/resolution`. */
@Serializable data class CommentResolutionRequest(val resolved: Boolean)

/**
 * The request body for `POST …/comments/{threadId}/{commentId}/reactions`.
 *
 * [on] false takes the reaction back, so one route both adds and removes and a client does not have
 * to remember which verb removes a thing it added with a `POST`.
 */
@Serializable data class CommentReactionRequest(val reaction: String, val on: Boolean = true)

/** What a refusal says, in the one shape every comment route answers errors in. */
@Serializable data class CommentErrorResponse(val message: String)

/** The ceiling on one comment. Long enough for a paragraph of review, short of an essay. */
const val MAX_COMMENT_BODY: Int = 4000

/** The ceiling on a display name; the label beside an author, not a document. */
const val MAX_COMMENT_DISPLAY_NAME: Int = 80

/**
 * The ceiling on one reaction.
 *
 * Long enough for any emoji a client sends — a family with four skin tones and joiners is around a
 * dozen code units — and far too short for a sentence somebody is trying to smuggle past the reply
 * route's own limits.
 */
const val MAX_COMMENT_REACTION: Int = 24

private const val MAX_ANCHOR_ID = 200
