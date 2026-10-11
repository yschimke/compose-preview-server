package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import okio.Path.Companion.toPath

/**
 * The UI builder's catalog settings, resolved from the box's environment and `catalogs.json`'s
 * `uiBuilder` block ([ServeCatalogsConfig.UiBuilderSettings]).
 *
 * The environment stays the baseline (only the operator can read the box's `.env`), so the block is
 * a set of overrides: a catalog it doesn't mention keeps what the environment gives it. One
 * variable maps to one field:
 *
 * | Environment                           | `catalogs.json` `uiBuilder`               |
 * |---------------------------------------|-------------------------------------------|
 * | `SERVE_UI_BUILDER_CATALOGS`           | `catalogs.<id>.serve`                     |
 * | `SERVE_UI_BUILDER_PUBLISHED_CATALOGS` | `catalogs.<id>.published`                 |
 * | `SERVE_UI_BUILDER_CATALOG_OWNERSHIP`  | `catalogs.<id>.owned`                     |
 * | `SERVE_UI_BUILDER_NATIVE_CATALOGS`    | `catalogs.<id>.nativeCatalog`             |
 * | `SERVE_UI_BUILDER_PACKS`              | `packs.<served catalog>` (null withdraws) |
 * | `SERVE_UI_BUILDER_WIDGET_PLAYER`      | `widgetPlayer`                            |
 * | (none: this file only)                | `catalogs.<id>.shadow`                    |
 *
 * Secrets and machine facts (`SERVE_UI_BUILDER_WEAR`, `…_STATE_DIR`, `…_COMPONENTS`, `…_HOST`, the
 * guidelines key) stay in the environment; other non-secret builder settings live in
 * `settings.json` ([ServeSettings]).
 *
 * A catalog the block starts serving with no `published` of its own is published when it is in
 * [ServeOptions.uiBuilderPublishedDefault], the deployment's list, which keeps catalog names out of
 * the server's Kotlin (`.github/scripts/ui-builder-catalog-literals.sh`).
 *
 * Contradictory settings are reported and narrowed rather than refusing to boot: a box must come up
 * on a config it wrote itself.
 */
object ServeUiBuilderSettings {

  /** The settings that decide what the builder serves, whichever source they came from. */
  data class Effective(
    val catalogs: Set<String>,
    /** Null ⇒ `all`, as `--ui-builder-published-catalogs` means it. */
    val publishedCatalogs: Set<String>?,
    val catalogOwnership: CatalogOwnership,
    val nativeCatalogs: Map<String, String>,
    val packs: Map<String, String>,
    val widgetPlayer: UiBuilderWidgetPlayer,
    /** What a newly served catalog defaults to publishing from; not itself a served setting. */
    val publishedDefault: Set<String> = emptySet(),
    /**
     * Catalogs this machine cannot serve, whatever the block says
     * ([ServeOptions.uiBuilderUnavailableCatalogs]); not itself a served setting.
     */
    val unavailable: Set<String> = emptySet(),
    /**
     * Catalogs reported on as if owned, while served as they are
     * ([ServeOptions.uiBuilderShadowCatalogs]).
     */
    val shadowCatalogs: Set<String> = emptySet(),
  ) {
    /** As `GET /admin/ui-builder/config` reports it. */
    fun describe(): ServeUiBuilderSettingsDto =
      ServeUiBuilderSettingsDto(
        catalogs = catalogs.sorted(),
        publishedCatalogs = publishedCatalogs?.sorted(),
        catalogOwnership = catalogOwnership.wireValue,
        nativeCatalogs = nativeCatalogs.toSortedMap(),
        packs = packs.toSortedMap(),
        widgetPlayer = widgetPlayer.flagValue,
        shadowCatalogs = shadowCatalogs.sorted(),
      )

    companion object {
      fun of(options: ServeOptions): Effective =
        Effective(
          catalogs = options.uiBuilderCatalogs,
          publishedCatalogs = options.uiBuilderPublishedCatalogs,
          catalogOwnership = options.uiBuilderCatalogOwnership,
          nativeCatalogs = options.uiBuilderNativeCatalogs,
          packs = options.uiBuilderPacks,
          widgetPlayer = options.uiBuilderWidgetPlayer,
          publishedDefault = options.uiBuilderPublishedDefault,
          unavailable = options.uiBuilderUnavailableCatalogs,
          shadowCatalogs = options.uiBuilderShadowCatalogs,
        )
    }
  }

