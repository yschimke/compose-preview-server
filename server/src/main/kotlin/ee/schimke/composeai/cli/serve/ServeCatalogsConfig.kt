package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The served catalog set as data: the operator's `catalogs.json`, living outside the image so
 * publishing a catalog is a config edit or an admin API call ([ServeCatalogAdmin], which rewrites
 * this file so runtime registrations survive restarts).
 *
 * ```json
 * {
 *   "groups": [
 *     { "id": "design-systems", "heading": "Design Systems", "noun": "design system(s)" }
 *   ],
 *   "catalogs": [
 *     { "system": "compose-m3", "repo": "yschimke/compose-ai-tools", "group": "design-systems" },
 *     { "system": "cadence", "repo": "yschimke/cadence", "listed": false }
 *   ]
 * }
 * ```
 *
 * A [Group] is claimed, never assumed: a catalog renders under its heading only when its bytes came
 * from a repo the entry names ([Entry.repo] plus [Entry.attributionRepos]), so a third party can't
 * present a catalog as an official design system by reusing its id.
 */
@Serializable
data class ServeCatalogsConfig(
  /** Front-page sections a catalog entry may claim by [Group.id]. */
  val groups: List<Group> = emptyList(),
  /** The catalogs to serve, in front-page order. */
  val catalogs: List<Entry> = emptyList(),
  /**
   * Top-level sites ([ServeSites]): hostnames serving one of the [catalogs] as the whole box.
   * Config, so a new vhost needs no rebuild.
   */
  val sites: List<Site> = emptyList(),
  /**
   * The pinned UI-builder editor ([ServeUiBuilderEditor]); null uses the bundled one. Lets an
   * editor fix ship without a server release; rollback is removing it.
   */
  val editor: EditorPin? = null,
  /**
   * The UI builder's catalog settings ([UiBuilderSettings]), also editable via
   * `/admin/ui-builder/config`. Null keeps the `SERVE_UI_BUILDER_*` environment.
   */
  val uiBuilder: UiBuilderSettings? = null,
) {
  /**
   * UI-builder catalog settings as overrides of the environment, not a replacement: every field is
   * optional and an unnamed catalog keeps what `SERVE_UI_BUILDER_*` gives it, so migrating can't
   * drop one. [ServeUiBuilderSettings] resolves the two.
   */
  @Serializable
  data class UiBuilderSettings(
    /**
     * Per-catalog overrides by builder catalog id, replacing that catalog's share of
     * `SERVE_UI_BUILDER_CATALOGS`, `…_PUBLISHED_CATALOGS`, `…_CATALOG_OWNERSHIP` and
     * `…_NATIVE_CATALOGS`.
     */
    val catalogs: Map<String, UiBuilderCatalogSettings> = emptyMap(),
    /**
     * Component packs (`SERVE_UI_BUILDER_PACKS`), served catalog → platform; null withdraws an
     * environment pack.
     */
    val packs: Map<String, String?> = emptyMap(),
    /** The Wear widget player (`SERVE_UI_BUILDER_WIDGET_PLAYER`); null keeps the environment's. */
    val widgetPlayer: String? = null,
  )

  /** One builder catalog's overrides; a null field keeps what the environment says for it. */
  @Serializable
  data class UiBuilderCatalogSettings(
    /** Offered in the builder (`SERVE_UI_BUILDER_CATALOGS`). False withdraws it. */
    val serve: Boolean? = null,
    /**
     * Defined by its repository's published `ui-builder.json` rather than the built-in Kotlin
     * catalog (`SERVE_UI_BUILDER_PUBLISHED_CATALOGS`).
     */
    val published: Boolean? = null,
    /** Seeds and exports from its own declaration (`SERVE_UI_BUILDER_CATALOG_OWNERSHIP`). */
    val owned: Boolean? = null,
    /**
     * Served catalog its designs compile against for native preview
     * (`SERVE_UI_BUILDER_NATIVE_CATALOGS`); empty removes the mapping.
     */
    val nativeCatalog: String? = null,
    /**
     * Report at startup what owning this catalog would change, without changing what is served
     * (`CatalogCutoverShadow`). Only for a published, not-yet-owned catalog; file-only, as a step
     * toward [owned].
     */
    val shadow: Boolean? = null,
  )

  /**
   * One pinned editor archive: compose-ui-builder's [version], the archive's [sha256], and an
   * optional [url] for an archive hosted somewhere other than its GitHub release.
   */
  @Serializable
  data class EditorPin(val version: String, val sha256: String, val url: String? = null)

  /** One front-page section: its stable [id], the [heading] shown, and its count [noun]. */
  @Serializable
  data class Group(
    val id: String,
    val heading: String,
    val noun: String = DEFAULT_NOUN,
    /**
     * Front-page section order, highest first, ties by first appearance; default 0 keeps the old
     * layout. Decoupled from catalog order, since admin-published catalogs are appended and would
     * otherwise push their whole section last (#4601). Only declared groups carry one; "Other"
     * stays last.
     */
    val priority: Int = 0,
  )

  /** One published catalog. */
  @Serializable
  data class Entry(
    /** Catalog id — the `/<system>/` path segment and the `design-artifacts/<system>` branch. */
    val system: String,
    /** `<owner>/<repo>` the delivery branch lives in; null ⇒ the server's `--catalog-repo`. */
    val repo: String? = null,
    /** On the front-page index. False ⇒ served at `/<system>/` but off the front door. */
    val listed: Boolean = true,
    /** [Group.id] this catalog is published under; null ⇒ grouped by its source repo's owner. */
    val group: String? = null,
    /**
     * `<owner>/<repo>` the previews were rendered from, when not [repo] (an import served from a
     * staging repo). Groups the card with that owner and tells readers whose work it is.
     * Attribution only: never widens [group] claims or trust.
     */
    val importedFrom: String? = null,
    /**
     * Extra repos allowed to satisfy the [group] claim, for a catalog fetched from somewhere other
     * than where it is authored (e.g. a fork's preview branches). Only repos you trust to publish
     * under the heading.
     */
    val attributionRepos: List<String> = emptyList(),
    /**
     * Startup load order, highest first, ties by position; default 0. The initial fetch is one
     * sequential pass and admin-published catalogs are appended ([CatalogLoadTracker.add]), so
     * without this the most important catalogs could come back last (#4231). Changes only fetch
     * order, not what is served; [isDesignSystem] is the one guarantee ([DESIGN_SYSTEMS_GROUP]).
     */
    val loadPriority: Int = 0,
  ) {
    /**
     * Claims [DESIGN_SYSTEMS_GROUP]: fetched first and required before ready. Read from the claimed
     * id rather than the resolved group, so deleting a heading can't silently stop it gating
     * readiness.
     */
    val isDesignSystem: Boolean
      get() = group == DESIGN_SYSTEMS_GROUP
  }

  /** One top-level site: [host] serves [system], which must already be one of [catalogs]. */
  @Serializable data class Site(val host: String, val system: String)

  /**
   * The declared group for [Entry.group], or null when the entry claims none / names an unknown.
   */
  fun groupFor(entry: Entry): Group? = entry.group?.let { id -> groups.firstOrNull { it.id == id } }

  /**
   * Human-readable problems (unknown groups, malformed ids or repos, duplicates); empty ⇒ usable.
   * Reported rather than thrown so valid entries still load.
   */
  fun problems(): List<String> = buildList {
    groups
      .groupBy { it.id }
      .filterValues { it.size > 1 }
      .keys
      .forEach { add("duplicate group id '$it'") }
    catalogs
      .groupBy { it.system }
      .filterValues { it.size > 1 }
      .keys
      .forEach { add("duplicate catalog system '$it'") }
    for (entry in catalogs) {
      validateEntry(entry)?.let { add(it) }
      if (entry.group != null && groups.none { it.id == entry.group }) {
        add("catalog '${entry.system}' names unknown group '${entry.group}'")
      }
    }
    editor?.let { pin -> validateEditor(pin)?.let { add(it) } }
    val served = catalogs.map { it.system }.toSet()
    val seenHosts = mutableSetOf<String>()
    for (site in sites) {
      val host = ServeSites.normalizeHost(site.host)
      when {
        host == null -> add("site host '${site.host}' is not a hostname")
        !seenHosts.add(host) -> add("duplicate site host '$host'")
      }
      if (site.system !in served) {
        add("site '${site.host}' names '${site.system}', which no catalog entry serves")
      }
    }
  }

  /** The configured [sites] as a lookup, dropping entries [problems] already reported. */
  fun siteMap(onProblem: (String) -> Unit = {}): ServeSites =
    ServeSites.of(
      sites.map { it.host to it.system },
      knownSystems = catalogs.map { it.system }.toSet(),
      onProblem = onProblem,
    )

  companion object {
    /** The count noun a section uses when its group declares none. */
    const val DEFAULT_NOUN: String = "catalog(s)"

    /**
     * The group whose catalogs this box exists to serve: fetched first
     * ([CatalogLoadTracker.loadOrder]) and required before ready
     * ([CatalogLoadTracker.Config.designSystem]), so a rolling update never routes to a replica
     * with empty design-system grids. A group id so the operator's `catalogs.json` is the only
     * list.
     */
    const val DESIGN_SYSTEMS_GROUP: String = "design-systems"

    val EMPTY: ServeCatalogsConfig = ServeCatalogsConfig()

    private val JSON = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      prettyPrintIndent = "  "
      encodeDefaults = true
    }

    /**
     * Catalog ids are URL segments and branch suffixes, so they stay in a conservative slug
     * alphabet.
     */
    private val SYSTEM_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val REPO_RE = Regex("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,64}")

    fun parse(text: String): ServeCatalogsConfig = JSON.decodeFromString(serializer(), text)

    fun encode(config: ServeCatalogsConfig): String =
      JSON.encodeToString(serializer(), config) + "\n"

    /**
     * Group ids are stable slugs; headings and nouns are rendered (escaped by [ServeWeb.section])
     * and length-capped.
     */
    private val GROUP_ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** Why [group] is unusable as a front-page section, or null when it's well-formed. */
    fun validateGroup(group: Group): String? =
      when {
        !GROUP_ID_RE.matches(group.id) -> "invalid group id '${group.id}'"
        group.heading.isBlank() -> "group '${group.id}' needs a heading"
        group.heading.length > 120 -> "group '${group.id}' heading is too long (max 120)"
        group.noun.isBlank() -> "group '${group.id}' needs a noun"
        group.noun.length > 60 -> "group '${group.id}' noun is too long (max 60)"
        else -> null
      }

    /**
     * A compose-ui-builder release version; it reaches a URL path and directory name, so the
     * alphabet is narrow.
     */
    private val EDITOR_VERSION_RE = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.]{1,32})?")
    private val SHA256_RE = Regex("[0-9a-fA-F]{64}")

    /** Why [pin] is unusable, or null when it's well-formed. */
    fun validateEditor(pin: EditorPin): String? =
      when {
        !EDITOR_VERSION_RE.matches(pin.version) -> "invalid editor version '${pin.version}'"
        !SHA256_RE.matches(pin.sha256) -> "editor ${pin.version} needs a 64-hex-digit sha256"
        pin.url != null && !pin.url.startsWith("https://") ->
          "editor ${pin.version} url must be https"
        else -> null
      }

    /** Why [entry] is unusable, or null when it's well-formed. */
    fun validateEntry(entry: Entry): String? =
      when {
        !SYSTEM_RE.matches(entry.system) -> "invalid catalog system id '${entry.system}'"
        // A slug may still be a reserved top-level route, which the registry refuses
        // ([ServeSessionRegistry.register]); report it here as an ordinary malformed entry instead
        // of a later runtime error.
        entry.system in ServeSites.RESERVED_SYSTEMS ->
          "catalog system id '${entry.system}' is one of the server's own routes"
        entry.repo != null && !REPO_RE.matches(entry.repo) ->
          "catalog '${entry.system}' has an invalid repo '${entry.repo}'"
        entry.attributionRepos.any { !REPO_RE.matches(it) } ->
          "catalog '${entry.system}' has an invalid attribution repo"
        else -> null
      }
  }
}

