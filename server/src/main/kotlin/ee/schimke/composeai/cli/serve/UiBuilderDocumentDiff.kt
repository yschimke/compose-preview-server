package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The `schema` of every [DesignDiffV1]. */
internal const val UI_BUILDER_DESIGN_DIFF_SCHEMA = "compose-preview/ui-builder-design-diff/v1"

/**
 * Two design documents compared node by node: what `ui_builder_diff_designs` answers with, and the
 * diff a `ui_builder_restore_revision` dry run says it would apply.
 *
 * ## Why this lives here and not in the editor
 *
 * The editor already has one — `documentDiff` beside `revisionDiff` in compose-ui-builder's
 * `editor/RevisionTimeline.kt` — but it is in the `:ui-builder` frontend module, which is published
 * to nobody, and it diffs the editor's own `UiBuilderDocument` model with a catalog in hand for
 * labels. This server consumes the protocol and `ui-builder-runtime`, neither of which has a
 * document diff. So this is a focused diff over the released [DesignDocumentV1] — the same shape
 * every reply already carries — and it should be consolidated with the editor's once a document
 * diff moves into a published module (yschimke/compose-ui-builder#375 phase 2 is the natural
 * place).
 *
 * ## What it reports
 *
 * - **added** and **removed** nodes, with the path they were (or are) at;
 * - **moved** nodes: a different parent or slot, or a different position among the siblings that
 *   are in the same slot on both sides. A sibling inserted before a node shifts its index without
 *   moving it, so reordering is decided by the longest common subsequence of the surviving
 *   siblings, not by comparing indices;
 * - **changed** nodes: per property, the modifier list as a whole (its order is meaning), and every
 *   other node field (`componentId`, event bindings, accessibility, …) by name. A node may be both
 *   moved and changed; [DesignNodeDiffV1.change] says `moved` and the change lists are filled;
 * - **document** fields: title, catalog pin, environment, state variables, assets, token bindings
 *   and components — what is not a node.
 *
 * Values are the protocol's own JSON, so a reader sees `{"type":"string","value":"Hi"}` exactly as
 * `ui_builder_apply` would take it back. Ids, revisions, timestamps and the canonical `home` are
 * not compared: two designs are always different designs, and the diff is about their content.
 */
internal object UiBuilderDocumentDiff {

  /** More than any screen a person draws; past it the reply says it was cut rather than grow. */
  const val MAX_NODE_ENTRIES = 400

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  /**
   * The keys of a document that are identity or bookkeeping rather than content, plus the two that
   * are compared as nodes.
   */
  private val UNCOMPARED_DOCUMENT_KEYS =
    setOf(
      "schema",
      "id",
      "revision",
      "createdAtEpochMillis",
      "updatedAtEpochMillis",
      "home",
      "nodes",
      "roots",
    )

  /**
   * Node keys compared on their own terms; everything else is a [DesignNodeDiffV1.fields] entry.
   */
  private val NODE_KEYS_COMPARED_APART = setOf("id", "properties", "modifiers", "slots")

