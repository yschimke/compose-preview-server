package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

  @Test
  fun `a paste beside a leaf lands next to it, not at the end of its list`() {
    // `findDestination` falls back to the selection's own parent slot when the selection cannot
    // hold the clipboard itself. The paste still anchored to the slot's last child, so copying a
    // chip and pasting sent the copy past every sibling to the bottom of the row.
    val (rowId, rowSlot) =
      document.nodes.values.firstNotNullOf { node ->
        node.slots.entries.firstOrNull { "chip-crime" in it.value }?.let { node.id to it.key }
      }
    val siblingsBefore = document.nodes.getValue(rowId).slots.getValue(rowSlot)
    assertTrue(siblingsBefore.indexOf("chip-crime") < siblingsBefore.lastIndex, "$siblingsBefore")

    val copied =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "chip-crime"),
        UiBuilderEditorEvent.CopySelected,
      )
    val pasted = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    assertIs<CommandOutcome.Accepted>(pasted.lastOutcome)

    val siblings = pasted.document.nodes.getValue(rowId).slots.getValue(rowSlot)
    val copy = assertIs<String>(pasted.selectedNodeId)
    assertEquals(
      siblings.indexOf("chip-crime") + 1,
      siblings.indexOf(copy),
      "the copy belongs beside what was copied: $siblings",
    )
  }

  @Test
  fun `an authoritative document keeps the selection and the clipboard`() {
    // Every accepted edit and every remote delta rebuilds the editor from a new document. The
    // multi-selection and the clipboard used to be dropped there, so a collaborator typing in
    // another panel silently emptied your clipboard and collapsed a range you had just built.
    val copied = reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.CopySelected)
    assertEquals(3, copied.selection.size)
    val clipboard = assertIs<EditorClipboard>(copied.clipboard)

    val reconciled = reducer.reconciled(copied, copied.document.copy(revision = 99))

    assertEquals(copied.selection, reconciled.selection)
    assertEquals(clipboard, reconciled.clipboard)
    assertEquals("chip-news", reconciled.selectedNodeId, "the anchor is still the last entry")
  }

  @Test
  fun `a node the new document dropped leaves the selection rather than emptying it`() {
    val copied = reducer.reduce(outOfOrderSelection(), UiBuilderEditorEvent.CopySelected)
    val without =
      copied.document.let { document ->
        document.copy(revision = 100, nodes = document.nodes - "chip-news")
      }

    val reconciled = reducer.reconciled(copied, without)

    assertEquals(listOf("chip-comedy", "chip-crime"), reconciled.selection)
    assertEquals("chip-crime", reconciled.selectedNodeId)
  }

  @Test
  fun `a property two components spell differently is not offered across both`() {
    // `m3/button.style` allows filled / filledTonal / text / fab; `m3/text.style` allows the
    // typography scale. Sharing by name alone offered one `style` dropdown built from the anchor's
    // declaration, so every choice in it was rejected for the other node — `commitProperty`
    // validates per node and fails the whole batch, which is right, and left an editable-looking
    // field that could not be used.
    val button = reducer.initial(document, selectedNodeId = "toolbar-library")
    assertTrue(
      reducer.propertyFields(button).any { it.name == "style" },
      "a button on its own still edits its style",
    )

    val both = reducer.reduce(button, UiBuilderEditorEvent.ToggleNode("main-episode-title"))
    assertEquals(listOf("toolbar-library", "main-episode-title"), both.selection)

    assertTrue(
      reducer.propertyFields(both).none { it.name == "style" },
      reducer.propertyFields(both).joinToString { it.name },
    )
  }

  @Test
  fun `a property two components spell identically is still offered across both`() {
    val one = reducer.initial(document, selectedNodeId = "main-episode-title")
    val two = reducer.reduce(one, UiBuilderEditorEvent.ToggleNode("main-episode-summary"))

    val style = reducer.propertyFields(two).single { it.name == "style" }

    assertEquals(2, style.nodeCount)
    assertTrue(style.choices.contains("bodyMedium"), style.choices.toString())
  }

  private fun withState(name: String, declaration: JsonObject): UiBuilderDocument =
    document.copy(stateVariables = JsonObject(mapOf(name to declaration)))

  private fun textState(name: String) =
    withState(
      name,
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("text"),
          "valueType" to JsonPrimitive("string"),
          "nullable" to JsonPrimitive(false),
          "initialValue" to JsonPrimitive("a"),
          "persistence" to JsonPrimitive("preview"),
        )
      ),
    )

  private fun insertWith(
    documentWithState: UiBuilderDocument,
    action: EditorStateAction,
  ): UiBuilderEditorState {
    val start = reducer.initial(documentWithState, selectedNodeId = "main-episode-footer")
    val target = ParentSlot("main-episode-footer", "children")
    return reducer.reduce(
      start,
      UiBuilderEditorEvent.InsertComponentWithAction("m3/button", target, action),
    )
  }

  @Test
  fun `toggling something that is not a flag is refused at insert time`() {
    // The renderer coerces whatever it finds to a boolean string and carries on, so the preview
    // looks like it works; the exporter refuses and emits a TODO that throws on the first press.
    // A design should not be able to hold an action only one of its two consumers can perform.
    val refused = insertWith(textState("caption"), EditorStateAction.Toggle("caption"))

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("not a flag"), outcome.message)
  }

  @Test
  fun `clearing something that is not nullable is refused at insert time`() {
    val refused = insertWith(textState("caption"), EditorStateAction.SelectOrClear("caption", "x"))

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("not nullable"), outcome.message)
  }

  @Test
  fun `a value that is not of the declared type is refused rather than coerced`() {
    // The encoder's fallbacks are `false`, `0` and `0.0`, so this used to insert a control that
    // wrote a different value from the one asked for — authored-looking and wrong.
    val flag =
      withState(
        "expanded",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("bool"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(false),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
      )

    val refused = insertWith(flag, EditorStateAction.Set("expanded", "yes"))

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("not a boolean value"), outcome.message)
  }

  @Test
  fun `a comparison operand is typed against the variable it compares with`() {
    // The operand is compared against the variable in the exported Kotlin, so a string encoding
    // gave `expanded == "true"` against a `Boolean`. The fourth place in this reducer where a
    // declaration and the literal written against it had to be made to agree.
    val flag =
      withState(
        "expanded",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("bool"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(false),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
      )
    val bound =
      reducer.reduce(
        reducer.initial(flag, selectedNodeId = "chip-crime"),
        UiBuilderEditorEvent.BindPropertyToState("chip-crime", "selected", "expanded", "true"),
      )
    assertIs<CommandOutcome.Accepted>(bound.lastOutcome)

    val encoded = bound.document.nodes.getValue("chip-crime").properties["selected"] as JsonObject
    assertEquals(JsonPrimitive(true), encoded["value"], encoded.toString())
  }

  @Test
  fun `a comparison operand that is not of the declared type is refused`() {
    val flag =
      withState(
        "expanded",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("bool"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(false),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
      )

    val refused =
      reducer.reduce(
        reducer.initial(flag, selectedNodeId = "chip-crime"),
        UiBuilderEditorEvent.BindPropertyToState("chip-crime", "selected", "expanded", "yes"),
      )

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("not a boolean value"), outcome.message)
  }

  @Test
  fun `an integer past the exported Int range is refused`() {
    // The exporter declares an integer variable as `Int`, so a value only a Long can hold would be
    // authored here and emitted as a literal the generated Kotlin cannot take.
    val number =
      withState(
        "count",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("int"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(0),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
      )

    val refused = insertWith(number, EditorStateAction.Set("count", "2147483648"))

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("not a integer value"), outcome.message)
  }

  @Test
  fun `toggling a flag is accepted`() {
    val flag =
      withState(
        "expanded",
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("value"),
            "valueType" to JsonPrimitive("bool"),
            "nullable" to JsonPrimitive(false),
            "initialValue" to JsonPrimitive(false),
            "persistence" to JsonPrimitive("preview"),
          )
        ),
      )

    val inserted = insertWith(flag, EditorStateAction.Toggle("expanded"))

    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