  /** What [resolve] produced, and what it had to narrow to get there. */
  data class Resolution(val effective: Effective, val problems: List<String>)

  /**
   * Why [settings] cannot be accepted, or null when it can. The admin route refuses on these; a
   * file that holds them anyway (hand-edited) is narrowed by [resolve] instead.
   */
  fun validate(settings: ServeCatalogsConfig.UiBuilderSettings): String? {
    for ((id, catalog) in settings.catalogs) {
      if (!ServeCommandOptions.UI_BUILDER_CATALOG_ID.matches(id)) {
        return "invalid builder catalog id `$id`"
      }
      val native = catalog.nativeCatalog
      if (!native.isNullOrEmpty() && !ServeCommandOptions.UI_BUILDER_CATALOG_ID.matches(native)) {
        return "catalog `$id` names an invalid native catalog `$native`"
      }
      if (catalog.owned == true && catalog.published == false) {
        return "catalog `$id` is owned but not published: owning a catalog means serving it from " +
          "nothing but its published file"
      }
    }
    for ((pack, platform) in settings.packs) {
      if (!ServeCommandOptions.UI_BUILDER_CATALOG_ID.matches(pack)) {
        return "invalid pack catalog id `$pack`"
      }
      if (platform != null && platform.lowercase() !in ServeCommandOptions.UI_BUILDER_PLATFORMS) {
        return "pack `$pack` names an unknown platform `$platform`; expected one of " +
          ServeCommandOptions.UI_BUILDER_PLATFORMS.joinToString(", ")
      }
    }
    settings.widgetPlayer?.let { player ->
      if (UiBuilderWidgetPlayer.fromFlag(player) == null) {
        return "widgetPlayer must be one of " +
          UiBuilderWidgetPlayer.entries.joinToString(", ") { it.flagValue } +
          ", got `$player`"
      }
    }
    return null
  }

  /** [base], the environment's settings, with [settings] applied over them. */
  fun resolve(
    base: Effective,
    settings: ServeCatalogsConfig.UiBuilderSettings?,
  ): Resolution {
    if (settings == null) return Resolution(base, emptyList())
    val problems = mutableListOf<String>()

    val catalogs = base.catalogs.toMutableSet()
    for ((id, catalog) in settings.catalogs) {
      when (catalog.serve) {
        true ->
          if (id in base.unavailable) {
            // A machine-level opt-out (SERVE_UI_BUILDER_WEAR=0): the lane this catalog needs is
            // not on this box, so no config can turn it back on.
            problems += "uiBuilder serves $id, which this machine cannot serve; not serving it"
          } else {
            catalogs += id
          }
        false -> catalogs -= id
        null -> Unit
      }
    }
    if (catalogs.isEmpty()) {
      // `--ui-builder-catalogs` refuses an empty list; a block that withdraws everything falls
      // back to the environment rather than serving a builder with nothing in it.
      problems +=
        "uiBuilder withdraws every catalog; keeping ${base.catalogs.sorted().joinToString()}"
      catalogs += base.catalogs
    }

    // Published: the environment's set, then each named catalog's own answer, then the default
    // for a catalog the block newly serves.
    val newlyServed = catalogs - base.catalogs
    val published: Set<String>? =
      if (
        base.publishedCatalogs == null && settings.catalogs.values.none { it.published == false }
      ) {
        null
      } else {
        val set = (base.publishedCatalogs ?: base.catalogs).toMutableSet()
        newlyServed.filterTo(set) { it in base.publishedDefault }
        for ((id, catalog) in settings.catalogs) {
          when (catalog.published) {
            true -> set += id
            false -> set -= id
            null -> Unit
          }
        }
        set.retainAll(catalogs)
        set
      }

    // Ownership: only a catalog that reads its published file can be owned.
    val readsPublished = published ?: catalogs
    val owned =
      if (
        base.catalogOwnership == CatalogOwnership.ALL &&
          settings.catalogs.values.none { it.owned == false }
      ) {
        CatalogOwnership.ALL
      } else {
        val set = catalogs.filterTo(mutableSetOf(), base.catalogOwnership::owns)
        for ((id, catalog) in settings.catalogs) {
          when (catalog.owned) {
            true -> set += id
            false -> set -= id
            null -> Unit
          }
        }
        val contradictions = set.filterNot { it in catalogs && it in readsPublished }
        if (contradictions.isNotEmpty()) {
          problems +=
            "uiBuilder owns ${contradictions.sorted().joinToString()}, which " +
              "is not served from its published file; not owning it"
          set.removeAll(contradictions.toSet())
        }
        if (set.isEmpty()) CatalogOwnership.NONE else CatalogOwnership.of(set)
      }

    // Shadow: a report on what owning a catalog would change, so only a catalog that reads its
    // published file, and is not owned already, has anything to report.
    val shadow = base.shadowCatalogs.toMutableSet()
    for ((id, catalog) in settings.catalogs) {
      when (catalog.shadow) {
        true -> shadow += id
        false -> shadow -= id
        null -> Unit
      }
    }
    val unshadowable = shadow.filterNot {
      it in catalogs && it in readsPublished && !owned.owns(it)
    }
    if (unshadowable.isNotEmpty()) {
      problems +=
        "uiBuilder shadows ${unshadowable.sorted().joinToString()}, which is not served from its " +
          "published file or is owned already; not shadowing it"
      shadow.removeAll(unshadowable.toSet())
    }

    val native = base.nativeCatalogs.toMutableMap()
    for ((id, catalog) in settings.catalogs) {
      when (val value = catalog.nativeCatalog) {
        null -> Unit
        "" -> native -= id
        else -> native[id] = value
      }
    }

    val packs = base.packs.toMutableMap()
    for ((pack, platform) in settings.packs) {
      if (platform == null) packs -= pack else packs[pack] = platform.lowercase()
    }

    val player =
      settings.widgetPlayer?.let { raw ->
        UiBuilderWidgetPlayer.fromFlag(raw)
          ?: run {
            problems +=
              "uiBuilder widgetPlayer `$raw` is unknown; keeping ${base.widgetPlayer.flagValue}"
            null
          }
      } ?: base.widgetPlayer

    return Resolution(
      Effective(
        catalogs,
        published,
        owned,
        native,
        packs,
        player,
        base.publishedDefault,
        base.unavailable,
        shadow,
      ),
      problems,
    )
  }

