package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What counts as news, and what each chat platform is handed.
 *
 * Both halves are pure by construction — [diffCommentBoards] is two boards in and a list out, and
 * every adapter is one event in and a string out — so the rules this feature is actually making are
 * checked here rather than against a channel somebody has to watch.
 *
 * The rule worth stating twice: **a reaction and an acknowledgement produce nothing.** They bump
 * the board's own sequence, so they wake the browser socket and the waiting agent exactly as they
 * should; what they must not do is post into somebody's chat window. That is the difference between
 * a notification people read and one they mute, and it is the case the store's own tests cannot
 * cover because the store is right to announce them.
 */
class ServeUiBuilderCommentWebhookTest {

  @Test
  fun `a new thread is news, and quotes what was said`() {
    val before = board()
    val after = board(thread("t-1", comment("c-1", "Yuri", "This row should be a card.")))

    val change = diffCommentBoards(before, after).single()

    assertEquals(CommentBoardChangeKind.NEW_THREAD, change.kind)
    assertEquals("t-1", change.thread.id)
    assertEquals("Yuri", change.comment.author)
    assertEquals(StoredComment.AUTHOR_KIND_HUMAN, change.comment.authorKind)
    assertEquals("This row should be a card.", change.comment.excerpt)
  }

  @Test
  fun `a board that had no file at all reports its first thread once`() {
    // `previous` is null only for the very first write to a design, which is exactly the state in
    // which every thread is new — so this needs no special case and must not need a seeding pass.
    val after = board(thread("t-1", comment("c-1", "Yuri", "First.")))

    assertEquals(
      listOf(CommentBoardChangeKind.NEW_THREAD),
      diffCommentBoards(null, after).map { it.kind },
    )
  }

  @Test
  fun `a reply is news, and it is the reply that is quoted rather than the question`() {
    val before = board(thread("t-1", comment("c-1", "Yuri", "Why is the padding 24?")))
    val after =
      board(
        thread(
          "t-1",
          comment("c-1", "Yuri", "Why is the padding 24?"),
          comment("c-2", "", "Because the spec pins it.", kind = StoredComment.AUTHOR_KIND_AGENT),
        )
      )

    val change = diffCommentBoards(before, after).single()

    assertEquals(CommentBoardChangeKind.REPLY, change.kind)
    assertEquals("Because the spec pins it.", change.comment.excerpt)
    assertEquals(StoredComment.AUTHOR_KIND_AGENT, change.comment.authorKind)
    // No display name given, so the actor id stands in — the same fallback the agent notice makes.
    assertEquals("agent-1", change.comment.author)
    assertEquals(2, change.thread.comments.size)
  }

  @Test
  fun `a resolve names who closed it and quotes the question, not the last word`() {
    val opening = comment("c-1", "Yuri", "The play icon looks like a cross.")
    val done = comment("c-2", "Bot", "Fixed.", kind = StoredComment.AUTHOR_KIND_AGENT)
    val before = board(thread("t-1", opening, done))
    val after = board(thread("t-1", opening, done).copy(resolved = true, resolvedBy = "agent-1"))

    val change = diffCommentBoards(before, after).single()

    assertEquals(CommentBoardChangeKind.RESOLVED, change.kind)
    assertEquals("Bot", change.comment.author)
    // What was settled is the question. "Fixed." would tell a reader nothing about what was fixed.
    assertEquals("The play icon looks like a cross.", change.comment.excerpt)
  }

  @Test
  fun `a reopen is news and claims no author, because the store does not keep one`() {
    val opening = comment("c-1", "Yuri", "Still wrong.")
    val before = board(thread("t-1", opening).copy(resolved = true, resolvedBy = "agent-1"))
    val after = board(thread("t-1", opening))

    val change = diffCommentBoards(before, after).single()

    assertEquals(CommentBoardChangeKind.REOPENED, change.kind)
    // `resolvedBy` is cleared on reopen by construction. Guessing the reopener from the
    // acknowledgement map would be a guess dressed as a fact, so the event names nobody.
    assertNull(change.comment.author)
    assertEquals("Still wrong.", change.comment.excerpt)
  }

  @Test
  fun `a reaction is not news`() {
    val original = comment("c-1", "Yuri", "The play icon looks like a cross.")
    val before = board(thread("t-1", original))
    val after = board(thread("t-1", original.copy(reactions = mapOf("👀" to listOf("agent-1")))))

    assertEquals(emptyList(), diffCommentBoards(before, after))
  }

