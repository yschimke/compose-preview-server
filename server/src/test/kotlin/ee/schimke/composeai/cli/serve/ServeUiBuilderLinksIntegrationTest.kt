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
import java.nio.file.Path
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
 * What a design is for, through both doors it is reachable by: the panel's REST calls and an
 * agent's MCP tools.
 *
 * ## Why an integration test rather than unit ones
 *
 * The store's own rules — what a link may be, what an empty record means — are unit-tested next
 * door. What cannot be unit-tested is the claim the feature makes: that the record is gated by the
 * *design's* access control rather than by the route's capability alone, that an agent opening a
 * design is told what it is for without asking, and that the reverse lookup answers a question
 * about designs the caller can actually open. Every one of those is wiring, and every one of them
 * fails silently against a mock.
 *
 * So this starts the real server, wired the way `ServeRunner` wires it, with three credentials: the
 * operator, a viewer who may read the surface and not write it, and a stranger who may do both and
 * owns a design of their own.
 */
class ServeUiBuilderLinksIntegrationTest {
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
  fun `a viewer may read a design's links and may not write them`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)

    // Nothing recorded yet: 404 rather than an empty record, because "nobody has said what this is
    // for" and "this is for nothing" are different answers.
    assertEquals(404, links(server, VIEWER_TOKEN, "GET", designPath(DESIGN_ID), null).first)

    val written =
      links(
        server,
        OPERATOR_TOKEN,
        "PUT",
        designPath(DESIGN_ID),
        """{"issue":"$ISSUE","pr":"https://github.com/yschimke/compose-preview-server/pull/34"}""",
      )
    assertEquals(200, written.first, written.second)

    val read = links(server, VIEWER_TOKEN, "GET", designPath(DESIGN_ID), null)
    assertEquals(200, read.first, read.second)
    assertTrue(read.second.contains(ISSUE), read.second)

    // The same viewer, one verb along: the route capability is what stops them, before the design
    // is touched at all.
    val refused =
      links(server, VIEWER_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""")
    assertEquals(403, refused.first, refused.second)
    assertEquals(403, links(server, VIEWER_TOKEN, "DELETE", designPath(DESIGN_ID), null).first)
  }

  @Test
  fun `a link this host will not keep is refused with the reason, and nothing is stored`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)

    val refused =
      links(
        server,
        OPERATOR_TOKEN,
        "PUT",
        designPath(DESIGN_ID),
        """{"issue":"javascript:alert(1)"}""",
      )
    assertEquals(422, refused.first, refused.second)
    assertTrue(refused.second.contains("absolute http or https URL"), refused.second)
    assertEquals(404, links(server, OPERATOR_TOKEN, "GET", designPath(DESIGN_ID), null).first)
  }

  @Test
  fun `an emptied record is deleted, and deleting is 204 either way`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)

    // Deleting what was never there is the state the caller asked for.
    assertEquals(204, links(server, OPERATOR_TOKEN, "DELETE", designPath(DESIGN_ID), null).first)

    links(server, OPERATOR_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""")
    val emptied = links(server, OPERATOR_TOKEN, "PUT", designPath(DESIGN_ID), "{}")
    assertEquals(200, emptied.first, emptied.second)
    assertEquals(404, links(server, OPERATOR_TOKEN, "GET", designPath(DESIGN_ID), null).first)

    links(server, OPERATOR_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""")
    assertEquals(204, links(server, OPERATOR_TOKEN, "DELETE", designPath(DESIGN_ID), null).first)
    assertEquals(404, links(server, OPERATOR_TOKEN, "GET", designPath(DESIGN_ID), null).first)
  }

  @Test
  fun `a design this actor cannot read is a 404, not a record they may write against`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)
    links(server, OPERATOR_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""")

    // The stranger holds every capability this surface hands out and no access to this design. Both
    // verbs answer the same way, so nothing here says whether the design exists.
    assertEquals(404, links(server, STRANGER_TOKEN, "GET", designPath(DESIGN_ID), null).first)
    assertEquals(
      404,
      links(server, STRANGER_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""").first,
    )
    assertEquals(404, links(server, OPERATOR_TOKEN, "GET", designPath("not-a-design"), null).first)
  }

  @Test
  fun `an unauthenticated caller is refused before the store is touched`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)
    val response =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.server.port}${designPath(DESIGN_ID)}")
            .build()
        )
        .execute()
    response.use { assertEquals(401, it.code) }
  }

  @Test
  fun `an agent opening a design is told what it is for, without a second call`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)

    val quiet = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$DESIGN_ID"}""")
    assertEquals(null, Json.parseToJsonElement(quiet).jsonObject["links"], quiet)

    val set =
      envelope(
        server,
        ServeUiBuilderMcp.SET_LINKS,
        """{"designId":"$DESIGN_ID","issue":"$ISSUE","previous":"checkout-v1"}""",
      )
    assertTrue(set.contains(ISSUE), set)

    val read = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$DESIGN_ID"}""")
    val carried = Json.parseToJsonElement(read).jsonObject["links"]!!.jsonObject
    assertEquals(ISSUE, carried["issue"]!!.jsonPrimitive.content)
    assertEquals("checkout-v1", carried["previous"]!!.jsonPrimitive.content)

    // The tool answers the same record the routes do, and a whole-record replace clears what it
    // leaves out.
    val fetched = envelope(server, ServeUiBuilderMcp.GET_LINKS, """{"designId":"$DESIGN_ID"}""")
    assertTrue(fetched.contains("checkout-v1"), fetched)
    envelope(server, ServeUiBuilderMcp.SET_LINKS, """{"designId":"$DESIGN_ID","issue":"$ISSUE"}""")
    val http = links(server, OPERATOR_TOKEN, "GET", designPath(DESIGN_ID), null)
    assertTrue(!http.second.contains("checkout-v1"), http.second)
  }

  @Test
  fun `a design this actor cannot open carries no links on its refusal`() {
    val server = start()
    // The stranger's design, with the stranger's links on it. The operator holds every capability
    // this host hands out and still may not open it.
    createDesign(server, STRANGER_TOKEN, OTHER_DESIGN_ID)
    links(server, STRANGER_TOKEN, "PUT", designPath(OTHER_DESIGN_ID), """{"issue":"$ISSUE"}""")

    // The service refuses, and a refusal is an ordinary reply envelope rather than a thrown error
    // — so the splice has to read the refusal, or it hands a private issue URL to anyone holding a
    // read capability who can guess a design id.
    val refused =
      envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"$OTHER_DESIGN_ID"}""")
    assertEquals(null, Json.parseToJsonElement(refused).jsonObject["links"], refused)
    assertTrue(!refused.contains(ISSUE), refused)
  }

  @Test
  fun `a link the tool will not keep is refused as an error rather than stored`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)

    val result =
      call(
        server,
        ServeUiBuilderMcp.SET_LINKS,
        """{"designId":"$DESIGN_ID","previous":"https://example.com/designs/checkout"}""",
      )
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(true, result["isError"]?.jsonPrimitive?.content?.toBoolean(), text)
    assertTrue(text.contains("design id on this host"), text)
  }

  @Test
  fun `the reverse lookup lists only the designs this actor may open`() {
    val server = start()
    createDesign(server, OPERATOR_TOKEN, DESIGN_ID)
    createDesign(server, STRANGER_TOKEN, OTHER_DESIGN_ID)
    links(server, OPERATOR_TOKEN, "PUT", designPath(DESIGN_ID), """{"issue":"$ISSUE"}""")
    links(server, STRANGER_TOKEN, "PUT", designPath(OTHER_DESIGN_ID), """{"issue":"$ISSUE"}""")

    val mine = links(server, OPERATOR_TOKEN, "GET", lookupPath(ISSUE), null)
    assertEquals(200, mine.first, mine.second)
    assertEquals(listOf(DESIGN_ID), designIdsOf(mine.second), mine.second)

    val theirs = links(server, STRANGER_TOKEN, "GET", lookupPath(ISSUE), null)
    assertEquals(listOf(OTHER_DESIGN_ID), designIdsOf(theirs.second), theirs.second)

    val none =
      links(server, OPERATOR_TOKEN, "GET", lookupPath("https://example.com/issues/99"), null)
    assertEquals(emptyList(), designIdsOf(none.second), none.second)

    // The issue is the question; without one there is nothing to answer.
    assertEquals(
      400,
      links(server, OPERATOR_TOKEN, "GET", UI_BUILDER_LINKS_LOOKUP_PATH, null).first,
    )
  }

  @Test
  fun `the links tools are listed only where the host records what a design is for`() {
    val withStore = tools(start())
    assertTrue(ServeUiBuilderMcp.LINKS_TOOL_NAMES.all { it in withStore }, withStore.toString())

    running?.close()
    running = null
    val without = tools(start(withLinks = false))
    assertTrue(ServeUiBuilderMcp.LINKS_TOOL_NAMES.none { it in without }, without.toString())
    // The rest of the door is still open: absence here is about this host's storage, not about the
    // builder.
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.all { it in without }, without.toString())
  }

  private fun designPath(designId: String) = "/api/ui-builder/v1/designs/$designId/links"

  private fun lookupPath(issue: String) =
    "$UI_BUILDER_LINKS_LOOKUP_PATH?issue=${java.net.URLEncoder.encode(issue, "UTF-8")}"

  private fun designIdsOf(body: String): List<String> =
    Json.parseToJsonElement(body).jsonObject["designIds"]!!.jsonArray.map {
      it.jsonPrimitive.content
    }

  /** One links route call, as its status and body. */
  private fun links(
    server: RunningServer,
    token: String,
    method: String,
    path: String,
    body: String?,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.server.port}$path")
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
        .build()
    return client.newCall(request).execute().use { it.code to it.body.string() }
  }

  private fun createDesign(server: RunningServer, token: String, designId: String) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "create-$designId",
        // The envelope's actor must be the one the credential authenticates as; the mapper refuses
        // to take the client's word for it, which is exactly why the stranger's design is theirs.
        actorId = if (token == STRANGER_TOKEN) "github:stranger" else "operator",
        request = CreateDesignRequestV1(document(designId)),
      )
    val response =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, token)
            .post(json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        )
        .execute()
    response.use { assertEquals(200, it.code, it.body.string()) }
  }

  private fun start(withLinks: Boolean = true): RunningServer {
    val store =
      if (!withLinks) null else ServeUiBuilderLinksStore(stateDirectory.resolve("links-$withLinks"))
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
          uiBuilderAuthorization = threeCredentials(),
          uiBuilderLinksStore = store,
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  /**
   * The operator, a read-only viewer of this surface, and a stranger who holds everything it hands
   * out.
   *
   * A stand-in for the real identity lane, which reads GitHub sessions and agent grants: what these
   * tests are about is which of the two gates refuses — the route's capability, or the design's own
   * access control — and that needs three credentials rather than one real one.
   */
  private fun threeCredentials(): ServeUiBuilderAuthorization =
    ServeUiBuilderAuthorization { call, capability, presented ->
      when (presented ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
        OPERATOR_TOKEN -> UiBuilderAuthorizationDecision.Authorized("operator")
        VIEWER_TOKEN ->
          if (capability == UiBuilderRouteCapability.READ)
            UiBuilderAuthorizationDecision.Authorized("operator")
          else UiBuilderAuthorizationDecision.Forbidden
        STRANGER_TOKEN -> UiBuilderAuthorizationDecision.Authorized("github:stranger")
        else -> UiBuilderAuthorizationDecision.Missing
      }
    }

  private fun envelope(
    server: RunningServer,
    tool: String,
    arguments: String = "{}",
    token: String = OPERATOR_TOKEN,
  ): String {
    val result = call(server, tool, arguments, token)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    return text
  }

  private fun call(
    server: RunningServer,
    tool: String,
    arguments: String,
    token: String = OPERATOR_TOKEN,
  ) =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
        token,
      )["result"]!!
      .jsonObject

  private fun tools(server: RunningServer): List<String> =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private fun post(server: RunningServer, body: String, token: String = OPERATOR_TOKEN) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, token)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use {
        val text = it.body.string()
        assertEquals(200, it.code, text)
        Json.parseToJsonElement(text).jsonObject
      }

  private fun document(designId: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = "Linked screen",
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
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-links-operator-token"
    const val VIEWER_TOKEN = "ui-builder-links-viewer-token"
    const val STRANGER_TOKEN = "ui-builder-links-stranger-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val DESIGN_ID = "linked-screen"
    const val OTHER_DESIGN_ID = "someone-elses-screen"
    const val ISSUE = "https://github.com/yschimke/compose-preview-server/issues/12"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
