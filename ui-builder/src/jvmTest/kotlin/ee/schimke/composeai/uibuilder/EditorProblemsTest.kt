package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The editor validates each write, so a rejected edit never lands. Nothing checked the document as
 * a whole, and the first anyone knew of a broken one was the export refusing.
 */
class EditorProblemsTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun problems(document: UiBuilderDocument) = reducer.problems(document)

  @Test
  fun `the checked-in fixture has nothing to report`() {
    assertEquals(emptyList(), problems(document))
  }

  @Test
  fun `a node missing a property its component requires is reported against that node`() {
    val placeholder = document.nodes.getValue("search-placeholder")
    val broken =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              placeholder.id to
                placeholder.copy(properties = JsonObject(placeholder.properties - "text"))
            )
      )

    val problem = assertNotNull(problems(broken).firstOrNull())
    assertEquals("MISSING_REQUIRED_PROPERTY", problem.code)
    assertEquals("search-placeholder", problem.nodeId)
    assertEquals("m3/text", problem.componentId)
  }

  @Test
  fun `a node no root can reach is reported, which no rejected write would have caught`() {
    // Nothing wrote to the icon. Its parent stopped naming it, which is the shape a botched delete
    // leaves behind and exactly what a per-edit check cannot see.
    val searchInput = document.nodes.getValue("search-input")
    val orphaned =
      document.copy(
        nodes =
          document.nodes +
            mapOf(searchInput.id to searchInput.copy(slots = searchInput.slots - "trailingIcon"))
      )

    val problem = assertNotNull(problems(orphaned).firstOrNull { it.code == "UNREACHABLE_NODE" })
    assertEquals("search-account-icon", problem.nodeId)
  }

  @Test
  fun `a problem naming a node that does not exist is not offered as something to select`() {
    // The row is clickable when it has a node. A dangling root has an id and no node behind it, and
    // selecting it would put the inspector on nothing.
    val ghostRoot = document.copy(roots = document.roots + "ghost")

    val problem = assertNotNull(problems(ghostRoot).firstOrNull { it.code == "UNKNOWN_ROOT" })
    assertNull(problem.nodeId)
    assertTrue(problem.message.contains("ghost"))
  }

  @Test
  fun `the panel reports exactly what the export gate refuses`() {
    // The point of reading the exporter rather than writing a second set of rules: a panel with
    // its own opinion would eventually disagree with the thing that actually refuses, and the
    // disagreement would surface at export time — the moment this exists to avoid.
    val placeholder = document.nodes.getValue("search-placeholder")
    val broken =
      document.copy(
        roots = document.roots + "ghost",
        nodes =
          document.nodes +
            mapOf(
              placeholder.id to
                placeholder.copy(properties = JsonObject(placeholder.properties - "text"))
            ),
      )

    assertEquals(
      CapabilityComposeCodeExporter.diagnose(broken, catalog)
        .filter { it.severity == ComposeExportSeverity.ERROR }
        .map { it.code to it.message },
      problems(broken).map { it.code to it.message },
    )
  }

  @Test
  fun `something only the Compose projection refuses is reported too`() {
    // A structurally valid document the Compose export still will not take:
    // `remote-compose/document`
    // is a declared container with a Kotlin symbol and no typed call emitter. Reading only
    // `validateDocumentForExport` made the panel say nothing was blocking an export right up until
    // the export refused — which is the one moment this panel exists to come before.
    val player =
      UiBuilderNode(
        id = "player",
        componentId = "remote-compose/document",
        properties =
          JsonObject(
            mapOf(
              "documentBase64" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("AAAA"))
                )
            )
          ),
      )
    val onlyThePlayer = document.copy(roots = listOf(player.id), nodes = mapOf(player.id to player))

    assertEquals(
      emptyList(),
      validateDocumentForExport(onlyThePlayer, catalog),
      "the document itself is valid",
    )

    val reported = problems(onlyThePlayer)
    assertTrue(
      reported.any { it.code == "UNSUPPORTED_CODE_COMPONENT" && it.nodeId == player.id },
      "$reported",
    )
  }

  @Test
  fun `a warning the export proceeds through is not listed as a blocker`() {
    // The panel's one claim is what stops the build. A diagnostic the export notes and carries on
    // past would make that claim untrue in the other direction. The checked-in fixture holds a
    // carousel, whose compatibility note is exactly such a warning.
    val diagnostics = CapabilityComposeCodeExporter.diagnose(document, catalog)
    assertTrue(
      diagnostics.any {
        it.code == "CAROUSEL_COMPATIBILITY_HELPER" && it.severity == ComposeExportSeverity.WARNING
      },
      "the fixture should still raise the warning this test is about: $diagnostics",
    )

    assertEquals(emptyList(), problems(document))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
