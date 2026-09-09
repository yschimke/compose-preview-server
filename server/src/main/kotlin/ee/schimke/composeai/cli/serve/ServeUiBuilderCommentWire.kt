package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignCommentAnchorV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentBoardV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentThreadV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentV1
import kotlinx.serialization.Serializable

/**
 * The discussion attached to one design — [DesignCommentBoardV1] and its parts, published.
 *
 * The shapes moved to `compose-preview-contracts` with the rest of the sidecar family: a board is a
 * response body, a file on disk and an MCP payload at once. The aliases stay because the names say
 * what this server does with them, and the editor's mirror in `:ui-builder` still decodes the same
 * JSON leniently — the two modules cannot share a type, and a tolerant client is what lets this
 * payload gain a field without blanking somebody's comment panel mid-release.
 *
 * The JSON is unchanged: same field names, same `@SerialName`.
 *
 * The request bodies below did **not** move, and neither did the two questions this server asks of
 * a thread. A `POST` body is this host's API rather than a record anybody stores, and
 * [isUnacknowledgedBy] and [sanitized] are behaviour — the contracts module is shape and never
 * behaviour.
 */
typealias StoredCommentBoard = DesignCommentBoardV1

typealias StoredCommentThread = DesignCommentThreadV1

/** Whether [actorId] has yet to catch up with what was said here. */
fun StoredCommentThread.isUnacknowledgedBy(actorId: String): Boolean =
  (acknowledgedBy[actorId] ?: -1L) < updatedAtSequence

typealias StoredComment = DesignCommentV1

typealias StoredCommentAnchor = DesignCommentAnchorV1

val StoredCommentAnchor.isEmpty: Boolean
  get() = markId == null && nodeId == null && x == null && y == null

/**
 * On the frame, or null where the point is unusable.
 *
 * Both coordinates or neither: a pin with one of them is a pin nobody can draw, and silently
 * filling the other with zero would put it in a corner the author never chose.
 */
fun StoredCommentAnchor.sanitized(): StoredCommentAnchor? {
  val pinX = x
  val pinY = y
  val point =
    if (pinX != null && pinY != null && pinX.isFinite() && pinY.isFinite()) {
      pinX.coerceIn(-1f, 2f) to pinY.coerceIn(-1f, 2f)
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
