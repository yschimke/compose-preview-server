package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A design that holds several top-level items keeps them in a board, and a board is a node.
 *
 * The rules under test are stated once in `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`: the
 * root list stays at one, the arrangement is an ordinary `layout/column` the inspector can edit, and
 * the wrap and the item that motivated it are one command so they undo together.
 */
class BoardInsertTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `an add beside wraps the existing root in a board and stays at one root`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val originalRoot = initial.document.roots.single()

    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    assertIs<CommandOutcome.Accepted>(added.lastOutcome, added.lastOutcome.toString())
    // The whole point: a design of several items is one tree, not several roots. Every consumer
    // downstream — both exporters, the projection, the export gate — sees what it always saw.
    val boardId = added.document.roots.single()
    val board = added.document.nodes.getValue(boardId)
    assertEquals(UiBuilderBoard.COMPONENT_ID, board.componentId)
    assertEquals(
      listOf(originalRoot, added.selectedNodeId),
      board.slots.getValue(UiBuilderBoard.SLOT),
    )
  }

  /**
   * The arrangement is in the document, which is the difference between a board node and a synthetic
   * one: "make the gap smaller" is an ordinary property edit rather than a constant in four
   * consumers.
   */
  @Test
  fun `the board carries its spacing and alignment as editable properties`() {
    val added =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    val board = added.document.nodes.getValue(added.document.roots.single())
    assertEquals(
      UiBuilderBoard.SPACING_DP.toString(),
      board.properties["verticalSpacingDp"]?.jsonObject?.get("value")?.toString()?.trim('"'),
    )
    assertEquals(
      "center",
      board.properties["horizontalAlignment"]?.jsonObject?.get("value")?.toString()?.trim('"'),
    )
  }

  @Test
  fun `a second add beside appends to the board rather than nesting another one`() {
    val first =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )
    val boardId = first.document.roots.single()

    val second = reducer.reduce(first, UiBuilderEditorEvent.InsertComponentBeside("m3/text"))

    assertIs<CommandOutcome.Accepted>(second.lastOutcome, second.lastOutcome.toString())
    assertEquals(boardId, second.document.roots.single())
    assertEquals(3, second.document.nodes.getValue(boardId).slots.getValue(UiBuilderBoard.SLOT).size)
    assertEquals(3, second.document.boardItemCount)
  }

  /**
   * One command, so one undo. A board that appeared because of an Add disappears when that Add is
   * taken back — the alternative is a wrapper left behind by an edit the author reversed.
   */
  @Test
  fun `undo takes back the wrap and the item together`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    val undone = reducer.reduce(added, UiBuilderEditorEvent.Undo)

    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, undone.lastOutcome.toString())
    assertEquals(initial.document.roots, undone.document.roots)
    assertEquals(initial.document.nodes.keys, undone.document.nodes.keys)
  }

  /**
   * A board with one item is a screen that happens to sit in a column. Claiming otherwise would put
   * "this is a board of several items" on a design showing one.
   */
  @Test
  fun `one item is not yet a board`() {
    val added =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    assertNotNull(added.document.boardRootId)
    assertTrue(added.document.isBoard)
    assertEquals(2, added.document.boardItemCount)
    // The design it came from: one root, no board, nothing claimed.
    assertNull(document.boardRootId)
    assertEquals(0, document.boardItemCount)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
