package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The hosted sidebar apps (#1241): `catalog_library` and `ui_builder_open`. */
class ServeLibraryMcpTest {
  @Test
  fun `catalog library offers designs only when builder is available`() {
    val disabled =
      ServeLibraryMcp.libraryResult(emptyList())["structuredContent"]!!
        .jsonObject["tools"]!!
        .jsonObject
    assertNull(disabled["listDesigns"])
    val enabled =
      ServeLibraryMcp.libraryResult(emptyList(), designsAvailable = true)["structuredContent"]!!
        .jsonObject["tools"]!!
        .jsonObject
    assertEquals(ServeUiBuilderMcp.LIST_DESIGNS, enabled["listDesigns"]!!.jsonPrimitive.content)
    assertEquals(ServeUiBuilderMcp.VIEW, enabled["view"]!!.jsonPrimitive.content)
  }

  private val opened = AtomicInteger()
  private val registry =
    ServeSessionRegistry(
      open = {
        opened.incrementAndGet()
        null
      }
    )

  init {
    listOf("alpha", "beta").forEach { id ->
      registry.register(
        id,
        ServeSessionState(
          descriptor = File("daemon-launch.json"),
          workspaceRoot =
            Files.createTempDirectory("catalog-library").toFile().also { it.deleteOnExit() },
          workspaceName = id,
          previews = listOf(ServePreview("$id.Home", "Home"), ServePreview("$id.Detail", "Detail")),
          label = "$id label",
        ),
      )
    }
  }

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        UiBuilderServiceResponse.Catalogs(emptyList())

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private fun request(
    method: String,
    params: String = "{}",
    uiBuilder: ServeUiBuilderMcp? = null,
  ): JsonObject {
    val mcp = ServeCatalogMcp(registry, Semaphore(1), uiBuilder = uiBuilder)
    val body =
      Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        .jsonObject
    return requireNotNull(
        runBlocking {
          mcp.handle(body) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
        }
          .body
      )["result"]!!
      .jsonObject
  }

  private fun tools(uiBuilder: ServeUiBuilderMcp? = null): Map<String, JsonObject> =
    request("tools/list", uiBuilder = uiBuilder)["tools"]!!.jsonArray.associate {
      it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
    }

  @Test
  fun `both apps are global entrypoints with a title, an svg icon and the library resource`() {
    val tools = tools(ServeUiBuilderMcp(service))
    for (name in listOf("catalog_library", ServeLibraryMcp.UI_BUILDER_OPEN)) {
      val tool = tools.getValue(name)
      val meta = tool["_meta"]!!.jsonObject
      assertEquals(
        Json.parseToJsonElement("""[{"type":"global"}]"""),
        meta["openai/ui"]!!.jsonObject["entrypoints"],
      )
      assertEquals(
        ServeLibraryMcp.RESOURCE_URI,
        meta["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content,
      )
      assertEquals(ServeLibraryMcp.RESOURCE_URI, meta["ui/resourceUri"]!!.jsonPrimitive.content)
      assertTrue(tool["title"]!!.jsonPrimitive.content.isNotBlank())
      val icon = tool["icons"]!!.jsonArray.single().jsonObject
      assertEquals("image/svg+xml", icon["mimeType"]!!.jsonPrimitive.content)
      val svg =
        String(
          Base64.getDecoder().decode(icon["src"]!!.jsonPrimitive.content.substringAfter("base64,"))
        )
      assertTrue("currentColor" in svg && "viewBox=\"0 0 20 20\"" in svg)
      // An entrypoint tool must accept {}.
      assertNull(tool["inputSchema"]!!.jsonObject["required"])
    }
    // No UI Builder on this box: no ui_builder_open.
    assertFalse(ServeLibraryMcp.UI_BUILDER_OPEN in tools())
    assertTrue("catalog_library" in tools())
  }

  @Test
  fun `the library resource is listed, readable without a grant, and prefers fullscreen`() {
    val listed =
      request("resources/list")["resources"]!!.jsonArray.single {
        it.jsonObject["uri"]!!.jsonPrimitive.content == ServeLibraryMcp.RESOURCE_URI
      }
    assertEquals(
      "fullscreen",
      listed.jsonObject["_meta"]!!
        .jsonObject["openai/ui"]!!
        .jsonObject["preferredDisplayMode"]!!
        .jsonPrimitive
        .content,
    )
    val read =
      request("resources/read", """{"uri":"${ServeLibraryMcp.RESOURCE_URI}"}""")["contents"]!!
        .jsonArray
        .single()
        .jsonObject
    assertEquals("text/html;profile=mcp-app", read["mimeType"]!!.jsonPrimitive.content)
    assertTrue("openai/deepLink" in read["text"]!!.jsonPrimitive.content)
    val readRequest =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"${ServeLibraryMcp.RESOURCE_URI}"}}"""
        )
        .jsonObject
    assertFalse(ServeCatalogMcp.requiresGrant(readRequest))
  }

  @Test
  fun `catalog_library lists catalogs with counts only and resumes none`() {
    val all =
      request("tools/call", """{"name":"catalog_library","arguments":{}}""")["structuredContent"]!!
        .jsonObject
    assertEquals(ServeLibraryMcp.SCHEMA, all["schema"]!!.jsonPrimitive.content)
    assertEquals("resource", all["renderVia"]!!.jsonPrimitive.content)
    val projects = all["projects"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("alpha", "beta"),
      projects.map { it["id"]!!.jsonPrimitive.content }.sorted(),
    )
    assertEquals(0, opened.get(), "an unscoped library call must not resume a catalog")
    // Registered suspended: the retained state gives the count, but an unscoped call lists counts
    // only. Listing every catalog's previews made `{}` return megabytes on the hosted box
    // (yschimke/compose-ag-plugin#64).
    val alpha = projects.single { it["id"]!!.jsonPrimitive.content == "alpha" }
    assertEquals("alpha label", alpha["name"]!!.jsonPrimitive.content)
    assertEquals(2, alpha["previewCount"]!!.jsonPrimitive.content.toInt())
    assertTrue(alpha["modules"]!!.jsonArray.isEmpty(), alpha.toString())
    assertTrue(projects.all { it["modules"]!!.jsonArray.isEmpty() }, projects.toString())
  }

  @Test
  fun `ui_builder_open opens the designs mode and names the tools the app calls`() {
    val result =
      request(
        "tools/call",
        """{"name":"ui_builder_open","arguments":{}}""",
        uiBuilder = ServeUiBuilderMcp(service),
      )
    val structured = result["structuredContent"]!!.jsonObject
    assertEquals("designs", structured["mode"]!!.jsonPrimitive.content)
    val tools = structured["tools"]!!.jsonObject
    assertEquals(ServeUiBuilderMcp.LIST_DESIGNS, tools["listDesigns"]!!.jsonPrimitive.content)
    assertEquals(ServeUiBuilderMcp.VIEW, tools["view"]!!.jsonPrimitive.content)
    // Without a UI Builder the tool does not exist.
    val refused = request("tools/call", """{"name":"ui_builder_open","arguments":{}}""")
    assertEquals("true", refused["isError"]!!.jsonPrimitive.content)
  }
}
