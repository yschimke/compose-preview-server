package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AddCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeIssueSeverityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeIssueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.RemoveCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.ReplaceCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * What moving a design from one catalog's vocabulary to another's would cost it.
 *
 * ## Why a plan rather than a rename
 *
 * `remote-m3` used to be synthesised in this repository, and the synthesised catalog BORROWED two
 * Material 3 ids outright — `m3/text` and `m3/surface` — from the packaged catalog
 * (`ProductionUiBuilderRuntime.remoteM3Catalog`). The published catalog that replaced it declares
 * its own namespace and nothing else, which is the correct statement for it to make: a Remote
 * Compose catalog does not own Material 3's ids, and `wear-m3` was renamed away from exactly that
 * claim. The cost of being right landed on the designs that had already been authored against the
 * borrowed ids — they name a component the served catalog has never heard of, and an unknown
 * component is fatal in a way an undeclared property is not ([UndeclaredCatalogProperties]): there
 * is nothing to draw.
 *
 * So the repair is to move the document, and a move is not a rename. `m3/text` has a counterpart
 * the catalog really publishes — `remote-m3/remote-text`, which is `RemoteText` — but its
 * parameters are the Remote library's, not Material 3's, so `fontSizeSp` becomes `fontSize` and
 * `letterSpacingSp` has nowhere to go at all. `m3/surface` has no counterpart: Remote Compose
 * Material 3 publishes no `Surface`, and its `RemoteCard` takes a non-defaulted `onClick`, so
 * migrating a static container onto a card would invent an action the document never had. A box
 * with a background modifier is what the author actually drew.
 *
 * ## Declared mappings, and everything else
 *
 * A rule states only what someone had to decide: the component it moves to, the properties whose
 * NAME changed, and the properties that become modifiers. It never lists what is dropped. A
 * property the target simply does not declare falls out at the end, with a `WARNING` naming it, and
 * that is deliberate — a list of drops would go stale the moment the target catalog changed, and a
 * catalog that later declares `letterSpacing` should start carrying it across with no edit here.
 *
 * ## Nothing is thrown away
 *
 * The candidate is a proposal. The stored document is untouched until an apply lands, and a dropped
 * value stays in it either way, so a catalog that grows the property back restores the design
 * rather than a migration having to put it back.
 */
internal data class CatalogUpgradeOutcome(
  /** The document as it would be under the target catalog. Never stored by planning it. */
  val candidate: DesignDocumentV1,
  /** Every difference between the stored document and [candidate], as JSON Pointer paths. */
  val changes: List<CatalogUpgradeChangeV1>,
  /** What a person needs to know before accepting it. `ERROR` means the move cannot be made. */
  val issues: List<CatalogUpgradeIssueV1>,
)

/**
 * How one component becomes another.
 *
 * [renamed] is a property whose name the target spells differently for the same thing.
 * [toModifiers] is a property the target expresses as a modifier instead — the surface's
 * `containerColor` is a `background` on a box. [slots] renames a slot, since the id a node's
 * children hang under is the component's too.
 */
private data class ComponentRewrite(
  val to: String,
  val renamed: Map<String, String> = emptyMap(),
  val slots: Map<String, String> = emptyMap(),
  val toModifiers: Map<String, (UiValueV1) -> DesignModifierV1> = emptyMap(),
)

/**
 * The one catalog that needs this, and why each line is what it is.
 *
 * Keyed by the target's `catalogSystemId` rather than by a pin, because it is the vocabulary that
 * decides the mapping and a catalog's revision moves under it. The intent of
 * [#819](https://github.com/yschimke/compose-preview-server/issues/819) is that a catalog states
 * its own successors, at which point this table loads rather than declares — the same journey
 * [composeFoundationCatalog] is on.
 */
private val UPGRADES: Map<String, Map<String, ComponentRewrite>> =
  mapOf(
    "remote-m3" to
      mapOf(
        // `RemoteText`, published by the catalog as `remote-m3/remote-text` and already on its
        // `Text` shelf. Its parameter list is the Remote library's: `fontSize` rather than
        // `fontSizeSp`, and no letter spacing at all, which is the one value this move costs.
        "m3/text" to
          ComponentRewrite(
            to = "remote-m3/remote-text",
            renamed = mapOf("fontSizeSp" to "fontSize"),
          ),
        // A box, not a card. `RemoteCard`'s `onClick` has no default, so a card would give a static
        // container an action nobody authored; `layout/box` is donated to this catalog already and
        // `background` is one of the modifiers `RemoteContentEmitter` can write
        // (`REMOTE_M3_MODIFIERS`). `shapeDp` is NOT mapped onto `clip`: the modifier names shapes
        // (`medium`, `circle`), a surface states a radius in dp, and turning one into the other
        // would be this code inventing a shape token the author did not choose.
        "m3/surface" to
          ComponentRewrite(
            to = "layout/box",
            slots = mapOf("content" to "children"),
            toModifiers = mapOf("containerColor" to { color -> BackgroundModifierV1(color) }),
          ),
      )
  )

/**
 * The move from [document]'s current pin to [targetPin], judged against [target].
 *
 * Pure: it reads the document and the catalog and returns what would happen. Nothing here writes,
 * validates or persists — the caller validates [CatalogUpgradeOutcome.candidate] against the target
 * catalog, because that is the same question every other write path asks and it should be answered
 * by the same validator rather than re-implemented here.
 */
