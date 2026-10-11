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
 * The components a project shares between its own designs, so a design can *reference* a subtree
 * rather than copy it. Same convention and reader as [ServeUiBuilderDesignLibrary]:
 * ```text
 * ui-builder/components/index.json     the manifest: which components this project publishes
 * ui-builder/components/<file>.json    one component symbol per file
 * ```
 *
 * A symbol file is an ordinary [DesignDocumentV1] with exactly one `components` entry, so it needs
 * no new wire type, validator or contracts release.
 *
 * - **Referenced, not copied.** Each symbol carries a content [Symbol.digest]; an importer records
 *   it and reports a moved library as drift.
 * - **Pinned-catalog components only.** A body placing another project symbol is refused here
 *   (cross-symbol composition would need an import graph and cycle checks); catalog membership is
 *   checked at import against [Symbol.catalogPin].
 *
 * Refusals are per symbol: an unusable file is logged and dropped, the rest are still offered.
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
   * One published symbol, read and checked. [nodes] is the body reachable from the component's
   * root, not the whole file, which may carry a surrounding frame.
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
    /**
     * Null when the read failed (unreachable source or unparseable index). Cached as a failure, not
     * as "publishes nothing", so a briefly unreachable branch isn't reported to importers as a
     * deletion.
     */
    val entries: IndexRead?,
  )

  private val indexes = ConcurrentHashMap<String, CachedIndex>()

  /**
   * Every published component across [catalogs] in catalog order, one per system and id. First
   * publisher wins, matching the symbol route, which lets a component moved into the project's
   * repository shadow the host's copy ([ServeUiBuilderComponentStore]).
   */
  fun list(catalogs: List<ServeUiBuilderDesignLibrary.Coordinate>): List<Entry> =
    catalogs.flatMap(::index).distinctBy { it.system to it.componentId }

  /**
   * A parsed index: what it offers, and ids it named but won't offer. [rejected] keeps "still named
   * but broken" distinct from "removed" for drift reporting.
   */
  data class IndexRead(val entries: List<Entry>, val rejected: Set<String> = emptySet())

  /**
   * One catalog's published components. Unlocked: concurrent cold reads are idempotent single round
   * trips.
   */
  fun index(catalog: ServeUiBuilderDesignLibrary.Coordinate): List<Entry> =
    readIndex(catalog)?.entries.orEmpty()

  /**
   * The same read, keeping what [index] flattens: null means could not read, [IndexRead.rejected]
   * means named but not offerable. The drift report needs both to avoid reporting deletions that
   * didn't happen.
   */
  fun readIndex(catalog: ServeUiBuilderDesignLibrary.Coordinate): IndexRead? {
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
      if (bytes == null) null
      else
        runCatching { parseIndex(catalog.system, bytes.toString(Charsets.UTF_8)) }
          .getOrElse {
            // Logged once per read: an unreadable index is otherwise indistinguishable from
            // publishing nothing.
            onLog(
              "serve: ${catalog.system} publishes a component index that is not readable " +
                "(${it.message})"
            )
            null
          }
    // Failures are cached too (else an unreachable branch is refetched per request), kept as
    // failures rather than empty lists.
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
   * The symbol behind an already-resolved entry. Local directory sources are uncached, so metadata
   * and body must come from the same read.
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
   * The whole one-component document behind an entry (environment included), for moving a host-held
   * component into a repository. Null for anything [symbol] would refuse.
   */
  fun document(catalog: ServeUiBuilderDesignLibrary.Coordinate, entry: Entry): DesignDocumentV1? {
    val bytes =
      runCatching { read(catalog, "$COMPONENTS_DIR/${entry.file}", MAX_DOCUMENT_BYTES) }.getOrNull()
        ?: return null
    val document =
      runCatching { json.decodeFromString<DesignDocumentV1>(bytes.toString(Charsets.UTF_8)) }
        .getOrNull() ?: return null
    return document.takeIf { symbolOf(entry, it) != null }
  }

  /**
   * The checked symbol a document publishes, or null with a logged reason. Internal for direct
   * testing.
   */
  internal fun symbolOf(entry: Entry, document: DesignDocumentV1): Symbol? {
    val system = entry.system
    val id = entry.componentId
    // Exactly one component: none means a design was published by mistake; several leave the entry
    // ambiguous.
    val declared = document.components
    if (declared.size != 1) {
      onLog(
        "serve: $system's component $id declares ${declared.size} components; a symbol file " +
          "declares exactly one"
      )
      return null
    }
    val (declaredId, component) = declared.entries.single()
    // Index id and file id must agree; a mismatch would let a design reference resolve to different
    // content than it was shown.
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
    val cyclic = mutableListOf<String>()
    // Two sets: `body` is what's collected (a node reached twice via two branches is done),
    // `onStack` is the current walk (reaching an open node is a cycle). Conflating them let a slot
    // pointing at its ancestor pass as a finished subtree.
    val onStack = linkedSetOf<String>()
    fun walk(nodeId: String) {
      if (nodeId in onStack) {
        cyclic += nodeId
        return
      }
      if (nodeId in body) return
      val node = document.nodes[nodeId]
      if (node == null) {
        missing += nodeId
        return
      }
      onStack += nodeId
      body[nodeId] = node
      if (node.componentId == COMPONENT_INSTANCE_ID) placements += nodeId
      node.slots.entries.sortedBy { it.key }.forEach { (_, children) -> children.forEach(::walk) }
      onStack -= nodeId
    }
    walk(root)

    if (cyclic.isNotEmpty()) {
      onLog(
        "serve: $system's component $id has a body that contains itself at " +
          "${cyclic.sorted().joinToString()}; a symbol is a tree"
      )
      return null
    }

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

  private fun parseIndex(system: String, body: String): IndexRead {
    val root = json.parseToJsonElement(body).jsonObject
    val schema = root["schema"]?.jsonPrimitive?.content
    require(schema == INDEX_SCHEMA) { "expected schema $INDEX_SCHEMA, got ${schema ?: "none"}" }
    val seen = mutableSetOf<String>()
    // Ids this index names and this host will not offer. Kept so that "still named, and broken"
    // stays distinguishable from "no longer named" for anything that has to tell the two apart.
    val rejected = mutableSetOf<String>()
    val entries =
      root["components"]?.jsonArray.orEmpty().mapNotNull { element ->
        val entry = element.jsonObject
        val componentId = entry.text("id") ?: return@mapNotNull null
        // A published id becomes a palette symbol and reaches a URL path, so the ones that can be
        // neither are refused here rather than discovered at the second step.
        if (!COMPONENT_ID.matches(componentId)) {
          onLog("serve: $system publishes a component with an unusable id `$componentId`")
          rejected += componentId
          return@mapNotNull null
        }
        // Not rejected: the first entry under this id was accepted, so the id *is* offered.
        if (!seen.add(componentId)) {
          onLog("serve: $system publishes `$componentId` twice; keeping the first")
          return@mapNotNull null
        }
        val file = entry.text("file") ?: "$componentId.json"
        // Joined onto a fetch URL, so it stays one flat name under the components directory rather
        // than anything that could climb out of it.
        if (!COMPONENT_FILE.matches(file)) {
          onLog("serve: $system publishes `$componentId` with an unusable file `$file`")
          rejected += componentId
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
    // Minus what is actually offered, not what was seen (ids reach `seen` before their file name is
    // checked).
    return IndexRead(
      entries = entries,
      rejected = rejected - entries.map { it.componentId }.toSet(),
    )
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
     * A symbol's content digest, recorded by importers to detect drift. Over the component and its
     * body only, with keys sorted at every level, so reformatting or renaming the surrounding file
     * isn't drift but a real edit is.
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
