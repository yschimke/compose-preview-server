package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * One-step GitHub project onboarding: paste a repository URL, get every catalog it already
 * delivers.
 *
 * The branch listing is stubbed — what's exercised is the whole of the flow that isn't `git`: which
 * spellings of a project URL resolve to the same repository, which refs become catalog ids, that
 * each one is published through the ordinary [ServeCatalogAdmin] path (so it lands in the tracker
 * AND in `catalogs.json`), and that the failures a repository can hand back stay per-catalog
 * instead of collapsing into one verdict.
 */
class ServeOnboardingTest {

  private val fs = FakeFileSystem()

  private val group = ServeCatalogsConfig.Group("ds", "Design Systems", "design system(s)")

  /**
   * The operator's config, with the front-page section already declared — as a box that publishes
   * one has. It matters that this is on disk rather than only seeded into the administrator: the
   * administrator refreshes its group table from the file on every write, so a section that exists
   * only in memory would survive exactly one registration.
   */
  private val configFile =
    ServeCatalogsConfigFile("/config/catalogs.json".toPath(), fs).also {
      it.save(ServeCatalogsConfig(groups = listOf(group)))
    }

  private val tracker = CatalogLoadTracker(emptyList())

  /** Systems the stubbed fetch refuses, so the upstream-failure path is reachable. */
  private val unfetchable = mutableSetOf<String>()

  private val loaded = mutableListOf<Pair<String, String>>()

  private val admin =
    ServeCatalogAdmin(
      tracker = tracker,
      defaultRepo = "yschimke/compose-ai-tools",
      branchPrefix = "design-artifacts/",
      configFile = configFile,
      groups = listOf(group),
      load = { system, repo ->
        if (system in unfetchable) {
          "branch not found"
        } else {
          loaded += system to repo
          tracker.recordSuccess(system)
          null
        }
      },
      unload = {},
      onLog = {},
    )

  private fun onboarding(vararg branches: String, readable: Boolean = true) =
    ServeOnboarding(
      admin = admin,
      branchPrefix = "design-artifacts/",
      listDeliveryBranches = { if (readable) branches.toList() else null },
      onLog = {},
    )

  @Test
  fun `onboards every delivery branch the repository publishes`() {
    val result =
      onboarding(
          "main",
          "design-artifacts/compose-m3",
          "design-artifacts/cadence",
          "gh-pages",
        )
        .onboard("https://github.com/yschimke/cadence", group = "ds")

    val ok = assertIs<ServeOnboarding.Result.Ok>(result)
    assertEquals("yschimke/cadence", ok.repo)
    // Sorted, so the response and the log read the same way twice running.
    assertEquals(listOf("cadence", "compose-m3"), ok.catalogs.map { it.system })
    assertTrue(ok.catalogs.all { it.status == ServeOnboarding.PUBLISHED }, "$ok")
    assertEquals(
      listOf("cadence" to "yschimke/cadence", "compose-m3" to "yschimke/cadence"),
      loaded.sortedBy { it.first },
    )
    // Published through the ordinary administrator, so it is an ordinary catalog: tracked, and
    // written back to catalogs.json so it survives a restart.
    assertEquals(
      setOf("cadence", "compose-m3"),
      tracker.snapshot().map { it.config.system }.toSet(),
    )
    val persisted = configFile.load()
    assertEquals(
      setOf("cadence", "compose-m3"),
      persisted.catalogs.map { it.system }.toSet(),
    )
    assertTrue(persisted.catalogs.all { it.repo == "yschimke/cadence" && it.group == "ds" })
  }

  @Test
  fun `non-delivery branches are ignored entirely`() {
    val result = onboarding("main", "release/2.0", "design-artifacts/app").onboard("owner/repo")

    val ok = assertIs<ServeOnboarding.Result.Ok>(result)
    assertEquals(listOf("app"), ok.catalogs.map { it.system })
  }

  @Test
  fun `re-onboarding the same project is idempotent`() {
    val flow = onboarding("design-artifacts/app")
    assertIs<ServeOnboarding.Result.Ok>(flow.onboard("owner/repo"))
    loaded.clear()

    val again = assertIs<ServeOnboarding.Result.Ok>(flow.onboard("owner/repo"))
    assertEquals(
      listOf(ServeOnboarding.ALREADY_PUBLISHED),
      again.catalogs.map { it.status },
    )
    assertTrue(again.catalogs.single().served)
    // Idempotent means idempotent: nothing was re-fetched.
    assertTrue(loaded.isEmpty())
  }

