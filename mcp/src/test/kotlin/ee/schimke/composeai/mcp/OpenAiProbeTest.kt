package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The OpenAI extensions probe (#1236): gated registration and the `_meta` shapes it serves. */
class OpenAiProbeTest {
  @get:Rule val tmp = TemporaryFolder()

  private val supervisor =
    DaemonSupervisor(
      descriptorProvider = FakeDescriptorProvider(),
      clientFactory = FakeDaemonClientFactory(),
    )
  private val closers = mutableListOf<() -> Unit>()
  private val logged = CopyOnWriteArrayList<String>()

  @After
  fun tearDown() {
    closers.reversed().forEach { runCatching { it() } }
    runCatching { supervisor.shutdown() }
  }

  private fun connect(server: DaemonMcpServer): McpTestClient {
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    val client = McpTestClient(input = clientFromServer, output = clientToServer)
    closers += { session.close() }
    closers += { client.close() }
    closers += { server.shutdown() }
    client.initialize(clientName = "codex-probe-test")
    return client
  }

  private fun probeClient(): McpTestClient =
    connect(
      DaemonMcpServer(
        supervisor,
        workingDirectory = null,
        openAiProbe = OpenAiProbe(log = { logged += it }),
      )
    )

  private fun toolsByName(client: McpTestClient, marker: String): Map<String, JsonObject> {
    client.awaitToolsContaining(marker)
    return client.request("tools/list")["tools"]!!.jsonArray.associate {
      it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
    }
  }

  private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

  @Test
  fun `probe is off unless the environment flag is exactly 1`() {
    assertThat(OpenAiProbe.fromEnvironment(emptyMap())).isNull()
    assertThat(OpenAiProbe.fromEnvironment(mapOf(OpenAiProbe.ENV to "true"))).isNull()
    assertThat(OpenAiProbe.fromEnvironment(mapOf(OpenAiProbe.ENV to "1"))).isNotNull()

    val client =
      connect(
        DaemonMcpServer(
          supervisor,
          workingDirectory = null,
          environment = mapOf(OpenAiProbe.ENV to "0"),
        )
      )
    val tools = toolsByName(client, "diff_semantics")
    assertThat(tools.keys.filter { it.startsWith("probe_") }).isEmpty()
    val resources =
      client.request("resources/list")["resources"]!!.jsonArray.map {
        it.jsonObject["uri"]!!.jsonPrimitive.content
      }
    assertThat(resources).doesNotContain(OpenAiProbe.RESOURCE_URI)
  }

  @Test
  fun `flag registers the probe tools and resource`() {
    val client =
      connect(
        DaemonMcpServer(
          supervisor,
          workingDirectory = null,
          environment = mapOf(OpenAiProbe.ENV to "1"),
        )
      )
    val tools = toolsByName(client, OpenAiProbe.PROBE_GLOBAL)
    assertThat(tools.keys).containsAtLeastElementsIn(OpenAiProbe.TOOL_NAMES)
    val resources =
      client.request("resources/list")["resources"]!!.jsonArray.map {
        it.jsonObject["uri"]!!.jsonPrimitive.content
      }
    assertThat(resources).contains(OpenAiProbe.RESOURCE_URI)
  }

  @Test
  fun `entrypoint tools carry openai-ui entrypoints, a title and an svg icon`() {
    val tools = toolsByName(probeClient(), OpenAiProbe.PROBE_GLOBAL)
    val expected =
      mapOf(
        OpenAiProbe.PROBE_GLOBAL to """[{"type":"global"}]""",
        OpenAiProbe.PROBE_THREAD to """[{"type":"thread"}]""",
        OpenAiProbe.PROBE_FILE to """[{"type":"file","extensions":["rc","uid"]}]""",
      )
    for ((name, entrypoints) in expected) {
      val tool = tools.getValue(name)
      val meta = tool["_meta"]!!.jsonObject
      assertThat(meta["openai/ui"]!!.jsonObject["entrypoints"])
        .isEqualTo(Json.parseToJsonElement(entrypoints))
      assertThat(meta["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content)
        .isEqualTo(OpenAiProbe.RESOURCE_URI)
      assertThat(meta["ui/resourceUri"]!!.jsonPrimitive.content).isEqualTo(OpenAiProbe.RESOURCE_URI)
      assertThat(tool["title"]!!.jsonPrimitive.content).isNotEmpty()
      val icon = tool["icons"]!!.jsonArray.single().jsonObject
      assertThat(icon["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/svg+xml")
      val svg =
        String(
          Base64.getDecoder().decode(icon["src"]!!.jsonPrimitive.content.substringAfter("base64,"))
        )
      assertThat(svg).contains("currentColor")
    }
    assertThat(tools.getValue(OpenAiProbe.PROBE_GLOBAL)["inputSchema"]!!.jsonObject["required"])
      .isNull()
    assertThat(
        tools
          .getValue(OpenAiProbe.PROBE_FILE)["inputSchema"]!!
          .jsonObject["required"]!!
          .jsonArray
          .map { it.jsonPrimitive.content }
      )
      .containsExactly("file")
  }

  @Test
  fun `mention and echo tools are app-visible and the mention tool is marked for search`() {
    val tools = toolsByName(probeClient(), OpenAiProbe.PROBE_MENTIONS)
    assertThat(tools.getValue(OpenAiProbe.PROBE_MENTIONS)["_meta"])
      .isEqualTo(
        parse("""{"ui": {"visibility": ["app"]}, "openai/extensions": {"mentions/search": {}}}""")
      )
    assertThat(tools.getValue(OpenAiProbe.PROBE_FILE_ECHO)["_meta"])
      .isEqualTo(parse("""{"ui": {"visibility": ["app"]}}"""))
    // The main viewer's portable keys are untouched by the probe.
    val render = tools.getValue("render_preview")["_meta"]!!.jsonObject
    assertThat(render["ui/resourceUri"]!!.jsonPrimitive.content)
      .isEqualTo(DaemonMcpServer.MCP_APP_VIEWER_URI)
    assertThat(render["openai/ui"]).isNull()
  }

  @Test
  fun `mention search returns three resource links and logs the query`() {
    val client = probeClient()
    client.awaitToolsContaining(OpenAiProbe.PROBE_MENTIONS)
    val result =
      client.callTool(OpenAiProbe.PROBE_MENTIONS, buildJsonObject { put("query", "hex bolt") })
    val items = result.raw["structuredContent"]!!.jsonObject["items"]!!.jsonArray
    assertThat(items).hasSize(3)
    assertThat(items.map { it.jsonObject["type"]!!.jsonPrimitive.content })
      .containsExactly("resource_link", "resource_link", "resource_link")
    assertThat(result.raw["content"]!!.jsonArray).hasSize(3)
    assertThat(logged.single { "mentions/search" in it })
      .contains("client=codex-probe-test query=\"hex bolt\"")

    val uri = items.first().jsonObject["uri"]!!.jsonPrimitive.content
    val read = client.request("resources/read", buildJsonObject { put("uri", uri) })
    assertThat(read["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
      .contains("probe-mention-1")
  }

  @Test
  fun `file echo returns the host-injected path and touches only probe formats`() {
    val client = probeClient()
    client.awaitToolsContaining(OpenAiProbe.PROBE_FILE_ECHO)
    val rc = File(tmp.root, "hello.rc").apply { writeText("rc-bytes") }
    val kt = File(tmp.root, "Hello.kt").apply { writeText("fun main() {}") }

    fun echo(path: String?, touch: Boolean): JsonObject =
      client
        .request(
          "tools/call",
          buildJsonObject {
            put("name", OpenAiProbe.PROBE_FILE_ECHO)
            putJsonObject("arguments") { if (touch) put("touch", true) }
            if (path != null)
              putJsonObject("_meta") { putJsonObject("openai/resource") { put("path", path) } }
          },
        )["structuredContent"]!!
        .jsonObject

    val plain = echo(rc.absolutePath, touch = false)
    assertThat(plain["resourcePath"]!!.jsonPrimitive.content).isEqualTo(rc.absolutePath)
    assertThat(plain["exists"]!!.jsonPrimitive.content).isEqualTo("true")
    assertThat(plain["sizeBytes"]!!.jsonPrimitive.content).isEqualTo("8")
    assertThat(plain["touch"]).isNull()

    val touched = echo(rc.absolutePath, touch = true)
    assertThat(touched["touch"]!!.jsonPrimitive.content).isEqualTo("rewrote 8 bytes unchanged")
    assertThat(rc.readText()).isEqualTo("rc-bytes")

    val refused = echo(kt.absolutePath, touch = true)
    assertThat(refused["touch"]!!.jsonPrimitive.content).startsWith("refused:")

    val none = echo(null, touch = true)
    assertThat(none["resourcePath"]!!.jsonPrimitive.contentOrNull).isNull()
    assertThat(none["touch"]!!.jsonPrimitive.content).contains("no openai/resource.path")
  }

  @Test
  fun `probe_file and probe_global report the client name and arguments`() {
    val client = probeClient()
    client.awaitToolsContaining(OpenAiProbe.PROBE_FILE)
    val fileArgs = buildJsonObject {
      putJsonObject("file") {
        put("name", "a.uid")
        put("resourceUri", "host-resource://a")
      }
    }
    val file = client.callTool(OpenAiProbe.PROBE_FILE, fileArgs).raw["structuredContent"]!!
    assertThat(file.jsonObject["clientName"]!!.jsonPrimitive.content).isEqualTo("codex-probe-test")
    assertThat(file.jsonObject["arguments"]).isEqualTo(fileArgs)
    val global = client.callTool(OpenAiProbe.PROBE_GLOBAL, JsonObject(emptyMap()))
    assertThat(global.raw["structuredContent"]!!.jsonObject["probe"]!!.jsonPrimitive.content)
      .isEqualTo(OpenAiProbe.PROBE_GLOBAL)
  }

  @Test
  fun `probe app resource serves the bundled html with display modes`() {
    val client = probeClient()
    val content =
      client
        .request("resources/read", buildJsonObject { put("uri", OpenAiProbe.RESOURCE_URI) })[
          "contents"]!!
        .jsonArray
        .single()
        .jsonObject
    assertThat(content["mimeType"]!!.jsonPrimitive.content)
      .isEqualTo(DaemonMcpServer.MCP_APP_MIME_TYPE)
    assertThat(content["text"]!!.jsonPrimitive.content).contains("ui/initialize")
    assertThat(content["_meta"]!!.jsonObject["openai/ui"])
      .isEqualTo(
        parse(
          """{"availableDisplayModes": ["inline", "fullscreen"],
             "preferredDisplayMode": "fullscreen"}"""
        )
      )
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }
}
