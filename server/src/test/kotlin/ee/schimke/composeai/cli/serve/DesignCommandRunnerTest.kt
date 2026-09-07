package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What a reply means, and what a refusal costs.
 *
 * The behaviour under test is the reason the command exists: a refused export must not land on disk
 * looking like an export, and its diagnostics — the most useful thing the call produces — must
 * reach stderr rather than being thrown away because the artifact was technically present.
 */
class DesignCommandRunnerTest {

  private val logged = mutableListOf<String>()
  private val written = mutableMapOf<String, ByteArray>()

  private fun options(
    verb: String,
    designId: String = "spotify-wear-widget",
    out: String? = null,
    format: ExportFormatV1? = null,
  ) =
    DesignCommand.Options(
      verb = verb,
      designId = designId,
      out = out,
      format = format ?: if (verb == DesignCommand.RENDER) ExportFormatV1.PNG else null,
      revision = null,
      limit = 50,
      server = DesignCommand.defaultServer(),
      authorize = true,
      timeoutSeconds = 30,
    )

  private fun runner(
    options: DesignCommand.Options,
    reply: String,
    seen: MutableList<Pair<String, JsonObject>> = mutableListOf(),
  ) =
    DesignCommandRunner(
      options = options,
      transport =
        object : DesignMcpTransport {
          override fun call(tool: String, arguments: JsonObject): JsonObject {
            seen += tool to arguments
            return Json.parseToJsonElement(reply).jsonObject
          }
        },
      emit = { logged += it },
      write = { destination, bytes -> written[destination] = bytes },
    )

  @Test
  fun `an exported source file lands on disk with its digest reported`() {
    val reply =
      """
      {"type":"export","artifact":{"format":"compose","mediaType":"text/x-kotlin; charset=utf-8",
       "encoding":"utf8","content":"fun Widget() {}\n","contentDigest":"0123456789abcdef",
       "diagnostics":[]}}
      """
    val seen = mutableListOf<Pair<String, JsonObject>>()
    val code =
      runner(
          options(DesignCommand.EXPORT, out = "Widget.kt", format = ExportFormatV1.COMPOSE),
          reply,
          seen,
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_OK, code)
    assertEquals("fun Widget() {}\n", written.getValue("Widget.kt").decodeToString())
    assertEquals(ServeUiBuilderMcp.EXPORT, seen.single().first)
    assertEquals("compose", seen.single().second["format"].toString().trim('"'))
    assertTrue(logged.any { it.contains("Widget.kt") && it.contains("0123456789ab") }, "$logged")
  }

  @Test
  fun `a render is decoded from base64 before it is written`() {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    val reply =
      """
      {"type":"export","artifact":{"format":"png","mediaType":"image/png","encoding":"base64",
       "content":"${Base64.getEncoder().encodeToString(png)}","contentDigest":"deadbeefdeadbeef",
       "diagnostics":[]}}
      """
    assertEquals(DesignCommandRunner.EXIT_OK, runner(options(DesignCommand.RENDER), reply).run())
    assertTrue(png.contentEquals(written.getValue("spotify-wear-widget.png")))
  }

