package ee.schimke.composeai.cli.serve

/**
 * Publish and retire catalogs on a running server, and persist the result. The admin API's
 * `POST`/`DELETE` land here, which:
 * 1. validates the entry (id/repo shape, unknown group, duplicate system),
 * 2. fetches and registers (or unregisters) it through the same [ServeCatalogStore] path startup
 *    uses,
 * 3. records it in the [CatalogLoadTracker], the configured-set source of truth for the home index,
 *    `/status` and the branch refresher,
 * 4. rewrites the operator's `catalogs.json` so it survives a restart.
 *
 * Persistence is best-effort and reported, never fatal: a working registration that couldn't be
 * written back keeps serving.
 */
class ServeCatalogAdmin(
  private val tracker: CatalogLoadTracker,
  /** The server's `--catalog-repo`, used when an entry names no repo of its own. */
  private val defaultRepo: String,
  /** The server's `--catalog-branch-prefix` (`design-artifacts/`), for the watched branch name. */
  private val branchPrefix: String,
  /**
   * The operator's config file; null ⇒ registrations are runtime-only and don't survive restart.
   */
  private val configFile: ServeCatalogsConfigFile?,
  /** Fetch + register one catalog. Returns null on success, else the failure reason. */
  private val load: (system: String, repo: String) -> String?,
  /** Drop a registered catalog's session (and its daemon), if any. */
  private val unload: (system: String) -> Unit,
  /**
   * The top-level sites this server publishes ([ServeSites]), so a catalog a hostname depends on
   * can't be retired under it. The live map, not a startup snapshot, so a runtime-published site
   * protects its catalog immediately.
   */
  private val sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
  /**
   * The server's catalog-registration monitor, so a re-point's load and its provenance record are
   * one critical section. [load] takes this lock internally, so the outer section nests
   * reentrantly. Without it, the branch refresher could slip in between, see the old repo in the
   * tracker and reload it over the new registration.
   */
  private val registrationLock: Any = Any(),
  /** Group table for resolving [ServeCatalogsConfig.Entry.group]; seeded from the config file. */
  groups: List<ServeCatalogsConfig.Group> = emptyList(),
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /**
   * The group table, refreshed from the file on each [persist] (an operator may edit by hand
   * between calls). Replaced wholesale so a concurrent [register] never sees a half-rebuilt list.
   */
  @Volatile private var groups: List<ServeCatalogsConfig.Group> = groups

  /**
   * Guards [groups] across the write that refreshes it. The file's own read-modify-write is
   * serialised by [ServeCatalogsConfigFile.update], shared with [ServeSiteAdmin].
   */
  private val configLock = Any()

  /** The outcome of an admin mutation, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /** The catalog is serving (or gone). [warning] flags a non-fatal persistence failure. */
    data class Ok(val system: String, val warning: String? = null) : Result

    /** The request was malformed / named an unknown group — a 400. */
    data class Invalid(val reason: String) : Result

    /** The system is already published (register) or isn't (unregister) — a 409 / 404. */
    data class Conflict(val reason: String) : Result

    /** The entry was accepted but the catalog couldn't be fetched — a 502. */
    data class Failed(val system: String, val reason: String) : Result
  }

  /** The currently configured catalogs, in front-page order. */
  fun list(): List<CatalogLoadTracker.State> = tracker.snapshot()

  /** The front-page sections a catalog entry may claim. */
  fun listGroups(): List<ServeCatalogsConfig.Group> = groups

  /**
   * Define [group], or update the heading/noun of an existing one, so a committed config can
   * converge without a restart.
   */
  fun upsertGroup(group: ServeCatalogsConfig.Group): Result {
    ServeCatalogsConfig.validateGroup(group)?.let {
      return Result.Invalid(it)
    }
    if (groups.any { it == group }) {
      return Result.Conflict("group '${group.id}' is already defined identically")
    }
    val warning = persist { it.withGroup(group) }
    // Re-resolve the claims of catalogs ALREADY registered: a section defined after its catalogs
    // were published must still collect them, or defining it does nothing visible.
    val moved = reapplyGroupClaims()
    onLog("serve: group ${group.id} defined via admin API (regrouped $moved catalog(s))")
    return Result.Ok(group.id, warning)
  }

  /** Delete group [id]. Catalogs claiming it fall back to their source repo's owner heading. */
  fun removeGroup(id: String): Result {
    if (groups.none { it.id == id }) {
      return Result.Conflict("group '$id' is not defined here")
    }
    val warning = persist { it.withoutGroup(id) }
    val moved = reapplyGroupClaims()
    onLog("serve: group $id removed via admin API (regrouped $moved catalog(s))")
    return Result.Ok(id, warning)
  }

  /**
   * Re-resolve every registered catalog's front-page placement against the current group table.
   * Returns how many changed. Reads entries from the config file because the tracker holds only the
   * resolved [ServeWeb.HomeGroup], not the declared `group` id; catalogs with no config entry are
   * left alone.
   */
  private fun reapplyGroupClaims(): Int {
    val declared = configFile?.let { runCatching { it.load() }.getOrNull() } ?: return 0
    val table = groups
    var changed = 0
    for (entry in declared.catalogs) {
      val current = tracker.configFor(entry.system) ?: continue
      val resolved = homeGroup(entry, current.repo, table)
      if (
        current.group == resolved &&
          current.listed == entry.listed &&
          current.loadPriority == entry.loadPriority &&
          current.importedFrom == entry.importedFrom &&
          current.designSystem == entry.isDesignSystem
      ) {
        continue
      }
      if (
        tracker.relist(
          entry.system,
          listed = entry.listed,
          group = resolved,
          loadPriority = entry.loadPriority,
          importedFrom = entry.importedFrom,
          designSystem = entry.isDesignSystem,
        )
      ) {
        changed++
      }
    }
    return changed
  }

  /**
   * Publish [entry]. The catalog is fetched before it's persisted, so a typo'd repo fails loudly
   * instead of leaving an unservable entry in the config for every future boot to retry.
   */
  fun register(entry: ServeCatalogsConfig.Entry): Result {
    ServeCatalogsConfig.validateEntry(entry)?.let {
      return Result.Invalid(it)
    }
    // One snapshot for both the check and the resolution, so a concurrent config rewrite can't
    // make this request validate against one group table and register against another.
    val declared = groups
    if (entry.group != null && declared.none { g -> g.id == entry.group }) {
      return Result.Invalid("unknown group '${entry.group}'")
    }
    val repo = entry.repo?.takeIf { it.isNotBlank() } ?: defaultRepo
    // Already published: converge its listing (group, listed flag, `loadPriority`) rather than
    // refusing, so a re-posted committed config can catch up with a running box;
    // `.github/scripts/publish-config-to-box.sh` is additive. Content is untouched; a repo change,
    // which does change the served bytes, is handled below.
    tracker.configFor(entry.system)?.let { current ->
      val resolved = homeGroup(entry, repo, declared)
      // A repo change is a swap, not a conflict. Load first: a failed load returns before touching
      // any registration, so the old catalog keeps serving; a successful one re-registers the host
      // in place (as the branch refresher does) and [CatalogLoadTracker.repoint] records the new
      // source. Retire-then-publish would leave the system published nowhere if the fetch failed,
      // and couldn't handle a catalog published as a top-level site.
      if (current.repo != repo) {
        // One critical section: hold the registration monitor across the provenance record too, or
        // the branch refresher could reload the old repo over the new registration. The refresher
        // also declines stale captures (`ServeRunner.buildCatalogRefresher`); either side alone
        // leaves a window.
        val failure =
          synchronized(registrationLock) {
            val loadFailure = runCatching {
              load(entry.system, repo)
            }
              .getOrElse { it.message ?: "load failed" }
            if (loadFailure == null) {
              tracker.repoint(entry.system, repo = repo, branch = "$branchPrefix${entry.system}")
              tracker.relist(
                entry.system,
                listed = entry.listed,
                group = resolved,
                loadPriority = entry.loadPriority,
                importedFrom = entry.importedFrom,
                designSystem = entry.isDesignSystem,
              )
            }
            loadFailure
          }
        if (failure != null) {
          // What the old catalog is actually doing: `configFor` answers the same for pending or
          // failed entries, so check availability rather than configuration.
          val serving = tracker.stateFor(entry.system)?.available == true
          val held =
            if (serving) "still serving ${current.repo}"
            else "kept the ${current.repo} configuration, which is not currently serving"
          return Result.Failed(
            entry.system,
            "could not re-point '${entry.system}' to $repo, $held: $failure",
          )
        }
        onLog("serve: catalog ${entry.system} re-pointed ${current.repo} -> $repo via admin API")
        return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
      }
      if (
        current.group == resolved &&
          current.listed == entry.listed &&
          current.loadPriority == entry.loadPriority &&
          // Attribution converges like placement, so an import can move off the staging owner's
          // section without a restart.
          current.importedFrom == entry.importedFrom
      ) {
        // Everything tracked matches, but the file may not: a swap whose persist failed transiently
        // leaves `catalogs.json` stale, and answering 409 here would stop the reconcile ever
        // repairing it. So a file mismatch is persisted and reported; otherwise this is the usual
        // 409.
        if (persistedRepoMatches(entry.system, repo)) {
          return Result.Conflict("catalog '${entry.system}' is already published")
        }
        onLog(
          "serve: catalog ${entry.system} is running from $repo but the config file disagreed" +
            " — rewriting it"
        )
        return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
      }
      tracker.relist(
        entry.system,
        listed = entry.listed,
        group = resolved,
        loadPriority = entry.loadPriority,
        importedFrom = entry.importedFrom,
        designSystem = entry.isDesignSystem,
      )
      onLog("serve: catalog ${entry.system} listing updated via admin API")
      return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
    }
    val config = configOf(entry, repo, declared)
    if (!tracker.add(config)) {
      return Result.Conflict("catalog '${entry.system}' is already published")
    }
    val failure = runCatching { load(entry.system, repo) }.getOrElse { it.message ?: "load failed" }
    if (failure != null) {
      // Never leave a half-published catalog behind: a failed fetch retires the entry it added.
      tracker.remove(entry.system)
      runCatching { unload(entry.system) }
      return Result.Failed(entry.system, failure)
    }
    onLog("serve: catalog ${entry.system} published via admin API (repo=$repo)")
    return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
  }

  /** Retire [system] — its session is dropped and the entry removed from the config file. */
  fun unregister(system: String): Result {
    // Retiring a site's catalog would strand its hostname (404 now, and after a restart the host
    // falls through to the global front door). Fail closed: drop the site first.
    sites.hostFor(system)?.let { host ->
      return Result.Conflict(
        "catalog '$system' is published as the top-level site '$host'; remove the site first"
      )
    }
    if (!tracker.remove(system)) {
      return Result.Conflict("catalog '$system' is not published here")
    }
    runCatching { unload(system) }
      .onFailure { onLog("serve: catalog $system unload failed: ${it.message}") }
    onLog("serve: catalog $system retired via admin API")
    return Result.Ok(system, persist { it.withoutEntry(system) })
  }

  private fun configOf(
    entry: ServeCatalogsConfig.Entry,
    repo: String,
    declaredGroups: List<ServeCatalogsConfig.Group>,
  ): CatalogLoadTracker.Config =
    CatalogLoadTracker.Config(
      system = entry.system,
      listed = entry.listed,
      repo = repo,
      branch = "$branchPrefix${entry.system}",
      group = homeGroup(entry, repo, declaredGroups),
      // Carried, not dropped, so an admin-published import is attributed correctly on the front
      // page before a restart.
      importedFrom = entry.importedFrom,
      loadPriority = entry.loadPriority,
      designSystem = entry.isDesignSystem,
    )

  /**
   * Whether the on-disk config already records [system] as coming from [repo], so a swap whose
   * persistence failed can be retried. True with no config file. An unreadable file answers false,
   * so the caller attempts the write and reports why it fails rather than a misleading 409.
   */
  private fun persistedRepoMatches(system: String, repo: String): Boolean {
    val file = configFile ?: return true
    return runCatching {
        val persisted = file.load().catalogs.firstOrNull { it.system == system } ?: return false
        (persisted.repo?.takeIf { it.isNotBlank() } ?: defaultRepo) == repo
      }
      .getOrDefault(false)
  }

  /**
   * Apply [mutate] to the on-disk config. Returns null on success (or when no config file is
   * configured), else the warning to hand back with an otherwise-successful result.
   */
  private fun persist(mutate: (ServeCatalogsConfig) -> ServeCatalogsConfig): String? {
    val file = configFile ?: return "not persisted: no catalogs config file is configured"
    return synchronized(configLock) {
      runCatching {
        // The read-modify-write is serialised by the file instance; configLock additionally guards
        // `groups`.
        groups = file.update(mutate).groups
        null
      }
        .getOrElse { e ->
          onLog("serve: could not update ${file.displayPath}: ${e.message}")
          "not persisted: ${e.message ?: "write failed"}"
        }
    }
  }

  companion object {
    /**
     * The front-page section [entry] claims, with the repos allowed to satisfy that claim: the repo
     * the catalog is actually fetched from plus any operator-declared
     * [ServeCatalogsConfig.Entry.attributionRepos]. Null when the entry claims no group (or names
     * one the config doesn't define), which leaves the card to the source-repo fallback in
     * [ServeWeb.homeSections].
     */
    fun homeGroup(
      entry: ServeCatalogsConfig.Entry,
      repo: String,
      groups: List<ServeCatalogsConfig.Group>,
    ): ServeWeb.HomeGroup? {
      val group = entry.group?.let { id -> groups.firstOrNull { it.id == id } } ?: return null
      return ServeWeb.HomeGroup(
        heading = group.heading,
        noun = group.noun,
        repos = (entry.attributionRepos + repo).toSet(),
        priority = group.priority,
      )
    }
  }
}

