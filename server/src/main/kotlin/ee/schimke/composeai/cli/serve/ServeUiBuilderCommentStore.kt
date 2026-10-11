package ee.schimke.composeai.cli.serve

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The discussion attached to one design: threads pinned to a mark, node or point, and their
 * replies, between people and agents.
 *
 * Not in the design document, for the reasons [ServeUiBuilderReferenceStore] gives: it must never
 * reach the export; `DesignMutationV1` has no comment mutation; and a reply must not advance the
 * revision, invalidate clients' optimistic state or appear in catalog-upgrade diffs.
 *
 * A change feed rather than a poll: every accepted write bumps [StoredCommentBoard.sequence] and
 * wakes subscribers (the browser socket and MCP's [awaitBoardAfter]) through the same code. Losing
 * this directory loses discussion but no design content.
 */
class ServeUiBuilderCommentStore(
  private val root: Path,
  /**
   * How many designs may hold a discussion. A cap rather than eviction, since evicting silently
   * destroys work and a named limit is actionable.
   */
  private val maximumDesigns: Int = DEFAULT_MAXIMUM_DESIGNS,
  private val now: () -> Long = System::currentTimeMillis,
  /**
   * Where removals are recorded: the board keeps no trace of a deleted thread, so the log is the
   * record.
   */
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder comment root is not a directory: $root" }
  }

  private val subscribers = ConcurrentHashMap<String, MutableSet<(StoredCommentBoard) -> Unit>>()

  /**
   * Subscribers to every design on this host, given the board before and after each write. For the
   * outbound webhook, which can't use per-design [subscribe] (it must hear about future designs)
   * and needs the pair to tell a reply from a reaction. Announced from the same statement as
   * [subscribers], so there is one feed. Must not block (runs on the writer's thread).
   */
  private val hostSubscribers =
    ConcurrentHashMap.newKeySet<(StoredCommentBoard?, StoredCommentBoard) -> Unit>()

  /**
   * Ids are minted here, never accepted from callers (which could overwrite or impersonate
   * threads). Per-process counter plus wall clock keeps them unique across restarts.
   */
  private val ids = AtomicLong(0)

  /** What one design's discussion is, or null when nobody has said anything about it. */
  fun read(designId: String): StoredCommentBoard? {
    val file = fileFor(designId)
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_BOARD_BYTES) null
      else
        COMMENT_JSON.decodeFromString(
          StoredCommentBoard.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      // A board this process cannot read must not take the design offline; the panel opens empty
      // and the next comment writes a board it can read. The same call the reference store makes.
      null
    }
  }

  /** What one design's discussion is, never null — an empty board for a design nobody has. */
  fun readOrEmpty(designId: String): StoredCommentBoard =
    read(designId) ?: StoredCommentBoard(designId = designId)

  /**
   * Post a new thread or a reply. [authorId] is the authenticated actor, never from [request].
   * [authorKind] comes from the credential ([commentAuthorKindOf]) unless the caller knows better
   * (MCP: always an agent); [CommentPostRequest.authorKind] is ignored.
   */
  fun post(
    designId: String,
    authorId: String,
    request: CommentPostRequest,
    authorKind: String = commentAuthorKindOf(authorId),
  ): CommentWriteResult {
    val body = request.body.trim()
    if (body.isEmpty()) return CommentWriteResult.Refused("a comment needs something in it")
    if (body.length > MAX_COMMENT_BODY) {
      return CommentWriteResult.Refused("a comment must be under $MAX_COMMENT_BODY characters")
    }
    val kind =
      authorKind.takeIf { it in StoredComment.KNOWN_AUTHOR_KINDS }
        ?: StoredComment.AUTHOR_KIND_HUMAN
    return mutate(designId) { board, sequence ->
      val timestamp = now()
      val comment =
        StoredComment(
          id = mintId("c"),
          authorId = authorId,
          displayName = request.displayName.trim().take(MAX_COMMENT_DISPLAY_NAME),
          authorKind = kind,
          body = body,
          createdAtEpochMillis = timestamp,
        )
      if (request.threadId == null) {
        if (board.threads.size >= MAXIMUM_THREADS) {
          return@mutate CommentMutation.Refused(
            "a design may carry at most $MAXIMUM_THREADS comment threads"
          )
        }
        CommentMutation.Applied(
          board.copy(
            threads =
              board.threads +
                StoredCommentThread(
                  id = mintId("t"),
                  anchor = request.anchor?.sanitized(),
                  createdAtEpochMillis = timestamp,
                  updatedAtEpochMillis = timestamp,
                  updatedAtSequence = sequence,
                  // Saying something is reading it: an author is never told to catch up with
                  // their own sentence.
                  acknowledgedBy = mapOf(authorId to sequence),
                  comments = listOf(comment),
                )
          )
        )
      } else {
        val existing =
          board.threads.firstOrNull { it.id == request.threadId }
            ?: return@mutate CommentMutation.Refused("no such comment thread")
        if (existing.comments.size >= MAXIMUM_COMMENTS_PER_THREAD) {
          return@mutate CommentMutation.Refused(
            "a thread may carry at most $MAXIMUM_COMMENTS_PER_THREAD comments"
          )
        }
        CommentMutation.Applied(
          board.replacing(
            existing
              .copy(
                comments = existing.comments + comment,
                updatedAtEpochMillis = timestamp,
                updatedAtSequence = sequence,
              )
              .caughtUpBy(authorId)
          )
        )
      }
    }
  }

  /**
   * Close or reopen a thread. Anyone who can write may resolve any thread: it is attributed and
   * reversible, which suits review better than ownership.
   */
  fun resolve(
    designId: String,
    actorId: String,
    threadId: String,
    resolved: Boolean,
  ): CommentWriteResult =
    mutate(designId) { board, sequence ->
      val existing =
        board.threads.firstOrNull { it.id == threadId }
          ?: return@mutate CommentMutation.Refused("no such comment thread")
      val timestamp = now()
      CommentMutation.Applied(
        board.replacing(
          existing
            .copy(
              resolved = resolved,
              resolvedBy = if (resolved) actorId else null,
              resolvedAtEpochMillis = if (resolved) timestamp else null,
              updatedAtEpochMillis = timestamp,
              // Closing or reopening a thread is a thing said about it, so everybody else has
              // something to catch up with; the actor who said it does not.
              updatedAtSequence = sequence,
            )
            .caughtUpBy(actorId)
        )
      )
    }

  /**
   * "I have read this", distinct from resolving. [threadId] null acknowledges every thread. Per
   * actor, so other readers still see it waiting. Bumps the board sequence (open pages learn
   * immediately) but not any thread's [StoredCommentThread.updatedAtSequence], since one actor
   * catching up isn't news.
   */
  fun acknowledge(designId: String, actorId: String, threadId: String?): CommentWriteResult =
    mutate(designId) { board, _ ->
      if (threadId != null && board.threads.none { it.id == threadId }) {
        return@mutate CommentMutation.Refused("no such comment thread")
      }
      val threads =
        board.threads.map { thread ->
          if (threadId != null && thread.id != threadId) thread else thread.caughtUpBy(actorId)
        }
      if (threads == board.threads) CommentMutation.Unchanged
      else CommentMutation.Applied(board.copy(threads = threads))
    }

  /**
   * React to one comment, or remove the reaction: the lightest answer (👀, 👍) without adding
   * replies. A reaction acknowledges the thread for its author but doesn't resolve it or
   * acknowledge it for anyone else.
   */
  fun react(
    designId: String,
    actorId: String,
    commentId: String,
    reaction: String,
    on: Boolean,
  ): CommentWriteResult {
    val emoji = reaction.trim()
    if (emoji.isEmpty()) return CommentWriteResult.Refused("a reaction needs a character in it")
    if (emoji.length > MAX_COMMENT_REACTION) {
      return CommentWriteResult.Refused(
        "a reaction must be under $MAX_COMMENT_REACTION characters; it is an emoji, not a reply"
      )
    }
    if (emoji.any { it.isWhitespace() }) {
      return CommentWriteResult.Refused("a reaction is one mark, with no whitespace in it")
    }
    return mutate(designId) { board, _ ->
      val thread =
        board.threads.firstOrNull { candidate -> candidate.comments.any { it.id == commentId } }
          ?: return@mutate CommentMutation.Refused("no such comment")
      val comment = thread.comments.first { it.id == commentId }
      val actors = comment.reactions[emoji].orEmpty()
      if (on && actors.size >= MAXIMUM_ACTORS_PER_REACTION) {
        return@mutate CommentMutation.Refused(
          "at most $MAXIMUM_ACTORS_PER_REACTION actors may leave the same reaction"
        )
      }
      if (on && emoji !in comment.reactions && comment.reactions.size >= MAXIMUM_REACTIONS) {
        return@mutate CommentMutation.Refused(
          "a comment may carry at most $MAXIMUM_REACTIONS different reactions"
        )
      }
      if (!on && actorId !in actors) return@mutate CommentMutation.Unchanged
      val next = if (on) (actors - actorId) + actorId else actors - actorId
      val reacted =
        thread
          .copy(
            comments =
              thread.comments.map { candidate ->
                if (candidate.id != commentId) candidate
                else {
                  val reactions =
                    if (next.isEmpty()) candidate.reactions - emoji
                    else candidate.reactions + (emoji to next)
                  candidate.copy(reactions = reactions)
                }
              }
          )
          // A reaction is not something said, so the thread's own cursor does not move and nobody
          // else is told to catch up; the actor who reacted has, by reacting.
          .caughtUpBy(actorId)
      if (reacted == thread) CommentMutation.Unchanged
      else CommentMutation.Applied(board.replacing(reacted))
    }
  }

  /**
   * Remove a thread and its replies. Irreversible and unattributed on the board, so narrower than
   * [resolve]: only the thread's opener, or an actor holding the design's WRITE action
   * ([mayDeleteAnyThread]). Who removed whose thread goes to the log.
   */
  fun deleteThread(
    designId: String,
    actorId: String,
    threadId: String,
    mayDeleteAnyThread: Boolean,
  ): CommentWriteResult {
    var removed: StoredCommentThread? = null
    val result =
      mutate(designId) { board, _ ->
        val thread =
          board.threads.firstOrNull { it.id == threadId }
            ?: return@mutate CommentMutation.Refused("no such comment thread")
        val openedBy = thread.comments.firstOrNull()?.authorId
        if (!mayDeleteAnyThread && openedBy != actorId) {
          return@mutate CommentMutation.Refused(
            "only the actor who opened this thread, or an editor of the design, may delete it",
            forbidden = true,
          )
        }
        removed = thread
        CommentMutation.Applied(board.copy(threads = board.threads.filterNot { it.id == threadId }))
      }
    removed?.let { thread ->
      if (result is CommentWriteResult.Stored) {
        onLog(
          "serve: comment thread $threadId on design $designId deleted by $actorId " +
            "(opened by ${thread.comments.firstOrNull()?.authorId ?: "nobody"}, " +
            "${thread.comments.size} comments)"
        )
      }
    }
    return result
  }

  /**
   * The board once past [afterSequence], or null on timeout. Registers before re-reading, so a
   * comment between read and wait can't be missed.
   */
  suspend fun awaitBoardAfter(
    designId: String,
    afterSequence: Long,
    timeoutMillis: Long,
  ): StoredCommentBoard? {
    val waiter = CompletableDeferred<StoredCommentBoard>()
    val subscription =
      subscribe(designId) { board -> if (board.sequence > afterSequence) waiter.complete(board) }
    return try {
      // Read *after* subscribing, never before: the other order has a window in which a comment
      // lands between the read and the registration and nobody is told about it until the next one.
      val current = readOrEmpty(designId)
      if (current.sequence > afterSequence) return current
      try {
        withTimeout(timeoutMillis) { waiter.await() }
      } catch (_: TimeoutCancellationException) {
        null
      }
    } finally {
      subscription.close()
    }
  }

  /**
   * Every accepted write on [designId] until the handle closes. The listener runs on the writer's
   * thread and must not block. Closing leaves the design's empty listener set in place; removing it
   * safely would need locking every registration, and the leftovers are bounded by the store's
   * design cap.
   */
  fun subscribe(designId: String, listener: (StoredCommentBoard) -> Unit): Closeable {
    val listeners =
      subscribers.computeIfAbsent(designId) {
        ConcurrentHashMap.newKeySet<(StoredCommentBoard) -> Unit>()
      }
    listeners.add(listener)
    return Closeable { listeners.remove(listener) }
  }

  /**
   * Every accepted write on every design as `(before, after)` until closed; see [hostSubscribers].
   * `before` is null only for a design's first write, when every thread is new, so diffing needs no
   * special case.
   */
  fun subscribeToHost(listener: (StoredCommentBoard?, StoredCommentBoard) -> Unit): Closeable {
    hostSubscribers.add(listener)
    return Closeable { hostSubscribers.remove(listener) }
  }

  private sealed interface CommentMutation {
    data class Applied(val board: StoredCommentBoard) : CommentMutation

    /**
     * Understood, nothing to change (e.g. re-acknowledging): the board comes back and the sequence
     * doesn't move, so open pages aren't woken needlessly.
     */
    data object Unchanged : CommentMutation

    data class Refused(val reason: String, val forbidden: Boolean = false) : CommentMutation
  }

  /**
   * Read, change, write and announce under the design's lock. Striped locks so the map doesn't grow
   * with every design ever seen.
   */
  private fun mutate(
    designId: String,
    change: (StoredCommentBoard, Long) -> CommentMutation,
  ): CommentWriteResult {
    // The previous board, read under the same lock so host subscribers get a real before/after
    // pair.
    var previous: StoredCommentBoard? = null
    val stored =
      synchronized(lockFor(designId)) {
        val file = fileFor(designId)
        val current = read(designId)
        if (current == null && storedDesigns() >= maximumDesigns) {
          return CommentWriteResult.Refused(
            "this host already holds discussions for $maximumDesigns designs"
          )
        }
        previous = current
        val board = current ?: StoredCommentBoard(designId = designId)
        // The sequence this write will land at, passed to the change so threads and
        // acknowledgements can record it.
        val applied =
          when (val outcome = change(board, board.sequence + 1)) {
            is CommentMutation.Refused ->
              return CommentWriteResult.Refused(outcome.reason, outcome.forbidden)
            is CommentMutation.Unchanged -> return CommentWriteResult.Stored(board)
            is CommentMutation.Applied -> {
              val next =
                outcome.board.copy(
                  designId = designId,
                  sequence = board.sequence + 1,
                  updatedAtEpochMillis = now(),
                )
              when (val written = write(file, next)) {
                is CommentWriteResult.Refused -> return written
                is CommentWriteResult.Stored -> written.board
              }
            }
          }
        // Host subscribers are announced inside the lock so announcement order is write order (a
        // reply never reaches a chat channel before its thread). Affordable only because the
        // listener diffs small in-memory boards and enqueues without blocking
        // ([ServeUiBuilderCommentWebhook]); keep this seam narrow.
        hostSubscribers.forEach { listener -> runCatching { listener(previous, applied) } }
        applied
      }
    // Announced outside the lock: a slow subscriber must not hold the next writer up, and this
    // caller does nothing but hand the board on.
    subscribers[designId]?.forEach { listener -> runCatching { listener(stored) } }
    return CommentWriteResult.Stored(stored)
  }

  private fun write(file: Path, board: StoredCommentBoard): CommentWriteResult {
    val encoded = COMMENT_JSON.encodeToString(StoredCommentBoard.serializer(), board)
    if (encoded.length > MAX_BOARD_BYTES) {
      return CommentWriteResult.Refused("this design's discussion is full")
    }
    return try {
      val temporary = Files.createTempFile(root, "comments", ".tmp")
      try {
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
      CommentWriteResult.Stored(board)
    } catch (_: IOException) {
      CommentWriteResult.Refused("the comment could not be written to disk")
    }
  }

  /**
   * Drop a design's whole board when the design itself is removed. False when there was nothing.
   */
  fun delete(designId: String): Boolean =
    synchronized(lockFor(designId)) {
      try {
        Files.deleteIfExists(fileFor(designId))
      } catch (_: IOException) {
        false
      }
    }

  /**
   * Replace [actorId] with [placeholder] everywhere on every board (author, resolver, acknowledger,
   * reactor), along with typed display names; the words stay. Reaction counts are preserved.
   * Rewritten through [mutate], so open pages update. Returns boards changed; for
   * [ServeUiBuilderAdmin].
   */
  fun eraseActor(actorId: String, placeholder: String): Int {
    val target = actorId.trim()
    if (target.isEmpty()) return 0
    fun matches(candidate: String) = candidate.equals(target, ignoreCase = true)
    val designIds =
      try {
          Files.list(root).use { entries ->
            entries.filter { it.toString().endsWith(".json") }.toList()
          }
        } catch (_: IOException) {
          return 0
        }
        .mapNotNull { file ->
          try {
            if (Files.size(file) > MAX_BOARD_BYTES) null
            else
              COMMENT_JSON.decodeFromString(
                  StoredCommentBoard.serializer(),
                  Files.readString(file, StandardCharsets.UTF_8),
                )
                .designId
          } catch (_: IOException) {
            null
          } catch (_: SerializationException) {
            null
          }
        }
    var changed = 0
    for (designId in designIds) {
      var applied = false
      val result =
        mutate(designId) { board, _ ->
          val threads =
            board.threads.map { thread ->
              val acknowledged = thread.acknowledgedBy.entries.partition { matches(it.key) }
              thread.copy(
                resolvedBy = thread.resolvedBy?.let { if (matches(it)) placeholder else it },
                acknowledgedBy =
                  if (acknowledged.first.isEmpty()) thread.acknowledgedBy
                  else
                    acknowledged.second.associate { it.key to it.value } +
                      (placeholder to
                        maxOf(
                          acknowledged.first.maxOf { it.value },
                          thread.acknowledgedBy[placeholder] ?: 0L,
                        )),
                comments =
                  thread.comments.map { comment ->
                    val authored = matches(comment.authorId)
                    comment.copy(
                      authorId = if (authored) placeholder else comment.authorId,
                      displayName = if (authored) placeholder else comment.displayName,
                      reactions =
                        comment.reactions.mapValues { (_, actors) ->
                          actors.map { if (matches(it)) placeholder else it }
                        },
                    )
                  },
              )
            }
          if (threads == board.threads) CommentMutation.Unchanged
          else {
            applied = true
            CommentMutation.Applied(board.copy(threads = threads))
          }
        }
      if (applied && result is CommentWriteResult.Stored) changed++
    }
    return changed
  }

  private fun storedDesigns(): Int =
    try {
      Files.list(root)
        .use { entries -> entries.filter { it.toString().endsWith(".json") }.count() }
        .toInt()
    } catch (_: IOException) {
      0
    }

  private fun mintId(prefix: String): String = "$prefix-${now()}-${ids.incrementAndGet()}"

  private fun lockFor(designId: String): Any = locks[(designId.hashCode() and 0x7fffffff) % LOCKS]

  /**
   * Design ids are caller-supplied, so files are named by the id's digest, which can't escape
   * [root].
   */
  private fun fileFor(designId: String): Path =
    root.resolve(sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json")

  private val locks = Array<Any>(LOCKS) { Any() }

  companion object {
    const val DEFAULT_MAXIMUM_DESIGNS: Int = 2000

    /** Ceilings on one design's discussion, so a writer cannot grow a board without end. */
    const val MAXIMUM_THREADS: Int = 500

    const val MAXIMUM_COMMENTS_PER_THREAD: Int = 500

    /** The whole board is one response and one file; a megabyte of text is already generous. */
    const val MAX_BOARD_BYTES: Int = 1024 * 1024

    /** Limits on one comment's reactions, so reactions can't grow the board unboundedly. */
    const val MAXIMUM_REACTIONS: Int = 20

    const val MAXIMUM_ACTORS_PER_REACTION: Int = 200

    private const val LOCKS = 64

    private val COMMENT_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
      }
  }
}

