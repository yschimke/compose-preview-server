package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deviceless canvas: a design that holds several top-level items rather than one screen.
 *
 * A document has always been allowed to carry a list of [UiBuilderDocument.roots] and, until this
 * existed, was refused the moment it held more than one — by the collaboration reducer, by the
 * service and by every export gate. The refusal was right while a second root meant *nothing*: two
 * disjoint trees satisfy every placement rule, and the only thing downstream could do with them was
 * decline (yschimke/compose-preview-server#429). It is wrong now that they mean something.
 *
 * **Two or more roots is the mode.** There is no stored flag, and deliberately so: the wire
 * `DesignEnvironmentV1` is published from another repository and its field set is closed, so a flag
 * would have to be smuggled through a field that means something else — a lie the renderer, the
 * exporter and the next reader would each have to be told separately. The shape of the document is
 * already the honest answer, it round-trips through every existing seam untouched, and it cannot
 * disagree with what is drawn. One item is a screen; several are a canvas.
 *
 * What the canvas *is*, once, so the four consumers cannot each invent their own arrangement:
 * [canvasNode] — a `layout/column` spacing the roots [CANVAS_SPACING_DP] apart and centring them
 * across the frame. The editor's surface draws that, the code exporters emit it, and
 * `ScreenDocumentProjection` projects it. A deviceless design is therefore exportable rather than
 * being a state nothing can get out of, which was the whole reason the second root was banned.
 */
object DevicelessCanvas {
  /**
   * The gap between two items on the canvas, in dp.
   *
   * 24 rather than a tighter number because the gap is what says "these are separate things". A
   * canvas holding a card and a dialog with 8dp between them reads as one screen laid out badly.
   */
  const val CANVAS_SPACING_DP: Int = 24

  /** The synthetic node's id, used verbatim unless the design already holds one. */
  const val CANVAS_NODE_ID: String = "deviceless-canvas"

  /**
   * A `layout/column` holding [roots] in canvas order, or null when [roots] is not a canvas.
   *
   * Synthetic: it is never inserted into a stored document and no operation can address it. It
   * exists so that everything downstream of the document — the surface, the two Kotlin exporters,
   * the screen projection — asks one piece of code what a canvas looks like instead of three.
   *
   * [nodeId] is [CANVAS_NODE_ID] unless the design already holds a node by that name, in which case
   * it is suffixed until it does not: a design is authored by people, and `deviceless-canvas` is a
   * name a person may reasonably have typed.
   */
  fun canvasNode(roots: List<String>, taken: Set<String>): UiBuilderNode? {
    if (roots.size < 2) return null
    var nodeId = CANVAS_NODE_ID
    var suffix = 2
    while (nodeId in taken) nodeId = "$CANVAS_NODE_ID-${suffix++}"
    return UiBuilderNode(
      id = nodeId,
      componentId = "layout/column",
      properties =
        JsonObject(
          mapOf(
            "verticalSpacingDp" to literal("float", JsonPrimitive(CANVAS_SPACING_DP)),
            "horizontalAlignment" to literal("enum", JsonPrimitive("center")),
          )
        ),
      modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxWidth"))))),
      slots = mapOf("children" to roots),
    )
  }

  private fun literal(type: String, value: JsonPrimitive): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
}

/**
 * Whether this design is a deviceless canvas rather than one screen — see [DevicelessCanvas].
 *
 * Zero roots is not a canvas: that is the empty document `create_design` takes and the first
 * parentless insert fills, and it is one insert away from being a screen.
 */
val UiBuilderDocument.isDevicelessCanvas: Boolean
  get() = roots.size > 1

/** [isDevicelessCanvas] asked of the released wire document. */
val DesignDocumentV1.isDevicelessCanvas: Boolean
  get() = roots.size > 1

/**
 * This document with its canvas expressed as a single root, or itself when it is already one.
 *
 * The one conversion every downstream consumer applies before it walks the tree: a screen passes
 * through untouched, and a canvas arrives as the `layout/column` [DevicelessCanvas.canvasNode]
 * describes, so nothing after this point has to know the difference.
 */
fun UiBuilderDocument.withCanvasRoot(): UiBuilderDocument {
  val canvas = DevicelessCanvas.canvasNode(roots, nodes.keys) ?: return this
  return copy(roots = listOf(canvas.id), nodes = nodes + (canvas.id to canvas))
}

/** [withCanvasRoot] asked of the released wire document. */
fun DesignDocumentV1.withCanvasRoot(): DesignDocumentV1 {
  val canvas = DevicelessCanvas.canvasNode(roots, nodes.keys) ?: return this
  val node = canvas.toDesignNodeV1()
  return copy(roots = listOf(node.id), nodes = nodes + (node.id to node))
}
