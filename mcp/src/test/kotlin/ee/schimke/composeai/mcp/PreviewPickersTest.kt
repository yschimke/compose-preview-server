package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Thumbnail pickers through OpenAI form elicitation (#1240). */
class PreviewPickersTest {
  @get:Rule val tmp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  // ---------------------------------------------------------------------------------------------
  // Wire shapes.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `single select is a uri string with a resource input and no selection mode`() {
    val field =
      OpenAiForms.singleResourceField(
        listOf(
          OpenAiForms.ResourceOption(
            uri = "cad://parts/hex-bolt",
            name = "hex-bolt",
            title = "M6 hex bolt",
            thumbnailPngBase64 = "AAAA",
            previewTarget = OpenAiForms.appToolTarget("cad.open", buildJsonObject { put("x", 1) }),
          ),
          OpenAiForms.ResourceOption(
            uri = "cad://parts/washer",
            name = "washer",
            title = "M6 washer",
          ),
        ),
        title = "Reference file",
        default = "cad://parts/hex-bolt",
      )
    // The spec's "Request (single selection)" example, minus its userOptions.
    assertThat(field)
      .isEqualTo(
        json.parseToJsonElement(
          """
          {"type":"string","title":"Reference file","format":"uri",
           "x-openai-input":{"type":"resource","options":[
             {"uri":"cad://parts/hex-bolt","name":"hex-bolt","title":"M6 hex bolt",
              "_meta":{
                "openai/thumbnail":{"src":"data:image/png;base64,AAAA","mimeType":"image/png"},
                "openai/preview":{"target":{"type":"mcp_app_tool","name":"cad.open","arguments":{"x":1}}}}},
             {"uri":"cad://parts/washer","name":"washer","title":"M6 washer"}]},
           "default":"cad://parts/hex-bolt"}
          """
        )
      )
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiForms.singleResourceField(emptyList(), default = "cad://parts/missing")
    }
  }

  @Test
  fun `multi select is a uri array with explicit selection and validated defaults`() {
    val options =
      listOf(
        OpenAiForms.ResourceOption("cad://parts/hex-bolt", "hex-bolt", "M6 hex bolt"),
        OpenAiForms.ResourceOption("cad://parts/washer", "washer", "M6 washer"),
      )
    val field = OpenAiForms.multiResourceField(options, defaults = listOf("cad://parts/hex-bolt"))
    // The spec's "Request (explicit selection)" example.
    assertThat(field)
      .isEqualTo(
        json.parseToJsonElement(
          """
          {"type":"array","items":{"type":"string","format":"uri"},
           "x-openai-input":{"type":"resource","selection":"explicit","options":[
             {"uri":"cad://parts/hex-bolt","name":"hex-bolt","title":"M6 hex bolt"},
             {"uri":"cad://parts/washer","name":"washer","title":"M6 washer"}]},
           "default":["cad://parts/hex-bolt"]}
          """
        )
      )
    val implicit =
      OpenAiForms.multiResourceField(options, selection = OpenAiForms.Selection.IMPLICIT)
    assertThat(implicit["x-openai-input"]!!.jsonObject["selection"]!!.jsonPrimitive.content)
      .isEqualTo("implicit")
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiForms.multiResourceField(
        options,
        selection = OpenAiForms.Selection.IMPLICIT,
        defaults = listOf("cad://parts/washer"),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiForms.multiResourceField(options, defaults = listOf("cad://parts/nut"))
    }
  }

  @Test
  fun `answers must name offered options`() {
    val offered = listOf("a://1", "a://2")
    fun content(value: String) = json.parseToJsonElement("""{"f":$value}""").jsonObject
    assertThat(OpenAiForms.selectedUri(content("\"a://2\""), "f", offered)).isEqualTo("a://2")
    assertThat(OpenAiForms.selectedUri(content("\"a://3\""), "f", offered)).isNull()
    assertThat(OpenAiForms.selectedUri(content("2"), "f", offered)).isNull()
    assertThat(OpenAiForms.selectedUri(null, "f", offered)).isNull()
    assertThat(OpenAiForms.selectedUris(content("""["a://2","a://1"]"""), "f", offered))
      .containsExactly("a://2", "a://1")
      .inOrder()
    assertThat(OpenAiForms.selectedUris(content("""["a://1","a://1"]"""), "f", offered)).isNull()
    assertThat(OpenAiForms.selectedUris(content("""["a://9"]"""), "f", offered)).isNull()
    assertThat(OpenAiForms.selectedUris(content("\"a://1\""), "f", offered)).isNull()
  }

  @Test
  fun `capability is the openai-elicitation extension's form object`() {
    val empty = JsonObject(emptyMap())
    fun caps(vararg extensions: Pair<String, JsonObject>) =
      ClientCapabilities(extensions = mapOf(*extensions))
    assertThat(OpenAiForms.supportsForms(null)).isFalse()
    assertThat(OpenAiForms.supportsForms(ClientCapabilities())).isFalse()
    // Plain MCP form elicitation is not the OpenAI extension.
    assertThat(
        OpenAiForms.supportsForms(
          ClientCapabilities(elicitation = ClientCapabilities.Elicitation(form = empty))
        )
      )
      .isFalse()
    assertThat(OpenAiForms.supportsForms(caps("openai/elicitation" to empty))).isFalse()
    assertThat(
        OpenAiForms.supportsForms(
          caps("openai/elicitation" to buildJsonObject { put("form", true) })
        )
      )
      .isFalse()
    assertThat(
        OpenAiForms.supportsForms(
          caps("openai/elicitation" to buildJsonObject { putJsonObject("form") {} })
        )
      )
      .isTrue()
  }

  @Test
  fun `picker options carry a render_preview preview target and only cached thumbnails`() {
    val rendered = "compose-preview://ws/_app/com.example.A"
    val neverRendered = "compose-preview://ws/_app/com.example.B"
    val activity = PreviewActivity()
    val thumbnails = RenderThumbnails()
    val png = tmp.newFile("a.png").also { writePng(it, 600, 300) }
    // A render with overrides still counts for the preview.
    activity.rendered("$rendered?overrides=e30", png.absolutePath)

    val cached = PreviewPickers.cachedThumbnail(rendered, activity, thumbnails)
    assertThat(cached).isNotNull()
    val decoded = ImageIO.read(java.util.Base64.getDecoder().decode(cached).inputStream())
    assertThat(decoded.width).isEqualTo(PreviewPickers.THUMBNAIL_EDGE_PX)
    assertThat(PreviewPickers.cachedThumbnail(neverRendered, activity, thumbnails)).isNull()

    val option = PreviewPickers.option(rendered, "A (dark)", ":app", cached).toJson()
    assertThat(option["uri"]!!.jsonPrimitive.content).isEqualTo(rendered)
    assertThat(option["name"]!!.jsonPrimitive.content).isEqualTo("A")
    assertThat(option["title"]!!.jsonPrimitive.content).isEqualTo("A (dark)")
    val meta = option["_meta"]!!.jsonObject
    assertThat(meta["openai/thumbnail"]!!.jsonObject["src"]!!.jsonPrimitive.content)
      .isEqualTo("data:image/png;base64,$cached")
    assertThat(meta["openai/preview"])
      .isEqualTo(
        json.parseToJsonElement(
          """{"target":{"type":"mcp_app_tool","name":"render_preview","arguments":{"uri":"$rendered"}}}"""
        )
      )
    val bare = PreviewPickers.option(neverRendered, "B").toJson()
    assertThat(bare["_meta"]!!.jsonObject.keys).containsExactly("openai/preview")
  }

  @Test
  fun `the openai request travels with its untyped schema intact`() {
    val schema =
      OpenAiForms.form(
        mapOf(
          "preview" to
            OpenAiForms.singleResourceField(
              listOf(OpenAiForms.ResourceOption("a://1", "one", thumbnailPngBase64 = "QQ=="))
            )
        ),
        required = listOf("preview"),
      )
    val sent = OpenAiForms.unwrapRawParams(OpenAiForms.request("Pick one", schema).toJSON())
    sent as JSONRPCRequest
    assertThat(sent.method).isEqualTo("openai/elicitation/create")
    assertThat(sent.params)
      .isEqualTo(
        buildJsonObject {
          put("mode", "form")
          put("message", "Pick one")
          put("requestedSchema", schema)
        }
      )
    // Everything else passes through untouched.
    val plain = JSONRPCRequest(method = "roots/list", params = buildJsonObject { put("x", 1) })
    assertThat(OpenAiForms.unwrapRawParams(plain)).isSameInstanceAs(plain)
    val notification = JSONRPCNotification(method = "notifications/x")
    assertThat(OpenAiForms.unwrapRawParams(notification)).isSameInstanceAs(notification)
  }

  // ---------------------------------------------------------------------------------------------
  // End to end over an MCP session.
  // ---------------------------------------------------------------------------------------------

  private val factory = FakeDaemonClientFactory()
  private val supervisor =
    DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
  private val server = DaemonMcpServer(supervisor, workingDirectory = null)
  private val closers = mutableListOf<() -> Unit>()

  @After
  fun tearDown() {
    closers.reversed().forEach { runCatching { it() } }
    runCatching { server.shutdown() }
    runCatching { supervisor.shutdown() }
  }

  /** Requests the fake client received, by method. */
  private val openAiRequests = CopyOnWriteArrayList<JsonObject>()
  private val plainRequests = AtomicLong()

  private fun connect(openAiForms: Boolean, answer: (JsonObject) -> JsonObject): McpTestClient {
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    val client =
      McpTestClient(
        input = clientFromServer,
        output = clientToServer,
        elicitationHandler = { _ ->
          plainRequests.incrementAndGet()
          buildJsonObject { put("action", "decline") }
        },
        requestHandlers =
          mapOf(
            OpenAiForms.METHOD to
              { request ->
                openAiRequests += request
                answer(request)
              }
          ),
      )
    closers += { session.close() }
    closers += { client.close() }
    client.initialize(
      capabilities =
        buildJsonObject {
          putJsonObject("elicitation") { putJsonObject("form") {} }
          if (openAiForms) {
            putJsonObject("extensions") {
              putJsonObject(OpenAiForms.EXTENSION_ID) { putJsonObject("form") {} }
            }
          }
        }
    )
    return client
  }

  private class Workspace(val id: WorkspaceId, val small: String, val large: String)

  /** Two previews named `Screen`, a "Small" and a "Large" variant, rendering solid PNGs. */
  private fun workspace(client: McpTestClient): Workspace {
    val dir = tmp.newFolder("variants")
    val registered =
      client.callTool(
        "register_project",
        buildJsonObject {
          put("path", dir.absolutePath)
          put("rootProjectName", "variants")
        },
      )
    val id =
      WorkspaceId(
        json
          .parseToJsonElement(registered.firstTextContent())
          .jsonObject["workspaceId"]!!
          .jsonPrimitive
          .content
      )
    client.expectNotification("notifications/resources/list_changed", 2_000)
    supervisor.daemonFor(id, ":app")
    val daemon = factory.daemons.getValue(id to ":app")
    val small = "com.example.VKt.Screen_Devices - Small"
    val large = "com.example.VKt.Screen_Devices - Large"
    listOf(small, large).forEach {
      daemon.emitDiscovery(it, functionName = "Screen")
      client.expectNotification("notifications/resources/list_changed", 2_000)
    }
    val png = tmp.newFile("variants.png").also { writePng(it, 40, 30) }
    daemon.autoRenderPngPath = { png.absolutePath }
    return Workspace(
      id,
      PreviewUri(id, ":app", small).toUri(),
      PreviewUri(id, ":app", large).toUri(),
    )
  }

  private fun pickerOptions(request: JsonObject): List<JsonObject> {
    val field =
      request["params"]!!
        .jsonObject["requestedSchema"]!!
        .jsonObject["properties"]!!
        .jsonObject[PreviewPickers.FIELD]!!
        .jsonObject
    assertThat(field["type"]!!.jsonPrimitive.content).isEqualTo("string")
    assertThat(field["format"]!!.jsonPrimitive.content).isEqualTo("uri")
    val input = field["x-openai-input"]!!.jsonObject
    assertThat(input["type"]!!.jsonPrimitive.content).isEqualTo("resource")
    assertThat(input.containsKey("selection")).isFalse()
    return input["options"]!!.jsonArray.map { it.jsonObject }
  }

  private fun variantChoice(result: McpToolResult): JsonObject =
    json.parseToJsonElement(result.textContents().last()).jsonObject["variantChoice"]!!.jsonObject

  @Test
  fun `an ambiguous render_preview asks with a thumbnail picker and renders the pick`() {
    val client =
      connect(openAiForms = true) { request ->
        val small =
          pickerOptions(request).single { it["name"]!!.jsonPrimitive.content.endsWith("Small") }
        buildJsonObject {
          put("action", "accept")
          putJsonObject("content") { put(PreviewPickers.FIELD, small["uri"]!!) }
        }
      }
    val ws = workspace(client)
    // Render one variant by uri first, so exactly one option has a cached thumbnail.
    client.callTool("render_preview", buildJsonObject { put("uri", ws.large) }, timeoutMs = 10_000)

    val result =
      client.callTool(
        "render_preview",
        buildJsonObject { put("preview", "Screen") },
        timeoutMs = 10_000,
      )

    assertThat(plainRequests.get()).isEqualTo(0)
    val options = pickerOptions(openAiRequests.single())
    assertThat(options.map { it["uri"]!!.jsonPrimitive.content })
      .containsExactly(ws.large, ws.small)
    val byUri = options.associateBy { it["uri"]!!.jsonPrimitive.content }
    assertThat(byUri.getValue(ws.large)["_meta"]!!.jsonObject.keys)
      .containsExactly("openai/thumbnail", "openai/preview")
    // Never rendered here: no thumbnail, and nothing rendered to make one.
    assertThat(byUri.getValue(ws.small)["_meta"]!!.jsonObject.keys)
      .containsExactly("openai/preview")
    assertThat(
        byUri.getValue(ws.small)["_meta"]!!.jsonObject["openai/preview"]!!.jsonObject["target"]
      )
      .isEqualTo(PreviewPickers.renderPreviewTarget(ws.small))

    val choice = variantChoice(result)
    assertThat(choice["mode"]!!.jsonPrimitive.content).isEqualTo("elicitation")
    assertThat(choice["form"]!!.jsonPrimitive.content).isEqualTo(OpenAiForms.EXTENSION_ID)
    assertThat(choice["message"]!!.jsonPrimitive.content).contains(ws.small)
    // One render of the pick, not a grid of every match.
    assertThat(result.textContents().none { "\"cells\"" in it }).isTrue()
    assertThat(result.raw.toString()).contains(PreviewUri.parseOrNull(ws.small)!!.previewFqn)
  }

  @Test
  fun `a declined picker renders nothing and says not to ask again`() {
    val client = connect(openAiForms = true) { buildJsonObject { put("action", "decline") } }
    workspace(client)
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject { put("preview", "Screen") },
        timeoutMs = 10_000,
      )
    assertThat(openAiRequests).hasSize(1)
    assertThat(result.textContents()).hasSize(1)
    assertThat(variantChoice(result)["mode"]!!.jsonPrimitive.content).isEqualTo("declined")
  }

  @Test
  fun `a cancelled picker renders the first match, since a headless client cancels unseen`() {
    val client = connect(openAiForms = true) { buildJsonObject { put("action", "cancel") } }
    val ws = workspace(client)
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject { put("preview", "Screen") },
        timeoutMs = 10_000,
      )
    assertThat(openAiRequests).hasSize(1)
    assertThat(variantChoice(result)["mode"]!!.jsonPrimitive.content).isEqualTo("cancelled")
    assertThat(result.textContents().size).isGreaterThan(1)
    assertThat(result.raw.toString()).contains(PreviewUri.parseOrNull(ws.large)!!.previewFqn)
  }

  @Test
  fun `without the openai capability an ambiguous match keeps the grid`() {
    val client = connect(openAiForms = false) { error("never asked") }
    val ws = workspace(client)
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject { put("preview", "Screen") },
        timeoutMs = 10_000,
      )
    assertThat(openAiRequests).isEmpty()
    assertThat(plainRequests.get()).isEqualTo(0)
    val choice = variantChoice(result)
    assertThat(choice["mode"]!!.jsonPrimitive.content).isEqualTo("text")
    assertThat(choice["message"]!!.jsonPrimitive.content).contains("grid")
    assertThat(choice["choices"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly(ws.large, ws.small)
  }

  @Test
  fun `render_matrix choose picks a cell from its thumbnail`() {
    var picked: String? = null
    val client =
      connect(openAiForms = true) { request ->
        val options = pickerOptions(request)
        picked = options.last()["uri"]!!.jsonPrimitive.content
        buildJsonObject {
          put("action", "accept")
          putJsonObject("content") { put(PreviewPickers.FIELD, picked) }
        }
      }
    val ws = workspace(client)
    val result =
      client.callTool(
        "render_matrix",
        buildJsonObject {
          put("uri", ws.small)
          put("choose", true)
          putJsonObject("axes") {
            putJsonArray("uiMode") {
              add("light")
              add("dark")
            }
          }
        },
        timeoutMs = 10_000,
      )
    val options = pickerOptions(openAiRequests.single())
    assertThat(options).hasSize(2)
    // Every cell was just rendered, so every option has a thumbnail, and each opens that cell.
    options.forEach { option ->
      val meta = option["_meta"]!!.jsonObject
      assertThat(meta["openai/thumbnail"]!!.jsonObject["src"]!!.jsonPrimitive.content)
        .startsWith("data:image/png;base64,")
      val uri = PreviewUri.parseOrNull(option["uri"]!!.jsonPrimitive.content)!!
      assertThat(uri.overridesJson).contains("uiMode")
    }
    assertThat(plainRequests.get()).isEqualTo(0)
    val selection =
      json.parseToJsonElement(result.firstTextContent()).jsonObject["selection"]!!.jsonObject
    assertThat(selection["mode"]!!.jsonPrimitive.content).isEqualTo("elicitation")
    assertThat(selection["uri"]!!.jsonPrimitive.content).isEqualTo(picked)
    assertThat(selection["variant"]!!.jsonPrimitive.contentOrNull)
      .isEqualTo(options.last()["title"]!!.jsonPrimitive.content)
  }

  private fun writePng(file: File, width: Int, height: Int) {
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }
}
