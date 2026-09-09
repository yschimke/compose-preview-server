package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The designs a project is working on, offered on this host so one can be opened and carried on
 * with rather than rebuilt.
 *
 * This is for a team leaning on the builder while a screen is still being designed: somebody
 * prototypes, exports the design into the app's own repository beside the code, and the next person
 * — or the next session, or an agent — opens it here and keeps going. The design in the repository
 * is the thing under review and the thing that survives; the design on this host is where it is
 * being worked on.
 *
 * ## The convention
 *
 * Either way a project's designs reach the server, they sit at the same two paths, so a team moves
 * between the two without rewriting anything:
 * ```text
 * ui-builder/designs/index.json      the manifest: which designs this project publishes
 * ui-builder/designs/<file>.json     one DesignDocumentV1 per design
 * ```
 *
 * They are read from one of two places, and the difference is which half of the loop a host is in:
 *
 * - **A directory** ([Source.Directory], `--ui-builder-designs`) — the app's own checkout, which is
 *   the prototyping case. The team edits designs here, exports them into the repository, and the
 *   server sees the new file on the next read: no publish step, no branch, no commit required to
 *   try something.
 * - **A branch** ([Source.Branch]) — a served catalog's `design-artifacts/<system>`, read the same
 *   way every other thing a catalog contributes is read. This is the case where the designs have
 *   settled enough to ship with the catalog, and it needs no per-catalog wiring at all: a catalog
 *   registered at runtime brings its designs with it.
 *
 * A project with no designs has no `index.json` — a missing file or a 404, whichever source it is —
 * which is an ordinary answer rather than an error anybody configures away.
 *
 * ## Documents, not operation logs
 *
 * The published file is a whole [DesignDocumentV1] — the shape `CreateDesignRequestV1` takes —
 * rather than the `compose-ui-builder-operations/v1-candidate` log this repository keeps its own
 * fixtures in. The two describe the same design and a project generates one from the other at
 * publish time (`scripts/ui-builder/design-sync.mjs` already holds both directions), but only one
 * of them can be *consumed* here: the reducer that replays an operation log lives in
 * `:ui-builder-export`, which `:server` deliberately does not depend on
 * ([`UI_BUILDER_PROJECT_BOUNDARY.md`](../../../../../../../../docs/design/UI_BUILDER_PROJECT_BOUNDARY.md)).
 * Publishing the document keeps that boundary and keeps the published artifact the same shape as
 * the API that receives it.
 *
 * ## Best-effort, and cached against the branch head
 *
 * Every read here is best-effort in the same sense the catalog machinery already means it: a
 * project whose index is missing, unreachable or malformed contributes no designs and takes nothing
 * away from the ones that do. A branch's index is cached against the marker the caller passes, so a
 * refreshed catalog invalidates its own entry — the load marker moves when the branch head does —
 * and a host that never refreshes still re-reads on [ttlMillis]. Neither the index nor a document
 * is ever fetched while rendering a page: the browse route asks, and one answer serves every viewer
 * until the branch moves.
 */
