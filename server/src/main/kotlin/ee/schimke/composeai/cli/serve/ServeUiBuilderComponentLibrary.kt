package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The components a project shares between its own designs — the third question the design record
 * asks, after "can the format repeat" and "can a subtree become a composable".
 *
 * A component defined inside one design is reusable in that design and nowhere else, which is the
 * point at which a team starts copying subtrees between files. A copy is not reuse: the second one
 * stops tracking the first the moment either is edited. So a project publishes its components the
 * way it already publishes its designs, and a design *references* one.
 *
 * ## The convention
 *
 * Deliberately the same shape as [ServeUiBuilderDesignLibrary], read through the same two sources
 * and the same file reader, because it is the same loop seen from one level down:
 * ```text
 * ui-builder/components/index.json     the manifest: which components this project publishes
 * ui-builder/components/<file>.json    one component symbol per file
 * ```
 *
 * ## A symbol file is a design document
 *
 * There is no new wire type here, and that is a decision rather than an economy. A published symbol
 * is an ordinary [DesignDocumentV1] carrying exactly one entry in its `components` map plus the
 * nodes that entry's body is made of — the same document a design is, written by the same exporter,
 * validated by the same rules, drawn by the same canvas. The alternative, a bespoke
 * `ComponentDocumentV1`, would need its own schema, its own validator and its own release through
 * the contracts repository, and would drift from the document shape the editor actually holds.
 *
 * What makes this worth having over a component *pack* is that these are catalog nodes all the way
 * down: the Wasm canvas draws a project symbol properly, where a pack can only name a placeholder
 * and say so.
 *
 * ## Two rules keep it honest
 *
 * - **Referenced, not copied.** A shared symbol changing under a design is the catalog-pin problem
 *   again, so every symbol carries a [Symbol.digest] over its content. An importing design records
 *   the id *and* the digest, and a design whose library has moved is reported as drifted rather
 *   than silently redrawn. Computing it is this class's job; acting on it is the importer's.
 * - **A symbol may only use components from the pinned catalog.** A body that places another
 *   project symbol is refused by name: cross-symbol composition needs an import graph, a cycle
 *   check and a digest per edge, none of which exist yet, and half-supporting it would mean a
 *   design that imports one symbol silently depends on another it never named.
 *
 * Both refusals are per symbol and never per project: one unusable file is dropped with a line in
 * the log, and the components either side of it are still offered.
 *
 * ## The far half, for context
 *
 * This convention only has to carry a symbol while it is still moving. Once a component settles, it
 * is generated into the app's own Kotlin and committed; discovery picks it up, and it returns as an
 * ordinary `<catalog>/<component>` on the palette, exported as a call to real code. That is why
 * this is a convention over the existing reader rather than a system of its own.
 */
