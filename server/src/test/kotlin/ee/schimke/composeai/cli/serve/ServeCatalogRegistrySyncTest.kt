package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconciling a running box against its registry projects ([ServeCatalogRegistrySync]) — what a
 * pass publishes, what it retires, and the two things it refuses to retire. Driven a pass at a
 * time; no clock, no network.
 */
class ServeCatalogRegistrySyncTest {

  private val repo = "yschimke/compose-preview-imports"

  /** A one-entry document whose attribution fields are settable, for the re-publish tests. */
  private fun contributionOfEntry(entry: ServeCatalogsConfig.Entry) =
    ServeCatalogRegistry.normalize(repo, ServeCatalogsConfig(catalogs = listOf(entry)))

  private fun contributionOf(vararg systems: String) =
    ServeCatalogRegistry.normalize(
      repo,
      ServeCatalogsConfig(catalogs = systems.map { ServeCatalogsConfig.Entry(system = it) }),
    )

  private class Box {
    val tracked = linkedSetOf<String>()
    val retired = mutableListOf<String>()
    var failWith: String? = null
    /** What an unreadable document reports through `read`'s problem callback. */
    var readProblem: String? = null
  }

  private val nomination = ServeCatalogRegistry.Nomination("yschimke/compose-preview-imports")

  private fun syncOf(
    box: Box,
    document: () -> ServeCatalogRegistry.Contribution?,
  ): ServeCatalogRegistrySync =
    ServeCatalogRegistrySync(
      repos = listOf(nomination),
      read = { _, onProblem ->
        document().also { if (it == null) box.readProblem?.let(onProblem) }
      },
      tracked = { box.tracked.toSet() },
      publish = { _, entry ->
        box.failWith ?: entry.system.also { box.tracked += it }.let { null }
      },
      retire = { system ->
        box.tracked -= system
        box.retired += system
      },
      intervalMillis = 0,
      onLog = {},
    )

  @Test
  fun `a catalog the registry starts listing is published`() {
    val box = Box()
    var doc = contributionOf("a")
    val sync = syncOf(box) { doc }

    sync.syncOnce()
    assertEquals(setOf("a"), box.tracked)

    doc = contributionOf("a", "b")
    sync.syncOnce()
    assertEquals(setOf("a", "b"), box.tracked)
    assertEquals(setOf("a", "b"), sync.ownedSystems())
  }

  @Test
  fun `a catalog the registry stops listing is retired`() {
    val box = Box()
    var doc = contributionOf("a", "b")
    val sync = syncOf(box) { doc }
    sync.syncOnce()

    doc = contributionOf("a")
    sync.syncOnce()

    assertEquals(listOf("b"), box.retired)
    assertEquals(setOf("a"), sync.ownedSystems())
  }

  @Test
  fun `a catalog the sync did not publish is never retired by a registry dropping it`() {
    val box = Box()
    // The operator's own `--catalogs` entry, already serving before any registry was read.
    box.tracked += "compose-m3"
    val sync = syncOf(box) { contributionOf("a") }

    sync.syncOnce()
    sync.syncOnce()

    assertEquals(emptyList(), box.retired)
    assertTrue("compose-m3" in box.tracked)
  }

  @Test
  fun `an unreadable registry retires nothing — silence is not a withdrawal`() {
    val box = Box()
    var readable = true
    val sync = syncOf(box) { if (readable) contributionOf("a") else null }
    sync.syncOnce()
    assertEquals(setOf("a"), box.tracked)

    readable = false
    sync.syncOnce()

    // A 404 or a timeout says nothing about the catalogs; treating it as a retirement would empty
    // the box on the first GitHub hiccup.
    assertEquals(emptyList(), box.retired)
    assertEquals(setOf("a"), box.tracked)
  }