  fun diff(
    before: DesignDocumentV1,
    after: DesignDocumentV1,
    beforeSide: DesignDiffSideV1 = side(before),
    afterSide: DesignDiffSideV1 = side(after),
  ): DesignDiffV1 {
    val beforeTree = Tree(before)
    val afterTree = Tree(after)
    val entries = mutableListOf<DesignNodeDiffV1>()
    val beforeIds = before.nodes.keys
    val afterIds = after.nodes.keys

    (afterIds - beforeIds).sortedWith(afterTree.order).forEach { id ->
      entries +=
        DesignNodeDiffV1(
          nodeId = id,
          change = ADDED,
          componentId = after.nodes.getValue(id).componentId,
          path = afterTree.path(id),
          to = afterTree.locations[id],
        )
    }
    (beforeIds - afterIds).sortedWith(beforeTree.order).forEach { id ->
      entries +=
        DesignNodeDiffV1(
          nodeId = id,
          change = REMOVED,
          componentId = before.nodes.getValue(id).componentId,
          path = beforeTree.path(id),
          from = beforeTree.locations[id],
        )
    }
    val common = beforeIds intersect afterIds
    val moved = movedNodes(common, beforeTree, afterTree)
    var changedCount = 0
    common.sortedWith(afterTree.order).forEach { id ->
      val old = nodeJson(before.nodes.getValue(id))
      val new = nodeJson(after.nodes.getValue(id))
      val properties =
        fieldChanges(old["properties"] as? JsonObject, new["properties"] as? JsonObject)
      val modifiers =
        (old["modifiers"] ?: EMPTY_ARRAY).let { oldModifiers ->
          val newModifiers = new["modifiers"] ?: EMPTY_ARRAY
          if (oldModifiers == newModifiers) null
          else DesignFieldChangeV1("modifiers", oldModifiers, newModifiers)
        }
      val fields =
        fieldChanges(
          JsonObject(old - NODE_KEYS_COMPARED_APART),
          JsonObject(new - NODE_KEYS_COMPARED_APART),
        )
      val isMoved = id in moved
      val isChanged = properties.isNotEmpty() || modifiers != null || fields.isNotEmpty()
      if (isChanged) changedCount++
      if (!isMoved && !isChanged) return@forEach
      entries +=
        DesignNodeDiffV1(
          nodeId = id,
          change = if (isMoved) MOVED else CHANGED,
          componentId = after.nodes.getValue(id).componentId,
          path = afterTree.path(id),
          beforePath = beforeTree.path(id).takeIf { isMoved && it != afterTree.path(id) },
          from = beforeTree.locations[id].takeIf { isMoved },
          to = afterTree.locations[id].takeIf { isMoved },
          properties = properties,
          modifiers = modifiers,
          fields = fields,
        )
    }
    val documentChanges =
      fieldChanges(
        JsonObject(documentJson(before) - UNCOMPARED_DOCUMENT_KEYS),
        JsonObject(documentJson(after) - UNCOMPARED_DOCUMENT_KEYS),
      )
    val counts =
      DesignDiffCountsV1(
        added = (afterIds - beforeIds).size,
        removed = (beforeIds - afterIds).size,
        moved = moved.size,
        changed = changedCount,
        document = documentChanges.size,
      )
    val identical = entries.isEmpty() && documentChanges.isEmpty()
    val truncated = entries.size > MAX_NODE_ENTRIES
    return DesignDiffV1(
      a = beforeSide,
      b = afterSide,
      identical = identical,
      summary = summary(beforeSide, afterSide, counts, entries, documentChanges),
      counts = counts,
      nodes = entries.take(MAX_NODE_ENTRIES),
      document = documentChanges,
      truncated = truncated,
    )
  }

  /** The side of a diff a document stands for: its id, revision, title and content digest. */
  fun side(document: DesignDocumentV1): DesignDiffSideV1 =
    DesignDiffSideV1(
      designId = document.id,
      revision = document.revision,
      title = document.title,
      documentDigest = designDocumentDigest(document),
    )

  /**
   * Nodes whose container changed, or whose place among the siblings both sides share did.
   *
   * Within one container the surviving siblings' order on each side is compared by longest common
   * subsequence: the nodes outside it are the ones that moved, and the rest only had something
   * inserted or removed around them.
   */
  private fun movedNodes(common: Set<String>, before: Tree, after: Tree): Set<String> {
    val moved = mutableSetOf<String>()
    val stayed = mutableMapOf<Pair<String?, String?>, MutableList<String>>()
    common.forEach { id ->
      val from = before.locations[id]
      val to = after.locations[id]
      when {
        from == null || to == null -> if (from != to) moved += id
        from.parentId != to.parentId || from.slot != to.slot -> moved += id
        else -> stayed.getOrPut(from.parentId to from.slot) { mutableListOf() } += id
      }
    }
    stayed.forEach { (container, ids) ->
      if (ids.size < 2) return@forEach
      val members = ids.toSet()
      val beforeOrder = before.children(container).filter { it in members }
      val afterOrder = after.children(container).filter { it in members }
      val kept = longestCommonSubsequence(beforeOrder, afterOrder).toSet()
      moved += afterOrder.filterNot { it in kept }
    }
    return moved
  }

