package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `a2ui render`: argv and the environment as a pure function, and what the runner makes of the
 * catalog MCP replies — against canned envelopes, no socket.
 */
class A2uiCommandTest {

  private val noEnvironment: (String) -> String? = { null }

  private fun run(vararg args: String, env: (String) -> String? = noEnvironment) =
    assertIs<A2uiCommand.Parsed.Run>(A2uiCommand.parse(args.toList(), env)).options

  private fun invalid(vararg args: String) =
    assertIs<A2uiCommand.Parsed.Invalid>(A2uiCommand.parse(args.toList(), noEnvironment)).message

  @Test
  fun `no verb or help prints usage`() {
    assertEquals(A2uiCommand.Parsed.Help, A2uiCommand.parse(emptyList(), noEnvironment))
    assertEquals(A2uiCommand.Parsed.Help, A2uiCommand.parse(listOf("help"), noEnvironment))
    assertEquals(
      A2uiCommand.Parsed.Help,
      A2uiCommand.parse(listOf("render", "--help"), noEnvironment),
    )
  }

  @Test
  fun `render defaults the catalog, the preview and the output`() {
    val options = run("render", "--document", "cards/card.jsonl")
    assertEquals(A2uiCommand.DEFAULT_CATALOG, options.catalog)
    assertNull(options.previewId)
    assertEquals("cards/card.png", options.out)
    assertEquals(DesignCommand.defaultServer(), options.server)
    assertTrue(options.authorize)
    assertEquals("a2ui.png", run("render", "--document", "-").out)
  }

  @Test
  fun `flags and the environment override the defaults`() {
    val options =
      run(
        "render",
        "--document",
        "-",
        "--out",
        "-",
        "--catalog",
        "mine",
        "--preview",
        "p.Doc",
        "--no-authorize",
        "--timeout",
        "5",
        env = { if (it == DesignCommand.SERVER_ENV) "https://preview.example" else null },
      )
    assertEquals(DesignCommand.STDOUT, options.document)
    assertEquals(DesignCommand.STDOUT, options.out)
    assertEquals("mine", options.catalog)
    assertEquals("p.Doc", options.previewId)
    assertEquals("https://preview.example", options.server)
    assertEquals(5, options.timeoutSeconds)
    assertTrue(!options.authorize)
    assertEquals("http://h:1", run("render", "--document", "d", "--server", "http://h:1").server)
  }

  @Test
  fun `bad argv is refused by name`() {
    assertTrue(invalid("render").contains("--document"))
    assertTrue(invalid("paint", "--document", "d").contains("unknown verb"))
    assertTrue(invalid("render", "--document", "d", "--bogus").contains("--bogus"))
    assertTrue(invalid("render", "--document").contains("needs a value"))
    assertTrue(invalid("render", "--document", "d", "--timeout", "0").contains("positive"))
    assertTrue(invalid("render", "--document", "d", "--token", "t").contains("on purpose"))
    assertTrue(invalid("render", "--document", "d", "stray").contains("unexpected"))
  }

  @Test
  fun `the binary routes a2ui to a client invocation`() {
    assertEquals(
      ServerCommands.Invocation.Client(ServerCommands.A2UI, listOf("render", "--document", "d")),
      ServerCommands.parse(listOf("a2ui", "render", "--document", "d")),
    )
    assertEquals(
      ServerCommands.Invocation.Help(ServerCommands.A2UI),
      ServerCommands.parse(listOf("help", "a2ui")),
    )
  }

  // ------------------------------------------------------------------ runner

