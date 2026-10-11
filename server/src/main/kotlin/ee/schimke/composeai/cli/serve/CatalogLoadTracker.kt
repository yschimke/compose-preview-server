package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe source of truth for every configured catalog and its latest load outcome, so
 * "configured but broken" stays distinct from "not configured" for readiness, `/status`, the home
 * index and the refresher.
 *
 * [State.available] means a usable copy is registered; a refresh failure after a success keeps it
 * true ([ServeCatalogStore] retains the last good copy) while [State.error] records the failure. An
 * initial failure stays visible and retried. Readiness doesn't require every catalog, except the
 * design-systems group ([Config.designSystem]; see [ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP]).
 */
class CatalogLoadTracker(
  configured: List<Config>,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** One configured catalog and where its delivery branch lives. */
  data class Config(
    val system: String,
    val listed: Boolean,
    val repo: String,
    val branch: String,
    /**
     * Front-page section ([ServeCatalogsConfig.Entry.group], resolved), or null; carried here
     * because the home index reads this tracker.
     */
    val group: ServeWeb.HomeGroup? = null,
    /**
     * Upstream project the catalog was rendered from when not [repo]
     * ([ServeCatalogsConfig.Entry.importedFrom]).
     */
    val importedFrom: String? = null,
    /**
     * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]); read by
     * [loadOrder].
     */
    val loadPriority: Int = 0,
    /**
     * Published under [ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP]: fetched first by [loadOrder] and
     * required by the readiness gate. A resolved boolean, since [group] keeps only the display
     * heading and readiness must not depend on display text.
     */
    val designSystem: Boolean = false,
  )

  /** Immutable snapshot of one catalog's current availability and latest attempt. */
  data class State(
    val config: Config,
    val available: Boolean = false,
    val error: String? = null,
    val lastAttemptEpochMillis: Long? = null,
    /** Render failures declared by the latest successfully loaded catalog. */
    val failedRenders: Int = 0,
  ) {
    val loadState: String
      get() =
        when {
          available && error == null -> "loaded"
          available -> "stale"
          error != null -> "failed"
          else -> "pending"
        }
  }

  /**
   * Configured order, mutable because the admin API ([ServeCatalogAdmin]) publishes and retires
   * catalogs at runtime. [lock] guards ordering; [states] stays concurrent so hot read paths never
   * block.
   */
  private val lock = Any()
  private val ordered =
    configured
      .distinctBy { it.system }
      .also { require(it.size == configured.size) { "duplicate catalog system id" } }
      .toMutableList()
  private val states = ConcurrentHashMap(ordered.associate { it.system to State(it) })

  /**
   * Publish a new catalog after the existing ones. False when the system is already tracked;
   * overwriting would drop the running catalog's load state.
   */
  fun add(config: Config): Boolean =
    synchronized(lock) {
      if (states.containsKey(config.system)) return false
      states[config.system] = State(config)
      ordered += config
      true
    }

  /**
   * Replace [system]'s listing metadata (placement and fetch order), keeping load state and
   * content. False when untracked. Placement is resolved once at registration, so
   * [ServeCatalogAdmin] calls this whenever the group table changes. Cannot change [Config.repo] or
   * [Config.branch], which decide what is served; see [repoint].
   */
  fun relist(
    system: String,
    listed: Boolean,
    group: ServeWeb.HomeGroup?,
    // Deliberately not defaulted: every caller states it, so none can silently reset it to 0.
    loadPriority: Int,
    // Front-page attribution, and undefaulted for the same reason: a caller that forgot it would
    // silently strip an import's origin, which is the failure this parameter was added to close.
    importedFrom: String?,
    // Undefaulted: a re-publish may move an entry into or out of the design-systems group, which
    // changes fetch band and readiness gating.
    designSystem: Boolean,
  ): Boolean =
    synchronized(lock) {
      val existing = states[system] ?: return false
      val updated =
        existing.config.copy(
          listed = listed,
          group = group,
          loadPriority = loadPriority,
          importedFrom = importedFrom,
          designSystem = designSystem,
        )
      states[system] = existing.copy(config = updated)
      val at = ordered.indexOfFirst { it.system == system }
      if (at >= 0) ordered[at] = updated
      true
    }

  /**
   * Replace [system]'s provenance (repository and branch), keeping position and load state. False
   * when untracked. Only safe after the new source is loaded and registered, since trust and
   * permalinks are built from this record; [ServeCatalogAdmin.register] loads first, so a failed
   * load leaves the old catalog serving.
   */
  fun repoint(system: String, repo: String, branch: String): Boolean =
    synchronized(lock) {
      val existing = states[system] ?: return false
      val updated = existing.config.copy(repo = repo, branch = branch)
      states[system] = existing.copy(config = updated)
      val at = ordered.indexOfFirst { it.system == system }
      if (at >= 0) ordered[at] = updated
      true
    }

  /** Retire a catalog. Returns false when it wasn't configured. */
  fun remove(system: String): Boolean =
    synchronized(lock) {
      if (states.remove(system) == null) return false
      ordered.removeAll { it.system == system }
      requestedLoads.remove(system)
      claimedLoads.remove(system)
      true
    }

  /** The configured entry for [system], or null when it isn't served here. */
  fun configFor(system: String): Config? = states[system]?.config

  /**
   * The full tracked state for [system] (configuration and whether anything serves it), or null.
   * [configFor] alone can't tell a pending or failed entry from a serving one.
   */
  fun stateFor(system: String): State? = states[system]

  /**
   * Whether a background pass queued for [system] against [repo] is still about the same catalog:
   * the refresher may wait through an admin re-point, and reloading the old repo would serve bytes
   * from a repository the provenance no longer names. Asked under the same monitor
   * [ServeCatalogAdmin] writes under.
   */
  fun stillPointsAt(system: String, repo: String): Boolean = configFor(system)?.repo == repo

  /** First currently usable catalog in configured order, or null while every catalog is pending. */
  fun firstAvailableSystem(): String? = snapshot().firstOrNull { it.available }?.config?.system

  /**
   * Every configured design-system catalog, loaded or not: the set readiness must see render.
   * Configured rather than available, so the gate can't go green before one appears.
   */
  fun designSystemSystems(): List<String> =
    snapshot().filter { it.config.designSystem }.map { it.config.system }

  fun record(result: ServeCatalogStore.Result) {
    when (result) {
      is ServeCatalogStore.Result.Ok -> recordSuccess(result.system, result.failedRenderCount)
      is ServeCatalogStore.Result.Failed -> recordFailure(result.system, result.reason)
    }
  }

  fun recordSuccess(system: String, failedRenders: Int = 0) {
    val at = clock()
    states.computeIfPresent(system) { _, previous ->
      previous.copy(
        available = true,
        error = null,
        lastAttemptEpochMillis = at,
        failedRenders = failedRenders,
      )
    }
  }

  fun recordFailure(system: String, reason: String) {
    val at = clock()
    states.computeIfPresent(system) { _, previous ->
      // A refresh is staged before swap, so failure leaves an earlier usable copy available.
      previous.copy(error = oneLine(reason), lastAttemptEpochMillis = at)
    }
  }

  /**
   * Stable configured-order snapshot, safe to iterate unlocked. Taken under [lock]; entries retired
   * mid-read are dropped.
   */
  fun snapshot(): List<State> =
    synchronized(lock) { ordered.toList() }.mapNotNull { states[it.system] }

  /**
   * The [snapshot] catalogs in initial-fetch order: design systems first (readiness waits on them),
   * then highest [Config.loadPriority], ties in configured order. Separate from [snapshot] so fetch
   * preference never moves front-page cards or [firstAvailableSystem], which the readiness probe
   * renders (#4231).
   */
  fun loadOrder(): List<State> =
    snapshot()
      .sortedWith(
        compareByDescending<State> { it.config.designSystem }
          .thenByDescending { it.config.loadPriority }
      )

  private val requestedLoads = linkedSetOf<String>()
  private val claimedLoads = mutableSetOf<String>()

  /** Promote a pending catalog without interrupting the fetch already in progress. */
  fun prioritize(system: String): Boolean =
    synchronized(lock) {
      if (states[system]?.loadState != "pending" || system in claimedLoads) return false
      requestedLoads.add(system)
    }

  /** Claim one startup fetch at a time, so requests can change the remaining order. */
  fun nextInitialLoad(): Config? =
    synchronized(lock) {
      val pending =
        loadOrder().filter { it.loadState == "pending" && it.config.system !in claimedLoads }
      val next =
        requestedLoads.toList().asReversed().firstNotNullOfOrNull { id ->
          pending.firstOrNull { it.config.system == id }
        } ?: pending.firstOrNull()
      next?.config?.also {
        claimedLoads.add(it.system)
        requestedLoads.remove(it.system)
      }
    }

  /** Catalogs with a usable registered copy; used to seed only successful branch heads. */
  /**
   * Every configured catalog, loaded or not. The theme-cache sweeper must keep renders for a
   * configured catalog that failed transiently (re-warming is expensive) and reclaim only
   * unconfigured ones; [availableSystems] can't tell those apart.
   */
  fun configuredSystems(): Set<String> = states.keys.toSet()

  fun availableSystems(): Set<String> =
    states.values.asSequence().filter { it.available }.map { it.config.system }.toSet()

  /** True only after every explicitly configured catalog has a usable registered copy. */
  fun allAvailable(): Boolean = states.values.all { it.available }

  fun startupSummary(): String {
    val current = snapshot()
    val available = current.count { it.available }
    val failed = current.filter { !it.available && it.error != null }
    return buildString {
      append("catalogs ").append(available).append('/').append(current.size).append(" loaded")
      if (failed.isNotEmpty()) {
        append("; failed: ")
        append(failed.joinToString(", ") { it.config.system })
      }
    }
  }

  private fun oneLine(reason: String): String =
    reason.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "unknown error"
}
