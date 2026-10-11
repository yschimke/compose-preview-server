package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps a running server's catalog set in step with its nominated registry projects
 * ([ServeCatalogRegistry]), without a restart. Each pass registers newly listed systems (not via
 * [ServeCatalogAdmin.register]: registry entries are derived state, not written into
 * `catalogs.json`), and retires or re-publishes changed systems only if this sync owns them. A
 * registry that fails to fetch retires nothing that pass.
 *
 * @param repos the nominated registry projects, in `--catalog-registry` order.
 * @param read fetch and normalise one registry's document, reporting why through its second
 *   argument; null ⇒ unreadable this pass.
 * @param tracked the systems the box serves or is configured to serve, read per pass so an admin
 *   publish between ticks is seen.
 * @param publish register one newly listed catalog; returns the failure reason, or null.
 * @param retire drop a catalog this sync published and the registry no longer lists.
 * @param intervalMillis poll cadence; the first tick fires one interval after [start].
 */
class ServeCatalogRegistrySync(
  private val repos: List<ServeCatalogRegistry.Nomination>,
  private val read:
    (
      ServeCatalogRegistry.Nomination,
      onProblem: (String) -> Unit,
    ) -> ServeCatalogRegistry.Contribution?,
  private val tracked: () -> Set<String>,
  private val publish: (ServeCatalogRegistry.Contribution, ServeCatalogsConfig.Entry) -> String?,
  private val retire: (system: String) -> Unit,
  private val intervalMillis: Long,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /**
   * The systems this sync owns: seeded at startup, added on publish, removed on retire. Ownership
   * is what makes withdrawal safe; "unlisted" alone is true of every operator-configured catalog
   * too.
   */
  private val owned = java.util.Collections.synchronizedSet(linkedSetOf<String>())

  /**
   * A fingerprint of what each owned system was published as, so a changed entry is recognisable
   * without re-publishing on cosmetic edits.
   */
  private val publishedAs = java.util.Collections.synchronizedMap(HashMap<String, String>())

  /**
   * Which registry each owned system came from, so a pass that can't read one registry doesn't
   * retire that registry's catalogs while others read cleanly.
   */
  private val ownerRepo = java.util.Collections.synchronizedMap(HashMap<String, String>())

  /** The registration-affecting fields of an entry, as a comparable string. */
  private fun fingerprintOf(
    contribution: ServeCatalogRegistry.Contribution,
    entry: ServeCatalogsConfig.Entry,
  ): String =
    listOf(
        contribution.repo,
        entry.listed.toString(),
        // The whole resolved group, not just its heading: `priority` moves the section too, so a
        // registry that reprioritises one must take effect like any other change.
        contribution.homeGroup(entry)?.toString().orEmpty(),
        entry.importedFrom.orEmpty(),
        entry.loadPriority.toString(),
      )
      .joinToString("\u0000")

  private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serve-catalog-registry").apply { isDaemon = true }
  }

  /**
   * Record the entries the startup fold-in registered from [contribution], with their fingerprints;
   * without them the first pass would see every boot-loaded catalog as changed and re-publish it.
   * [entries] excludes ones the operator's config or an earlier registry already claimed, which the
   * sync must not touch.
   */
  fun adopt(
    contribution: ServeCatalogRegistry.Contribution,
    entries: Collection<ServeCatalogsConfig.Entry> = contribution.entries,
  ) {
    for (entry in entries) {
      owned += entry.system
      publishedAs[entry.system] = fingerprintOf(contribution, entry)
      ownerRepo[entry.system] = contribution.repo
    }
  }

  /** The systems this sync published, for status / tests. */
  fun ownedSystems(): Set<String> = synchronized(owned) { owned.toSet() }

  /**
   * One nomination as the latest pass saw it. [contribution] is the last clean document, kept
   * across failed reads (which retire nothing); [error] is the latest read's problem, or null.
   */
  data class LastRead(val contribution: ServeCatalogRegistry.Contribution?, val error: String?)

  private val lastReads =
    java.util.concurrent.ConcurrentHashMap<ServeCatalogRegistry.Nomination, LastRead>()

  /** What the latest pass read for [nomination]; null before the first pass (or with no sync). */
  fun lastRead(nomination: ServeCatalogRegistry.Nomination): LastRead? = lastReads[nomination]

  fun start() {
    if (intervalMillis <= 0 || repos.isEmpty()) return
    exec.scheduleWithFixedDelay(
      {
        runCatching { syncOnce() }
          .onFailure { onLog("serve: catalog registry sync: ${it.message}") }
      },
      intervalMillis,
      intervalMillis,
      TimeUnit.MILLISECONDS,
    )
  }

  /** One reconciliation pass. Public so a test drives it without a clock. */
  fun syncOnce() {
    val listed = linkedSetOf<String>()
    var readAny = false
    for (nomination in repos) {
      val repo = nomination.repo
      var problem: String? = null
      val contribution = read(nomination) { problem = it }
      if (contribution == null) {
        lastReads.compute(nomination) { _, previous ->
          LastRead(previous?.contribution, problem ?: "catalog registry $nomination: unreadable")
        }
        // This registry said nothing this pass, so everything it contributed stays listed: another
        // registry reading cleanly must not turn this one's outage into a withdrawal.
        listed += synchronized(ownerRepo) { ownerRepo.filterValues { it == repo }.keys.toList() }
        continue
      }
      lastReads[nomination] = LastRead(contribution, null)
      readAny = true
      val known = tracked()
      for (entry in contribution.entries) {
        listed += entry.system
        val fingerprint = fingerprintOf(contribution, entry)
        // Not registered → publish; registered by this sync and changed → retire and re-publish;
        // otherwise leave alone. A registry may correct only its own entries, never overrule
        // `catalogs.json`.
        val registered = entry.system in known
        val mine = entry.system in owned
        if (registered && (!mine || publishedAs[entry.system] == fingerprint)) continue
        if (registered) {
          // Retire first: re-adding a tracked system isn't defined to update it. If the publish
          // fails the catalog is briefly unpublished and the next pass repairs it.
          retire(entry.system)
          onLog("serve: catalog ${entry.system} changed in registry $repo — re-publishing")
        }
        val failure = publish(contribution, entry)
        if (failure == null) {
          owned += entry.system
          publishedAs[entry.system] = fingerprint
          ownerRepo[entry.system] = contribution.repo
          if (!registered) onLog("serve: catalog ${entry.system} imported from registry $repo")
        } else {
          publishedAs.remove(entry.system)
          // Left unowned so the next pass retries; usually the import's delivery branch isn't built
          // yet.
          onLog("serve: catalog ${entry.system} from registry $repo not available yet: $failure")
        }
      }
    }
    // Only withdraw after a pass that read something: silence from a failed fetch is not a
    // retirement.
    if (!readAny) return
    val gone = synchronized(owned) { owned.filterNot { it in listed } }
    for (system in gone) {
      retire(system)
      owned.remove(system)
      publishedAs.remove(system)
      ownerRepo.remove(system)
      onLog("serve: catalog $system retired — no longer listed by any catalog registry")
    }
  }

  override fun close() {
    exec.shutdownNow()
  }
}

/**
 * The `/status` row for one nomination: what the registry contributes now. [live] (the sync's
 * latest read) wins when present; the boot read answers only before the first pass or when the box
 * never syncs. Reporting the boot snapshot made `publish-config-to-box.sh --prune` retire catalogs
 * published after boot.
 *
 * `systems` is the registry's whole nominated list from its last clean document, not just what this
 * box published, since the prune needs everything the registry nominates. On a failed read the last
 * clean document stands and [error] says why.
 */
fun catalogRegistryStatus(
  nomination: ServeCatalogRegistry.Nomination,
  bootContribution: ServeCatalogRegistry.Contribution?,
  bootProblem: String?,
  live: ServeCatalogRegistrySync.LastRead?,
): CatalogRegistryStatus {
  val contribution = live?.contribution ?: bootContribution
  return CatalogRegistryStatus(
    repo = nomination.repo,
    ref = nomination.ref,
    catalogs = contribution?.entries?.size ?: 0,
    systems = contribution?.entries?.map { it.system }.orEmpty(),
    error = if (live != null) live.error else bootProblem,
  )
}
