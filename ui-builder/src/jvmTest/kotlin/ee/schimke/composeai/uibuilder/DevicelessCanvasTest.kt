package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The deviceless canvas end to end, from the editor's switch to the Kotlin it exports.
 *
 * The mode is not stored anywhere and deliberately so — the wire environment's field set is
 * published from another repository and closed — so *the shape of the document is the mode*, and
 * these are the assertions that keep the four consumers agreeing about what that shape means:
 * `isDevicelessCanvas` reads it, `withCanvasRoot` resolves it, the reducer produces it, and the
 * exporter writes the same column the surface draws.
 */
class DevicelessCanvasTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `one item is a screen and two are a canvas`() {
    assertFalse(document.isDevicelessCanvas)
    assertEquals(document, document.withCanvasRoot())

    val canvas =
      reducer.reduce(canvasMode(), UiBuilderEditorEvent.InsertComponentOnCanvas("m3/card"))

    assertTrue(canvas.lastOutcome is CommandOutcome.Accepted, canvas.lastOutcome.toString())
    assertEquals(2, canvas.document.roots.size)
    assertTrue(canvas.document.isDevicelessCanvas)
    // The design's own first root is still first: an item added to the canvas goes beside what is
    // there, never in front of it.
    assertEquals(document.roots.single(), canvas.document.roots.first())
  }

  @Test
  fun `the canvas resolves to one spaced column holding every item`() {
    val canvas =
      reducer.reduce(canvasMode(), UiBuilderEditorEvent.InsertComponentOnCanvas("m3/card")).document
    val resolved = canvas.withCanvasRoot()

    val root = resolved.nodes.getValue(resolved.roots.single())
    assertEquals("layout/column", root.componentId)
    assertEquals(canvas.roots, root.slots.getValue("children"))
    // Every item is still reachable, and the column is the only node the resolution added.
    assertEquals(canvas.nodes.keys + root.id, resolved.nodes.keys)
  }

  /**
   * A design that already holds a node named `deviceless-canvas` does not lose it to the synthetic
   * one. The name is one a person could reasonably have typed, and colliding would replace a real
   * node in the document the exporter then writes.
   */
  @Test
  fun `the synthetic column takes another name when the design already uses it`() {
    val taken = UiBuilderNode(DevicelessCanvas.CANVAS_NODE_ID, "m3/card")
    val canvas =
      document.copy(
        roots = document.roots + taken.id,
        nodes = document.nodes + (taken.id to taken),
      )

    val resolved = canvas.withCanvasRoot()

    assertEquals("${DevicelessCanvas.CANVAS_NODE_ID}-2", resolved.roots.single())
    assertEquals("m3/card", resolved.nodes.getValue(DevicelessCanvas.CANVAS_NODE_ID).componentId)
  }

  @Test
  fun `a canvas passes the export gate that used to refuse a second root`() {
    val canvas =
      reducer.reduce(canvasMode(), UiBuilderEditorEvent.InsertComponentOnCanvas("m3/card")).document

    assertEquals(emptyList(), validateDocumentForExport(canvas, catalog).map { it.code })
  }

  /** The switch is a tool mode: it changes what Add does and touches no revision. */
  @Test
  fun `the add-to-canvas switch is editor state rather than a document edit`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    assertFalse(initial.addToCanvas)

    val toggled = reducer.reduce(initial, UiBuilderEditorEvent.ToggleAddToCanvas)

    assertTrue(toggled.addToCanvas)
    assertEquals(initial.document, toggled.document)
    assertFalse(reducer.reduce(toggled, UiBuilderEditorEvent.ToggleAddToCanvas).addToCanvas)
  }

  /**
   * An item goes on the canvas whether or not the selection could have held it — that is the whole
   * difference between the two inserts. `m3/card` has a home in this design's grid; the point is
   * that asking for the canvas does not consult it.
   */
  @Test
  fun `a canvas insert ignores the selection that an ordinary insert would fill`() {
    val state = canvasMode()
    assertNotNull(reducer.dropTarget(state, "m3/card"))

    val canvas = reducer.reduce(state, UiBuilderEditorEvent.InsertComponentOnCanvas("m3/card"))

    val added = assertNotNull(canvas.selectedNodeId)
    assertEquals(added, canvas.document.roots.last())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun canvasMode(): UiBuilderEditorState =
    reducer.reduce(
      reducer.initial(document, selectedNodeId = "discover-grid"),
      UiBuilderEditorEvent.ToggleAddToCanvas,
    )
}