  /**
   * [options] with the `uiBuilder` block applied, or [options] itself when there is no file or
   * block. An unreadable file leaves the environment in charge.
   */
  fun overlay(
    options: ServeOptions,
    onLog: (String) -> Unit = System.err::println,
    fileSystem: okio.FileSystem = ee.schimke.composeai.io.SystemFileSystem,
  ): ServeOptions {
    val path = options.catalogsFilePath ?: return options
    val settings =
      runCatching { ServeCatalogsConfigFile(path.toPath(), fileSystem).load().uiBuilder }
        .getOrNull() ?: return options
    // The admin route validates what it writes; a hand-edited file has had no such check, and one
    // bad optional setting (a pack on an unknown platform) must not stop the whole server.
    validate(settings)?.let {
      onLog("serve: catalogs config: uiBuilder ignored, keeping the environment: $it")
      return options
    }
    val base = Effective.of(options)
    // A block the server cannot resolve must not stop the box: it comes up on its environment.
    val resolution = runCatching {
      resolve(base, settings)
    }
      .getOrElse {
        onLog("serve: catalogs config: uiBuilder ignored, keeping the environment: ${it.message}")
        return options
      }
    resolution.problems.forEach { onLog("serve: catalogs config: $it") }
    if (resolution.effective != base) {
      onLog(
        "serve: UI-builder catalogs from catalogs.json: " +
          resolution.effective.catalogs.sorted().joinToString() +
          " (environment: " +
          base.catalogs.sorted().joinToString() +
          ")"
      )
    }
    return Overlay(options, resolution.effective)
  }

  /**
   * The environment's options with builder settings answered from `catalogs.json`. [base] is kept
   * so the admin route resolves a new block against the environment, not the block it replaces.
   */
  class Overlay(val base: ServeOptions, private val effective: Effective) : ServeOptions by base {
    override val uiBuilderCatalogs: Set<String>
      get() = effective.catalogs

    override val uiBuilderPublishedCatalogs: Set<String>?
      get() = effective.publishedCatalogs

    override val uiBuilderCatalogOwnership: CatalogOwnership
      get() = effective.catalogOwnership

    override val uiBuilderNativeCatalogs: Map<String, String>
      get() = effective.nativeCatalogs

    override val uiBuilderPacks: Map<String, String>
      get() = effective.packs

    override val uiBuilderWidgetPlayer: UiBuilderWidgetPlayer
      get() = effective.widgetPlayer

