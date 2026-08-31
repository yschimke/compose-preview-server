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
  }

  private fun syncOf(
    box: Box,
    document: () -> ServeCatalogRegistry.Contribution?,
  ): ServeCatalogRegistrySync =
    ServeCatalogRegistrySync(
      repos = listOf(ServeCatalogRegistry.Nomination("yschimke/compose-preview-imports")),
      read = { document() },
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
    sync.adopt(listOf("a"))

    sync.syncOnce()

    assertEquals(listOf("a"), box.retired)
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
}
