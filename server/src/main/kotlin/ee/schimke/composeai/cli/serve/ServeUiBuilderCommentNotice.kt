package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * What an agent is not being told, carried on the replies it is already reading.
 *
 * ## The failure this exists for
 *
 * A designer left "The play icon looks like a cross" on a design an agent was mid-way through
 * editing. The agent applied several more mutations and exported twice without seeing it, and only
 * found the comment when the person asked whether it had. Every reply in between —
 * `ui_builder_apply` outcomes, an export, a render — was a moment where the agent was demonstrably
 * reading the design's state, and every one of them was silent about a thread waiting on it.
 *
 * The tools to find it existed. The problem was that noticing was opt-in: an agent mid-edit has no
 * reason to poll a discussion it does not know has moved, and `ui_builder_await_comments` blocks,
 * which is the wrong shape for something that is in the middle of a different job.
 *
 * So the discussion is delivered where the agent is already looking, and the only cost on a design
 * nobody has commented on is a file read that answers "no board".
 *
 * ## Why the excerpt is load-bearing
 *
 * A bare count is easy to skip past — one number among the fields of an apply outcome. A quoted
 * sentence naming a node is not: it reads as somebody talking about the thing the agent has its
 * hands on. The excerpt is why this survives the agent that would otherwise ignore it, so it is the
 * part that is never dropped to save bytes.
 *
 * Bounded on purpose: a count, the cursor, and at most [MAX_NOTICE_THREADS] threads. A busy design
 * must not turn every apply outcome into a transcript, and `ui_builder_list_comments` is one call
 * away for the rest.
 */
@Serializable
internal data class CommentNoticeV1(
  val schema: String = "compose-preview/ui-builder-comment-notice/v1",
  /** How many threads this actor has not acknowledged. Never zero: the block is absent instead. */
  val unacknowledged: Int,
  /** The board's cursor, to quote to `ui_builder_await_comments` or to compare on the next call. */
  val sequence: Long,
  /** The most recently active of them, newest first, at most [MAX_NOTICE_THREADS]. */
  val threads: List<CommentNoticeThreadV1>,
  /** What to do about it, in the reply itself rather than only in a tool description. */
  val hint: String =
    "Somebody is talking to you about this design. Read it with ui_builder_list_comments, then " +
      "say you have seen it with ui_builder_acknowledge_comment (or react with " +
      "ui_builder_react_to_comment) — acknowledging is not resolving, and this block stays until " +
      "you do one of them.",
)

/** One waiting thread, in the space of a line. */
@Serializable
internal data class CommentNoticeThreadV1(
  val id: String,
  /** Who said the last thing in it: their display name where they gave one, else their actor id. */
  val author: String,
  /** The last thing said, trimmed to [MAX_COMMENT_EXCERPT] characters. */
  val excerpt: String,
  /** Where the thread is pinned, where it is — the node an agent can act on without asking. */
  val nodeId: String? = null,
  val markId: String? = null,
  val comments: Int = 1,
  /** A resolved thread that has moved since is still news; the flag says which kind. */
  val resolved: Boolean = false,
)

/**
 * The block for [actorId], or null when there is nothing they have not seen.
 *
 * Null rather than a zero count so the field is absent from a reply rather than present and empty:
 * an agent reading `"unacknowledged": 0` on every call learns to skip the key, which is how a
 * notice stops being noticed.
 */
internal fun StoredCommentBoard.noticeFor(actorId: String): CommentNoticeV1? {
  val waiting = threads.filter { it.isUnacknowledgedBy(actorId) && it.comments.isNotEmpty() }
  if (waiting.isEmpty()) return null
  return CommentNoticeV1(
    unacknowledged = waiting.size,
    sequence = sequence,
    threads =
      waiting
        .sortedByDescending { it.updatedAtSequence }
        .take(MAX_NOTICE_THREADS)
        .map { thread ->
          val last = thread.comments.last()
          CommentNoticeThreadV1(
            id = thread.id,
            author = last.displayName.ifBlank { last.authorId },
            excerpt = last.body.commentExcerpt(),
            nodeId = thread.anchor?.nodeId,
            markId = thread.anchor?.markId,
            comments = thread.comments.size,
            resolved = thread.resolved,
          )
        },
  )
}

/**
 * One line of what was said, with an ellipsis where the rest of it is.
 *
 * Shared with [ServeUiBuilderCommentWebhook] rather than reimplemented there, so a sentence quoted
 * into Slack and the same sentence quoted into an agent's reply are trimmed by one rule. Two rules
 * would drift, and the drift would be invisible: both outputs look right on their own.
 */
internal fun String.commentExcerpt(): String {
  val flattened = trim().replace(Regex("\\s+"), " ")
  return if (flattened.length <= MAX_COMMENT_EXCERPT) flattened
  else flattened.take(MAX_COMMENT_EXCERPT - 1).trimEnd() + "…"
}

/**
 * How many waiting threads a reply names, and how much of each.
 *
 * Three is the count at which the block is still a glance rather than a page, and a sentence is
 * enough to tell an agent whether the comment is about what it is doing right now.
 */
private const val MAX_NOTICE_THREADS = 3

/** How much of a sentence is quoted, wherever one is quoted. */
internal const val MAX_COMMENT_EXCERPT: Int = 160
