package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * The three R3 decisions (#1120) asked as `elicitation/create` forms on a negotiated request scope,
 * end to end over the real Streamable HTTP transport.
 *
 * Each decision is driven by a scripted client that answers the form — accept with each option,
 * decline, cancel, or not at all (the interaction timeout) — and the design store is read back
 * afterwards, because "nothing was written" is the property that matters on every path but an
 * accepted write. The same calls from a client without the scope must return today's text decision
 * byte for byte.
 */
class ServeUiBuilderDecisionElicitationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
  }

  // --- (a) save a temporary copy back / create new / discard / keep --------------------------

  @Test
  fun `replace dry run falls back to the unchanged text decision without the capability`() {
    start()
    createAuthoritative()
    val stateless = callStateless(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun())
    assertNull(stateless["isError"])
    val decision = Json.parseToJsonElement(text(stateless)).jsonObject
    assertEquals("save-back-or-reimport", decision["decision"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("save-back", "create-new", "discard", "keep"),
      decision["options"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
    assertEquals(0, snapshot("authoritative").revision)

    // A scope negotiated WITHOUT form support (URL-only) changes nothing either.
    val urlOnly = McpClient(elicitation = """{"url":{}}""")
    assertNull(urlOnly.sessionId)
    val (again, asked) = urlOnly.call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun())
    assertTrue(asked.isEmpty())
    assertEquals(text(stateless), text(again))
  }

  @Test
  fun `replace dry run saves back when the person accepts save-back`() {
    start()
    createAuthoritative()
    val session = McpClient()
    val (result, asked) =
      session.call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()) {
        accept("save-back")
      }
    val form = asked.single()
    assertEquals(
      listOf("save-back", "create-new", "discard", "keep"),
      choices(form),
      form.toString(),
    )
    assertNotNull(schemaProperties(form)[ServeUiBuilderMcp.DECISION_NEW_DESIGN_ID_FIELD])
    val outcome =
      assertIs<AcceptedOutcomeV1>(
        assertIs<OperationOutcomeResponseV1>(response(text(result))).outcome,
        text(result),
      )
    assertEquals(1, outcome.committedRevision)
    assertEquals("Saved from a temporary copy", snapshot("authoritative").title)
  }

  @Test
  fun `replace dry run creates a new design when the person accepts create-new with an id`() {
    start()
    createAuthoritative()
    val (result, _) =
      McpClient().call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()) {
        accept("create-new", newDesignId = "authoritative-copy")
      }
    assertNull(result["isError"], text(result))
    assertCreated(text(result))
    val copy = snapshot("authoritative-copy")
    assertEquals("Saved from a temporary copy", copy.title)
    assertEquals(DesignHomeV1.Server(PUBLIC_ORIGIN, "authoritative-copy"), copy.home)
    val original = snapshot("authoritative")
    assertEquals(0, original.revision)
    assertEquals("Agent screen", original.title)
  }

  @Test
  fun `replace dry run writes nothing for discard keep an unoffered choice or create-new without an id`() {
    start()
    createAuthoritative()
    val fallback = text(callStateless(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()))
    for (choice in listOf("discard", "keep")) {
      val (result, _) =
        McpClient().call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()) {
          accept(choice)
        }
      assertNull(result["isError"])
      val answered = Json.parseToJsonElement(text(result)).jsonObject
      assertEquals(choice, answered["chosen"]!!.jsonPrimitive.content)
      assertEquals("false", answered["written"]!!.jsonPrimitive.content)
      assertEquals(ServeUiBuilderMcp.DECISION_SCHEMA, answered["schema"]!!.jsonPrimitive.content)
    }
    for (answer in listOf(accept("move"), accept("create-new"), """{"action":"accept"}""")) {
      val (result, _) =
        McpClient().call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()) { answer }
      assertEquals(fallback, text(result), answer)
    }
    assertEquals(0, snapshot("authoritative").revision)
    assertEquals("Agent screen", snapshot("authoritative").title)
  }

  @Test
  fun `replace dry run returns the text decision on decline cancel and timeout`() {
    start()
    createAuthoritative()
    val fallback = text(callStateless(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()))
    for (answer in listOf("""{"action":"decline"}""", """{"action":"cancel"}""", null)) {
      val (result, asked) =
        McpClient().call(ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT, replaceDryRun()) { answer }
      assertEquals(1, asked.size)
      assertNull(result["isError"])
      assertEquals(fallback, text(result), "answer $answer")
    }
    assertEquals(0, snapshot("authoritative").revision)
  }

  // --- (b) move a design's home ---------------------------------------------------------------

  @Test
  fun `move dry run falls back to the unchanged text decision without the capability`() {
    start()
    createAuthoritative()
    val decision =
      Json.parseToJsonElement(text(callStateless(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun())))
        .jsonObject
    assertEquals("move-design-home", decision["decision"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("move", "cancel"),
      decision["options"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
    assertEquals(
      DesignHomeV1.Server(PUBLIC_ORIGIN, "authoritative"),
      snapshot("authoritative").home,
    )
  }

  @Test
  fun `move dry run moves the home only when the person accepts move`() {
    start()
    createAuthoritative()
    val (result, asked) =
      McpClient().call(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun()) { accept("move") }
    assertEquals(listOf("move", "cancel"), choices(asked.single()))
    assertNull(schemaProperties(asked.single())[ServeUiBuilderMcp.DECISION_NEW_DESIGN_ID_FIELD])
    val outcome =
      assertIs<AcceptedOutcomeV1>(
        assertIs<OperationOutcomeResponseV1>(response(text(result))).outcome,
        text(result),
      )
    assertEquals(1, outcome.committedRevision)
    assertEquals(TARGET_HOME, snapshot("authoritative").home)
  }

  @Test
  fun `move dry run writes nothing on cancel decline or timeout`() {
    start()
    createAuthoritative()
    val fallback = text(callStateless(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun()))
    val (cancelled, _) =
      McpClient().call(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun()) { accept("cancel") }
    val answered = Json.parseToJsonElement(text(cancelled)).jsonObject
    assertEquals("cancel", answered["chosen"]!!.jsonPrimitive.content)
    assertEquals("false", answered["written"]!!.jsonPrimitive.content)
    for (answer in listOf("""{"action":"decline"}""", null)) {
      val (result, _) =
        McpClient().call(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun()) { answer }
      assertEquals(fallback, text(result), "answer $answer")
    }
    val stored = snapshot("authoritative")
    assertEquals(0, stored.revision)
    assertEquals(DesignHomeV1.Server(PUBLIC_ORIGIN, "authoritative"), stored.home)
  }

  // --- (c) import a document whose home is an existing design here ------------------------------

  @Test
  fun `import onto an existing home keeps the refusal without the capability`() {
    start()
    createAuthoritative()
    val refused = callStateless(ServeUiBuilderMcp.CREATE_DESIGN, reimport())
    assertEquals("true", refused["isError"]!!.jsonPrimitive.content)
    val decision = Json.parseToJsonElement(text(refused)).jsonObject
    assertEquals("import-onto-existing-home", decision["decision"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("apply-operations", "create-new", "cancel"),
      decision["options"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
    assertEquals(0, snapshot("authoritative").revision)
  }

  @Test
  fun `import onto an existing home creates a new design when the person accepts create-new`() {
    start()
    createAuthoritative()
    val (result, asked) =
      McpClient().call(ServeUiBuilderMcp.CREATE_DESIGN, reimport()) {
        accept("create-new", newDesignId = "imported")
      }
    assertEquals(listOf("apply-operations", "create-new", "cancel"), choices(asked.single()))
    assertNull(result["isError"], text(result))
    assertCreated(text(result))
    val imported = snapshot("imported")
    assertEquals("Imported copy", imported.title)
    assertEquals(DesignHomeV1.Server(PUBLIC_ORIGIN, "imported"), imported.home)
    assertEquals("Agent screen", snapshot("authoritative").title)
  }

  @Test
  fun `import onto an existing home writes nothing for apply-operations cancel or a reused id`() {
    start()
    createAuthoritative()
    val refusal = text(callStateless(ServeUiBuilderMcp.CREATE_DESIGN, reimport()))
    for (choice in listOf("apply-operations", "cancel")) {
      val (result, _) =
        McpClient().call(ServeUiBuilderMcp.CREATE_DESIGN, reimport()) { accept(choice) }
      assertNull(result["isError"], text(result))
      val answered = Json.parseToJsonElement(text(result)).jsonObject
      assertEquals(choice, answered["chosen"]!!.jsonPrimitive.content)
      assertEquals("false", answered["written"]!!.jsonPrimitive.content)
    }
    val (reused, _) =
      McpClient().call(ServeUiBuilderMcp.CREATE_DESIGN, reimport()) {
        accept("create-new", newDesignId = "authoritative")
      }
    assertEquals("true", reused["isError"]!!.jsonPrimitive.content)
    assertEquals(refusal, text(reused))
    assertEquals(0, snapshot("authoritative").revision)
    assertEquals("Agent screen", snapshot("authoritative").title)
  }

  @Test
  fun `import onto an existing home keeps the refusal on decline and timeout`() {
    start()
    createAuthoritative()
    val refusal = text(callStateless(ServeUiBuilderMcp.CREATE_DESIGN, reimport()))
    for (answer in listOf("""{"action":"decline"}""", """{"action":"cancel"}""", null)) {
      val (result, asked) = McpClient().call(ServeUiBuilderMcp.CREATE_DESIGN, reimport()) { answer }
      assertEquals(1, asked.size)
      assertEquals("true", result["isError"]!!.jsonPrimitive.content)
      assertEquals(refusal, text(result), "answer $answer")
    }
    assertEquals(0, snapshot("authoritative").revision)
  }

  // --- binding
  // ------------------------------------------------------------------------------------

  @Test
  fun `an answer without the eliciting credential is refused and the real answer still lands`() {
    start()
    createAuthoritative()
    val session = McpClient()
    var foreign = -1
    val (result, _) =
      session.call(
        ServeUiBuilderMcp.MOVE_DESIGN_HOME,
        moveDryRun(),
        beforeAnswer = { id ->
          // Knowing the session and request ids is not enough without the same credential.
          foreign = session.postResponse(id, accept("move"), token = null)
        },
      ) {
        accept("move")
      }
    assertEquals(400, foreign)
    assertIs<AcceptedOutcomeV1>(
      assertIs<OperationOutcomeResponseV1>(response(text(result))).outcome,
      text(result),
    )
    assertEquals(TARGET_HOME, snapshot("authoritative").home)
  }

  @Test
  fun `a 2025-11-25 client that declares forms negotiates a scope too`() {
    start()
    createAuthoritative()
    val session = McpClient(protocolVersion = ServeCatalogMcp.MCP_PROTOCOL_VERSION_2025_11)
    assertNotNull(session.sessionId)
    val (result, asked) =
      session.call(ServeUiBuilderMcp.MOVE_DESIGN_HOME, moveDryRun()) { accept("cancel") }
    assertEquals(1, asked.size)
    assertEquals(
      "cancel",
      Json.parseToJsonElement(text(result)).jsonObject["chosen"]!!.jsonPrimitive.content,
    )
  }

  // --- harness
  // ------------------------------------------------------------------------------------

  /** A Streamable HTTP client that negotiates a request scope and answers forms from a script. */
  private inner class McpClient(
    elicitation: String = """{"form":{}}""",
    private val protocolVersion: String = ServeCatalogMcp.MCP_PROTOCOL_VERSION,
  ) {
    val sessionId: String?

    init {
      val initialize =
        """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"$protocolVersion","capabilities":{"elicitation":$elicitation},"clientInfo":{"name":"decisions","version":"1"}}}"""
      sessionId =
        client.newCall(request(initialize, withSession = false)).execute().use {
          assertEquals(200, it.code)
          it.header("MCP-Session-Id")
        }
    }

    /**
     * One `tools/call`. [answer] returns the elicitation result object to send back, or null to
     * never answer (so the server's interaction timeout fires). Returns the tool result and every
     * `elicitation/create` the server sent.
     */
    fun call(
      tool: String,
      arguments: String,
      beforeAnswer: (String) -> Unit = {},
      answer: (JsonObject) -> String? = { null },
    ): Pair<JsonObject, List<JsonObject>> {
      val body =
        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
      val asked = mutableListOf<JsonObject>()
      client.newCall(request(body)).execute().use { response ->
        assertEquals(200, response.code)
        val type = response.header("Content-Type").orEmpty()
        if (type.startsWith("application/json")) {
          return Json.parseToJsonElement(response.body.string())
            .jsonObject["result"]!!
            .jsonObject to asked
        }
        assertTrue(type.startsWith("text/event-stream"), type)
        val source = response.body.source()
        while (true) {
          val line = source.readUtf8Line() ?: error("stream ended without a result")
          if (!line.startsWith("data: ")) continue
          val message = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
          if (message["method"]?.jsonPrimitive?.content == "elicitation/create") {
            asked += message
            val id = message["id"]!!.jsonPrimitive.content
            beforeAnswer(id)
            answer(message)?.let { assertEquals(202, postResponse(id, it)) }
            continue
          }
          assertEquals("7", message["id"]!!.jsonPrimitive.content)
          return message["result"]!!.jsonObject to asked
        }
      }
    }

    /** POSTs a JSON-RPC response for server request [id]; returns the status. */
    fun postResponse(id: String, result: String, token: String? = OPERATOR_TOKEN): Int {
      val body = """{"jsonrpc":"2.0","id":"$id","result":$result}"""
      return client.newCall(request(body, token = token)).execute().use { it.code }
    }

    private fun request(
      body: String,
      withSession: Boolean = true,
      token: String? = OPERATOR_TOKEN,
    ): Request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}/mcp")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", protocolVersion)
        .apply {
          token?.let { header(ServeHttpServer.TOKEN_HEADER, it) }
          if (withSession) sessionId?.let { header("MCP-Session-Id", it) }
        }
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .build()
  }

  private fun accept(choice: String, newDesignId: String? = null): String =
    """{"action":"accept","content":{"choice":"$choice"""" +
      (newDesignId?.let { ""","newDesignId":"$it"""" } ?: "") +
      "}}"

  private fun choices(form: JsonObject): List<String> =
    schemaProperties(form)[ServeUiBuilderMcp.DECISION_CHOICE_FIELD]!!
      .jsonObject["enum"]!!
      .jsonArray
      .map { it.jsonPrimitive.content }

  private fun schemaProperties(form: JsonObject): JsonObject =
    form["params"]!!.jsonObject["requestedSchema"]!!.jsonObject["properties"]!!.jsonObject

  private fun start() {
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG_SYSTEM_ID)),
        exporter = UiBuilderExportExecutor { error("export is not exercised here") },
      )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = PUBLIC_ORIGIN,
          token = OPERATOR_TOKEN,
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          catalogMcpInteractionTimeoutMillis = INTERACTION_TIMEOUT_MILLIS,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
  }

  private fun callStateless(tool: String, arguments: String): JsonObject =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server!!.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
              .toRequestBody(JSON_MEDIA_TYPE)
          )
          .build()
      )
      .execute()
      .use {
        val body = it.body.string()
        assertEquals(200, it.code, body)
        assertTrue(it.header("Content-Type")!!.startsWith("application/json"))
        Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject
      }

  /**
   * A create's reply: the new design's snapshot (without the catalog, which it omits by default).
   */
  private fun assertCreated(reply: String) {
    val snapshot =
      Json.parseToJsonElement(reply).jsonObject["response"]?.jsonObject?.get("snapshot")?.jsonObject
    assertNotNull(snapshot?.get("state"), reply)
  }

  private fun text(result: JsonObject): String =
    result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  private fun response(envelope: String) =
    json.decodeFromString(McpResponseEnvelopeV1.serializer(), envelope).response

  private fun createAuthoritative() {
    val created =
      callStateless(
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"authoritative","document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
      )
    assertNull(created["isError"], text(created))
  }

  private fun snapshot(designId: String): DesignDocumentV1 =
    assertIs<SnapshotResponseV1>(
        response(
          text(
            callStateless(
              ServeUiBuilderMcp.GET_DESIGN,
              """{"designId":"$designId","includeCatalog":true}""",
            )
          )
        )
      )
      .snapshot
      .state
      .document

  private fun replaceDryRun(): String {
    val replacement = snapshot("authoritative").copy(title = "Saved from a temporary copy")
    return """{"designId":"authoritative","operationId":"replace-1","baseRevision":0,"dryRun":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), replacement)}}"""
  }

  private fun moveDryRun(): String {
    val source = assertNotNull(snapshot("authoritative").home)
    return """{"designId":"authoritative","operationId":"move-1","baseRevision":0,"dryRun":true,"sourceHome":${json.encodeToString(DesignHomeV1.serializer(), source)},"targetHome":${json.encodeToString(DesignHomeV1.serializer(), TARGET_HOME)}}"""
  }

  private fun reimport(): String {
    val canonical =
      document()
        .copy(
          title = "Imported copy",
          home = DesignHomeV1.Server(PUBLIC_ORIGIN, "authoritative"),
        )
    return """{"designId":"authoritative","document":${json.encodeToString(DesignDocumentV1.serializer(), canonical)}}"""
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "authoritative",
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

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-decision-operator-token"
    const val PUBLIC_ORIGIN = "https://designs.example"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val INTERACTION_TIMEOUT_MILLIS = 1_000L
    val TARGET_HOME = DesignHomeV1.Repo("designs/authoritative.uid")
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
