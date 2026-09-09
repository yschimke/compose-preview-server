package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canvas measures boxes, and today a box is still spelled like the node that describes it.
 *
 * `RenderNode` now carries a [UiBuilderInstancePath] rather than a bare id, because a loop over
 * data and an instance of a reusable component both draw one node more than once and a bare id
 * cannot say which copy was measured. Nothing in the format draws a second copy yet, so this pins
 * the property that makes the seam safe to land ahead of them: with no repetition, every path is
 * character-for-character the node id, so every bounds key, inspection entry and comment anchor is
 * the one that was there before.
 */
@OptIn(ExperimentalComposeUiApi::class)
class InstancePathBoundsTest {

  @Test
  fun `every measured box is reported under its authored node id`() {
    val document = tabRowSelectionPreviewDocument
    var snapshot: UiBuilderInspectionSnapshot? = null

    renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) {
      UiBuilderSurface(document = document, onInspectionSnapshot = { snapshot = it })
    }

    val measured = checkNotNull(snapshot) { "the canvas published no inspection snapshot" }
    val measuredIds = measured.nodes.filter { it.bounds != null }.map { it.nodeId }.sorted()

    assertEquals(document.nodes.keys.sorted(), measuredIds)
    assertTrue(measuredIds.none { it.contains('#') || it.contains('/') }, "$measuredIds")
  }

  /**
   * The same claim from the other end: what the renderer would key a box by, for a node with no
   * repeat above it, is the id itself.
   */
  @Test
  fun `a path built the way the renderer builds one is the node id`() {
    val row = UiBuilderInstancePath.of("root")
    val tab = row.child("tab-1")

    assertEquals("tab-1", tab.value)
    assertEquals("tab-1", tab.nodeId)
  }

  private companion object {
    const val FRAME_PX = 400
  }
}
