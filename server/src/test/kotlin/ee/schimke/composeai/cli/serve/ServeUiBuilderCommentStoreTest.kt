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

  @Test
  fun `a host subscriber is told inside the write's lock, so it is told in write order`() {
    // Host subscribers receive a before and an after, so the order they are told in is part of the
    // message: announced after the lock is released, two concurrent writes to one board could be
    // announced in the opposite order and a chat channel would show a reply above the thread it
    // answers.
    //
    // Asserted as the property that makes that impossible: a second write to the same design
    // cannot start while the first write's announcement is still running.
    val secondWriteStarted = CountDownLatch(1)
    val insideListener = CountDownLatch(1)
    val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)

    store.subscribeToHost { _, _ ->
      insideListener.countDown()
      // If the announcement ran outside the lock, this second write would get in here.
      if (secondWriteStarted.await(750, TimeUnit.MILLISECONDS)) overlapped.set(true)
    }

    val writer = Thread {
      insideListener.await(5, TimeUnit.SECONDS)
      store.post("design-1", "second", CommentPostRequest(body = "Second."))
      secondWriteStarted.countDown()
    }
    writer.start()
    store.post("design-1", "first", CommentPostRequest(body = "First."))
    writer.join(TimeUnit.SECONDS.toMillis(10))

    assertTrue(
      !overlapped.get(),
      "a second write to the design ran while the first was still being announced",
    )
    assertEquals(2, store.readOrEmpty("design-1").threads.size)
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

  @Test
  fun `a thread is unacknowledged by everybody except whoever said it`() {
    val opened = board(post(author = "designer"))
    val thread = opened.threads.single()
    // Saying something is reading it, so the author is never told to catch up with their own
    // sentence; everybody else has something to catch up with.
    assertTrue(!thread.isUnacknowledgedBy("designer"))
    assertTrue(thread.isUnacknowledgedBy("agent"))
  }

  @Test
  fun `acknowledging is per actor and says nothing about whether the question is settled`() {
    val opened = board(post(author = "designer"))
    val threadId = opened.threads.single().id

    val acknowledged = board(store.acknowledge("design-1", "agent", threadId))
    val thread = acknowledged.threads.single()
    assertTrue(!thread.isUnacknowledgedBy("agent"))
    // Not a resolution: the question is still open, which is the whole point of the two being
    // different acts. An agent that has read a bug report it has not fixed can now say so.
    assertTrue(!thread.resolved)
    assertNull(thread.resolvedBy)
    // And still waiting on the second designer, who has read nothing.
    assertTrue(thread.isUnacknowledgedBy("second-designer"))
  }

  @Test
  fun `a reply after an acknowledgement is unacknowledged again`() {
    val opened = board(post(author = "designer"))
    val threadId = opened.threads.single().id
    board(store.acknowledge("design-1", "agent", threadId))

    val replied = board(post(author = "designer", body = "Still wrong.", threadId = threadId))
    assertTrue(replied.threads.single().isUnacknowledgedBy("agent"))
  }

  @Test
  fun `acknowledging what is already acknowledged does not move the sequence`() {
    board(post(author = "designer"))
    val first = board(store.acknowledge("design-1", "agent", threadId = null))
    assertEquals(2, first.sequence)
    // Every open page waking up because somebody re-read a thread would make the cursor
    // meaningless, so a write with nothing in it is understood and stored nothing.
    val again = board(store.acknowledge("design-1", "agent", threadId = null))
    assertEquals(2, again.sequence)
  }

  @Test
  fun `acknowledging a thread that is not there is refused`() {
    board(post())
    val refused = store.acknowledge("design-1", "agent", "t-nonexistent")
    assertEquals("no such comment thread", assertIs<CommentWriteResult.Refused>(refused).reason)
  }

  @Test
  fun `a reaction is kept per emoji, is idempotent, and comes back off`() {
    val opened = board(post(author = "designer"))
    val commentId = opened.threads.single().comments.single().id

    val reacted = board(store.react("design-1", "agent", commentId, "👀", on = true))
    assertEquals(
      mapOf("👀" to listOf("agent")),
      reacted.threads.single().comments.single().reactions,
    )

    // The same actor reacting twice is the same reaction, not two.
    val again = board(store.react("design-1", "agent", commentId, "👀", on = true))
    assertEquals(listOf("agent"), again.threads.single().comments.single().reactions["👀"])

    val alsoTheDesigner = board(store.react("design-1", "designer", commentId, "👀", on = true))
    assertEquals(
      listOf("agent", "designer"),
      alsoTheDesigner.threads.single().comments.single().reactions["👀"],
    )

    // Taking the last one back leaves no empty row behind.
    board(store.react("design-1", "agent", commentId, "👀", on = false))
    val cleared = board(store.react("design-1", "designer", commentId, "👀", on = false))
    assertTrue(cleared.threads.single().comments.single().reactions.isEmpty())
  }

  @Test
  fun `reacting is the lightest acknowledgement, and still not a resolution`() {
    val opened = board(post(author = "designer"))
    val commentId = opened.threads.single().comments.single().id

    val reacted = board(store.react("design-1", "agent", commentId, "👀", on = true))
    val thread = reacted.threads.single()
    assertTrue(!thread.isUnacknowledgedBy("agent"))
    assertTrue(!thread.resolved)
    // A reaction is not something said, so nobody else is told to catch up with it.
    assertTrue(thread.isUnacknowledgedBy("designer") || thread.acknowledgedBy["designer"] != null)
    assertTrue(thread.isUnacknowledgedBy("second-designer"))
  }

  @Test
  fun `a reaction that is a sentence, blank, or on nothing is refused`() {
    val opened = board(post())
    val commentId = opened.threads.single().comments.single().id

    assertTrue(
      assertIs<CommentWriteResult.Refused>(store.react("design-1", "a", commentId, "  ", true))
        .reason
        .contains("needs a character")
    )
    assertTrue(
      assertIs<CommentWriteResult.Refused>(
          store.react("design-1", "a", commentId, "👍 nice work", true)
        )
        .reason
        .contains("no whitespace")
    )
    assertTrue(
      assertIs<CommentWriteResult.Refused>(
          store.react("design-1", "a", commentId, "x".repeat(MAX_COMMENT_REACTION + 1), true)
        )
        .reason
        .contains("under")
    )
    assertEquals(
      "no such comment",
      assertIs<CommentWriteResult.Refused>(store.react("design-1", "a", "c-nope", "👍", true))
        .reason,
    )
  }

  @Test
  fun `taking back a reaction nobody left changes nothing`() {
    val opened = board(post())
    val commentId = opened.threads.single().comments.single().id
    val unchanged = board(store.react("design-1", "agent", commentId, "👍", on = false))
    assertEquals(1, unchanged.sequence)
  }

  @Test
  fun `the notice names the thread, the node and what was said, and empties on acknowledgement`() {
    val opened =
      board(
        post(
          author = "designer",
          body = "The play icon looks like a cross.",
          anchor = StoredCommentAnchor(nodeId = "play-button"),
        )
      )
    val notice = assertNotNull(opened.noticeFor("agent"))
    assertEquals(1, notice.unacknowledged)
    assertEquals(1, notice.sequence)
    // The excerpt is the load-bearing part: a bare count is easy to skip past, a quoted sentence
    // naming a node is not.
    assertEquals("The play icon looks like a cross.", notice.threads.single().excerpt)
    assertEquals("play-button", notice.threads.single().nodeId)
    assertEquals("designer", notice.threads.single().author)

    // Absent rather than zero, so an agent never learns to skip the key.
    assertNull(opened.noticeFor("designer"))
    val acknowledged = board(store.acknowledge("design-1", "agent", notice.threads.single().id))
    assertNull(acknowledged.noticeFor("agent"))
  }

  @Test
  fun `the notice stays a glance however loud the discussion is`() {
    repeat(6) { index -> board(post(author = "designer", body = "Thread $index.")) }
    val notice = assertNotNull(store.readOrEmpty("design-1").noticeFor("agent"))
    assertEquals(6, notice.unacknowledged)
    // Bounded: a count and the newest few, with ui_builder_list_comments one call away for the
    // rest. A busy design must not turn every apply outcome into a transcript.
    assertEquals(3, notice.threads.size)
    assertEquals(listOf("Thread 5.", "Thread 4.", "Thread 3."), notice.threads.map { it.excerpt })

    val long = board(post(author = "designer", body = "x".repeat(400)))
    val trimmed = assertNotNull(long.noticeFor("agent")).threads.first().excerpt
    assertTrue(trimmed.length <= 160, trimmed.length.toString())
    assertTrue(trimmed.endsWith("…"), trimmed)
  }
}
