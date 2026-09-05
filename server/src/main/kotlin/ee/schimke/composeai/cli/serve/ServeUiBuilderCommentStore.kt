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
 * The **discussion** attached to one design: threads pinned to a mark, a node or a point on the
 * frame, and the replies under them, between the people editing a design and the agents helping.
 *
 * ### Why this is not in the design document
 *
 * The same three reasons [ServeUiBuilderReferenceStore] gives, and they hold at least as strongly:
 *
 * 1. **It is not part of the design.** "Should this row be a card?" must never reach the Compose
 *    export or the rendered document. A design that shipped its own review notes as nodes would be
 *    a bug in the generator, not a feature.
 * 2. **The wire cannot carry it.** `DesignMutationV1` is a closed set with no comment mutation, so
 *    there is no way to write one without releasing `ui-builder-protocol` — to carry something
 *    point 1 says should not be in the document.
 * 3. **It must not disturb the document.** The document is replayed, hashed, diffed for catalog
 *    upgrades and pushed to every subscriber on every edit. A reply typed into a review thread
 *    would advance the design's revision, invalidate every client's optimistic state, and show up
 *    in the catalog-upgrade diff. Discussion has to be able to happen *about* a revision without
 *    changing it.
 *
 * ### Why it is a change feed rather than a poll
 *
 * The point of comments here is a conversation between people and agents who are not looking at the
 * page at the same moment. So every accepted write bumps [StoredCommentBoard.sequence] and wakes
 * every subscriber: the browser holds a socket, and an agent holds [awaitBoardAfter] through the
 * MCP tool. Both are told the same thing at the same time by the same code, which is what makes
 * "open the page and watch" and "wait for a reply" the same feature rather than two.
 *
 * Losing this directory loses the discussion and no design content, which is the correct blast
 * radius.
 */
