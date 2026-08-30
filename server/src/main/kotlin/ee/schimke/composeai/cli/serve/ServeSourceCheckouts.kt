package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Shallow checkouts of **foreign** repositories — the ones an operator pastes a URL for.
 *
 * Distinct from [GitWorktrees], which materialises revisions of the *server's own* project out of a
 * repository already on disk and refuses anything outside an operator-configured ref allowlist.
 * That allowlist is the right policy there and cannot express this case at all: there is no local
 * clone to be an ancestor of, and the whole request is "fetch a repository this box has never
 * seen".
 *
 * So the policy is different in kind, and stated rather than inherited: **an admin-token holder
 * named this repository, and the box opted into building foreign code**. Both halves are enforced
 * upstream ([ServeSourceOnboarding], and the route that reaches it); what is enforced *here* is the
 * blast radius — one directory per repository under [cacheRoot], a shallow single-branch fetch, and
 * a default branch unless the caller named a ref.
 *
 * A checkout is reused across requests. Re-onboarding the same repository fetches instead of
 * re-cloning, so the second paste of a URL is cheap and lands on the current head rather than the
 * one that happened to be current the first time.
 */
class ServeSourceCheckouts(
  private val cacheRoot: File,
  private val git: GitRunner = GitWorktrees.RealGitRunner,
  /** Where a repository is cloned from. Injected so tests never reach the network. */
  private val remoteUrl: (String) -> String = { "https://github.com/$it.git" },
  private val onLog: (String) -> Unit = {},
) {

  /** A checkout on disk: where it is, and exactly which commit it holds. */
  data class Checkout(val dir: File, val sha: String, val ref: String)

  /**
   * Check [repo] (`owner/name`) out at [ref], or at its default branch when [ref] is null.
   *
   * Returns the checkout, or a failure whose message names what git said — a missing or private
   * repository and an unknown ref are the two things a person pasting a URL gets wrong, and telling
   * them apart is the difference between "fix your typo" and "this box can't reach GitHub".
   */
  fun checkout(repo: String, ref: String? = null): Result<Checkout> {
    val dir = File(cacheRoot, repo.replace('/', '_'))
    cacheRoot.mkdirs()
    val fresh = !File(dir, ".git").exists()
    if (fresh) {
      // --depth 1: history is not wanted, only a tree to build. A shallow single-branch clone of a
      // large project is the difference between an onboarding request that answers and one that
      // times out.
      val args = buildList {
        add("clone")
        add("--depth")
        add("1")
        add("--single-branch")
        if (ref != null) {
          add("--branch")
          add(ref)
        }
        add(remoteUrl(repo))
        add(dir.absolutePath)
      }
      val clone = git.run(cacheRoot, args)
      if (!clone.ok) {
        // A half-made directory would be treated as a reusable checkout by the next request.
        runCatching { dir.deleteRecursively() }
        return Result.failure(
          CheckoutException(
            "could not clone $repo${ref?.let { " at '$it'" }.orEmpty()} — " +
              "is it public, and does that ref exist? (${clone.stdout.trim().takeLast(400)})"
          )
        )
      }
    } else {
      val target = ref ?: defaultBranch(dir) ?: "HEAD"
      val fetch = git.run(dir, listOf("fetch", "--depth", "1", "origin", target))
      if (!fetch.ok) {
        return Result.failure(
          CheckoutException(
            "could not fetch '$target' from $repo (${fetch.stdout.trim().takeLast(400)})"
          )
        )
      }
      // Detached, and --force: the working tree is the server's own scratch space, and a previous
      // build left generated files in it that must not block the update.
      val checkout = git.run(dir, listOf("checkout", "--detach", "--force", "FETCH_HEAD"))
      if (!checkout.ok) {
        return Result.failure(
          CheckoutException("could not check out '$target' of $repo — ${checkout.stdout.trim()}")
        )
      }
    }
    val sha = git.run(dir, listOf("rev-parse", "HEAD")).stdout.trim()
    if (sha.isEmpty()) {
      return Result.failure(CheckoutException("checkout of $repo produced no commit"))
    }
    val resolved = ref ?: defaultBranch(dir) ?: "HEAD"
    onLog("serve: checked out $repo@$resolved ($sha) into ${dir.absolutePath}")
    return Result.success(Checkout(dir = dir, sha = sha, ref = resolved))
  }

  /** The remote's default branch, read from the clone's own `origin/HEAD`; null when unknown. */
  private fun defaultBranch(dir: File): String? {
    val head = git.run(dir, listOf("symbolic-ref", "--short", "refs/remotes/origin/HEAD"))
    if (!head.ok) return null
    return head.stdout.trim().removePrefix("origin/").takeIf { it.isNotEmpty() }
  }
}

/** A checkout failure carrying the message the HTTP layer shows the caller. */
class CheckoutException(message: String) : Exception(message)
