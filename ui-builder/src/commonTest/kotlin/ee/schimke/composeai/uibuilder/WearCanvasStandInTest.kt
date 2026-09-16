package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What is left of the Material 3 borrow, and what has stopped being one.
 *
 * ## What this test used to assert, and why it changed
 *
 * It used to be called "only the three renamed borrows are drawn as Material 3", and it existed to
 * stop a fourth being added. The reasoning was that the canvas is Compose Multiplatform for Wasm,
 * `androidx.wear.compose:compose-material3` is an Android AAR it cannot link, and so a Wear
 * component with no Material 3 counterpart could only ever be hand-assembled from Material pieces
 * at sizes read off a screenshot — an impression of upstream with nothing in this build to check it
 * against. It named `CheckboxButton`, `SwitchButton`, `Slider`, `DatePicker` and friends as the
 * shapes a future change would reach for, and refused all of them.
 *
 * The premise about the AAR is still true and still checkable — that artifact publishes two
 * variants, both `releaseVariantRelease*Publication`, and one `.aar`. What was wrong was treating
 * it as the only way to obtain Wear Compose. `ee.schimke.wearcmp:*` is the same library's source
 * compiled for Compose Multiplatform with `jvm` and `wasmJs` variants, which are exactly this
 * module's targets, and `WearCanvasComponents` now draws with it.
 *
 * So the rule inverted: a Wear component should be drawn by Wear Compose, and a Material 3 borrow
 * is the exception to be retired. `SwitchButton` and `Slider` — two the old test listed as
 * permanently impossible — are drawn for real today.
 *
 * ## What it asserts now
 *
 * The borrow table is exactly the three ids not yet moved, asserted as a whole map so that
 * *growing* it is an edit somebody makes on purpose. Shrinking it is the expected direction of
 * travel and only requires updating this list.
 */
class WearCanvasStandInTest {
  @Test
  fun `the borrow table is down to the three ids not yet moved`() {
    val wearIds =
      listOf(
        "wear-m3/text",
        "wear-m3/card",
        "wear-m3/button",
        "wear-m3/list-header",
        "wear-m3/screen-scaffold",
        "wear-m3/transforming-lazy-column",
        // Drawn by Wear Compose in `WearCanvasComponents`, so they must NOT appear in the table.
        "wear-m3/list-sub-header",
        "wear-m3/switch-button",
        "wear-m3/slider",
        // Not drawn at all yet. Named here so that if one is ever added, it is added as a real
        // component rather than by extending this mapping.
        "wear-m3/checkbox-button",
        "wear-m3/radio-button",
        "wear-m3/stepper",
        "wear-m3/date-picker",
        "wear-m3/time-picker",
        "wear-m3/alert-dialog",
      )

    val standIns = wearIds.associateWith { it.wearScreenStandIn() }.filterNot { it.key == it.value }

    assertEquals(
      mapOf(
        "wear-m3/text" to "m3/text",
        "wear-m3/card" to "m3/card",
        "wear-m3/button" to "m3/button",
      ),
      standIns,
    )
  }

  /** A mobile id is untouched: the mapping is one direction, for Wear ids only. */
  @Test
  fun `a mobile id maps to itself`() {
    assertEquals("m3/card", "m3/card".wearScreenStandIn())
    assertEquals("layout/column", "layout/column".wearScreenStandIn())
  }
}
