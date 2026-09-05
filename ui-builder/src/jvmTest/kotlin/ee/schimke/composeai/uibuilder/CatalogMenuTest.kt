package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The insert panel's tree: the catalog's own families, the components on each, and the variants
 * under a component.
 *
 * These are assertions about a **menu**, which is why they are here rather than left to a render:
 * the shape of the tree is a pure function of the catalog and two sets of open rows, and a
 * screenshot can tell you that a row is missing but not why. The rendering — chevrons, counts,
 * indentation — is what the editor chrome previews are for.
 */
class CatalogMenuTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val state = reducer.initial(document, selectedNodeId = "discover-grid")

  @Test
  fun `every component sits on a family the catalog ordered`() {
    val ordered = catalog.groupOrder.toSet()
    val used = catalog.components.map { it.group }.toSet()

    assertEquals(emptySet<String?>(), used - ordered, "families missing from groupOrder")
    assertEquals(emptyList(), catalog.components.filter { it.group == null }.map { it.componentId })
  }

  @Test
  fun `families lead their components, in the order the catalog declares`() {
    val rows = reducer.catalogRows(state)
    val groups = rows.filterIsInstance<EditorCatalogRow.Group>().map { it.name }

    // Not alphabetical, and not the id order the fixture is written in: `groupOrder` is what says
    // a scaffold is the first thing you reach for and a gradient is not.
    assertEquals(catalog.groupOrder.filter { it in groups }, groups)
    // The count on a heading is the components under it, so heading and rows cannot disagree.
    rows.filterIsInstance<EditorCatalogRow.Group>().forEach { group ->
      val under =
        rows
          .dropWhile { it !== group }
          .drop(1)
          .takeWhile { it !is EditorCatalogRow.Group }
          .filterIsInstance<EditorCatalogRow.Component>()
      assertEquals(group.count, under.size, group.name)
      under.forEach { assertEquals(group.name, it.item.group) }
    }
  }

  @Test
  fun `collapsing a family hides its components and keeps its count`() {
    val collapsed =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup("Selection")).let(::rows)
    val selection =
      collapsed.filterIsInstance<EditorCatalogRow.Group>().first { it.name == "Selection" }

    assertTrue(selection.count > 1)
    assertEquals(0, componentsUnder(collapsed, "Selection").size)
    // Every other family is untouched — collapsing is one heading's business.
    assertTrue(componentsUnder(collapsed, "Layout").isNotEmpty())
    assertEquals(
      componentsUnder(rows(state), "Selection").size,
      selection.count,
      "a collapsed heading still says how much is behind it",
    )
  }

  @Test
  fun `a card's variants are its catalog values, default first, and only when expanded`() {
    val card = component(rows(state), "m3/card")

    assertEquals(listOf("filled", "elevated", "outlined"), card.item.variants.map { it.value })
    assertEquals(listOf("Filled", "Elevated", "Outlined"), card.item.variants.map { it.label })
    assertEquals(listOf(true, false, false), card.item.variants.map { it.default })
    assertEquals(0, variantRows(rows(state), "m3/card").size)

    val expanded = reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogComponent("m3/card"))

    assertEquals(
      listOf("filled", "elevated", "outlined"),
      variantRows(rows(expanded), "m3/card").map { it.variant.value },
    )
    // One component opens, not the shelf: a Dialog sitting beside the Card stays shut.
    assertEquals(0, variantRows(rows(expanded), "m3/text-field").size)
  }

  @Test
  fun `a component the catalog names no variant property for offers none`() {
    // `m3/icon.iconKey` is an enum of forty-seven icons, and forty-seven rows under Icon is what
    // declaring the variant property rather than guessing at it avoids.
    assertEquals(emptyList(), component(rows(state), "m3/icon").item.variants)
    assertEquals(emptyList(), component(rows(state), "layout/row").item.variants)
  }

  @Test
  fun `adding a variant inserts the component already carrying it`() {
    val outlined = component(rows(state), "m3/card").item.variants.single { it.value == "outlined" }
    val target = assertNotNull(reducer.dropTarget(state, "m3/card"))

    val inserted =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/card", target, outlined))

    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    val node = inserted.document.nodes.getValue(assertNotNull(inserted.selectedNodeId))
    assertEquals("m3/card", node.componentId)
    assertEquals(
      "outlined",
      node.properties.getValue("variant").jsonObject["value"]?.jsonPrimitive?.content,
    )
    // The variant is a fact about the card, not a replacement for what a card arrives holding:
    // the starter content is still there.
    assertTrue(node.slots.getValue("content").isNotEmpty(), "outlined card arrived empty")
  }

  @Test
  fun `adding without a variant leaves the choice to the catalog, and the default row records it`() {
    val target = assertNotNull(reducer.dropTarget(state, "m3/card"))

    val plain = reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/card", target))
    val explicit =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent(
          "m3/card",
          target,
          component(rows(state), "m3/card").item.variants.single { it.default },
        ),
      )

    // `variant` is optional, so the row's Add writes no opinion at all and the renderer applies the
    // catalog's first allowed value — which is exactly what this insert did before variants
    // existed, and what the Default variant row then writes down. Picking the default explicitly
    // is a choice, and a choice a collaborator can read is worth the one property.
    assertEquals(
      null,
      inserted(plain).properties["variant"],
      "a plain Add started writing a property it never wrote",
    )
    assertEquals(
      "filled",
      inserted(explicit).properties.getValue("variant").jsonObject["value"]?.jsonPrimitive?.content,
    )
    assertEquals(
      catalog.componentsById.getValue("m3/card").variantValues.first(),
      component(rows(state), "m3/card").item.variants.single { it.default }.value,
    )
  }

  private fun inserted(state: UiBuilderEditorState) =
    state.document.nodes.getValue(assertNotNull(state.selectedNodeId))

  @Test
  fun `a search opens what it matched and does not spend the collapse doing it`() {
    val collapsed = reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup("Containment"))
    val searching = reducer.reduce(collapsed, UiBuilderEditorEvent.SearchCatalog("outlined"))

    // "outlined" is a variant label and nothing's display name, so the only way a Card is here is
    // by its variants — and the row is open, since a match hidden behind a twisty reads as no
    // match.
    val matched = searching.let(::rows).filterIsInstance<EditorCatalogRow.Component>()
    assertTrue(matched.any { it.item.componentId == "m3/card" }, matched.toString())
    assertTrue(variantRows(rows(searching), "m3/card").isNotEmpty())

    val cleared = reducer.reduce(searching, UiBuilderEditorEvent.SearchCatalog(""))

    assertEquals(0, componentsUnder(rows(cleared), "Containment").size, "the collapse came back")
  }

  private fun rows(state: UiBuilderEditorState) = reducer.catalogRows(state)

  private fun componentsUnder(rows: List<EditorCatalogRow>, group: String) =
    rows
      .dropWhile { !(it is EditorCatalogRow.Group && it.name == group) }
      .drop(1)
      .takeWhile { it !is EditorCatalogRow.Group }
      .filterIsInstance<EditorCatalogRow.Component>()

  private fun component(rows: List<EditorCatalogRow>, componentId: String) =
    rows.filterIsInstance<EditorCatalogRow.Component>().single {
      it.item.componentId == componentId
    }

  private fun variantRows(rows: List<EditorCatalogRow>, componentId: String) =
    rows.filterIsInstance<EditorCatalogRow.Variant>().filter {
      it.variant.componentId == componentId
    }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
