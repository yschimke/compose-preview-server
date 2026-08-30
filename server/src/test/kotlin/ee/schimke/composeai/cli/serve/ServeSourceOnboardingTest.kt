package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Onboarding a repository that publishes nothing: what the two passes answer, and — the part worth
 * most of the attention — what the build pass refuses to do.
 *
 * The checkout and the builder are both faked, so the orchestration is exercised without a network
 * or a Gradle build; the build lane runs inline so a test asserts a finished job rather than a
 * timing window.
 */
class ServeSourceOnboardingTest {

  private val cacheRoot = Files.createTempDirectory("onboard").toFile().also { it.deleteOnExit() }

  /** Repositories the fake remote has, and the modules each turns out to hold. */
  private val repos =
    mutableMapOf(
      "joreilly/PeopleInSpace" to
        listOf(module("shared", previews = 3), module("androidApp", previews = 0))
    )

  private fun module(path: String, previews: Int) =
    ServeSourceModule(
      gradlePath = path,
      relativePath = path,
      previewCount = previews,
      previewFunctions = (1..previews).map { "Preview$it" },
      hostPlugins = listOf("org.jetbrains.compose"),
      pluginPreApplied = false,
      buildable = previews > 0,
      skipReason = if (previews > 0) null else "no @Preview functions under src/",
    )

  private val git = GitRunner { _, args ->
    when (args.first()) {
      "clone" -> {
        val repo = args[args.size - 2].substringAfter("github.com/").removeSuffix(".git")
        if (repo in repos) {
          File(args.last(), ".git").apply { mkdirs() }
          GitResult(0, "")
        } else {
          GitResult(128, "not found")
        }
      }
      "rev-parse" -> GitResult(0, "sha-1\n")
      "symbolic-ref" -> GitResult(0, "origin/main\n")
      else -> GitResult(0, "")
    }
  }

  private val checkouts = ServeSourceCheckouts(cacheRoot, git = git)

  /** Modules whose fake build fails, so the partial-success path is reachable. */
  private val unbuildable = mutableSetOf<String>()

  private val builder = RevisionBuilder { worktreeDir, module, checked ->
    // The audit marker at the exec point: a build that isn't asserted to have cleared policy is a
    // bug, and the fake is the cheapest place to notice one.
    assertTrue(checked, "a foreign build must be marked security-checked")
    if (module.gradlePath in unbuildable) {
      null
    } else {
      val moduleDir = File(worktreeDir, module.relativePath).apply { mkdirs() }
      BuiltRevision(
        moduleDir = moduleDir,
        descriptor = File(moduleDir, "daemon-launch.json").apply { writeText("{}") },
        previews = listOf(ServePreview(id = "P1", label = "P1")),
      )
    }
  }

  private val registered = LinkedHashMap<String, ServeSessionState>()

  private fun onboarding(builder: RevisionBuilder? = this.builder) =
    ServeSourceOnboarding(
      checkouts = checkouts,
      builder = builder,
      register = { id, state -> registered[id] = state },
      isTaken = { it in registered },
      scanner = { dir -> ServeSourceScanResult(repos[repoOf(dir)].orEmpty()) },
      // Inline, so the job is finished by the time startBuild returns and the test asserts an
      // outcome rather than polling one.
      executor = Executor { it.run() },
    )

  private fun repoOf(dir: File) = dir.name.replaceFirst('_', '/')