class ServeUiBuilderCommentStore(
  private val root: Path,
  /**
   * How many designs may hold a discussion at once.
   *
   * A cap rather than eviction, for the reason the reference store gives: evicting one design's
   * threads to make room for another's destroys work silently, and a refusal that names the limit
   * is something an operator can act on.
   */
  private val maximumDesigns: Int = DEFAULT_MAXIMUM_DESIGNS,
  private val now: () -> Long = System::currentTimeMillis,
) {
  init {
    Files.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder comment root is not a directory: $root" }
  }

  private val subscribers = ConcurrentHashMap<String, MutableSet<(StoredCommentBoard) -> Unit>>()

  /**
   * Ids are minted here rather than accepted from the caller.
   *
   * A client-chosen thread id is a way to overwrite somebody else's thread by guessing its name,
   * and a client-chosen comment id is a way to make one reply masquerade as another. The counter is
   * per process and the id carries the wall clock, so ids stay unique across a restart.
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
   * Say something: a new thread, or a reply under an existing one.
   *
   * [authorId] is the authenticated actor and is never read from [request]; see [StoredComment].
   */
  fun post(
    designId: String,
    authorId: String,
    request: CommentPostRequest,
  ): CommentWriteResult {
    val body = request.body.trim()
    if (body.isEmpty()) return CommentWriteResult.Refused("a comment needs something in it")
    if (body.length > MAX_COMMENT_BODY) {
      return CommentWriteResult.Refused("a comment must be under $MAX_COMMENT_BODY characters")
    }
    val kind =
      request.authorKind.takeIf { it in StoredComment.KNOWN_AUTHOR_KINDS }
        ?: StoredComment.AUTHOR_KIND_HUMAN
    return mutate(designId) { board ->
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
            existing.copy(
              comments = existing.comments + comment,
              updatedAtEpochMillis = timestamp,
            )
          )
        )
      }
    }
  }

  /**
   * Close a thread, or reopen it.
   *
   * Anybody who can write may resolve anybody's thread. Not an oversight: a resolution is a claim
   * that the question is answered, it is attributed to whoever made it, and it is reversible by the
   * same call — which is a better fit for a design review than an ownership rule that leaves a
   * thread open forever because its author has moved on.
   */
  fun resolve(
    designId: String,
    actorId: String,
    threadId: String,
    resolved: Boolean,
  ): CommentWriteResult =
    mutate(designId) { board ->
      val existing =
        board.threads.firstOrNull { it.id == threadId }
          ?: return@mutate CommentMutation.Refused("no such comment thread")
      val timestamp = now()
      CommentMutation.Applied(
        board.replacing(
          existing.copy(
            resolved = resolved,
            resolvedBy = if (resolved) actorId else null,
            resolvedAtEpochMillis = if (resolved) timestamp else null,
            updatedAtEpochMillis = timestamp,
          )
        )
      )
    }

  /** Remove a thread and everything said in it. */
  fun deleteThread(designId: String, threadId: String): CommentWriteResult =
    mutate(designId) { board ->
      if (board.threads.none { it.id == threadId }) {
        return@mutate CommentMutation.Refused("no such comment thread")
      }
      CommentMutation.Applied(board.copy(threads = board.threads.filterNot { it.id == threadId }))
    }

  /**
   * The board once it is past [afterSequence], or null when nothing was said in time.
   *
   * The event-driven half, and the reason a watching agent does not have to poll: it registers
   * before it re-reads, so a comment posted between the read and the wait cannot be missed. A null
   * return is "nothing yet", which the caller answers as a timeout rather than an error.
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
   * Every accepted write on [designId], until the handle is closed.
   *
   * The listener runs on the writer's thread, so it must not block: both callers hand the board to
   * a channel or complete a deferred and return.
   *
   * Closing removes the listener and leaves the design's (now empty) set in place. Deliberately:
   * dropping it correctly needs a lock around every registration to close the window where one
   * subscriber leaves as another arrives, and what it would reclaim is one empty set per design
   * anybody has ever watched on this host — bounded by the same number of designs the store itself
   * is bounded to.
   */
  fun subscribe(designId: String, listener: (StoredCommentBoard) -> Unit): Closeable {
    val listeners =
      subscribers.computeIfAbsent(designId) {
        ConcurrentHashMap.newKeySet<(StoredCommentBoard) -> Unit>()
      }
    listeners.add(listener)
    return Closeable { listeners.remove(listener) }
  }

  private sealed interface CommentMutation {
    data class Applied(val board: StoredCommentBoard) : CommentMutation

    data class Refused(val reason: String) : CommentMutation
  }

  /**
   * Read, change, write and announce, under the design's own lock.
   *
   * Striped rather than one lock per design so the map cannot grow with the number of designs this
   * host has ever seen; two designs sharing a stripe serialise against each other, which costs a
   * write that was going to touch the disk anyway.
   */
  private fun mutate(
    designId: String,
    change: (StoredCommentBoard) -> CommentMutation,
  ): CommentWriteResult {
    val stored =
      synchronized(lockFor(designId)) {
        val file = fileFor(designId)
        val current = read(designId)
        if (current == null && storedDesigns() >= maximumDesigns) {
          return CommentWriteResult.Refused(
            "this host already holds discussions for $maximumDesigns designs"
          )
        }
        val board = current ?: StoredCommentBoard(designId = designId)
        when (val outcome = change(board)) {
          is CommentMutation.Refused -> return CommentWriteResult.Refused(outcome.reason)
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
      }
    // Announced outside the lock: a slow subscriber must not hold the next writer up, and neither
    // caller does anything but hand the board on.
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
   * A design id is caller-supplied text, so it never becomes a path segment: the file is named by
   * the digest of the id, which is fixed-length, path-safe, and cannot escape [root].
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

private fun StoredCommentBoard.replacing(thread: StoredCommentThread) =
  copy(threads = threads.map { if (it.id == thread.id) thread else it })

sealed interface CommentWriteResult {
  data class Stored(val board: StoredCommentBoard) : CommentWriteResult

  /** A sentence the route hands back verbatim; it is written to be read by an operator. */
  data class Refused(val reason: String) : CommentWriteResult
}