class ServeUiBuilderDesignLibrary(
  private val fetch: (url: String, maxBytes: Long) -> ByteArray?,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
) {

  /** Where one project's designs are read from. */
  sealed interface Source {
    /**
     * A checkout on this machine — the app's own repository, during the design phase.
     *
     * Not cached beyond a single read, because this is the half of the loop that changes under you:
     * somebody exports a design from the editor into their repo and expects to see it in the list,
     * not in five minutes.
     */
    data class Directory(val dir: File) : Source

    /** A served catalog's delivery branch, read through the same fetcher as everything else. */
    data class Branch(val repo: String, val branch: String) : Source
  }

  /**
   * One project to look in.
   *
   * [generation] is an opaque marker of the project's current content — for a catalog the server
   * passes the timestamp of its last load, which moves whenever the refresher re-fetches it. When
   * it changes the index is re-read; when a caller has no such marker it passes null and
   * [ttlMillis] alone governs. A [Source.Directory] is never cached at all.
   */
  data class Coordinate(
    val system: String,
    val source: Source,
    val generation: String? = null,
  ) {
    internal val cacheable: Boolean
      get() = source is Source.Branch
  }

  /** One design a project publishes. [file] is relative to [DESIGNS_DIR]. */
  data class Entry(
    val system: String,
    val designId: String,
    val title: String,
    val description: String?,
    val file: String,
    /**
     * What the project says this design is for — the issue, the frame, the pull request, the
     * thread, the design it continues — written into this host's links store when the design is
     * opened here.
     *
     * Optional and additive: an index published before this field existed carries none, and an
     * index that carries one still loads on a host that ignores it. Null when the entry omits it or
     * when what it carries is not a links record this host will keep, which is a fact about the
     * entry rather than a reason to drop the design.
     */
    val links: StoredLinks? = null,
  )

  private data class CachedIndex(
    val generation: String?,
    val readAt: Long,
    val entries: List<Entry>,
  )

  private val indexes = ConcurrentHashMap<String, CachedIndex>()

  /** Every published design across [catalogs], catalog order preserved. */
  fun list(catalogs: List<Coordinate>): List<Entry> = catalogs.flatMap(::index)

  /**
   * One catalog's published designs.
   *
   * Concurrent, because two viewers opening the browse screen at once reach this on different
   * request threads. Both may fetch a cold index; that is deliberate and harmless — the read is
   * idempotent and the results are equal — where a lock would put every viewer behind one HTTP
   * round trip to GitHub.
   */
  fun index(catalog: Coordinate): List<Entry> {
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
      .onFailure { onLog("serve: ${catalog.system} design index unreadable (${it.message})") }
      .getOrNull()
    val entries =
      if (bytes == null) emptyList()
      else
        runCatching { parseIndex(catalog.system, bytes.toString(Charsets.UTF_8)) }
          .getOrElse {
            // A malformed index is worth saying once per read rather than swallowing: the project
            // published something and it is not being offered, which is exactly the case an
            // operator cannot otherwise tell apart from publishing nothing.
            onLog(
              "serve: ${catalog.system} publishes a design index that is not readable (${it.message})"
            )
            emptyList()
          }
    if (catalog.cacheable)
      indexes[catalog.system] = CachedIndex(catalog.generation, clock(), entries)
    return entries
  }

  /**
   * The document behind one entry, or null when it cannot be read.
   *
   * Not cached: it is fetched once, at the moment somebody loads it, and the copy that matters
   * afterwards is the design the service now holds.
   */
  fun document(catalog: Coordinate, designId: String): DesignDocumentV1? =
    index(catalog).firstOrNull { it.designId == designId }?.let { document(catalog, it) }

  /**
   * The document behind an entry the caller already resolved.
   *
   * The overload exists so a caller that needs both the entry and its document reads the index
   * once. A local `--ui-builder-designs` source is deliberately uncached, so two reads can land on
   * either side of somebody exporting the project, and a caller that took its metadata from the
   * first read and its document from the second would attach one design's links to another's
   * document.
   */
  fun document(catalog: Coordinate, entry: Entry): DesignDocumentV1? {
    val designId = entry.designId
    val bytes =
      runCatching { read(catalog, "$DESIGNS_DIR/${entry.file}", MAX_DOCUMENT_BYTES) }.getOrNull()
        ?: return null
    return runCatching { json.decodeFromString<DesignDocumentV1>(bytes.toString(Charsets.UTF_8)) }
      .onFailure {
        onLog(
          "serve: ${catalog.system}'s design $designId is not a DesignDocumentV1 (${it.message})"
        )
      }
      .getOrNull()
  }

  /** Forget one catalog's index, so the next read goes back to the branch. */
  fun forget(system: String) {
    indexes.remove(system)
  }

  private fun CachedIndex.isStale(): Boolean = clock() - readAt >= ttlMillis

  /** One path from one project, whichever kind of source it is. */
  private fun read(catalog: Coordinate, path: String, maxBytes: Long): ByteArray? =
    when (val source = catalog.source) {
      is Source.Directory -> {
        // Resolved against the directory and then checked to be inside it: `file` is already
        // refused unless it is one flat name, and this is the second lock on the same door.
        val root = source.dir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) null
        else if (file.length() > maxBytes) {
          onLog("serve: ${catalog.system}'s $path is larger than $maxBytes bytes; ignoring it")
          null
        } else file.readBytes()
      }
      is Source.Branch ->
        fetch("https://raw.githubusercontent.com/${source.repo}/${source.branch}/$path", maxBytes)
    }

  private fun parseIndex(system: String, body: String): List<Entry> {
    val root = json.parseToJsonElement(body).jsonObject
    val schema = root["schema"]?.jsonPrimitive?.content
    require(schema == INDEX_SCHEMA) { "expected schema $INDEX_SCHEMA, got ${schema ?: "none"}" }
    val seen = mutableSetOf<String>()
    return root["designs"]?.jsonArray.orEmpty().mapNotNull { element ->
      val entry = element.jsonObject
      val designId = entry.text("id") ?: return@mapNotNull null
      // A published id reaches a URL path and, if it is loaded, becomes a design id on this host.
      // Refusing the ones that cannot be either is cheaper than discovering it at the second step.
      if (!DESIGN_ID.matches(designId)) {
        onLog("serve: $system publishes a design with an unusable id `$designId`")
        return@mapNotNull null
      }
      if (!seen.add(designId)) {
        onLog("serve: $system publishes `$designId` twice; keeping the first")
        return@mapNotNull null
      }
      val file = entry.text("file") ?: "$designId.json"
      // The file is joined onto a fetch URL, so it stays one flat name under the designs directory
      // rather than anything that could climb out of it.
      if (!DESIGN_FILE.matches(file)) {
        onLog("serve: $system publishes `$designId` with an unusable file `$file`")
        return@mapNotNull null
      }
      Entry(
        system = system,
        designId = designId,
        title = entry.text("title") ?: designId,
        description = entry.text("description"),
        file = file,
        links = entry.links(system, designId),
      )
    }
  }

  private fun JsonObject.text(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

  /**
   * The entry's `links` object, or null when it has none or publishes one this host will not keep.
   *
   * Read leniently and validated with the store's own rule, so the index cannot be a way around it:
   * an entry citing `javascript:` as its issue publishes a design with no links rather than a
   * design with a link nothing else on this host would have accepted.
   */
  private fun JsonObject.links(system: String, designId: String): StoredLinks? {
    val element = this["links"] as? JsonObject ?: return null
    val links =
      runCatching { json.decodeFromJsonElement(StoredLinks.serializer(), element) }
        .onFailure { onLog("serve: $system publishes `$designId` with unreadable links") }
        .getOrNull() ?: return null
    if (links.isEmpty) return null
    val refusal = ServeUiBuilderLinksStore.refusal(links)
    if (refusal != null) {
      onLog("serve: $system publishes `$designId` with links this host will not keep ($refusal)")
      return null
    }
    return links
  }

  companion object {
    /** Where a catalog project keeps the designs it publishes, relative to its branch root. */
    const val DESIGNS_DIR: String = "ui-builder/designs"

    const val INDEX_PATH: String = "$DESIGNS_DIR/index.json"

    const val INDEX_SCHEMA: String = "compose-ui-builder-design-index/v1"

    /** Five minutes: a design index changes when somebody publishes one, not on a page load. */
    const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000L

    private const val MAX_INDEX_BYTES: Long = 256L * 1024
    private const val MAX_DOCUMENT_BYTES: Long = 8L * 1024 * 1024

    /** The same shape `--ui-builder-catalogs` and the create route already require of an id. */
    private val DESIGN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val DESIGN_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")

    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = false
    }
  }
}
