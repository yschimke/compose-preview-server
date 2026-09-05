package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.nio.file.Path
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
import org.junit.jupiter.api.io.TempDir

/**
 * The other direction: a designer edits, and an agent that is waiting is told.
 *
 * An agent could already be *seen* — every write reaches the browser's `/updates` socket whatever
 * transport made it, because they all pass through one `PersistentUiBuilderService.apply`. It could
 * not *see*: the MCP surface had `ui_builder_get_design` and nothing that waits, so the only way an
 * agent learned about a person's edit was to ask again.
 *
 * MCP's own server-to-client notifications are not available to close that gap, and deliberately:
 * `/mcp` is stateless JSON-RPC, `GET /mcp` answers 405, and `initialize` advertises `resources:
 * {"subscribe": false}`. So the wait is a tool call, over the same subscription the socket uses —
 * which is what this test is here to prove, along with the two things a waiter must not do: wake
 * for presence, and answer an idle design with something that reads like an edit.
 */
class ServeUiBuilderAwaitDesignTest {
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
  fun `an edit made over HTTP wakes an agent waiting on the tool`() {
    val server = start()
    createDesign(server)

    val awaited = CompletableTool()
    val waiter = Thread {
      awaited.complete(
        envelope(
          server,
          ServeUiBuilderMcp.AWAIT_DESIGN,
          """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":30}""",
        )
      )
    }
      .also { it.isDaemon = true }
      .also { it.start() }

    // The designer's browser, editing the design it has open.
    val applied = insertSubtitle(server, baseRevision = 0)
    assertEquals(200, applied.first, applied.second)

    val update = awaited.await()
    waiter.join(5_000)
    // The released update envelope — the identical frame the browser's own socket receives, rather
    // than a shape invented for this door.
    assertTrue(update.contains("\"designId\":\"$DESIGN_ID\""), update)
    assertTrue(update.contains("Two sessions today"), update)
    assertTrue(update.contains("\"throughSequence\":1"), update)
  }

  @Test
  fun `a design that nobody touches answers timedOut rather than an edit`() {
    val server = start()
    createDesign(server)
    val idle =
      envelope(
        server,
        ServeUiBuilderMcp.AWAIT_DESIGN,
        """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":1}""",
      )
    assertTrue(idle.contains("\"timedOut\":true"), idle)
    // The cursor comes back, so a caller that loops does not have to track it itself.
    assertTrue(idle.contains("\"afterSequence\":0"), idle)
  }

  @Test
  fun `somebody merely looking at the design does not wake the waiter`() {
    val server = start()
    createDesign(server)

    val awaited = CompletableTool()
    Thread {
      awaited.complete(
        envelope(
          server,
          ServeUiBuilderMcp.AWAIT_DESIGN,
          """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":3}""",
        )
      )
    }
      .also { it.isDaemon = true }
      .start()

    // A heartbeat, then a selection: presence is chrome, excluded by design from the document, the
    // revision and the sequence. An agent woken by it would spend a tool call on nothing.
    repeat(2) {
      val presence =
        request(
          server,
          "/api/ui-builder/v1/requests",
          json.encodeToString(
            HttpRequestEnvelopeV1(
              requestId = "presence-$it",
              actorId = "operator",
              request =
                UpdatePresenceRequestV1(
                  designId = DESIGN_ID,
                  presence =
                    PresenceV1(
                      actorId = "operator",
                      clientId = "browser-a",
                      displayName = "Yuri",
                      colorArgbHex = "#FFB9C3FF",
                      selectedNodeIds = listOf("column"),
                      observedRevision = 0,
                    ),
                ),
            )
          ),
        )
      assertEquals(200, presence.first, presence.second)
    }

    val reply = awaited.await()
    assertTrue(reply.contains("\"timedOut\":true"), reply)
  }

