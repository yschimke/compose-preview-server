package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps a running server's catalog set in step with its nominated **registry projects**
 * ([ServeCatalogRegistry]).
 *
 * The startup fold-in reads each registry once, which is enough to serve what was listed at boot
 * and nothing that lands after it — and what lands after it is the entire point. A registry
 * project's whole workflow is "merge the PR and the catalog is imported"; if a merge only takes
 * effect at the next container restart, the reviewer is back to filing a second request against the
 * box, which is the gap [ServeCatalogRegistry] exists to close. So the document is re-read on the
 * same cadence the branch refresher polls at, and the difference is applied:
 * - a system the registry has started listing is fetched and registered, exactly as an admin `POST`
 *   would ([ServeCatalogAdmin.register] is not reused only because a registry entry is *derived*
 *   state and must not be written into the operator's `catalogs.json`, where it would outlive the
 *   registry that asked for it);
 * - a system the registry has stopped listing is retired — but only if **this sync** is what put it
 *   there. A catalog the operator named, or one published through the admin API, is never withdrawn
 *   because a registry stopped mentioning it;
 * - a system whose ENTRY changed — `importedFrom`, `listed`, `group`, `loadPriority` — is
 *   re-published, again only if this sync owns it.
 *
 * That last one was missing, and its absence was silent in the way that costs an afternoon. The
 * pass skipped any system it already knew, so editing a registry document changed nothing about a
 * catalog already registered from it: the entry was re-read every tick and thrown away. On
 * preview.coo.ee three imports kept the `group: imported-projects` they were first registered with
 * — a group that box does not declare, so they fell back to the owner heading and sat under
 * `yschimke repositories` — while the two first listed *after* the document gained `importedFrom`
 * were filed correctly under `joreilly repositories`. Same repository, same branch, same trust; the
 * only difference was which revision of the document each was registered from. Nothing on the box
 * reported a problem, and only a restart would have fixed it.
 *
 * A registry that fails to fetch contributes nothing *that pass* and retires nothing: an
 * unreachable document is not a statement that its catalogs are gone. Only a document that read
 * cleanly and no longer names a system is.
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
        entry.loadPriority?.toString().orEmpty(),
      )
      .joinToString("\u0000")

  private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serve-catalog-registry").apply { isDaemon = true }
  }

  /** Record the systems the startup fold-in already registered from a registry. */
  fun adopt(systems: Collection<String>) {
    owned.addAll(systems)
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