    override val uiBuilderShadowCatalogs: Set<String>
      get() = effective.shadowCatalogs
  }

  /** The environment's options under [options], whether or not an overlay was applied. */
  fun environmentOf(options: ServeOptions): ServeOptions = (options as? Overlay)?.base ?: options
}

/** The settings as `/admin/ui-builder/config` reports them. */
@kotlinx.serialization.Serializable
data class ServeUiBuilderSettingsDto(
  val catalogs: List<String>,
  /** Null ⇒ every served catalog reads its published file (`all`). */
  val publishedCatalogs: List<String>?,
  val catalogOwnership: String,
  val nativeCatalogs: Map<String, String>,
  val packs: Map<String, String>,
  val widgetPlayer: String,
  val shadowCatalogs: List<String> = emptyList(),
)

/**
 * One shadowed catalog's report (compose-ui-builder's `CatalogCutoverShadow.Report`): [ready] when
 * owning it would take nothing away ([losses] empty); [differences] is what the editor would see
 * change, null when this build synthesises nothing for the catalog.
 */
@kotlinx.serialization.Serializable
data class ServeUiBuilderShadowReportDto(
  val ready: Boolean,
  val findings: List<String>,
  val differences: List<String>?,
  /**
   * The [differences] that take something away from an editor; non-empty ⇒ not [ready]. Null when
   * [differences] is: nothing to compare, so nothing to lose.
   */
  val losses: List<String>? = differences?.let { emptyList() },
)

/**
 * `/admin/ui-builder/config`: the `uiBuilder` block, validated and written to apply at the next
 * start, since the catalog set feeds routes, seeds, native lanes and packs decided at startup. A
 * reply says when the written settings differ from what is serving.
 */
class ServeUiBuilderSettingsAdmin(
  private val configFile: ServeCatalogsConfigFile?,
  /** The environment's settings, before any block: what a new block is resolved against. */
  private val environment: ServeUiBuilderSettings.Effective,
  /** What this process is serving. */
  private val serving: ServeUiBuilderSettings.Effective,
  /** Each shadowed catalog's latest report, by catalog id; filled as catalogs compose. */
  private val shadowReports: () -> Map<String, ServeUiBuilderShadowReportDto> = { emptyMap() },
  /**
   * Each owned catalog this process can't serve fully, with why: its published file is missing or
   * doesn't compose (left out), or its templates don't read (served, new designs start blank).
   */
  private val unavailable: () -> Map<String, String> = { emptyMap() },
) {
  sealed interface Result {
    data class Ok(
      val settings: ServeCatalogsConfig.UiBuilderSettings?,
      val effective: ServeUiBuilderSettings.Effective,
      val restartRequired: Boolean,
      val problems: List<String>,
    ) : Result

    data class Invalid(val reason: String) : Result

    /** No config file to write: settings that would not survive the restart that applies them. */
    data class Unavailable(val reason: String) : Result
  }

  fun configured(): ServeCatalogsConfig.UiBuilderSettings? = runCatching {
    configFile?.load()?.uiBuilder
  }
    .getOrNull()

  /** What the next start will serve, from the block written now. */
  fun next(): ServeUiBuilderSettings.Resolution =
    ServeUiBuilderSettings.resolve(environment, configured())

  fun environment(): ServeUiBuilderSettings.Effective = environment

  fun serving(): ServeUiBuilderSettings.Effective = serving

  fun shadowReports(): Map<String, ServeUiBuilderShadowReportDto> = shadowReports.invoke()

  fun unavailable(): Map<String, String> = unavailable.invoke()

  fun set(settings: ServeCatalogsConfig.UiBuilderSettings): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    ServeUiBuilderSettings.validate(settings)?.let {
      return Result.Invalid(it)
    }
    val resolution = runCatching {
      ServeUiBuilderSettings.resolve(environment, settings)
    }
      .getOrElse {
        return Result.Invalid(it.message ?: "UI-builder settings could not be resolved")
      }
    file.update { it.copy(uiBuilder = settings) }
    return Result.Ok(
      settings,
      resolution.effective,
      resolution.effective != serving,
      resolution.problems,
    )
  }

  fun clear(): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    file.update { it.copy(uiBuilder = null) }
    return Result.Ok(null, environment, environment != serving, emptyList())
  }

  private companion object {
    const val NO_FILE =
      "this server has no --catalogs-file, so UI-builder settings would not survive a restart"
  }
}