/**
 * The `catalogs.json` file: read at startup, rewritten by the admin API. Plain JSON on a mounted
 * path so operators can edit, diff and back it up.
 */
class ServeCatalogsConfigFile(
  private val path: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  val displayPath: String
    get() = path.toString()

  /** True when the file exists (an absent file is not an error — it reads as empty). */
  fun exists(): Boolean = fileSystem.exists(path)

  /** Parse the file; an absent file is [ServeCatalogsConfig.EMPTY]. Throws on malformed JSON. */
  fun load(): ServeCatalogsConfig {
    if (!fileSystem.exists(path)) return ServeCatalogsConfig.EMPTY
    return ServeCatalogsConfig.parse(fileSystem.read(path) { readUtf8() })
  }

  /**
   * Write [config] via a temp file and [FileSystem.atomicMove], so a crash can't leave a truncated
   * config.
   */
  fun save(config: ServeCatalogsConfig) {
    val parent = path.parent
    parent?.let { fileSystem.createDirectories(it) }
    val tmp = if (parent != null) parent / "${path.name}.tmp" else "${path.name}.tmp".toPath()
    fileSystem.write(tmp) { writeUtf8(ServeCatalogsConfig.encode(config)) }
    fileSystem.atomicMove(tmp, path)
  }

  /**
   * [load] → [mutate] → [save] as one critical section, so concurrent admin edits can't silently
   * lose updates. The lock is on the file, shared by [ServeCatalogAdmin] and [ServeSiteAdmin];
   * callers share one instance per path.
   */
  fun update(mutate: (ServeCatalogsConfig) -> ServeCatalogsConfig): ServeCatalogsConfig =
    synchronized(this) { mutate(load()).also { save(it) } }
}
