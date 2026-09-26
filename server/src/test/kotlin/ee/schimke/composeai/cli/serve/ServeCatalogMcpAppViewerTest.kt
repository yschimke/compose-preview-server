package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.Semaphore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeCatalogMcpAppViewerTest {
  private fun request(
    method: String,
    params: String = "{}",
    uiBuilder: ServeUiBuilderMcp? = null,
    uiBuilderNative: Boolean = false,
  ): JsonObject {
    val mcp =
      ServeCatalogMcp(
        ServeSessionRegistry(open = { null }),
        Semaphore(1),
        uiBuilder = uiBuilder,
        uiBuilderNative = uiBuilderNative,
      )
    val body =
      Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        .jsonObject
    return requireNotNull(
      runBlocking {
        mcp.handle(body) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
      }
        .body
    )
  }

  @Test
  fun `viewer resource is predeclared and returns MCP App HTML`() {
    val listed = request("resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray
    val viewer =
      listed
        .single {
          it.jsonObject["uri"]!!.jsonPrimitive.content == ServeCatalogMcp.MCP_APP_VIEWER_URI
        }
        .jsonObject
    assertEquals("text/html;profile=mcp-app", viewer["mimeType"]!!.jsonPrimitive.content)
    assertEquals(
      "true",
      viewer["_meta"]!!.jsonObject["ui"]!!.jsonObject["prefersBorder"]!!.jsonPrimitive.content,
    )

    val read =
      request(
          "resources/read",
          """{"uri":"${ServeCatalogMcp.MCP_APP_VIEWER_URI}"}""",
        )["result"]!!
        .jsonObject["contents"]!!
        .jsonArray
        .single()
        .jsonObject
    assertEquals("text/html;profile=mcp-app", read["mimeType"]!!.jsonPrimitive.content)
    val html = read["text"]!!.jsonPrimitive.content
    assertTrue(
      html.encodeToByteArray().size <= 500_000,
      "the portable viewer bundle must stay at or below 500 KB",
    )
    assertTrue(html.contains("Compose Preview"))
    assertTrue(html.contains("const pending = new Map();"))
    assertTrue(html.contains("await request('ui/initialize'"))
    assertTrue(html.contains("if (event.source !== window.parent) return;"))
    assertTrue(!html.contains("innerHTML"))
    assertTrue(html.contains("selected = undefined;"))
    assertTrue(html.contains("use.hidden = true;"))
    assertTrue(html.contains("message.method === 'ui/notifications/tool-input'"))
    assertTrue(html.contains("toolArguments = safeSelectionArguments(incomingArguments);"))
    assertTrue(html.contains("resourceToken = typeof incomingArguments.token === 'string'"))
    assertTrue(
      html.contains("if (resourceToken) params._meta = { 'compose-preview/token': resourceToken };")
    )
    assertTrue(html.contains("function safeSelectionArguments(value)"))
    assertTrue(html.contains("/(token|authorization|password|secret|api[-_]?key|cookie|session)/i"))
    assertTrue(html.contains("function credentialKeyInUri(key, value)"))
    assertTrue(html.contains("if (url.username || url.password)"))
    assertTrue(html.contains("for (const [parameter] of url.searchParams)"))
    assertTrue(html.contains("new URLSearchParams(url.hash.slice(1))"))
    assertTrue(html.contains("arguments: toolArguments"))
    assertTrue(html.contains("structuredContent: { composePreviewSelection: selected }"))
    assertTrue(html.contains("await request('ui/update-model-context'"))
    assertTrue(!html.contains("notify('ui/update-model-context'"))
    assertTrue(
      html.contains(
        "if (image && !cells.some(cell => typeof cell?.png === 'string' && cell.png.length > 0))"
      )
    )
    assertTrue(html.contains("renderImage(image, value, resource);"))
    assertTrue(html.contains("const RESOURCE_READ_TIMEOUT_MS = 65000;"))
    assertTrue(html.contains("RESOURCE_READ_TIMEOUT_MS,"))
    assertTrue(html.contains("const RESOURCE_POLL_INTERVAL_MS = 5000;"))
    assertTrue(html.contains("result?.hostCapabilities?.serverResources?.subscribe === true"))
    assertTrue(html.contains("await request('resources/subscribe', { uri });"))
    assertTrue(html.contains("await request('resources/unsubscribe', { uri });"))
    assertTrue(html.contains("Unable to unsubscribe a stale Compose Preview resource"))
    assertTrue(html.contains("message.method === 'notifications/resources/updated'"))
    assertTrue(html.contains("generation !== resourceReadGeneration"))
    assertTrue(html.contains("scheduleResourcePoll(resource.uri);"))
    assertTrue(html.contains("typeof content.blob === 'string'"))
    assertTrue(html.contains("Refresh resource"))
    assertTrue(html.contains("const REQUEST_TIMEOUT_MS = 5000;"))
    assertTrue(html.contains("if (!pending.delete(id)) return;"))
    assertTrue(html.contains("window.clearTimeout(request.timer);"))
    assertTrue(html.contains("Viewer unavailable; use the complete text fallback."))
    assertTrue(html.contains("const STATIC_RESULT_PARAM = 'compose-preview-result';"))
    assertTrue(html.contains("const MAX_STATIC_RESULT_BYTES = 500000;"))
    assertTrue(html.contains("#compose-preview-result=<unpadded base64url UTF-8 JSON>"))
    assertTrue(html.contains("credential field"))
    assertTrue(html.contains("const bridgeReady = staticMode ? Promise.resolve()"))
    assertTrue(html.contains("Complete text and structured output:"))
    assertTrue(
      html.indexOf("await request('ui/initialize'") <
        html.indexOf("notify('ui/notifications/initialized'"),
      "the bridge must not announce readiness until the initialize response succeeds",
    )
  }

  @Test
  fun `render tools declare the portable viewer without losing their text fallback`() {
    val tools = request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
    val viewerTools = setOf("render_preview", "render_matrix", "diff_semantics")
    viewerTools.forEach { name ->
      val tool = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
      assertEquals(
        ServeCatalogMcp.MCP_APP_VIEWER_URI,
        tool["_meta"]!!.jsonObject["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content,
      )
    }
  }

  @Test
  fun `visual UI builder tools declare the portable viewer`() {
    val service =
      object : UiBuilderServicePort {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
          UiBuilderServiceResponse.Catalogs(emptyList())

        override fun subscribe(
          call: UiBuilderSubscriptionCall,
          listener: (UiBuilderServiceUpdate) -> Unit,
        ): Closeable = Closeable {}
      }
    val native = UiBuilderNativePreviewLane { _, _ -> error("not called by tools/list") }
    val tools =
      request(
          "tools/list",
          uiBuilder = ServeUiBuilderMcp(service, nativePreview = native),
          uiBuilderNative = true,
        )["result"]!!
        .jsonObject["tools"]!!
        .jsonArray

    buildSet {
      add(ServeUiBuilderMcp.RENDER_NATIVE)
      if (RemoteDocumentExportSupport.formats.isNotEmpty()) {
        add(ServeUiBuilderMcp.EXPORT_DOCUMENT)
      }
    }
      .forEach { name ->
        val tool = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
        assertEquals(
          ServeCatalogMcp.MCP_APP_VIEWER_URI,
          tool["_meta"]!!.jsonObject["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content,
        )
      }
  }

  @Test
  fun `visual UI builder replies keep text fallback and expose PNG image blocks`() {
    val mcp = ServeCatalogMcp(ServeSessionRegistry(open = { null }), Semaphore(1))
    val native =
      """{"designId":"demo","previewToken":"pg_secret","previewUrl":"/pg/pg_secret","imageBase64":"data:image/png;base64,AQID","compileError":null}"""
    val nativeResult = mcp.uiBuilderToolResult(ServeUiBuilderMcp.RENDER_NATIVE, native)
    assertVisualReply(
      nativeResult,
      """{"designId":"demo","previewToken":"pg_secret","previewUrl":"/pg/pg_secret","compileError":null}""",
    )
    assertTrue(nativeResult.toString().contains("pg_secret"))

    val exported =
      """{"callId":"ui_builder_export_document","response":{"artifact":{"format":"png","mediaType":"image/png","encoding":"base64","content":"AQID","contentDigest":"abc","diagnostics":[]}}}"""
    val exportResult = mcp.uiBuilderToolResult(ServeUiBuilderMcp.EXPORT_DOCUMENT, exported)
    assertVisualReply(
      exportResult,
      """{"callId":"ui_builder_export_document","response":{"artifact":{"format":"png","mediaType":"image/png","encoding":"base64","contentDigest":"abc","diagnostics":[]}}}""",
    )

    val jsonExported =
      """{"callId":"ui_builder_export_document","response":{"artifact":{"format":"json","mediaType":"application/json","encoding":"utf8","content":"document bytes","contentDigest":"def","diagnostics":[]}}}"""
    val jsonResult = mcp.uiBuilderToolResult(ServeUiBuilderMcp.EXPORT_DOCUMENT, jsonExported)
    assertEquals(1, jsonResult["content"]!!.jsonArray.size)
    assertEquals(
      jsonExported,
      jsonResult["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
    )

    val refused = """{"code":"COMPILE_FAILED","reasons":["bad source"]}"""
    val fallback = mcp.uiBuilderToolResult(ServeUiBuilderMcp.RENDER_NATIVE, refused)
    assertEquals(1, fallback["content"]!!.jsonArray.size)
    assertEquals(
      refused,
      fallback["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `visual UI builder reply keeps a representative PNG inside the static viewer bound`() {
    val mcp = ServeCatalogMcp(ServeSessionRegistry(open = { null }), Semaphore(1))
    val png = Base64.getEncoder().encodeToString(ByteArray(160_000) { it.toByte() })
    val native =
      """{"designId":"demo","previewToken":"pg_secret","previewUrl":"/pg/pg_secret","imageBase64":"$png","compileError":null}"""
    val result = mcp.uiBuilderToolResult(ServeUiBuilderMcp.RENDER_NATIVE, native).toString()

    assertTrue(result.indexOf(png) >= 0, "PNG image block is missing")
    assertEquals(result.indexOf(png), result.lastIndexOf(png), "PNG must appear exactly once")
    val fragment =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString("""{"version":1,"result":$result}""".encodeToByteArray())
    assertTrue(fragment.length <= 500_000, "static viewer fragment was ${fragment.length} bytes")
  }

  private fun assertVisualReply(result: JsonObject, original: String) {
    val content = result["content"]!!.jsonArray
    assertEquals(2, content.size)
    assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(original, content[0].jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("AQID", content[1].jsonObject["data"]!!.jsonPrimitive.content)
    assertEquals("image/png", content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
  }
}
