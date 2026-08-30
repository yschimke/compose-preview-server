package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scanning a repository that publishes nothing: what the pass answers, and — the part worth most of
 * the attention — that it is only ever a read.
 *
 * The checkout is faked, so nothing here reaches the network. There is deliberately no build lane
 * to test: an imported project is built on a runner in the import staging repository, and this
 * server has no route that executes a pasted repository at all.
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

  /** Every git command the scan ran, so a test can assert that none of them was a build. */
  private val commands = mutableListOf<List<String>>()

  private val git = GitRunner { _, args ->
    commands += args
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

  private fun onboarding() =
    ServeSourceOnboarding(
      checkouts = ServeSourceCheckouts(cacheRoot, git = git),
      scanner = { dir -> ServeSourceScanResult(repos[repoOf(dir)].orEmpty()) },
    )

  private fun repoOf(dir: File) = dir.name.replaceFirst('_', '/')

  @Test
  fun `a scan reports what is in the repository and runs nothing from it`() {
    val result = onboarding().scan("https://github.com/joreilly/PeopleInSpace")

    val ok = result as ServeSourceOnboarding.ScanResult.Ok
    assertEquals("joreilly/PeopleInSpace", ok.repo)
    assertEquals("main", ok.ref)
    assertEquals("sha-1", ok.sha)
    // The module with previews is what an import would name; the one without rides along with its
    // reason, so the answer is the shape of the repository rather than a filtered list.
    assertEquals(listOf("shared"), ok.buildable.map { it.gradlePath })
    assertEquals(
      "no @Preview functions under src/",
      ok.modules.single { it.gradlePath == "androidApp" }.skipReason,
    )

    // The whole safety claim of this lane, asserted rather than assumed: git fetched a tree and
    // nothing else ever ran. A build would have to appear here first.
    assertEquals(setOf("clone", "rev-parse", "symbolic-ref"), commands.map { it.first() }.toSet())
  }

  @Test
  fun `a repository that cannot be read is upstream trouble, and a non-GitHub URL is the caller's`() {
    assertTrue(onboarding().scan("someone/missing") is ServeSourceOnboarding.ScanResult.Unreachable)
    assertTrue(
      onboarding().scan("https://gitlab.com/a/b") is ServeSourceOnboarding.ScanResult.Invalid
    )
  }

  @Test
  fun `a ref that could be mistaken for a git argument is refused`() {
    // The ref reaches a command line, so a value starting with `-` is an argument, not a branch.
    assertTrue(
      onboarding().scan("joreilly/PeopleInSpace", ref = "--upload-pack=touch /tmp/x")
        is ServeSourceOnboarding.ScanResult.Invalid
    )
    assertTrue(commands.isEmpty(), "$commands")
  }
}