  @Test
  fun `a scan reports what is in the repository without building anything`() {
    val result = onboarding(builder = null).scan("https://github.com/joreilly/PeopleInSpace")

    val ok = result as ServeSourceOnboarding.ScanResult.Ok
    assertEquals("joreilly/PeopleInSpace", ok.repo)
    assertEquals("main", ok.ref)
    assertEquals(listOf("shared"), ok.buildable.map { it.gradlePath })
    // Nothing was registered: a scan is a read of the checkout, and a box with no build lane can
    // still answer it. That is the half of the feature every box gets.
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `a box that did not opt in has no build path at all`() {
    val start = onboarding(builder = null).startBuild("joreilly/PeopleInSpace")

    val unavailable = start as ServeSourceOnboarding.BuildStart.Unavailable
    // The message names the switch, because this is a deployment decision and the operator is the
    // only one who can change it.
    assertTrue(unavailable.reason.contains("--onboard-build"), unavailable.reason)
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `building serves each previewable module as its own session`() {
    val start = onboarding().startBuild("https://github.com/joreilly/PeopleInSpace")

    val job = (start as ServeSourceOnboarding.BuildStart.Started).job
    assertEquals("joreilly/PeopleInSpace", job.repo)

    // The module with previews built and is serving; the one without was passed over with its
    // reason, not attempted and not silently dropped.
    val built = job.modules.single { it.gradlePath == "shared" }
    assertEquals(ServeSourceOnboarding.BUILT, built.status)
    assertEquals("joreilly-peopleinspace", built.sessionId)
    assertEquals(setOf("joreilly-peopleinspace"), registered.keys)
    // An ordinary project session: the label says where it came from, and nothing marks it trusted.
    assertEquals(
      "joreilly/PeopleInSpace:shared@main",
      registered.getValue("joreilly-peopleinspace").label,
    )
    assertEquals(ServeSourceOnboarding.SUCCEEDED, job.status)
  }

  @Test
  fun `a repository with nothing previewable is a finding, not a job`() {
    repos["someone/backend"] = listOf(module("server", previews = 0))

    val start = onboarding().startBuild("someone/backend")

    // No job is created: an empty job born failed reads as "the build broke", when what happened is
    // that this repository has no previews to build.
    val nothing = start as ServeSourceOnboarding.BuildStart.NothingToBuild
    assertEquals("someone/backend", nothing.repo)
    assertTrue(
      nothing.notes.any { it.contains("holds buildable previews") },
      "${nothing.notes}",
    )
    // The modules that WERE found ride along with their reasons, so the answer is "here is what is
    // in your repository and why none of it previews" rather than a bare no.
    assertEquals(
      "no @Preview functions under src/",
      nothing.modules.single { it.gradlePath == "server" }.skipReason,
    )
    assertTrue(registered.isEmpty())
  }

  @Test
  fun `a repository that cannot be read fails the same way for both passes`() {
    // One mapping of one failure: the caller's 502 must not depend on which route it came in by.
    val scan = onboarding().scan("someone/missing")
    val build = onboarding().startBuild("someone/missing")

    assertTrue(scan is ServeSourceOnboarding.ScanResult.Unreachable)
    assertTrue(
      (build as ServeSourceOnboarding.BuildStart.Rejected).scan
        is ServeSourceOnboarding.ScanResult.Unreachable
    )
    assertTrue(
      onboarding().scan("https://gitlab.com/a/b") is ServeSourceOnboarding.ScanResult.Invalid
    )
  }

  @Test
  fun `a module whose build fails does not sink the ones that worked`() {
    repos["joreilly/BikeShare"] = listOf(module("shared", previews = 2), module("ui", previews = 1))
    unbuildable += "ui"

    val job =
      (onboarding().startBuild("joreilly/BikeShare") as ServeSourceOnboarding.BuildStart.Started)
        .job

    assertEquals(ServeSourceOnboarding.PARTIAL, job.status)
    assertEquals(listOf("joreilly-bikeshare"), job.served)
    val failed = job.modules.single { it.gradlePath == "ui" }
    assertEquals(ServeSourceOnboarding.FAILED, failed.status)
    assertNull(failed.sessionId)
    assertTrue(
      failed.detail!!.contains("Gradle build or preview discovery failed"),
      "${failed.detail}",
    )
  }

  @Test
  fun `two modules of one repository do not claim the same session id`() {
    repos["joreilly/GalwayBus"] =
      listOf(module("shared", previews = 1), module("app", previews = 1))

    val job =
      (onboarding().startBuild("joreilly/GalwayBus") as ServeSourceOnboarding.BuildStart.Started)
        .job

    val ids = job.modules.mapNotNull { it.sessionId }
    assertEquals(2, ids.size)
    assertEquals(ids.size, ids.toSet().size)
    // The second falls back to a module-qualified id rather than replacing the first — a session
    // someone may already be browsing.
    assertTrue(ids.contains("joreilly-galwaybus"), "$ids")
    assertTrue(ids.any { it.endsWith("-app") || it.endsWith("-shared") }, "$ids")
  }

  @Test
  fun `naming a module that is not in the repository says so`() {
    val job =
      (onboarding().startBuild("joreilly/PeopleInSpace", modules = listOf(":nope", "shared"))
          as ServeSourceOnboarding.BuildStart.Started)
        .job

    val missing = job.modules.single { it.gradlePath == "nope" }
    assertEquals(ServeSourceOnboarding.SKIPPED, missing.status)
    assertEquals("no such module in this repository", missing.detail)
    // The typo didn't stop the module that was spelled correctly.
    assertEquals(
      ServeSourceOnboarding.BUILT,
      job.modules.single { it.gradlePath == "shared" }.status,
    )
    assertFalse(registered.isEmpty())
  }

  @Test
  fun `a ref that could be mistaken for a git argument is refused`() {
    // The ref reaches a command line, so a value starting with `-` is an argument, not a branch.
    assertTrue(
      onboarding().scan("joreilly/PeopleInSpace", ref = "--upload-pack=touch /tmp/x")
        is ServeSourceOnboarding.ScanResult.Invalid
    )
  }
}
