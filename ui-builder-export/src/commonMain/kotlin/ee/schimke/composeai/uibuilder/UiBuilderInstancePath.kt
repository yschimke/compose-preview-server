package ee.schimke.composeai.uibuilder

import kotlin.jvm.JvmInline

/**
 * Which drawn box a measurement, a selection or a comment belongs to.
 *
 * The document is a flat map of nodes, and three walks agree with it one for one: the canvas
 * reports bounds per node, the exporters emit one call per node, and everything anchored to a node
 * id — selection, the inspector, comments, the native lane's `testTag` — reads that agreement as an
 * identity. **One node id is one drawn box** is load-bearing far outside the renderer, and it is
 * exactly what a loop over data and an instance of a reusable component both break: one node, drawn
 * *n* times.
 *
 * A path is what survives that. It names a box rather than a node, and it is deliberately no longer
 * than it has to be:
 *
 * - With no repetition above it, the path **is** the node id — `cell-0` — because node ids are
 *   unique in the document and nothing needs disambiguating. Every key every consumer writes today
 *   is unchanged, which is the point: the seam can land before anything produces a second copy.
 * - Inside a copy, the path carries the repeating node, which copy, and the way down from it —
 *   `cell#3`, `cell#3/label`, and `row#2/cell#4/label` where one repeat sits inside another. The
 *   chain begins at the **outermost repeat above the box**, never at the root: above every repeat
 *   there is nothing to disambiguate, and below one the copies are exactly what a bare id loses.
 *
 * [nodeId] is therefore always available, and always the document node this box drew — which is
 * what a consumer needs to look up properties, a component id, or a comment's anchor.
 */
@JvmInline
value class UiBuilderInstancePath(val value: String) {

  /** The document node this box drew. */
  val nodeId: String
    get() = value.substringAfterLast(SEPARATOR).substringBefore(OCCURRENCE)

  /** Whether any repeat stands between this box and the root. */
  val isAuthored: Boolean
    get() = OCCURRENCE !in value

  /**
   * The path of a child drawn inside this one.
   *
   * Below no repeat this is the child's own id — the identity it already had. Below one it keeps
   * the whole chain of copies it sits in, because those are the only thing that distinguishes it
   * from the same node in the copy beside it.
   */
  fun child(childNodeId: String): UiBuilderInstancePath =
    if (isAuthored) UiBuilderInstancePath(childNodeId)
    else UiBuilderInstancePath("$value$SEPARATOR$childNodeId")

  /**
   * One copy of this node, by its index in the run.
   *
   * Applied by whatever draws the run — a loop over rows, a component placed more than once — to
   * the path of the node being repeated, before its children are drawn.
   */
  fun occurrence(index: Int): UiBuilderInstancePath =
    UiBuilderInstancePath("$value$OCCURRENCE$index")

  override fun toString(): String = value

  companion object {
    private const val SEPARATOR = '/'
    private const val OCCURRENCE = '#'

    /** The path of a node drawn once, which is every node the format can express today. */
    fun of(nodeId: String): UiBuilderInstancePath = UiBuilderInstancePath(nodeId)
  }
}