  @Test
  fun `an acknowledgement is not news`() {
    val original = comment("c-1", "Yuri", "The play icon looks like a cross.")
    val before = board(thread("t-1", original))
    val after = board(thread("t-1", original).copy(acknowledgedBy = mapOf("agent-1" to 2L)))

    assertEquals(emptyList(), diffCommentBoards(before, after))
  }

  @Test
  fun `a deleted thread is not news, because there is nothing left to link to`() {
    val before =
      board(
        thread("t-1", comment("c-1", "Yuri", "Gone.")),
        thread("t-2", comment("c-2", "Yuri", "Kept.")),
      )
    val after = board(thread("t-2", comment("c-2", "Yuri", "Kept.")))

    assertEquals(emptyList(), diffCommentBoards(before, after))
  }

  @Test
  fun `an excerpt is trimmed to the same 160 characters the agent notice uses`() {
    val long = "x".repeat(500)
    val change =
      diffCommentBoards(board(), board(thread("t-1", comment("c-1", "Yuri", long)))).single()

    assertEquals(MAX_COMMENT_EXCERPT, change.comment.excerpt.length)
    assertTrue(change.comment.excerpt.endsWith("…"), change.comment.excerpt)
  }

  @Test
  fun `the permalink names the catalog, the design and the thread`() {
    assertEquals(
      "https://preview.example/ui-builder/m3-catalog/checkout-screen#thread=t-1",
      ServeUiBuilderCommentWebhook.threadUrl(
        "https://preview.example/",
        "m3-catalog",
        "checkout-screen",
        "t-1",
      ),
    )
  }

  @Test
  fun `a design whose catalog is unknown links to the builder rather than inventing a segment`() {
    assertEquals(
      "https://preview.example/ui-builder/",
      ServeUiBuilderCommentWebhook.threadUrl("https://preview.example", null, "d-1", "t-1"),
    )
  }

