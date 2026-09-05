package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class ServeUiBuilderCommentStoreTest {
  private val root = Files.createTempDirectory("comment-store")
  private val store = ServeUiBuilderCommentStore(root)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  private fun board(result: CommentWriteResult): StoredCommentBoard =
    assertIs<CommentWriteResult.Stored>(result).board

  private fun post(
    designId: String = "design-1",
    author: String = "designer",
    body: String = "Why is this row a card?",
    threadId: String? = null,
    anchor: StoredCommentAnchor? = null,
  ) = store.post(designId, author, CommentPostRequest(threadId, anchor, body))

  @Test
  fun `a design nobody has commented on reads as an empty board`() {
    assertNull(store.read("design-1"))
    assertEquals(0, store.readOrEmpty("design-1").sequence)
    assertTrue(store.readOrEmpty("design-1").threads.isEmpty())
  }

  @Test
  fun `a thread and its replies come back on the next open`() {
    val opened = board(post())
    val threadId = opened.threads.single().id
    board(post(author = "agent", body = "Because the spec pins it.", threadId = threadId))

    val reopened = assertNotNull(store.read("design-1"))
    val thread = reopened.threads.single()
    assertEquals(2, thread.comments.size)
    assertEquals(listOf("designer", "agent"), thread.comments.map { it.authorId })
    assertEquals("Why is this row a card?", thread.comments.first().body)
  }

  @Test
  fun `the sequence rises once per accepted write and never for a refusal`() {
    val first = board(post())
    assertEquals(1, first.sequence)
    val second = board(post(body = "And the padding?"))
    assertEquals(2, second.sequence)

    assertIs<CommentWriteResult.Refused>(post(body = "   "))
    assertEquals(2, store.readOrEmpty("design-1").sequence)
  }

  @Test
  fun `the author is the authenticated actor, not anything the request carries`() {
    // `CommentPostRequest` has no author field at all, which is the point: the only way an author
    // reaches the store is the parameter the route fills from the authenticated actor.
    val stored = board(store.post("design-1", "real-actor", CommentPostRequest(body = "Mine.")))
    assertEquals("real-actor", stored.threads.single().comments.single().authorId)
  }

  @Test
  fun `an anchor keeps a mark, a node and a point, and drops an unusable one`() {
    val anchored =
      board(
        post(anchor = StoredCommentAnchor(markId = "mark-3", nodeId = "row", x = 0.5f, y = 0.25f))
      )
    val anchor = assertNotNull(anchored.threads.single().anchor)
    assertEquals("mark-3", anchor.markId)
    assertEquals("row", anchor.nodeId)
    assertEquals(0.5f, anchor.x)

    // Half a point is no point: a pin with one coordinate is one nobody can draw, and filling the
    // other with zero would put it in a corner the author never chose.
    val halfPointed = board(post(anchor = StoredCommentAnchor(x = 0.5f)))
    assertNull(halfPointed.threads.last().anchor)
  }

  @Test
  fun `resolving records who did it and reopening clears it`() {
    val opened = board(post())
    val threadId = opened.threads.single().id

    val resolved = board(store.resolve("design-1", "reviewer", threadId, resolved = true))
    assertTrue(resolved.threads.single().resolved)
    assertEquals("reviewer", resolved.threads.single().resolvedBy)

    val reopened = board(store.resolve("design-1", "reviewer", threadId, resolved = false))
    assertTrue(!reopened.threads.single().resolved)
    assertNull(reopened.threads.single().resolvedBy)
  }

  @Test
  fun `a reply to a thread that is not there is refused rather than starting a new one`() {
    val refused = post(threadId = "t-nonexistent")
    assertEquals("no such comment thread", assertIs<CommentWriteResult.Refused>(refused).reason)
    assertTrue(store.readOrEmpty("design-1").threads.isEmpty())
  }

  @Test
  fun `two designs keep their discussions apart`() {
    board(post(designId = "design-1", body = "One."))
    board(post(designId = "design-2", body = "Two."))
    assertEquals("One.", store.readOrEmpty("design-1").threads.single().comments.single().body)
    assertEquals("Two.", store.readOrEmpty("design-2").threads.single().comments.single().body)
  }

  @Test
  fun `a comment longer than the ceiling is refused`() {
    val refused = post(body = "x".repeat(MAX_COMMENT_BODY + 1))
    assertTrue(assertIs<CommentWriteResult.Refused>(refused).reason.contains("under"))
  }

  @Test
  fun `a subscriber is told about every accepted write and nothing after it closes`() {
    val seen = mutableListOf<Long>()
    val delivered = CountDownLatch(2)
    val subscription =
      store.subscribe("design-1") { board ->
        seen += board.sequence
        delivered.countDown()
      }
    board(post())
    board(post(body = "Also this."))
    assertTrue(delivered.await(5, TimeUnit.SECONDS))
    subscription.close()
    board(post(body = "After the handle closed."))

    assertEquals(listOf(1L, 2L), seen)
  }

  @Test
  fun `a watcher waiting on a cursor wakes on the next comment rather than polling`() =
    runBlocking {
      val waiting = async {
        store.awaitBoardAfter("design-1", afterSequence = 0, timeoutMillis = 5000)
      }
      // Racing on purpose: the wait registers before it re-reads, so whichever order these run in,
      // a comment posted around the registration cannot be missed.
      board(post())
      val woken = assertNotNull(waiting.await())
      assertEquals(1, woken.sequence)
      assertEquals("Why is this row a card?", woken.threads.single().comments.single().body)
    }

  @Test
  fun `a watcher behind the current sequence is answered without waiting at all`() = runBlocking {
    board(post())
    val answered = assertNotNull(store.awaitBoardAfter("design-1", 0, timeoutMillis = 60_000))
    assertEquals(1, answered.sequence)
  }

  @Test
  fun `a watcher that is up to date times out rather than being handed what it has`() =
    runBlocking {
      board(post())
      assertNull(store.awaitBoardAfter("design-1", afterSequence = 1, timeoutMillis = 150))
    }

  @Test
  fun `a board survives the store being rebuilt from the same directory`() {
    board(post())
    val reopened = ServeUiBuilderCommentStore(root).readOrEmpty("design-1")
    assertEquals(1, reopened.sequence)
    assertEquals("Why is this row a card?", reopened.threads.single().comments.single().body)
  }

  @Test
  fun `a deleted thread takes its comments with it and still advances the sequence`() {
    val opened = board(post())
    val threadId = opened.threads.single().id
    val deleted = board(store.deleteThread("design-1", threadId))
    assertTrue(deleted.threads.isEmpty())
    assertEquals(2, deleted.sequence)
  }
}
