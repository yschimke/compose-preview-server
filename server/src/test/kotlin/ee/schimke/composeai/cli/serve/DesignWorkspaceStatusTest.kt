package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesignWorkspaceStatusTest {
  @Test
  fun `aggregates comments and detects only changed server temporary copies`() {
    val root =
      workspace(
        """
      {"schema":"compose-ui-builder-design-index/v1","designs":[
        {"id":"clean","file":"clean.json"},
        {"id":"changed","file":"changed.json"},
        {"id":"repo","file":"repo.json"}
      ]}
      """,
        "clean.json" to serverDocument("clean", "alpha"),
        "changed.json" to serverDocument("changed", "local"),
        "repo.json" to
          """{"schema":"doc","home":{"kind":"repo","path":"designs/repo.uid"},"value":"local"}""",
      )
    val options = options(root.toString())
    val result =
      DesignWorkspaceStatus(options) { id, _ ->
          response(
            if (id == "clean") serverDocument("clean", "alpha")
            else serverDocument("changed", "remote"),
            comments = if (id == "clean") 2 else 3,
          )
        }
        .inspect()

    assertEquals("attention", result.verdict)
    assertEquals(3, result.workspaceDesigns)
    assertEquals(2, result.serverLinked)
    assertEquals(1, result.unsavedTemporaryCopies)
    assertEquals(5, result.unacknowledgedComments)
    assertEquals(0, result.unavailable)
    assertEquals(
      "2",
      result.designs.first { it.designId == "clean" }.unacknowledgedComments.toString(),
    )
    assertTrue(result.summary().contains("5 unacknowledged design comments"))
    assertTrue(result.summary().contains("1 unsaved temporary copy"))
  }

  @Test
  fun `tracked home cannot redirect ambient credentials to another origin`() {
    val root =
      workspace(
        index("unsafe"),
        "unsafe.json" to
          """{"home":{"kind":"server","url":"https://attacker.example","designId":"unsafe"}}""",
      )
    var called = false
    val result =
      DesignWorkspaceStatus(options(root.toString())) { _, _ ->
          called = true
          error("must not call")
        }
        .inspect()

    assertFalse(called)
    assertEquals(DesignWorkspaceStatus.UNTRUSTED_HOME, result.designs.single().code)
    val json = result.json().toString()
    assertFalse(json.contains("attacker.example"), json)
    assertFalse(json.contains(root.toString()), json)
  }

  @Test
  fun `authorization failures are redacted and never start a grant flow`() {
    val root = workspace(index("private"), "private.json" to serverDocument("private", "local"))
    val result =
      DesignWorkspaceStatus(options(root.toString())) { _, _ ->
          throw DesignAuthorizationRequired(
            "https://server.example/request?token=secret",
            "credential secret refused",
          )
        }
        .inspect()

    assertEquals(DesignWorkspaceStatus.AUTHORIZATION_REQUIRED, result.designs.single().code)
    assertFalse(result.json().toString().contains("secret"))
  }

  @Test
  fun `one deadline bounds all remote reads`() {
    val root =
      workspace(
        """{"schema":"compose-ui-builder-design-index/v1","designs":[{"id":"one"},{"id":"two"}]}""",
        "one.json" to serverDocument("one", "one"),
        "two.json" to serverDocument("two", "two"),
      )
    var now = 0L
    val budgets = mutableListOf<Duration>()
    val result =
      DesignWorkspaceStatus(
          options(root.toString(), timeout = 3),
          remote = { id, timeout ->
            budgets += timeout
            now += Duration.ofSeconds(3).toNanos()
            response(serverDocument(id, id), 0)
          },
          clockNanos = { now },
        )
        .inspect()

    assertEquals(1, budgets.size)
    assertEquals(Duration.ofSeconds(3), budgets.single())
    assertEquals(DesignWorkspaceStatus.TIMED_OUT, result.designs.last().code)
  }

  @Test
  fun `malformed oversized and linked workspace inputs stay bounded`() {
    val malformed = workspace("{}")
    assertEquals(
      DesignWorkspaceStatus.MALFORMED_INDEX,
      DesignWorkspaceStatus(options(malformed.toString())) { _, _ -> error("unused") }
        .inspect()
        .designs
        .single()
        .code,
    )

    val root = workspace(index("linked"))
    val outside = Files.createTempFile("outside-design", ".json")
    outside.writeText(serverDocument("linked", "value"))
    Files.createSymbolicLink(root.resolve("ui-builder/designs/linked.json"), outside)
    assertEquals(
      DesignWorkspaceStatus.MALFORMED_DOCUMENT,
      DesignWorkspaceStatus(options(root.toString())) { _, _ -> error("unused") }
        .inspect()
        .designs
        .single()
        .code,
    )

    val entries = (0..100).joinToString(",") { "{\"id\":\"d$it\"}" }
    val tooMany =
      workspace("""{"schema":"compose-ui-builder-design-index/v1","designs":[$entries]}""")
    assertEquals(
      DesignWorkspaceStatus.TOO_MANY_DESIGNS,
      DesignWorkspaceStatus(options(tooMany.toString())) { _, _ -> error("unused") }
        .inspect()
        .designs
        .single()
        .code,
    )
  }

  @Test
  fun `missing index is a clean silent inventory`() {
    val root = createTempDirectory("design-status-empty")
    val result =
      DesignWorkspaceStatus(options(root.toString())) { _, _ -> error("unused") }.inspect()
    assertEquals("ok", result.verdict)
    assertEquals("", result.summary())
    assertEquals(0, result.workspaceDesigns)
  }

  @Test
  fun `entry keeps a clean summary silent and emits a versioned JSON envelope`() {
    val root = createTempDirectory("design-status-entry")
    val summaryOut = ByteArrayOutputStream()
    val summaryExit =
      DesignCommandEntry.run(
        listOf("status", "--workspace", root.toString(), "--summary"),
        env = { null },
        out = PrintStream(summaryOut),
      )
    assertEquals(DesignCommandRunner.EXIT_OK, summaryExit)
    assertEquals("", summaryOut.toString())

    val jsonOut = ByteArrayOutputStream()
    val jsonExit =
      DesignCommandEntry.run(
        listOf("status", "--workspace", root.toString(), "--json"),
        env = { if (it == DesignCommand.TOKEN_ENV) "must-not-appear" else null },
        out = PrintStream(jsonOut),
      )
    assertEquals(DesignCommandRunner.EXIT_OK, jsonExit)
    assertTrue(jsonOut.toString().contains(DesignWorkspaceStatus.SCHEMA))
    assertFalse(jsonOut.toString().contains("must-not-appear"))
  }

  private fun options(workspace: String, timeout: Long = 5) =
    DesignCommand.Options(
      verb = DesignCommand.STATUS,
      designId = "",
      out = null,
      format = null,
      revision = null,
      limit = 50,
      server = SERVER,
      authorize = false,
      timeoutSeconds = timeout,
      workspace = workspace,
    )

  private fun workspace(index: String, vararg documents: Pair<String, String>) =
    createTempDirectory("design-status").also { root ->
      val designs = root.resolve("ui-builder/designs")
      Files.createDirectories(designs)
      designs.resolve("index.json").writeText(index.trimIndent())
      documents.forEach { (name, body) -> designs.resolve(name).writeText(body) }
    }

  private fun index(id: String) =
    """{"schema":"compose-ui-builder-design-index/v1","designs":[{"id":"$id"}]}"""

  private fun serverDocument(id: String, value: String) =
    """{"schema":"doc","home":{"kind":"server","url":"$SERVER","designId":"$id"},"value":"$value"}"""

  private fun response(document: String, comments: Int): JsonObject = buildJsonObject {
    put(
      "snapshot",
      buildJsonObject {
        put(
          "state",
          buildJsonObject {
            put("document", kotlinx.serialization.json.Json.parseToJsonElement(document))
          },
        )
      },
    )
    put("unacknowledgedComments", comments)
  }

  private companion object {
    const val SERVER = "https://preview.example"
  }
}
