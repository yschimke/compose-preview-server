package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps a running server's catalog set in step with its nominated **registry projects**
 * ([ServeCatalogRegistry]), so a merged registry change takes effect without a restart. Each pass:
 * - registers a newly listed system (not via [ServeCatalogAdmin.register]: registry entries are
 *   derived state and must not be written into the operator's `catalogs.json`);
 * - retires a system no longer listed, but only if this sync put it there;
 * - re-publishes a system whose entry changed (`importedFrom`, `listed`, `group`, `loadPriority`),
 *   again only if this sync owns it.
 *
 * A registry that fails to fetch contributes and retires nothing that pass: only a document that
 * read cleanly and no longer names a system retires it.
 *
 * @param repos the nominated registry projects, in `--catalog-registry` order.
 * @param read fetch + normalise one registry's document; null ⇒ unreadable this pass.
 * @param tracked the systems the box currently serves or is configured to serve — the
 *   [CatalogLoadTracker], read per pass rather than captured, so an admin publish between ticks is
 *   seen.
 * @param publish register one newly-listed catalog: add it to the tracker and fetch it. Returns the
 *   failure reason, or null on success.
 * @param retire drop a catalog this sync published and the registry no longer lists.
 * @param intervalMillis poll cadence; the first tick fires one interval after [start].
 */
class ServeCatalogRegistrySync(
  private val repos: List<ServeCatalogRegistry.Nomination>,
  private val read: (ServeCatalogRegistry.Nomination) -> ServeCatalogRegistry.Contribution?,
  private val tracked: () -> Set<String>,
  private val publish: (ServeCatalogRegistry.Contribution, ServeCatalogsConfig.Entry) -> String?,
  private val retire: (system: String) -> Unit,
  private val intervalMillis: Long,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {

  /**
   * The systems this sync is responsible for — seeded with the startup fold-in's, added to on a
   * publish, removed on a retire.
   *
   * Ownership is what makes withdrawal safe. Without it the only available test would be "the
   * registry doesn't list it", which is true of every catalog on the box, including the ones the
   * operator spent a config edit naming.
   */
  private val owned = java.util.Collections.synchronizedSet(linkedSetOf<String>())

  /**
   * What was published for each owned system, so a changed entry is recognisable.
   *
   * A fingerprint rather than the entry: only the fields that reach the registration matter, and
   * comparing whole entries would re-publish on a cosmetic edit elsewhere in the document.
   */
  private val publishedAs = java.util.Collections.synchronizedMap(HashMap<String, String>())

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
   * Record the entries the startup fold-in already registered from [contribution], **with** what
   * they were registered as.
   *
   * The fingerprint is the half that used to be missing. Adopting only the system names left
   * [publishedAs] empty, so the first pass found every boot-loaded catalog "changed" — nothing ever
   * equals an absent fingerprint — and retired and re-published the lot. On preview.coo.ee that
   * re-publish failed for two imports after a restart, and they served a 404 for a refresh interval
   * until the next pass put them back, from a registry document that had not changed at all.
   *
   * [entries] is the subset the boot actually took from this registry: an entry the operator's own
   * configuration (or an earlier registry) already claimed was never registered from here, so
   * adopting it would hand the sync a catalog it must not re-point or withdraw.
   */
  fun adopt(
    contribution: ServeCatalogRegistry.Contribution,
    entries: Collection<ServeCatalogsConfig.Entry> = contribution.entries,
  ) {
    for (entry in entries) {
      owned += entry.system
      publishedAs[entry.system] = fingerprintOf(contribution, entry)
    }
  }

  /** The systems this sync published, for status / tests. */
  fun ownedSystems(): Set<String> = synchronized(owned) { owned.toSet() }

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
      val contribution = read(nomination) ?: continue
      readAny = true
      val known = tracked()
      for (entry in contribution.entries) {
        listed += entry.system
        val fingerprint = fingerprintOf(contribution, entry)
        // Three states, and only the middle one is new:
        //   not registered at all      -> publish
        //   registered by THIS sync, changed -> retire and re-publish
        //   registered by anyone else, or unchanged -> leave alone
        //
        // The ownership test is the same one that makes retirement safe, and it matters more here:
        // a catalog the operator named in catalogs.json wins over a registry entry by design, so
        // re-pointing it because a registry document changed would silently overrule the config
        // file. A registry may correct its OWN entries and nothing else.
        val registered = entry.system in known
        val mine = entry.system in owned
        if (registered && (!mine || publishedAs[entry.system] == fingerprint)) continue
        if (registered) {
          // Retire first: `publish` adds to the tracker and fetches, and re-adding a system that is
          // already there is not defined to update it. This is the same retire-then-republish the
          // config reconcile uses to re-point a catalog, and it carries the same window — if the
          // publish below fails the catalog is briefly unpublished, which the next pass repairs
          // because it is no longer in `known`.
          retire(entry.system)
          onLog("serve: catalog ${entry.system} changed in registry $repo — re-publishing")
        }
        val failure = publish(contribution, entry)
        if (failure == null) {
          owned += entry.system
          publishedAs[entry.system] = fingerprint
          if (!registered) onLog("serve: catalog ${entry.system} imported from registry $repo")
        } else {
          publishedAs.remove(entry.system)
          // Left unowned and unlisted-from, so the next pass tries again. An import whose delivery
          // branch hasn't been built yet is the ordinary case here, not an error: the registry
          // entry lands with the PR and the branch appears when the build finishes.
          onLog("serve: catalog ${entry.system} from registry $repo not available yet: $failure")
        }
      }
    }
    // Only withdraw against a pass that actually read something. A registry that 404'd or timed
    // out has said nothing about its catalogs, and treating silence as a retirement would empty
    // the box on the first outage.
    if (!readAny) return
    val gone = synchronized(owned) { owned.filterNot { it in listed } }
    for (system in gone) {
      retire(system)
      owned.remove(system)
      publishedAs.remove(system)
      onLog("serve: catalog $system retired — no longer listed by any catalog registry")
    }
  }

  override fun close() {
    exec.shutdownNow()
  }
}
