package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The **id → branch-path** maps a pinned (`?at=<sha>`) request resolves against, read from the
 * catalog's own manifests at that commit.
 *
 * The loaded catalog's map describes the branch tip, and is wrong for a pin in two ways: a
 * **render** id is derived from its path ([ServeCatalogStore.previewIdFor]), so a renamed
 * component's old id resolves nowhere; a **reference** declares its id independently of its path,
 * so the tip resolves it to a path that commit never had.
 *
 * Both manifests are small and immutable per sha, so each commit's maps are memoised. Fail-soft: an
 * unreadable manifest yields empty maps and the caller falls back to the tip's mapping.
 */
class ServePinnedManifest(
  /** Reads one published manifest at one commit. Supplied by [ServeCatalogStore]. */
  private val fetch: (commit: String, file: String) -> ByteArray?,
  private val maxCommits: Int = MAX_COMMITS,
) {

  /**
   * One commit's published layout.
   *
   * Each lane records **whether its manifest was read**, separately from what it contained, because
   * the two answer different questions and only one of them licenses a fallback. "The manifest says
   * this revision had no such id" is an answer — the asset was not published then, and the request
   * is a 404. "I could not read the manifest" is an absence of one, and there the tip's map is
   * better than nothing. Collapsing them would let a pin serve a file that merely happens to exist
   * at that commit under today's path, i.e. claim an asset was published in a revision whose own
   * manifest omits it.
   */
  data class Paths(
    /** Preview id → its baked render's path on the branch. */
    val renders: Map<String, String>,
    /** Design-reference id → its canonical raster's path on the branch. */
    val references: Map<String, String>,
    /**
     * Preview id → its component, when that revision's catalog named one, so a permalink to a
     * since-renamed preview can still be labelled.
     */
    val labels: Map<String, String> = emptyMap(),
    /** Preview id → the caption that revision published for it. See [CatalogEntries.captions]. */
    val captions: Map<String, String> = emptyMap(),
    /** Preview id → the baked light/dark theme recorded by that revision's image entry. */
    val themes: Map<String, String> = emptyMap(),
    /** Whether `catalog.json` was fetched and parsed at this commit. */
    val catalogRead: Boolean = false,
    /** Whether `references/index.json` was fetched and parsed at this commit. */
    val referencesRead: Boolean = false,
  ) {
    val isEmpty: Boolean
      get() = renders.isEmpty() && references.isEmpty()

    companion object {
      /** Nothing was read — every lookup falls back to the tip's map. */
      val NONE = Paths(emptyMap(), emptyMap())
    }
  }

  private val byCommit = java.util.concurrent.ConcurrentHashMap<String, Paths>()

  /**
   * [commit]'s published layout, fetched once and remembered. Misses are cached as [Paths.NONE] so
   * a page of broken pinned images doesn't refetch per image. At capacity an arbitrary entry is
   * dropped: pinned traffic is a long tail with no useful recency order.
   */
  fun forCommit(commit: String): Paths {
    val pin = ServeCatalogRevision.normalize(commit) ?: return Paths.NONE
    // `computeIfAbsent` so concurrent requests for one commit (comparison panels, a pinned grid)
    // fetch once. Eviction stays outside the mapping function, which must not touch the map it
    // computes into.
    val paths =
      byCommit.computeIfAbsent(pin) {
        // One read of catalog.json, two maps out of it — the paths a pinned asset resolves
        // through and the labels a pinned page needs to name a preview the tip no longer lists.
        val catalog = read(pin, ServeCatalogRevision.CATALOG_FILE, ::parseCatalog)
        val references = read(pin, ServeCatalogRevision.REFERENCES_FILE, ::parseReferences)
        Paths(
          renders = catalog?.paths.orEmpty(),
          references = references.orEmpty(),
          labels = catalog?.labels.orEmpty(),
          captions = catalog?.captions.orEmpty(),
          themes = catalog?.themes.orEmpty(),
          catalogRead = catalog != null,
          referencesRead = references != null,
        )
      }
    if (byCommit.size > maxCommits) {
      synchronized(byCommit) { byCommit.keys.firstOrNull { it != pin }?.let(byCommit::remove) }
    }
    return paths
  }

  /** Null when the manifest could not be read at all — distinct from "read, and it lists none". */
  private fun <T> read(commit: String, file: String, parse: (String) -> T?): T? = runCatching {
    fetch(commit, file)?.toString(Charsets.UTF_8)?.let(parse)
  }
    .getOrNull()

  companion object {

    /**
     * Commits remembered per catalog host: a de-duplicator across one page's assets, not an
     * archive.
     */
    private const val MAX_COMMITS = 4

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * The eligibility rule [ServeCatalogStore] plans an image by: inside `images/`, a PNG, no
     * traversal. The loader's `maxImages` cap is deliberately not mirrored, since it is a property
     * of the server, not the revision.
     */
    private fun isServable(path: String): Boolean =
      path.startsWith("${ServeCatalogStore.IMAGES_DIR}/") &&
        path.endsWith(".png") &&
        ".." !in path.split("/")

    /**
     * `catalog.json` → preview id → image path, keyed exactly as the loader keys the live catalog
     * ([ServeCatalogStore.previewIdFor]). Tolerant: a malformed component or image is skipped,
     * since the file may come from an older CLI.
     */
    /** What one revision's `catalog.json` says, read in a single pass. */
    data class CatalogEntries(
      /** Preview id → image path. */
      val paths: Map<String, String>,
      /** Preview id → the component that declared it, where the catalog names one. */
      val labels: Map<String, String>,
      /** Preview id → the caption that component authored, where the catalog carries one. */
      val captions: Map<String, String>,
      /** Preview id → the explicit baked theme on the winning image entry. */
      val themes: Map<String, String>,
    )

    fun parseCatalog(json: String): CatalogEntries? {
      val components =
        runCatching { JSON.parseToJsonElement(json).jsonObject["components"]?.jsonArray }
          .getOrNull() ?: return null
      val paths = LinkedHashMap<String, String>()
      val labels = LinkedHashMap<String, String>()
      val captions = LinkedHashMap<String, String>()
      val themes = LinkedHashMap<String, String>()
      for (component in components) {
        val obj = runCatching { component.jsonObject }.getOrNull() ?: continue
        val componentId = runCatching {
          obj["componentId"]?.jsonPrimitive?.content
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
        val caption = runCatching {
          obj["caption"]?.jsonPrimitive?.content
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
        val images = runCatching { obj["images"]?.jsonArray }.getOrNull() ?: continue
        for (image in images) {
          val path =
            runCatching { image.jsonObject["path"]?.jsonPrimitive?.content }
              .getOrNull()
              ?.takeIf(::isServable) ?: continue
          // LAST declaration wins, matching the live loader, so a pin resolves id collisions to the
          // same pixels the catalog served. The eligibility filter must run first for the same
          // reason: the loader never maps rejected entries, so one must not overwrite a served one
          // here.
          val id = ServeCatalogStore.previewIdFor(path)
          paths[id] = path
          // The label follows the winning path, even when the winner has no component name, so a
          // render is never attributed to the loser's component.
          if (componentId != null) labels[id] = componentId else labels.remove(id)
          // Likewise the caption.
          if (caption != null) captions[id] = caption else captions.remove(id)
          val theme = runCatching {
            image.jsonObject["theme"]?.jsonPrimitive?.content
          }
            .getOrNull()
            ?.takeIf { it == "light" || it == "dark" }
          if (theme != null) themes[id] = theme else themes.remove(id)
        }
      }
      return CatalogEntries(paths, labels, captions, themes)
    }

    /** `references/index.json` → reference id → the canonical raster's path on the branch. */
    fun parseReferences(json: String): Map<String, String>? {
      val references =
        runCatching { JSON.parseToJsonElement(json).jsonObject["references"]?.jsonArray }
          .getOrNull() ?: return null
      val paths = LinkedHashMap<String, String>()
      for (reference in references) {
        val obj = runCatching { reference.jsonObject }.getOrNull() ?: continue
        val id =
          runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: continue
        val path =
          runCatching { obj["raster"]?.jsonObject?.get("path")?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: continue
        // FIRST declaration wins here, mirroring the reference importer (`seen.add(reference.id)`);
        // each lane mirrors its own loader.
        paths.putIfAbsent(id, path)
      }
      return paths
    }
  }
}
