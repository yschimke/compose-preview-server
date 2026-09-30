package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Previews tray (#1238) and the composer `@`-mention search (#1239). */
class PreviewTrayTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun preview(
    fqn: String,
    file: String? = null,
    line: Int? = null,
    displayName: String? = null,
    module: String = ":app",
  ) =
    CatalogPreview(
      uri = "compose-preview://ws/_app/$fqn",
      fqn = fqn,
      functionName = fqn.substringAfterLast('.'),
      displayName = displayName,
      modulePath = module,
      sourceFile = file,
      sourceLine = line,
    )

  // ---------------------------------------------------------------------------------------------
  // Mention search: pure ranking.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `mentions rank exact then prefix then subsequence then recency`() {
    val exact = preview("com.example.Profile", "/src/Profile.kt")
    val prefixOld = preview("com.example.ProfileScreen", "/src/ProfileScreen.kt")
    val prefixRecent = preview("com.example.ProfileCard", "/src/Cards.kt")
    val subsequence = preview("com.example.PrettyOfflineFile", "/src/Pretty.kt")
    val unrelated = preview("com.example.Settings", "/src/Settings.kt")
    val activity = PreviewActivity()
    activity.rendered(prefixOld.uri, "/nowhere/a.png")
    activity.rendered(prefixRecent.uri, "/nowhere/b.png")

    val found =
      PreviewMentions.search(
        "profile",
        listOf(unrelated, subsequence, prefixOld, prefixRecent, exact),
        activity,
      )

    assertThat(found.map { it.simpleName })
      .containsExactly("Profile", "ProfileCard", "ProfileScreen", "PrettyOfflineFile")
      .inOrder()
  }

  @Test
  fun `mentions match display name FQN and file name case-insensitively`() {
    val byDisplay = preview("com.example.A", displayName = "Dark checkout")
    val byFile = preview("com.example.B", file = "/src/WeatherTile.kt")
    val byFqn = preview("org.sample.C")
    val activity = PreviewActivity()
    val all = listOf(byDisplay, byFile, byFqn)
    assertThat(PreviewMentions.search("DARK", all, activity)).containsExactly(byDisplay)
    assertThat(PreviewMentions.search("wthrtl", all, activity)).containsExactly(byFile)
    assertThat(PreviewMentions.search("weathertile", all, activity)).containsExactly(byFile)
    assertThat(PreviewMentions.search("org.sample", all, activity)).containsExactly(byFqn)
    assertThat(PreviewMentions.search("zzz", all, activity)).isEmpty()
  }

  @Test
  fun `an empty query lists the previews rendered or watched in this session, most recent first`() {
    val a = preview("com.example.A")
    val b = preview("com.example.B")
    val c = preview("com.example.C")
    val activity = PreviewActivity()
    // Nothing rendered yet: the picker is not blank, it lists the catalog by name.
    assertThat(PreviewMentions.search("", listOf(c, b, a), activity).map { it.simpleName })
      .containsExactly("A", "B", "C")
      .inOrder()

    activity.rendered(b.uri, "/nowhere/b.png")
    activity.watched("${a.uri}?overrides=e30")
    assertThat(PreviewMentions.search("  ", listOf(a, b, c), activity).map { it.simpleName })
      .containsExactly("A", "B")
      .inOrder()
  }

  @Test
  fun `mentions are capped at twenty`() {
    val many = (1..40).map { preview("com.example.Screen$it") }
    assertThat(PreviewMentions.search("screen", many, PreviewActivity())).hasSize(20)
    assertThat(PreviewMentions.search("", many, PreviewActivity())).hasSize(20)
  }

  @Test
  fun `mention search answers an empty list while the catalog has not loaded`() {
    val result =
      PreviewMentions.call(
        buildJsonObject { put("query", "anything") },
        emptyList(),
        PreviewActivity(),
        RenderThumbnails(),
      )
    assertThat(result.isError).isNull()
    assertThat(result.structuredContent!!["items"]!!.jsonArray).isEmpty()
  }

  @Test
  fun `mention items are resource links with a thumbnail only from a cached render`() {
    val png = tmp.newFile("render.png").also { writePng(it, 400, 800) }
    val rendered = preview("com.example.ProfileScreen", "/src/ProfileScreen.kt", 12)
    val neverRendered = preview("com.example.ProfileCard", "/src/Cards.kt")
    val activity = PreviewActivity()
    activity.rendered(rendered.uri, png.absolutePath)

    val result =
      PreviewMentions.call(
        buildJsonObject { put("query", "profile") },
        listOf(rendered, neverRendered),
        activity,
        RenderThumbnails(),
      )
    assertThat(result.content).isEmpty()
    val items = result.structuredContent!!["items"]!!.jsonArray.map { it.jsonObject }
    // The spec's example item: {type: "resource_link", uri, name}.
    val first = items.first()
    assertThat(first.string("type")).isEqualTo("resource_link")
    assertThat(first.string("uri")).isEqualTo(rendered.uri)
    assertThat(first.string("name")).isEqualTo("ProfileScreen")
    assertThat(first.string("description")).isEqualTo(":app · ProfileScreen.kt")
    val icon = first["icons"]!!.jsonArray.single().jsonObject
    assertThat(icon.string("mimeType")).isEqualTo("image/png")
    val thumbnail =
      ImageIO.read(
        Base64.getDecoder()
          .decode(icon.string("src")!!.removePrefix("data:image/png;base64,"))
          .inputStream()
      )
    assertThat(maxOf(thumbnail.width, thumbnail.height)).isEqualTo(PreviewMentions.ICON_EDGE_PX)
    // Never rendered: no icon, and nothing was rendered to make one.
    assertThat(items[1].string("uri")).isEqualTo(neverRendered.uri)
    assertThat(items[1]["icons"]).isNull()
  }

  // ---------------------------------------------------------------------------------------------
  // Tray listing: pure ordering.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `tray lists changed files most recent first, pins before them, each preview once`() {
    val a = File(tmp.root, "A.kt").canonicalPath
    val b = File(tmp.root, "B.kt").canonicalPath
    val a1 = preview("com.example.A1", a, 20)
    val a2 = preview("com.example.A2", a, 10)
    val b1 = preview("com.example.B1", b, 5)
    val other = preview("com.example.Other", File(tmp.root, "Other.kt").canonicalPath)
    val activity = PreviewActivity()
    activity.sourceChanged(a)
    activity.sourceChanged(b)
    val catalog = listOf(a1, a2, b1, other)

    assertThat(PreviewTray.list(catalog, activity).map { it.uri })
      .containsExactly(b1.uri, a2.uri, a1.uri)
      .inOrder()

    // A pin comes first even when its file also changed, and it is listed once.
    assertThat(activity.pin(a1.uri)).isTrue()
    assertThat(activity.pin(other.uri)).isTrue()
    val pinned = PreviewTray.list(catalog, activity)
    assertThat(pinned.map { it.uri }).containsExactly(other.uri, a1.uri, b1.uri, a2.uri).inOrder()
    assertThat(pinned.first { it.uri == a1.uri }.changed).isNotNull()
    assertThat(pinned.first { it.uri == other.uri }.changed).isNull()

    // Editing A again moves it ahead of B.
    activity.sourceChanged(a)
    assertThat(PreviewTray.list(catalog, activity).map { it.uri })
      .containsExactly(other.uri, a1.uri, a2.uri, b1.uri)
      .inOrder()

    assertThat(activity.pin("file:///not/a/preview")).isFalse()
    assertThat(activity.unpin(other.uri)).isTrue()
    assertThat(PreviewTray.list(catalog, activity).map { it.uri }).doesNotContain(other.uri)
  }

  // ---------------------------------------------------------------------------------------------
  // Through the MCP server.
  // ---------------------------------------------------------------------------------------------

  private lateinit var supervisor: DaemonSupervisor
  private lateinit var factory: FakeDaemonClientFactory
  private lateinit var server: DaemonMcpServer
  private lateinit var client: McpTestClient
  private lateinit var session: McpSession

  private fun startServer() {
    factory = FakeDaemonClientFactory()
    supervisor =
      DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
    server = DaemonMcpServer(supervisor, workingDirectory = null, sourcePollIntervalMs = 0)
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
    client.initialize()
  }

  @After
  fun tearDown() {
    if (!::client.isInitialized) return
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { server.shutdown() }
    runCatching { supervisor.shutdown() }
  }

  @Test
  fun `previews_tray is a thread entrypoint listing the changed files' previews and pins`() {
    startServer()
    val tools = client.awaitToolsContaining(PreviewTray.TOOL)
    val tray = tools.tools.single { it.name == PreviewTray.TOOL }
    assertThat(tray.title).isEqualTo("Previews")
    val meta = tray.meta!!
    assertThat(meta["ui"]!!.jsonObject.string("resourceUri"))
      .isEqualTo(DaemonMcpServer.MCP_APP_VIEWER_URI)
    assertThat(
        meta["openai/ui"]!!.jsonObject["entrypoints"]!!.jsonArray.single().jsonObject.string("type")
      )
      .isEqualTo("thread")
    assertThat(tray.icons).isNotEmpty()

    val projectDir = tmp.newFolder("workspace")
    val moduleDir = tmp.newFolder("workspace", "module")
    val fileA = File(moduleDir, "A.kt").apply { writeText("@Preview fun A1() {}") }
    val fileB = File(moduleDir, "B.kt").apply { writeText("@Preview fun B1() {}") }
    val workspaceId = registerWorkspace(projectDir)
    val daemon = warmDaemonFor(workspaceId, ":module")
    daemon.emitDiscovery("com.example.A1", sourceFile = "A.kt", bodyLine = 1)
    daemon.emitDiscovery("com.example.B1", sourceFile = "B.kt", bodyLine = 1)
    daemon.emitDiscovery("com.example.Pinned", sourceFile = "C.kt", bodyLine = 1)
    repeat(3) { client.expectNotification("notifications/resources/list_changed", 2_000) }

    // Nothing changed yet: the tray is empty but answers.
    val empty = client.callTool(PreviewTray.TOOL, JsonObject(emptyMap()))
    assertThat(empty.structured()["previews"]!!.jsonArray).isEmpty()
    assertThat(empty.structured().string("mode")).isEqualTo("tray")

    for (file in listOf(fileA, fileB)) {
      client.callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", workspaceId.value)
          put("path", file.absolutePath)
        },
      )
    }
    val pinned = PreviewUri(workspaceId, ":module", "com.example.Pinned").toUri()
    val listed =
      client.callTool(PreviewTray.TOOL, buildJsonObject { put("pin", pinned) }).structured()
    val previews = listed["previews"]!!.jsonArray.map { it.jsonObject }
    assertThat(previews.map { it.string("name") }).containsExactly("Pinned", "B1", "A1").inOrder()
    assertThat(previews.map { it.string("pinned") }).containsExactly("true", "false", "false")
    assertThat(previews[1].string("sourceFile")).isEqualTo(fileB.canonicalPath)
    assertThat(previews[1].string("sourceLine")).isEqualTo("1")
    assertThat(listed["changedFiles"]!!.jsonArray.map { it.jsonObject.string("path") })
      .containsExactly(fileB.canonicalPath, fileA.canonicalPath)
      .inOrder()

    // A bad pin is an error; an unpin drops it.
    assertThat(
        client.callTool(PreviewTray.TOOL, buildJsonObject { put("pin", "file:///x.kt") }).isError()
      )
      .isTrue()
    val unpinned =
      client.callTool(PreviewTray.TOOL, buildJsonObject { put("unpin", pinned) }).structured()
    assertThat(unpinned["previews"]!!.jsonArray.map { it.jsonObject.string("name") })
      .containsExactly("B1", "A1")
      .inOrder()
  }

  @Test
  fun `a mentioned preview URI is readable through resources read and accepted by render_preview`() {
    startServer()
    val tools = client.awaitToolsContaining(PreviewMentions.TOOL)
    val mentions = tools.tools.single { it.name == PreviewMentions.TOOL }
    assertThat(mentions.meta!!["openai/extensions"]!!.jsonObject.keys).contains("mentions/search")
    assertThat(
        mentions.meta!!["ui"]!!.jsonObject["visibility"]!!.jsonArray.map {
          it.jsonPrimitive.content
        }
      )
      .contains("app")

    // No catalog yet: an empty list, not an error.
    val before = client.callTool(PreviewMentions.TOOL, buildJsonObject { put("query", "profile") })
    assertThat(before.isError()).isFalse()
    assertThat(before.structured()["items"]!!.jsonArray).isEmpty()

    val projectDir = tmp.newFolder("workspace")
    tmp.newFolder("workspace", "module")
    val workspaceId = registerWorkspace(projectDir)
    val daemon = warmDaemonFor(workspaceId, ":module")
    val previewId = "com.example.ProfileScreenPreview"
    daemon.emitDiscovery(previewId)
    client.expectNotification("notifications/resources/list_changed", 2_000)
    val png = tmp.newFile("profile.png").also { writePng(it, 300, 600) }
    daemon.autoRenderPngPath = { id -> if (id == previewId) png.absolutePath else null }

    val item =
      client
        .callTool(PreviewMentions.TOOL, buildJsonObject { put("query", "profscr") })
        .structured()["items"]!!
        .jsonArray
        .single()
        .jsonObject
    val uri = item.string("uri")!!
    assertThat(uri).isEqualTo(PreviewUri(workspaceId, ":module", previewId).toUri())
    // Not rendered yet, so no thumbnail — and the search rendered nothing to make one.
    assertThat(item["icons"]).isNull()
    assertThat(daemon.renderRequests).isEmpty()

    val read = client.request("resources/read", buildJsonObject { put("uri", uri) }, 10_000)
    val blob = read["contents"]!!.jsonArray.single().jsonObject
    assertThat(blob.string("mimeType")).isEqualTo("image/png")
    assertThat(Base64.getDecoder().decode(blob.string("blob"))).isEqualTo(png.readBytes())

    val rendered =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          put("observe", "hash")
        },
        timeoutMs = 10_000,
      )
    assertThat(rendered.isError()).isFalse()

    // Now there is a cached render, the item carries its thumbnail, and an empty query lists it.
    val recent =
      client
        .callTool(PreviewMentions.TOOL, buildJsonObject { put("query", "") })
        .structured()["items"]!!
        .jsonArray
        .single()
        .jsonObject
    assertThat(recent.string("uri")).isEqualTo(uri)
    assertThat(recent["icons"]!!.jsonArray).hasSize(1)
  }

  private fun McpToolResult.structured(): JsonObject =
    raw["structuredContent"]?.jsonObject ?: error("no structuredContent in $raw")

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun registerWorkspace(projectDir: File): WorkspaceId {
    val text =
      client
        .callTool(
          "register_project",
          buildJsonObject {
            put("path", projectDir.absolutePath)
            put("rootProjectName", "demo")
          },
        )
        .firstTextContent()
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(
      json.parseToJsonElement(text).jsonObject["workspaceId"]!!.jsonPrimitive.content
    )
  }

  private fun warmDaemonFor(workspaceId: WorkspaceId, modulePath: String): FakeDaemon {
    supervisor.daemonFor(workspaceId, modulePath)
    return factory.daemons.getValue(workspaceId to modulePath)
  }

  private fun writePng(file: File, width: Int, height: Int) {
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val socketServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val socket = java.net.Socket(socketServer.inetAddress, socketServer.localPort)
    val accepted = socketServer.accept()
    socketServer.close()
    return socket.getOutputStream() to accepted.getInputStream()
  }
}
