package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.ResourceContents
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

/** Shared fixtures: a fake editor archive with the files the asset tests ask for. */
internal object UiBuilderArchiveFixture {
  const val VERSION = "3.70.0"
  const val SHELL =
    """<!doctype html><html><head><base href="__COMPOSE_UI_BUILDER_ASSET_BASE__" />""" +
      """<script>globalThis.composeUiBuilderMcpApp = { assetBase: "__COMPOSE_UI_BUILDER_ASSET_BASE__", version: "3.70.0" };</script>""" +
      """</head><body><script type="module" src="uiBuilder.mjs"></script></body></html>"""
  /** The 3.71.0 shell (compose-ui-builder#378): the layout placeholder beside the asset base. */
  const val LAYOUT_SHELL =
    """<!doctype html><html data-ui-builder-layout="__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__"><head>""" +
      """<base href="__COMPOSE_UI_BUILDER_ASSET_BASE__" />""" +
      """<script>globalThis.composeUiBuilderMcpApp = { assetBase: "__COMPOSE_UI_BUILDER_ASSET_BASE__", layout: "__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__", version: "3.71.0" };</script>""" +
      """</head><body><script type="module" src="uiBuilder.mjs"></script></body></html>"""
  val WASM: ByteArray = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0) + ByteArray(4096) { 7 }

  fun manifest(mcpApp: Int?): String =
    """{"schema":"compose-ui-builder-web/v1","version":"$VERSION","serverApi":1,"hostBridge":1""" +
      (if (mcpApp != null) ""","mcpApp":$mcpApp""" else "") +
      "}"

  fun files(mcpApp: Int? = 1, shell: String? = SHELL): Map<String, ByteArray> = buildMap {
    put("ui-builder-web.json", manifest(mcpApp).toByteArray())
    put("index.html", "<html>editor</html>".toByteArray())
    put("uiBuilder.wasm", WASM)
    put("uiBuilder.mjs", "export const x = 1;".toByteArray())
    put("ui-builder-boot.js", "console.log('boot')".toByteArray())
    put("styles.css", "body{}".toByteArray())
    put("m3-catalog-capabilities-v1.json", "{}".toByteArray())
    put("fonts/Roboto.woff2", byteArrayOf(1, 2, 3))
    put("fonts/Inter.ttf", byteArrayOf(4, 5, 6))
    if (shell != null) put(UiBuilderDesignMcp.SHELL_PATH, shell.toByteArray())
  }

  fun zip(dir: File, files: Map<String, ByteArray> = files()): File {
    val out = File(dir, "compose-preview-ui-builder-web-$VERSION.zip")
    ZipOutputStream(out.outputStream()).use { zip ->
      for ((name, bytes) in files) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return out
  }

  fun directory(dir: File, files: Map<String, ByteArray> = files()): File {
    for ((name, bytes) in files) {
      File(dir, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
    return dir
  }
}

/** The archive gate, the tool, the editor resource and the archive reader (#364). */
class UiBuilderDesignMcpTest {
  @get:Rule val tmp = TemporaryFolder()

  private val opened = mutableListOf<AutoCloseable>()
  private val logs = mutableListOf<String>()

  @After
  fun tearDown() {
    opened.forEach { runCatching { it.close() } }
  }

  private fun archive(files: Map<String, ByteArray> = UiBuilderArchiveFixture.files()) =
    UiBuilderWebArchive.open(UiBuilderArchiveFixture.zip(tmp.newFolder(), files))!!.also {
      opened += it
    }

  private fun design(files: Map<String, ByteArray> = UiBuilderArchiveFixture.files()) =
    UiBuilderDesignMcp.fromArchive(archive(files), onLog = { logs += it })?.also { opened += it }

  @Test
  fun `an archive with mcpApp 1 registers design_open and the editor resource`() {
    val design = design()!!
    val tool = design.toolDefs().single()
    assertThat(tool.name).isEqualTo("design_open")
    assertThat(tool.inputSchema).isEqualTo(OpenAiUi.FILE_INPUT_SCHEMA)
    assertThat(tool.meta!!["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content)
      .isEqualTo("ui://compose-ui-builder/editor")
    assertThat(tool.meta!!["openai/ui"]!!.jsonObject["entrypoints"])
      .isEqualTo(Json.parseToJsonElement("""[{"type":"file","extensions":[".uid"]}]"""))
    assertThat(tool.title).isEqualTo("Compose UI Builder")
    assertThat(tool.icons!!.single().mimeType).isEqualTo("image/svg+xml")
    // No `path` for model calls: the editor can only load and save through the host.
    assertThat((tool.inputSchema as JsonObject)["properties"]!!.jsonObject.keys)
      .containsExactly("file")

    val descriptor = design.resourceDescriptors().single()
    assertThat(descriptor.uri).isEqualTo(UiBuilderDesignMcp.EDITOR_URI)
    assertThat(descriptor.mimeType).isEqualTo("text/html;profile=mcp-app")
    assertThat(descriptor.meta!!["openai/ui"])
      .isEqualTo(
        Json.parseToJsonElement(
          """{"availableDisplayModes":["fullscreen"],"preferredDisplayMode":"fullscreen"}"""
        )
      )
    assertThat(design.editorVersion).isEqualTo(UiBuilderArchiveFixture.VERSION)
  }

  @Test
  fun `an archive without a supported mcpApp registers nothing`() {
    assertThat(design(UiBuilderArchiveFixture.files(mcpApp = null))).isNull()
    assertThat(design(UiBuilderArchiveFixture.files(mcpApp = 0))).isNull()
    assertThat(design(UiBuilderArchiveFixture.files(mcpApp = 2))).isNull()
    assertThat(logs.single()).contains("mcpApp 2")
    // mcpApp 1 claimed, but the shell is missing or has lost its placeholder.
    assertThat(design(UiBuilderArchiveFixture.files(shell = null))).isNull()
    assertThat(design(UiBuilderArchiveFixture.files(shell = "<html></html>"))).isNull()
    // No manifest at all: an archive that predates it.
    assertThat(design(UiBuilderArchiveFixture.files() - "ui-builder-web.json")).isNull()
  }

  @Test
  fun `no archive anywhere means no design_open`() {
    val env = mapOf(UiBuilderWebArchive.PATH_ENV to File(tmp.root, "missing.zip").path)
    assertThat(UiBuilderDesignMcp.fromEnvironment(env, onLog = { logs += it })).isNull()
    // No env, no app home, and a code source with no `ui-builder/` beside it.
    assertThat(
        UiBuilderWebArchive.locate(
          emptyMap(),
          property = { null },
          codeSource = File(tmp.newFolder("lib"), "x.jar"),
        )
      )
      .isNull()
  }

  @Test
  fun `the archive is found by override, app home, then the jar's install`() {
    val zip = UiBuilderArchiveFixture.zip(tmp.newFolder())
    assertThat(
        UiBuilderWebArchive.locate(
          mapOf(UiBuilderWebArchive.PATH_ENV to zip.path),
          property = { null },
          codeSource = null,
        )
      )
      .isEqualTo(zip)

    val home = tmp.newFolder("home")
    val packaged =
      File(home, "ui-builder/compose-preview-ui-builder-web.zip").apply {
        parentFile.mkdirs()
        writeBytes(zip.readBytes())
      }
    assertThat(
        UiBuilderWebArchive.locate(
          mapOf("APP_HOME" to home.path),
          property = { null },
          codeSource = null,
        )
      )
      .isEqualTo(packaged)
    assertThat(
        UiBuilderWebArchive.locate(
          emptyMap(),
          property = { null },
          codeSource = File(home, "lib/compose-preview-mcp.jar"),
        )
      )
      .isEqualTo(packaged)

    val design = UiBuilderDesignMcp.fromEnvironment(mapOf("APP_HOME" to home.path))
    assertThat(design).isNotNull()
    design!!.close()
  }

  @Test
  fun `reading the editor fills in the layout setting, once per read, focused unless full`() {
    val design =
      design(UiBuilderArchiveFixture.files(shell = UiBuilderArchiveFixture.LAYOUT_SHELL))!!
    fun read(layout: String?): String =
      (design.readResource(UiBuilderDesignMcp.EDITOR_URI, layout)!!.contents.single()
          as ResourceContents.Text)
        .text
    for ((setting, expected) in
      listOf(
        null to "focused",
        "focused" to "focused",
        "full" to "full",
        // Anything else never reaches the shell: the editor would read it as focused anyway.
        "fullscreen" to "focused",
        "" to "focused",
        "\" onload=\"x" to "focused",
      )) {
      val text = read(setting)
      assertThat(text).doesNotContain(UiBuilderDesignMcp.LAYOUT_PLACEHOLDER)
      assertThat(text).contains("data-ui-builder-layout=\"$expected\"")
      assertThat(text).contains("layout: \"$expected\"")
    }
    // The same surface answers a changed setting on the next read; nothing is cached.
    assertThat(read("full")).contains("layout: \"full\"")
    assertThat(read(null)).contains("layout: \"focused\"")
  }

  @Test
  fun `a shell without the layout placeholder is served the same whatever the setting`() {
    val design = design()!!
    fun read(layout: String): String =
      (design.readResource(UiBuilderDesignMcp.EDITOR_URI, layout)!!.contents.single()
          as ResourceContents.Text)
        .text
    assertThat(read("full")).isEqualTo(read("focused"))
  }

  @Test
  fun `reading the editor fills in the asset base and declares the origin in the CSP`() {
    val design = design()!!
    val content =
      design.readResource(UiBuilderDesignMcp.EDITOR_URI)!!.contents.single()
        as ResourceContents.Text
    assertThat(content.mimeType).isEqualTo("text/html;profile=mcp-app")
    assertThat(content.text).doesNotContain("__COMPOSE_UI_BUILDER_ASSET_BASE__")
    val base =
      Regex("""<base href="([^"]+)"""").find(content.text)!!.groupValues[1].let(URI::create)
    assertThat(base.scheme).isEqualTo("http")
    assertThat(base.host).isEqualTo("127.0.0.1")
    assertThat(base.port).isGreaterThan(0)
    assertThat(base.path).isEqualTo("/ui-builder/v/3.70.0/")
    assertThat(content.text).contains("assetBase: \"$base\"")

    val origin = "http://127.0.0.1:${base.port}"
    val csp = content.meta!!["ui"]!!.jsonObject["csp"]!!.jsonObject
    assertThat(csp["resourceDomains"]).isEqualTo(Json.parseToJsonElement("""["$origin"]"""))
    assertThat(csp["connectDomains"]).isEqualTo(Json.parseToJsonElement("""["$origin"]"""))
    assertThat(
        content.meta!!["openai/ui"]!!.jsonObject["preferredDisplayMode"]!!.jsonPrimitive.content
      )
      .isEqualTo("fullscreen")

    // The listener is real and serves the archive's Wasm, cross-origin, as `application/wasm`.
    val http = HttpClient.newHttpClient()
    val wasm =
      http.send(
        HttpRequest.newBuilder(base.resolve("uiBuilder.wasm"))
          .header("Origin", "https://sandbox.example")
          .build(),
        HttpResponse.BodyHandlers.ofByteArray(),
      )
    assertThat(wasm.statusCode()).isEqualTo(200)
    assertThat(wasm.headers().firstValue("content-type").get()).isEqualTo("application/wasm")
    assertThat(wasm.headers().firstValue("access-control-allow-origin").get()).isEqualTo("*")
    assertThat(wasm.body()).isEqualTo(UiBuilderArchiveFixture.WASM)

    // A second read reuses the same listener.
    val again =
      design.readResource(UiBuilderDesignMcp.EDITOR_URI)!!.contents.single()
        as ResourceContents.Text
    assertThat(again.text).isEqualTo(content.text)
    assertThat(design.readResource("ui://compose-preview/rc-viewer")).isNull()
  }

  @Test
  fun `the asset origin starts only when the editor is first read`() {
    val design = design()!!
    design.toolDefs()
    design.resourceDescriptors()
    design.handle("design_open", fileInput("a.uid"))
    assertThat(logs.filter { "editor assets at" in it }).isEmpty()
    design.readResource(UiBuilderDesignMcp.EDITOR_URI)
    assertThat(logs.filter { "editor assets at" in it }).hasSize(1)
  }

  @Test
  fun `design_open acknowledges a uid FileInput and refuses anything else`() {
    val design = design()!!
    val ok = design.handle("design_open", fileInput("screens/home.uid"))!!
    assertThat(ok.isError).isNull()
    assertThat(ok.structuredContent!!["name"]!!.jsonPrimitive.content).isEqualTo("home.uid")
    assertThat(ok.structuredContent!!["resourceUri"]!!.jsonPrimitive.content)
      .isEqualTo("host-resource://file/1")

    val wrong = design.handle("design_open", fileInput("notes.txt"))!!
    assertThat(wrong.isError).isTrue()
    assertThat(errorCode(wrong.structuredContent!!)).isEqualTo("unsupported_extension")

    val path = design.handle("design_open", buildJsonObject { put("path", "/tmp/a.uid") })!!
    assertThat(path.isError).isTrue()
    assertThat(errorCode(path.structuredContent!!)).isEqualTo("invalid_arguments")

    val blank =
      design.handle(
        "design_open",
        buildJsonObject {
          putJsonObject("file") {
            put("name", "a.uid")
            put("resourceUri", " ")
          }
        },
      )!!
    assertThat(errorCode(blank.structuredContent!!)).isEqualTo("invalid_arguments")
    assertThat(design.handle("rc_open", fileInput("a.uid"))).isNull()
  }

  @Test
  fun `archive paths are plain relative file names`() {
    for (ok in listOf("uiBuilder.wasm", "fonts/Roboto.woff2", "mcp-app/ui-builder-mcp-app.html")) {
      assertThat(UiBuilderWebArchive.isSafeArchivePath(ok)).isTrue()
    }
    for (bad in
      listOf(
        "",
        "/etc/passwd",
        "../secret",
        "fonts/../../secret",
        "./uiBuilder.wasm",
        "fonts//x",
        "fonts/",
        "..\\secret",
        "C:/x",
        "a\u0000b",
      )) {
      assertThat(UiBuilderWebArchive.isSafeArchivePath(bad)).isFalse()
    }
  }

  @Test
  fun `a directory archive never serves a file outside it, even through a symlink`() {
    val root = UiBuilderArchiveFixture.directory(tmp.newFolder("unpacked"))
    val secret = tmp.newFile("secret.txt").apply { writeText("secret") }
    Files.createSymbolicLink(File(root, "link.txt").toPath(), secret.toPath())
    val archive = UiBuilderWebArchive.open(root)!!
    assertThat(archive.entry("uiBuilder.wasm")!!.size)
      .isEqualTo(UiBuilderArchiveFixture.WASM.size.toLong())
    assertThat(archive.entry("link.txt")).isNull()
    assertThat(archive.entry("../secret.txt")).isNull()
    assertThat(archive.entry("fonts")).isNull()
    assertThat(archive.manifest!!.mcpApp).isEqualTo(1)
  }

  private fun fileInput(name: String): JsonObject = buildJsonObject {
    putJsonObject("file") {
      put("name", name)
      put("resourceUri", "host-resource://file/1")
    }
  }

  private fun errorCode(structured: JsonObject): String =
    structured["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
}

/** The asset origin's HTTP behaviour, header by header (#366's list). */
class UiBuilderAssetOriginTest {
  @get:Rule val tmp = TemporaryFolder()

  private val prefix = "/ui-builder/v/3.70.0/"

  private fun archive() = UiBuilderWebArchive.open(UiBuilderArchiveFixture.zip(tmp.newFolder()))!!

  @Test
  fun `content types follow the extension`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    val expected =
      mapOf(
        "uiBuilder.wasm" to "application/wasm",
        "uiBuilder.mjs" to "text/javascript; charset=utf-8",
        "ui-builder-boot.js" to "text/javascript; charset=utf-8",
        "styles.css" to "text/css; charset=utf-8",
        "m3-catalog-capabilities-v1.json" to "application/json",
        "index.html" to "text/html; charset=utf-8",
        "fonts/Roboto.woff2" to "font/woff2",
        "fonts/Inter.ttf" to "font/ttf",
      )
    for ((path, type) in expected) {
      val response = client.get(prefix + path)
      assertThat(response.status).isEqualTo(HttpStatusCode.OK)
      assertThat(response.headers[HttpHeaders.ContentType]).isEqualTo(type)
      assertThat(response.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
      assertThat(response.headers[HttpHeaders.CacheControl]).contains("immutable")
    }
    assertThat(client.get(prefix + "uiBuilder.wasm").bodyAsBytes())
      .isEqualTo(UiBuilderArchiveFixture.WASM)
    archive.close()
  }

  @Test
  fun `every response allows any origin and never credentials`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    for (path in listOf(prefix + "uiBuilder.mjs", prefix + "missing.js")) {
      val response = client.get(path) { header(HttpHeaders.Origin, "null") }
      assertThat(response.headers[HttpHeaders.AccessControlAllowOrigin]).isEqualTo("*")
      assertThat(response.headers[HttpHeaders.AccessControlAllowCredentials]).isNull()
    }
    archive.close()
  }

  @Test
  fun `a Private Network Access preflight is answered`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    val preflight =
      client.options(prefix + "uiBuilder.wasm") {
        header(HttpHeaders.Origin, "https://sandbox.example")
        header(HttpHeaders.AccessControlRequestMethod, "GET")
        header("Access-Control-Request-Private-Network", "true")
      }
    assertThat(preflight.status).isEqualTo(HttpStatusCode.NoContent)
    assertThat(preflight.headers["Access-Control-Allow-Private-Network"]).isEqualTo("true")
    assertThat(preflight.headers[HttpHeaders.AccessControlAllowOrigin]).isEqualTo("*")
    assertThat(preflight.headers[HttpHeaders.AccessControlAllowMethods])
      .isEqualTo("GET, HEAD, OPTIONS")

    // An ordinary CORS preflight gets no PNA grant it did not ask for.
    val plain =
      client.options(prefix + "uiBuilder.wasm") {
        header(HttpHeaders.Origin, "https://sandbox.example")
        header(HttpHeaders.AccessControlRequestMethod, "GET")
      }
    assertThat(plain.status).isEqualTo(HttpStatusCode.NoContent)
    assertThat(plain.headers["Access-Control-Allow-Private-Network"]).isNull()
    archive.close()
  }

  @Test
  fun `HEAD answers the headers without the body`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    val head = client.head(prefix + "uiBuilder.wasm")
    assertThat(head.status).isEqualTo(HttpStatusCode.OK)
    assertThat(head.headers[HttpHeaders.ContentType]).isEqualTo("application/wasm")
    assertThat(head.headers[HttpHeaders.ContentLength])
      .isEqualTo(UiBuilderArchiveFixture.WASM.size.toString())
    assertThat(head.bodyAsBytes()).isEmpty()
    archive.close()
  }

  @Test
  fun `only GET HEAD and OPTIONS are allowed`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    val post = client.post(prefix + "uiBuilder.wasm")
    assertThat(post.status).isEqualTo(HttpStatusCode.MethodNotAllowed)
    assertThat(post.headers[HttpHeaders.Allow]).isEqualTo("GET, HEAD, OPTIONS")
    archive.close()
  }

  @Test
  fun `traversal, other prefixes and files outside the archive are refused`() = testApplication {
    val archive = archive()
    application { installUiBuilderAssets(archive, prefix) }
    for (path in
      listOf(
        prefix + "%2e%2e/%2e%2e/etc/passwd",
        prefix + "..%2F..%2Fetc%2Fpasswd",
        prefix + "fonts%2F..%2F..%2Fsecret",
        prefix + "%2Fetc%2Fpasswd",
        prefix + "..%5C..%5Csecret",
        prefix + "not-in-archive.js",
        prefix,
        prefix + "fonts",
        "/ui-builder/v/3.69.0/uiBuilder.wasm",
        "/uiBuilder.wasm",
        "/",
      )) {
      val response = client.get(path)
      assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
      assertThat(response.bodyAsText()).isEqualTo("Not found")
    }
    archive.close()
  }
}

/**
 * `design_open` registered in [DaemonMcpServer], over the MCP wire, with and without an archive.
 */
class UiBuilderDesignMcpServerTest {
  @get:Rule val tmp = TemporaryFolder()

  private val closers = mutableListOf<() -> Unit>()

  @After
  fun tearDown() {
    closers.reversed().forEach { runCatching { it() } }
  }

  private fun connect(
    design: UiBuilderDesignMcp?,
    settings: PreviewSettingsStore = PreviewSettingsStore(File(tmp.root, "settings.json")) {},
  ): McpTestClient {
    val supervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
      )
    val server =
      DaemonMcpServer(
        supervisor,
        workingDirectory = null,
        previewSettingsStore = settings,
        uiBuilderDesign = design,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    val session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    val client = McpTestClient(input = clientFromServer, output = clientToServer)
    closers += { supervisor.shutdown() }
    closers += { server.shutdown() }
    closers += { session.close() }
    closers += { client.close() }
    client.initialize()
    return client
  }

  @Test
  fun `design_open and the editor are listed, readable and callable`() {
    val archive = UiBuilderWebArchive.open(UiBuilderArchiveFixture.zip(tmp.newFolder()))!!
    val client = connect(UiBuilderDesignMcp.fromArchive(archive, onLog = {})!!)
    val tool = client.awaitToolsContaining("design_open").tools.single { it.name == "design_open" }
    assertThat(tool.meta!!["openai/ui"]!!.jsonObject["entrypoints"])
      .isEqualTo(Json.parseToJsonElement("""[{"type":"file","extensions":[".uid"]}]"""))

    val listed =
      client.request("resources/list")["resources"]!!.jsonArray.map {
        it.jsonObject["uri"]!!.jsonPrimitive.content
      }
    assertThat(listed).contains(UiBuilderDesignMcp.EDITOR_URI)

    val read =
      client.request(
        "resources/read",
        buildJsonObject { put("uri", UiBuilderDesignMcp.EDITOR_URI) },
      )
    val content = read["contents"]!!.jsonArray.single().jsonObject
    assertThat(content["mimeType"]!!.jsonPrimitive.content).isEqualTo("text/html;profile=mcp-app")
    assertThat(content["text"]!!.jsonPrimitive.content).contains("http://127.0.0.1:")
    assertThat(content["_meta"]!!.jsonObject["ui"]!!.jsonObject["csp"]).isNotNull()

    val result =
      client
        .callTool(
          "design_open",
          buildJsonObject {
            putJsonObject("file") {
              put("name", "home.uid")
              put("resourceUri", "host-resource://file/1")
            }
          },
        )
        .raw
    assertThat(result["isError"]?.jsonPrimitive?.content ?: "false").isEqualTo("false")
    assertThat(result["structuredContent"]!!.jsonObject["name"]!!.jsonPrimitive.content)
      .isEqualTo("home.uid")
  }

  @Test
  fun `the editor opens in the layout the settings file names`() {
    val archive =
      UiBuilderWebArchive.open(
        UiBuilderArchiveFixture.zip(
          tmp.newFolder(),
          UiBuilderArchiveFixture.files(shell = UiBuilderArchiveFixture.LAYOUT_SHELL),
        )
      )!!
    val file = File(tmp.root, "layout-settings.json")
    val settings = PreviewSettingsStore(file) {}
    val client = connect(UiBuilderDesignMcp.fromArchive(archive, onLog = {})!!, settings)
    fun layout(): String {
      val read =
        client.request(
          "resources/read",
          buildJsonObject { put("uri", UiBuilderDesignMcp.EDITOR_URI) },
        )
      val text = read["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
      assertThat(text).doesNotContain(UiBuilderDesignMcp.LAYOUT_PLACEHOLDER)
      return Regex("layout: \"([^\"]*)\"").find(text)!!.groupValues[1]
    }
    // Unset: the default.
    assertThat(layout()).isEqualTo("focused")
    // Set through settings_update (or by the CLI writing the file): the next read follows it.
    settings.update(mapOf(PreviewSettings.UI_BUILDER_MCP_APP_LAYOUT to JsonPrimitive("full")))
    assertThat(layout()).isEqualTo("full")
    // A hand-edited value the schema refuses falls back to the default.
    file.writeText(
      """{"schema":"compose-preview-settings/v1","values":{"uiBuilderMcpAppLayout":"wide"}}"""
    )
    file.setLastModified(System.currentTimeMillis() + 5_000)
    assertThat(layout()).isEqualTo("focused")
  }

  @Test
  fun `without an mcpApp archive neither the tool nor the resource exists`() {
    val client = connect(null)
    val names = client.awaitToolsContaining("rc_open").tools.map { it.name }
    assertThat(names).doesNotContain("design_open")
    val listed =
      client.request("resources/list")["resources"]!!.jsonArray.map {
        it.jsonObject["uri"]!!.jsonPrimitive.content
      }
    assertThat(listed).doesNotContain(UiBuilderDesignMcp.EDITOR_URI)
    val result = client.callTool("design_open", buildJsonObject { putJsonObject("file") {} }).raw
    assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("true")
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val out = PipedOutputStream()
    val input = PipedInputStream(out, 1 shl 20)
    return out to input
  }
}