  /**
   * The case the issue was written about: `ui_builder_export` answers a design it cannot express
   * with a parseable artifact whose content is the refusal in comments. Writing that out and
   * exiting 0 would put a file that compiles to nothing into a pipeline and call it a success.
   */
  @Test
  fun `a refusal reaches stderr, writes nothing, and fails`() {
    val reply =
      """
      {"type":"export","artifact":{"format":"compose","mediaType":"text/x-kotlin; charset=utf-8",
       "encoding":"utf8","content":"// refused\n","contentDigest":"aaaa",
       "diagnostics":[
         {"severity":"error","code":"UNSUPPORTED_COMPONENT",
          "message":"`asset/image` has no Remote Compose counterpart"},
         {"severity":"error","code":"MISSING_ASSET",
          "message":"the image background `bg-art` needs a RemoteImageBitmap"}]}}
      """
    val code =
      runner(
          options(DesignCommand.EXPORT, out = "Widget.kt", format = ExportFormatV1.COMPOSE),
          reply,
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertTrue(written.isEmpty(), "a refusal wrote ${written.keys}")
    assertTrue(logged.any { it.contains("has no Remote Compose counterpart") }, "$logged")
    assertTrue(logged.any { it.contains("needs a RemoteImageBitmap") }, "$logged")
    assertTrue(logged.any { it.contains("2 errors") }, "$logged")
  }

  /** A warning is not a refusal: it is printed, and the artifact is still the artifact. */
  @Test
  fun `a warning is printed and the export still succeeds`() {
    val reply =
      """
      {"type":"export","artifact":{"format":"compose","mediaType":"text/x-kotlin","encoding":"utf8",
       "content":"fun Widget() {}","contentDigest":"bbbb",
       "diagnostics":[{"severity":"warning","code":"ASSET_PLACEHOLDER",
                       "message":"replace the painter on line 12"}]}}
      """
    assertEquals(
      DesignCommandRunner.EXIT_OK,
      runner(options(DesignCommand.EXPORT, out = "W.kt", format = ExportFormatV1.COMPOSE), reply)
        .run(),
    )
    assertTrue(logged.any { it.contains("replace the painter") }, "$logged")
    assertTrue(written.containsKey("W.kt"))
  }

  @Test
  fun `get writes the document rather than the snapshot around it`() {
    val reply =
      """
      {"type":"snapshot","snapshot":{"designId":"w","retainedFromSequence":0,
       "state":{"lastSequence":4,"document":{"id":"w","title":"Widget","revision":4}},
       "catalog":{"enormous":true}}}
      """
    assertEquals(
      DesignCommandRunner.EXIT_OK,
      runner(options(DesignCommand.GET, out = "w.json"), reply).run(),
    )
    val document = Json.parseToJsonElement(written.getValue("w.json").decodeToString()).jsonObject
    assertEquals("Widget", document["title"].toString().trim('"'))
    assertNull(document["catalog"])
    assertTrue(logged.any { it.contains("revision 4") }, "$logged")
  }

  @Test
  fun `list is tab separated so the first column can be cut`() {
    val reply =
      """
      {"type":"designs","designs":[
        {"designId":"a","title":"Widget","revision":3},
        {"designId":"b","title":"Now playing","revision":12}]}
      """
    assertEquals(
      DesignCommandRunner.EXIT_OK,
      runner(options(DesignCommand.LIST, designId = ""), reply).run(),
    )
    assertEquals(
      "a\t3\tWidget\nb\t12\tNow playing\n",
      written.getValue(DesignCommand.STDOUT).decodeToString(),
    )
  }

  /** Stdout carries the artifact, so the summary line that would compete with it is withheld. */
  @Test
  fun `writing to stdout does not narrate`() {
    val reply =
      """
      {"type":"export","artifact":{"format":"compose","mediaType":"text/x-kotlin","encoding":"utf8",
       "content":"fun W() {}","contentDigest":"cccc","diagnostics":[]}}
      """
    runner(
        options(DesignCommand.EXPORT, out = DesignCommand.STDOUT, format = ExportFormatV1.COMPOSE),
        reply,
      )
      .run()
    assertTrue(logged.isEmpty(), "$logged")
  }

  @Test
  fun `an artifact in the wrong format is a failure rather than a mislabelled file`() {
    val reply =
      """
      {"type":"export","artifact":{"format":"svg","mediaType":"image/svg+xml","encoding":"utf8",
       "content":"<svg/>","contentDigest":"dddd","diagnostics":[]}}
      """
    assertEquals(
      DesignCommandRunner.EXIT_FAILURE,
      runner(options(DesignCommand.RENDER), reply).run(),
    )
    assertTrue(written.isEmpty())
    assertTrue(logged.any { it.contains("asked for png and got svg") }, "$logged")
  }
}

/**
 * Three envelopes wrap every reply — JSON-RPC's, the MCP tool result's, and the UI builder's — and
 * each can carry the refusal. Peeling them is where a failing call turns into an empty artifact if
 * anything is dropped, so each layer is pinned.
 */
class DesignEnvelopeTest {

  /** One MCP `CallToolResult`, as the JSON-RPC reply that carries it. */
  private fun rpc(text: String, isError: Boolean = false): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", 1)
    put(
      "result",
      buildJsonObject {
        put(
          "content",
          buildJsonArray {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", text)
              }
            )
          },
        )
        if (isError) put("isError", true)
      },
    )
  }
    .toString()

  @Test
  fun `the response object comes out of a plain JSON reply`() {
    val response = unwrap("t", rpc("""{"callId":"1","response":{"type":"designs","designs":[]}}"""))
    assertEquals("designs", response["type"].toString().trim('"'))
  }

  /** Which transport framing arrives depends on the deployment; callers should not have to care. */
  @Test
  fun `an SSE frame carries the same reply`() {
    val json = rpc("""{"callId":"1","response":{"type":"designs","designs":[]}}""")
    val response = unwrap("t", "event: message\ndata: $json\n\n")
    assertEquals("designs", response["type"].toString().trim('"'))
  }

  @Test
  fun `a JSON-RPC error is reported rather than parsed as a result`() {
    val failure =
      assertFailsWith<DesignCommandFailure> {
        unwrap("t", """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"no such tool"}}""")
      }
    assertTrue(failure.message!!.contains("no such tool"), failure.message)
  }

  @Test
  fun `a tool error keeps the sentence the tool wrote`() {
    val failure =
      assertFailsWith<DesignCommandFailure> {
        unwrap("t", rpc("design is not shared with you", isError = true))
      }
    assertTrue(failure.message!!.contains("not shared with you"), failure.message)
  }

  /** An expired grant is a normal event, so it is raised as one and not as a mystery 404. */
  @Test
  fun `an unauthorized refusal asks for a grant instead of failing`() {
    assertFailsWith<DesignAuthorizationRequired> {
      unwrap("t", rpc("authorization_required", isError = true))
    }
    assertFailsWith<DesignAuthorizationRequired> {
      unwrap(
        "t",
        rpc(
          """{"callId":"1","response":{"type":"error","error":{"code":"unauthorized","message":"nope"}}}"""
        ),
      )
    }
  }

  @Test
  fun `a service error names the code it refused with`() {
    val failure =
      assertFailsWith<DesignCommandFailure> {
        unwrap(
          "t",
          rpc(
            """{"callId":"1","response":{"type":"error","error":{"code":"notFound","message":"no such design"}}}"""
          ),
        )
      }
    assertTrue(failure.message!!.contains("no such design"), failure.message)
    assertTrue(failure.message!!.contains("notFound"), failure.message)
  }
}
