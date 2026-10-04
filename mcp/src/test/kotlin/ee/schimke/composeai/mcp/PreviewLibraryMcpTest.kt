package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sidebar library app (#1241) and the `openai/settings` capability and tools (#1242), end to
 * end over the MCP session.
 */
class PreviewLibraryMcpTest {
  @get:Rule val tmp = TemporaryFolder()

  private val supervisor =
    DaemonSupervisor(
      descriptorProvider = FakeDescriptorProvider(),
      clientFactory = FakeDaemonClientFactory(),
    )
  private val closers = mutableListOf<() -> Unit>()

  @After
  fun tearDown() {
    closers.reversed().forEach { runCatching { it() } }
    runCatching { supervisor.shutdown() }
  }

  private lateinit var initializeResult: JsonObject

  private fun connect(
    profile: McpToolProfile = McpToolProfile.NATIVE,
    settingsFile: File = File(tmp.root, "settings.json"),
    projectSupervisor: DaemonSupervisor = supervisor,
  ): McpTestClient {
    val server =
      DaemonMcpServer(
        projectSupervisor,
        workingDirectory = null,
        profile = profile,
        previewSettingsStore = PreviewSettingsStore(settingsFile) {},
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    val client = McpTestClient(input = clientFromServer, output = clientToServer)
    closers += { session.close() }
    closers += { client.close() }
    closers += { server.shutdown() }
    initializeResult = client.initialize(clientName = "codex-library-test")
    return client
  }

  private fun tools(client: McpTestClient): Map<String, JsonObject> {
    client.awaitToolsContaining(PreviewLibrary.TOOL)
    return client.request("tools/list")["tools"]!!.jsonArray.associate {
      it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
    }
  }

  @Test
  fun `initialize advertises openai-settings under extensions and experimental`() {
    connect()
    val capabilities = initializeResult["capabilities"]!!.jsonObject
    val expected =
      Json.parseToJsonElement("""{"readTool":"settings_read","updateTool":"settings_update"}""")
    assertThat(capabilities["extensions"]!!.jsonObject["openai/settings"]).isEqualTo(expected)
    assertThat(capabilities["experimental"]!!.jsonObject["openai/settings"]).isEqualTo(expected)
  }

  @Test
  fun `the storybook profile advertises no settings and lists no library`() {
    val client = connect(McpToolProfile.STORYBOOK)
    val capabilities = initializeResult["capabilities"]!!.jsonObject
    assertThat(capabilities["extensions"]).isNull()
    client.awaitToolsContaining("preview-stories")
    val names =
      client.request("tools/list")["tools"]!!.jsonArray.map {
        it.jsonObject["name"]!!.jsonPrimitive.content
      }
    assertThat(names).containsNoneOf(PreviewLibrary.TOOL, PreviewSettingsMcp.READ_TOOL)
  }

  @Test
  fun `library is a global entrypoint with a title, icon and a fullscreen resource`() {
    val client = connect()
    val tools = tools(client)
    val library = tools.getValue(PreviewLibrary.TOOL)
    val meta = library["_meta"]!!.jsonObject
    assertThat(meta["openai/ui"]!!.jsonObject["entrypoints"])
      .isEqualTo(Json.parseToJsonElement("""[{"type":"global"}]"""))
    assertThat(meta["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content)
      .isEqualTo(PreviewLibrary.RESOURCE_URI)
    assertThat(library["title"]!!.jsonPrimitive.content).isEqualTo("Compose Previews")
    assertThat(library["icons"]!!.jsonArray.single().jsonObject["mimeType"]!!.jsonPrimitive.content)
      .isEqualTo("image/svg+xml")
    assertThat(library["inputSchema"]!!.jsonObject["required"]).isNull()

    // Settings tools: read-only hint and outputSchema reach the wire.
    val read = tools.getValue(PreviewSettingsMcp.READ_TOOL)
    assertThat(read["annotations"]!!.jsonObject["readOnlyHint"]).isEqualTo(JsonPrimitive(true))
    assertThat(
        read["outputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
      )
      .containsExactly("schema", "values")
    assertThat(
        tools
          .getValue(PreviewSettingsMcp.REGISTER_PROJECT_TOOL)["_meta"]!!
          .jsonObject["ui"]!!
          .jsonObject["resourceUri"]!!
          .jsonPrimitive
          .content
      )
      .isEqualTo(PreviewLibrary.RESOURCE_URI)

    val listed =
      client
        .request("resources/list")["resources"]!!
        .jsonArray
        .map { it.jsonObject }
        .single { it["uri"]!!.jsonPrimitive.content == PreviewLibrary.RESOURCE_URI }
    assertThat(listed["_meta"]!!.jsonObject["openai/ui"]!!.jsonObject["preferredDisplayMode"])
      .isEqualTo(JsonPrimitive("fullscreen"))
    val read2 =
      client.request("resources/read", buildJsonObject { put("uri", PreviewLibrary.RESOURCE_URI) })
    val content = read2["contents"]!!.jsonArray.single().jsonObject
    assertThat(content["mimeType"]!!.jsonPrimitive.content)
      .isEqualTo(DaemonMcpServer.MCP_APP_MIME_TYPE)
    assertThat(content["text"]!!.jsonPrimitive.content).contains("openai/deepLink")
    assertThat(content["_meta"]!!.jsonObject["openai/ui"]!!.jsonObject["availableDisplayModes"])
      .isEqualTo(Json.parseToJsonElement("""["inline","fullscreen"]"""))
  }

  @Test
  fun `library result carries the project tree, accepting empty arguments`() {
    val project = File(tmp.root, "proj").apply { mkdirs() }
    supervisor.registerProject(project, "sample")
    val client = connect()
    tools(client)
    val result = client.callTool(PreviewLibrary.TOOL, JsonObject(emptyMap()))
    val structured = result.raw["structuredContent"]!!.jsonObject
    assertThat(structured["schema"]!!.jsonPrimitive.content).isEqualTo(PreviewLibrary.SCHEMA)
    assertThat(structured["mode"]!!.jsonPrimitive.content).isEqualTo("library")
    assertThat(structured["tools"]!!.jsonObject["render"]!!.jsonPrimitive.content)
      .isEqualTo("render_preview")
    val projects = structured["projects"]!!.jsonArray
    assertThat(projects.map { it.jsonObject["name"]!!.jsonPrimitive.content })
      .containsExactly("sample")
    assertThat(result.firstTextContent()).contains("1 project(s)")
  }

  @Test
  fun `global library restores a registration made by another process without starting daemons`() {
    val file = File(tmp.root, "workspaces.json")
    val factory = FakeDaemonClientFactory()
    val sidebarSupervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = factory,
        workspaceStore = WorkspaceStore(file),
      )
    closers += { sidebarSupervisor.shutdown() }
    val client = connect(projectSupervisor = sidebarSupervisor)
    tools(client)
    // The sidebar was already open when a chat in unrelated roots registered the build.
    assertThat(client.callTool(PreviewLibrary.TOOL, JsonObject(emptyMap())).firstTextContent())
      .contains("No projects")
    val project = File(tmp.root, "other-chat-project").apply { mkdirs() }
    WorkspaceStore(file).remember("chat-project", project, "From another chat")
    val result = client.callTool(PreviewLibrary.TOOL, JsonObject(emptyMap()))
    val restored =
      result.raw["structuredContent"]!!.jsonObject["projects"]!!.jsonArray.single().jsonObject
    assertThat(restored["id"]!!.jsonPrimitive.content).isEqualTo("chat-project")
    assertThat(restored["warming"]).isEqualTo(JsonPrimitive(false))
    assertThat(sidebarSupervisor.listProjects().single().daemons).isEmpty()
  }

  @Test
  fun `settings round-trip over MCP and doctor answers`() {
    val file = File(tmp.root, "settings.json")
    val client = connect(settingsFile = file)
    tools(client)
    val update =
      client.callTool(
        PreviewSettingsMcp.UPDATE_TOOL,
        buildJsonObject { putJsonObject("set") { put(PreviewSettings.DARK_THEME, true) } },
      )
    assertThat(update.raw["isError"]).isNotEqualTo(JsonPrimitive(true))
    assertThat(file.readText()).contains("\"darkTheme\": true")
    val read = client.callTool(PreviewSettingsMcp.READ_TOOL, JsonObject(emptyMap()))
    assertThat(
        read.raw["structuredContent"]!!
          .jsonObject["values"]!!
          .jsonObject[PreviewSettings.DARK_THEME]
      )
      .isEqualTo(JsonPrimitive(true))

    val doctor = client.callTool(PreviewSettingsMcp.DOCTOR_TOOL, JsonObject(emptyMap()))
    assertThat(doctor.firstTextContent()).contains("settings: ${file.path}")
    assertThat(doctor.firstTextContent()).contains("none registered")
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }
}