class ServeUiBuilderComponentLibrary(
  private val fetch: (url: String, maxBytes: Long) -> ByteArray?,
  private val ttlMillis: Long = ServeUiBuilderDesignLibrary.DEFAULT_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) {

  /** One component a project publishes. [file] is relative to [COMPONENTS_DIR]. */
  data class Entry(
    val system: String,
    val componentId: String,
    val title: String,
    val description: String?,
    val file: String,
  )

  /**
   * One published symbol, read and checked.
   *
   * [nodes] is the body reachable from the component's root, not the whole file: a symbol file may
   * carry a frame or a swatch around the thing it publishes, and what an importing design copies in
   * is the subtree the component names.
   */
  data class Symbol(
    val entry: Entry,
    val component: DesignComponentV1,
    val nodes: Map<String, DesignNodeV1>,
    /** Stable across formatting and key order; see [digestOf]. */
    val digest: String,
    /** The catalog this symbol's nodes were authored against, for the importer to check its own. */
    val catalogPin: CatalogReferenceV1,
  ) {
    val componentId: String
      get() = entry.componentId
  }

  private data class CachedIndex(
    val generation: String?,
    val readAt: Long,
    val entries: List<Entry>,
  )

  private val indexes = ConcurrentHashMap<String, CachedIndex>()

  /** Every published component across [catalogs], catalog order preserved. */
  fun list(catalogs: List<ServeUiBuilderDesignLibrary.Coordinate>): List<Entry> =
    catalogs.flatMap(::index)

  /**
   * One catalog's published components.
   *
   * Concurrent for the reason the design index is: two viewers opening a palette at once reach this
   * on different request threads, a cold read is one HTTP round trip, and the read is idempotent —
   * so two of them are harmless where a lock would put both behind one.
   */
  fun index(catalog: ServeUiBuilderDesignLibrary.Coordinate): List<Entry> {
    val cached = indexes[catalog.system]
    if (
      catalog.cacheable &&
        cached != null &&
        cached.generation == catalog.generation &&
        !cached.isStale()
    ) {
      return cached.entries
    }

    val bytes = runCatching {
      read(catalog, INDEX_PATH, MAX_INDEX_BYTES)
    }
      .onFailure { onLog("serve: ${catalog.system} component index unreadable (${it.message})") }
      .getOrNull()
    val entries =
      if (bytes == null) emptyList()
      else
        runCatching { parseIndex(catalog.system, bytes.toString(Charsets.UTF_8)) }
          .getOrElse {
            // Said once per read rather than swallowed: the project published something and it is
            // not being offered, which is the one case an operator cannot otherwise tell apart
            // from publishing nothing at all.
            onLog(
              "serve: ${catalog.system} publishes a component index that is not readable " +
                "(${it.message})"
            )
            emptyList()
          }
    if (catalog.cacheable)
      indexes[catalog.system] = CachedIndex(catalog.generation, clock(), entries)
    return entries
  }

  /** The symbol behind one published id, or null when it cannot be read or does not check out. */
  fun symbol(
    catalog: ServeUiBuilderDesignLibrary.Coordinate,
    componentId: String,
  ): Symbol? =
    index(catalog).firstOrNull { it.componentId == componentId }?.let { symbol(catalog, it) }

  /**
   * The symbol behind an entry the caller already resolved.
   *
   * The overload exists for the reason the design library's does: a local directory source is
   * deliberately uncached, so two reads can land either side of somebody exporting the project, and
   * a caller that took its metadata from one and its body from the other would describe one symbol
   * with another's content.
   */
  fun symbol(catalog: ServeUiBuilderDesignLibrary.Coordinate, entry: Entry): Symbol? {
    val bytes =
      runCatching { read(catalog, "$COMPONENTS_DIR/${entry.file}", MAX_DOCUMENT_BYTES) }.getOrNull()
        ?: return null
    val document =
      runCatching { json.decodeFromString<DesignDocumentV1>(bytes.toString(Charsets.UTF_8)) }
        .onFailure {
          onLog(
            "serve: ${catalog.system}'s component ${entry.componentId} is not a design document " +
              "(${it.message})"
          )
        }
        .getOrNull() ?: return null
    return symbolOf(entry, document)
  }

  /**
   * The checked symbol a document publishes, or null with a logged reason.
   *
   * Internal rather than private so the checks can be exercised directly against a document the
   * test builds, without a source to read it from.
   */
  internal fun symbolOf(entry: Entry, document: DesignDocumentV1): Symbol? {
    val system = entry.system
    val id = entry.componentId
    // Exactly one, because the file *is* the symbol. None means the export published a design by
    // mistake; more than one means nothing in the file says which of them the entry names, and
    // guessing would make the answer depend on map order.
    val declared = document.components
    if (declared.size != 1) {
      onLog(
        "serve: $system's component $id declares ${declared.size} components; a symbol file " +
          "declares exactly one"
      )
      return null
    }
    val (declaredId, component) = declared.entries.single()
    // The id in the index and the id in the file have to agree. The design library learned to cope
    // with a stale export publishing an entry for A whose document carries B; here it is refused
    // instead, because a symbol is referenced by id and a mismatch would let a design record a
    // reference that resolves to different content than the one it was shown.
    if (declaredId != id) {
      onLog("serve: $system publishes component $id whose file declares `$declaredId` instead")
      return null
    }
    val root = component.root
    if (root !in document.nodes) {
      onLog("serve: $system's component $id names a body root `$root` its file does not carry")
      return null
    }

    val body = linkedMapOf<String, DesignNodeV1>()
    val missing = mutableListOf<String>()
    val placements = mutableListOf<String>()
    fun walk(nodeId: String) {
      if (nodeId in body) return
      val node = document.nodes[nodeId]
      if (node == null) {
        missing += nodeId
        return
      }
      body[nodeId] = node
      if (node.componentId == COMPONENT_INSTANCE_ID) placements += nodeId
      node.slots.entries.sortedBy { it.key }.forEach { (_, children) -> children.forEach(::walk) }
    }
    walk(root)

    if (missing.isNotEmpty()) {
      onLog(
        "serve: $system's component $id has a body reaching ${missing.sorted().joinToString()}, " +
          "which its file does not carry"
      )
      return null
    }
    // The second honest rule. A symbol placing another symbol is a dependency the importing design
    // never named, so it is refused until there is an import graph to name it in.
    if (placements.isNotEmpty()) {
      onLog(
        "serve: $system's component $id places another component at " +
          "${placements.sorted().joinToString()}; a published symbol uses only its catalog's " +
          "components"
      )
      return null
    }

    return Symbol(
      entry = entry,
      component = component,
      nodes = body,
      digest = digestOf(component, body),
      catalogPin = document.catalogPin,
    )
  }

  private fun CachedIndex.isStale(): Boolean = clock() - readAt >= ttlMillis

  private fun read(
    catalog: ServeUiBuilderDesignLibrary.Coordinate,
    path: String,
    maxBytes: Long,
  ): ByteArray? =
    ServeUiBuilderProjectFiles.read(
      system = catalog.system,
      source = catalog.source,
      path = path,
      maxBytes = maxBytes,
      fetch = fetch,
      onLog = onLog,
    )

  private fun parseIndex(system: String, body: String): List<Entry> {
    val root = json.parseToJsonElement(body).jsonObject
    val schema = root["schema"]?.jsonPrimitive?.content
    require(schema == INDEX_SCHEMA) { "expected schema $INDEX_SCHEMA, got ${schema ?: "none"}" }
    val seen = mutableSetOf<String>()
    return root["components"]?.jsonArray.orEmpty().mapNotNull { element ->
      val entry = element.jsonObject
      val componentId = entry.text("id") ?: return@mapNotNull null
      // A published id becomes a palette symbol and reaches a URL path, so the ones that can be
      // neither are refused here rather than discovered at the second step.
      if (!COMPONENT_ID.matches(componentId)) {
        onLog("serve: $system publishes a component with an unusable id `$componentId`")
        return@mapNotNull null
      }
      if (!seen.add(componentId)) {
        onLog("serve: $system publishes `$componentId` twice; keeping the first")
        return@mapNotNull null
      }
      val file = entry.text("file") ?: "$componentId.json"
      // Joined onto a fetch URL, so it stays one flat name under the components directory rather
      // than anything that could climb out of it.
      if (!COMPONENT_FILE.matches(file)) {
        onLog("serve: $system publishes `$componentId` with an unusable file `$file`")
        return@mapNotNull null
      }
      Entry(
        system = system,
        componentId = componentId,
        title = entry.text("title") ?: componentId,
        description = entry.text("description"),
        file = file,
      )
    }
  }

  private fun JsonObject.text(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

  companion object {
    /** Where a project keeps the components it publishes, relative to its root. */
    const val COMPONENTS_DIR: String = "ui-builder/components"

    const val INDEX_PATH: String = "$COMPONENTS_DIR/index.json"

    const val INDEX_SCHEMA: String = "compose-ui-builder-component-index/v1"

    /** How a project symbol is named once it reaches a palette. */
    fun paletteId(componentId: String): String = "$PALETTE_PREFIX/$componentId"

    const val PALETTE_PREFIX: String = "project"

    /**
     * A symbol's content digest: what an importing design records alongside the id, and what tells
     * it later that the library moved underneath it.
     *
     * Over the component and its body only — not the file — so reformatting the file, renaming the
     * design around the symbol, or bumping the document's revision does not read as drift. Keys are
     * sorted at every level before encoding, so two files that differ only in the order they wrote
     * their properties produce one digest; a real edit to a property, a slot or a modifier produces
     * another.
     */
    fun digestOf(component: DesignComponentV1, nodes: Map<String, DesignNodeV1>): String {
      val canonical =
        JsonObject(
          mapOf(
            "component" to
              canonicalise(json.encodeToJsonElement(DesignComponentV1.serializer(), component)),
            "nodes" to
              JsonObject(
                nodes.entries
                  .sortedBy { it.key }
                  .associate { (id, node) ->
                    id to canonicalise(json.encodeToJsonElement(DesignNodeV1.serializer(), node))
                  }
              ),
          )
        )
      val bytes = json.encodeToString(JsonObject.serializer(), canonical).toByteArray()
      return "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** The same JSON with every object's keys in sorted order, at every depth. */
    private fun canonicalise(element: JsonElement): JsonElement =
      when (element) {
        is JsonObject ->
          JsonObject(
            element.entries.sortedBy { it.key }.associate { it.key to canonicalise(it.value) }
          )
        is JsonArray -> JsonArray(element.map(::canonicalise))
        else -> element
      }

    /** The wire's own id for a node that places a component. */
    private const val COMPONENT_INSTANCE_ID = "design/component-instance"

    private const val MAX_INDEX_BYTES: Long = 256L * 1024
    private const val MAX_DOCUMENT_BYTES: Long = 8L * 1024 * 1024

    /** The same shape the design index and the create route already require of an id. */
    private val COMPONENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val COMPONENT_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")

    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = false
      encodeDefaults = false
    }
  }
}
