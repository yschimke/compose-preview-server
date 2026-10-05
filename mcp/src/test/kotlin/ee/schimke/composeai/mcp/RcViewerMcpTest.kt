package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ResourceContents
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [RcViewerMcp] on its own: the `rc_open` tool, the viewer resource, and live reload (#1237). */
class RcViewerMcpTest {
  @get:Rule val tmp = TemporaryFolder()

  private val subscribed = mutableMapOf<String, Set<Session>>()
  private val viewer = RcViewerMcp(subscribers = { subscribed[it].orEmpty() }, pollIntervalMs = 0)

  private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

  private fun rcFile(name: String = "watch.rc", bytes: ByteArray = FIXTURE): File =
    tmp.newFile(name).apply { writeBytes(bytes) }

  private fun call(args: JsonObject, requestMeta: JsonObject? = null): CallToolResult =
    runBlocking {
      withContext(OpenAiRequestMeta(requestMeta)) { viewer.handle("rc_open", args)!! }
    }

  private fun errorCode(result: CallToolResult): String? {
    assertThat(result.isError).isTrue()
    return result.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content
  }

  /** Everything the app (and the model) can see of a result. */
  private fun visible(result: CallToolResult): String =
    Json.encodeToString(CallToolResult.serializer(), result)

  @Test
  fun `rc_open is a file entrypoint tool linked to the viewer`() {
    val tool = viewer.toolDefs().single()
    assertThat(tool.name).isEqualTo("rc_open")
    assertThat(tool.title).isEqualTo("Remote Compose viewer")
    assertThat(tool.icons!!.single().src).startsWith("data:image/svg+xml;base64,")
    assertThat(tool.meta)
      .isEqualTo(
        parse(
          """
          {
            "ui": {"resourceUri": "ui://compose-preview/rc-viewer"},
            "ui/resourceUri": "ui://compose-preview/rc-viewer",
            "openai/ui": {"entrypoints": [{"type": "file", "extensions": ["rc"]}]}
          }
          """
        )
      )
    val properties = tool.inputSchema.jsonObject["properties"]!!.jsonObject
    // FileInput's `file`, unchanged, plus `path` for the model.
    assertThat(properties["file"])
      .isEqualTo(OpenAiUi.FILE_INPUT_SCHEMA["properties"]!!.jsonObject["file"])
    assertThat(properties["path"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
    assertThat(tool.inputSchema.jsonObject["required"]).isNull()
  }

  @Test
  fun `the viewer resource is an MCP App with display modes and the player inlined`() {
    val descriptor = viewer.resourceDescriptors().single()
    assertThat(descriptor.uri).isEqualTo("ui://compose-preview/rc-viewer")
    assertThat(descriptor.mimeType).isEqualTo("text/html;profile=mcp-app")
    assertThat(descriptor.meta)
      .isEqualTo(
        parse(
          """
          {
            "ui": {
              "prefersBorder": true,
              "csp": {"resourceDomains": ["https://fonts.googleapis.com", "https://fonts.gstatic.com"]}
            },
            "openai/ui": {"availableDisplayModes": ["inline", "fullscreen"]}
          }
          """
        )
      )

    val content = viewer.readResource(RcViewerMcp.VIEWER_URI)!!.contents.single()
    content as ResourceContents.Text
    assertThat(content.mimeType).isEqualTo("text/html;profile=mcp-app")
    assertThat(content.meta).isEqualTo(descriptor.meta)
    assertThat(content.text).doesNotContain(RcViewerMcp.PLAYER_PLACEHOLDER)
    assertThat(content.text).contains("var RC = (() => {")
    assertThat(content.text).contains("RcdPlayer")
    assertThat(content.text).contains("request('ui/initialize'")
    assertThat(content.text).contains("'openai/resource': { representation: 'blob' }")
    assertThat(content.text).contains("request('resources/subscribe'")
    assertThat(content.text).contains("request('ui/update-model-context', params)")
    assertThat(content.text).contains("if (event.source !== window.parent) return;")
    // One script element holds the whole bundle.
    assertThat(Regex("</script>").findAll(content.text).count()).isEqualTo(2)
  }

  @Test
  fun `the player bundle is refused when it could break out of its script element`() {
    val html = "<script>${RcViewerMcp.PLAYER_PLACEHOLDER}</script>"
    assertThat(RcViewerMcp.assembleViewerHtml(html, "var RC = 1;"))
      .isEqualTo("<script>var RC = 1;</script>")
    assertThrows(IllegalStateException::class.java) {
      RcViewerMcp.assembleViewerHtml(html, "x = '</SCRIPT>'")
    }
    assertThrows(IllegalStateException::class.java) {
      RcViewerMcp.assembleViewerHtml("<script></script>", "var RC = 1;")
    }
  }

  @Test
  fun `a path call returns the bytes for the app and a server URI, never the path`() {
    val file = rcFile()
    val result = call(buildJsonObject { put("path", file.absolutePath) })
    assertThat(result.isError).isNull()
    val structured = result.structuredContent!!
    assertThat(structured["source"]!!.jsonPrimitive.content).isEqualTo("server")
    assertThat(structured["name"]!!.jsonPrimitive.content).isEqualTo("watch.rc")
    assertThat(structured["sizeBytes"]!!.jsonPrimitive.content.toInt()).isEqualTo(FIXTURE.size)
    val uri = structured["resourceUri"]!!.jsonPrimitive.content
    assertThat(uri).matches("compose-preview-rc://document/[0-9a-f]{32}/watch\\.rc")
    val inline =
      result.meta!!["compose-preview/rc"]!!.jsonObject["documentBase64"]!!.jsonPrimitive.content
    assertThat(Base64.getDecoder().decode(inline)).isEqualTo(FIXTURE)
    assertThat(visible(result)).doesNotContain(file.parent)
    assertThat((result.content.single() as ContentBlock.Text).text).contains("watch.rc")

    // The server URI reads the file as it is now, as a blob.
    val read = viewer.readResource(uri)!!.contents.single() as ResourceContents.Blob
    assertThat(read.mimeType).isEqualTo(RcViewerMcp.RC_MIME_TYPE)
    assertThat(Base64.getDecoder().decode(read.blob)).isEqualTo(FIXTURE)

    // Reopening the same file keeps its URI, so a subscribed viewer stays subscribed.
    val again = call(buildJsonObject { put("path", file.absolutePath) })
    assertThat(again.structuredContent!!["resourceUri"]!!.jsonPrimitive.content).isEqualTo(uri)
  }

  @Test
  fun `a FileInput call acknowledges the host URI`() {
    val result =
      call(
        buildJsonObject {
          putJsonObject("file") {
            put("name", "watch.rc")
            put("resourceUri", "host-resource://watch")
          }
        }
      )
    assertThat(result.isError).isNull()
    assertThat(result.structuredContent)
      .isEqualTo(
        parse(
          """{"schema": "compose-preview/rc-document/v1", "source": "host", "name": "watch.rc",
              "resourceUri": "host-resource://watch"}"""
        )
      )
    assertThat(result.meta).isNull()
  }

  @Test
  fun `a FileInput call uses the host-injected path without returning it`() {
    val file = rcFile()
    val result =
      call(
        buildJsonObject {
          putJsonObject("file") {
            put("name", "watch.rc")
            put("resourceUri", "host-resource://watch")
          }
        },
        requestMeta =
          buildJsonObject { putJsonObject("openai/resource") { put("path", file.path) } },
      )
    val structured = result.structuredContent!!
    assertThat(structured["resourceUri"]!!.jsonPrimitive.content).isEqualTo("host-resource://watch")
    assertThat(structured["sizeBytes"]!!.jsonPrimitive.content.toInt()).isEqualTo(FIXTURE.size)
    val fallback = structured["fallbackResourceUri"]!!.jsonPrimitive.content
    assertThat(fallback).startsWith(RcViewerMcp.DOCUMENT_URI_PREFIX)
    assertThat(viewer.readResource(fallback)).isNotNull()
    assertThat(visible(result)).doesNotContain(file.parent)
  }

  @Test
  fun `bad arguments give structured errors`() {
    val directory = tmp.newFolder("folder.rc")
    val notRc = tmp.newFile("notes.txt")
    val cases =
      mapOf(
        buildJsonObject {} to RcViewerMcp.INVALID_ARGUMENTS,
        buildJsonObject { put("path", "") } to RcViewerMcp.INVALID_ARGUMENTS,
        buildJsonObject { put("path", "relative/watch.rc") } to RcViewerMcp.PATH_NOT_ABSOLUTE,
        buildJsonObject { put("path", File(tmp.root, "missing.rc").path) } to RcViewerMcp.NOT_FOUND,
        buildJsonObject { put("path", notRc.path) } to RcViewerMcp.UNSUPPORTED_EXTENSION,
        buildJsonObject { put("path", directory.path) } to RcViewerMcp.NOT_A_FILE,
        buildJsonObject { put("file", "watch.rc") } to RcViewerMcp.INVALID_ARGUMENTS,
        buildJsonObject { putJsonObject("file") { put("name", "watch.rc") } } to
          RcViewerMcp.INVALID_ARGUMENTS,
        buildJsonObject {
          putJsonObject("file") {
            put("name", "notes.txt")
            put("resourceUri", "host-resource://notes")
          }
        } to RcViewerMcp.UNSUPPORTED_EXTENSION,
      )
    for ((args, code) in cases) {
      val result = call(args)
      com.google.common.truth.Truth.assertWithMessage("$args")
        .that(errorCode(result))
        .isEqualTo(code)
      assertThat(visible(result)).doesNotContain(tmp.root.path)
    }
  }

  @Test
  fun `an oversized document is refused`() {
    val big = tmp.newFile("big.rc")
    RandomAccessFile(big, "rw").use { it.setLength(RcViewerMcp.MAX_DOCUMENT_BYTES + 1) }
    assertThat(errorCode(call(buildJsonObject { put("path", big.path) })))
      .isEqualTo(RcViewerMcp.TOO_LARGE)
  }

  @Test
  fun `an unknown document URI is not readable and a foreign URI is not ours`() {
    assertThrows(IllegalArgumentException::class.java) {
      viewer.readResource(RcViewerMcp.DOCUMENT_URI_PREFIX + "deadbeef/watch.rc")
    }
    assertThat(viewer.readResource("compose-preview://ws/:app/com.example.Card")).isNull()
    runBlocking { assertThat(viewer.handle("render_preview", buildJsonObject {})).isNull() }
  }

  @Test
  fun `a subscribed document that changes on disk notifies its subscribers`() {
    val file = rcFile()
    val uri =
      call(buildJsonObject { put("path", file.path) })
        .structuredContent!!["resourceUri"]!!
        .jsonPrimitive
        .content
    val session = RecordingSession()

    // Changed, but nobody subscribed: nothing to send.
    file.writeBytes(FIXTURE + 1)
    viewer.pollOnce()
    assertThat(session.updated).isEmpty()

    subscribed[uri] = setOf(session)
    viewer.pollOnce()
    assertThat(session.updated).containsExactly(uri)

    // Unchanged since: no second notification.
    viewer.pollOnce()
    assertThat(session.updated).containsExactly(uri)

    // Same size and mtime, different bytes (a same-second regeneration): the digest sees it.
    val mtime = file.lastModified()
    file.writeBytes(FIXTURE + 2)
    file.setLastModified(mtime)
    viewer.pollOnce()
    assertThat(session.updated).containsExactly(uri, uri)
  }

  private class RecordingSession : Session {
    val updated = CopyOnWriteArrayList<String>()

    override fun notifyResourceUpdated(uri: String) {
      updated += uri
    }

    override fun notifyResourceListChanged() = Unit

    override fun notifyToolListChanged() = Unit

    override fun notifyProgress(
      token: JsonElement,
      progress: Double,
      total: Double?,
      message: String?,
    ) = Unit
  }

  companion object {
    /** Any bytes do for the server: it never parses a document, the player does. */
    val FIXTURE: ByteArray = ByteArray(64) { it.toByte() }
  }
}

/** `rc_open` and the viewer registered in [DaemonMcpServer], over the MCP wire. */
class RcViewerMcpServerTest {
  @get:Rule val tmp = TemporaryFolder()

  private lateinit var supervisor: DaemonSupervisor
  private lateinit var server: DaemonMcpServer
  private lateinit var session: McpSession
  private lateinit var client: McpTestClient

  @Before
  fun setUp() {
    supervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
      )
    server = DaemonMcpServer(supervisor, workingDirectory = null)
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
    client.initialize()
  }

  @After
  fun tearDown() {
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { server.shutdown() }
    runCatching { supervisor.shutdown() }
  }

  @Test
  fun `rc_open and the viewer are listed with their _meta`() {
    val tool = client.awaitToolsContaining("rc_open").tools.single { it.name == "rc_open" }
    assertThat(tool.meta!!["openai/ui"]!!.jsonObject["entrypoints"])
      .isEqualTo(Json.parseToJsonElement("""[{"type":"file","extensions":["rc"]}]"""))
    assertThat(tool.meta!!["ui"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content)
      .isEqualTo(RcViewerMcp.VIEWER_URI)

    val listed =
      client.request("resources/list")["resources"]!!.jsonArray.single {
        it.jsonObject["uri"]!!.jsonPrimitive.content == RcViewerMcp.VIEWER_URI
      }
    assertThat(listed.jsonObject["mimeType"]!!.jsonPrimitive.content)
      .isEqualTo("text/html;profile=mcp-app")
    assertThat(listed.jsonObject["_meta"]!!.jsonObject["openai/ui"]).isNotNull()

    val read =
      client.request("resources/read", buildJsonObject { put("uri", RcViewerMcp.VIEWER_URI) })
    val content = read["contents"]!!.jsonArray.single().jsonObject
    assertThat(content["mimeType"]!!.jsonPrimitive.content).isEqualTo("text/html;profile=mcp-app")
    assertThat(content["text"]!!.jsonPrimitive.content).contains("RcdPlayer")
  }

  @Test
  fun `rc_open over the wire reads a path, and its document URI is readable`() {
    client.awaitToolsContaining("rc_open")
    val file = tmp.newFile("doc.rc").apply { writeBytes(RcViewerMcpTest.FIXTURE) }
    val result = client.callTool("rc_open", buildJsonObject { put("path", file.path) }).raw
    assertThat(result["isError"]?.jsonPrimitive?.content ?: "false").isEqualTo("false")
    assertThat(result.toString()).doesNotContain(file.parent)
    val uri = result["structuredContent"]!!.jsonObject["resourceUri"]!!.jsonPrimitive.content
    assertThat(result["_meta"]!!.jsonObject["compose-preview/rc"]!!.jsonObject["documentBase64"])
      .isNotNull()

    val read = client.request("resources/read", buildJsonObject { put("uri", uri) })
    val blob = read["contents"]!!.jsonArray.single().jsonObject["blob"]!!.jsonPrimitive.content
    assertThat(Base64.getDecoder().decode(blob)).isEqualTo(RcViewerMcpTest.FIXTURE)
    client.request("resources/subscribe", buildJsonObject { put("uri", uri) })
  }

  @Test
  fun `rc_open over the wire takes FileInput with the host-injected path`() {
    client.awaitToolsContaining("rc_open")
    val file = tmp.newFile("doc.rc").apply { writeBytes(RcViewerMcpTest.FIXTURE) }
    val result =
      client.request(
        "tools/call",
        buildJsonObject {
          put("name", "rc_open")
          putJsonObject("arguments") {
            putJsonObject("file") {
              put("name", "doc.rc")
              put("resourceUri", "host-resource://doc")
            }
          }
          putJsonObject("_meta") { putJsonObject("openai/resource") { put("path", file.path) } }
        },
      )
    val structured = result["structuredContent"]!!.jsonObject
    assertThat(structured["source"]!!.jsonPrimitive.content).isEqualTo("host")
    assertThat(structured["resourceUri"]!!.jsonPrimitive.content).isEqualTo("host-resource://doc")
    assertThat(structured["fallbackResourceUri"]).isNotNull()
    assertThat(result.toString()).doesNotContain(file.parent)
  }

  @Test
  fun `rc_open over the wire reports a bad path as a structured error`() {
    client.awaitToolsContaining("rc_open")
    val result =
      client
        .callTool("rc_open", buildJsonObject { put("path", File(tmp.root, "nope.rc").path) })
        .raw
    assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("true")
    assertThat(
        result["structuredContent"]!!
          .jsonObject["error"]!!
          .jsonObject["code"]!!
          .jsonPrimitive
          .content
      )
      .isEqualTo(RcViewerMcp.NOT_FOUND)
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val out = PipedOutputStream()
    val input = PipedInputStream(out, 1 shl 20)
    return out to input
  }
}
