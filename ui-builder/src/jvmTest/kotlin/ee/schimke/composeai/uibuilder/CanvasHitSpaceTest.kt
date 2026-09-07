package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One space for everything that asks where something is on the canvas.
 *
 * The renderer reports every node's box as `boundsInRoot` — the window's space, with the frame's
 * offset in the workspace and the scale it is painted at already inside the numbers — and that is
 * the space the reducer hit-tests a drop in. Three callers around it were each answering in a
 * different one: the catalog drop divided a window point back into the frame's own pixels, a
 * reference piece was stated in the design's, and another person's selection was drawn from window
 * boxes inside the frame. Each of them is right only on a canvas at 1:1 pinned to the window's
 * corner, which is what a test at the default zoom never leaves.
 *
 * So these cases put the frame somewhere — offset in its pane, at a zoom, at a density that is not
 * the host's — and ask each of them where something is.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CanvasHitSpaceTest {

  /**
   * A drop lands in the window's space, which is where the boxes it is compared against are.
   *
   * Asked of the reducer rather than of a rectangle, because that is what a drop asks: the slot it
   * names has to be one the pointer is actually over. The second half is the regression — the same
   * point put through the conversion this used to do names a different slot, so the canvas being
   * offset and zoomed is enough to fail it.
   */
  @Test
  fun `a drop over a slot names a slot under the pointer`() {
    val drawn = drawCanvas(jetcasterDocument)
    val over =
      checkNotNull(drawn.slots.firstOrNull { it.parentNodeId == GRID_NODE && it.bounds != null }) {
        "the canvas measured no slot on $GRID_NODE"
      }
    val bounds = checkNotNull(over.bounds)
    val centre = Offset(bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f)

    assertTrue(
      drawn.canvasBounds.contains(centre),
      "expected the slot's centre $centre inside the frame ${drawn.canvasBounds}",
    )
    val hit = checkNotNull(drawn.hitTest(centre)) { "nothing was under $centre" }
    assertTrue(
      drawn.boundsOf(hit)?.contains(centre) == true,
      "expected $hit to be under $centre, its box is ${drawn.boundsOf(hit)}",
    )
    val asTheDropUsedToAsk =
      Offset(
        (centre.x - drawn.canvasBounds.left) / ZOOM,
        (centre.y - drawn.canvasBounds.top) / ZOOM,
      )
    assertNotEquals(
      hit,
      drawn.hitTest(asTheDropUsedToAsk),
      "the frame is offset and zoomed, so the old conversion should have left the slot",
    )
  }

  /** The box the canvas measured for [slot], as a rectangle. */
  private fun DrawnCanvas.boundsOf(slot: ParentSlot): Rect? =
    slots
      .firstOrNull { it.parentNodeId == slot.nodeId && it.slotName == slot.slot }
      ?.bounds
      ?.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }

  /** The drop's own hit-test, on the boxes the canvas last measured. */
  private fun DrawnCanvas.hitTest(point: Offset): ParentSlot? =
    reducer.dropTargetAt(
      state = reducer.initial(jetcasterDocument, selectedNodeId = null),
      componentId = "m3/text",
      inspection = snapshot,
      position = point,
    )

  /** A piece's fractions become a window point through the frame's rectangle and nothing else. */
  @Test
  fun `a reference piece is hit-tested where it is drawn`() {
    val frame = Rect(left = 120f, top = 60f, right = 120f + 400f, bottom = 60f + 800f)
    val piece = piece(left = 0.25f, top = 0.1f, right = 0.75f, bottom = 0.3f)

    assertEquals(Offset(120f + 200f, 60f + 160f), piece.centreIn(frame))
  }

  /**
   * The frame's rectangle, not the environment's: an extent taller than the device it is drawn for
   * is the case the old conversion could not see, because it scaled the fraction by `heightDp`.
   */
  @Test
  fun `a piece on a design taller than its frame is hit-tested down the extent`() {
    val extent = Rect(left = 0f, top = 0f, right = 400f, bottom = 2400f)
    val piece = piece(left = 0f, top = 0.5f, right = 0.1f, bottom = 0.5f)

    assertEquals(1200f, piece.centreIn(extent).y)
  }

  /**
   * Another person's selection is outlined over the node they have selected.
   *
   * Probed in pixels, because the bug was in a draw. The overlay is composed inside the frame, so
   * it draws in the frame's own pixels, while the boxes it draws are the window's — so this puts it
   * in a frame offset and scaled the way the canvas draws one, hands it a node box in the window,
   * and asks where the colour came out. Untranslated, the outline picked up the frame's offset a
   * second time and the scale twice over; it has to land back on the box it was given.
   */
  @Test
  fun `a collaborator's outline is drawn over the node, not at the frame's offset again`() {
    val node = Rect(left = 200f, top = 160f, right = 320f, bottom = 260f)

    assertWithin(node, drawPresence(node), tolerance = 4f)
  }

  /**
   * The overlay alone, inside a frame placed and scaled the way [PinnedDesignCanvas] places one.
   *
   * The whole canvas would have been the better subject, but its inspection arrives during layout,
   * and a scene rendered once draws the frame before those boxes exist. This is the same composable
   * against the same contract, with the snapshot handed to it rather than measured.
   */
  private fun drawPresence(nodeInWindow: Rect): Rect {
    val collector = UiBuilderInspectionCollector(widgetDocument())
    collector.recordNodeBounds(
      WIDGET_NODE,
      nodeInWindow.left,
      nodeInWindow.top,
      nodeInWindow.right,
      nodeInWindow.bottom,
    )
    // Where the frame sits in the window and what it is painted at, which is all the overlay is
    // told and all it needs.
    val frameBounds =
      Rect(
        FRAME_ORIGIN,
        FRAME_ORIGIN,
        FRAME_ORIGIN + FRAME_SIDE * FRAME_DRAW_SCALE,
        FRAME_ORIGIN + FRAME_SIDE * FRAME_DRAW_SCALE,
      )
    val image =
      renderComposeScene(SCENE_PX, SCENE_PX, Density(HOST_DENSITY)) {
        Box(Modifier.fillMaxSize()) {
          Box(
            Modifier.offset(FRAME_ORIGIN.dp, FRAME_ORIGIN.dp).size(FRAME_SIDE.dp).graphicsLayer {
              scaleX = FRAME_DRAW_SCALE
              scaleY = FRAME_DRAW_SCALE
              transformOrigin = TransformOrigin(0f, 0f)
            }
          ) {
            RemotePresenceOverlay(
              collaborators =
                listOf(
                  UiBuilderCollaborator(
                    actorId = "other",
                    displayName = "Other",
                    colorArgbHex = PRESENCE_COLOR,
                    selectedNodeIds = listOf(WIDGET_NODE),
                  )
                ),
              inspection = collector.snapshot(),
              frameBounds = frameBounds,
              drawScale = FRAME_DRAW_SCALE,
            )
          }
        }
      }
    return checkNotNull(readPixels(image).boundsOf(PRESENCE_RGB)) {
      "no presence outline was drawn"
    }
  }

  /**
   * The canvas drawn offset in its pane, zoomed, on a design whose density is not the host's.
   *
   * All three at once deliberately: each one alone leaves the wrong conversion looking right, and
   * the canvas in a browser is never any of them alone.
   */
  private fun drawCanvas(document: UiBuilderDocument = widgetDocument()): DrawnCanvas {
    var snapshot: UiBuilderInspectionSnapshot? = null
    var canvasBounds = Rect.Zero
    renderComposeScene(SCENE_PX, SCENE_PX, Density(HOST_DENSITY)) {
      Box(Modifier.fillMaxSize().padding(start = PANE_INSET.dp, top = PANE_INSET.dp)) {
        PinnedDesignCanvas(
          document = document,
          selectedNodeId = null,
          onNodeSelected = {},
          onCanvasMetrics = { _, _, _ -> },
          onCanvasBounds = { canvasBounds = it },
          dropHovered = false,
          showSelectionOverlay = true,
          reference = ReferenceOverlayState(),
          onMarkDrawn = { _, _ -> },
          onPieceMoved = { _, _, _ -> },
          collaborators = emptyList(),
          commentThreads = emptyList(),
          selectedThreadId = null,
          onCommentThreadSelected = {},
          onInspectionSnapshot = { snapshot = it },
          onInspectionInvalidated = null,
          selectionMenu = {},
          hoverEditor = null,
          zoom = ZOOM,
          onZoomChanged = {},
          contentAlignment = Alignment.TopStart,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    val measured = checkNotNull(snapshot) { "the canvas measured nothing" }
    check(canvasBounds.left >= PANE_INSET) {
      "expected the frame inset in its pane, reported at $canvasBounds"
    }
    val root =
      checkNotNull(measured.nodes.firstOrNull { it.bounds != null }?.bounds) {
        "the canvas measured no node"
      }
    return DrawnCanvas(
      canvasBounds = canvasBounds,
      widgetBounds = Rect(root.x, root.y, root.x + root.width, root.y + root.height),
      snapshot = measured,
    )
  }

  private class DrawnCanvas(
    val canvasBounds: Rect,
    val widgetBounds: Rect,
    val snapshot: UiBuilderInspectionSnapshot,
  ) {
    val slots: List<UiBuilderSlotInspection>
      get() = snapshot.slots
  }

  /** The scene's pixels, BGRA, which is the colour type the renderer hands back. */
  private class Scene(private val pixels: ByteArray) {
    /**
     * The box every pixel recognisably [rgb] falls in, or null where it was never drawn.
     *
     * Nearest rather than exact: a stroke this thin is antialiased at both of its edges, so asking
     * for the literal value would find only whatever landed on a whole pixel.
     */
    fun boundsOf(rgb: Int): Rect? {
      var left = Int.MAX_VALUE
      var top = Int.MAX_VALUE
      var right = Int.MIN_VALUE
      var bottom = Int.MIN_VALUE
      for (y in 0 until SCENE_PX) {
        for (x in 0 until SCENE_PX) {
          val at = (y * SCENE_PX + x) * 4
          val pixel =
            (pixels[at + 2].toInt() and 0xff shl 16) or
              (pixels[at + 1].toInt() and 0xff shl 8) or
              (pixels[at].toInt() and 0xff)
          if (!pixel.resembles(rgb)) continue
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          if (y > bottom) bottom = y
        }
      }
      if (right < left) return null
      // Inclusive pixel indices become a box, so the right and bottom edges are past the last one.
      return Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }
  }

  private fun readPixels(image: org.jetbrains.skia.Image): Scene =
    Scene(
      org.jetbrains.skia.Bitmap.makeFromImage(image).readPixels()
        ?: error("the scene rendered no pixels")
    )

  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))

  private val reducer = UiBuilderEditorReducer(catalog)

  /** A real design with real nested slots, which is what a drop has to choose between. */
  private val jetcasterDocument =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun widgetDocument(): UiBuilderDocument =
    wearWidgetUiBuilderDocument(
      designId = "hit-space",
      catalogPin = catalogPin,
      environment = watchEnvironment,
      size = WearWidgetScaffoldSize.Large,
    )

  private fun piece(left: Float, top: Float, right: Float, bottom: Float) =
    ReferencePiece(
      id = "piece",
      image = ReferenceImage(id = "i", name = "n", mediaType = "image/png", base64 = "AAAA"),
      left = left,
      top = top,
      right = right,
      bottom = bottom,
    )

  /** Every edge within [tolerance] pixels of the expected one, said as one assertion. */
  private fun assertWithin(expected: Rect, actual: Rect, tolerance: Float) {
    val off =
      listOf(
          expected.left - actual.left,
          expected.top - actual.top,
          expected.right - actual.right,
          expected.bottom - actual.bottom,
        )
        .maxOf(::abs)
    assertTrue(off <= tolerance, "expected the outline at $expected, drawn at $actual")
  }

  private val watchEnvironment: JsonObject =
    Json.parseToJsonElement(
        """
        {
          "widthDp": 240, "heightDp": 240, "density": 2.0, "theme": "dark",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr", "animations": "settled"
        }
        """
      )
      .jsonObject

  private val catalogPin: JsonObject =
    Json.parseToJsonElement(
        """
        {
          "systemId": "remote-m3", "catalogRevision": "wear-widget-scaffolds-v1",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
        }
        """
      )
      .jsonObject

  private companion object {
    /** Within a channel of the colour asked for, which antialiasing costs at every edge. */
    private fun Int.resembles(rgb: Int): Boolean =
      listOf(16, 8, 0).all { shift ->
        abs((this shr shift and 0xff) - (rgb shr shift and 0xff)) <= 40
      }

    const val SCENE_PX = 900
    const val HOST_DENSITY = 1f

    /** Enough that a point converted out of the window's space lands somewhere else entirely. */
    const val PANE_INSET = 90f
    const val ZOOM = 1.5f

    const val FRAME_ORIGIN = 150f
    const val FRAME_SIDE = 400f
    const val FRAME_DRAW_SCALE = 0.75f

    const val WIDGET_NODE = "wear-widget-large"
    /** A node of the fixture whose slot holds the cards, so a point lands inside something. */
    const val GRID_NODE = "discover-grid"
    /** Opaque, and nothing else on this canvas is anywhere near it. */
    const val PRESENCE_COLOR = "#FFFF00FF"
    const val PRESENCE_RGB = 0xFF00FF
  }
}
