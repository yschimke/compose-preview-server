package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * **What Compose previews are in a repository nobody has vouched for?** — the pass that answers a
 * pasted GitHub URL by *reading* the project, never by running it.
 *
 * [ServeOnboarding] registers what a repository already *publishes* on a `design-artifacts/`
 * branch, and answers 404 for a project that has never heard of this tool — which is every project
 * the first time, and exactly the case someone pastes a URL for (issue #12). The four multiplatform
 * sample apps this was written against (GalwayBus, BikeShare, ClimateTraceKMP, PeopleInSpace) have
 * no delivery branch, no preview plugin in their build files, and no reason to add one; onboarding
 * them must not require a fork or a pull request upstream.
 *
 * ### Reading is this server's whole half of that
 *
 * Building an arbitrary pasted repository means running its build scripts as the server user. This
 * server deliberately does not do that. The build happens instead on a GitHub Actions runner in the
 * **import staging repository**, whose workflow checks the upstream project out, injects the
 * preview plugin via the CLI's init script, renders, and force-pushes an ordinary
 * `design-artifacts/<slug>` branch. The preview box then serves that branch through the *existing*
 * [ServeOnboarding] path, with no new execution surface and no new serving code — an imported
 * project is an ordinary catalog that happens to have been built somewhere else.
 *
 * So what is left here is the part that has to happen before anyone can write that import down:
 * which modules hold previews, and which of them the plugin could be injected into. That is most of
 * the perceived magic of pasting a URL, it is safe on a repository nobody has vouched for, and its
 * output is what drafts the import.
 *
 * ### What it costs and what it doesn't
 *
 * A scan is a shallow clone ([ServeSourceCheckouts]) plus a text read ([ServeSourceScan]). It
 * executes nothing from the repository, registers nothing, and grants nothing: a scanned repository
 * is not served, not trusted, and not remembered beyond its cached checkout.
 */
class ServeSourceOnboarding(
  private val checkouts: ServeSourceCheckouts,
  private val scanner: (File) -> ServeSourceScanResult = { ServeSourceScan.scan(it) },
  private val onLog: (String) -> Unit = {},
) {

  /** The outcome of a scan. */
  sealed interface ScanResult {
    /** Not a GitHub project URL — a 400. */
    data class Invalid(val reason: String) : ScanResult

    /** The repository couldn't be cloned: missing, private, bad ref, or git trouble — a 502. */
    data class Unreachable(val repo: String, val reason: String) : ScanResult

    /**
     * The checkout was read. [modules] may be empty or hold nothing previewable; [notes] says why.
     */
    data class Ok(
      val repo: String,
      val ref: String,
      val sha: String,
      val modules: List<ServeSourceModule>,
      val notes: List<String>,
    ) : ScanResult {
      /**
       * The modules an import would name. "Buildable" is a well-founded guess, not a promise — the
       * staging repository's workflow is what finds out for certain.
       */
      val buildable: List<ServeSourceModule>
        get() = modules.filter { it.buildable }
    }
  }

  /**
   * Clone [rawUrl] shallowly and report the Compose modules in it. Executes nothing in the
   * checkout.
   */
  fun scan(rawUrl: String, ref: String? = null): ScanResult {
    val project =
      GithubProject.parse(rawUrl)
        ?: return ScanResult.Invalid("'$rawUrl' is not a GitHub project URL")
    if (ref != null && !isSafeRef(ref)) return ScanResult.Invalid("'$ref' is not a usable git ref")
    val repo = project.slug
    val checkout =
      checkouts.checkout(repo, ref).getOrElse {
        return ScanResult.Unreachable(repo, it.message ?: "could not check out $repo")
      }
    val scanned = runCatching {
      scanner(checkout.dir)
    }
      .getOrElse {
        return ScanResult.Unreachable(repo, "could not read the checkout: ${it.message}")
      }
    onLog(
      "serve: scanned $repo@${checkout.ref} — ${scanned.buildable.size}/${scanned.modules.size} " +
        "module(s) hold previewable modules"
    )
    return ScanResult.Ok(
      repo = repo,
      ref = checkout.ref,
      sha = checkout.sha,
      modules = scanned.modules,
      notes = scanned.notes,
    )
  }

  /**
   * Refs that may be handed to `git clone --branch` / `git fetch`. Conservative on purpose: a ref
   * reaches a command line, and a value that starts with `-` is an argument, not a branch.
   */
  private fun isSafeRef(ref: String): Boolean =
    ref.isNotBlank() &&
      ref.length <= MAX_REF_LENGTH &&
      !ref.startsWith("-") &&
      ref.all { it.isLetterOrDigit() || it in "._/-" }

  private companion object {
    const val MAX_REF_LENGTH = 200
  }
}