/**
 * This thread acknowledged by [actorId] up to [StoredCommentThread.updatedAtSequence] (not the
 * write's sequence), so re-acknowledging is a no-op. Callers that also post set that field first.
 */
private fun StoredCommentThread.caughtUpBy(actorId: String): StoredCommentThread =
  copy(acknowledgedBy = acknowledgedBy.acknowledging(actorId, updatedAtSequence))

private fun StoredCommentBoard.replacing(thread: StoredCommentThread) =
  copy(threads = threads.map { if (it.id == thread.id) thread else it })

/**
 * Move this actor's acknowledgement forward to [sequence], never back. Bounded: past the cap the
 * oldest entries drop, costing those actors one resurfaced thread.
 */
private fun Map<String, Long>.acknowledging(actorId: String, sequence: Long): Map<String, Long> {
  val moved = this + (actorId to maxOf(this[actorId] ?: 0L, sequence))
  if (moved.size <= MAXIMUM_ACKNOWLEDGERS) return moved
  val kept =
    moved.entries
      .filterNot { it.key == actorId }
      .sortedByDescending { it.value }
      .take(MAXIMUM_ACKNOWLEDGERS - 1)
      .associate { it.key to it.value }
  return kept + (actorId to moved.getValue(actorId))
}

/** How many actors' acknowledgements one thread remembers. */
private const val MAXIMUM_ACKNOWLEDGERS = 200

sealed interface CommentWriteResult {
  data class Stored(val board: StoredCommentBoard) : CommentWriteResult

  /**
   * A sentence the route returns verbatim. [forbidden] means the thing exists but this actor may
   * not act on it (403, not 404).
   */
  data class Refused(val reason: String, val forbidden: Boolean = false) : CommentWriteResult
}

/**
 * Author kind from the authenticated identity: an agent grant's own identity
 * (`agent:<fingerprint>`) is an agent; everything else (GitHub session, a person's own grant, the
 * operator token) is a person, since the contract knows only these two kinds.
 */
internal fun commentAuthorKindOf(actorId: String): String =
  if (actorId.startsWith(ServeAgentGrants.agentActorId(""))) StoredComment.AUTHOR_KIND_AGENT
  else StoredComment.AUTHOR_KIND_HUMAN