  private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)

  private fun textResult(text: String) =
    """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":${
      kotlinx.serialization.json.JsonPrimitive(text)
    }}]}}"""

  private val listing =
    textResult(
      """{"catalogs":[{"catalog":"a2ui-catalog","previews":[""" +
        """{"id":"other","knobs":[{"key":"label","type":"string"}]},""" +
        """{"id":"doc.Preview","knobs":[{"key":"document","type":"string"}]}]}]}"""
    )

  private val rendered =
    """{"jsonrpc":"2.0","id":1,"result":{"content":[""" +
      """{"type":"image","data":"${Base64.getEncoder().encodeToString(png)}","mimeType":"image/png"},""" +
      """{"type":"text","text":"{\"generation\":\"live\",\"overridesApplied\":true}"}]}}"""

  private fun runner(
    replies: Map<String, String>,
    seen: MutableList<Pair<String, JsonObject>>,
    written: MutableMap<String, ByteArray>,
    preview: String? = null,
  ) =
    A2uiCommandRunner(
      options =
        A2uiCommand.Options(
          verb = A2uiCommand.RENDER,
          server = DesignCommand.defaultServer(),
          catalog = "a2ui-catalog",
          previewId = preview,
          document = "doc.jsonl",
          out = "doc.png",
          authorize = true,
          timeoutSeconds = 5,
        ),
      transport = { tool, arguments ->
        seen += tool to arguments
        replies.getValue(tool)
      },
      readDocument = { "{\"components\":[]}" },
      emit = {},
      write = { destination, bytes -> written[destination] = bytes },
    )

  @Test
  fun `the document preview is found by its knob and the png is decoded to disk`() {
    val seen = mutableListOf<Pair<String, JsonObject>>()
    val written = mutableMapOf<String, ByteArray>()
    val code =
      runner(mapOf("list_previews" to listing, "render_preview" to rendered), seen, written).run()
    assertEquals(DesignCommandRunner.EXIT_OK, code)
    assertEquals(listOf("list_previews", "render_preview"), seen.map { it.first })
    val render = seen.last().second
    assertEquals("doc.Preview", render["previewId"]!!.jsonPrimitive.content)
    assertEquals("png", render["observe"]!!.jsonPrimitive.content)
    assertEquals(
      "{\"components\":[]}",
      render["overrides"]!!.jsonObject["knob.document"]!!.jsonPrimitive.content,
    )
    assertContentEquals(png, written["doc.png"])
  }

  @Test
  fun `an explicit preview skips the listing`() {
    val seen = mutableListOf<Pair<String, JsonObject>>()
    runner(mapOf("render_preview" to rendered), seen, mutableMapOf(), preview = "p.X").run()
    assertEquals(listOf("render_preview"), seen.map { it.first })
  }

  @Test
  fun `a catalog with no document knob says so`() {
    val none = textResult("""{"catalogs":[{"catalog":"a2ui-catalog","previews":[{"id":"x"}]}]}""")
    val failure =
      assertFailsWith<DesignCommandFailure> {
        runner(mapOf("list_previews" to none), mutableListOf(), mutableMapOf()).run()
      }
    assertTrue(failure.message!!.contains("--preview"), failure.message)
  }

  @Test
  fun `a baked answer that ignored the document is a failure, not a file`() {
    val baked =
      """{"jsonrpc":"2.0","id":1,"result":{"content":[""" +
        """{"type":"image","data":"${Base64.getEncoder().encodeToString(png)}","mimeType":"image/png"},""" +
        """{"type":"text","text":"{\"generation\":\"baked\",\"overridesApplied\":false}"}]}}"""
    val written = mutableMapOf<String, ByteArray>()
    assertFailsWith<DesignCommandFailure> {
      runner(mapOf("render_preview" to baked), mutableListOf(), written, preview = "p").run()
    }
    assertTrue(written.isEmpty())
  }

  @Test
  fun `a missing live grant asks for one rather than failing`() {
    val refused =
      """{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[""" +
        """{"type":"text","text":"live grant scope is required"}]}}"""
    assertFailsWith<DesignAuthorizationRequired> {
      runner(mapOf("render_preview" to refused), mutableListOf(), mutableMapOf(), preview = "p")
        .run()
    }
    val other =
      """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"no such preview 'p'"}}"""
    val failure =
      assertFailsWith<DesignCommandFailure> {
        runner(mapOf("render_preview" to other), mutableListOf(), mutableMapOf(), preview = "p")
          .run()
      }
    assertTrue(failure.message!!.contains("no such preview"))
  }

  @Test
  fun `an SSE frame carries the same reply`() {
    val written = mutableMapOf<String, ByteArray>()
    runner(
        mapOf("render_preview" to "event: message\ndata: $rendered\n\n"),
        mutableListOf(),
        written,
        preview = "p",
      )
      .run()
    assertContentEquals(png, written["doc.png"])
  }
}
