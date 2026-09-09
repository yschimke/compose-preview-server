package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.io.TempDir

/**
 * One discussion, three doors: the browser's REST calls, the browser's socket, and an agent's MCP
 * tools — including the one that *waits*.
 *
 * ## Why an integration test rather than unit ones
 *
 * The store's own rules are unit-tested next door. What cannot be unit-tested is the claim the
 * feature is actually making: that a comment typed in a browser wakes an agent that is waiting, and
 * that an agent's reply reaches a page that is open, without either of them polling for the other.
 * That claim is made of wiring — the routes registered only where a store is configured, the socket
 * subscribing before it reads, the MCP tools reaching the same store the routes reach, and one
 * design's access control gating all of it — and every one of those fails silently against a mock.
 *
 * So this starts the real server, wired the way `ServeRunner` wires it, and plays both parts.
 */
class ServeUiBuilderCommentsIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private var running: RunningServer? = null

  @AfterTest
  fun tearDown() {
    running?.close()
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun `a comment in the browser wakes an agent that is waiting for one`() {
    val server = start()
    createDesign(server)

    // The agent parks on the feed at sequence 0. Nothing has been said, so this call is holding
    // rather than returning — which is the whole point of it existing.
    val awaited = CompletableTool()
    val waiter = Thread {
      awaited.complete(
        envelope(
          server,
          ServeUiBuilderMcp.AWAIT_COMMENTS,
          """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":30}""",
        )
      )
    }
      .also { it.isDaemon = true }
      .also { it.start() }

    // Meanwhile, a designer types into the page.
    val posted =
      comments(
        server,
        "POST",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"This row should be a card.","displayName":"Yuri"}""",
      )
    assertEquals(201, posted.first, posted.second)

    val reply = awaited.await()
    waiter.join(5_000)
    assertTrue(reply.contains("This row should be a card."), reply)
    assertTrue(reply.contains("\"sequence\":1"), reply)
  }

  @Test
  fun `an agent's reply reaches a page that is already open, over the socket`() {
    val server = start()
    createDesign(server)
    val opening =
      comments(
        server,
        "POST",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"Why is the padding 24?"}""",
      )
    val threadId = threadIdOf(opening.second)

    val frames = CopyOnWriteArrayList<String>()
    // Two latches, not one: the socket sends the board it finds on connect, and the page is only
    // "already open" once that has arrived. Posting before it would be testing a socket that
    // opened after the reply, which is the case the connect frame exists to cover rather than the
    // one this test is about.
    val connected = CountDownLatch(1)
    val replied = CountDownLatch(2)
    val socket = openCommentSocket(server, frames, listOf(connected, replied))
    try {
      assertTrue(connected.await(10, TimeUnit.SECONDS), "the socket sent no board on connect")
      assertTrue(frames.single().contains("Why is the padding 24?"), frames.toString())
      // The agent answers in the same thread, through MCP, as itself.
      val answered =
        envelope(
          server,
          ServeUiBuilderMcp.POST_COMMENT,
          """{"designId":"$DESIGN_ID","threadId":"$threadId","body":"Because the spec pins it."}""",
        )
      assertTrue(answered.contains("Because the spec pins it."), answered)

      assertTrue(replied.await(10, TimeUnit.SECONDS), frames.toString())
      val last = frames.last()
      assertTrue(last.contains("Because the spec pins it."), last)
      // Attributed to the agent's own grant, and marked as an agent — the panel draws a badge off
      // this, and an agent that could post as a person would make the badge a lie.
      assertTrue(last.contains("\"authorKind\":\"agent\""), last)
    } finally {
      socket.close(1000, null)
    }
  }

  @Test
  fun `a thread resolved over MCP reads as resolved over HTTP`() {
    val server = start()
    createDesign(server)
    val opening =
      comments(
        server,
        "POST",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"Spacing is off here.","anchor":{"x":0.5,"y":0.25}}""",
      )
    val threadId = threadIdOf(opening.second)

    envelope(
      server,
      ServeUiBuilderMcp.RESOLVE_COMMENT_THREAD,
      """{"designId":"$DESIGN_ID","threadId":"$threadId","resolved":true}""",
    )

    val read = comments(server, "GET", "/api/ui-builder/v1/designs/$DESIGN_ID/comments", null)
    assertEquals(200, read.first, read.second)
    assertTrue(read.second.contains("\"resolved\":true"), read.second)
    // The pin the panel draws survives the round trip: a resolved thread is still on the frame,
    // greyed rather than gone.
    assertTrue(read.second.contains("\"x\":0.5"), read.second)
  }

  @Test
  fun `the discussion is refused for a design this actor cannot read`() {
    val server = start()
    // No design was created, so there is nothing here to discuss. A 404 rather than an empty board
    // is what stops a caller enumerating which design ids exist by watching which posts succeed.
    val read = comments(server, "GET", "/api/ui-builder/v1/designs/not-a-design/comments", null)
    assertEquals(404, read.first, read.second)
  }

  @Test
  fun `a design that exists but was never shared is refused the same way as one that does not`() {
    val server = start()
    createDesign(server)

    // The reviewer holds a credential this host authenticates, and the design is real — what they
    // do not hold is any access to THIS design. The board must answer exactly as it does for an id
    // that names nothing, or the pair of replies tells a caller which private design ids exist.
    //
    // Pinned separately from the missing-design case because the two used to be answered by
    // different code: the board asked by requesting a whole snapshot and seeing whether one came
    // back, where the other sidecars ask the design's access control directly. One question with
    // two implementations is one that can drift, and this is the reading that would drift.
    for (path in
      listOf(
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        "/api/ui-builder/v1/designs/no-such-design/comments",
      )) {
      val read = comments(server, "GET", path, null, token = REVIEWER_TOKEN)
      assertEquals(404, read.first, "$path -> ${read.second}")
      val posted =
        comments(server, "POST", path, """{"body":"can I see this?"}""", token = REVIEWER_TOKEN)
      assertEquals(404, posted.first, "$path -> ${posted.second}")
    }
  }

  @Test
  fun `a viewer shared into a design may still comment on it`() {
    val server = start()
    createDesign(server)
    shareAsViewer(server, "github:reviewer")

    // The board deliberately stays on READ where the reference overlay and the links record take
    // the design's own WRITE action. Its whole reason to exist is a reviewer who may see a screen
    // saying so on it; requiring WRITE to comment would lock the reviewer out of the review
    // surface. This test is here so that a later tightening of the sidecars has to say no to it on
    // purpose rather than by sweeping all three together.
    val posted =
      comments(
        server,
        "POST",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        """{"body":"The play icon looks like a cross"}""",
        token = REVIEWER_TOKEN,
      )
    assertEquals(201, posted.first, posted.second)

    val read =
      comments(
        server,
        "GET",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
        null,
        token = REVIEWER_TOKEN,
      )
    assertEquals(200, read.first, read.second)
    assertTrue(read.second.contains("looks like a cross"), read.second)
  }

  /** Share the design with [actorId] as a viewer, as its owner. */
  private fun shareAsViewer(server: RunningServer, actorId: String) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "share-1",
        actorId = "operator",
        request =
          UpdateDesignAccessRequestV1(
            designId = DESIGN_ID,
            baseAccessRevision = 0,
            mutations =
              listOf(
                GrantActorAccessMutationV1(
                  actorId,
                  DesignAccessRoleV1.VIEWER,
                  listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
                )
              ),
          ),
      )
    val response =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .post(json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        )
        .execute()
    response.use { assertEquals(200, it.code, it.body.string()) }
  }

  @Test
  fun `an unauthenticated caller is refused before the store is touched`() {
    val server = start()
    createDesign(server)
    val response =
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/$DESIGN_ID/comments"
            )
            .build()
        )
        .execute()
    response.use { assertEquals(401, it.code) }
  }

  @Test
  fun `the watch route says nothing rather than repeating what the caller already has`() {
    val server = start()
    createDesign(server)
    comments(
      server,
      "POST",
      "/api/ui-builder/v1/designs/$DESIGN_ID/comments",
      """{"body":"First."}""",
    )
    // Already at sequence 1, so there is no news: 204, which a looping client reads as "ask again"
    // rather than as "the discussion was emptied".
    val timedOut =
      comments(
        server,
        "GET",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments/watch?afterSequence=1&waitSeconds=1",
        null,
      )
    assertEquals(204, timedOut.first, timedOut.second)

    val behind =
      comments(
        server,
        "GET",
        "/api/ui-builder/v1/designs/$DESIGN_ID/comments/watch?afterSequence=0&waitSeconds=1",
        null,
      )
    assertEquals(200, behind.first, behind.second)
    assertTrue(behind.second.contains("First."), behind.second)
  }

  @Test
  fun `a comment the agent has not seen rides along on the reply it is already reading`() {
    val server = start()
    createDesign(server)
    // A designer says something while the agent is mid-edit. Nothing tells it to look.
    server.comments!!.post(
      DESIGN_ID,
      "github:yuri",
      CommentPostRequest(
        anchor = StoredCommentAnchor(nodeId = "row"),
        body = "The play icon looks like a cross.",
        displayName = "Yuri",
      ),
    )

    val read = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$DESIGN_ID"}""")
    val notice = Json.parseToJsonElement(read).jsonObject["comments"]!!.jsonObject
    assertEquals(1, notice["unacknowledged"]!!.jsonPrimitive.content.toInt())
    // The excerpt is the part that survives an agent skimming: a count is easy to skip past, a
    // quoted sentence naming a node it is holding is not.
    val thread = notice["threads"]!!.jsonArray.single().jsonObject
    assertEquals("The play icon looks like a cross.", thread["excerpt"]!!.jsonPrimitive.content)
    assertEquals("row", thread["nodeId"]!!.jsonPrimitive.content)
    assertEquals("Yuri", thread["author"]!!.jsonPrimitive.content)

    // Acknowledging is not resolving: the block goes, the thread stays open.
    val acknowledged =
      envelope(
        server,
        ServeUiBuilderMcp.ACKNOWLEDGE_COMMENT,
        """{"designId":"$DESIGN_ID","threadId":"${thread["id"]!!.jsonPrimitive.content}"}""",
      )
    assertTrue(acknowledged.contains("\"resolved\":false"), acknowledged)

    val quiet = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$DESIGN_ID"}""")
    assertEquals(null, Json.parseToJsonElement(quiet).jsonObject["comments"], quiet)
  }

  @Test
  fun `a reaction is the lightest acknowledgement, and reaches the page as one`() {
    val server = start()
    createDesign(server)
    val posted =
      server.comments!!.post(DESIGN_ID, "github:yuri", CommentPostRequest(body = "Padding?"))
    val commentId =
      (posted as CommentWriteResult.Stored).board.threads.single().comments.single().id

    val reacted =
      envelope(
        server,
        ServeUiBuilderMcp.REACT_TO_COMMENT,
        """{"designId":"$DESIGN_ID","commentId":"$commentId","reaction":"👀"}""",
      )
    assertTrue(reacted.contains("👀"), reacted)

    // Read back over HTTP, which is what the panel does: the chip is there, the thread is still
    // open, and the agent is no longer being nagged about a comment it has picked up.
    val read = comments(server, "GET", "/api/ui-builder/v1/designs/$DESIGN_ID/comments", null)
    assertEquals(200, read.first, read.second)
    assertTrue(read.second.contains("👀"), read.second)
    assertTrue(read.second.contains("\"resolved\":false"), read.second)
    val quiet = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$DESIGN_ID"}""")
    assertEquals(null, Json.parseToJsonElement(quiet).jsonObject["comments"], quiet)
  }

  @Test
  fun `the comment tools are listed only where the host keeps a discussion`() {
    val withStore = tools(start())
    assertTrue(
      ServeUiBuilderMcp.COMMENT_TOOL_NAMES.all { it in withStore },
      withStore.toString(),
    )

    running?.close()
    running = null
    val without = tools(start(withComments = false))
    assertTrue(
      ServeUiBuilderMcp.COMMENT_TOOL_NAMES.none { it in without },
      without.toString(),
    )
    // The rest of the door is still open: absence here is about this host's storage, not about the
    // builder.
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.all { it in without }, without.toString())
  }

  /** One value handed between threads, with a bounded wait and a legible failure. */
  private class CompletableTool {
    private val latch = CountDownLatch(1)
    @Volatile private var value: String? = null

    fun complete(result: String) {
      value = result
      latch.countDown()
    }

    fun await(): String {
      assertTrue(latch.await(30, TimeUnit.SECONDS), "the awaiting agent was never woken")
      return requireNotNull(value)
    }
  }

  private fun openCommentSocket(
    server: RunningServer,
    frames: CopyOnWriteArrayList<String>,
    received: List<CountDownLatch>,
  ): WebSocket =
    client.newWebSocket(
      Request.Builder()
        .url(
          "ws://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/$DESIGN_ID/comments/updates"
        )
        .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
        .build(),
      object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
          frames += text
          received.forEach(CountDownLatch::countDown)
        }
      },
    )

  private fun threadIdOf(board: String): String =
    Json.parseToJsonElement(board)
      .jsonObject["threads"]!!
      .jsonArray
      .first()
      .jsonObject["id"]!!
      .jsonPrimitive
      .content

  /** One comment route call, as its status and body. */
  private fun comments(
    server: RunningServer,
    method: String,
    path: String,
    body: String?,
    token: String = OPERATOR_TOKEN,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.server.port}$path")
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
        .build()
    return client.newCall(request).execute().use { it.code to it.body.string() }
  }

  /**
   * The operator and a second collaborator, both holding every UI-builder capability this host
   * hands out.
   *
   * Two are needed to say anything about a *design's* access control rather than the host's: with
   * one credential every design is owned by the caller, and "a viewer may comment" is not a
   * sentence the fixture can express.
   */
  private fun twoCredentials(): ServeUiBuilderAuthorization =
    ServeUiBuilderAuthorization { call, _, presented ->
      when (presented ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
        OPERATOR_TOKEN -> UiBuilderAuthorizationDecision.Authorized("operator")
        REVIEWER_TOKEN -> UiBuilderAuthorizationDecision.Authorized("github:reviewer")
        else -> UiBuilderAuthorizationDecision.Missing
      }
    }

  private fun createDesign(server: RunningServer) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "create-1",
        actorId = "operator",
        request = CreateDesignRequestV1(document()),
      )
    val response =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .post(json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        )
        .execute()
    response.use { assertEquals(200, it.code, it.body.string()) }
  }

  private fun start(withComments: Boolean = true): RunningServer {
    val store =
      if (!withComments) null
      else ServeUiBuilderCommentStore(stateDirectory.resolve("comments-$withComments"))
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
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderAuthorization = twoCredentials(),
          uiBuilderCommentStore = store,
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry, store).also { running = it }
  }

  private fun envelope(server: RunningServer, tool: String, arguments: String = "{}"): String {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    return text
  }

  private fun call(server: RunningServer, tool: String, arguments: String) =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
      )["result"]!!
      .jsonObject

  private fun tools(server: RunningServer): List<String> =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private fun post(server: RunningServer, body: String) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use {
        val text = it.body.string()
        assertEquals(200, it.code, text)
        Json.parseToJsonElement(text).jsonObject
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

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
    /**
     * The same store the routes and the MCP tools reach.
     *
     * Held so a test can speak as a *second* actor. Both doors in this test authenticate with the
     * one operator token, so every comment posted through them is the agent's own — and an agent is
     * never told to catch up with what it said itself, which is the case the unacknowledged notice
     * exists for.
     */
    val comments: ServeUiBuilderCommentStore?,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-comments-operator-token"
    const val REVIEWER_TOKEN = "ui-builder-comments-reviewer-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val DESIGN_ID = "discussed-screen"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