  private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
      for (j in b.indices.reversed()) {
        lengths[i][j] =
          if (a[i] == b[j]) lengths[i + 1][j + 1] + 1
          else maxOf(lengths[i + 1][j], lengths[i][j + 1])
      }
    }
    val result = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
      when {
        a[i] == b[j] -> {
          result += a[i]
          i++
          j++
        }
        lengths[i + 1][j] >= lengths[i][j + 1] -> i++
        else -> j++
      }
    }
    return result
  }

  /** Per key of two JSON objects: the keys whose values differ, in key order. */
  private fun fieldChanges(old: JsonObject?, new: JsonObject?): List<DesignFieldChangeV1> {
    val before = old ?: JsonObject(emptyMap())
    val after = new ?: JsonObject(emptyMap())
    return (before.keys + after.keys).sorted().mapNotNull { key ->
      val oldValue = before[key]?.takeUnless { it.isEmptyValue() }
      val newValue = after[key]?.takeUnless { it.isEmptyValue() }
      if (oldValue == newValue) null else DesignFieldChangeV1(key, oldValue, newValue)
    }
  }

  /** An empty map or list reads the same as an absent one: defaults are not changes. */
  private fun JsonElement.isEmptyValue(): Boolean =
    (this is JsonObject && isEmpty()) || (this is JsonArray && isEmpty())

  private fun nodeJson(node: DesignNodeV1): JsonObject =
    json.encodeToJsonElement(DesignNodeV1.serializer(), node).jsonObject

  private fun documentJson(document: DesignDocumentV1): JsonObject =
    json.encodeToJsonElement(DesignDocumentV1.serializer(), document).jsonObject

  private fun summary(
    a: DesignDiffSideV1,
    b: DesignDiffSideV1,
    counts: DesignDiffCountsV1,
    entries: List<DesignNodeDiffV1>,
    document: List<DesignFieldChangeV1>,
  ): String {
    val header = "${a.designId}@r${a.revision} → ${b.designId}@r${b.revision}"
    if (entries.isEmpty() && document.isEmpty()) return "$header: identical content."
    val totals =
      listOfNotNull(
          counts.added.takeIf { it > 0 }?.let { "$it added" },
          counts.removed.takeIf { it > 0 }?.let { "$it removed" },
          counts.moved.takeIf { it > 0 }?.let { "$it moved" },
          counts.changed.takeIf { it > 0 }?.let { "$it changed" },
          document
            .takeIf { it.isNotEmpty() }
            ?.let { changes -> "document: " + changes.joinToString(", ") { it.name } },
        )
        .joinToString("; ")
    val lines =
      entries.take(SUMMARY_LINES).map { entry ->
        when (entry.change) {
          ADDED -> "+ ${entry.nodeId} (${entry.componentId}) at ${entry.path}"
          REMOVED -> "- ${entry.nodeId} (${entry.componentId}) from ${entry.path}"
          else -> {
            val what =
              entry.properties.map { it.name } +
                listOfNotNull(entry.modifiers?.name) +
                entry.fields.map { it.name }
            val moved =
              if (entry.change == MOVED)
                " moved to ${entry.path}" + (entry.beforePath?.let { " (was $it)" } ?: "")
              else ""
            "~ ${entry.nodeId}$moved" + if (what.isEmpty()) "" else ": " + what.joinToString(", ")
          }
        }
      }
    val more = (entries.size - SUMMARY_LINES).takeIf { it > 0 }?.let { "\n… and $it more" } ?: ""
    return "$header: $totals." + lines.joinToString("") { "\n$it" } + more
  }

  /**
   * Where every reachable node sits: its parent, slot and index, and a readable path from its root.
   */
  private class Tree(document: DesignDocumentV1) {
    val locations = mutableMapOf<String, DesignNodeLocationV1>()
    private val containers = mutableMapOf<Pair<String?, String?>, List<String>>()
    private val visitOrder = mutableMapOf<String, Int>()
    private val nodes = document.nodes

    init {
      containers[null to null] = document.roots
      document.roots.forEachIndexed { index, id -> visit(id, null, null, index) }
      // Anything the roots do not reach still has an order, after everything they do.
      nodes.keys.sorted().forEach { id -> visitOrder.putIfAbsent(id, visitOrder.size) }
    }

    private fun visit(id: String, parentId: String?, slot: String?, index: Int) {
      if (id in visitOrder) return
      visitOrder[id] = visitOrder.size
      locations[id] = DesignNodeLocationV1(parentId = parentId, slot = slot, index = index)
      val node = nodes[id] ?: return
      node.slots.entries
        .sortedBy { it.key }
        .forEach { (slotName, children) ->
          containers[id to slotName] = children
          children.forEachIndexed { childIndex, child -> visit(child, id, slotName, childIndex) }
        }
    }

    fun children(container: Pair<String?, String?>): List<String> = containers[container].orEmpty()

    val order: Comparator<String> = compareBy { visitOrder[it] ?: Int.MAX_VALUE }

    /** `root/slot[index]/child/…`, or the bare id of a node no root reaches. */
    fun path(id: String): String {
      val segments = ArrayDeque<String>()
      var current: String? = id
      val seen = mutableSetOf<String>()
      while (current != null && seen.add(current)) {
        val location = locations[current] ?: return (listOf(current) + segments).joinToString("/")
        segments.addFirst(current)
        if (location.parentId == null) return segments.joinToString("/")
        segments.addFirst("${location.slot}[${location.index}]")
        current = location.parentId
      }
      return segments.joinToString("/")
    }
  }

  const val ADDED = "added"
  const val REMOVED = "removed"
  const val MOVED = "moved"
  const val CHANGED = "changed"
  private const val SUMMARY_LINES = 12
  private val EMPTY_ARRAY = JsonArray(emptyList())
}

