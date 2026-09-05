package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `wear-m3` authoring surface: what it admits, and what it says when asked for something else.
 *
 * The catalog is synthesised from the packaged M3 one rather than shipped as its own resource, the
 * way `remote-m3`'s widget scaffolds are. That is a statement about how much of it is real: two
 * components are Wear's and every other component in it is Material 3 borrowed for the canvas.
 */
class WearM3ScreenCatalogTest {
  private val executor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        )
    )

  private val wear =
    executor.listCatalogs().single {
      it.benchmark.catalogSystemId == CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
    }

  @Test
  fun `the wear catalog publishes the screen scaffold and the transforming lazy column`() {
    val ids = wear.components.map { it.componentId }

    assertTrue("wear-m3/screen-scaffold" in ids, ids.toString())
    assertTrue("wear-m3/transforming-lazy-column" in ids, ids.toString())
    assertEquals("wear-screen-scaffold-v1", wear.benchmark.catalogRevision)
  }

  /**
   * The scaffold carries the screen's furniture and NOT its size.
   *
   * The diameter is the document's frame — the Screen inspector's Wear OS presets — and a scaffold
   * property for it would be a second answer to the same question. This is the assertion that
   * notices if somebody adds one.
   */
  @Test
  fun `the screen scaffold takes no size property`() {
    val scaffold = wear.components.single { it.componentId == "wear-m3/screen-scaffold" }

    assertEquals(
      listOf("timeText", "scrollIndicator", "background"),
      scaffold.properties.map { it.name },
    )
    assertEquals(listOf("content", "edgeButton"), scaffold.slots.map { it.name })
    assertEquals("Scaffold", scaffold.role)
  }

  /**
   * Every component says, in its own capability note, that the canvas is not Wear Compose.
   *
   * `androidx.wear.compose:compose-material3` is an Android AAR and the canvas is Compose
   * Multiplatform for Wasm, so nothing in this catalog draws the real component. A note is how an
   * adapter states that rather than implying parity, and an unannotated component would be a claim
   * nobody made on purpose.
   */
  @Test
  fun `no component claims to draw the real Wear component`() {
    wear.components.forEach { component ->
      assertNotNull(component.wasm.notes, component.componentId)
      assertTrue(component.wasm.notes!!.isNotBlank(), component.componentId)
    }
  }

  /**
   * The rule this catalog exists under: **Material 3 and Wear Material 3 are not used together.**
   *
   * A Wear screen built from `m3/card` and `m3/text` claimed to hold mobile Material components,
   * and `WearScreenCodeExporter` then wrote them out as `TitleCard` and Wear's `Text` — the export
   * was right and the palette was lying. What may be borrowed from `m3-catalog` is foundation:
   * `Box`, `Column`, `Row` and `Image` are one declaration shared by both platforms, so borrowing
   * one claims nothing about Material at all.
   *
   * Asserted as a set rather than a predicate so that adding a borrow is a decision somebody writes
   * down here, not something that drifts in behind a convenient `m3/` id.
   */
  @Test
  fun `the wear catalog borrows foundation and no Material component`() {
    val borrowed = wear.components.map { it.componentId }.filterNot { it.startsWith("wear-m3/") }

    assertEquals(
      listOf(
        "layout/box",
        "layout/column",
        "layout/row",
        "asset/image",
        "remote-compose/document",
      ),
      borrowed,
    )
    assertTrue(borrowed.none { it.startsWith("m3/") }, borrowed.toString())
  }

  /** The content components a Wear screen is built from are Wear's own, named as Wear's. */
  @Test
  fun `the wear content ids are wear ids`() {
    val wearOwn = wear.components.map { it.componentId }.filter { it.startsWith("wear-m3/") }

    assertTrue("wear-m3/text" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/card" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/button" in wearOwn, wearOwn.toString())
    // Every one of them says what it draws and what it generates, which is what keeps a stand-in
    // from being mistaken for the real component.
    wearOwn.forEach { id ->
      val notes = wear.components.single { it.componentId == id }.wasm.notes.orEmpty()
      assertTrue(notes.isNotBlank(), id)
    }
  }

  /**
   * Wear's selection rows are rows: a label, an optional secondary label, and a state.
   *
   * The shape matters beyond this catalog. `StarterContent` in `:ui-builder` seeds these three by
   * slot name from a table that module cannot check against this catalog, so the names are pinned
   * here — and the reason they are rows at all is the reason they are not borrowed: a Wear
   * `CheckboxButton` is a full-width labelled row, where `m3/checkbox` is a 20dp square.
   */
  @Test
  fun `each wear selection row carries a label, a secondary label and its own state`() {
    listOf(
        "wear-m3/checkbox-button" to "checked",
        "wear-m3/switch-button" to "checked",
        "wear-m3/radio-button" to "selected",
      )
      .forEach { (componentId, stateProperty) ->
        val component = wear.components.single { it.componentId == componentId }

        assertEquals(
          listOf("label", "secondaryLabel"),
          component.slots.map { it.name },
          componentId,
        )
        assertEquals(1, component.slots.first().cardinality.min, componentId)
        assertEquals(0, component.slots.last().cardinality.min, componentId)
        assertTrue(component.properties.any { it.name == stateProperty }, componentId)
        assertTrue(component.properties.any { it.name == "enabled" }, componentId)
        // A list item, which is what puts it in the transforming lazy column.
        assertTrue("ListItem" in component.traits, componentId)
      }
  }

  /** An id with no packaged adapter is refused by name rather than served as something else. */
  @Test
  fun `a catalog with no adapter is refused`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("wear-m3-catalog"))
      }

    assertTrue("wear-m3-catalog" in failure.message.orEmpty(), failure.message.orEmpty())
    assertTrue("no packaged adapter" in failure.message.orEmpty(), failure.message.orEmpty())
  }
}
