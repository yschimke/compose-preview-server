package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.uibuilder.export.NativeNodeTags

/**
 * Turns the node tags a Remote Compose render carries as content descriptions into the test tags
 * [ServeSemanticsTags.index] keys bounds by.
 *
 * Remote Compose has no `testTag`. For a Wear widget the native lane instead starts every node's
 * `RemoteModifier` chain with `semantics { contentDescription = NativeNodeTags.tag(id) }`, and the
 * CMP player turns that into a Compose semantics node on the component's own layout node, with the
 * component's bounds. The semantics product reports it as the node's `label`; this rewrites such a
 * node so it reads as if it had been tagged `testTag = <id>`, and the rest of the bounds capture is
 * unchanged.
 *
 * The tag goes first in the chain because the player keeps the outermost content description, so a
 * node's label names at most one id in practice; if it ever names more, the first (outermost) is
 * the node's own. A node that already has a test tag is left alone: ordinary Compose output tags
 * with `Modifier.testTag`, and its own content description is not an id.
 */
internal object NativeNodeTagSemantics {

  fun retag(payload: ComposeSemanticsPayload): ComposeSemanticsPayload =
    payload.copy(root = retag(payload.root))

  private fun retag(node: ComposeSemanticsNode): ComposeSemanticsNode {
    val children = node.children.map(::retag)
    val id =
      node.label?.takeIf { node.testTag == null }?.let { NativeNodeTags.nodeIds(it).firstOrNull() }
    return when {
      id != null -> node.copy(testTag = id, children = children)
      children == node.children -> node
      else -> node.copy(children = children)
    }
  }
}
