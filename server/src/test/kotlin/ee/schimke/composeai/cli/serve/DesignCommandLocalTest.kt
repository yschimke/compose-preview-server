package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `--local` as a pure function of argv, pinned the way the rest of the surface is — no socket, no
 * bundle, no compiler.
 *
 * Every refusal here is one somebody would otherwise discover after a bundle extraction and a
 * Kotlin compile, which is the expensive way to be told a flag was in the wrong place.
 */
class DesignCommandLocalTest {

  private val noEnvironment: (String) -> String? = { null }

  private fun run(vararg args: String) =
    assertIs<DesignCommand.Parsed.Run>(DesignCommand.parse(args.toList(), noEnvironment)).options

  private fun invalid(vararg args: String) =
    assertIs<DesignCommand.Parsed.Invalid>(DesignCommand.parse(args.toList(), noEnvironment))
      .message

  @Test
  fun `a local render names its bundle, its assets and its records`() {
    val options =
      run(
        "render",
        "spotify-wear-widget",
        "--local",
        "--catalog",
        "wear.bundle",
        "--assets",
        "./assets",
        "--components",
        "m3-catalog=m3.json",
        "--components",
        "wear-m3=wear.json",
      )

    assertTrue(options.local)
    assertEquals("wear.bundle", options.catalog)
    assertEquals("./assets", options.assets)
    assertEquals(mapOf("m3-catalog" to "m3.json", "wear-m3" to "wear.json"), options.components)
    assertEquals("spotify-wear-widget.png", options.destination)
  }

  /** The whole point of the mode: a document a broken host handed over, replayed here. */
  @Test
  fun `a document stands in for the design id, and names the output beside it`() {
    val options = run("render", "--document", "captures/broken.json", "--local", "--catalog", "b")
    assertEquals("captures/broken.json", options.document)
    assertEquals("", options.designId)
    assertEquals("broken.png", options.destination)
    assertEquals(
      "replay.png",
      run("render", "--document", "d.json", "--local", "--catalog", "b", "-o", "replay.png")
        .destination,
    )
  }

  @Test
  fun `a local export writes Kotlin and needs no bundle`() {
    val options = run("export", "w", "--local")
    assertTrue(options.local)
    assertEquals(ExportFormatV1.COMPOSE, options.format)
    assertEquals(DesignCommand.STDOUT, options.destination)
  }

  @Test
  fun `a local render must say what to compile against`() {
    assertTrue(invalid("render", "w", "--local").contains("--catalog"))
  }

  /** `list` and `get` read a server's state; this process holds no copy of it. */
  @Test
  fun `local applies to render and export only`() {
    assertTrue(invalid("get", "w", "--local").contains("--local applies to"))
    assertTrue(invalid("list", "--local").contains("--local applies to"))
  }

  @Test
  fun `a document is a local input and a revision is a server's`() {
    assertTrue(invalid("render", "--document", "d.json").contains("Add --local"))
    assertTrue(
      invalid("render", "--document", "d.json", "--local", "--catalog", "b", "--revision", "3")
        .contains("--revision")
    )
    assertTrue(
      invalid("render", "w", "--document", "d.json", "--local", "--catalog", "b")
        .contains("second one")
    )
  }

  @Test
  fun `the local flags are refused where they would be ignored`() {
    assertTrue(invalid("render", "w", "--catalog", "b").contains("Add --local"))
    assertTrue(invalid("export", "w", "--components", "a=b.json").contains("Add --local"))
  }

  @Test
  fun `a local render draws, so it cannot answer with SVG`() {
    assertTrue(
      invalid("render", "w", "--local", "--catalog", "b", "--format", "svg").contains("only png")
    )
  }

  @Test
  fun `--components takes a catalog and a file`() {
    assertTrue(
      invalid("export", "w", "--local", "--components", "m3.json").contains("--components")
    )
  }

  /**
   * A local run asks a server to read a design and nothing else, so it asks an approver for a read.
   */
  @Test
  fun `a local render asks for a read, not for an export`() {
    val local = run("render", "w", "--local", "--catalog", "b")
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_READ), local.capabilities)
    assertEquals(AgentGrantScope.PREVIEW, local.scope)

    val remote = run("render", "w")
    assertEquals(listOf(AgentGrantCapability.UI_BUILDER_EXPORT), remote.capabilities)
    assertEquals(AgentGrantScope.LIVE, remote.scope)
  }

  @Test
  fun `the ordinary surface is unchanged when nothing local is asked for`() {
    val options = run("render", "w")
    assertFalse(options.local)
    assertEquals(null, options.catalog)
    assertEquals(emptyMap(), options.components)
  }

  @Test
  fun `--help says what --local needs`() {
    val usage = DesignCommand.usage()
    assertTrue(usage.contains("--local"))
    assertTrue(usage.contains("--catalog"))
    assertTrue(usage.contains("--document"))
  }
}
