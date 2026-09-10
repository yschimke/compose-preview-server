package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ComponentSourceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reporting half of "referenced, not copied".
 *
 * A design holds the body of every component it uses, which is what lets it draw and export without
 * asking anything — and exactly what makes it a copy unless something notices when the original
 * moves. These tests are about that noticing: the digest recorded at import, compared against what
 * the library computes now, and the four answers that comparison can have.
 */
class ServeUiBuilderComponentDriftTest {
  private val dir = createTempDirectory("drift").toFile()
  private val components =
    File(dir, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
  private val local =
    ServeUiBuilderDesignLibrary.Coordinate(
      system = "local",
      source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
    )

  @AfterTest fun tearDown() = dir.deleteRecursively().let {}

  @Test
  fun `a component whose library has not moved is unchanged`() {
    publish(label = "Cell")
    val digest = currentDigest()

    val findings = drift().check(mapOf("cell" to imported(digest)), listOf(local))

    assertEquals(
      listOf(ServeUiBuilderComponentDrift.State.UNCHANGED),
      findings.map { it.state },
    )
    assertNull(findings.single().currentDigest, "nothing to show when nothing changed")
  }

  /** The case the whole mechanism exists for. */
  @Test
  fun `a component whose library moved is reported as drifted with the new digest`() {
    publish(label = "Cell")
    val atImport = currentDigest()
    publish(label = "Cell, reworded")
    val now = currentDigest()

    val finding = drift().check(mapOf("cell" to imported(atImport)), listOf(local)).single()

    assertEquals(ServeUiBuilderComponentDrift.State.DRIFTED, finding.state)
    assertEquals(now, finding.currentDigest, "the evidence for the claim travels with it")
    assertEquals(atImport, finding.source.digest, "and what it was compared against")
  }

  /**
   * Reformatting the published file is not drift; editing what it draws is.
   *
   * The digest is taken over the component and its body with keys sorted at every depth, so this is
   * really a test that the report inherits that property rather than one about the report itself —
   * which is the point: a team that runs a formatter over its design files should not be told every
   * design has drifted.
   */
  @Test
  fun `reformatting the published file is not drift`() {
    publish(label = "Cell")
    val atImport = currentDigest()
    publish(label = "Cell", reordered = true)

    val finding = drift().check(mapOf("cell" to imported(atImport)), listOf(local)).single()

    assertEquals(ServeUiBuilderComponentDrift.State.UNCHANGED, finding.state)
  }

  @Test
  fun `a symbol the project no longer publishes is withdrawn`() {
    publish(label = "Cell")
    val atImport = currentDigest()
    File(components, "index.json")
      .writeText("""{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}","components":[]}""")

    assertEquals(
      ServeUiBuilderComponentDrift.State.WITHDRAWN,
      drift().check(mapOf("cell" to imported(atImport)), listOf(local)).single().state,
    )
  }

  /**
   * Still named by the project, but the library refuses it.
   *
   * Deliberately a different answer from [WITHDRAWN]: "the project removed this" and "the project's
   * copy is broken today" call for different actions, and a branch that is briefly unreachable must
   * not read as a deletion.
   */
  @Test
  fun `a symbol the library refuses is unusable rather than withdrawn`() {
    publish(label = "Cell")
    val atImport = currentDigest()
    File(components, "cell.json").writeText("{ not a document")

    assertEquals(
      ServeUiBuilderComponentDrift.State.UNUSABLE,
      drift().check(mapOf("cell" to imported(atImport)), listOf(local)).single().state,
    )
  }

  @Test
  fun `a system this host does not serve is unusable, not withdrawn`() {
    publish(label = "Cell")

    assertEquals(
      ServeUiBuilderComponentDrift.State.UNUSABLE,
      drift()
        .check(mapOf("cell" to imported("sha256:whatever", system = "elsewhere")), listOf(local))
        .single()
        .state,
    )
  }

  @Test
  fun `a component authored in this design is not checked and not reported`() {
    publish(label = "Cell")

    val findings =
      drift()
        .check(
          mapOf(
            "own" to DesignComponentV1(name = "Own", root = "own"),
            "cell" to imported(currentDigest()),
          ),
          listOf(local),
        )

    assertEquals(listOf("cell"), findings.map { it.componentKey })
  }

  @Test
  fun `findings come back in key order so two reads of one design agree`() {
    publish(label = "Cell")
    val digest = currentDigest()
    publish(id = "banner", label = "Banner", keepExisting = true)

    val findings =
      drift()
        .check(
          mapOf(
            "zebra" to imported(digest),
            "cell" to imported(digest),
            "banner" to imported(digest, componentId = "banner"),
          ),
          listOf(local),
        )

    assertEquals(listOf("banner", "cell", "zebra"), findings.map { it.componentKey })
  }

  private fun drift() =
    ServeUiBuilderComponentDrift(ServeUiBuilderComponentLibrary(fetch = { _, _ -> null }))

  private fun currentDigest(id: String = "cell"): String {
    val library = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
    val entry = library.index(local).first { it.componentId == id }
    return checkNotNull(library.symbol(local, entry)) { "the fixture must be readable" }.digest
  }

  private fun imported(
    digest: String,
    system: String = "local",
    componentId: String = "cell",
  ) =
    DesignComponentV1(
      name = "Cell",
      root = "cell",
      source = ComponentSourceV1(system = system, componentId = componentId, digest = digest),
    )

  /** Publish one symbol, replacing the index unless [keepExisting]. */
  private fun publish(
    id: String = "cell",
    label: String,
    reordered: Boolean = false,
    keepExisting: Boolean = false,
  ) {
    val ids = if (keepExisting) listOf("cell", id).distinct() else listOf(id)
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}","components":[${
          ids.joinToString(",") { """{"id":"$it","title":"$it"}""" }
        }]}"""
      )
    val body =
      if (reordered)
        """"$id": {"eventBindings": {}, "slots": {}, "properties": {"text": {"type": "string", "value": "$label"}}, "modifiers": [], "componentId": "m3/text", "id": "$id"}"""
      else
        """"$id": {"id": "$id", "componentId": "m3/text", "properties": {"text": {"type": "string", "value": "$label"}}, "modifiers": [], "slots": {}, "eventBindings": {}}"""
    File(components, "$id.json")
      .writeText(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "$id", "title": "$label", "revision": 0,
          "catalogPin": {
            "systemId": "m3-catalog", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
          },
          "environment": {
            "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "dark",
            "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
            "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
            "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
          },
          "stateVariables": {}, "roots": ["$id"],
          "nodes": {$body},
          "components": {"$id": {"name": "$label", "root": "$id"}}
        }
        """
          .trimIndent()
      )
  }
}
