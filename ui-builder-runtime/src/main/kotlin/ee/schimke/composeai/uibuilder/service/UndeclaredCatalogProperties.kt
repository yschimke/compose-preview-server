package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * The properties a stored design carries that its catalog no longer declares.
 *
 * ## Why this exists
 *
 * A catalog is not frozen. A component loses a property, or a published catalog replaces a
 * synthesised one that declared more, and every design that used the property stops validating. The
 * verdict was fatal: `unusableReason` turned one undeclared property into `invalid stored design
 * <id>`, and from then on *every* request naming that design was refused. Thirty-six designs on
 * `preview.coo.ee` died that way after the catalog source flip, twenty-six of them for nothing
 * worse than `property containerColor is not declared by m3/surface`.
 *
 * Nothing was lost, which is the part worth noticing: the value is still sitting in the stored
 * document, untouched. Only the verdict was fatal. So there is no need to park the value somewhere
 * new — and a good reason not to, since the document schema belongs to `compose-preview-contracts`
 * and a design that stores its own damage has to be migrated back out of it later. Leaving the
 * property where it is means a catalog that declares it again simply resumes: no migration, no
 * un-parking, and the design is exactly what its author wrote.
 *
 * ## The probe
 *
 * [withoutProperties] builds the document as it WOULD be if the undeclared properties were absent,
 * and that copy is validated instead of the real one. It exists only to answer "is anything else
 * wrong?" — a document whose sole complaint is an undeclared property validates clean as a probe
 * and is served as itself. Anything the probe still refuses is a real defect and stays fatal: an
 * unknown component has nothing to draw, and no amount of tolerance produces a node.
 *
 * The probe is never stored, never handed to a client, and never exported as the design. It is a
 * question, not a document.
 */
internal fun undeclaredProperties(
  document: DesignDocumentV1,
  catalog: CatalogCapabilityV1,
): Map<String, Set<String>> {
  val declared = catalog.components.associateBy { it.componentId }
  return buildMap {
    document.nodes.forEach { (nodeId, node) ->
      // Only nodes the catalog DOES define. A node naming a component the catalog does not have is
      // the blocking case, and one naming a design-defined component is not the catalog's to judge
      // — `validate` already separates those two, and guessing here would answer for it.
      val component = declared[node.componentId] ?: return@forEach
      val names = component.properties.mapTo(mutableSetOf()) { it.name }
      val undeclared = node.properties.keys.filterTo(mutableSetOf()) { it !in names }
      if (undeclared.isNotEmpty()) put(nodeId, undeclared)
    }
  }
}

/**
 * The same document with [drop] removed, for asking a question about it.
 *
 * Returns the receiver unchanged when there is nothing to drop, so the common path allocates
 * nothing and the probe is the real document by identity.
 */
internal fun DesignDocumentV1.withoutProperties(drop: Map<String, Set<String>>): DesignDocumentV1 {
  if (drop.isEmpty()) return this
  return copy(
    nodes =
      nodes.mapValues { (nodeId, node) ->
        val names = drop[nodeId] ?: return@mapValues node
        node.copy(properties = node.properties.filterKeys { it !in names })
      }
  )
}
