package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Wrapping and copying are operations whose whole result is an order, and both were reading the
 * order nodes were *clicked* in rather than the order they sit in.
 */
class EditorWrapOrderTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** The three chips, selected last-then-first so click order and tree order disagree. */
  private fun outOfOrderSelection(): UiBuilderEditorState {
    val start = reducer.initial(document, selectedNodeId = "chip-comedy")
    val extended = reducer.reduce(start, UiBuilderEditorEvent.ToggleNode("chip-crime"))
    return reducer.reduce(extended, UiBuilderEditorEvent.ToggleNode("chip-news")).also {
      assertEquals(listOf("chip-comedy", "chip-crime", "chip-news"), it.selection)
    }
  }

  @Test
  fun `wrapping keeps the order the nodes were in, not the order they were clicked`() {
    val wrapped =
      reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.WrapSelection("layout/row"))
    assertIs<CommandOutcome.Accepted>(wrapped.lastOutcome)

    val container = assertIs<String>(wrapped.selectedNodeId)
    assertEquals(
      listOf("chip-crime", "chip-news", "chip-comedy"),
      wrapped.document.nodes.getValue(container).slots.values.flatten(),
    )
  }

  @Test
  fun `a wrapper does not keep the placeholder it was born with`() {
    // A container with a required slot arrives holding a default child. Wrapping one text in a
    // Card produced a Card containing `New text` *and* the text, and unwrap could not undo it.
    val single = reducer.initial(document, selectedNodeId = "main-episode-title")
    val candidate =
      reducer.wrapCandidates(single).firstOrNull { it.componentId == "m3/card" }
        ?: reducer.wrapCandidates(single).first()

    val wrapped = reducer.reduce(single, UiBuilderEditorEvent.WrapSelection(candidate.componentId))
    assertIs<CommandOutcome.Accepted>(wrapped.lastOutcome)

    val container = assertIs<String>(wrapped.selectedNodeId)
    assertEquals(
      listOf("main-episode-title"),
      wrapped.document.nodes.getValue(container).slots.values.flatten(),
      "the wrapper kept a placeholder beside the wrapped node",
    )
  }

  @Test
  fun `a wrap and an unwrap put the slot back exactly as it was`() {
    // Only true once the placeholder is gone: an extra child is one unwrap cannot remove.
    val before = document.nodes.getValue("category-row").slots.getValue("items")
    val wrapped =
      reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.WrapSelection("layout/row"))
    assertIs<CommandOutcome.Accepted>(wrapped.lastOutcome)

    val unwrapped = reducer.reduce(wrapped, UiBuilderEditorEvent.UnwrapSelection)
    assertIs<CommandOutcome.Accepted>(unwrapped.lastOutcome)

    assertEquals(before, unwrapped.document.nodes.getValue("category-row").slots.getValue("items"))
  }

  @Test
  fun `the clipboard holds what was copied in the order it sits in`() {
    val copied = reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.CopySelected)

    assertEquals(
      listOf("chip-crime", "chip-news", "chip-comedy"),
      assertIs<EditorClipboard>(copied.clipboard).rootNodeIds,
    )
  }

  @Test
  fun `pasting reproduces that order`() {
    val copied = reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.CopySelected)
    val pasted = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    assertIs<CommandOutcome.Accepted>(pasted.lastOutcome)

    val labels =
      pasted.selection.map { id -> pasted.document.nodes.getValue(id).eventBindings.toString() }
    assertEquals(3, pasted.selection.size)
    assertTrue(labels.first().contains("Crime"), labels.toString())
    assertTrue(labels.last().contains("Comedy"), labels.toString())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
