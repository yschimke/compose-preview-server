package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import okio.Path.Companion.toPath

/**
 * The UI builder's catalog settings, resolved from the box's environment **and** `catalogs.json`'s
 * `uiBuilder` block ([ServeCatalogsConfig.UiBuilderSettings]).
 *
 * ## Why the environment stays the baseline
 *
 * These settings used to live only in `SERVE_UI_BUILDER_*` variables in the box's private `.env`,
 * read once by the entrypoint into `--ui-builder-*` flags. Moving them to `catalogs.json` — which
 * the repository publishes to the box through the admin API — must not disrupt a box whose `.env`
 * already names them, and nobody but the operator can read that `.env`. So the block is a set of
 * **overrides**: a catalog it does not mention keeps exactly what the environment gives it, and a
 * box with no block is unchanged. The mapping is one variable to one field:
 *
 * | Environment                           | `catalogs.json` `uiBuilder`               |
 * |---------------------------------------|-------------------------------------------|
 * | `SERVE_UI_BUILDER_CATALOGS`           | `catalogs.<id>.serve`                     |
 * | `SERVE_UI_BUILDER_PUBLISHED_CATALOGS` | `catalogs.<id>.published`                 |
 * | `SERVE_UI_BUILDER_CATALOG_OWNERSHIP`  | `catalogs.<id>.owned`                     |
 * | `SERVE_UI_BUILDER_NATIVE_CATALOGS`    | `catalogs.<id>.nativeCatalog`             |
 * | `SERVE_UI_BUILDER_PACKS`              | `packs.<served catalog>` (null withdraws) |
 * | `SERVE_UI_BUILDER_WIDGET_PLAYER`      | `widgetPlayer`                            |
 *
 * Secrets, credentials and facts about the machine (`SERVE_UI_BUILDER_WEAR`, `…_STATE_DIR`,
 * `…_COMPONENTS`, `…_HOST`, the guidelines keys, the admin actors) stay in the environment.
 *
 * ## The published default
 *
 * A catalog the block starts serving, with no `published` of its own, is published when it is in
 * the deployment's published default ([ServeOptions.uiBuilderPublishedDefault]): the list the image
 * entrypoint derives `--ui-builder-published-catalogs` from and passes alongside it, so turning a
 * catalog on here behaves exactly as naming it in the `.env` would. The list is the deployment's,
 * not this file's, because catalog names stay out of the server's Kotlin
 * (`.github/scripts/ui-builder-catalog-literals.sh`).
 *
 * Settings left that contradict each other (a published id no longer served, an owned catalog that
 * does not read its published file) are reported and narrowed rather than refusing to boot: a box
 * must come up on a config it wrote itself.
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
      ),
      problems,
    )
  }

  /**
   * [options] with `catalogs.json`'s `uiBuilder` block applied, or [options] itself when there is
   * no file or no block. A file that cannot be read leaves the environment in charge, exactly as
   * the rest of the server treats an unreadable `catalogs.json`.
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
   * The environment's options with the builder settings answered from `catalogs.json`. [base] is
   * kept so the admin route can resolve a new block against the environment rather than against the
   * block it replaces.
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
)

/**
 * `/admin/ui-builder/config`: the `uiBuilder` block in `catalogs.json`, validated and written so it
 * applies at the next start, like the editor pin.
 *
 * Applied at the next start rather than live because the builder's catalog set feeds the routes,
 * the seeds, the native lanes and the packs, all decided once at startup; a reply says when the
 * written settings differ from what is serving.
 */
class ServeUiBuilderSettingsAdmin(
  private val configFile: ServeCatalogsConfigFile?,
  /** The environment's settings, before any block: what a new block is resolved against. */
  private val environment: ServeUiBuilderSettings.Effective,
  /** What this process is serving. */
  private val serving: ServeUiBuilderSettings.Effective,
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
