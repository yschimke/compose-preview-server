package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * The UI Builder editor as an MCP App over a server design (compose-ui-builder's
 * `UI_BUILDER_MCP_APP_HOST.md`, "Server designs"): `ui_builder_edit_design`, the three app-only
 * tools the editor calls, the `ui://compose-ui-builder/editor` resource and the CORS-open archive
 * it loads from, all gated on the archive's `mcpApp >= 2`.
 */
class ServeUiBuilderEditorAppTest {
  @TempDir lateinit var stateDirectory: Path
  @TempDir lateinit var archiveDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")

  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
  }

  // ---- declarations ----------------------------------------------------------------------------

  @Test
  fun `tools list shows the edit tool for model and app and the three app-only tools`() {
    val tools = tools(catalogMcp())
    val edit = tools.getValue(ServeUiBuilderEditorTools.EDIT_DESIGN)
    val editUi = edit["_meta"]!!.jsonObject["ui"]!!.jsonObject
    assertEquals(EDITOR_URI, editUi["resourceUri"]!!.jsonPrimitive.content)
    assertEquals(listOf("model", "app"), strings(editUi["visibility"]))
    assertEquals(
      listOf("designId"),
      strings(edit["inputSchema"]!!.jsonObject["required"]),
    )
    for (name in ServeUiBuilderEditorTools.APP_ONLY_TOOL_NAMES) {
      val ui = tools.getValue(name)["_meta"]!!.jsonObject["ui"]!!.jsonObject
      assertEquals(listOf("app"), strings(ui["visibility"]), name)
      assertEquals(EDITOR_URI, ui["resourceUri"]!!.jsonPrimitive.content, name)
    }
    assertEquals(
      listOf("designId", "baseRevision", "document"),
      strings(
        tools
          .getValue(ServeUiBuilderEditorTools.EDITOR_WRITE)["inputSchema"]!!
          .jsonObject["required"],
      ),
    )
  }

  @Test
  fun `nothing is registered when the archive's mcpApp is below 2`() {
    val old = archive(mcpApp = 1)
    assertNull(ServeUiBuilderEditorApp.forArchive(old, assetPath = { ASSET_PATH }))
    assertNull(
      ServeUiBuilderEditorApp.forArchive(archive(mcpApp = null), assetPath = { ASSET_PATH }),
    )
    val bare =
      ServeCatalogMcp(registry(), Semaphore(1), uiBuilder = builder(), uiBuilderEditor = null)
    val names = tools(bare).keys
    assertTrue(ServeUiBuilderEditorTools.TOOL_NAMES.none { it in names }, names.toString())
    val listed =
      request(bare, "resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray.map {
        it.jsonObject["uri"]!!.jsonPrimitive.content
      }
    assertFalse(EDITOR_URI in listed, listed.toString())
    val refused =
      callTool(bare, ServeUiBuilderEditorTools.EDIT_DESIGN, """{"designId":"$DESIGN"}""", "owner")
    assertEquals("true", refused["isError"]!!.jsonPrimitive.content)
    assertTrue("unknown tool" in text(refused), refused.toString())
    // And without the UI Builder itself the app does not exist either.
    val noBuilder = ServeCatalogMcp(registry(), Semaphore(1), uiBuilderEditor = editorApp())
    assertTrue(ServeUiBuilderEditorTools.TOOL_NAMES.none { it in tools(noBuilder) })
  }

  // ---- resource --------------------------------------------------------------------------------

  @Test
  fun `the resource is the shell with its asset base, layout and device presets filled and the origin in the csp`() {
    val mcp = catalogMcp()
    val read =
      request(mcp, "resources/read", """{"uri":"$EDITOR_URI"}""", origin = ORIGIN)["result"]!!
        .jsonObject["contents"]!!
        .jsonArray
        .single()
        .jsonObject
    assertEquals("text/html;profile=mcp-app", read["mimeType"]!!.jsonPrimitive.content)
    val html = read["text"]!!.jsonPrimitive.content
    assertFalse(ServeUiBuilderEditorApp.ASSET_BASE_PLACEHOLDER in html, html)
    assertFalse(ServeUiBuilderEditorApp.LAYOUT_PLACEHOLDER in html, html)
    assertTrue("""<base href="$ORIGIN$ASSET_PATH" />""" in html, html)
    assertTrue("""const assetBase = "$ORIGIN$ASSET_PATH";""" in html, html)
    assertTrue("""layout: "focused"""" in html, html)
    // The device presets are the server's own public, CORS-open route, so the frame can read them.
    assertFalse(ServeUiBuilderEditorApp.DEVICE_PRESETS_PLACEHOLDER in html, html)
    assertTrue(
      """const devicePresets = "$ORIGIN$UI_BUILDER_DEVICE_PRESETS_PATH";""" in html,
      html,
    )
    val meta = read["_meta"]!!.jsonObject
    val ui = meta["ui"]!!.jsonObject
    val csp = ui["csp"]!!.jsonObject
    assertEquals(listOf(ORIGIN), strings(csp["resourceDomains"]))
    assertEquals(listOf(ORIGIN), strings(csp["connectDomains"]))
    assertEquals("fullscreen", ui["preferredDisplayMode"]!!.jsonPrimitive.content)
    val openAi = meta["openai/ui"]!!.jsonObject
    assertEquals("fullscreen", openAi["preferredDisplayMode"]!!.jsonPrimitive.content)
    assertEquals(listOf("fullscreen"), strings(openAi["availableDisplayModes"]))

    // Listed, and readable without a grant like the viewer: it is static.
    val listed =
      request(mcp, "resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray.single {
        it.jsonObject["uri"]!!.jsonPrimitive.content == EDITOR_URI
      }
    assertEquals("text/html;profile=mcp-app", listed.jsonObject["mimeType"]!!.jsonPrimitive.content)
    assertFalse(
      ServeCatalogMcp.requiresGrant(
        Json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$EDITOR_URI"}}""",
          )
          .jsonObject,
      ),
    )
  }

  @Test
  fun `without a request origin the resource falls back to the public origin`() {
    val mcp =
      ServeCatalogMcp(
        registry(),
        Semaphore(1),
        uiBuilder = builder(),
        uiBuilderEditor = editorApp(),
        publicOrigin = { "https://public.example" },
      )
    val html =
      request(mcp, "resources/read", """{"uri":"$EDITOR_URI"}""")["result"]!!
        .jsonObject["contents"]!!
        .jsonArray
        .single()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    assertTrue("https://public.example$ASSET_PATH" in html, html)
  }

  // ---- edit / read -----------------------------------------------------------------------------

  @Test
  fun `edit design opens the design by id, title and revision`() =
    runBlocking<Unit> {
      val builder = builder()
      create(builder)
      val result =
        callTool(
          catalogMcp(builder),
          ServeUiBuilderEditorTools.EDIT_DESIGN,
          """{"designId":"$DESIGN"}""",
          "owner",
        )
      assertNull(result["isError"], result.toString())
      assertEquals("Opened Editor screen in the UI Builder editor.", text(result))
      val structured = result["structuredContent"]!!.jsonObject
      assertEquals(DESIGN, structured["designId"]!!.jsonPrimitive.content)
      assertEquals("Editor screen", structured["title"]!!.jsonPrimitive.content)
      assertEquals(0L, structured["revision"]!!.jsonPrimitive.content.toLong())
    }

  @Test
  fun `read returns the document, its revision and whether this caller may write it`() =
    runBlocking<Unit> {
      val builder = builder()
      create(builder)
      share(builder, viewer.actorId, "viewer")
      val mcp = catalogMcp(builder)

      val read = structured(callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "owner"))
      assertEquals(DESIGN, read["designId"]!!.jsonPrimitive.content)
      assertEquals(0L, read["revision"]!!.jsonPrimitive.content.toLong())
      assertEquals("true", read["writable"]!!.jsonPrimitive.content)
      val document =
        json.decodeFromJsonElement(DesignDocumentV1.serializer(), read["document"]!!.jsonObject)
      assertEquals(DESIGN, document.id)
      assertEquals("Editor screen", document.title)

      // A design the actor may only read, and an owner whose grant carries no write.
      val asViewer =
        structured(callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "viewer"))
      assertEquals("false", asViewer["writable"]!!.jsonPrimitive.content)
      val readGrant =
        structured(callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "owner-read"))
      assertEquals("false", readGrant["writable"]!!.jsonPrimitive.content)

      // A design the actor cannot read at all is an error, not an empty design.
      val stranger = callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "stranger")
      assertEquals("true", stranger["isError"]!!.jsonPrimitive.content, stranger.toString())
    }

  // ---- write -----------------------------------------------------------------------------------

  @Test
  fun `write saves, reports a conflict on a stale base, and is read-only for a reader`() =
    runBlocking<Unit> {
      val builder = builder()
      create(builder)
      share(builder, viewer.actorId, "viewer")
      val mcp = catalogMcp(builder)

      val saved =
        structured(
          callTool(mcp, ServeUiBuilderEditorTools.EDITOR_WRITE, write(0, "Renamed"), "owner"),
        )
      assertEquals("saved", saved["outcome"]!!.jsonPrimitive.content, saved.toString())
      assertEquals(1L, saved["revision"]!!.jsonPrimitive.content.toLong())
      assertEquals(
        "Renamed",
        structured(callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "owner"))["title"]!!
          .jsonPrimitive
          .content,
      )

      val conflict =
        structured(
          callTool(mcp, ServeUiBuilderEditorTools.EDITOR_WRITE, write(0, "Stale"), "owner"),
        )
      assertEquals("conflict", conflict["outcome"]!!.jsonPrimitive.content, conflict.toString())
      assertEquals(1L, conflict["revision"]!!.jsonPrimitive.content.toLong())

      val readOnly =
        structured(
          callTool(mcp, ServeUiBuilderEditorTools.EDITOR_WRITE, write(1, "Viewer"), "viewer"),
        )
      assertEquals("read-only", readOnly["outcome"]!!.jsonPrimitive.content, readOnly.toString())
      assertNull(readOnly["revision"])

      // Nothing was written by either refusal.
      val after = structured(callTool(mcp, ServeUiBuilderEditorTools.EDITOR_READ, ARGS, "owner"))
      assertEquals(1L, after["revision"]!!.jsonPrimitive.content.toLong())
      assertEquals("Renamed", after["title"]!!.jsonPrimitive.content)

      // A document that is not one is a tool error with the reason.
      val invalid =
        callTool(
          mcp,
          ServeUiBuilderEditorTools.EDITOR_WRITE,
          """{"designId":"$DESIGN","baseRevision":1,"document":{"title":"x"}}""",
          "owner",
        )
      assertEquals("true", invalid["isError"]!!.jsonPrimitive.content, invalid.toString())
    }

  // ---- await -----------------------------------------------------------------------------------

  @Test
  fun `await answers changed false on timeout and changed true after a write`() =
    runBlocking<Unit> {
      val builder = builder()
      create(builder)
      val mcp = catalogMcp(builder)
      val await = ServeUiBuilderEditorTools.EDITOR_AWAIT

      val idle =
        structured(
          callTool(mcp, await, """{"designId":"$DESIGN","afterRevision":0,"waitSeconds":1}"""),
        )
      assertEquals("false", idle["changed"]!!.jsonPrimitive.content, idle.toString())
      assertEquals(0L, idle["revision"]!!.jsonPrimitive.content.toLong())

      // A wait in flight wakes on the save.
      val waiting =
        async(Dispatchers.Default) {
          structured(
            callTool(mcp, await, """{"designId":"$DESIGN","afterRevision":0,"waitSeconds":20}"""),
          )
        }
      delay(300)
      val saved =
        structured(
          callTool(mcp, ServeUiBuilderEditorTools.EDITOR_WRITE, write(0, "Moved"), "owner"),
        )
      assertEquals("saved", saved["outcome"]!!.jsonPrimitive.content, saved.toString())
      val woke = waiting.await()
      assertEquals("true", woke["changed"]!!.jsonPrimitive.content, woke.toString())
      assertEquals(1L, woke["revision"]!!.jsonPrimitive.content.toLong())

      // Already past the cursor: answered at once.
      val behind =
        structured(
          callTool(mcp, await, """{"designId":"$DESIGN","afterRevision":0,"waitSeconds":20}"""),
        )
      assertEquals("true", behind["changed"]!!.jsonPrimitive.content)
      assertEquals(1L, behind["revision"]!!.jsonPrimitive.content.toLong())
    }

  // ---- over HTTP -------------------------------------------------------------------------------

  @Test
  fun `the served resource points at this server's archive, which answers cross-origin`() {
    val dir = archive(mcpApp = 2)
    File(dir, "uiBuilder.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0))
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = benchmarkCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter = UiBuilderExportExecutor { error("export is not exercised here") },
      )
    val started =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
          uiBuilderDir = dir,
        )
        .also(ServeHttpServer::start)
    server = started
    val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    val origin = "http://127.0.0.1:${started.port}"
    val body =
      client
        .newCall(
          Request.Builder()
            .url("$origin/mcp")
            .post(
              """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$EDITOR_URI"}}"""
                .toRequestBody("application/json".toMediaType()),
            )
            .build(),
        )
        .execute()
        .use {
          val text = it.body.string()
          assertEquals(200, it.code, text)
          text
        }
    val content =
      Json.parseToJsonElement(body)
        .jsonObject["result"]!!
        .jsonObject["contents"]!!
        .jsonArray
        .single()
        .jsonObject
    val html = content["text"]!!.jsonPrimitive.content
    val base = assertNotNull(Regex("""<base href="([^"]+)"""").find(html)).groupValues[1]
    assertTrue(base.startsWith("$origin/ui-builder/v/") && base.endsWith("/"), base)
    assertEquals(
      listOf(origin),
      strings(
        content["_meta"]!!.jsonObject["ui"]!!.jsonObject["csp"]!!.jsonObject["resourceDomains"],
      ),
    )

    client
      .newCall(
        Request.Builder()
          .url("${base}uiBuilder.wasm")
          .header("Origin", "https://abc123.claudemcpcontent.com")
          .build(),
      )
      .execute()
      .use {
        assertEquals(200, it.code)
        assertEquals("*", it.header("Access-Control-Allow-Origin"))
        assertEquals("application/wasm", it.header("Content-Type")?.substringBefore(';'))
        assertTrue("immutable" in it.header("Cache-Control").orEmpty(), it.headers.toString())
      }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun registry() = ServeSessionRegistry(open = { null })

  private fun builder(): ServeUiBuilderMcp =
    ServeUiBuilderMcp(
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
        catalogs = benchmarkCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter = { error("this test never exports") },
      ),
    )

  private fun editorApp(): ServeUiBuilderEditorApp =
    assertNotNull(
      ServeUiBuilderEditorApp.forArchive(archive(mcpApp = 2), assetPath = { ASSET_PATH }),
    )

  private fun catalogMcp(builder: ServeUiBuilderMcp = builder()): ServeCatalogMcp =
    ServeCatalogMcp(registry(), Semaphore(1), uiBuilder = builder, uiBuilderEditor = editorApp())

  /** A fixture web archive: its manifest and a shell carrying the contract's placeholders. */
  private fun archive(mcpApp: Int?): File {
    val dir = archiveDirectory.resolve("archive-${mcpApp ?: "none"}-${System.nanoTime()}").toFile()
    File(dir, "mcp-app").mkdirs()
    File(dir, "index.html").writeText("<html></html>")
    File(dir, ServeUiBuilderEditor.MANIFEST_FILE)
      .writeText(
        """{"schema":"compose-ui-builder-web/v1","version":"9.9.9","serverApi":2""" +
          (mcpApp?.let { ""","mcpApp":$it""" } ?: "") +
          "}",
      )
    File(dir, ServeUiBuilderEditorApp.SHELL_PATH).writeText(SHELL)
    return dir
  }

  /**
   * The UI-builder door as a transport would answer it: `owner` holds every capability, `viewer` is
   * another account with them, `owner-read` is the owner's grant without write.
   */
  private fun authorize(
    capability: UiBuilderRouteCapability,
    token: String?,
  ): UiBuilderAuthorizationDecision =
    when (token) {
      "owner" -> UiBuilderAuthorizationDecision.Authorized(owner.actorId)
      "viewer" -> UiBuilderAuthorizationDecision.Authorized(viewer.actorId)
      "stranger" -> UiBuilderAuthorizationDecision.Authorized("github:stranger")
      "owner-read" ->
        if (capability == UiBuilderRouteCapability.READ)
          UiBuilderAuthorizationDecision.Authorized(owner.actorId)
        else UiBuilderAuthorizationDecision.Forbidden
      else -> UiBuilderAuthorizationDecision.Missing
    }

  private fun request(
    mcp: ServeCatalogMcp,
    method: String,
    params: String = "{}",
    origin: String? = null,
  ): JsonObject {
    val body =
      Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        .jsonObject
    return requireNotNull(
      runBlocking {
        mcp.handle(body, uiBuilderAuthorization = ::authorize, requestOrigin = origin) {
          ServeMachineAuthorization.Decision.Authorized("agent:test")
        }
      }
        .body,
    )
  }

  private fun tools(mcp: ServeCatalogMcp): Map<String, JsonObject> =
    request(mcp, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.associate {
      it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
    }

  private fun callTool(
    mcp: ServeCatalogMcp,
    name: String,
    arguments: String,
    token: String = "owner",
  ): JsonObject {
    val args = Json.parseToJsonElement(arguments).jsonObject
    val withToken = JsonObject(args + (ServeCatalogMcp.TOKEN_ARGUMENT to JsonPrimitive(token)))
    val params = """{"name":"$name","arguments":$withToken}"""
    val reply = request(mcp, "tools/call", params)
    return reply["result"]?.jsonObject ?: error("not a result: $reply")
  }

  private fun structured(result: JsonObject): JsonObject {
    assertNull(result["isError"], result.toString())
    return result["structuredContent"]!!.jsonObject
  }

  private fun text(result: JsonObject): String =
    result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as JsonArray).map { it.jsonPrimitive.content }

  private fun write(baseRevision: Long, title: String): String {
    val document = json.encodeToString(DesignDocumentV1.serializer(), fixture().copy(title = title))
    return """{"designId":"$DESIGN","baseRevision":$baseRevision,"document":$document}"""
  }

  private suspend fun create(builder: ServeUiBuilderMcp) {
    val document = json.encodeToString(DesignDocumentV1.serializer(), fixture())
    builder.call(
      ServeUiBuilderMcp.CREATE_DESIGN,
      Json.parseToJsonElement("""{"designId":"$DESIGN","document":$document}""").jsonObject,
      owner,
      callId = "create",
    )
  }

  private suspend fun share(builder: ServeUiBuilderMcp, actorId: String, role: String) {
    builder.call(
      ServeUiBuilderMcp.SHARE_DESIGN,
      Json.parseToJsonElement("""{"designId":"$DESIGN","actorId":"$actorId","role":"$role"}""")
        .jsonObject,
      owner,
      callId = "share",
    )
  }

  private fun fixture(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN,
      title = "Editor screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG, "candidate", "candidate", "candidate"),
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
              slots = mapOf("children" to listOf("a")),
            ),
          "a" to
            DesignNodeV1(
              id = "a",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Alpha")),
            ),
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN = "editor-screen"
    const val ARGS = """{"designId":"$DESIGN"}"""
    const val EDITOR_URI = ServeUiBuilderEditorApp.RESOURCE_URI
    const val ORIGIN = "https://ui.example"
    const val ASSET_PATH = "/ui-builder/v/abc123/"
    const val OPERATOR_TOKEN = "operator-token"

    /** The placeholder-bearing parts of compose-ui-builder's `mcp-app/ui-builder-mcp-app.html`. */
    const val SHELL =
      """<!doctype html><html><head>
<base href="__COMPOSE_UI_BUILDER_ASSET_BASE__" />
<script>
const assetBase = "__COMPOSE_UI_BUILDER_ASSET_BASE__";
const devicePresets = "__COMPOSE_UI_BUILDER_DEVICE_PRESETS__";
globalThis.composeUiBuilderMcpApp = { assetBase, layout: "__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__", devicePresets };
</script></head><body></body></html>"""
  }
}
