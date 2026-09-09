package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * A comment typed over HTTP, arriving in somebody else's chat window.
 *
 * ## Why this is not covered by the unit tests next door
 *
 * [ServeUiBuilderCommentWebhookTest] pins what counts as news and what each platform is handed, and
 * both are pure. What it cannot pin is the claim the feature actually makes: that a comment
 * **accepted by the real routes** reaches a real receiver with a link somebody can click, that a
 * reaction accepted by the same routes reaches nobody, and that a receiver which never answers
 * costs the person who typed the comment nothing.
 *
 * Every one of those is wiring — the subscription registered against the same store the routes
 * write through, the diff run on the writer's thread, the queue between it and the socket — and
 * every one of them passes against a mock while being broken in the server.
 *
 * So this starts the real server, wired as [ServeRunner] wires it, and puts a real Ktor receiver on
 * the other end.
 */
class ServeUiBuilderCommentWebhookIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private val closeables = mutableListOf<Closeable>()

  @AfterTest
  fun tearDown() {
    closeables.reversed().forEach { runCatching { it.close() } }
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun `a comment posted over HTTP reaches the webhook, with the thread permalink`() {
    val receiver = receiver()
    val server = start(receiver.url)
    createDesign(server)

    val posted =
      comments(
        server,
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"This row should be a card.","displayName":"Yuri","anchor":{"nodeId":"row"}}""",
      )
    assertEquals(201, posted.first, posted.second)

    val body = Json.parseToJsonElement(receiver.awaitOne()).jsonObject
    assertEquals("thread", body["event"]!!.jsonPrimitive.content)
    assertEquals(
      "This row should be a card.",
      body["comment"]!!.jsonObject["excerpt"]!!.jsonPrimitive.content,
    )
    assertEquals("Yuri", body["comment"]!!.jsonObject["author"]!!.jsonPrimitive.content)
    assertEquals("node row", body["thread"]!!.jsonObject["anchor"]!!.jsonPrimitive.content)
    // The design's title and catalog come from the service, which the comment store cannot see.
    assertEquals("Discussed screen", body["design"]!!.jsonObject["title"]!!.jsonPrimitive.content)

    val url = body["url"]!!.jsonPrimitive.content
    val threadId = threadIdOf(posted.second)
    assertEquals(
      "http://127.0.0.1:${server.port}/ui-builder/$CATALOG_SYSTEM_ID/$DESIGN_ID#thread=$threadId",
      url,
    )
  }

  @Test
  fun `a reply and a resolution are told, and a reaction is not`() {
    val receiver = receiver()
    val server = start(receiver.url)
    createDesign(server)

    val opened =
      comments(
        server,
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"Why is the padding 24?"}""",
      )
    val threadId = threadIdOf(opened.second)
    val commentId = commentIdOf(opened.second)

    // A reaction between the two things that ARE news, so a webhook that posted for it would be
    // caught by the ORDER of what arrives rather than only by a count that could race.
    assertEquals(
      200,
      comments(
          server,
          "/api/ui-builder/v1/designs/$DESIGN_ID/comments/$threadId/$commentId/reactions",
          """{"reaction":"👀"}""",
        )
        .first,
    )
    assertEquals(
      200,
      comments(
          server,
          "/api/ui-builder/v1/designs/$DESIGN_ID/comments/$threadId/acknowledgement",
          "{}",
        )
        .first,
    )
    assertEquals(
      201,
      comments(
          server,
          "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
          """{"threadId":"$threadId","body":"Because the spec pins it."}""",
        )
        .first,
    )
    assertEquals(
      200,
      comments(
          server,
          "/api/ui-builder/v1/designs/$DESIGN_ID/comments/$threadId/resolution",
          """{"resolved":true}""",
        )
        .first,
    )

    val events = receiver.await(3).map { Json.parseToJsonElement(it).jsonObject }
    assertEquals(
      listOf("thread", "reply", "resolved"),
      events.map { it["event"]!!.jsonPrimitive.content },
    )
    // Not a count that happens to be right: nothing about a reaction is in any of the three.
    assertTrue(receiver.bodies.none { it.contains("👀") }, receiver.bodies.toString())
  }

  @Test
  fun `a webhook that never answers does not hold up the comment write`() {
    // The receiver accepts the connection and then does nothing at all, which is the shape that
    // hurts: a refused connection fails fast, and a wedged one fails only after a timeout.
    val receiver = receiver(hangSeconds = 60)
    val server = start(receiver.url)
    createDesign(server)

    val started = System.nanoTime()
    // Three in a row, so the delivery worker is definitely mid-hang on the second and third: if
    // anything about the write path waited on the far end, this is where it would show.
    repeat(3) { index ->
      val posted =
        comments(
          server,
          "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
          """{"body":"Comment $index."}""",
        )
      assertEquals(201, posted.first, posted.second)
    }
    val elapsedMillis = (System.nanoTime() - started) / 1_000_000

    // The sender's own request timeout is five seconds; three sequential writes that waited on it
    // would take fifteen. Generous enough not to flake on a loaded CI box, and an order of
    // magnitude under what a synchronous delivery would cost.
    assertTrue(elapsedMillis < 4_000, "three comment writes took ${elapsedMillis}ms")
    assertTrue(receiver.arrived.await(10, TimeUnit.SECONDS), "the receiver was never called")
  }

  @Test
  fun `the slack adapter is what a slack-shaped hook receives`() {
    val receiver = receiver()
    val server = start(receiver.url, format = "slack")
    createDesign(server)

    comments(
      server,
      "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
      """{"body":"Ship it.","displayName":"Yuri"}""",
    )

    val body = Json.parseToJsonElement(receiver.awaitOne()).jsonObject
    assertEquals(setOf("text"), body.keys)
    val text = body["text"]!!.jsonPrimitive.content
    assertTrue(text.startsWith("*Yuri* started a thread on <http://127.0.0.1:"), text)
    assertTrue(text.contains("|Discussed screen>"), text)
    assertTrue(text.contains("> Ship it."), text)
  }

  // -------------------------------------------------------------------------------------------

  /** A real HTTP endpoint that records what it was sent, and can be told to never answer. */
  @Test
  fun `a redirect is a failed delivery, because nothing was delivered`() {
    // Redirects are not followed: a webhook URL is a credential and the destination of a 302 is
    // chosen by whatever answered rather than by the operator. So a 3xx means the body went
    // nowhere, and counting it as delivered would retire the retry and swallow the log line for a
    // hook that is quietly posting nothing.
    val redirecting = redirectReceiver(302)
    val sender = HttpCommentWebhookSender(CommentWebhookConfig(redirecting.url))

    assertTrue(!sender.post("""{"hello":"world"}"""), "a 302 must not read as delivered")
    assertTrue(redirecting.bodies.isNotEmpty(), "the receiver should still have seen the POST")

    // The 2xx boundary itself, so the check cannot drift back to `< 400`.
    val accepting = redirectReceiver(200)
    assertTrue(HttpCommentWebhookSender(CommentWebhookConfig(accepting.url)).post("{}"))
  }

  /** A receiver that answers one fixed status and never redirects the client anywhere real. */
  private fun redirectReceiver(status: Int): Receiver {
    val bodies = CopyOnWriteArrayList<String>()
    val arrived = CountDownLatch(1)
    val engine =
      embeddedServer(CIO, host = "127.0.0.1", port = 0) {
          routing {
            post("/hook") {
              bodies += call.receiveText()
              arrived.countDown()
              call.response.header(HttpHeaders.Location, "http://127.0.0.1:1/elsewhere")
              call.respondText("", status = HttpStatusCode.fromValue(status))
            }
          }
        }
        .also { it.start(wait = false) }
    val port = runBlocking { engine.engine.resolvedConnectors().first().port }
    closeables += Closeable { engine.stop(0, 0) }
    return Receiver("http://127.0.0.1:$port/hook", bodies, arrived)
  }

  private fun receiver(hangSeconds: Long = 0): Receiver {
    val bodies = CopyOnWriteArrayList<String>()
    val arrived = CountDownLatch(1)
    val engine =
      embeddedServer(CIO, host = "127.0.0.1", port = 0) {
          routing {
            post("/hook") {
              val body = call.receiveText()
              bodies += body
              arrived.countDown()
              if (hangSeconds > 0) delay(hangSeconds * 1_000)
              call.respondText("ok")
            }
          }
        }
        .also { it.start(wait = false) }
    val port = runBlocking { engine.engine.resolvedConnectors().first().port }
    closeables += Closeable { engine.stop(0, 0) }
    return Receiver("http://127.0.0.1:$port/hook", bodies, arrived)
  }

  private class Receiver(
    val url: String,
    val bodies: CopyOnWriteArrayList<String>,
    val arrived: CountDownLatch,
  ) {
    fun awaitOne(): String = await(1).single()

    /** The first [count] bodies, in arrival order, once that many have arrived. */
    fun await(count: Int): List<String> {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
      while (bodies.size < count && System.nanoTime() < deadline) Thread.sleep(25)
      assertTrue(bodies.size >= count, "expected $count deliveries, saw ${bodies.size}: $bodies")
      // A beat past the last expected body, so a test asserting that something did NOT arrive is
      // not merely asserting that it had not arrived yet.
      Thread.sleep(250)
      return bodies.take(count)
    }
  }

  private fun start(webhookUrl: String, format: String = "plain"): ServeHttpServer {
    val comments = ServeUiBuilderCommentStore(stateDirectory.resolve("comments"))
    val registry = ServeSessionRegistry(open = { null })
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs =
          CurrentM3UiBuilderCatalogExecutor(
            catalogSystemIds = setOf(CATALOG_SYSTEM_ID),
            exportCapabilities =
              ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1(
                composeCode = true,
                svg = false,
                png = false,
              ),
          ),
        exporter =
          ScreenGeneratorComposeExportExecutor(
            ComponentRecordSource(
              mapOf(CATALOG_SYSTEM_ID to ScreenGeneratorScreenFixture.componentsFile())
            )::record
          ),
      )
    val server =
      ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = OPERATOR_TOKEN,
        sessions = registry,
        defaultSessionId = "unused",
        machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
        uiBuilderService = service,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        uiBuilderCommentStore = comments,
      )
    // Wired exactly as `ServeRunner` wires it: the same store the routes write through, the
    // design's
    // title and catalog read from the service, and the origin resolved per event.
    val webhook =
      ServeUiBuilderCommentWebhook(
        config = CommentWebhookConfig(webhookUrl, CommentWebhookFormat.parse(format)!!),
        designs = { designId ->
          service
            .adminListDesigns()
            .firstOrNull { it.designId == designId }
            ?.let { CommentWebhookDesign(it.title, it.catalogPin.systemId) }
        },
        baseUrl = { ServeUrls.origin("127.0.0.1", server.port) },
      )
    closeables += webhook.attach(comments)
    closeables += webhook
    closeables += Closeable { registry.close() }
    closeables += Closeable { server.stop() }
    server.start()
    return server
  }

  private fun comments(
    server: ServeHttpServer,
    path: String,
    body: String,
  ): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$path")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use { it.code to it.body.string() }

  private fun threadIdOf(board: String): String = idOf(board, "\"id\":\"t-")

  private fun commentIdOf(board: String): String = idOf(board, "\"id\":\"c-")

  private fun idOf(board: String, marker: String): String {
    val start = board.indexOf(marker)
    assertTrue(start >= 0, board)
    val from = start + "\"id\":\"".length
    return board.substring(from, board.indexOf('"', from))
  }

  private fun createDesign(server: ServeHttpServer) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "create-1",
        actorId = "operator",
        request = CreateDesignRequestV1(document()),
      )
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/api/ui-builder/v1/requests")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use { assertEquals(200, it.code, it.body.string()) }
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Discussed screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG_SYSTEM_ID, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("row")),
            ),
          "row" to
            DesignNodeV1(
              id = "row",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            ),
        ),
    )

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-comment-webhook-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val DESIGN_ID = "discussed-screen"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