  @Test
  fun `one unfetchable branch does not hide the catalogs that worked`() {
    unfetchable += "broken"

    val result =
      onboarding("design-artifacts/broken", "design-artifacts/good").onboard("owner/repo")

    val ok = assertIs<ServeOnboarding.Result.Ok>(result)
    assertEquals(
      mapOf("broken" to ServeOnboarding.FAILED, "good" to ServeOnboarding.PUBLISHED),
      ok.catalogs.associate { it.system to it.status },
    )
    assertEquals(listOf("good"), ok.served.map { it.system })
    assertEquals("branch not found", ok.catalogs.first { it.system == "broken" }.detail)
    // The failed one left nothing behind — the administrator retires what it couldn't fetch.
    assertEquals(listOf("good"), tracker.snapshot().map { it.config.system })
  }

  @Test
  fun `a branch naming a built-in route is refused against its own id`() {
    val result =
      onboarding("design-artifacts/status.json", "design-artifacts/good").onboard("owner/repo")

    val ok = assertIs<ServeOnboarding.Result.Ok>(result)
    assertEquals(
      mapOf("status.json" to ServeOnboarding.INVALID, "good" to ServeOnboarding.PUBLISHED),
      ok.catalogs.associate { it.system to it.status },
    )
  }

  @Test
  fun `a repository that publishes nothing is not an error, it is empty`() {
    val result = onboarding("main", "docs").onboard("owner/repo")

    val empty = assertIs<ServeOnboarding.Result.Empty>(result)
    assertEquals("owner/repo", empty.repo)
    assertEquals("design-artifacts/", empty.branchPrefix)
  }

  @Test
  fun `a repository that cannot be read is upstream trouble, not an empty one`() {
    val result = onboarding(readable = false).onboard("https://github.com/owner/gone")

    val unreachable = assertIs<ServeOnboarding.Result.Unreachable>(result)
    assertEquals("owner/gone", unreachable.repo)
    assertTrue(unreachable.reason.contains("owner/gone"))
  }

  @Test
  fun `a listing that throws is reported rather than propagated`() {
    val flow =
      ServeOnboarding(
        admin = admin,
        branchPrefix = "design-artifacts/",
        listDeliveryBranches = { error("git exploded") },
        onLog = {},
      )

    assertIs<ServeOnboarding.Result.Unreachable>(flow.onboard("owner/repo"))
  }

  @Test
  fun `unlisted onboarding keeps the catalogs off the front door`() {
    val result = onboarding("design-artifacts/app").onboard("owner/repo", listed = false)

    assertIs<ServeOnboarding.Result.Ok>(result)
    assertEquals(false, tracker.snapshot().single().config.listed)
    assertEquals(false, configFile.load().catalogs.single().listed)
  }

  @Test
  fun `every spelling of a project URL names the same repository`() {
    val spellings =
      listOf(
        "https://github.com/yschimke/cadence",
        "http://github.com/yschimke/cadence",
        "https://www.github.com/yschimke/cadence/",
        "https://github.com/yschimke/cadence.git",
        "git@github.com:yschimke/cadence.git",
        "ssh://git@github.com/yschimke/cadence",
        "github.com/yschimke/cadence",
        "yschimke/cadence",
        // Wherever the person happened to be reading when they decided to onboard.
        "https://github.com/yschimke/cadence/tree/design-artifacts/cadence",
        "https://github.com/yschimke/cadence?tab=readme-ov-file",
        "https://github.com/yschimke/cadence#readme",
        "  https://github.com/yschimke/cadence  ",
      )

    for (spelling in spellings) {
      assertEquals(
        GithubProject("yschimke", "cadence"),
        GithubProject.parse(spelling),
        "parsing '$spelling'",
      )
    }
  }

  @Test
  fun `a repository dot in the name survives, a trailing dot-git does not`() {
    assertEquals(GithubProject("owner", "some.repo"), GithubProject.parse("owner/some.repo"))
    assertEquals(
      GithubProject("owner", "some.repo"),
      GithubProject.parse("https://github.com/owner/some.repo.git"),
    )
  }

  @Test
  fun `what is not a GitHub project is refused rather than half-accepted`() {
    // A same-named project on another forge must never be onboarded as the GitHub one.
    assertNull(GithubProject.parse("https://gitlab.com/yschimke/cadence"))
    assertNull(GithubProject.parse("https://example.org/yschimke/cadence"))
    assertNull(GithubProject.parse("https://github.com/yschimke"))
    assertNull(GithubProject.parse("yschimke"))
    assertNull(GithubProject.parse(""))
    assertNull(GithubProject.parse("   "))
    assertNull(GithubProject.parse("owner/re po"))
  }

  @Test
  fun `an unparseable URL never reaches the branch listing`() {
    var asked = false
    val flow =
      ServeOnboarding(
        admin = admin,
        branchPrefix = "design-artifacts/",
        listDeliveryBranches = {
          asked = true
          emptyList()
        },
        onLog = {},
      )

    val invalid = assertIs<ServeOnboarding.Result.Invalid>(flow.onboard("https://gitlab.com/a/b"))
    assertTrue(invalid.reason.contains("gitlab.com"))
    assertTrue(!asked)
  }
}