  @Test
  fun `the plain body carries the design, the thread, the comment and the link`() {
    val body = Json.parseToJsonElement(CommentWebhookFormat.PLAIN.body(event())).jsonObject

    assertEquals("thread", body["event"]!!.jsonPrimitive.content)
    assertEquals("Checkout", body["design"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    assertEquals("m3-catalog", body["design"]!!.jsonObject["catalog"]!!.jsonPrimitive.content)
    assertEquals("node play-button", body["thread"]!!.jsonObject["anchor"]!!.jsonPrimitive.content)
    assertEquals("Yuri", body["comment"]!!.jsonObject["author"]!!.jsonPrimitive.content)
    assertEquals(
      "https://preview.example/ui-builder/m3-catalog/checkout#thread=t-1",
      body["url"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `the slack body is one mrkdwn string with the permalink as the title's link`() {
    val body = Json.parseToJsonElement(CommentWebhookFormat.SLACK.body(event())).jsonObject
    val text = body["text"]!!.jsonPrimitive.content

    assertEquals(setOf("text"), body.keys)
    assertTrue(text.startsWith("*Yuri* started a thread on "), text)
    assertTrue(
      text.contains("<https://preview.example/ui-builder/m3-catalog/checkout#thread=t-1|Checkout>"),
      text,
    )
    assertTrue(text.contains("\n> This row should be a card."), text)
    assertTrue(text.contains("on node play-button"), text)
  }

  @Test
  fun `slack markup in a comment is escaped rather than interpreted`() {
    // A comment is text somebody typed. `<!channel>` in a design review must reach the channel as
    // those characters, not as a ping to everybody in it.
    val text =
      Json.parseToJsonElement(
          CommentWebhookFormat.SLACK.body(event(excerpt = "<!channel> the & in <Button> is wrong"))
        )
        .jsonObject["text"]!!
        .jsonPrimitive
        .content

    assertTrue(text.contains("&lt;!channel&gt; the &amp; in &lt;Button&gt; is wrong"), text)
  }

  @Test
  fun `the google chat body is one text string, in the same markup`() {
    val body = Json.parseToJsonElement(CommentWebhookFormat.GOOGLE_CHAT.body(event())).jsonObject

    assertEquals(setOf("text"), body.keys)
    assertTrue(
      body["text"]!!
        .jsonPrimitive
        .content
        .contains("<https://preview.example/ui-builder/m3-catalog/checkout#thread=t-1|Checkout>"),
      body.toString(),
    )
  }

  @Test
  fun `the teams body is an adaptive card whose action opens the thread`() {
    val body = Json.parseToJsonElement(CommentWebhookFormat.TEAMS.body(event())).jsonObject

    assertEquals("message", body["type"]!!.jsonPrimitive.content)
    val attachment = body["attachments"]!!.jsonArray.single().jsonObject
    assertEquals(
      "application/vnd.microsoft.card.adaptive",
      attachment["contentType"]!!.jsonPrimitive.content,
    )
    val content = attachment["content"]!!.jsonObject
    assertEquals("AdaptiveCard", content["type"]!!.jsonPrimitive.content)
    val blocks = content["body"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
    // No mrkdwn: a card renders its own emphasis, and `*Yuri*` would arrive as literal asterisks.
    assertEquals("Yuri started a thread on Checkout", blocks.first())
    assertTrue(blocks.any { it.contains("This row should be a card.") }, blocks.toString())
    val action = content["actions"]!!.jsonArray.single().jsonObject
    assertEquals("Action.OpenUrl", action["type"]!!.jsonPrimitive.content)
    assertEquals(
      "https://preview.example/ui-builder/m3-catalog/checkout#thread=t-1",
      action["url"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `an agent's comment says so, so a channel can tell the two apart`() {
    val text =
      Json.parseToJsonElement(
          CommentWebhookFormat.SLACK.body(
            event(author = "Assistant", kind = StoredComment.AUTHOR_KIND_AGENT)
          )
        )
        .jsonObject["text"]!!
        .jsonPrimitive
        .content

    assertTrue(text.startsWith("*Assistant* (agent) started a thread on "), text)
  }

  @Test
  fun `every format is reachable by the name the flag takes`() {
    assertEquals(
      CommentWebhookFormat.entries.toSet(),
      CommentWebhookFormat.WIRE_NAMES.map { CommentWebhookFormat.parse(it) }.toSet(),
    )
    assertNull(CommentWebhookFormat.parse("discord"))
  }

  @Test
  fun `only https and loopback http are accepted`() {
    assertNull(CommentWebhookConfig("https://hooks.slack.com/services/T/B/xxx").rejection())
    assertNull(CommentWebhookConfig("http://127.0.0.1:8080/hook").rejection())
    assertNull(CommentWebhookConfig("http://localhost:8080/hook").rejection())
    // The URL is a credential, so a cleartext hop to another machine is refused rather than warned
    // about — and the refusal must be a sentence an operator can act on.
    assertTrue(
      CommentWebhookConfig("http://chat.example/hook").rejection()!!.contains("https"),
      CommentWebhookConfig("http://chat.example/hook").rejection().orEmpty(),
    )
    assertTrue(CommentWebhookConfig("ftp://chat.example/hook").rejection() != null)
    assertTrue(CommentWebhookConfig("not a url at all").rejection() != null)
  }

  @Test
  fun `a fingerprint identifies a hook without carrying it`() {
    val url = "https://hooks.slack.com/services/T000/B000/SUPERSECRET"
    val fingerprint = ServeUiBuilderCommentWebhook.fingerprintOf(url)

    assertEquals(12, fingerprint.length)
    assertEquals(fingerprint, ServeUiBuilderCommentWebhook.fingerprintOf(url))
    assertTrue(!url.contains(fingerprint), fingerprint)
    assertTrue(
      fingerprint != ServeUiBuilderCommentWebhook.fingerprintOf("$url-2"),
      "two hooks must be distinguishable",
    )
  }

  private fun event(
    author: String? = "Yuri",
    kind: String? = StoredComment.AUTHOR_KIND_HUMAN,
    excerpt: String = "This row should be a card.",
  ) =
    CommentWebhookEventV1(
      event = "thread",
      design = CommentWebhookDesignV1(id = "checkout", title = "Checkout", catalog = "m3-catalog"),
      thread = CommentWebhookThreadV1(id = "t-1", anchor = "node play-button", comments = 1),
      comment = CommentWebhookCommentV1(author = author, authorKind = kind, excerpt = excerpt),
      url = "https://preview.example/ui-builder/m3-catalog/checkout#thread=t-1",
    )

  private fun board(vararg threads: StoredCommentThread) =
    StoredCommentBoard(
      designId = "checkout",
      sequence = threads.size.toLong(),
      threads = threads.toList(),
    )

  private fun thread(id: String, vararg comments: StoredComment) =
    StoredCommentThread(
      id = id,
      anchor = StoredCommentAnchor(nodeId = "play-button"),
      comments = comments.toList(),
    )

  private fun comment(
    id: String,
    displayName: String,
    body: String,
    kind: String = StoredComment.AUTHOR_KIND_HUMAN,
  ) =
    StoredComment(
      id = id,
      authorId = if (kind == StoredComment.AUTHOR_KIND_AGENT) "agent-1" else "yuri",
      displayName = displayName,
      authorKind = kind,
      body = body,
    )
}
