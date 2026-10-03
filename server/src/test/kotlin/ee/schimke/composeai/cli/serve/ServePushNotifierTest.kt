package ee.schimke.composeai.cli.serve

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import java.io.Closeable
import java.nio.file.Path
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** Who is told about what ([commentPushIntents]), and the whole path to a push endpoint. */
class ServePushNotifierTest {
  @TempDir lateinit var root: Path

  private val closeables = mutableListOf<Closeable>()

  @AfterTest
  fun tearDown() {
    closeables.reversed().forEach { runCatching { it.close() } }
  }

  // ------------------------------------------------------------------------- pure rules

  private fun comment(id: String, author: String, body: String = "ok", agent: Boolean = false) =
    StoredComment(
      id = id,
      authorId = author,
      displayName = "",
      authorKind = if (agent) StoredComment.AUTHOR_KIND_AGENT else StoredComment.AUTHOR_KIND_HUMAN,
      body = body,
      createdAtEpochMillis = 0,
    )

  private fun board(vararg threads: Pair<String, List<StoredComment>>) =
    StoredCommentBoard(
      designId = "d1",
      threads = threads.map { (id, comments) -> StoredCommentThread(id = id, comments = comments) },
    )

  @Test
  fun `a reply reaches earlier participants and never its author`() {
    val before = board("t1" to listOf(comment("c1", "github:alice"), comment("c2", "github:bob")))
    val after =
      board(
        "t1" to
          listOf(
            comment("c1", "github:alice"),
            comment("c2", "github:bob"),
            comment("c3", "github:bob"),
          )
      )
    val intents = commentPushIntents(before, after)
    assertEquals(listOf("github:alice"), intents.map { it.recipient })
    assertEquals(listOf(PushKind.REPLIES), intents.single().kinds)
    assertEquals("t1", intents.single().threadId)
  }

  @Test
  fun `a mention reaches its login, case-insensitively, and an email address is not a mention`() {
    val after =
      board(
        "t1" to
          listOf(
            comment("c1", "github:alice", "@Carol can you look? cc bob@example.com and @alice")
          )
      )
    val intents = commentPushIntents(null, after)
    assertEquals(listOf("github:carol"), intents.map { it.recipient })
    assertEquals(listOf(PushKind.MENTIONS), intents.single().kinds)
  }

  @Test
  fun `a mention of a participant admits either kind, mention first`() {
    val before = board("t1" to listOf(comment("c1", "github:alice")))
    val after =
      board(
        "t1" to listOf(comment("c1", "github:alice"), comment("c2", "github:bob", "@alice yes"))
      )
    val intent = commentPushIntents(before, after).single()
    assertEquals("github:alice", intent.recipient)
    assertEquals(listOf(PushKind.MENTIONS, PushKind.REPLIES), intent.kinds)
  }

  @Test
  fun `reactions, resolutions and agents' ids are not people to notify`() {
    val before = board("t1" to listOf(comment("c1", "agent:abc", agent = true)))
    val after =
      board("t1" to listOf(comment("c1", "agent:abc", agent = true), comment("c2", "github:bob")))
    assertTrue(commentPushIntents(before, after).isEmpty())
    assertTrue(commentPushIntents(after, after).isEmpty())
    // An agent replying to a person does reach that person.
    val agentReply =
      board("t1" to listOf(comment("c1", "github:bob"), comment("c2", "agent:abc", agent = true)))
    val intent =
      commentPushIntents(board("t1" to listOf(comment("c1", "github:bob"))), agentReply).single()
    assertTrue(intent.agent)
    assertEquals("An agent replied on “Card”", pushTitle(PushKind.REPLIES, intent, "Card"))
  }

  @Test
  fun `a verdict reaches the owner unless the owner gave it`() {
    val decision =
      DesignActivity(
        designId = "d1",
        kind = DesignActivityKind.DECISION,
        actorId = "github:bob",
        verdict = "approve",
      )
    val intent = assertNotNull(reviewPushIntent(decision, "github:alice"))
    assertEquals("github:alice", intent.recipient)
    assertEquals("“Card” was approved", pushTitle(PushKind.REVIEWS, intent, "Card"))
    assertNull(reviewPushIntent(decision.copy(actorId = "github:Alice"), "github:alice"))
    assertNull(reviewPushIntent(decision, null))
    assertNull(reviewPushIntent(decision.copy(kind = DesignActivityKind.FORK), "github:alice"))
    val pr =
      reviewPushIntent(
        decision.copy(kind = DesignActivityKind.IMPLEMENTATION, verdict = null),
        "github:alice",
      )
    assertEquals("A pull request implements “Card”", pushTitle(PushKind.REVIEWS, pr!!, "Card"))
  }