  @Test
  fun `one registry failing while another reads cleanly retires nothing of the failed one`() {
    val box = Box()
    val other = "yschimke/other-imports"
    var first: ServeCatalogRegistry.Contribution? = contributionOf("a")
    val second =
      ServeCatalogRegistry.normalize(
        other,
        ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry(system = "b"))),
      )
    val sync =
      ServeCatalogRegistrySync(
        repos = listOf(nomination, ServeCatalogRegistry.Nomination(other)),
        read = { n, _ -> if (n.repo == repo) first else second },
        tracked = { box.tracked.toSet() },
        publish = { _, entry -> entry.system.also { box.tracked += it }.let { null } },
        retire = { system ->
          box.tracked -= system
          box.retired += system
        },
        intervalMillis = 0,
        onLog = {},
      )
    sync.syncOnce()
    assertEquals(setOf("a", "b"), box.tracked)

    first = null
    sync.syncOnce()
    assertEquals(emptyList(), box.retired)
    assertEquals(setOf("a", "b"), sync.ownedSystems())

    first = contributionOf()
    sync.syncOnce()
    assertEquals(listOf("a"), box.retired)
  }

  @Test
  fun `a listed catalog whose branch is not built yet is retried on the next pass`() {
    val box = Box()
    box.failWith = "could not fetch catalog.json"
    val sync = syncOf(box) { contributionOf("a") }

    sync.syncOnce()
    assertEquals(emptySet(), box.tracked)
    // Unowned, so nothing to withdraw and nothing to skip: the import lands when the build does.
    assertEquals(emptySet(), sync.ownedSystems())

    box.failWith = null
    sync.syncOnce()
    assertEquals(setOf("a"), box.tracked)
    assertEquals(setOf("a"), sync.ownedSystems())
  }

  @Test
  fun `systems adopted from the startup fold-in are the sync's to withdraw`() {
    val box = Box()
    box.tracked += "a"
    val sync = syncOf(box) { contributionOf() }
    sync.adopt(contributionOf("a"))

    sync.syncOnce()

    assertEquals(listOf("a"), box.retired)
  }

  @Test
  fun `an unchanged entry adopted from the startup fold-in is not re-published`() {
    // The restart churn on preview.coo.ee: adopting system names without what they were registered
    // as made every boot-loaded catalog look changed on the first pass, so the sync retired and
    // re-published all of them — and two whose re-publish failed served a 404 until the next pass.
    val box = Box()
    val doc =
      contributionOfEntry(
        ServeCatalogsConfig.Entry(
          system = "joreilly-peopleinspace",
          importedFrom = "joreilly/PeopleInSpace",
        )
      )
    box.tracked += "joreilly-peopleinspace"
    val sync = syncOf(box) { doc }
    sync.adopt(doc)

    sync.syncOnce()
    sync.syncOnce()

    assertEquals(emptyList<String>(), box.retired)
    assertEquals(setOf("joreilly-peopleinspace"), box.tracked)
    assertEquals(setOf("joreilly-peopleinspace"), sync.ownedSystems())
  }

  @Test
  fun `an adopted entry that changed after boot is still re-published`() {
    val box = Box()
    val boot = contributionOfEntry(ServeCatalogsConfig.Entry(system = "a"))
    box.tracked += "a"
    var doc = boot
    val sync = syncOf(box) { doc }
    sync.adopt(boot)

    doc = contributionOfEntry(ServeCatalogsConfig.Entry(system = "a", importedFrom = "o/R"))
    sync.syncOnce()

    assertEquals(listOf("a"), box.retired)
    assertEquals(setOf("a"), box.tracked)
  }

  @Test
  fun `an entry the operator claimed at boot is not adopted`() {
    // The boot de-duplicates first-wins with the operator's config ahead of every registry, so a
    // registry entry for a system the operator names was never registered from the registry.
    // Adopting it would let the first pass retire the operator's catalog and re-point it.
    val box = Box()
    box.tracked += "operator-named"
    val doc = contributionOf("operator-named", "a")
    box.tracked += "a"
    val sync = syncOf(box) { doc }
    sync.adopt(doc, doc.entries.filter { it.system != "operator-named" })

    sync.syncOnce()

    assertEquals(emptyList<String>(), box.retired)
    assertEquals(setOf("a"), sync.ownedSystems())
  }

  @Test
  fun `an entry whose attribution changes is re-published`() {
    // The bug this closes, in the shape it actually occurred. preview.coo.ee registered three
    // imports from a revision of the registry document that carried no importedFrom, then the
    // document gained it — and nothing happened, because the pass skipped every system it already
    // knew. They kept the attribution-free registration until a restart, filed under the wrong
    // heading, with nothing on the box reporting a problem.
    val box = Box()
    var doc = contributionOfEntry(ServeCatalogsConfig.Entry(system = "joreilly-peopleinspace"))
    val sync = syncOf(box) { doc }

    sync.syncOnce()
    assertEquals(setOf("joreilly-peopleinspace"), box.tracked)
    assertEquals(emptyList<String>(), box.retired)

    doc =
      contributionOfEntry(
        ServeCatalogsConfig.Entry(
          system = "joreilly-peopleinspace",
          importedFrom = "joreilly/PeopleInSpace",
        )
      )
    sync.syncOnce()

    // Retired and re-published, so the registration carries the new attribution.
    assertEquals(listOf("joreilly-peopleinspace"), box.retired)
    assertEquals(setOf("joreilly-peopleinspace"), box.tracked)
    assertEquals(setOf("joreilly-peopleinspace"), sync.ownedSystems())
  }

  @Test
  fun `an unchanged entry is left alone`() {
    // The other half: re-publishing every entry every tick would refetch the whole registry set on
    // the refresh cadence, which is the cost this fingerprint exists to avoid.
    val box = Box()
    val entry = ServeCatalogsConfig.Entry(system = "a", importedFrom = "joreilly/PeopleInSpace")
    val sync = syncOf(box) { contributionOfEntry(entry) }

    sync.syncOnce()
    sync.syncOnce()
    sync.syncOnce()

    assertEquals(emptyList<String>(), box.retired)
    assertEquals(setOf("a"), box.tracked)
  }

  @Test
  fun `a catalog this sync does not own is never re-published`() {
    // An operator's catalogs.json entry wins over a registry one by design. A registry correcting
    // its own document must not be able to re-point a catalog the operator named — that would let
    // a publisher silently overrule the box's config.
    val box = Box()
    box.tracked += "operator-named"
    val sync =
      syncOf(box) {
        contributionOfEntry(
          ServeCatalogsConfig.Entry(system = "operator-named", importedFrom = "someone/Else")
        )
      }

    sync.syncOnce()
    sync.syncOnce()

    assertEquals(emptyList<String>(), box.retired)
    assertEquals(emptySet<String>(), sync.ownedSystems())
  }

  @Test
  fun `a failed re-publish is retried on the next pass`() {
    // Retire-then-republish has a window: if the publish fails the catalog is briefly unpublished.
    // The next pass must repair it rather than leaving it gone, which it does because the system is
    // no longer tracked and takes the ordinary publish path.
    val box = Box()
    var doc = contributionOfEntry(ServeCatalogsConfig.Entry(system = "a"))
    val sync = syncOf(box) { doc }
    sync.syncOnce()

    doc = contributionOfEntry(ServeCatalogsConfig.Entry(system = "a", importedFrom = "o/R"))
    box.failWith = "branch not found"
    sync.syncOnce()
    assertEquals(emptySet<String>(), box.tracked)

    box.failWith = null
    sync.syncOnce()
    assertEquals(setOf("a"), box.tracked)
  }

  /** The `/status` row for [nomination], as the server builds it from a boot read of [boot]. */
  private fun statusOf(
    sync: ServeCatalogRegistrySync,
    boot: ServeCatalogRegistry.Contribution?,
    bootProblem: String? = null,
  ) = catalogRegistryStatus(nomination, boot, bootProblem, sync.lastRead(nomination))

  @Test
  fun `a catalog published by a pass after boot appears in the status systems`() {
    // preview.coo.ee: the imports registry grew from 16 catalogs to 23 after boot, the sync served
    // all 23, and /status.json kept reporting the boot 16. `publish-config-to-box.sh --prune` keeps
    // exactly those `systems`, so it DELETEd the seven new ones on every config publish.
    val box = Box()
    val boot = contributionOf("a")
    box.tracked += "a"
    var doc = boot
    val sync = syncOf(box) { doc }
    sync.adopt(boot)

    // Before the first pass the boot read is the answer.
    assertEquals(listOf("a"), statusOf(sync, boot).systems)

    doc = contributionOf("a", "compose-samples-jetsnack")
    sync.syncOnce()

    val status = statusOf(sync, boot)
    assertEquals(setOf("a", "compose-samples-jetsnack"), box.tracked)
    assertEquals(listOf("a", "compose-samples-jetsnack"), status.systems)
    assertEquals(2, status.catalogs)
    assertEquals(null, status.error)
  }

  @Test
  fun `a catalog retired after boot leaves the status systems`() {
    val box = Box()
    val boot = contributionOf("a", "b")
    box.tracked += listOf("a", "b")
    var doc = boot
    val sync = syncOf(box) { doc }
    sync.adopt(boot)

    doc = contributionOf("a")
    sync.syncOnce()

    assertEquals(listOf("b"), box.retired)
    assertEquals(listOf("a"), statusOf(sync, boot).systems)
  }

  @Test
  fun `a nominated entry whose branch is not built yet still counts as the registry's`() {
    // The prune needs "what the registry nominates", not "what this box managed to publish": an
    // entry that is not served yet is not on the box to be pruned, and the moment it is published
    // it must already be covered, so there is no window in which it reads as stale.
    val box = Box()
    box.failWith = "could not fetch catalog.json"
    val sync = syncOf(box) { contributionOf("a") }

    sync.syncOnce()

    assertEquals(emptySet(), sync.ownedSystems())
    assertEquals(listOf("a"), statusOf(sync, boot = null).systems)
  }

  @Test
  fun `a failed re-read reports its error and keeps the last clean document`() {
    val box = Box()
    var readable = true
    val sync = syncOf(box) { if (readable) contributionOf("a", "b") else null }
    sync.syncOnce()

    readable = false
    box.readProblem = "catalog registry yschimke/compose-preview-imports: timed out"
    sync.syncOnce()

    // The sync retired nothing on the failed pass, so both catalogs are still served and still
    // the registry's — dropping them from `systems` here would have the prune delete them.
    val status = statusOf(sync, boot = null)
    assertEquals(listOf("a", "b"), status.systems)
    assertEquals("catalog registry yschimke/compose-preview-imports: timed out", status.error)

    readable = true
    sync.syncOnce()
    assertEquals(null, statusOf(sync, boot = null).error)
  }

  @Test
  fun `a registry unreadable at boot and read by a later pass reports the pass`() {
    val box = Box()
    val sync = syncOf(box) { contributionOf("a") }
    assertEquals("boot failed", statusOf(sync, boot = null, bootProblem = "boot failed").error)

    sync.syncOnce()

    val status = statusOf(sync, boot = null, bootProblem = "boot failed")
    assertEquals(null, status.error)
    assertEquals(listOf("a"), status.systems)
  }
}
