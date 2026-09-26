package ee.schimke.composeai.cli.serve

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
  private fun request(method: String, params: String = "{}"): JsonObject {
    val mcp = ServeCatalogMcp(ServeSessionRegistry(open = { null }), Semaphore(1))
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
    assertTrue(html.contains("Compose Preview"))
    assertTrue(html.contains("const pending = new Map();"))
    assertTrue(html.contains("await request('ui/initialize'"))
    assertTrue(html.contains("if (event.source !== window.parent) return;"))
    assertTrue(!html.contains("innerHTML"))
    assertTrue(html.contains("selected = undefined;"))
    assertTrue(html.contains("use.hidden = true;"))
    assertTrue(html.contains("message.method === 'ui/notifications/tool-input'"))
    assertTrue(html.contains("delete copy.token;"))
    assertTrue(html.contains("toolArguments = safeToolArguments(message.params?.arguments);"))
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
    assertTrue(
      html.indexOf("await request('ui/initialize'") <
        html.indexOf("notify('ui/notifications/initialized'"),
      "the bridge must not announce readiness until the initialize response succeeds",
    )
  }

  @Test
  fun `render tools declare the portable viewer without losing their text fallback`() {
    val tools = request("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
    val viewerTools = setOf("render_preview", "render_matrix")
    viewerTools.forEach { name ->
      val tool = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
      assertEquals(
        ServeCatalogMcp.MCP_APP_VIEWER_URI,
        tool["_meta"]!!.jsonObject["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content,
      )
    }
    val diff = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == "diff_semantics" }
    assertTrue(diff.jsonObject["_meta"] == null)
  }
}
