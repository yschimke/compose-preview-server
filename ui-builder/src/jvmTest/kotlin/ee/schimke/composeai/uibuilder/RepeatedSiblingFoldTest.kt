package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A run of identical siblings is generated as one `repeat`.
 *
 * The document has no loop, so a twelve-cell contribution row is twelve nodes — and the export
 * printed twelve identical `Surface` calls, which is a faithful screen nobody can read. The fold is
 * spelling, not semantics: the same calls, in the same order, in the same parent scope.
 *
 * What each test here pins is a boundary of that claim rather than the happy path alone: a run of
 * two is still written out, a cell that differs breaks the run at itself, and a subtree claiming an
 * identity keeps every one of its copies.
 */
class RepeatedSiblingFoldTest {
  private val reference by lazy {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")) as JsonObject
      )
      .document
  }
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  }

  @Test
  fun `twelve identical cells become one repeat`() {
    val source = exportSource(contributionRow(cells = 12))

    assertEquals(1, Regex("repeat\\(12\\) \\{").findAll(source).count())
    assertEquals(1, emittedCellBodies(source))
    assertTrue(source.contains("// repeated:12 nodes:cell-0,cell-1,"), source)
  }

  @Test
  fun `a run of two is still written out, because two calls read fine`() {
    val source = exportSource(contributionRow(cells = 2))

    assertFalse(source.contains("repeat("), source)
    assertEquals(2, emittedCellBodies(source))
  }

  @Test
  fun `a cell that differs breaks the run at itself and neither side is lost`() {
    val document = contributionRow(cells = 8, distinctAt = 3)
    val source = exportSource(document)

    assertEquals(1, Regex("repeat\\(3\\) \\{").findAll(source).count())
    assertEquals(1, Regex("repeat\\(4\\) \\{").findAll(source).count())
    // Three folded, the odd one out, then four folded: three emitted bodies for eight cells.
    assertEquals(3, emittedCellBodies(source))
    assertTrue(source.contains("// node:cell-3 "), source)
  }

  @Test
  fun `a stableKey is an identity claim, so its cells are printed the long way`() {
    val source = exportSource(contributionRow(cells = 12, stableKeys = true))

    assertFalse(source.contains("repeat("), source)
    assertEquals(12, emittedCellBodies(source))
    assertEquals(12, Regex("key\\(\"cell-").findAll(source).count())
  }

  @Test
  fun `folding does not change which nodes were exported`() {
    val folded = exportSource(contributionRow(cells = 12))

    // Every node still appears in the generated source's own node index, folded or not: the
    // comment above a run names the nodes it stands for, so nothing becomes unfindable.
    (0 until 12).forEach { index ->
      assertTrue(folded.contains("cell-$index"), "cell-$index is not named in the generated source")
    }
  }

  /**
   * `repeat` and the `it` it binds are names like any other.
   *
   * `exportedStateIdentifier` leaves both alone, so a design declaring state called either gets a
   * local of that name in the generated function — and inside a folded run the lambda's implicit
   * `Int` would shadow the first while the second would capture the call. Neither is a refusal: the
   * cells are printed the long way, exactly as before the fold existed.
   */
  @Test
  fun `state named it or repeat turns the fold off rather than changing what a cell reads`() {
    listOf("it", "repeat").forEach { name ->
      val document = contributionRow(cells = 12)
      val source =
        exportSource(
          document.copy(
            stateVariables =
              JsonObject(
                mapOf(
                  name to
                    JsonObject(
                      mapOf(
                        "valueType" to JsonPrimitive("string"),
                        "initialValue" to JsonPrimitive("x"),
                      )
                    )
                )
              )
          )
        )

      assertFalse(source.contains("repeat(12)"), source)
      assertEquals(12, emittedCellBodies(source))
    }
  }

  /**
   * How many cell bodies the source actually holds.
   *
   * Counted from the located node comment each emitted node carries rather than from the call — the
   * generated file's compatibility helpers contain `Surface(` of their own, and a count that
   * included those would move whenever a helper did.
   */
  private fun emittedCellBodies(source: String): Int =
    Regex("// node:cell-\\d+ ").findAll(source).count()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun exportSource(document: UiBuilderDocument): String {
    val result = CapabilityComposeCodeExporter.export(document, catalog)
    assertTrue(result.successful, result.diagnostics.joinToString { it.message })
    return assertNotNull(result.source)
  }

  /** A Column holding a Row of [cells] colour swatches — the shape that provoked this. */
  private fun contributionRow(
    cells: Int,
    distinctAt: Int? = null,
    stableKeys: Boolean = false,
  ): UiBuilderDocument {
    val cellIds = (0 until cells).map { "cell-$it" }
    val nodes =
      buildMap<String, UiBuilderNode> {
        put(
          "root",
          UiBuilderNode(
            id = "root",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("row")),
          ),
        )
        put(
          "row",
          UiBuilderNode(
            id = "row",
            componentId = "layout/row",
            slots = mapOf("children" to cellIds),
          ),
        )
        cellIds.forEachIndexed { index, id ->
          val colour = if (index == distinctAt) "#39D353" else "#EBEDF0"
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = if (stableKeys) "m3/card" else "m3/surface",
              properties =
                JsonObject(
                  buildMap {
                    put("containerColor", JsonObject(mapOf("value" to JsonPrimitive(colour))))
                    if (stableKeys) {
                      put("stableKey", JsonObject(mapOf("value" to JsonPrimitive(id))))
                    }
                  }
                ),
              slots = mapOf("content" to listOf("$id-label")),
            ),
          )
          put(
            "$id-label",
            UiBuilderNode(
              id = "$id-label",
              componentId = "m3/text",
              properties =
                JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(""))))),
            ),
          )
        }
      }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "contribution-graph",
      title = "Contribution graph",
      revision = 1,
      // The pin and the environment come from the frozen benchmark document: they are what the
      // export gate checks, and restating them here would be a second copy to keep in step.
      catalogPin = reference.catalogPin,
      environment = reference.environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes = nodes,
    )
  }
}
