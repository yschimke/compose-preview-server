package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
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
 * A design authored, edited and exported entirely over MCP.
 *
 * ## Why an integration test rather than unit ones
 *
 * Every seam this change adds is a wiring seam, and each of them fails silently in a unit test:
 * that the tools appear in `tools/list` only when a service is configured, that a tool name routes
 * to the UI-builder door rather than falling through to the catalog surface, that the capability
 * checked is the one the HTTP routes check, that the actor reaching `UiBuilderProtocolMapper` is
 * the authenticated one rather than a field from the message, and that the reply is the released
 * [McpResponseEnvelopeV1]. A mock of the service port proves the JSON and none of that.
 *
 * So this starts the real server, wired the way `ServeRunner` wires it, and asks it as an agent
 * would: list, create, read, mutate, export — with the export's Kotlin as the last assertion,
 * because that is the whole point of the door existing.
 */
class ServeUiBuilderMcpIntegrationTest {
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
  }

  @Test
  fun `an agent creates, edits and exports a design without touching a browser`() {
    val server = start()

    // 1. What can be pinned. An agent that skipped this would be guessing a catalog revision, and
    // the service checks it.
    val catalogs = envelope(server, ServeUiBuilderMcp.LIST_CATALOGS)
    assertTrue(catalogs.contains(CATALOG_SYSTEM_ID), catalogs)

    // 2. A design.
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
      )
    assertIs<SnapshotResponseV1>(response(created))

    // 3. What revision to quote. Read back rather than assumed: `baseRevision` is how a concurrent
    // edit is detected, and an agent that guessed it would be the concurrent edit.
    val snapshot =
      assertIs<SnapshotResponseV1>(
        response(envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}"""))
      )
    assertEquals("agent-screen", snapshot.snapshot.designId)
    val revision = snapshot.snapshot.state.document.revision

    // 4. The edit: a second text in the column, which is "add a component to a container" — the
    // thing the browser builder does by dragging.
    val operations =
      json.encodeToString(
        ListSerializer(DesignMutationV1.serializer()),
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
    val applied =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{"designId":"agent-screen","operationId":"agent-op-1","baseRevision":$revision,"operations":$operations}""",
      )
    val outcome = assertIs<OperationOutcomeResponseV1>(response(applied))
    // Accepted, not merely answered: a rejection also comes back as an outcome, so asserting the
    // response type alone would pass on a refused edit.
    val accepted = assertIs<AcceptedOutcomeV1>(outcome.outcome)
    assertEquals(revision + 1, accepted.committedRevision, applied)

    // 5. The Kotlin. Both texts, from the real generator against the configured record — the same
    // answer the browser's code pane shows for the same design.
    val exported =
      envelope(
        server,
        ServeUiBuilderMcp.EXPORT,
        """{"designId":"agent-screen","format":"compose"}""",
      )
    val artifact = assertIs<ExportResponseV1>(response(exported)).artifact
    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    assertTrue(artifact.content.contains("""Text(text = "Opening keynote""""), artifact.content)
    assertTrue(artifact.content.contains("""Text(text = "Two sessions today""""), artifact.content)
    assertTrue(artifact.content.contains("Column("), artifact.content)
  }

  @Test
  fun `the tools are listed only where a UI builder is actually served`() {
    val withBuilder = tools(start())
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.all { it in withBuilder }, withBuilder.toString())

    running?.close()
    running = null
    // A box that serves no builder does not advertise the door. Listed-and-failing would tell an
    // agent this server can do something it cannot, which is worse than silence.
    val without = tools(start(withUiBuilder = false))
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.none { it in without }, without.toString())
    assertTrue("render_preview" in without, without.toString())
  }

  @Test
  fun `the native render tool appears only where the host can compile`() {
    // Two absences, not one: a box with no builder has no UI-builder tools at all, and a box with
    // a builder but no compiler has the six that need no compiler and not the seventh. A client
    // reads which of the three it is talking to off `tools/list` rather than off a failed call.
    val withoutCompiler = tools(start())
    assertTrue(
      ServeUiBuilderMcp.TOOL_NAMES.all { it in withoutCompiler },
      withoutCompiler.toString(),
    )
    assertTrue(
      ServeUiBuilderMcp.NATIVE_TOOL_NAMES.none { it in withoutCompiler },
      withoutCompiler.toString(),
    )
  }

  @Test
  fun `a caller without the capability is refused by name rather than served`() {
    // The service is configured; the authorization is not. The refusal has to say which grant is
    // missing, because "unauthorized" on a surface with three capabilities is not actionable.
    val server = start(withAuthorization = false)
    val result = call(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")

    assertEquals(true, result["isError"]?.jsonPrimitive?.content?.toBoolean())
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertTrue(text.contains("read grant"), text)
  }

  private fun start(
    withUiBuilder: Boolean = true,
    withAuthorization: Boolean = true,
  ): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    val service =
      if (!withUiBuilder) null
      else
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
            if (withAuthorization)
              ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null)
            else null,
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  /** One `tools/call`, as its raw MCP result object. */
  private fun call(server: RunningServer, tool: String, arguments: String = "{}") =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
      )["result"]!!
      .jsonObject

  /** The UI-builder envelope a tool replied with, as text. */
  private fun envelope(server: RunningServer, tool: String, arguments: String = "{}"): String {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    return text
  }

  private fun response(envelope: String) =
    json.decodeFromString(McpResponseEnvelopeV1.serializer(), envelope).response

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

  /**
   * A column holding one text, so the mutation above has a slot to insert into and the export has
   * something to nest. Pinned to the packaged M3 catalog, like the HTTP export test's document.
   */
  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "agent-screen",
      title = "Agent screen",
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
              slots = mapOf("children" to listOf("session")),
            ),
          "session" to
            DesignNodeV1(
              id = "session",
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
    const val OPERATOR_TOKEN = "ui-builder-mcp-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
