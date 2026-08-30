package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fetching a repository the box has never seen: what git is asked to do, what is reused, and what
 * is left behind when it fails.
 *
 * The git runner is faked, so nothing here reaches the network — what is asserted is the *shape* of
 * the commands, which is where the interesting decisions are (shallow, single-branch, detached) and
 * the one place a mistake would be invisible until a production onboarding took ten minutes.
 */
class ServeSourceCheckoutsTest {

  private val cacheRoot = Files.createTempDirectory("checkouts").toFile().also { it.deleteOnExit() }
  private val calls = mutableListOf<List<String>>()

  /** Repositories the fake remote has; anything else fails to clone the way a private repo does. */
  private val known = mutableSetOf("joreilly/PeopleInSpace")

  private fun runner(): GitRunner = GitRunner { workdir, args ->
    calls += args
    when {
      args.first() == "clone" -> {
        val repo = args[args.size - 2]
        if (known.none { repo.contains(it) }) {
          GitResult(exitCode = 128, stdout = "repository not found")
        } else {
          File(args.last()).apply { mkdirs() }
          File(args.last(), ".git").apply { mkdirs() }
          GitResult(0, "")
        }
      }
      args.first() == "rev-parse" -> GitResult(0, "abc123\n")
      args.first() == "symbolic-ref" -> GitResult(0, "origin/main\n")
      args.first() == "fetch" -> GitResult(0, "")
      args.first() == "checkout" -> GitResult(0, "")
      else -> GitResult(0, "")
    }.also { _ -> workdir.mkdirs() }
  }

  private val checkouts = ServeSourceCheckouts(cacheRoot, git = runner())

  @Test
  fun `a first checkout clones shallowly and a second one only fetches`() {
    val first = checkouts.checkout("joreilly/PeopleInSpace").getOrThrow()

    assertEquals("abc123", first.sha)
    assertEquals("main", first.ref)
    val clone = calls.single { it.first() == "clone" }
    // Depth and single-branch are the difference between an onboarding request that answers and one
    // that spends minutes fetching history nothing will read.
    assertTrue(clone.containsAll(listOf("--depth", "1", "--single-branch")), "$clone")

    calls.clear()
    val second = checkouts.checkout("joreilly/PeopleInSpace").getOrThrow()

    assertEquals(first.dir, second.dir)
    assertTrue(calls.none { it.first() == "clone" }, "$calls")
    // Detached and forced: the tree is the server's scratch space, and the last build left
    // generated files in it that must not block the update.
    val checkout = calls.single { it.first() == "checkout" }
    assertTrue(checkout.containsAll(listOf("--detach", "--force")), "$checkout")
  }

  @Test
  fun `a named ref is cloned as that branch`() {
    checkouts.checkout("joreilly/PeopleInSpace", ref = "develop").getOrThrow()
    val clone = calls.single { it.first() == "clone" }
    assertEquals("develop", clone[clone.indexOf("--branch") + 1])
  }

  @Test
  fun `a repository that cannot be cloned leaves nothing reusable behind`() {
    val result = checkouts.checkout("someone/private")

    assertTrue(result.isFailure)
    // The message has to distinguish the two things a person pasting a URL gets wrong.
    assertTrue(
      result.exceptionOrNull()!!.message!!.contains("is it public, and does that ref exist?"),
      "${result.exceptionOrNull()?.message}",
    )
    // A half-made directory would be taken for a reusable checkout by the next request, and that
    // request would then "fetch" a repository that was never cloned.
    assertFalse(File(cacheRoot, "someone_private").exists())
  }
}