/** This config with [group] added, or replacing a same-id group, preserving section order. */
internal fun ServeCatalogsConfig.withGroup(group: ServeCatalogsConfig.Group): ServeCatalogsConfig {
  val known = groups.any { it.id == group.id }
  val updated = if (known) groups.map { if (it.id == group.id) group else it } else groups + group
  return copy(groups = updated)
}

/**
 * This config with group [id] removed. Entries claiming it keep the claim: an unknown group is
 * tolerated ([ServeCatalogsConfig.problems] reports it, the card falls back to its owner heading),
 * and the claim survives the group coming back.
 */
internal fun ServeCatalogsConfig.withoutGroup(id: String): ServeCatalogsConfig =
  copy(groups = groups.filterNot { it.id == id })

/** This config with [entry] added (or replacing a same-system entry), preserving order. */
internal fun ServeCatalogsConfig.withEntry(entry: ServeCatalogsConfig.Entry): ServeCatalogsConfig {
  val known = catalogs.any { it.system == entry.system }
  val updated =
    if (known) catalogs.map { if (it.system == entry.system) entry else it } else catalogs + entry
  return copy(catalogs = updated)
}

/** This config with [system]'s entry removed. */
internal fun ServeCatalogsConfig.withoutEntry(system: String): ServeCatalogsConfig =
  copy(catalogs = catalogs.filterNot { it.system == system })