  @Test
  fun `topics are short, URL-safe and stable per thread`() {
    val topic = ServePushNotifier.topicFor("d1", "t1")
    assertTrue(topic.length <= 32)
    assertTrue(topic.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    assertEquals(topic, ServePushNotifier.topicFor("d1", "t1"))
    assertTrue(topic != ServePushNotifier.topicFor("d1", "t2"))
  }

  @Test
  fun `Retry-After reads both spellings`() {
    assertEquals(120, parseRetryAfter("120"))
    assertEquals(30, parseRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT") { 30_000 })
    assertNull(parseRetryAfter("soon"))
  }

  // ------------------------------------------------------------------------- fan-out

  /** The sender records the push service's answer after the receiver has seen the request. */
  private fun eventually(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertTrue(condition())
  }

  /** A push service on loopback that decrypts what it receives, as the browser would. */
  private class FakePushService(private val browser: KeyPair, private val auth: ByteArray) :
    Closeable {
    data class Received(val path: String, val headers: Map<String, String>, val payload: String)

    val received = LinkedBlockingQueue<Received>()
    @Volatile var gone = setOf<String>()

    private val server =
      embeddedServer(CIO, host = "127.0.0.1", port = 0) {
          routing {
            post("/push/{id}") {
              val id = call.parameters["id"]!!
              val body = call.receiveChannel().toByteArray()
              val payload = ServeWebPush.decrypt(body, browser, auth).toString(Charsets.UTF_8)
              val headers =
                listOf("TTL", "Urgency", "Topic", "Content-Encoding", "Authorization")
                  .associateWith { call.request.headers[it].orEmpty() }
              received += Received("/push/$id", headers, payload)
              if (id in gone) call.respondText("", status = HttpStatusCode.Gone)
              else call.respondText("", status = HttpStatusCode.Created)
            }
          }
        }
        .also { it.start(wait = false) }

    val port: Int =
      kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }

    fun endpoint(id: String) = "http://127.0.0.1:$port/push/$id"

    override fun close() = server.stop(100, 500)
  }

  @Test
  fun `a reply reaches the participant's push endpoint, never the author, and 410 removes it`() {
    val browser = ServeWebPush.generateKeyPair()
    val auth = ByteArray(16) { (it * 7).toByte() }
    val p256dh = ServeWebPush.base64Url(ServeWebPush.rawPublicKey(browser.public as ECPublicKey))
    val authText = ServeWebPush.base64Url(auth)
    val fake = FakePushService(browser, auth).also(closeables::add)

    val subscriptions =
      ServePushSubscriptionStore(root.resolve("push"), endpointRejection = { null })
    // Alice wants replies on one device; Bob, the author, is subscribed too and must hear nothing.
    subscriptions.subscribe(
      "github:alice",
      fake.endpoint("alice"),
      p256dh,
      authText,
      setOf(PushKind.REPLIES),
    )
    subscriptions.subscribe("github:bob", fake.endpoint("bob"), p256dh, authText, null)
    val keys = ServeVapidKeys(ServeWebPush.generateKeyPair(), "mailto:ops@example.com")
    val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    val notifier =
      ServePushNotifier(
        store = subscriptions,
        keys = keys,
        designs = { PushDesign(title = "Checkout", ownerActorId = "github:alice") },
        canRead = { _, _ -> true },
        baseUrl = { "https://preview.example" },
        endpointAllowed = { true },
        onLog = { logs += it },
        debounceMillis = 50,
        retryBaseMillis = 10,
      )
    closeables += notifier
    val comments = ServeUiBuilderCommentStore(root.resolve("comments"))
    closeables += notifier.attachComments(comments)

    val opened = comments.post("d1", "github:alice", CommentPostRequest(body = "Is this a card?"))
    val threadId = (opened as CommentWriteResult.Stored).board.threads.single().id
    // Bob replies twice in a burst: Alice gets one notification, with the count.
    comments.post("d1", "github:bob", CommentPostRequest(threadId = threadId, body = "Yes."))
    comments.post("d1", "github:bob", CommentPostRequest(threadId = threadId, body = "Fixed it."))

    val delivered = assertNotNull(fake.received.poll(10, TimeUnit.SECONDS))
    assertEquals("/push/alice", delivered.path)
    val payload = Json.parseToJsonElement(delivered.payload).jsonObject
    assertEquals("replies", payload["kind"]!!.jsonPrimitive.content)
    assertEquals("d1", payload["designId"]!!.jsonPrimitive.content)
    assertEquals(threadId, payload["threadId"]!!.jsonPrimitive.content)
    assertEquals("New reply on “Checkout”", payload["title"]!!.jsonPrimitive.content)
    assertEquals(
      ServeUiBuilderCommentWebhook.threadUrl("https://preview.example", "d1", threadId),
      payload["url"]!!.jsonPrimitive.content,
    )
    assertEquals(2, payload["count"]!!.jsonPrimitive.int)
    // Nothing anybody wrote travels in the payload.
    assertTrue("Fixed it" !in delivered.payload && "Yes." !in delivered.payload)
    assertEquals("86400", delivered.headers["TTL"])
    assertEquals("normal", delivered.headers["Urgency"])
    assertEquals("aes128gcm", delivered.headers["Content-Encoding"])
    assertEquals(ServePushNotifier.topicFor("d1", threadId), delivered.headers["Topic"])
    assertTrue(delivered.headers["Authorization"]!!.startsWith("vapid t="))
    assertTrue(delivered.headers["Authorization"]!!.endsWith("k=${keys.publicKey}"))
    eventually { subscriptions.forActor("github:alice").single().lastSuccess != null }
    assertNull(
      fake.received.poll(500, TimeUnit.MILLISECONDS),
      "the author was notified of their own reply",
    )

    // Alice's browser unsubscribed at the push service: the next push answers 410 and is forgotten.
    fake.gone = setOf("alice")
    comments.post("d1", "github:bob", CommentPostRequest(threadId = threadId, body = "Ping."))
    assertEquals("/push/alice", assertNotNull(fake.received.poll(10, TimeUnit.SECONDS)).path)
    eventually { subscriptions.forActor("github:alice").isEmpty() }
    assertEquals(1, subscriptions.forActor("github:bob").size)
    // Endpoints never reach the log; only digests do.
    assertTrue(logs.none { "127.0.0.1" in it }, logs.toString())
  }

  @Test
  fun `a verdict reaches the design's owner, and somebody who cannot read the design hears nothing`() {
    val browser = ServeWebPush.generateKeyPair()
    val auth = ByteArray(16) { 3 }
    val p256dh = ServeWebPush.base64Url(ServeWebPush.rawPublicKey(browser.public as ECPublicKey))
    val fake = FakePushService(browser, auth).also(closeables::add)
    val subscriptions =
      ServePushSubscriptionStore(root.resolve("push"), endpointRejection = { null })
    subscriptions.subscribe(
      "github:alice",
      fake.endpoint("alice"),
      p256dh,
      ServeWebPush.base64Url(auth),
      null,
    )
    subscriptions.subscribe(
      "github:carol",
      fake.endpoint("carol"),
      p256dh,
      ServeWebPush.base64Url(auth),
      null,
    )
    val notifier =
      ServePushNotifier(
        store = subscriptions,
        keys = ServeVapidKeys(ServeWebPush.generateKeyPair(), ServeVapidKeys.DEFAULT_SUBJECT),
        designs = { PushDesign(title = "Checkout", ownerActorId = "github:alice") },
        canRead = { actor, _ -> actor != "github:carol" },
        baseUrl = { "https://preview.example" },
        endpointAllowed = { true },
        debounceMillis = 20,
      )
    closeables += notifier
    val reviews = ServeUiBuilderReviewStore(root.resolve("reviews"))
    val comments = ServeUiBuilderCommentStore(root.resolve("comments"))
    closeables += notifier.attachReviews(reviews)
    closeables += notifier.attachComments(comments)

    reviews.decide("d1", "github:bob", "human", DecisionRequest(revision = 3, verdict = "approve"))
    val delivered = assertNotNull(fake.received.poll(10, TimeUnit.SECONDS))
    assertEquals("/push/alice", delivered.path)
    val payload = Json.parseToJsonElement(delivered.payload).jsonObject
    assertEquals("reviews", payload["kind"]!!.jsonPrimitive.content)
    assertEquals("“Checkout” was approved", payload["title"]!!.jsonPrimitive.content)
    assertNull(payload["threadId"])
    assertEquals("https://preview.example/ui-builder/d1", payload["url"]!!.jsonPrimitive.content)

    // The owner approving their own design is nobody's news.
    reviews.decide(
      "d1",
      "github:alice",
      "human",
      DecisionRequest(revision = 4, verdict = "approve"),
    )
    // A mention of somebody without read access is not delivered.
    comments.post("d1", "github:bob", CommentPostRequest(body = "@carol thoughts?"))
    assertNull(fake.received.poll(500, TimeUnit.MILLISECONDS))
  }
}
