package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `design validate`: argv in, one `ui_builder_validate` call out, problems to stderr. */
class DesignValidateCommandTest {

  private val noEnvironment: (String) -> String? = { null }

  private fun run(vararg args: String) =
    assertIs<DesignCommand.Parsed.Run>(DesignCommand.parse(args.toList(), noEnvironment)).options

  private fun invalid(vararg args: String) =
    assertIs<DesignCommand.Parsed.Invalid>(DesignCommand.parse(args.toList(), noEnvironment))
      .message

  @Test
  fun `validate is a verb that reads, writes to stdout, and takes a document or operations`() {
    assertTrue(DesignCommand.VALIDATE in DesignCommand.VERBS)
    assertTrue(DesignCommand.usage().contains("validate <designId>"))

    val stored = run("validate", "w")
    assertEquals(DesignCommand.STDOUT, stored.destination)
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_READ), stored.capabilities)
    assertEquals(AgentGrantScope.PREVIEW, stored.scope)

    assertEquals("ops.json", run("validate", "w", "--operations", "ops.json").operations)
    // Sent to the server to be checked, never stored: no --local needed.
    assertEquals("doc.json", run("validate", "--document", "doc.json").document)
  }

  @Test
  fun `validate refuses the combinations that mean nothing`() {
    assertTrue(invalid("validate").contains("a design id is required"))
    assertTrue(
      invalid("validate", "--document", "d.json", "--operations", "o.json")
        .contains("stored design")
    )
    assertTrue(invalid("get", "w", "--operations", "o.json").contains("validate only"))
    assertTrue(invalid("validate", "w", "--revision", "3").contains("--revision"))
    assertTrue(invalid("validate", "w", "--local").contains("--local"))
    assertTrue(invalid("validate", "w", "--format", "png").contains("--format"))
  }

  private val logged = mutableListOf<String>()
  private val written = mutableMapOf<String, ByteArray>()

  private fun runner(
    options: DesignCommand.Options,
    reply: String,
    files: Map<String, String> = emptyMap(),
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
      read = { files.getValue(it) },
    )

  @Test
  fun `a valid check exits zero and prints the reply`() {
    val seen = mutableListOf<Pair<String, JsonObject>>()
    val code =
      runner(
          run("validate", "w", "--operations", "ops.json"),
          """{"schema":"compose-preview/ui-builder-validation/v1","valid":true,"designId":"w","revision":3,"problems":[]}""",
          files = mapOf("ops.json" to """{"operations":[{"type":"deleteNode","nodeId":"a"}]}"""),
          seen = seen,
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_OK, code)
    val (tool, arguments) = seen.single()
    assertEquals(ServeUiBuilderMcp.VALIDATE, tool)
    assertEquals("w", arguments["designId"]!!.jsonPrimitive.content)
    // An apply-style object is unwrapped to its array, so the file an agent already has works.
    assertEquals(1, arguments["operations"]!!.jsonArray.size)
    val printed = Json.parseToJsonElement(written.getValue(DesignCommand.STDOUT).decodeToString())
    assertEquals("true", printed.jsonObject["valid"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an invalid check prints each problem to stderr and exits non-zero`() {
    val code =
      runner(
          run("validate", "--document", "doc.json"),
          """{"valid":false,"problems":[
            {"severity":"error","source":"export","code":"NO_COMPONENT_RECORD","message":"no component `m3/x` in this catalog","nodeId":"n1"},
            {"severity":"warning","source":"mutations","code":"revisionMismatch","message":"stale"}]}""",
          files = mapOf("doc.json" to """{"id":"w"}"""),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertTrue(
      logged.any { it.contains("error: export/NO_COMPONENT_RECORD") && it.contains("(node n1)") },
      "$logged",
    )
    assertTrue(logged.any { it.contains("1 error above") && it.contains("Nothing was saved") })
  }

  @Test
  fun `a document that is not JSON never reaches the server`() {
    val seen = mutableListOf<Pair<String, JsonObject>>()
    val code =
      runner(
          run("validate", "--document", "doc.json"),
          """{"valid":true,"problems":[]}""",
          files = mapOf("doc.json" to "not json"),
          seen = seen,
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertEquals(emptyList(), seen)
    assertTrue(logged.single().contains("is not JSON"), "$logged")
  }
}