/**
 * The digest of a design document: SHA-256 over its canonical JSON (keys sorted, defaults written)
 * with the canonical `home` left out. It covers the id, revision and timestamps too, so it names
 * one revision of one design; [UiBuilderDocumentDiff] is what says whether two have the same
 * content.
 *
 * The same bytes `ui-builder-runtime` hashes for `AcceptedOutcomeV1.documentHash` and for a
 * restore's hash check — its `documentHash` is internal to that module, so the rule is restated
 * here and a test holds the two to the same answer. Excluding `home` is the runtime's rule too:
 * moving a document is not a change to its content.
 */
internal fun designDocumentDigest(document: DesignDocumentV1): String {
  val element =
    DIGEST_JSON.encodeToJsonElement(DesignDocumentV1.serializer(), document.copy(home = null))
  return MessageDigest.getInstance("SHA-256")
    .digest(canonicalJson(element).encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
}

private val DIGEST_JSON = Json { encodeDefaults = true }

private fun canonicalJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
    is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalJson)
    is JsonPrimitive -> element.toString()
  }

@Serializable
internal data class DesignDiffV1(
  val schema: String = UI_BUILDER_DESIGN_DIFF_SCHEMA,
  /** The "before" side. */
  val a: DesignDiffSideV1,
  /** The "after" side. */
  val b: DesignDiffSideV1,
  /** Whether the two documents have the same content (ids, revisions and homes aside). */
  val identical: Boolean,
  /** A few lines a person can read: the totals, then one line per node entry. */
  val summary: String,
  val counts: DesignDiffCountsV1,
  /** Added, removed, then moved or changed nodes, in tree order. */
  val nodes: List<DesignNodeDiffV1> = emptyList(),
  /** Changed document fields that are not nodes: title, environment, state variables, … */
  val document: List<DesignFieldChangeV1> = emptyList(),
  /** Whether [nodes] was cut at [UiBuilderDocumentDiff.MAX_NODE_ENTRIES]; [counts] is whole. */
  val truncated: Boolean = false,
)

@Serializable
internal data class DesignDiffSideV1(
  val designId: String,
  val revision: Long,
  val title: String,
  /** See [designDocumentDigest]. */
  val documentDigest: String,
)

@Serializable
internal data class DesignDiffCountsV1(
  val added: Int,
  val removed: Int,
  val moved: Int,
  /** Nodes with a property, modifier or other field change; a moved node may be counted too. */
  val changed: Int,
  val document: Int,
)

@Serializable
internal data class DesignNodeDiffV1(
  val nodeId: String,
  /** `added`, `removed`, `moved` (perhaps also changed) or `changed`. */
  val change: String,
  val componentId: String,
  /** Where the node is on side `b` — or was on side `a`, for a removed node. */
  val path: String,
  /** Where a moved node was on side `a`, when that path differs. */
  val beforePath: String? = null,
  val from: DesignNodeLocationV1? = null,
  val to: DesignNodeLocationV1? = null,
  val properties: List<DesignFieldChangeV1> = emptyList(),
  /** The whole modifier list on each side, when it differs: its order is its meaning. */
  val modifiers: DesignFieldChangeV1? = null,
  /** Any other node field that differs: `componentId`, `eventBindings`, `accessibility`, … */
  val fields: List<DesignFieldChangeV1> = emptyList(),
)

/** Where a node sits: a root (no parent, no slot) or a child at [index] of [parentId]'s [slot]. */
@Serializable
internal data class DesignNodeLocationV1(
  val parentId: String? = null,
  val slot: String? = null,
  val index: Int,
)

/** One named value on each side; an absent side was unset there. */
@Serializable
internal data class DesignFieldChangeV1(
  val name: String,
  val before: JsonElement? = null,
  val after: JsonElement? = null,
)