internal fun planCatalogUpgrade(
  document: DesignDocumentV1,
  target: CatalogCapabilityV1,
  targetPin: CatalogReferenceV1,
): CatalogUpgradeOutcome {
  val rewrites = UPGRADES[target.benchmark.catalogSystemId].orEmpty()
  val declared = target.components.associateBy { it.componentId }
  val changes = mutableListOf<CatalogUpgradeChangeV1>()
  val issues = mutableListOf<CatalogUpgradeIssueV1>()
  val nodes =
    document.nodes.mapValues { (nodeId, node) ->
      val rewrite = rewrites[node.componentId]
      val componentId = rewrite?.to ?: node.componentId
      val component = declared[componentId]
      if (component == null && componentId !in document.components) {
        // Nothing to move onto: no rule names a successor and the target does not declare the id
        // itself. `ERROR` rather than a dropped node, because deleting somebody's content to make a
        // document validate is never the repair.
        issues +=
          CatalogUpgradeIssueV1(
            CatalogUpgradeIssueSeverityV1.ERROR,
            "UNKNOWN_COMPONENT",
            nodePath(nodeId),
            "${target.benchmark.catalogSystemId} does not declare ${node.componentId}, " +
              "and no successor is stated for it",
          )
        return@mapValues node
      }
      if (rewrite != null) {
        changes +=
          ReplaceCatalogUpgradeChangeV1(
            "${nodePath(nodeId)}/componentId",
            JsonPrimitive(node.componentId),
            JsonPrimitive(componentId),
          )
      }
      val names = component?.properties?.mapTo(mutableSetOf()) { it.name } ?: mutableSetOf()
      val properties = mutableMapOf<String, UiValueV1>()
      val modifiers = node.modifiers.toMutableList()
      node.properties.forEach { (name, value) ->
        val asModifier = rewrite?.toModifiers?.get(name)
        if (asModifier != null) {
          val modifier = asModifier(value)
          modifiers += modifier
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          changes += AddCatalogUpgradeChangeV1("${nodePath(nodeId)}/modifiers", modifier.encoded())
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.INFO,
              "PROPERTY_BECOMES_MODIFIER",
              propertyPath(nodeId, name),
              "$name is a modifier on $componentId, and moves onto the node's modifier chain",
            )
          return@forEach
        }
        val renamed = rewrite?.renamed?.get(name) ?: name
        if (component != null && renamed !in names) {
          // The drop that is not declared anywhere: whatever the target does not have a place for.
          // The value stays in the stored document -- only the candidate is without it -- so this
          // is a warning about what an export would stop writing, not a deletion.
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.WARNING,
              "PROPERTY_NOT_DECLARED",
              propertyPath(nodeId, name),
              "$componentId does not declare $renamed; the value stays in the stored design and " +
                "is left out of the upgraded one",
            )
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          return@forEach
        }
        properties[renamed] = value
        if (renamed != name) {
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          changes += AddCatalogUpgradeChangeV1(propertyPath(nodeId, renamed), value.encoded())
        }
      }
      val slots = mutableMapOf<String, List<String>>()
      node.slots.forEach { (slot, children) ->
        val to = rewrite?.slots?.get(slot) ?: slot
        // A slot rename that lands on a slot the node already fills would silently merge two lists
        // of children into one. Nothing states such a rule today; if one ever does, it stops here
        // rather than in a design that quietly gained a sibling.
        require(to !in slots) { "slot rename $slot -> $to collides on $nodeId" }
        slots[to] = children
        if (to != slot) {
          changes += RemoveCatalogUpgradeChangeV1(slotPath(nodeId, slot), children.encoded())
          changes += AddCatalogUpgradeChangeV1(slotPath(nodeId, to), children.encoded())
        }
      }
      node.copy(properties = properties, modifiers = modifiers, slots = slots)
    }
  changes +=
    ReplaceCatalogUpgradeChangeV1(
      "/catalogPin",
      document.catalogPin.encoded(),
      targetPin.encoded(),
    )
  return CatalogUpgradeOutcome(
    candidate = document.copy(catalogPin = targetPin, nodes = nodes),
    changes = changes,
    issues = issues,
  )
}

private fun nodePath(nodeId: String) = "/nodes/$nodeId"

private fun propertyPath(nodeId: String, property: String) =
  "${nodePath(nodeId)}/properties/$property"

private fun slotPath(nodeId: String, slot: String) = "${nodePath(nodeId)}/slots/$slot"

/**
 * The wire shape of a value, for a change a person reads rather than a document anything replays.
 *
 * `encodeDefaults` so a change record spells a value the same way the stored design does.
 */
private val json = Json { encodeDefaults = true }

private fun UiValueV1.encoded(): JsonElement =
  json.encodeToJsonElement(UiValueV1.serializer(), this)

private fun DesignModifierV1.encoded(): JsonElement =
  json.encodeToJsonElement(DesignModifierV1.serializer(), this)

private fun List<String>.encoded(): JsonElement = JsonArray(map { JsonPrimitive(it) })

/**
 * The whole reference, not its system id: source and target usually share the id and differ only in
 * the revision, so a change spelling ids alone would read as a change to nothing.
 */
private fun CatalogReferenceV1.encoded(): JsonElement =
  json.encodeToJsonElement(CatalogReferenceV1.serializer(), this)