  @Test
  fun `a waiter already level with the design waits, and one behind it does not`() {
    val server = start()
    createDesign(server)
    insertSubtitle(server, baseRevision = 0)

    // Behind: answered at once with what it missed, without waiting out the timeout.
    val behind =
      envelope(
        server,
        ServeUiBuilderMcp.AWAIT_DESIGN,
        """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":30}""",
      )
    assertTrue(behind.contains("Two sessions today"), behind)

    // Level: an empty catch-up delta is "nothing has happened", not news.
    val level =
      envelope(
        server,
        ServeUiBuilderMcp.AWAIT_DESIGN,
        """{"designId":"$DESIGN_ID","afterSequence":1,"waitSeconds":1}""",
      )
    assertTrue(level.contains("\"timedOut\":true"), level)
  }

  @Test
  fun `a zero wait is a non-blocking check, not a report that nothing happened`() {
    val server = start()
    createDesign(server)
    insertSubtitle(server, baseRevision = 0)

    // `waitSeconds: 0` is the honest way to ask "has anything changed since my cursor" without
    // holding a subscriber slot. The edit is already committed and the subscription's catch-up
    // hands it over before the wait even begins, so answering `timedOut` here would be a lie a
    // polling caller acts on — and the caller that most wants a zero wait is the one polling.
    val checked =
      envelope(
        server,
        ServeUiBuilderMcp.AWAIT_DESIGN,
        """{"designId":"$DESIGN_ID","afterSequence":0,"waitSeconds":0}""",
      )
    assertTrue(checked.contains("Two sessions today"), checked)
    assertTrue(!checked.contains("\"timedOut\":true"), checked)
  }

  @Test
  fun `a design this actor cannot read is refused by the service, not by this door`() {
    val server = start()
    val result =
      call(
        server,
        ServeUiBuilderMcp.AWAIT_DESIGN,
        """{"designId":"not-a-design","afterSequence":0,"waitSeconds":1}""",
      )
    assertEquals(true, result["isError"]?.jsonPrimitive?.content?.toBoolean())
  }

  @Test
  fun `the tool is listed wherever the builder is`() {
    val tools = tools(start())
    assertTrue(ServeUiBuilderMcp.AWAIT_DESIGN in tools, tools.toString())
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
      assertTrue(latch.await(30, TimeUnit.SECONDS), "the waiting agent never got an answer")
      return requireNotNull(value)
    }
  }

  private fun insertSubtitle(server: RunningServer, baseRevision: Long): Pair<Int, String> =
    request(
      server,
      "/api/ui-builder/v1/requests",
      json.encodeToString(
        HttpRequestEnvelopeV1(
          requestId = "apply-1",
          actorId = "operator",
          request =
            ApplyOperationRequestV1(
              DesignCommandV1(
                designId = DESIGN_ID,
                operationId = "browser-op-1",
                actorId = "operator",
                clientId = "browser-a",
                baseRevision = baseRevision,
                operations =
                  listOf(
                    InsertNodeMutationV1(
                      node =
                        DesignNodeV1(
                          id = "subtitle",
                          componentId = "m3/text",
                          properties = mapOf("text" to StringValueV1("Two sessions today")),
                        ),
                      location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
                    )
                  ),
              )
            ),
        )
      ),
    )

  private fun createDesign(server: RunningServer) {
    val created =
      request(
        server,
        "/api/ui-builder/v1/requests",
        json.encodeToString(
          HttpRequestEnvelopeV1(
            requestId = "create-1",
            actorId = "operator",
            request = CreateDesignRequestV1(document()),
          )
        ),
      )
    assertEquals(200, created.first, created.second)
  }

  private fun request(server: RunningServer, path: String, body: String): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}$path")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use { it.code to it.body.string() }

  private fun start(): RunningServer {
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
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  private fun envelope(server: RunningServer, tool: String, arguments: String): String {
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
      title = "Watched screen",
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
              slots = mapOf("children" to listOf("title")),
            ),
          "title" to
            DesignNodeV1(
              id = "title",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            ),
        ),
    )

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-await-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val DESIGN_ID = "watched-screen"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
