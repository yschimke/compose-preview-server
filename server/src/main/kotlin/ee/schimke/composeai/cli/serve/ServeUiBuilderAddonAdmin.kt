package ee.schimke.composeai.cli.serve

/**
 * `GET`/`POST /admin/ui-builder-addons` and `DELETE /admin/ui-builder-addons/{id}`: the add-on
 * builder catalogs this instance serves beside `m3-catalog`.
 *
 * Material 3 is the image's only built-in builder catalog. Others — Wear, Remote Compose — are
 * add-ons: published by their own catalog repository, drawn through the renderer runtime it
 * publishes, and turned on by an instance in its `catalogs.json` (`uiBuilder.addons`). These routes
 * are how a committed add-on reaches a running box (`publish-config-to-box.sh`), and the same
 * additive, 409-is-already-there contract the other admin routes use.
 *
 * Like the editor pin, an add-on applies at the next **start**: the builder's catalog set is fixed
 * when its runtime is built, and every served design, palette and export capability hangs off it.
 * The result says when a restart is owed.
 */
class ServeUiBuilderAddonAdmin(
  private val configFile: ServeCatalogsConfigFile?,
  /**
   * The add-ons this process started with and serves, declarations and all. Compared whole, so a
   * re-pointed source owes a restart as much as a new id does.
   */
  private val serving: () -> List<ServeCatalogsConfig.UiBuilderAddon>,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    /** Written. [restartRequired] when the change is not yet what this process serves. */
    data class Ok(val id: String, val restartRequired: Boolean) : Result

    /** Malformed — a 400. */
    data class Invalid(val reason: String) : Result

    /** Already declared exactly so (add) or not declared (remove) — a 409. */
    data class Conflict(val reason: String) : Result

    /** No config file to write — a 503, since the change could never apply. */
    data class Unavailable(val reason: String) : Result
  }

  /** The add-ons `catalogs.json` declares now, which is what the next start will serve. */
  fun configured(): List<ServeCatalogsConfig.UiBuilderAddon> = runCatching {
    configFile?.load()?.uiBuilder?.addons
  }
    .getOrNull()
    .orEmpty()

  fun serving(): List<ServeCatalogsConfig.UiBuilderAddon> = serving.invoke()

  /** Whether the next start serves different add-ons from this one. */
  fun restartRequired(): Boolean = configured().toSet() != serving().toSet()

  /** Declare [addon], or re-point a declared one at a different source. */
  fun add(addon: ServeCatalogsConfig.UiBuilderAddon): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    ServeCatalogsConfig.validateAddon(addon)?.let {
      return Result.Invalid(it)
    }
    runCatching { file.load().catalogs.map { it.system }.toSet() }
      .getOrNull()
      ?.let { ServeCatalogsConfig.sourceProblem(addon, it) }
      ?.let {
        return Result.Invalid(it)
      }
    if (addon in configured()) {
      return Result.Conflict("UI-builder add-on '${addon.id}' is already configured")
    }
    file.update { config ->
      val addons = config.uiBuilder?.addons.orEmpty().filterNot { it.id == addon.id } + addon
      config.copy(uiBuilder = ServeCatalogsConfig.UiBuilder(addons))
    }
    onLog("serve: UI-builder add-on ${addon.id} configured via admin API")
    return Result.Ok(addon.id, restartRequired = addon !in serving())
  }

  /** Stop declaring [id]. Designs made against it stay stored; the catalog stops being served. */
  fun remove(id: String): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    if (configured().none { it.id == id }) {
      return Result.Conflict("UI-builder add-on '$id' is not configured")
    }
    file.update { config ->
      val addons = config.uiBuilder?.addons.orEmpty().filterNot { it.id == id }
      config.copy(uiBuilder = if (addons.isEmpty()) null else ServeCatalogsConfig.UiBuilder(addons))
    }
    onLog("serve: UI-builder add-on $id removed via admin API")
    return Result.Ok(id, restartRequired = serving().any { it.id == id })
  }

  private companion object {
    const val NO_FILE =
      "no catalogs config file is configured; pass --catalogs-file to declare UI-builder add-ons"
  }
}
