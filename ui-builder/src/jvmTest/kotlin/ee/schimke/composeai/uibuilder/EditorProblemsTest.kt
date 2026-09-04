package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

  private fun problems(document: UiBuilderDocument) =
    reducer.problems(reducer.initial(document, selectedNodeId = document.roots.first()))

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
    // The point of reading `validateDocumentForExport` rather than writing a second set of rules:
    // a panel with its own opinion would eventually disagree with the thing that actually refuses,
    // and the disagreement would surface at export time — the moment this exists to avoid.
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
      validateDocumentForExport(broken, catalog).map { it.code to it.message },
      problems(broken).map { it.code to it.message },
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
