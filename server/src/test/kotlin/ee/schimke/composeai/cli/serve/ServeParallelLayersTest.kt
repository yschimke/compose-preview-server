package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-catalog derived-layer diff (issue #4838): what two catalogs of one design system each
 * resolved for the same cell, and what the surface says when only one of them drew a node.
 */
class ServeParallelLayersTest {

  private fun typography(
    text: String?,
    label: String = "16.0sp/24.0sp",
    detail: Map<String, String> = emptyMap(),
  ) =
    DesignAnnotation(
      kind = AnnotationKind.TYPOGRAPHY,
      bounds = AnnotationBounds(0, 0, 10, 10),
      label = label,
      role = text,
      detail = detail,
    )

  private fun layer(diff: ServeParallelLayers.Diff, kind: String) =
    diff.layers.single { it.kind == kind }

  @Test
  fun `a family that fell back on one runtime is the finding a pixel diff hides`() {
    // The live case in the issue: the CMP/Wasm lane resolves no `DataFont` for `google:Inter` and
    // draws the same 16sp text in a different face. At 227dp that is antialiasing; here it is a
    // row.
    val diff =
      ServeParallelLayers.diff(
        here =
          listOf(
            typography(
              "Continue",
              detail =
                mapOf("fontFamily" to "Inter", "fontSize" to "16.0sp", "token" to "bodyLarge"),
            )
          ),
        there =
          listOf(
            typography(
              "Continue",
              detail =
                mapOf("fontFamily" to "Roboto", "fontSize" to "16.0sp", "token" to "bodyLarge"),
            )
          ),
      )
    val rows = layer(diff, AnnotationKind.TYPOGRAPHY).rows
    assertEquals(1, rows.size)
    assertEquals(ServeParallelLayers.Presence.BOTH, rows.single().presence)
    val family = rows.single().fields.single { it.name == "fontFamily" }
    assertTrue(family.differs, "the two runtimes resolved different families")
    assertEquals("Inter" to "Roboto", family.here to family.there)
    // The size they agree on is still carried — a reader comparing two rows needs both halves —
    // but it is not counted as a finding.
    assertTrue(rows.single().fields.single { it.name == "fontSize" }.agrees)
    assertEquals(1, diff.differing)
    assertEquals(0, diff.unpaired)
  }

  @Test
  fun `rows pair on the text they draw, however the two trees are ordered`() {
    val diff =
      ServeParallelLayers.diff(
        here =
          listOf(
            typography("Title", detail = mapOf("fontSize" to "20.0sp")),
            typography("Continue", detail = mapOf("fontSize" to "16.0sp")),
          ),
        there =
          listOf(
            typography("Continue", detail = mapOf("fontSize" to "14.0sp")),
            typography("Title", detail = mapOf("fontSize" to "20.0sp")),
          ),
      )
    val rows = layer(diff, AnnotationKind.TYPOGRAPHY).rows
    assertEquals(listOf("Title", "Continue"), rows.map { it.subject })
    assertTrue(rows.first().fields.single { it.name == "fontSize" }.agrees)
    assertTrue(rows.last().fields.single { it.name == "fontSize" }.differs)
  }

  @Test
  fun `two catalogs that spell their strings differently pair by reading order`() {
    // A Wear sheet says "Next" where the Remote one says "Continue". Equal leftover counts say the
    // two renders have the same shape, which is the only evidence position-pairing needs.
    val diff =
      ServeParallelLayers.diff(
        here = listOf(typography("Continue", detail = mapOf("fontWeight" to "500"))),
        there = listOf(typography("Next", detail = mapOf("fontWeight" to "400"))),
      )
    val row = layer(diff, AnnotationKind.TYPOGRAPHY).rows.single()
    assertEquals(ServeParallelLayers.Presence.BOTH, row.presence)
    assertTrue(row.fields.single { it.name == "fontWeight" }.differs)
  }

  @Test
  fun `unequal leftovers stay one-sided rather than being paired by position`() {
    // Two texts against one: pairing by position would state a correspondence nobody established.
    val diff =
      ServeParallelLayers.diff(
        here = listOf(typography("Continue"), typography("Cancel")),
        there = listOf(typography("Next")),
      )
    val rows = layer(diff, AnnotationKind.TYPOGRAPHY).rows
    assertEquals(3, rows.size)
    assertEquals(
      listOf(
        ServeParallelLayers.Presence.ONLY_HERE,
        ServeParallelLayers.Presence.ONLY_HERE,
        ServeParallelLayers.Presence.ONLY_THERE,
      ),
      rows.map { it.presence },
    )
    assertEquals(3, diff.unpaired)
    assertTrue(rows.all { it.notable }, "a one-sided row is a finding in its own right")
  }

  @Test
  fun `a node only the sibling draws is stated, not dropped`() {
    val diff =
      ServeParallelLayers.diff(
        here = listOf(typography("Continue")),
        there = listOf(typography("Continue"), typography("Beta")),
      )
    val rows = layer(diff, AnnotationKind.TYPOGRAPHY).rows
    assertEquals(listOf("Continue", "Beta"), rows.map { it.subject })
    assertEquals(ServeParallelLayers.Presence.ONLY_THERE, rows.last().presence)
    assertEquals(1, layer(diff, AnnotationKind.TYPOGRAPHY).onlyThere)
  }

  @Test
  fun `a kind neither side publishes contributes no layer`() {
    val diff =
      ServeParallelLayers.diff(
        here = listOf(typography("Continue")),
        there = listOf(typography("Continue")),
      )
    assertEquals(listOf(AnnotationKind.TYPOGRAPHY), diff.layers.map { it.kind })
  }

  @Test
  fun `layout boxes diff on the same footing as type`() {
    val box = { label: String, detail: Map<String, String> ->
      DesignAnnotation(
        kind = AnnotationKind.LAYOUT,
        bounds = AnnotationBounds(0, 0, 10, 10),
        label = label,
        role = "Button",
        detail = detail,
      )
    }
    val diff =
      ServeParallelLayers.diff(
        here = listOf(box("pad 16dp · gap 8dp", mapOf("padding" to "16dp", "gap" to "8dp"))),
        there = listOf(box("pad 12dp · gap 8dp", mapOf("padding" to "12dp", "gap" to "8dp"))),
      )
    val row = layer(diff, AnnotationKind.LAYOUT).rows.single()
    assertTrue(row.fields.single { it.name == "padding" }.differs)
    assertTrue(row.fields.single { it.name == "gap" }.agrees)
    // Preferred order puts the fields a layout argument is had in first.
    assertEquals(listOf("padding", "gap"), row.fields.map { it.name })
  }

  @Test
  fun `identical rows are not folded into one`() {
    // Two labels drawing the same string at the same spec are two nodes, and a map keyed on the
    // annotation would have silently made them one.
    val diff =
      ServeParallelLayers.diff(
        here = listOf(typography(null), typography(null)),
        there = listOf(typography(null), typography(null)),
      )
    assertEquals(2, layer(diff, AnnotationKind.TYPOGRAPHY).rows.size)
    assertEquals(2, layer(diff, AnnotationKind.TYPOGRAPHY).paired)
  }

  @Test
  fun `a row with no role falls back to the token both sides resolved`() {
    val diff =
      ServeParallelLayers.diff(
        here =
          listOf(
            typography(null, detail = mapOf("token" to "titleMedium", "fontSize" to "16.0sp"))
          ),
        there =
          listOf(
            typography(null, detail = mapOf("token" to "titleMedium", "fontSize" to "18.0sp"))
          ),
      )
    val row = layer(diff, AnnotationKind.TYPOGRAPHY).rows.single()
    assertEquals("titleMedium", row.subject)
    assertTrue(row.fields.single { it.name == "fontSize" }.differs)
  }
}
