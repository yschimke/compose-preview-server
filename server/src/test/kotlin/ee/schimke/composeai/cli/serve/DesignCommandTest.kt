package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole `design` surface as a pure function of argv and the environment — no socket, no disk.
 *
 * The point of pinning it here is the same as [ServerCommandsTest]'s: a command anyone can discover
 * is a command whose defaults must not drift silently, and every one of these defaults is a promise
 * to a shell script somebody has already written.
 */
class DesignCommandTest {

  private val noEnvironment: (String) -> String? = { null }

  private fun run(vararg args: String, env: (String) -> String? = noEnvironment) =
    assertIs<DesignCommand.Parsed.Run>(DesignCommand.parse(args.toList(), env)).options

  private fun invalid(vararg args: String) =
    assertIs<DesignCommand.Parsed.Invalid>(DesignCommand.parse(args.toList(), noEnvironment))
      .message

  @Test
  fun `no verb prints usage rather than failing`() {
    assertEquals(DesignCommand.Parsed.Help, DesignCommand.parse(emptyList(), noEnvironment))
    assertEquals(DesignCommand.Parsed.Help, DesignCommand.parse(listOf("--help"), noEnvironment))
    assertEquals(
      DesignCommand.Parsed.Help,
      DesignCommand.parse(listOf("render", "-h"), noEnvironment),
    )
  }

  @Test
  fun `render defaults to a PNG file named after the design`() {
    val options = run("render", "spotify-wear-widget")
    assertEquals(DesignCommand.RENDER, options.verb)
    assertEquals("spotify-wear-widget", options.designId)
    assertEquals(ExportFormatV1.PNG, options.format)
    assertEquals("spotify-wear-widget.png", options.destination)
    assertNull(options.revision)
  }

  /** Bytes are not sprayed at a terminal, but text is: a pipe is the obvious use for both. */
  @Test
  fun `text verbs default to stdout and pictures default to a file`() {
    assertEquals(DesignCommand.STDOUT, run("get", "widget").destination)
    assertEquals(DesignCommand.STDOUT, run("list").destination)
    assertEquals(DesignCommand.STDOUT, run("export", "widget").destination)
    assertEquals("widget.svg", run("render", "widget", "--format", "svg").destination)
  }

  @Test
  fun `an explicit out wins over every default`() {
    assertEquals("cover.png", run("render", "w", "--out", "cover.png").destination)
    assertEquals("Widget.kt", run("export", "w", "-o", "Widget.kt").destination)
    assertEquals(DesignCommand.STDOUT, run("render", "w", "--out", "-").destination)
  }

  @Test
  fun `a revision pins and an absent one means the current committed revision`() {
    assertEquals(7L, run("render", "w", "--revision", "7").revision)
    assertNull(run("render", "w").revision)
    assertTrue(invalid("render", "w", "--revision", "later").contains("non-negative"))
  }

  /**
   * The two capability checks the server makes are separate on purpose, so the command asks for the
   * one it needs rather than for everything it might ever need.
   */
  @Test
  fun `each verb asks for the least it needs`() {
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_READ), run("list").capabilities)
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_READ), run("get", "w").capabilities)
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_EXPORT), run("export", "w").capabilities)
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_EXPORT), run("render", "w").capabilities)
    // A made-to-order render is not a published preview.
    assertEquals(AgentGrantScope.LIVE, run("render", "w").scope)
    assertEquals(AgentGrantScope.PREVIEW, run("get", "w").scope)
  }

  @Test
  fun `the server defaults locally and is overridable by flag and by environment`() {
    assertEquals(DesignCommand.defaultServer(), run("list").server)
    assertEquals(
      "https://preview.coo.ee",
      run("list", env = { if (it == DesignCommand.SERVER_ENV) "https://preview.coo.ee" else null })
        .server,
    )
    assertEquals(
      "https://elsewhere.example",
      run(
          "list",
          "--server",
          "https://elsewhere.example",
          env = { if (it == DesignCommand.SERVER_ENV) "https://preview.coo.ee" else null },
        )
        .server,
    )
  }

  /**
   * Two names for one grant existed before this command — the header's own and the one
   * `design-sync.mjs` reads — and reconciling them beat inventing a third. The header's name wins;
   * the older one still works.
   */
  @Test
  fun `the token comes from either environment name, header spelling first`() {
    assertNull(DesignCommand.token(noEnvironment))
    assertEquals(
      "legacy",
      DesignCommand.token { if (it == DesignCommand.LEGACY_TOKEN_ENV) "legacy" else null },
    )
    assertEquals(
      "primary",
      DesignCommand.token {
        when (it) {
          DesignCommand.TOKEN_ENV -> "primary"
          DesignCommand.LEGACY_TOKEN_ENV -> "legacy"
          else -> null
        }
      },
    )
  }

  /** A `--token` flag would put the credential in a shell history; the refusal says why. */
  @Test
  fun `there is no token flag and the message explains itself`() {
    val message = invalid("render", "w", "--token", "abc")
    assertTrue(message.contains("shell history"), message)
    assertTrue(message.contains(DesignCommand.TOKEN_ENV), message)
  }

  @Test
  fun `a verb that needs a design id says so`() {
    assertTrue(invalid("render").contains("design id is required"))
    assertTrue(invalid("get").contains("design id is required"))
    assertTrue(invalid("list", "widget").contains("takes no design id"))
  }

  @Test
  fun `formats are checked against the verb`() {
    assertEquals(ExportFormatV1.COMPOSE, run("export", "w").format)
    assertTrue(invalid("render", "w", "--format", "compose").contains("--format must be one of"))
    assertTrue(invalid("export", "w", "--format", "png").contains("--format must be one of"))
    assertTrue(invalid("get", "w", "--format", "png").contains("render and export only"))
  }

  @Test
  fun `unknown verbs and flags are refused by name`() {
    assertTrue(invalid("apply", "w").contains("unknown verb 'apply'"))
    assertTrue(invalid("render", "w", "--pretty").contains("unknown option '--pretty'"))
    assertTrue(invalid("render", "w", "--out").contains("needs a value"))
  }

  @Test
  fun `authorising is the default and can be turned off for CI`() {
    assertTrue(run("render", "w").authorize)
    assertTrue(!run("render", "w", "--no-authorize").authorize)
  }

  /** `design` is reachable from the binary's front door, and it is not a serving command. */
  @Test
  fun `the binary routes design to a client invocation`() {
    assertEquals(
      ServerCommands.Invocation.Client(ServerCommands.DESIGN, listOf("render", "widget")),
      ServerCommands.parse(listOf("design", "render", "widget")),
    )
    assertEquals(
      ServerCommands.Invocation.Help(ServerCommands.DESIGN),
      ServerCommands.parse(listOf("help", "design")),
    )
    assertTrue(ServerCommands.commandListing().contains("design"))
  }
}
